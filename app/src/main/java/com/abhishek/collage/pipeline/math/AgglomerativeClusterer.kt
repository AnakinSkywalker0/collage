package com.abhishek.collage.pipeline.math

/**
 * Average-linkage agglomerative clustering on L2-normalized embeddings.
 *
 * Repeatedly merges the two most similar clusters until no pair exceeds
 * [threshold]. Similarity between two clusters is the MEAN pairwise cosine
 * similarity between their members (average linkage).
 *
 * Why not the single-pass greedy version this replaces:
 *
 *  - **Order dependence.** Greedy assigns each item to the best cluster that
 *    exists *at the time it is visited*, so the result depends on input order.
 *    Two appearances of the same person could land in different clusters purely
 *    because a third item was seen between them. Agglomerative always merges the
 *    globally-best available pair, so the output is a function of the embeddings
 *    alone -- the same input gives the same answer every time.
 *
 *  - **Centroid drift.** Greedy tracked a running-mean centroid that was updated
 *    on every merge, so one marginal merge shifted the target and made the next
 *    marginal merge easier -- errors compounded in one direction. Average linkage
 *    re-reads the actual members every time and never stores a mutable summary,
 *    so a single borderline member cannot drag the cluster somewhere new.
 *
 *  - **Outlier sensitivity.** A mean centroid is pulled by its worst member.
 *    Averaging over all pairs means one bad embedding is outvoted by the rest
 *    rather than redefining the cluster.
 *
 * Cost is O(n^3) worst case. n here is the number of tracklets in a 30s clip --
 * tens, not thousands -- so this runs in microseconds and the robustness is free.
 */
object AgglomerativeClusterer {

    /**
     * @param threshold minimum average-linkage cosine similarity for two
     *   clusters to be considered the same person.
     * @return clusters of the input items; every input appears in exactly one.
     *   Input order is preserved within each cluster.
     */
    fun <T> cluster(
        items: List<T>,
        embeddingOf: (T) -> FloatArray,
        threshold: Float
    ): List<List<T>> {
        if (items.size < 2) return items.map { listOf(it) }

        val embeddings = items.map(embeddingOf)
        val n = items.size

        // Precompute the full pairwise similarity matrix once; linkage scores
        // are then just averages of lookups.
        val sim = Array(n) { i ->
            FloatArray(n) { j -> VectorMath.cosineSim(embeddings[i], embeddings[j]) }
        }

        // Clusters as lists of original indices, so members stay identifiable
        // after any number of merges.
        val clusters = MutableList(n) { mutableListOf(it) }

        while (clusters.size > 1) {
            var bestI = -1
            var bestJ = -1
            var bestSim = threshold // only merges strictly above threshold qualify

            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val linkage = averageLinkage(clusters[i], clusters[j], sim)
                    if (linkage > bestSim) {
                        bestSim = linkage
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (bestI < 0) break // nothing left worth merging

            clusters[bestI].addAll(clusters[bestJ])
            clusters[bestI].sort() // keep original input order inside a cluster
            clusters.removeAt(bestJ)
        }

        return clusters.map { indices -> indices.map { items[it] } }
    }

    private fun averageLinkage(a: List<Int>, b: List<Int>, sim: Array<FloatArray>): Float {
        var total = 0f
        for (i in a) for (j in b) total += sim[i][j]
        return total / (a.size * b.size)
    }
}
