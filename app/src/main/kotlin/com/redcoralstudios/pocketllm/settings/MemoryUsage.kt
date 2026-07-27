package com.redcoralstudios.pocketllm.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.util.Locale

/**
 * A reading of what the app and the phone are using right now.
 *
 * All figures are bytes. [appBytes] is proportional set size, which is the only
 * number that means anything for a process holding a memory-mapped model: it
 * counts the pages of the GGUF actually resident and splits shared pages by how
 * many processes hold them, so it neither ignores the model nor charges the app
 * for the whole file.
 */
data class MemorySnapshot(
    val appBytes: Long,
    val nativeHeapBytes: Long,
    val codeBytes: Long,
    val deviceAvailableBytes: Long,
    val deviceTotalBytes: Long,
    /** The system is under pressure and about to start killing processes. */
    val lowMemory: Boolean,
) {
    val deviceUsedBytes: Long get() = (deviceTotalBytes - deviceAvailableBytes).coerceAtLeast(0)

    val deviceUsedFraction: Float
        get() = if (deviceTotalBytes > 0) deviceUsedBytes.toFloat() / deviceTotalBytes else 0f

    /** True once the phone is close enough to full that a load may be refused. */
    val tight: Boolean get() = lowMemory || deviceUsedFraction >= 0.90f
}

object MemoryUsage {

    fun read(context: Context): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val device = ActivityManager.MemoryInfo()
        am.getMemoryInfo(device)

        // Own process only, so this needs no permission and no pid.
        val self = Debug.MemoryInfo()
        Debug.getMemoryInfo(self)

        return MemorySnapshot(
            appBytes = self.totalPss * 1024L,
            nativeHeapBytes = stat(self, "summary.native-heap"),
            codeBytes = stat(self, "summary.code"),
            deviceAvailableBytes = device.availMem,
            deviceTotalBytes = device.totalMem,
            lowMemory = device.lowMemory,
        )
    }

    /** `getMemoryStat` hands back kilobytes as a string, and null for bad keys. */
    private fun stat(info: Debug.MemoryInfo, key: String): Long =
        info.getMemoryStat(key)?.toLongOrNull()?.times(1024L) ?: 0L

    /**
     * Sizes for a phone screen: gigabytes once past one, megabytes below that.
     * A decimal on a megabyte figure is noise at this scale.
     */
    fun format(bytes: Long): String = when {
        bytes >= GB -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / GB)
        bytes >= MB -> String.format(Locale.US, "%d MB", bytes / MB)
        else -> String.format(Locale.US, "%d kB", bytes / 1024)
    }

    private const val MB = 1L shl 20
    private const val GB = 1L shl 30
}
