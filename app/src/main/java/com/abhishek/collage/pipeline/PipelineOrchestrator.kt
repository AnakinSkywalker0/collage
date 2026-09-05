package com.abhishek.collage.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.abhishek.collage.collage.CollageComposer
import com.abhishek.collage.model.FaceObservation
import com.abhishek.collage.model.Person
import com.abhishek.collage.model.ProcessingState.Stage
import com.abhishek.collage.model.Tracklet
import com.abhishek.collage.pipeline.math.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wires every pipeline stage together end-to-end: extract -> detect -> embed ->
 * build tracklets -> cluster identities -> split each person's timeline into
 * appearances -> pick best shot -> compose collage. This is the only class the
 * ViewModel talks to.
 *
 * Note the order of the middle three stages: identity is resolved BEFORE
 * appearances are counted. Counting a tracker's output directly would make the
 * count only as good as frame-to-frame tracking, which is a spatial signal and
 * breaks down at cuts. Clustering first, then splitting each person's own
 * timeline on temporal gaps, matches the brief's definition of an appearance
 * and keeps a tracking slip from turning into a wrong count.
 *
 * The whole body runs on [Dispatchers.Default] regardless of the caller's
 * dispatcher, so cropping, alignment, scoring, and collage rendering never
 * block the main thread (the assignment's responsiveness requirement).
 * FrameExtractor/FaceDetectorStage/FaceEmbedder additionally confine their
 * own heavy work to IO/Default internally.
 */
class PipelineOrchestrator(context: Context) {

    companion object {
        private const val TAG = "CollagePipeline"
    }

    private val frameExtractor = FrameExtractor(context)
    private val faceDetector = FaceDetectorStage()
    private val faceEmbedder = FaceEmbedder(context)
    private val trackletBuilder = TrackletBuilder()
    private val identityClusterer = IdentityClusterer()

    /**
     * Longest absence that still counts as one continuous appearance. Sized at
     * ~3 sample intervals so a couple of consecutive missed detections (a blink,
     * a motion-blurred frame) don't split one segment in two, while a real cut
     * away and back does.
     */
    private val appearanceGapMs = 3 * FrameExtractor.SAMPLE_INTERVAL_MS

    /**
     * An appearance seen in only one sampled frame is a detector flicker, not
     * something that "became clearly visible" -- the brief's wording implies
     * persistence.
     */
    private val minObservationsPerAppearance = 2

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
    ): PipelineResult = withContext(Dispatchers.Default) {
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
                // Other faces sharing this exact frame -- passed through so the
                // generous collage crop backs off before it bleeds into a
                // neighbour's face (see FaceCropUtils.expandedRect).
                val otherBoxes = rawFaces.filter { it !== raw }.map { it.boundingBox }
                val collageCrop = FaceCropUtils.cropGenerous(frame.bitmap, raw.boundingBox, otherFacesInFrame = otherBoxes)
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
        // Source frames are no longer needed -- only the crops derived from
        // them. Free them now instead of holding ~150 full frames for the
        // rest of the run (OOM risk on low-end devices).
        for (frame in frames) frame.bitmap.recycle()

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
        var embeddingFailures = 0
        val perFrameFaces: List<TrackletBuilder.FrameFaces> = frames.mapIndexed { index, frame ->
            val observations = perFrameDetected[index].mapNotNull { d ->
                val embedding = faceEmbedder.embed(d.alignedCrop)
                // The aligned crop exists only to be embedded; free it immediately
                // rather than holding one per detection for the whole run.
                d.alignedCrop.recycle()
                embeddedCount++
                if (embedding == null) embeddingFailures++
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
            TrackletBuilder.FrameFaces(frame.timestampMs, observations)
        }
        if (totalFaces == 0) onProgress(Stage.EMBEDDING, 1f)
        if (embeddingFailures > 0) {
            Log.w(TAG, "embedding: $embeddingFailures/$totalFaces faces produced no embedding")
        }

        // Phase C: frame-to-frame continuity -> tracklets. These are an
        // intermediate for building low-noise identity embeddings, NOT the
        // appearance count -- see TrackletBuilder.
        val tracklets = trackletBuilder.build(perFrameFaces)
        onProgress(Stage.TRACKING, 1f)
        Log.d(TAG, "tracklets found: ${tracklets.size}")
        for ((i, t) in tracklets.withIndex()) {
            Log.d(TAG, "  tracklet[$i]: ${t.startMs}-${t.endMs}ms, ${t.observations.size} observations")
        }
        logPairwiseSimilarity(tracklets, identityClusterer.similarityThreshold)

        // Phase D: identity clustering across tracklets.
        val clusters = identityClusterer.cluster(tracklets)
        onProgress(Stage.CLUSTERING, 1f)
        Log.d(TAG, "people (clusters) found: ${clusters.size}, tracklets_per_person=${clusters.map { it.tracklets.size }}")

        // Phase E: per person, re-derive appearances from their own timeline,
        // then pick their representative shot. A cluster whose every candidate
        // segment was single-frame noise yields no appearances and is dropped
        // rather than shown as a person who appears zero times.
        val peopleUnsorted = clusters.mapNotNull { cluster ->
            val allObservations = cluster.tracklets.flatMap { it.observations }
            val appearances = AppearanceSplitter.split(
                observations = allObservations,
                maxGapMs = appearanceGapMs,
                minObservations = minObservationsPerAppearance
            )
            if (appearances.isEmpty()) return@mapNotNull null

            // Score only the observations that survived into a counted
            // appearance, so the collage never shows a frame we just rejected.
            val countedObservations = appearances.flatMap { it.observations }
            val best = QualityScorer.pickBest(countedObservations) ?: return@mapNotNull null
            Triple(appearances, best, appearances.first().startMs)
        }

        // Present people in the order the video introduces them -- stable across
        // runs and easier to check against the video than cluster order.
        val people = peopleUnsorted
            .sortedBy { it.third }
            .mapIndexed { index, (appearances, best, _) ->
                Log.d(
                    TAG,
                    "person[${index + 1}]: ${appearances.size} appearance(s) at " +
                        appearances.joinToString { "${it.startMs}-${it.endMs}ms" }
                )
                Person(
                    displayIndex = index + 1,
                    appearanceCount = appearances.size,
                    bestShot = best.observation.generousCrop,
                    bestScore = best.score
                )
            }
        onProgress(Stage.SCORING, 1f)

        // Every detection kept a generous crop as a collage candidate; only the
        // chosen ones are still needed. Releasing the rest before compositing
        // keeps peak memory to the collage itself rather than every candidate
        // tile from the whole video.
        recycleUnusedCrops(perFrameFaces, keep = people.map { it.bestShot })

        // Phase F: collage.
        val collage = CollageComposer.compose(people)
        onProgress(Stage.COMPOSING, 1f)

        PipelineResult(people, collage)
    }

    fun close() {
        faceDetector.close()
        faceEmbedder.close()
    }

    /**
     * Releases every candidate collage crop that did not end up in the collage.
     * [keep] holds the bitmaps the Person list still references.
     */
    private fun recycleUnusedCrops(frames: List<TrackletBuilder.FrameFaces>, keep: List<Bitmap>) {
        // Identity comparison, not equals: two distinct bitmaps can be equal by
        // content and we must only spare the exact instances still in use.
        val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
        kept.addAll(keep)
        var recycled = 0
        for (frame in frames) {
            for (observation in frame.faces) {
                val crop = observation.generousCrop
                if (crop !in kept && !crop.isRecycled) {
                    crop.recycle()
                    recycled++
                }
            }
        }
        Log.d(TAG, "released $recycled unused candidate crop(s), kept ${keep.size}")
    }

    /**
     * Logs the full sorted spread of cosine similarity between every pair of
     * tracklet identity embeddings.
     *
     * This is the calibration instrument for [IdentityClusterer]'s threshold,
     * and it is meant to be read rather than skimmed. With alignment working,
     * the sorted list should show a visible step: a band of impostor pairs
     * (different people) and a band of genuine pairs (same person), with a gap
     * between them -- put the threshold in the gap. If instead the values form
     * one smooth continuum with no step, no threshold can separate identities
     * and the problem is upstream (crop alignment, face size, lighting), not a
     * number to tune here.
     */
    private fun logPairwiseSimilarity(tracklets: List<Tracklet>, threshold: Float) {
        if (tracklets.size < 2) {
            Log.d(TAG, "only ${tracklets.size} tracklet(s) -- nothing to compare")
            return
        }
        val embeddings = identityClusterer.clusteringEmbeddings(tracklets)
        val pairs = mutableListOf<Pair<String, Float>>()
        for (i in tracklets.indices) {
            for (j in i + 1 until tracklets.size) {
                pairs.add("$i-$j" to VectorMath.cosineSim(embeddings[i], embeddings[j]))
            }
        }
        val sims = pairs.map { it.second }
        Log.d(
            TAG,
            "tracklet-pair similarity (centered): n=${sims.size} min=%.3f avg=%.3f max=%.3f above($threshold)=%d"
                .format(sims.min(), sims.average(), sims.max(), sims.count { it > threshold })
        )
        Log.d(
            TAG,
            "  sorted: " + pairs.sortedByDescending { it.second }
                .joinToString(" ") { "%s=%.3f".format(it.first, it.second) }
        )
    }
}
