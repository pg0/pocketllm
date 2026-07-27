package com.redcoralstudios.pocketllm.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Progress of a single file download. */
sealed interface DownloadProgress {
    data class Running(val bytesDone: Long, val bytesTotal: Long, val fileName: String) : DownloadProgress {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else bytesDone.toFloat() / bytesTotal
    }

    data class Done(val fileName: String) : DownloadProgress
    data class Failed(val fileName: String, val reason: String) : DownloadProgress
}

/**
 * Resumable HTTP download for model files.
 *
 * Multi-GB downloads over mobile networks get interrupted, so every transfer
 * writes to a `.part` file and resumes with a Range request. The file is only
 * renamed into place once its length matches the size the catalog expects.
 */
class ModelDownloader(private val store: ModelStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout: a slow CDN stream is not a failure.
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * @param token Hugging Face access token, needed for gated repos. Sent as a
     *        bearer header to huggingface.co and nowhere else.
     */
    fun download(remote: RemoteFile, token: String? = null): Flow<DownloadProgress> = flow {
        val target = store.fileFor(remote)
        if (target.length() == remote.sizeBytes) {
            emit(DownloadProgress.Done(remote.fileName))
            return@flow
        }
        // Wrong size means a corrupt or interrupted previous run; start over.
        if (target.exists()) target.delete()

        val part = store.partFileFor(remote)
        var offset = part.length()
        if (offset > remote.sizeBytes) {
            part.delete()
            offset = 0
        }

        val needed = remote.sizeBytes - offset
        if (store.freeSpaceBytes() < needed + SAFETY_MARGIN_BYTES) {
            emit(DownloadProgress.Failed(remote.fileName, "not enough free storage"))
            return@flow
        }

        val request = Request.Builder()
            .url(remote.url)
            .apply {
                if (offset > 0) header("Range", "bytes=$offset-")
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        401, 403 -> "access denied - this repo needs a Hugging Face token"
                        404 -> "file not found in the repo"
                        else -> "HTTP ${response.code}"
                    }
                    emit(DownloadProgress.Failed(remote.fileName, reason))
                    return@flow
                }
                // A server that ignores Range replies 200 and restarts the body.
                if (offset > 0 && response.code != 206) {
                    part.delete()
                    offset = 0
                }

                val body = response.body ?: run {
                    emit(DownloadProgress.Failed(remote.fileName, "empty response"))
                    return@flow
                }

                emit(DownloadProgress.Running(offset, remote.sizeBytes, remote.fileName))

                RandomAccessFile(part, "rw").use { out ->
                    out.seek(offset)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = offset
                    var lastEmit = 0L

                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            done += read

                            // Throttle: emitting per 8 KB chunk would flood the UI.
                            if (done - lastEmit >= EMIT_EVERY_BYTES) {
                                lastEmit = done
                                emit(DownloadProgress.Running(done, remote.sizeBytes, remote.fileName))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Keep the .part file: the next attempt resumes from here.
            emit(DownloadProgress.Failed(remote.fileName, e.message ?: e::class.java.simpleName))
            return@flow
        }

        if (part.length() != remote.sizeBytes) {
            emit(
                DownloadProgress.Failed(
                    remote.fileName,
                    "incomplete (${part.length()} of ${remote.sizeBytes} bytes)",
                )
            )
            return@flow
        }

        if (!renameInto(part, target)) {
            emit(DownloadProgress.Failed(remote.fileName, "could not finalise the file"))
            return@flow
        }
        emit(DownloadProgress.Done(remote.fileName))
    }.flowOn(Dispatchers.IO)

    private fun renameInto(part: File, target: File): Boolean {
        if (target.exists()) target.delete()
        return part.renameTo(target)
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 16          // 64 KB
        const val EMIT_EVERY_BYTES = 2L shl 20     // 2 MB
        const val SAFETY_MARGIN_BYTES = 256L shl 20
    }
}
