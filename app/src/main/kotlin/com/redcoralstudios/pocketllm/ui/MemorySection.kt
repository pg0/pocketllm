package com.redcoralstudios.pocketllm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.redcoralstudios.pocketllm.settings.MemorySnapshot
import com.redcoralstudios.pocketllm.settings.MemoryUsage
import kotlinx.coroutines.delay

/**
 * Live memory readout.
 *
 * Sits under the model list because that is what it explains: the RAM warning on
 * a model row is an estimate made from a file size, and this is the measurement
 * next to it. It is also the answer to "why did the app die mid-answer" -
 * nothing else in the app can show a phone that was already full before the
 * model loaded.
 *
 * Polls while the sheet is open and stops when it closes; [produceState] cancels
 * its coroutine on leaving composition.
 */
@Composable
fun MemorySection() {
    val context = LocalContext.current
    val snapshot by produceState<MemorySnapshot?>(initialValue = null, context) {
        while (true) {
            value = MemoryUsage.read(context)
            delay(REFRESH_MS)
        }
    }

    Text("Memory", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val m = snapshot
    if (m == null) {
        Text("Measuring", style = MaterialTheme.typography.labelSmall)
        return
    }

    Row(Modifier.fillMaxWidth()) {
        Text("This app", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(MemoryUsage.format(m.appBytes), style = MaterialTheme.typography.bodyMedium)
    }
    Column {
        Text(
            buildString {
                append("native heap ")
                append(MemoryUsage.format(m.nativeHeapBytes))
                append(" - code ")
                append(MemoryUsage.format(m.codeBytes))
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Phone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "${MemoryUsage.format(m.deviceUsedBytes)} of ${MemoryUsage.format(m.deviceTotalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (m.tight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { m.deviceUsedFraction },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        color = if (m.tight) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "${MemoryUsage.format(m.deviceAvailableBytes)} free" +
            if (m.lowMemory) " - the system is low on memory and killing background apps" else "",
        style = MaterialTheme.typography.labelSmall,
        color = if (m.lowMemory) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(6.dp))
    Text(
        "Model weights are memory-mapped rather than read into the heap, so the " +
            "app figure counts the parts of the GGUF currently resident and falls " +
            "when other apps need the RAM. A drop does not mean the model was " +
            "unloaded. The KV cache is a real allocation and grows with the " +
            "context size setting.",
        style = MaterialTheme.typography.labelSmall,
    )
}

private const val REFRESH_MS = 2000L
