package com.abhishek.collage.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Cropping helpers shared by the embedding stage, quality scoring, and the
 * final collage tiles. Deliberately never crops tight to the ML Kit bounding
 * box -- a tight crop produces low-resolution, poor-quality tiles per the
 * assignment brief. Always expand generously and clamp to frame bounds.
 */
object FaceCropUtils {

    const val DEFAULT_MARGIN_RATIO = 0.5f

    /**
     * Expands [bbox] by [marginRatio] on every side (relative to the box's own
     * width/height), clamps to the frame, and returns whether the resulting
     * box still touches an edge of the frame (used to flag likely-clipped faces).
     */
    fun expandedRect(frame: Bitmap, bbox: Rect, marginRatio: Float = DEFAULT_MARGIN_RATIO): Rect {
        val marginX = (bbox.width() * marginRatio).toInt()
        val marginY = (bbox.height() * marginRatio).toInt()
        val left = (bbox.left - marginX).coerceIn(0, frame.width - 1)
        val top = (bbox.top - marginY).coerceIn(0, frame.height - 1)
        val right = (bbox.right + marginX).coerceIn(left + 1, frame.width)
        val bottom = (bbox.bottom + marginY).coerceIn(top + 1, frame.height)
        return Rect(left, top, right, bottom)
    }

    fun touchesFrameEdge(frame: Bitmap, bbox: Rect, edgePaddingPx: Int = 4): Boolean {
        return bbox.left <= edgePaddingPx ||
            bbox.top <= edgePaddingPx ||
            bbox.right >= frame.width - edgePaddingPx ||
            bbox.bottom >= frame.height - edgePaddingPx
    }

    /**
     * Long-edge cap for retained collage crops. One of these is kept in memory
     * for every detected face across every sampled frame, so leaving them at
     * source resolution is an OOM waiting to happen on a real device (hundreds
     * of detections x ~0.5MB each). 512px is still comfortably more than the
     * ~330-500px a collage tile actually renders at.
     */
    const val MAX_COLLAGE_CROP_EDGE_PX = 512

    fun cropGenerous(frame: Bitmap, bbox: Rect, marginRatio: Float = DEFAULT_MARGIN_RATIO): Bitmap {
        val rect = expandedRect(frame, bbox, marginRatio)
        val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())

        val longEdge = maxOf(crop.width, crop.height)
        if (longEdge <= MAX_COLLAGE_CROP_EDGE_PX) return crop

        val scale = MAX_COLLAGE_CROP_EDGE_PX / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).toInt().coerceAtLeast(1),
            (crop.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== crop) crop.recycle()
        return scaled
    }
}
