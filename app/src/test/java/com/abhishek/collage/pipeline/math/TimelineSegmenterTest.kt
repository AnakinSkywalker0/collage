package com.abhishek.collage.pipeline.math

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the appearance-counting rule against the assignment brief's own worked
 * example. This is the arithmetic behind the metric worth 50% of the grade.
 */
class TimelineSegmenterTest {

    private val sampleIntervalMs = 200L
    private val gapMs = 3 * sampleIntervalMs // production setting

    private fun frames(fromMs: Long, toMs: Long): List<Long> =
        (fromMs..toMs step sampleIntervalMs).toList()

    @Test
    fun segment_emptyTimeline_hasNoSegments() {
        assertEquals(emptyList<IntRange>(), TimelineSegmenter.segment(emptyList(), gapMs))
    }

    @Test
    fun segment_singleObservation_isOneSegment() {
        assertEquals(listOf(0..0), TimelineSegmenter.segment(listOf(1000L), gapMs))
    }

    @Test
    fun segment_continuousRun_staysOneSegment() {
        // A person visible 10.1s-11.5s, sampled every 200ms, never dropped.
        val timeline = frames(10200, 11400)
        assertEquals(1, TimelineSegmenter.segment(timeline, gapMs).size)
    }

    @Test
    fun segment_briefDetectorDropout_doesNotSplitAnAppearance() {
        // Same continuous segment, but the detector missed two consecutive
        // frames mid-way (a blink or a motion-blurred frame). A 600ms gap
        // tolerance must absorb this rather than reporting two appearances.
        val timeline = frames(10200, 11400).filterNot { it == 10600L || it == 10800L }
        assertEquals(1, TimelineSegmenter.segment(timeline, gapMs).size)
    }

    @Test
    fun segment_cutAwayAndBack_countsTwoAppearances() {
        // On screen, gone for ~1.4s while another shot plays, then back.
        val timeline = frames(1000, 2400) + frames(3800, 5000)
        val segments = TimelineSegmenter.segment(timeline, gapMs)
        assertEquals(2, segments.size)
        assertEquals(1000L, timeline[segments[0].first])
        assertEquals(2400L, timeline[segments[0].last])
        assertEquals(3800L, timeline[segments[1].first])
        assertEquals(5000L, timeline[segments[1].last])
    }

    /**
     * The brief's Sample 1 worked example: five people, each appearing four
     * times, 20 appearances total. Modelled here as one person's share of that
     * -- four separate visible segments spread across the 30s clip -- to check
     * the segmenter reports exactly four, not one merged run and not eight
     * fragments.
     */
    @Test
    fun segment_sampleOneShapedPerson_countsFourAppearances() {
        val timeline = frames(1000, 2400) +
            frames(10100, 11500) +
            frames(18000, 19400) +
            frames(26000, 27400)

        val segments = TimelineSegmenter.segment(timeline, gapMs)

        assertEquals("expected 4 appearances", 4, segments.size)
        assertEquals(
            "every observation must belong to exactly one appearance",
            timeline.size,
            segments.sumOf { it.count() }
        )
    }

    @Test
    fun segment_gapExactlyAtThreshold_doesNotSplit() {
        // Boundary: the rule is "split when gap is strictly greater than
        // maxGapMs", so a gap of exactly maxGapMs stays one segment.
        val timeline = listOf(0L, gapMs)
        assertEquals(1, TimelineSegmenter.segment(timeline, gapMs).size)
        assertEquals(2, TimelineSegmenter.segment(listOf(0L, gapMs + 1), gapMs).size)
    }
}
