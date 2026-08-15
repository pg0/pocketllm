package com.redcoralstudios.pocketllm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redcoralstudios.pocketllm.llm.EngineState
import com.redcoralstudios.pocketllm.model.CustomModels
import com.redcoralstudios.pocketllm.model.ModelCatalog
import com.redcoralstudios.pocketllm.model.ModelSpec
import com.redcoralstudios.pocketllm.model.RepoFile

/**
 * The model section of the settings sheet.
 *
 * Was two chips while there were exactly two models. Anything the user adds
 * from Hugging Face lands in the same list, so it has to be a list that grows,
 * and each row has to say enough to choose by: size, whether it is on disk, and
 * whether it will fit in this phone's RAM.
 */
@Composable
fun ModelSection(vm: ChatViewModel, selectedId: String) {
    val engine by vm.engineState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Model", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { adding = true }) { Text("Add from Hugging Face") }
    }
    Spacer(Modifier.height(4.dp))

    vm.availableModels().forEach { spec ->
        ModelRow(
            spec = spec,
            selected = spec.id == selectedId,
            downloaded = vm.isDownloaded(spec),
            fits = vm.fitsInRam(spec),
            onSelect = { vm.selectModel(spec) },
            onRemove = { vm.removeCustomModel(spec) }.takeIf { spec.isCustom },
        )
    }

    val active = vm.currentSpec()
    Spacer(Modifier.height(6.dp))
    Text(
        buildString {
            append(active.summary)
            append("\n")
            append(if (vm.isDownloaded(active)) "Weights: downloaded" else "Weights: not downloaded")
            append(" - ")
            append(
                when {
                    active.projector == null -> "text only, no image/voice encoder"
                    vm.hasProjector(active) -> "image/voice: available"
                    else -> "image/voice: encoder missing"
                }
            )
        },
        style = MaterialTheme.typography.labelSmall,
    )

    // A prompt format the app had to guess at does not fail loudly - the model
    // just answers worse, and that reads as a bad model rather than a bad
    // prompt. Say it once, here, where the model was chosen.
    (engine as? EngineState.Ready)?.let { ready ->
        if (ready.spec.id == active.id && ready.templateIsGuessed) {
            Spacer(Modifier.height(6.dp))
            Text(
                "This GGUF carries no chat template llama.cpp recognises, so prompts " +
                    "use the ChatML format as a guess. If replies are rambling or the " +
                    "model talks to itself, that is the likely cause.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { vm.downloadModel(active) }) { Text("Download / repair") }
        TextButton(onClick = { vm.deleteModel(active) }) { Text("Delete files") }
    }
    // Only offered when there is something to unload. Otherwise the button
    // would claim to free memory that is not currently held.
    if (engine is EngineState.Ready) {
        TextButton(onClick = vm::unloadModel) { Text("Unload model from memory") }
        Text(
            "Frees the RAM without leaving the app, and clears the conversation - " +
                "the model's memory of it lives in the same allocation. Closing the " +
                "app does not reliably do this: Android keeps the process cached " +
                "until something else needs the RAM.",
            style = MaterialTheme.typography.labelSmall,
        )
    }

    if (adding) {
        AddModelDialog(vm = vm, onDismiss = { adding = false; vm.clearRepo() })
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    selected: Boolean,
    downloaded: Boolean,
    fits: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(
                spec.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(CustomModels.gbLabel(spec.totalBytes))
                    append(if (downloaded) " - on disk" else " - not downloaded")
                    // Weights are memory-mapped, so "too big" shows up as
                    // thrashing or a kill mid-answer rather than a clean error.
                    if (!fits) append(" - needs ~${spec.minRamGb} GB RAM")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove ${spec.displayName}")
            }
        }
    }
}

/**
 * Paste a repo, pick a file.
 *
 * Deliberately not a search over the Hub: the useful filter ("which of these
 * runs on a phone") is not one the search API can express, and a list of
 * thousands of repos on a phone screen is worse than a paste field.
 */
@Composable
private fun AddModelDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val repo by vm.repo.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var weights by remember { mutableStateOf<RepoFile?>(null) }
    var projector by remember { mutableStateOf<RepoFile?>(null) }
    var token by remember { mutableStateOf(settings.hfToken) }

    val listing = repo.listing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Hugging Face repo") },
                    placeholder = { Text("unsloth/Qwen3-4B-Instruct-GGUF") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A GGUF repo. Paste the id or the page URL.",
                    style = MaterialTheme.typography.labelSmall,
                )

                // Only while nothing has been looked up yet. Once a listing is
                // on screen the choice has moved on to which file, and a list of
                // other repos underneath it is just noise.
                if (listing == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Or start from one of these", style = MaterialTheme.typography.titleSmall)
                    ModelCatalog.suggestedRepos.forEach { suggestion ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !repo.busy) {
                                    input = suggestion.repo
                                    vm.searchRepo(suggestion.repo)
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                suggestion.repo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(suggestion.note, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; vm.setHfToken(it) },
                    label = { Text("Access token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only needed for gated repos. Stored on this device and sent to " +
                        "huggingface.co only.",
                    style = MaterialTheme.typography.labelSmall,
                )

                Spacer(Modifier.height(12.dp))
                if (repo.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp))
                        Spacer(Modifier.height(0.dp))
                        Text("  Reading the repo", style = MaterialTheme.typography.labelMedium)
                    }
                }
                repo.error?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }

                if (listing != null) {
                    listing.notes.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }

                    if (listing.weights.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Weights", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Smaller quants are faster and fit more easily; Q4 is the " +
                                "usual compromise on a phone.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            listing.weights.forEach { file ->
                                FileRow(file, weights?.path == file.path) { weights = file }
                            }
                        }
                    }

                    if (listing.projectors.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Image / voice encoder", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Optional. Without it the model is text only.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        FileRow(null, projector == null) { projector = null }
                        listing.projectors.forEach { file ->
                            FileRow(file, projector?.path == file.path) { projector = file }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (listing == null) {
                TextButton(
                    onClick = { vm.searchRepo(input) },
                    enabled = input.isNotBlank() && !repo.busy,
                ) { Text("Look up") }
            } else {
                TextButton(
                    onClick = { weights?.let { vm.addCustomModel(listing.repo, it, projector) } },
                    enabled = weights != null,
                ) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One selectable file. A null [file] is the "no encoder" choice. */
@Composable
private fun FileRow(file: RepoFile?, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(
                file?.name ?: "None",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file != null) {
                Text(
                    CustomModels.gbLabel(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
