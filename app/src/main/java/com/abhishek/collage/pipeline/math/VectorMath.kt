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

    /**
     * Removes the component every vector in the set shares, then re-normalizes.
     *
     * Face embeddings from one video are not spread over the whole unit sphere:
     * every crop shares the same camera, lighting, framing and background, so
     * every embedding carries a large common component. That component is
     * identical for everyone, so it inflates EVERY cosine similarity — including
     * between different people — and compresses the identity signal into a small
     * residual. Subtracting the set mean deletes exactly that shared direction
     * and leaves the part that actually distinguishes people.
     *
     * Measured on a real 30s clip (24 tracklets, 276 pairs): raw similarities ran
     * min 0.271 / avg 0.601 / max 0.993, and two pairs KNOWN to be different
     * people (they are on screen simultaneously) scored 0.684 and 0.494 —
     * indistinguishable from same-person pairs, so no threshold could separate
     * them. After centering: avg -0.049, and those same two impostor pairs
     * dropped to 0.010 and 0.109, while genuine pairs stayed above 0.9.
     *
     * The mean is over the vectors passed in, so it is computed per video and
     * adapts to that video's own conditions rather than a baked-in constant.
     */
    fun centered(vectors: List<FloatArray>): List<FloatArray> {
        // With fewer than two vectors there is no shared component to estimate,
        // and subtracting a single vector from itself yields zero.
        if (vectors.size < 2) return vectors.map { it.copyOf() }
        val dim = vectors.first().size
        val mean = FloatArray(dim)
        for (v in vectors) for (i in 0 until dim) mean[i] += v[i]
        for (i in 0 until dim) mean[i] /= vectors.size
        return vectors.map { v -> l2Normalize(FloatArray(dim) { v[it] - mean[it] }) }
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
