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
     *
     * [otherFacesInFrame] -- the bounding boxes of every OTHER face detected in
     * this same frame, if any. When two people share a frame (the assignment's
     * own worked example has this: two people overlapping for over a second),
     * a blind 50%-margin expansion around one person's box can reach straight
     * into their neighbour's face -- producing a collage tile that visibly
     * shows two different people. Each directional margin is capped at half
     * the gap to the nearest face on that side, so the crop still fills out
     * generously wherever there's no one nearby, but never crosses into
     * someone else's face.
     */
    fun expandedRect(
        frame: Bitmap,
        bbox: Rect,
        marginRatio: Float = DEFAULT_MARGIN_RATIO,
        otherFacesInFrame: List<Rect> = emptyList()
    ): Rect {
        var marginLeft = bbox.width() * marginRatio
        var marginRight = marginLeft
        var marginTop = bbox.height() * marginRatio
        var marginBottom = marginTop

        for (other in otherFacesInFrame) {
            val gapRight = (other.left - bbox.right).toFloat()
            if (gapRight in 0f..marginRight) marginRight = (gapRight / 2f).coerceAtLeast(0f)
            val gapLeft = (bbox.left - other.right).toFloat()
            if (gapLeft in 0f..marginLeft) marginLeft = (gapLeft / 2f).coerceAtLeast(0f)
            val gapBottom = (other.top - bbox.bottom).toFloat()
            if (gapBottom in 0f..marginBottom) marginBottom = (gapBottom / 2f).coerceAtLeast(0f)
            val gapTop = (bbox.top - other.bottom).toFloat()
            if (gapTop in 0f..marginTop) marginTop = (gapTop / 2f).coerceAtLeast(0f)
        }

        val left = (bbox.left - marginLeft.toInt()).coerceIn(0, frame.width - 1)
        val top = (bbox.top - marginTop.toInt()).coerceIn(0, frame.height - 1)
        val right = (bbox.right + marginRight.toInt()).coerceIn(left + 1, frame.width)
        val bottom = (bbox.bottom + marginBottom.toInt()).coerceIn(top + 1, frame.height)
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

    fun cropGenerous(
        frame: Bitmap,
        bbox: Rect,
        marginRatio: Float = DEFAULT_MARGIN_RATIO,
        otherFacesInFrame: List<Rect> = emptyList()
    ): Bitmap {
        val rect = expandedRect(frame, bbox, marginRatio, otherFacesInFrame)
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
