package com.abhishek.collage.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.abhishek.collage.collage.CollageComposer
import com.abhishek.collage.model.Appearance
import com.abhishek.collage.model.FaceObservation
import com.abhishek.collage.model.Person
import com.abhishek.collage.model.ProcessingState.Stage
import com.abhishek.collage.pipeline.math.VectorMath

/**
 * Wires every pipeline stage together end-to-end: extract -> detect ->
 * embed -> track appearances -> cluster identities -> pick best shot ->
 * compose collage. This is the only class the ViewModel talks to.
 */
class PipelineOrchestrator(context: Context) {

    companion object {
        private const val TAG = "CollagePipeline"
    }

    private val frameExtractor = FrameExtractor(context)
    private val faceDetector = FaceDetectorStage()
    private val faceEmbedder = FaceEmbedder(context)
    private val appearanceTracker = AppearanceTracker()
    private val identityClusterer = IdentityClusterer()

    data class PipelineResult(val people: List<Person>, val collage: Bitmap)

    private data class DetectedFace(
        val frameTimestampMs: Long,
        val raw: FaceDetectorStage.RawFace,
        /** Loose crop kept for the collage tile (assignment: don't crop tight). */
        val collageCrop: Bitmap,
        /** 112x112 eye-aligned crop -- the only thing the embedder should ever see. */
        val alignedCrop: Bitmap,
        val sharpness: Float,
        val touchesEdge: Boolean
    )

