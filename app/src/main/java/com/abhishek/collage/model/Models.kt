package com.abhishek.collage.model

import android.graphics.Bitmap
import android.graphics.Rect
import com.abhishek.collage.pipeline.math.VectorMath

/**
 * One sampled frame from the source video.
 */
data class FrameSample(
    val timestampMs: Long,
    val bitmap: Bitmap
)

/**
 * A single detected face in a single sampled frame, with everything needed
 * for both temporal tracking and later representative-shot scoring.
 */
data class FaceObservation(
    val timestampMs: Long,
    val boundingBox: Rect,
    val eulerY: Float,
    val eulerZ: Float,
    val leftEyeOpenProb: Float?,
    val rightEyeOpenProb: Float?,
    val smilingProb: Float?,
    val embedding: FloatArray,
    val generousCrop: Bitmap,
    val sharpness: Float,
    val touchesFrameEdge: Boolean
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * One continuous visible segment of a single person within the source video.
 * Finalized once a track is closed by AppearanceTracker; personIdProvisional
 * is a per-video track id, NOT the final cross-appearance identity.
 */
data class Appearance(
    val personIdProvisional: Int,
    val observations: List<FaceObservation>,
    val startMs: Long,
    val endMs: Long
) {
    val meanEmbedding: FloatArray by lazy { VectorMath.mean(observations.map { it.embedding }) }
}

/**
 * Final, clustered identity: one real person, possibly spanning multiple
 * appearances, with their best representative shot picked out.
 */
data class Person(
    val displayIndex: Int,
    val appearanceCount: Int,
    val bestShot: Bitmap,
    val bestScore: Float
)

/**
 * Progress reported by the pipeline while it runs, consumed by the UI.
 */
sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class Running(val stage: Stage, val fraction: Float) : ProcessingState()
    data class Done(val people: List<Person>, val collage: Bitmap) : ProcessingState()
    data class Failed(val message: String) : ProcessingState()

    enum class Stage(val label: String) {
        EXTRACTING("Reading video frames"),
        DETECTING("Detecting faces"),
        EMBEDDING("Computing face signatures"),
        TRACKING("Tracking appearances"),
        CLUSTERING("Grouping people"),
        SCORING("Picking best shots"),
        COMPOSING("Building collage")
    }
}
