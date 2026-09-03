package com.abhishek.collage.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.abhishek.collage.model.FrameSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Samples frames from a source video at a fixed interval, off the main thread.
 * Downscales to a manageable resolution up front so downstream ML Kit / TFLite
 * work stays fast across ~150 frames from a 30s clip.
 */
class FrameExtractor(private val context: Context) {

    companion object {
        const val SAMPLE_INTERVAL_MS = 200L // 5 fps
        const val MAX_LONG_EDGE_PX = 1280
    }

    /**
     * @param onProgress called with a 0f..1f fraction as frames are pulled.
     */
    suspend fun extract(
        videoUri: Uri,
        onProgress: (Float) -> Unit
    ): List<FrameSample> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<FrameSample>()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) return@withContext emptyList()

            val timestamps = (0..durationMs step SAMPLE_INTERVAL_MS).toList()
            for ((index, tsMs) in timestamps.withIndex()) {
                val raw = getFrameAt(retriever, tsMs) ?: continue
                val scaled = downscale(raw)
                if (scaled !== raw) raw.recycle()
                frames.add(FrameSample(tsMs, scaled))
                onProgress((index + 1) / timestamps.size.toFloat())
            }
        } finally {
            retriever.release()
        }
        frames
    }

    private fun getFrameAt(retriever: MediaMetadataRetriever, tsMs: Long): Bitmap? {
        val tsUs = tsMs * 1000
        // OPTION_CLOSEST, not OPTION_CLOSEST_SYNC: sync-only returns the nearest
        // *keyframe*, and keyframes are typically 1-3s apart, so sampling every
        // 200ms with SYNC hands back the same keyframe 5-15 times in a row and
        // never sees most of the video. OPTION_CLOSEST decodes the actual frame
        // at the requested time (slower, but correct).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                retriever.getScaledFrameAtTime(
                    tsUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_LONG_EDGE_PX,
                    MAX_LONG_EDGE_PX
                )
            }.getOrNull() ?: retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } else {
            retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE_PX) return bitmap
        val scale = MAX_LONG_EDGE_PX / longEdge.toFloat()
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}
