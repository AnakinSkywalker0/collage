package com.abhishek.collage.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.abhishek.collage.model.Person
import kotlin.math.ceil
import kotlin.math.min

/**
 * Renders a shareable, Instagram-Story-style grid collage: one rounded tile
 * per person, center-cropped to a consistent aspect ratio, with a bottom
 * gradient scrim carrying an appearance-count badge. Pure function of
 * List<Person> -> Bitmap so layout math is easy to reason about / test on
 * its own.
 */
object CollageComposer {

    private const val CANVAS_WIDTH = 1080
    private const val PADDING = 36f
    private const val GUTTER = 24f
    private const val TILE_ASPECT = 5f / 4f // height / width
    private const val CORNER_RADIUS = 28f
    private const val HEADER_HEIGHT = 120f

    fun compose(people: List<Person>): Bitmap {
        if (people.isEmpty()) {
            return placeholder()
        }

        // One person in a 2-column grid leaves half the canvas empty; give a
        // solo result the full width instead.
        val columns = when {
            people.size == 1 -> 1
            people.size <= 4 -> 2
            else -> 3
        }
        val rows = ceil(people.size / columns.toFloat()).toInt()
        val tileWidth = (CANVAS_WIDTH - PADDING * 2 - GUTTER * (columns - 1)) / columns
        val tileHeight = tileWidth * TILE_ASPECT
        val canvasHeight = (HEADER_HEIGHT + PADDING * 2 + rows * tileHeight + (rows - 1) * GUTTER).toInt()

        val output = Bitmap.createBitmap(CANVAS_WIDTH, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        drawBackground(canvas, CANVAS_WIDTH, canvasHeight)
        drawHeader(canvas, people.size)

        people.forEachIndexed { index, person ->
            val col = index % columns
            val row = index / columns
            val left = PADDING + col * (tileWidth + GUTTER)
            val top = HEADER_HEIGHT + PADDING + row * (tileHeight + GUTTER)
            drawTile(canvas, person, RectF(left, top, left + tileWidth, top + tileHeight))
        }

        return output
    }

    private fun placeholder(): Bitmap {
        val bmp = Bitmap.createBitmap(CANVAS_WIDTH, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawBackground(canvas, CANVAS_WIDTH, 400)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No faces detected", CANVAS_WIDTH / 2f, 200f, paint)
        return bmp
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(Color.parseColor("#1A1A2E"), Color.parseColor("#16213E"), Color.parseColor("#0F3460")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawHeader(canvas: Canvas, count: Int) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B8C1EC")
            textSize = 30f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("The Collage", PADDING, 60f, titlePaint)
        canvas.drawText(
            "$count ${if (count == 1) "person" else "people"} detected",
            PADDING, 100f, subtitlePaint
        )
    }

    private fun drawTile(canvas: Canvas, person: Person, rect: RectF) {
        canvas.save()

        val clipPath = Path().apply {
            addRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        drawCenterCropped(canvas, person.bestShot, rect)
        drawBottomScrim(canvas, rect)
        drawBadge(canvas, person.appearanceCount, rect)

        canvas.restore()
    }

    private fun drawCenterCropped(canvas: Canvas, bitmap: Bitmap, dest: RectF) {
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destAspect = dest.width() / dest.height()

        val src: Rect = if (srcAspect > destAspect) {
            // source wider than dest -> crop left/right
            val cropWidth = (bitmap.height * destAspect).toInt()
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            // source taller than dest -> crop top/bottom
            val cropHeight = (bitmap.width / destAspect).toInt()
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }

        canvas.drawBitmap(bitmap, src, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawBottomScrim(canvas: Canvas, rect: RectF) {
        val scrimHeight = rect.height() * 0.28f
        val scrimTop = rect.bottom - scrimHeight
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, rect.bottom,
                Color.TRANSPARENT, Color.parseColor("#CC000000"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect.left, scrimTop, rect.right, rect.bottom, paint)
    }

    private fun drawBadge(canvas: Canvas, appearanceCount: Int, rect: RectF) {
        val text = "×$appearanceCount"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = min(rect.width() * 0.13f, 40f)
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }
        val padding = 16f
        canvas.drawText(text, rect.left + padding, rect.bottom - padding, paint)
    }
}
