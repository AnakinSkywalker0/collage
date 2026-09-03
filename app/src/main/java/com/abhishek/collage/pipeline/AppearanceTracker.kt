package com.abhishek.collage.pipeline

import android.graphics.Rect
import com.abhishek.collage.model.Appearance
import com.abhishek.collage.model.FaceObservation
import com.abhishek.collage.pipeline.math.GeometryMath
import com.abhishek.collage.pipeline.math.VectorMath

/**
 * Frame-to-frame tracker that segments the continuous visible runs of each
 * person within ONE video into [Appearance]s. This is deliberately separate
 * from cross-appearance identity clustering (IdentityClusterer): this stage
 * only answers "is this the same physical track as last frame", using both
 * spatial continuity (IOU) and identity continuity (embedding similarity) so
 * a person walking across frame stays one track, and two different people
 * swapping positions don't get merged.
 *
 * A track closes (finalizes as an Appearance) once it hasn't been matched for
 * longer than [maxGapMs] -- this is what makes a blurred whip-pan or a person
 * leaving frame end the appearance rather than bridging it indefinitely.
 */
class AppearanceTracker(
    private val iouThreshold: Float = 0.3f,
    private val embeddingThreshold: Float = 0.5f,
    private val maxGapMs: Long = 600L,
    /**
     * A track can only be extended via IOU alone (no strong embedding match)
     * if its embedding similarity to the candidate face is at least this much
     * -- i.e. "not obviously a different person". Without this floor, two
     * people with spatially overlapping boxes (standing close together) can
     * have their identities swapped between tracks purely on position, since
     * IOU says nothing about who someone is. Keep this near 0: raising it
     * trades a rare identity-swap for much more common track fragmentation
     * under normal embedding noise (verified empirically -- see README).
     */
    private val embeddingSanityFloor: Float = 0f,
    /**
     * Appearances with fewer than this many observations are dropped. At 5fps
     * sampling, a single matched observation means the face was "visible" for
     * one 200ms instant -- almost always a stray false-positive detection
     * (ML Kit misfiring on one frame) rather than a real appearance, and the
     * assignment's own definition implies some persistence ("becomes clearly
     * visible" / "no longer clearly visible", not "flickers once"). 2 is a
     * conservative floor: it only removes single-frame noise, never a
     * genuinely brief real appearance spanning multiple samples.
     */
    private val minObservationsPerAppearance: Int = 2
) {

    private class ActiveTrack(
        val id: Int,
        val observations: MutableList<FaceObservation>,
        var lastBox: Rect,
        var runningEmbedding: FloatArray,
        var lastSeenMs: Long
    )

    /**
     * One sampled frame's timestamp plus whatever faces were detected in it
     * (possibly none) -- the timestamp must be carried explicitly even for
     * empty frames, otherwise the tracker can't tell how much real time has
     * elapsed and gap-closing silently breaks.
     */
    data class FrameFaces(val timestampMs: Long, val faces: List<FaceObservation>)

    /**
     * @param frames time-ordered, one entry per sampled frame.
     */
    fun track(frames: List<FrameFaces>): List<Appearance> {
        val active = mutableListOf<ActiveTrack>()
        val finished = mutableListOf<Appearance>()
        var nextId = 0

        for ((frameTs, frameFaces) in frames) {
            // Close tracks that have already exceeded the allowed gap BEFORE
            // matching this frame -- otherwise a track that should already be
            // dead can still greedily steal this frame's detection just
            // because no fresher candidate outscores it, silently merging two
            // different people's appearances into one track across the gap.
            val staleIterator = active.iterator()
            while (staleIterator.hasNext()) {
                val track = staleIterator.next()
                if (frameTs - track.lastSeenMs > maxGapMs) {
                    finished.add(track.toAppearance())
                    staleIterator.remove()
                }
            }

            // Greedy best-first matching between active tracks and this frame's faces.
            val candidates = mutableListOf<Triple<ActiveTrack, FaceObservation, Float>>()
            for (track in active) {
                for (face in frameFaces) {
                    val iou = boxIou(track.lastBox, face.boundingBox)
                    val simRaw = VectorMath.cosineSim(track.runningEmbedding, face.embedding)
                    // IOU alone can't win a match against a clearly-different
                    // embedding -- two people standing close together can have
                    // high IOU on their boxes without being the same person.
                    val isMatch = simRaw > embeddingThreshold ||
                        (iou > iouThreshold && simRaw > embeddingSanityFloor)
                    if (isMatch) {
                        val score = 0.5f * iou + 0.5f * simRaw
                        candidates.add(Triple(track, face, score))
                    }
                }
            }
            candidates.sortByDescending { it.third }

            val matchedTracks = mutableSetOf<Int>()
            val matchedFaces = mutableSetOf<FaceObservation>()
            for ((track, face, _) in candidates) {
                if (track.id in matchedTracks || face in matchedFaces) continue
                matchedTracks.add(track.id)
                matchedFaces.add(face)
                track.observations.add(face)
                track.lastBox = face.boundingBox
                track.lastSeenMs = face.timestampMs
                track.runningEmbedding = VectorMath.runningMean(track.runningEmbedding, face.embedding, track.observations.size)
            }

            // Unmatched faces start new tracks.
            for (face in frameFaces) {
                if (face in matchedFaces) continue
                active.add(
                    ActiveTrack(
                        id = nextId++,
                        observations = mutableListOf(face),
                        lastBox = face.boundingBox,
                        runningEmbedding = face.embedding.copyOf(),
                        lastSeenMs = face.timestampMs
                    )
                )
            }
        }

        // Close whatever is still open at the end of the video.
        for (track in active) finished.add(track.toAppearance())

        return finished.filter { it.observations.size >= minObservationsPerAppearance }
    }

    private fun ActiveTrack.toAppearance() = Appearance(
        personIdProvisional = id,
        observations = observations.toList(),
        startMs = observations.first().timestampMs,
        endMs = observations.last().timestampMs
    )

    private fun boxIou(a: Rect, b: Rect): Float = GeometryMath.iou(
        a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat(),
        b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()
    )
}
