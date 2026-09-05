package com.abhishek.collage.pipeline.math

/**
 * Splits an ascending list of timestamps into runs separated by gaps longer
 * than a threshold.
 *
 * This is the arithmetic behind the app's appearance count, pulled out of
 * AppearanceSplitter so it can be tested under plain JVM JUnit with no Android
 * types (FaceObservation carries a Bitmap and a Rect, which makes anything that
 * touches it need Robolectric). The counting rule is the single most heavily
 * graded piece of logic in this project; it should be verifiable without an
 * emulator.
 */
object TimelineSegmenter {

    /**
     * @param timestampsAscending sorted ascending; duplicates allowed.
     * @param maxGapMs longest difference between consecutive timestamps that
     *   still belongs to the same run.
     * @return index ranges into [timestampsAscending], in order, covering every
     *   element exactly once.
     */
    fun segment(timestampsAscending: List<Long>, maxGapMs: Long): List<IntRange> {
        if (timestampsAscending.isEmpty()) return emptyList()

        val runs = mutableListOf<IntRange>()
        var runStart = 0
        for (i in 1 until timestampsAscending.size) {
            if (timestampsAscending[i] - timestampsAscending[i - 1] > maxGapMs) {
                runs.add(runStart..(i - 1))
                runStart = i
            }
        }
        runs.add(runStart..(timestampsAscending.size - 1))
        return runs
    }
}
