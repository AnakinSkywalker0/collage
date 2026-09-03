package com.abhishek.collage.collage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Shares the generated collage through the standard Android share sheet via
 * a FileProvider-issued content:// URI (see xml/file_paths.xml + the
 * <provider> entry in the manifest).
 */
object ShareUtil {

    suspend fun share(context: Context, bitmap: Bitmap) {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "collage_share.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share collage").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
