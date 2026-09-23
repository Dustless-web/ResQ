package com.example.resq1.mesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PhotoPreparer(private val context: Context) {

    data class PreparedPhoto(
        val file: File,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val hash: String,
        val size: Long
    )

    fun prepare(
        uri: Uri,
        maxDim: Int = 1024,
        quality: Int = 80
    ): PreparedPhoto? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val mimeType = options.outMimeType ?: "image/jpeg"
        val originalWidth = options.outWidth
        val originalHeight = options.outHeight

        // Calculate scaling if needed
        var scale = 1
        if (originalWidth > maxDim || originalHeight > maxDim) {
            val halfWidth = originalWidth / 2
            val halfHeight = originalHeight / 2
            while ((halfWidth / scale) >= maxDim && (halfHeight / scale) >= maxDim) {
                scale *= 2
            }
        }

        // Decode with scaling
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
        val bitmapStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(bitmapStream, null, decodeOptions) ?: return null
        bitmapStream.close()

        // Final output file
        val tempFile = File(context.cacheDir, "mesh_transfer_${System.currentTimeMillis()}.jpg")
        val out = FileOutputStream(tempFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.close()
        bitmap.recycle()

        // Calculate Hash
        val hash = calculateSha256(tempFile)

        return PreparedPhoto(
            file = tempFile,
            mimeType = "image/jpeg",
            width = decodeOptions.outWidth,
            height = decodeOptions.outHeight,
            hash = hash,
            size = tempFile.length()
        )
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
