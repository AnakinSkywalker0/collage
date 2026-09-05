package com.abhishek.collage.pipeline

import com.abhishek.collage.model.Appearance
import com.abhishek.collage.model.FaceObservation
import com.abhishek.collage.pipeline.math.TimelineSegmenter

/**
 * Splits one identified person's observations into [Appearance]s -- the unit
 * the assignment counts.
 *
 * The brief defines an appearance as "one continuous visible segment: it starts
 * when a person's face becomes clearly visible and ends when it is no longer
 * clearly visible". Once every observation carries a person identity, that
 * definition is literally a gap analysis over one person's timestamps: sort
 * their observations, and cut wherever the video went longer than [maxGapMs]
 * without seeing them.
 *
 * Running this AFTER identity clustering (rather than counting tracker output,
 * as an earlier version did) is what makes the count robust. Tracking answers
 * "is this the same face as the previous frame", which is a spatial question
 * that breaks down at cuts; counting from a per-person timeline asks only "was
 * this person on screen continuously", which is exactly what is being graded.
 * It also handles the brief's overlap case for free -- when two people share a
 * segment, each one's timeline independently yields one appearance.
 */
object AppearanceSplitter {

    /**
     * @param observations every observation attributed to one person, any order.
     * @param maxGapMs longest absence that still counts as the same continuous
     *   segment. Should absorb a few consecutive missed detections (blink,
     *   motion blur) without bridging a real cut away and back.
     * @param minObservations appearances supported by fewer observations than
     *   this are discarded as detector noise rather than counted.
     */
    fun split(
        observations: List<FaceObservation>,
        maxGapMs: Long,
        minObservations: Int
    ): List<Appearance> {
        if (observations.isEmpty()) return emptyList()

        val sorted = observations.sortedBy { it.timestampMs }
        return TimelineSegmenter.segment(sorted.map { it.timestampMs }, maxGapMs)
            .map { range -> sorted.slice(range) }
            .filter { it.size >= minObservations }
            .map { segment ->
                Appearance(
                    observations = segment,
                    startMs = segment.first().timestampMs,
                    endMs = segment.last().timestampMs
                )
            }
    }
}
