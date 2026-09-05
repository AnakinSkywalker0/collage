package com.abhishek.collage.pipeline.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VectorMathTest {

    @Test
    fun cosineSim_identicalVectors_isOne() {
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, VectorMath.cosineSim(v, v), 1e-5f)
    }

    @Test
    fun cosineSim_orthogonalVectors_isZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, VectorMath.cosineSim(a, b), 1e-5f)
    }

    @Test
    fun cosineSim_oppositeVectors_isNegativeOne() {
        val a = VectorMath.l2Normalize(floatArrayOf(1f, 1f))
        val b = VectorMath.l2Normalize(floatArrayOf(-1f, -1f))
        assertEquals(-1f, VectorMath.cosineSim(a, b), 1e-5f)
    }

    @Test
    fun l2Normalize_producesUnitLength() {
        val v = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, norm, 1e-5f)
    }

    @Test
    fun l2Normalize_zeroVector_doesNotDivideByZero() {
        val v = VectorMath.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun runningMean_convergesTowardRepeatedSample() {
        var current = VectorMath.l2Normalize(floatArrayOf(1f, 0f))
        val target = VectorMath.l2Normalize(floatArrayOf(0f, 1f))
        var count = 1
        repeat(50) {
            count++
            current = VectorMath.runningMean(current, target, count)
        }
        // After enough samples of `target`, the running mean should point almost
        // exactly at `target`.
        assertTrue(VectorMath.cosineSim(current, target) > 0.99f)
    }

    @Test
    fun mean_ofIdenticalVectors_equalsThatVector() {
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        val m = VectorMath.mean(listOf(v, v, v))
        assertEquals(1f, VectorMath.cosineSim(v, m), 1e-4f)
    }

    /**
     * Regression test for the fix that made identity clustering work at all.
     *
     * Models what real per-video embeddings look like: every face shares a large
     * common component (same camera, lighting, background) with only a small
     * identity-specific residual. Raw cosine similarity between two DIFFERENT
     * people is then high enough to be indistinguishable from same-person pairs.
     * Centering must remove the shared component and restore the separation.
     */
    @Test
    fun centered_removesSharedComponent_andSeparatesDifferentIdentities() {
        val shared = VectorMath.l2Normalize(FloatArray(64) { 1f })
        fun person(seed: Int): FloatArray {
            val identity = FloatArray(64) { if (it % 64 == seed) 1f else 0f }
            // 90% shared framing/lighting, 10% identity -- the real-world ratio
            // that made raw similarities cluster around 0.6 regardless of person.
            return VectorMath.l2Normalize(FloatArray(64) { shared[it] * 0.9f + identity[it] * 0.1f })
        }
        val a1 = person(1); val a2 = person(1); val b1 = person(2)

        val rawImpostor = VectorMath.cosineSim(a1, b1)
        assertTrue(
            "setup check: raw impostor similarity should be misleadingly high, was $rawImpostor",
            rawImpostor > 0.9f
        )

        val (ca1, ca2, cb1) = VectorMath.centered(listOf(a1, a2, b1)).let { Triple(it[0], it[1], it[2]) }
        val centeredGenuine = VectorMath.cosineSim(ca1, ca2)
        val centeredImpostor = VectorMath.cosineSim(ca1, cb1)

        assertTrue("same person should stay similar, was $centeredGenuine", centeredGenuine > 0.9f)
        assertTrue("different people should separate, was $centeredImpostor", centeredImpostor < 0.5f)
        assertTrue(
            "centering must widen the genuine/impostor gap",
            (centeredGenuine - centeredImpostor) > (1f - rawImpostor)
        )
    }

    @Test
    fun centered_withFewerThanTwoVectors_isANoOp() {
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(0, VectorMath.centered(emptyList()).size)
        val single = VectorMath.centered(listOf(v))
        assertEquals(1, single.size)
        assertEquals(1f, VectorMath.cosineSim(v, single[0]), 1e-5f)
    }
}
