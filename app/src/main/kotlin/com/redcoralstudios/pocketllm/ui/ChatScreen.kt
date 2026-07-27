package com.redcoralstudios.pocketllm.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redcoralstudios.pocketllm.llm.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingAttachments.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val recording by vm.isRecording.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val searchArmed by vm.searchArmed.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val attachError by vm.attachError.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::attachImage) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startRecording() }

    if (showSettings) {
        SettingsSheet(vm = vm, onDismiss = { showSettings = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { StatusTitle(engineState) },
                actions = {
                    IconButton(onClick = vm::newChat, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Default.Add, contentDescription = "New chat")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            when (val s = engineState) {
                is EngineState.Loading -> LoadingBar(s.progress)
                is EngineState.MissingFiles -> ModelSetupCard(vm, download)
                is EngineState.Failed -> ErrorCard(s.message, vm::retryLoad)
                else -> Unit
            }

            if (download.error != null) {
                ErrorCard("Download failed: ${download.error}", vm::dismissDownloadError)
            }

            attachError?.let { ErrorCard(it, vm::dismissAttachError) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { MessageBubble(it) }
            }

            if (pending.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pending.forEach { attachment ->
                        if (attachment.isAudio) {
                            AssistChip(
                                onClick = { vm.removeAttachment(attachment) },
                                label = { Text("Voice clip") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        Modifier.size(16.dp),
                                    )
                                },
                            )
                        } else {
                            AttachmentThumbnail(
                                path = attachment.path,
                                onRemove = { vm.removeAttachment(attachment) },
                            )
                        }
                    }
                }
            }

            // A named, animated status line. Without it a slow turn is just a
            // Stop button and no way to tell searching from thinking from stuck.
            if (activity != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "$activity...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            InputRow(
                input = input,
                onInputChange = { input = it },
                busy = busy,
                recording = recording,
                webAccess = settings.webAccess,
                searchArmed = searchArmed,
                onToggleSearch = vm::toggleSearchArmed,
                enabled = engineState is EngineState.Ready,
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = vm::stopGeneration,
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onMicDown = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onMicUp = vm::stopRecording,
            )
        }
    }
}

@Composable
private fun StatusTitle(state: EngineState) {
    val (title, subtitle) = when (state) {
        is EngineState.Ready -> state.spec.displayName to "ready"
        is EngineState.Loading -> "Loading model" to "${(state.progress * 100).toInt()}%"
        is EngineState.MissingFiles -> "PocketLLM" to "model not downloaded"
        is EngineState.Failed -> "PocketLLM" to "load failed"
        EngineState.Idle -> "PocketLLM" to "starting"
    }
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LoadingBar(progress: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Loading model", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        // Progress is reported by llama.cpp while it maps the weights.
        if (progress <= 0f) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModelSetupCard(vm: ChatViewModel, download: DownloadState) {
    val spec = vm.currentSpec()
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
            Text(spec.summary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "One-time download of ${"%.1f".format(spec.totalBytes / 1_000_000_000.0)} GB " +
                    "(weights plus the image/voice encoder). Resumes if interrupted.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (download.active) {
                Text(download.label, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(onClick = { vm.downloadModel() }) { Text("Download") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** Picked image plus a remove badge, so the pick is visibly the right photo. */
@Composable
private fun AttachmentThumbnail(path: String, onRemove: () -> Unit) {
    Box {
        LocalThumbnail(path, size = 64.dp)
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(bottomStart = 8.dp),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove image",
                    Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    val bg = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                // What was sent, shown as what it was: the picture itself for
                // images, a label for a voice clip there is nothing to show for.
                if (message.attachments.isNotEmpty()) {
                    val images = message.attachments.filterNot { it.isAudio }
                    if (images.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            images.forEach { LocalThumbnail(it.path, size = 96.dp) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (message.attachments.any { it.isAudio }) {
                        Text("voice", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                // User text is shown verbatim: it is what they typed, not
                // markdown, and asterisks in a question should stay asterisks.
                if (isUser || message.isError) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (message.text.isNotEmpty()) {
                    MarkdownText(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (message.streaming) {
                    Text("...", style = MaterialTheme.typography.bodyMedium)
                }

                if (message.image != null) {
                    Spacer(Modifier.height(6.dp))
                    LocalImage(message.image.path, message.image.caption)
                    if (message.image.caption.isNotBlank()) {
                        Text(
                            message.image.caption,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (message.note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(message.note, style = MaterialTheme.typography.labelSmall)
                }

                // Showing what was actually fetched keeps "it searched the web"
                // from being something the user has to take on trust.
                if (message.sources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sources",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    message.sources.forEach { source ->
                        Text(
                            source,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputRow(
    input: String,
    onInputChange: (String) -> Unit,
    busy: Boolean,
    recording: Boolean,
    webAccess: Boolean,
    searchArmed: Boolean,
    onToggleSearch: () -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // No navigationBarsPadding() here: Scaffold's content padding
            // already carries the navigation-bar inset, and applying it twice
            // is what left a band of dead space under the input row.
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPickImage, enabled = enabled && !busy) {
            Icon(Icons.Default.Image, contentDescription = "Attach image")
        }

        if (webAccess) {
            IconButton(onClick = onToggleSearch, enabled = enabled && !busy) {
                Icon(
                    Icons.Default.Public,
                    contentDescription = if (searchArmed) "Web search on - tap to turn off"
                    else "Turn on web search",
                    tint = if (searchArmed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(
            onClick = { if (recording) onMicUp() else onMicDown() },
            enabled = enabled && !busy,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = if (recording) "Stop recording" else "Record voice",
                tint = if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (recording) "Recording..." else "Message") },
            enabled = enabled,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        )

        Box {
            if (busy) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = onSend, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
