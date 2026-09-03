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
}
