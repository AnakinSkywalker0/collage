package com.abhishek.collage.pipeline

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.abhishek.collage.pipeline.math.GeometryMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val TAG = "CollagePipeline"

/**
 * Thin wrapper around ML Kit's face detector. One detector instance is reused
 * sequentially (ML Kit detectors are not safe for concurrent use), so calls
 * from this class must not be parallelized across frames.
 */
class FaceDetectorStage {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.1f)
        // Deliberately NOT enableTracking(): ML Kit's tracker is built for a
        // continuous camera stream, we feed it frames seeked 200ms apart, and
        // nothing downstream reads Face.trackingId -- TrackletBuilder does its
        // own continuity from boxes plus embeddings. Enabling it only added
        // per-frame cost, and ML Kit itself advises against pairing it with
        // PERFORMANCE_MODE_ACCURATE.
        .build()

    private val detector = FaceDetection.getClient(options)

    data class RawFace(
        val boundingBox: Rect,
        val eulerY: Float,
        val eulerZ: Float,
        val leftEyeOpenProb: Float?,
        val rightEyeOpenProb: Float?,
        val smilingProb: Float?,
        /** Eye centres in source-frame pixel coords; used to align the crop fed to the embedder. */
        val leftEye: PointF?,
        val rightEye: PointF?
    )

    suspend fun detect(bitmap: Bitmap): List<RawFace> = withContext(Dispatchers.Default) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val faces = awaitTask(detector.process(input))
        dedupeSameFaceDetections(faces.map { it.toRawFace() })
    }

    fun close() {
        detector.close()
    }

    companion object {
        /**
         * ML Kit occasionally emits two near-identical bounding boxes for the
         * same physical face in a single frame (a known quirk under accurate
         * mode with full landmarks/classification). Left unfiltered, the
         * tracker has no way to tell "duplicate box, same face" apart from "a
         * second real person", and spawns a phantom track that then tracks
         * *itself* for the rest of the video -- producing a ghost person with
         * suspiciously perfect continuity. Two boxes this overlapping within
         * one frame are almost certainly the same face; keep only the larger.
         * Verified against real two-person frames: genuinely different people
         * never approach this IOU (their boxes may touch, not coincide), so
         * the filter does not eat real second people.
         */
        private const val DUPLICATE_IOU_THRESHOLD = 0.6f
    }

    private fun dedupeSameFaceDetections(faces: List<RawFace>): List<RawFace> {
        if (faces.size < 2) return faces
        val kept = mutableListOf<RawFace>()
        for (face in faces.sortedByDescending { it.boundingBox.width().toLong() * it.boundingBox.height() }) {
            val isDuplicate = kept.any { existing -> boxIou(existing.boundingBox, face.boundingBox) > DUPLICATE_IOU_THRESHOLD }
            if (!isDuplicate) {
                kept.add(face)
            }
        }
        if (kept.size != faces.size) {
            Log.d(TAG, "dedup: dropped ${faces.size - kept.size} duplicate box(es) from a ${faces.size}-detection frame")
        }
        return kept
    }

    private fun boxIou(a: Rect, b: Rect): Float = GeometryMath.iou(
        a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat(),
        b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()
    )

    private fun Face.toRawFace() = RawFace(
        boundingBox = boundingBox,
        eulerY = headEulerAngleY,
        eulerZ = headEulerAngleZ,
        leftEyeOpenProb = leftEyeOpenProbability,
        rightEyeOpenProb = rightEyeOpenProbability,
        smilingProb = smilingProbability,
        leftEye = getLandmark(FaceLandmark.LEFT_EYE)?.position,
        rightEye = getLandmark(FaceLandmark.RIGHT_EYE)?.position
    )

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }
}
