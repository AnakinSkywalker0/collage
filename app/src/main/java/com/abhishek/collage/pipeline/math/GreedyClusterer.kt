package com.abhishek.collage.pipeline.math

/**
 * Generic greedy threshold clustering on L2-normalized embedding vectors.
 * Framework-free and reusable -- IdentityClusterer is a thin adapter over
 * this for Appearance/Person, but the algorithm itself doesn't know or care
 * what an "appearance" is, which makes it directly unit-testable with plain
 * synthetic embeddings.
 *
 * Items are processed in the given order (caller decides -- e.g. start-time
 * order), each compared against existing cluster centroids by cosine
 * similarity, and merged into the best match above [threshold] or else seeds
 * a new cluster. O(n * k); at "a few dozen items" scale there's no practical
 * accuracy loss versus heavier agglomerative clustering, and the behavior is
 * easy to reason about and explain.
 */
object GreedyClusterer {

    data class Cluster<T>(var centroid: FloatArray, val members: MutableList<T> = mutableListOf())

    fun <T> cluster(items: List<T>, embeddingOf: (T) -> FloatArray, threshold: Float): List<Cluster<T>> {
        val clusters = mutableListOf<Cluster<T>>()

        for (item in items) {
            val embedding = embeddingOf(item)
            var best: Cluster<T>? = null
            var bestSim = -1f
            for (cluster in clusters) {
                val sim = VectorMath.cosineSim(cluster.centroid, embedding)
                if (sim > bestSim) {
                    bestSim = sim
                    best = cluster
                }
            }

            if (best != null && bestSim > threshold) {
                best.members.add(item)
                best.centroid = VectorMath.runningMean(best.centroid, embedding, best.members.size)
            } else {
                clusters.add(Cluster(embedding.copyOf(), mutableListOf(item)))
            }
        }
        return clusters
    }
}
