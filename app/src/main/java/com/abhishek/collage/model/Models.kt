package com.abhishek.collage.model

import android.graphics.Bitmap
import android.graphics.Rect

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
 * A run of detections that frame-to-frame continuity proves are the same face.
 *
 * A tracklet is an INTERMEDIATE, not an answer. Its only job is to pool several
 * views of one face so we can compute a low-noise embedding for it; it is
 * deliberately built conservatively (see TrackletBuilder), so it may
 * over-segment a single visible segment into two or three pieces. That is fine
 * and by design -- the final appearance count is re-derived from timestamps
 * after identity clustering (see AppearanceSplitter), so fragmentation here
 * costs nothing, while a tracklet that wrongly spans two people would corrupt
 * both the identity and the count.
 */
data class Tracklet(
    val id: Int,
    val observations: List<FaceObservation>
) {
    val startMs: Long get() = observations.first().timestampMs
    val endMs: Long get() = observations.last().timestampMs
}

/**
 * One continuous visible segment of one identified person -- the unit the
 * assignment actually counts ("starts when a person's face becomes clearly
 * visible and ends when it is no longer clearly visible").
 *
 * Produced by AppearanceSplitter AFTER identity clustering, by splitting a
 * person's whole observation timeline on temporal gaps.
 */
data class Appearance(
    val observations: List<FaceObservation>,
    val startMs: Long,
    val endMs: Long
)

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