    suspend fun process(
        videoUri: Uri,
        onProgress: (Stage, Float) -> Unit
    ): PipelineResult {
        check(faceEmbedder.isModelLoaded) {
            "Embedding model not found. Add app/src/main/assets/${FaceEmbedder.MODEL_ASSET} -- see README."
        }

        val frames = frameExtractor.extract(videoUri) { onProgress(Stage.EXTRACTING, it) }
        check(frames.isNotEmpty()) { "Could not read any frames from the selected video." }
        Log.d(TAG, "frames extracted: ${frames.size}")

        // Phase A: detection (cheap per-frame quality metrics computed here too).
        val perFrameDetected = frames.mapIndexed { index, frame ->
            val rawFaces = faceDetector.detect(frame.bitmap)
            val detected = rawFaces.map { raw ->
                val collageCrop = FaceCropUtils.cropGenerous(frame.bitmap, raw.boundingBox)
                val alignedCrop = FaceAligner.align(
                    frame.bitmap,
                    raw.boundingBox,
                    raw.leftEye,
                    raw.rightEye
                )
                DetectedFace(
                    frameTimestampMs = frame.timestampMs,
                    raw = raw,
                    collageCrop = collageCrop,
                    alignedCrop = alignedCrop,
                    // Sharpness is judged on the actual face, not the padded tile.
                    sharpness = ImageQuality.laplacianVariance(alignedCrop),
                    touchesEdge = FaceCropUtils.touchesFrameEdge(frame.bitmap, raw.boundingBox)
                )
            }
            onProgress(Stage.DETECTING, (index + 1) / frames.size.toFloat())
            detected
        }
        val rawFaceCount = perFrameDetected.sumOf { it.size }
        val framesWithAFace = perFrameDetected.count { it.isNotEmpty() }
        Log.d(
            TAG,
            "detection: $rawFaceCount raw face detections across $framesWithAFace/${frames.size} frames"
        )
        if (rawFaceCount == 0) {
            Log.w(TAG, "ML Kit found zero faces in any sampled frame -- check video orientation/lighting, not thresholds")
        }

        // Phase B: embedding. Frame timestamps are carried alongside even for
        // frames with zero detections, so the tracker always knows real
        // elapsed time between samples (needed for correct gap-closing).
        val totalFaces = perFrameDetected.sumOf { it.size }
        var embeddedCount = 0
        val perFrameFaces: List<AppearanceTracker.FrameFaces> = frames.mapIndexed { index, frame ->
            val observations = perFrameDetected[index].mapNotNull { d ->
                val embedding = faceEmbedder.embed(d.alignedCrop)
                // The aligned crop exists only to be embedded; free it immediately
                // rather than holding one per detection for the whole run.
                d.alignedCrop.recycle()
                embeddedCount++
                if (totalFaces > 0) onProgress(Stage.EMBEDDING, embeddedCount / totalFaces.toFloat())
                embedding?.let {
                    FaceObservation(
                        timestampMs = d.frameTimestampMs,
                        boundingBox = d.raw.boundingBox,
                        eulerY = d.raw.eulerY,
                        eulerZ = d.raw.eulerZ,
                        leftEyeOpenProb = d.raw.leftEyeOpenProb,
                        rightEyeOpenProb = d.raw.rightEyeOpenProb,
                        smilingProb = d.raw.smilingProb,
                        embedding = it,
                        generousCrop = d.collageCrop,
                        sharpness = d.sharpness,
                        touchesFrameEdge = d.touchesEdge
                    )
                }
            }
            AppearanceTracker.FrameFaces(frame.timestampMs, observations)
        }
        if (totalFaces == 0) onProgress(Stage.EMBEDDING, 1f)

        // Phase C: within-video appearance tracking.
        val appearances = appearanceTracker.track(perFrameFaces)
        onProgress(Stage.TRACKING, 1f)
        Log.d(TAG, "appearances (tracks) found: ${appearances.size}")
        for ((i, a) in appearances.withIndex()) {
            Log.d(TAG, "  appearance[$i]: ${a.startMs}-${a.endMs}ms, ${a.observations.size} observations")
        }
        logPairwiseSimilarity(appearances, identityClusterer.similarityThreshold)

        // Phase D: cross-appearance identity clustering.
        val clusters = identityClusterer.cluster(appearances)
        onProgress(Stage.CLUSTERING, 1f)
        Log.d(TAG, "people (clusters) found: ${clusters.size}, sizes=${clusters.map { it.appearances.size }}")

        // Phase E: representative shot per person.
        val people = clusters.mapIndexedNotNull { index, cluster ->
            val allObservations = cluster.appearances.flatMap { it.observations }
            val best = QualityScorer.pickBest(allObservations) ?: return@mapIndexedNotNull null
            Person(
                displayIndex = index + 1,
                appearanceCount = cluster.appearances.size,
                bestShot = best.observation.generousCrop,
                bestScore = best.score
            )
        }
        onProgress(Stage.SCORING, 1f)

        // Phase F: collage.
        val collage = CollageComposer.compose(people)
        onProgress(Stage.COMPOSING, 1f)

        return PipelineResult(people, collage)
    }

    fun close() {
        faceDetector.close()
        faceEmbedder.close()
    }

    /**
     * Logs the spread of cosine similarity between every pair of appearances'
     * mean embeddings, and how many pairs currently clear the clustering
     * threshold. This is the fastest way to tell over-merging (too many pairs
     * above threshold -- lower it) apart from under-merging (too few -- raise
     * it, or the embedding model/crop isn't discriminative enough) on a real
     * device, without guessing. Mirrors the diagnostic used to pick the
     * default threshold in simulation (see README).
     */
    private fun logPairwiseSimilarity(appearances: List<Appearance>, threshold: Float) {
        if (appearances.size < 2) {
            Log.d(TAG, "only ${appearances.size} appearance(s) -- nothing to compare")
            return
        }
        val sims = mutableListOf<Float>()
        for (i in appearances.indices) {
            for (j in i + 1 until appearances.size) {
                sims.add(VectorMath.cosineSim(appearances[i].meanEmbedding, appearances[j].meanEmbedding))
            }
        }
        val aboveThreshold = sims.count { it > threshold }
        Log.d(
            TAG,
            "appearance-pair similarity: n=${sims.size} min=${sims.min()} " +
                "avg=${sims.average()} max=${sims.max()} " +
                "pairs_above_threshold($threshold)=$aboveThreshold/${sims.size}"
        )
    }
}
