package com.abhishek.collage.pipeline

import android.graphics.Bitmap

/**
 * Sharpness estimation via Laplacian variance on a downsampled grayscale
 * version of the crop. Higher variance = more high-frequency detail = more
 * likely in-focus / not motion-blurred. This is intentionally cheap (small
 * fixed grid) since it runs once per candidate face across ~150 frames.
 */
object ImageQuality {

    private const val GRID = 64

    fun laplacianVariance(bitmap: Bitmap): Float {
        val w = GRID
        val h = GRID
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val gray = FloatArray(w * h)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        if (scaled !== bitmap) scaled.recycle()

        // 4-neighbor Laplacian kernel: |4*center - up - down - left - right|
        val lap = mutableListOf<Float>()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val value = 4 * gray[idx] - gray[idx - 1] - gray[idx + 1] -
                    gray[idx - w] - gray[idx + w]
                lap.add(value)
            }
        }
        if (lap.isEmpty()) return 0f
        val mean = lap.sum() / lap.size
        val variance = lap.sumOf { ((it - mean) * (it - mean)).toDouble() } / lap.size
        return variance.toFloat()
    }
}
