package com.abhishek.collage.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.abhishek.collage.pipeline.math.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device face embedding via a bundled TFLite model.
 *
 * Model: MobileFaceNet-class, 112x112 RGB input, single [1, EMBEDDING_DIM]
 * float output. See README for the exact model source and license.
 *
 * Place the .tflite file at app/src/main/assets/face_embedder.tflite before
 * building -- see README "Embedding model" section for where to get one.
 */
class FaceEmbedder(context: Context) {

    companion object {
        const val MODEL_ASSET = "face_embedder.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_DIM = 192
    }

    private val interpreter: Interpreter? = runCatching {
        Interpreter(loadModelFile(context, MODEL_ASSET), Interpreter.Options().apply {
            setNumThreads(4)
        })
    }.getOrNull()

    val isModelLoaded: Boolean get() = interpreter != null

    /**
     * Runs inference on a generous face crop, returns an L2-normalized
     * embedding. Returns null if the model failed to load (caller should
     * surface a clear error rather than silently skip embedding -- an app
     * that "detects but never embeds" fails the assignment's core requirement).
     */
    suspend fun embed(faceCrop: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        val interp = interpreter ?: return@withContext null
        val resized = Bitmap.createScaledBitmap(faceCrop, INPUT_SIZE, INPUT_SIZE, true)
        val input = bitmapToByteBuffer(resized)
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interp.run(input, output)
        if (resized !== faceCrop) resized.recycle()
        VectorMath.l2Normalize(output[0])
    }

    fun close() {
        interpreter?.close()
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            // MobileFaceNet-style preprocessing: scale to roughly [-1, 1].
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            buffer.putFloat((r - 127.5f) / 128f)
            buffer.putFloat((g - 127.5f) / 128f)
            buffer.putFloat((b - 127.5f) / 128f)
        }
        buffer.rewind()
        return buffer
    }

    private fun loadModelFile(context: Context, assetName: String): ByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}
