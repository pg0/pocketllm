package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PocketLLM-image"

/**
 * Turns a picked image into a modest JPEG on disk that the native layer can read.
 *
 * A modern phone photo is 12 MP and 5 MB. The vision encoder downsamples it
 * anyway, so decoding it at full size only risks an OOM on an 8 GB device that
 * is already holding a 3 GB model.
 */
object ImagePrep {

    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 90

    suspend fun prepare(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            // NOTE: decodeStream returns null by design when inJustDecodeBounds
            // is set -- the result lands in `bounds`, not in a bitmap. An elvis
            // on this call therefore always fires, which is exactly how every
            // picked image used to be dropped silently. Check the *stream*.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
                true
            }
            if (opened != true) {
                Log.w(TAG, "could not open $uri")
                return@runCatching null
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "undecodable image: ${bounds.outMimeType}")
                return@runCatching null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            if (decoded == null) {
                Log.w(TAG, "decode failed for $uri")
                return@runCatching null
            }

            val scaled = scaleToMaxEdge(decoded)
            val rotated = applyExifRotation(context, uri, scaled)

            val out = File(cacheDir(context), "img_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { stream ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            if (rotated !== decoded) decoded.recycle()
            out
        }.getOrNull()
    }

    fun cacheDir(context: Context): File =
        File(context.cacheDir, "attachments").also { if (!it.exists()) it.mkdirs() }

    /**
     * A content uri the camera app can write one full-size photo into.
     *
     * It lands in the same cache as everything else attached, so [clearCache]
     * takes the original away with the downscaled copy [prepare] makes of it.
     */
    fun captureTarget(context: Context): Uri {
        val file = File(cacheDir(context), "cam_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Drops attachments from previous sessions. */
    fun clearCache(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_EDGE && h / 2 >= MAX_EDGE) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Camera photos carry their orientation in EXIF rather than in the pixels.
     * Without this, portrait shots reach the model on their side.
     */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
