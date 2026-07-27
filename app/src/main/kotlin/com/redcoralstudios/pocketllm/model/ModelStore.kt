package com.redcoralstudios.pocketllm.model

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Owns the on-disk location of model files.
 *
 * Files live in app-specific external storage: large enough for multi-GB
 * weights, needs no storage permission, and is removed when the app is
 * uninstalled so a 4 GB download cannot be orphaned.
 */
class ModelStore(private val context: Context) {

    val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
            .also { if (!it.exists()) it.mkdirs() }

    fun fileFor(file: RemoteFile): File = File(dir, file.localName)

    /** Partial download target; renamed into place only once the size matches. */
    fun partFileFor(file: RemoteFile): File = File(dir, file.localName + ".part")

    /**
     * A file counts as present only at its exact expected size. A truncated
     * download would otherwise surface as an unreadable-GGUF crash much later.
     */
    fun isComplete(file: RemoteFile): Boolean = fileFor(file).length() == file.sizeBytes

    fun bytesOnDisk(file: RemoteFile): Long {
        val done = fileFor(file)
        if (done.exists()) return done.length()
        return partFileFor(file).length()
    }

    fun isReady(spec: ModelSpec): Boolean = isComplete(spec.weights)

    /** Attachments need the projector; text-only chat does not. */
    fun hasProjector(spec: ModelSpec): Boolean =
        spec.projector?.let { isComplete(it) } ?: false

    fun delete(spec: ModelSpec) {
        listOfNotNull(spec.weights, spec.projector).forEach {
            fileFor(it).delete()
            partFileFor(it).delete()
        }
    }

    fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    companion object {
        fun totalRamGb(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            // Round up: a "8 GB" phone reports ~7.4 GB of addressable RAM.
            return ((info.totalMem + (1L shl 29)) / (1L shl 30)).toInt()
        }

        /** Threads for inference: physical cores minus a couple for the UI. */
        fun inferenceThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
