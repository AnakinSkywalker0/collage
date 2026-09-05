package com.abhishek.collage.pipeline

import android.graphics.Rect
import android.util.Log
import com.abhishek.collage.model.FaceObservation
import com.abhishek.collage.model.Tracklet
import com.abhishek.collage.pipeline.math.GeometryMath
import com.abhishek.collage.pipeline.math.VectorMath

private const val TAG = "CollagePipeline"

/**
 * Groups per-frame detections into [Tracklet]s: short runs that frame-to-frame
 * continuity proves are the same face.
 *
 * This stage does NOT decide how many appearances a person has. It exists only
 * to pool several views of one face so [IdentityClusterer] can embed it from
 * more than a single noisy frame. Appearance counts are re-derived from
 * timestamps after clustering, by [AppearanceSplitter].
 *
 * That division of labour drives the tuning here, which is deliberately
 * ASYMMETRIC:
 *
 *  - Splitting one visible segment into two tracklets is harmless. Both halves
 *    cluster to the same person, their observations are pooled, and the
 *    temporal split puts them back into one appearance.
 *  - Joining two *different people* into one tracklet is unrecoverable. It
 *    corrupts that tracklet's embedding, and its observations get attributed to
 *    whichever identity wins -- wrong person, wrong count.
 *
 * So every match must satisfy spatial AND identity continuity. The supplied
 * clips are cut-based portrait video where consecutive shots frame different
 * people's heads in the same part of the frame, so at a hard cut the outgoing
 * and incoming face overlap heavily -- IOU is HIGH precisely when identity has
 * changed. IOU alone therefore cannot be trusted to end a tracklet; the identity
 * gate is what actually stops a cut being bridged.
 *
 * That gate only works if its threshold is above the similarity two DIFFERENT
 * people actually score, which is why the embeddings reaching this class are
 * centered first. Two earlier versions failed on exactly this point: one used
 * OR with a 0f identity floor, and one used AND with a raw-cosine threshold of
 * 0.55 when different people in the same clip measure 0.68 raw. Both let
 * tracklets run through cuts and swallow whole appearances, which shows up
 * downstream as a person's four appearances being reported as two.
 */
