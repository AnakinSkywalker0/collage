package com.abhishek.collage.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Shader
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

    /**
     * Smallest one-step reduction the affine warp is allowed to perform.
     *
     * [Canvas.drawBitmap] with a matrix samples bilinearly: two source pixels
     * per axis per output pixel, no mip pyramid. Reducing by more than 2x in one
     * step therefore skips most of the source image rather than averaging it,
     * and what lands on the canvas is an aliased subsample. Below this factor we
     * halve first -- see [align].
     */
    private const val MIN_DIRECT_SCALE = 0.5f

    /**
     * Whether [align] can do a real eye-aligned warp for this detection, or has
     * to fall back to an unaligned box crop.
     *
     * Worth knowing downstream, because the two produce embeddings that are not
     * comparable to each other. MobileFaceNet is trained only on eye-aligned
     * faces; a box crop of the same person lands somewhere else in the embedding
     * space, so an aligned and an unaligned view of one person can score lower
     * against each other than two aligned views of different people.
     */
    fun usesLandmarks(leftEye: PointF?, rightEye: PointF?): Boolean =
        leftEye != null && rightEye != null &&
            hypot(rightEye.x - leftEye.x, rightEye.y - leftEye.y) >= 1f

    fun align(frame: Bitmap, bbox: Rect, leftEye: PointF?, rightEye: PointF?): Bitmap {
        if (leftEye == null || rightEye == null) {
            return fallbackCrop(frame, bbox)
        }

        // Note: ML Kit's LEFT_EYE is the subject's left eye, which appears on the
        // right side of the image. Canonical LEFT_EYE_X/Y are image-space, so we
        // pair image-left with the subject's right eye.
        val imageLeftEye = rightEye
        val imageRightEye = leftEye

        if (hypot(imageRightEye.x - imageLeftEye.x, imageRightEye.y - imageLeftEye.y) < 1f) {
            return fallbackCrop(frame, bbox)
        }

        val dstEyeDistance = RIGHT_EYE_X - LEFT_EYE_X

        // Pre-reduce by successive halving before the warp, whenever the face is
        // large enough that the warp alone would alias it.
        //
        // This is what makes the embedding scale-invariant in practice, and its
        // absence is visible in results: the same person filmed in a close-up and
        // in a wide shot produced embeddings far enough apart to cluster as two
        // people. A close-up can put the eyes 300px apart, which the warp has to
        // squeeze to 35 -- an 8x reduction, where bilinear sampling keeps a
        // sparse, speckled subsample. The wide shot needs no reduction and stays
        // smooth. The model then sees two different image statistics for one
        // face, and identity clustering has no way to know the difference came
        // from sampling rather than from the person.
        //
        // Halving is exactly a 2x2 box average, so every source pixel contributes
        // and no detail is dropped unaveraged. Repeat until the residual scale is
        // within what the warp handles cleanly; the warp then does the rotation
        // and the remaining sub-2x scaling in one pass, as before.
        var work = frame
        var workIsOurs = false
        var leftX = imageLeftEye.x
        var leftY = imageLeftEye.y
        var rightX = imageRightEye.x
        var rightY = imageRightEye.y

        while (dstEyeDistance / hypot(rightX - leftX, rightY - leftY) < MIN_DIRECT_SCALE &&
            work.width >= 2 * OUTPUT_SIZE && work.height >= 2 * OUTPUT_SIZE
        ) {
            val halved = Bitmap.createScaledBitmap(work, work.width / 2, work.height / 2, true)
            if (workIsOurs) work.recycle()
            work = halved
            workIsOurs = true
            leftX /= 2f; leftY /= 2f; rightX /= 2f; rightY /= 2f
        }

        val output = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val dx = rightX - leftX
        val dy = rightY - leftY
        val scale = dstEyeDistance / hypot(dx, dy)
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
            postTranslate(-leftX, -leftY)
            postRotate(-angleDegrees)
            postScale(scale, scale)
            postTranslate(LEFT_EYE_X, LEFT_EYE_Y)
        }

        // Drawn through a CLAMP shader rather than with canvas.drawBitmap, so
        // that any part of the 112x112 patch mapping outside the source frame
        // takes the nearest edge pixel instead of transparent black.
        //
        // This is not cosmetic. A face close enough to run off the edge of the
        // frame -- an extreme close-up, or someone at the side of the shot --
        // has an aligned patch that genuinely extends past the source, and
        // drawBitmap leaves those regions empty. MobileFaceNet then sees a face
        // with black bands across it and returns an embedding that encodes the
        // bands, not the person. Measured on a real clip: one such face scored
        // 0.078 against the person it belonged to and 0.077 against an unrelated
        // person -- indistinguishable, i.e. the embedding carried no identity at
        // all, which is what a corrupted input looks like rather than a hard one.
        // Edge clamping keeps the patch continuous, so what the model sees is a
        // face that runs to the border rather than a face with holes in it.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = BitmapShader(work, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        canvas.drawRect(0f, 0f, OUTPUT_SIZE.toFloat(), OUTPUT_SIZE.toFloat(), paint)
        if (workIsOurs) work.recycle()
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
