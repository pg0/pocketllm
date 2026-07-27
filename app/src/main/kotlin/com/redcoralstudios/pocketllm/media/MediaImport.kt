package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PocketLLM-media"

/**
 * Copies a picked audio file somewhere the native layer can open it.
 *
 * libmtmd takes a filesystem path, not a content URI or a stream, so a picked
 * file has to be materialised locally first.
 */
object MediaImport {

    /** Roughly a minute of speech. Longer clips crowd out the context window. */
    const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    /** What mtmd's decoder understands. */
    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "flac", "ogg", "m4a", "aac")

    suspend fun importAudio(context: Context, uri: Uri): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(context, uri) ?: "clip"
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext.isNotEmpty() && ext !in AUDIO_EXTENSIONS) {
                    error("$ext files are not supported - try WAV, MP3, FLAC or M4A.")
                }

                val target = File(
                    ImagePrep.cacheDir(context),
                    "aud_${System.currentTimeMillis()}.${ext.ifEmpty { "wav" }}",
                )
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: error("Could not open that file.")

                if (target.length() == 0L) {
                    target.delete()
                    error("That file is empty.")
                }
                if (target.length() > MAX_AUDIO_BYTES) {
                    target.delete()
                    error("That clip is too long - keep it under about a minute.")
                }
                Log.i(TAG, "imported audio ${target.name} (${target.length()} bytes)")
                target
            }
        }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
