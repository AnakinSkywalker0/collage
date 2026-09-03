package com.abhishek.collage.pipeline.math

import kotlin.math.sqrt

/**
 * Framework-free vector math shared by tracking and clustering. Kept out of
 * android.* entirely so it's trivially unit-testable under plain JVM JUnit
 * (no Robolectric / instrumentation needed).
 */
object VectorMath {

    /** Cosine similarity. If both inputs are L2-normalized this is just a dot product. */
    fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm).takeIf { it > 1e-6f } ?: 1f
        return FloatArray(vec.size) { vec[it] / norm }
    }

    /**
     * Incremental mean: blends [next] into [current] as if [current] were the
     * mean of (count - 1) prior samples, then re-normalizes. Used both for a
     * track's running identity estimate and a cluster's running centroid.
     */
    fun runningMean(current: FloatArray, next: FloatArray, count: Int): FloatArray {
        val out = FloatArray(current.size)
        for (i in current.indices) {
            out[i] = current[i] + (next[i] - current[i]) / count
        }
        return l2Normalize(out)
    }

    fun mean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty())
        val dim = vectors.first().size
        val sum = FloatArray(dim)
        for (v in vectors) for (i in 0 until dim) sum[i] += v[i]
        for (i in 0 until dim) sum[i] /= vectors.size
        return l2Normalize(sum)
    }
}
