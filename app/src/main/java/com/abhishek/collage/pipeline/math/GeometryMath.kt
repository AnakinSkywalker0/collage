package com.abhishek.collage.pipeline.math

/**
 * Framework-free geometry math (no android.graphics.Rect) so it's directly
 * unit-testable under plain JVM JUnit.
 */
object GeometryMath {

    fun iou(
        aLeft: Float, aTop: Float, aRight: Float, aBottom: Float,
        bLeft: Float, bTop: Float, bRight: Float, bBottom: Float
    ): Float {
        val interLeft = maxOf(aLeft, bLeft)
        val interTop = maxOf(aTop, bTop)
        val interRight = minOf(aRight, bRight)
        val interBottom = minOf(aBottom, bBottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (aRight - aLeft) * (aBottom - aTop)
        val areaB = (bRight - bLeft) * (bBottom - bTop)
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }
}
