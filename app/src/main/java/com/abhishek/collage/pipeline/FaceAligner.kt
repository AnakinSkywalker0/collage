package com.abhishek.collage.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Produces the 112x112 input the embedding model actually expects.
 *
 * This matters more than any threshold: MobileFaceNet-class models are trained
 * on *tightly cropped, eye-aligned* faces, where the face fills the frame and
 * the eyes sit at fixed canonical positions. Feeding them anything else --
 * a loose crop, a rotated head, a face off to one side -- pushes the input off
 * the training distribution, and the embeddings stop encoding identity and
 * start encoding framing and background instead. That shows up downstream as
 * "everyone looks like the same person" or "the same person looks different
 * every frame", i.e. nonsense appearance counts.
 *
 * So: the generous crop is for the *collage tile* (per the assignment brief,
 * which is about output image quality). The aligned crop here is for the
 * *embedder*. They are deliberately two different images of the same face.
 *
 * Alignment is a 2-point similarity transform on the eye centres: rotate so
 * the eye line is horizontal, scale so the inter-eye distance matches the
 * canonical one, translate so the eyes land on the canonical positions. If
 * ML Kit didn't give us both eyes, we fall back to a tight box crop, which is
 * still far closer to the training distribution than the generous crop was.
 */
object FaceAligner {

    const val OUTPUT_SIZE = 112

    // Canonical eye positions for a 112x112 ArcFace/MobileFaceNet-style crop.
    private const val LEFT_EYE_X = 38.29f
    private const val LEFT_EYE_Y = 51.69f
    private const val RIGHT_EYE_X = 73.53f
    private const val RIGHT_EYE_Y = 51.50f

    /** Margin used by the no-landmark fallback: tight-ish, not the generous collage margin. */
    private const val FALLBACK_MARGIN_RATIO = 0.15f

    fun align(frame: Bitmap, bbox: Rect, leftEye: PointF?, rightEye: PointF?): Bitmap {
        if (leftEye == null || rightEye == null) {
            return fallbackCrop(frame, bbox)
        }

        val output = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Note: ML Kit's LEFT_EYE is the subject's left eye, which appears on the
        // right side of the image. Canonical LEFT_EYE_X/Y are image-space, so we
        // pair image-left with the subject's right eye.
        val imageLeftEye = rightEye
        val imageRightEye = leftEye

        val dx = imageRightEye.x - imageLeftEye.x
        val dy = imageRightEye.y - imageLeftEye.y
        val srcEyeDistance = hypot(dx, dy)
        if (srcEyeDistance < 1f) return fallbackCrop(frame, bbox)

        val dstEyeDistance = RIGHT_EYE_X - LEFT_EYE_X
        val scale = dstEyeDistance / srcEyeDistance
        val angleDegrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

        // These MUST be post* calls in this order, and the distinction is not
        // cosmetic. Android's Matrix applies post* as M = X * M, so calling
        // them in this order composes T(dest) * S * R * T(-eye), which maps a
        // point as: shift the image-left eye to the origin, undo the head
        // roll, scale to canonical eye spacing, then place the eye at its
        // canonical destination -- eyes land exactly on
        // (LEFT_EYE_X, LEFT_EYE_Y) / (RIGHT_EYE_X, RIGHT_EYE_Y).
        //
        // pre* composes the same four calls in the opposite direction
        // (M = M * X), which applies the destination shift FIRST and the
        // -eye shift LAST. Worked numerically for a face with eyes at
        // (500,400)/(560,430): the pre* form puts the left eye at
        // (-140.96, -314.23) -- entirely off a 112x112 canvas -- and the
        // resulting crop samples source pixels around y=1055..1338, i.e. the
        // bottom edge of the frame rather than the face. The embedder then
        // sees background instead of a person, every embedding collapses
        // toward "this video's background", and identity clustering becomes
        // noise. Do not "simplify" these back to pre*.
        val matrix = Matrix().apply {
            postTranslate(-imageLeftEye.x, -imageLeftEye.y)
            postRotate(-angleDegrees)
            postScale(scale, scale)
            postTranslate(LEFT_EYE_X, LEFT_EYE_Y)
        }

        canvas.drawBitmap(frame, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun fallbackCrop(frame: Bitmap, bbox: Rect): Bitmap {
        val rect = FaceCropUtils.expandedRect(frame, bbox, FALLBACK_MARGIN_RATIO)
        val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        if (crop.width == OUTPUT_SIZE && crop.height == OUTPUT_SIZE) return crop
        val scaled = Bitmap.createScaledBitmap(crop, OUTPUT_SIZE, OUTPUT_SIZE, true)
        if (scaled !== crop) crop.recycle()
        return scaled
    }
}
