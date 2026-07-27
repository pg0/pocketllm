package com.redcoralstudios.pocketllm.ui

import android.Manifest
import android.widget.Toast
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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

    // The composer text lives in the view model so dictation can write into it.
    val input by vm.input.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::attachImage) }

    // Audio is not a "visual media" type, so it needs the document picker.
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::attachAudio) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startDictation() }

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
                onInputChange = vm::setInput,
                busy = busy,
                recording = recording,
                webAccess = settings.webAccess,
                searchArmed = searchArmed,
                onToggleSearch = vm::toggleSearchArmed,
                wikipedia = settings.wikipediaGrounding,
                onToggleWikipedia = vm::toggleWikipedia,
                enabled = engineState is EngineState.Ready,
                onSend = { vm.send() },
                onStop = vm::stopGeneration,
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onMicDown = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                onMicUp = vm::stopDictation,
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
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyAll = {
        if (message.text.isNotBlank()) {
            clipboard.setText(AnnotatedString(message.text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
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
            modifier = Modifier
                .widthIn(max = 320.dp)
                // Catches double taps on the padding and around the text. The
                // text itself carries its own handler - see InlineText.
                .pointerInput(message.text) {
                    detectTapGestures(onDoubleTap = { copyAll() })
                },
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
                // SelectionContainer gives long-press selection with the normal
                // Android handles. It leaves single taps alone, so links inside
                // the markdown still open.
                SelectionContainer {
                    // User text is shown verbatim: it is what they typed, not
                    // markdown, and asterisks in a question stay asterisks.
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
                            onDoubleTap = copyAll,
                        )
                    } else if (message.streaming) {
                        Text("...", style = MaterialTheme.typography.bodyMedium)
                    }
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

/**
 * A retrieval source in the + menu: checked means it will be used, unchecked
 * means it will not. Neither is consulted on its own any more - a lookup
 * nobody asked for spends context on an article that may have nothing to do
 * with the question.
 */
@Composable
private fun SourceToggleItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    webAccess: Boolean,
    onClick: () -> Unit,
) {
    val active = on && webAccess
    DropdownMenuItem(
        text = { Text(label) },
        // Both need the network, so with web access off they are shown
        // disabled and say why rather than disappearing.
        enabled = webAccess,
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        },
        trailingIcon = {
            when {
                !webAccess -> Text(
                    "needs web access",
                    style = MaterialTheme.typography.labelSmall,
                )

                active -> Icon(
                    Icons.Default.Check,
                    contentDescription = "on",
                    tint = MaterialTheme.colorScheme.primary,
                )

                else -> Unit
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun InputRow(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    busy: Boolean,
    recording: Boolean,
    webAccess: Boolean,
    searchArmed: Boolean,
    onToggleSearch: () -> Unit,
    wikipedia: Boolean,
    onToggleWikipedia: () -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onPickAudio: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
) {
    var showAttachMenu by remember { mutableStateOf(false) }

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
        Box {
            IconButton(onClick = { showAttachMenu = true }, enabled = enabled && !busy) {
                // Tinted while a source is on. They are behind the menu now,
                // and a setting you cannot see is a setting that surprises you
                // three messages later.
                val armed = webAccess && (searchArmed || wikipedia)
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (armed) "Attach - a web source is on" else "Attach",
                    tint = if (armed) MaterialTheme.colorScheme.primary
                    else LocalContentColor.current,
                )
            }
            DropdownMenu(
                expanded = showAttachMenu,
                onDismissRequest = { showAttachMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Image") },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                    onClick = { showAttachMenu = false; onPickImage() },
                )
                DropdownMenuItem(
                    text = { Text("Audio file") },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) },
                    onClick = { showAttachMenu = false; onPickAudio() },
                )

                HorizontalDivider()

                // The two sources live in the menu rather than in the row: they
                // are toggles, not things you press every message, and every
                // icon out here comes straight off the width of the text field.
                SourceToggleItem(
                    label = "Web search",
                    icon = Icons.Default.Public,
                    on = searchArmed,
                    webAccess = webAccess,
                    onClick = { showAttachMenu = false; onToggleSearch() },
                )
                SourceToggleItem(
                    label = "Wikipedia",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    on = wikipedia,
                    webAccess = webAccess,
                    onClick = { showAttachMenu = false; onToggleWikipedia() },
                )
            }
        }

        // Dictation, not audio-to-model: the text lands in the field where it
        // can be read and corrected before it costs any context.
        IconButton(
            onClick = { if (recording) onMicUp() else onMicDown() },
            enabled = enabled && !busy,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = if (recording) "Stop dictation" else "Dictate",
                tint = if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (recording) "Listening..." else "Message") },
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