class TrackletBuilder(
    /** Minimum box overlap between consecutive frames for spatial continuity. */
    private val iouThreshold: Float = 0.2f,
    /**
     * Minimum cosine similarity to the PREVIOUS frame's face.
     *
     * IMPORTANT: this is measured on CENTERED embeddings (PipelineOrchestrator
     * centers them before calling this class), and the scale is nothing like raw
     * cosine. Centered, two different people score around 0.0-0.1 while one
     * person 200ms apart scores above 0.9, so 0.45 sits in open space between
     * them. On RAW embeddings the same two populations are about 0.68 and 0.95,
     * which is why an earlier raw threshold of 0.55 was below the impostor level
     * and bridged cuts instead of blocking them. Do not port this number back to
     * raw similarities.
     */
    private val stepSimilarityThreshold: Float = 0.45f,
    /**
     * Minimum cosine similarity to the tracklet's FIRST face, also centered.
     * Step-wise matching alone can drift: each hop is individually plausible
     * while the endpoints are not the same person. Anchoring to the first
     * observation bounds total drift over the tracklet's life. Looser than the
     * step threshold because pose legitimately changes across a segment.
     */
    private val anchorSimilarityThreshold: Float = 0.25f,
    /**
     * How long a tracklet may go unmatched before it closes. Kept to two sample
     * intervals: a tracklet only needs to survive brief detector dropouts, since
     * a segment split into two tracklets is recovered downstream anyway.
     */
    private val maxGapMs: Long = 500L,
    /**
     * Tracklets with fewer observations than this are discarded before they can
     * influence identity clustering.
     *
     * These are not short appearances, they are duplicate detections. ML Kit
     * occasionally emits a second, offset box for a face it is already
     * reporting; if the two boxes overlap by less than the dedup threshold, the
     * extra one spawns a tracklet that lives for one or two frames inside
     * another tracklet's span. A real 30s clip produced six of them, and each
     * one scored 0.93-0.98 against the tracklet it overlapped -- which is the
     * proof they are the same face, not a second person. Left in, they add
     * spurious near-duplicate points that drag clustering around.
     */
    private val minObservations: Int = 3
) {

    private class OpenTracklet(
        val id: Int,
        val observations: MutableList<FaceObservation>,
        var lastBox: Rect,
        var lastSeenMs: Long,
        var lastEmbedding: FloatArray,
        val firstEmbedding: FloatArray
    )

    /**
     * One sampled frame's timestamp plus whatever faces were detected in it
     * (possibly none). The timestamp must be carried explicitly even for empty
     * frames, otherwise the builder cannot tell how much real time has elapsed
     * and gap-closing silently breaks.
     */
    data class FrameFaces(val timestampMs: Long, val faces: List<FaceObservation>)

    /**
     * @param frames time-ordered, one entry per sampled frame.
     */
    fun build(frames: List<FrameFaces>): List<Tracklet> {
        if (frames.isEmpty()) {
            Log.w(TAG, "TrackletBuilder.build called with 0 frames")
            return emptyList()
        }

        val open = mutableListOf<OpenTracklet>()
        val closed = mutableListOf<Tracklet>()
        var nextId = 0

        for ((frameTs, faces) in frames) {
            // Close anything that has already exceeded the gap BEFORE matching,
            // so a tracklet that should already be dead cannot win this frame's
            // detection just because nothing fresher outscores it.
            val stale = open.iterator()
            while (stale.hasNext()) {
                val tracklet = stale.next()
                if (frameTs - tracklet.lastSeenMs > maxGapMs) {
                    closed.add(tracklet.finish())
                    stale.remove()
                }
            }

            if (faces.isEmpty()) continue

            // Score every (tracklet, face) pair that clears BOTH continuity
            // tests, then assign greedily best-first so the strongest match in
            // the frame is settled before weaker ones can steal from it.
            val candidates = mutableListOf<Triple<OpenTracklet, FaceObservation, Float>>()
            for (tracklet in open) {
                for (face in faces) {
                    val iou = boxIou(tracklet.lastBox, face.boundingBox)
                    if (iou <= iouThreshold) continue

                    val stepSim = VectorMath.cosineSim(tracklet.lastEmbedding, face.embedding)
                    if (stepSim <= stepSimilarityThreshold) continue

                    val anchorSim = VectorMath.cosineSim(tracklet.firstEmbedding, face.embedding)
                    if (anchorSim <= anchorSimilarityThreshold) continue

                    candidates.add(Triple(tracklet, face, 0.5f * iou + 0.5f * stepSim))
                }
            }
            candidates.sortByDescending { it.third }

            val usedTracklets = mutableSetOf<Int>()
            val usedFaces = mutableSetOf<FaceObservation>()
            for ((tracklet, face, _) in candidates) {
                if (tracklet.id in usedTracklets || face in usedFaces) continue
                usedTracklets.add(tracklet.id)
                usedFaces.add(face)
                tracklet.observations.add(face)
                tracklet.lastBox = face.boundingBox
                tracklet.lastSeenMs = face.timestampMs
                tracklet.lastEmbedding = face.embedding
            }

            // Every unmatched face starts its own tracklet. Starting a spurious
            // tracklet is cheap; forcing a face into the wrong one is not.
            for (face in faces) {
                if (face in usedFaces) continue
                open.add(
                    OpenTracklet(
                        id = nextId++,
                        observations = mutableListOf(face),
                        lastBox = face.boundingBox,
                        lastSeenMs = face.timestampMs,
                        lastEmbedding = face.embedding,
                        firstEmbedding = face.embedding
                    )
                )
            }
        }

        for (tracklet in open) closed.add(tracklet.finish())

        return closed
            .filter { it.observations.size >= minObservations }
            .sortedBy { it.startMs }
    }

    private fun OpenTracklet.finish() = Tracklet(id = id, observations = observations.toList())

    private fun boxIou(a: Rect, b: Rect): Float = GeometryMath.iou(
        a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat(),
        b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()
    )
}
