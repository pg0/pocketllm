package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PocketLLM-video"

/**
 * Turns a video into a handful of still frames.
 *
 * llama.cpp's own video helper shells out to ffmpeg, which does not exist on
 * Android, so `MTMD_VIDEO` is off and there is no native video path at all.
 * Frames are extracted here instead and handed to the vision encoder as an
 * ordered sequence of images.
 *
 * The frame count is the whole design question. Each frame costs roughly 250
 * tokens of a 4096-token context that also has to hold the question, the answer
 * and the rest of the conversation. Gemma 4 nominally accepts a minute of video
 * at 1 fps; sixty frames would be about 15,000 tokens, so that is not available
 * here. Six evenly spaced frames is the compromise: enough to follow what
 * happens, cheap enough to leave room to talk about it.
 *
 * Audio is not extracted. A video's soundtrack would need its own encoder pass
 * and would double the cost of an already expensive attachment.
 */
object VideoPrep {

    const val DEFAULT_FRAMES = 6
    private const val MAX_EDGE = 768

    data class Frames(val files: List<File>, val durationMs: Long, val note: String)

    suspend fun extractFrames(
        context: Context,
        uri: Uri,
        count: Int = DEFAULT_FRAMES,
    ): Result<Frames> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)

                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: error("Could not read that video.")

                if (duration <= 0L) error("That video has no readable duration.")

                val files = mutableListOf<File>()
                // Sample strictly inside the clip: the very first and last
                // frames are routinely black or a fade.
                for (i in 0 until count) {
                    val fraction = (i + 0.5) / count
                    val atUs = (duration * 1000 * fraction).toLong()
                    val frame = retriever.getFrameAtTime(
                        atUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: continue

                    val scaled = scale(frame)
                    val out = File(
                        ImagePrep.cacheDir(context),
                        "vid_${System.currentTimeMillis()}_$i.jpg",
                    )
                    out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (scaled !== frame) frame.recycle()
                    files += out
                }

                if (files.isEmpty()) error("No frames could be read from that video.")

                Log.i(TAG, "extracted ${files.size} frames from ${duration}ms of video")
                Frames(
                    files = files,
                    durationMs = duration,
                    note = "${files.size} frames sampled from ${formatDuration(duration)} of video",
                )
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
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

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
