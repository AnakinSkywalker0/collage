package com.abhishek.collage.pipeline.math

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryMathTest {

    @Test
    fun iou_identicalBoxes_isOne() {
        val iou = GeometryMath.iou(0f, 0f, 10f, 10f, 0f, 0f, 10f, 10f)
        assertEquals(1f, iou, 1e-5f)
    }

    @Test
    fun iou_disjointBoxes_isZero() {
        val iou = GeometryMath.iou(0f, 0f, 10f, 10f, 20f, 20f, 30f, 30f)
        assertEquals(0f, iou, 1e-5f)
    }

    @Test
    fun iou_touchingEdges_isZero() {
        // Sharing only an edge means zero intersection area.
        val iou = GeometryMath.iou(0f, 0f, 10f, 10f, 10f, 0f, 20f, 10f)
        assertEquals(0f, iou, 1e-5f)
    }

    @Test
    fun iou_knownPartialOverlap_matchesExpectedFraction() {
        // A: (0,0)-(10,10) area 100. B: (5,5)-(15,15) area 100.
        // Intersection: (5,5)-(10,10) area 25. Union: 100+100-25 = 175.
        val iou = GeometryMath.iou(0f, 0f, 10f, 10f, 5f, 5f, 15f, 15f)
        assertEquals(25f / 175f, iou, 1e-5f)
    }

    @Test
    fun iou_oneBoxFullyInsideOther_equalsAreaRatio() {
        // A: (0,0)-(10,10) area 100. B: (2,2)-(8,8) area 36, fully inside A.
        // Intersection = 36, union = 100.
        val iou = GeometryMath.iou(0f, 0f, 10f, 10f, 2f, 2f, 8f, 8f)
        assertEquals(0.36f, iou, 1e-5f)
    }

    @Test
    fun iou_degenerateZeroAreaBox_isZero() {
        val iou = GeometryMath.iou(0f, 0f, 0f, 10f, 0f, 0f, 10f, 10f)
        assertEquals(0f, iou, 1e-5f)
    }
}
