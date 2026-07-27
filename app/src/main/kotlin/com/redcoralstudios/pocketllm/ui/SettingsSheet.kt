package com.redcoralstudios.pocketllm.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redcoralstudios.pocketllm.llm.Dials
import com.redcoralstudios.pocketllm.model.ModelCatalog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Response style", style = MaterialTheme.typography.titleMedium)
                    Text(
                        Dials.describe(settings.creativity, settings.factuality),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = vm::resetDials) { Text("Reset") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "These two are mostly two ends of one axis, but not quite. Creativity " +
                    "changes how the model picks words - higher means more varied " +
                    "phrasing and more willingness to speculate. Fact-checking changes " +
                    "what it is willing to claim - higher means it sticks to what it is " +
                    "confident about and says \"I don't know\" instead of guessing.\n\n" +
                    "Because they pull against each other, fact-checking caps creativity: " +
                    "at maximum fact-checking, creativity can only reach a quarter of its " +
                    "range. Without that, sliding creativity up would quietly cancel the " +
                    "fact-checking you asked for.\n\n" +
                    "The one combination this cannot express is \"playful wording but " +
                    "strictly accurate\" - for that, write it into the system prompt below.",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(16.dp))

            DialRow(
                label = "Creativity",
                help = "Higher means more varied wording and more willingness to speculate.",
                value = settings.creativity,
                onChange = vm::setCreativity,
            )

            DialRow(
                label = "Fact-checking",
                help = "Higher means the model sticks to what it is confident about and says " +
                    "\"I don't know\" instead of guessing. This also caps creativity, because " +
                    "the two settings pull against each other.",
                value = settings.factuality,
                onChange = vm::setFactuality,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SystemPromptEditor(
                value = settings.systemPrompt,
                generated = vm.generatedSystemPrompt(),
                onChange = vm::setSystemPrompt,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCatalog.all.forEach { spec ->
                    FilterChip(
                        selected = spec.id == settings.modelId,
                        onClick = { vm.selectModel(spec) },
                        label = { Text(spec.displayName.substringBefore(" (")) },
                    )
                }
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
                        if (vm.hasProjector(active)) "image/voice: available"
                        else "image/voice: encoder missing"
                    )
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.downloadModel(active) }) { Text("Download / repair") }
                TextButton(onClick = { vm.deleteModel(active) }) { Text("Delete files") }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Behaviour", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            ToggleRow(
                label = "Load model on launch",
                help = "Off means you have to start the model manually.",
                checked = settings.autoLoad,
                onChange = vm::setAutoLoad,
            )

            ToggleRow(
                label = "Web access",
                help = "Off means a conversation never touches the network. On, a link in " +
                    "your message gets fetched and read, and the two sources in the + menu " +
                    "become available. Your text is sent to those sites.",
                checked = settings.webAccess,
                onChange = vm::setWebAccess,
            )

            ToggleRow(
                label = "Wikipedia grounding",
                help = "Same switch as Wikipedia in the + menu. On, a fact-shaped question " +
                    "fetches the matching article and ranks it above every other source. " +
                    "English article first - it is usually longer and better cited - and " +
                    "the answer comes back in the language you asked in. Needs web access.",
                checked = settings.wikipediaGrounding,
                onChange = vm::setWikipediaGrounding,
            )

            ToggleRow(
                label = "Image/voice encoder on GPU",
                help = "Faster attachment handling where the driver supports it. " +
                    "Turn off if attachments crash.",
                checked = settings.projectorOnGpu,
                onChange = vm::setProjectorOnGpu,
            )

            Spacer(Modifier.height(16.dp))
            StepperRow(
                label = "Context window",
                value = settings.contextSize,
                suffix = "tokens",
                steps = listOf(2048, 4096, 8192, 16384),
                onChange = vm::setContextSize,
                help = "Longer conversations before a reset is needed. Costs RAM. " +
                    "Applies after the model is reloaded.",
            )

            StepperRow(
                label = "Max reply length",
                value = settings.maxTokens,
                suffix = "tokens",
                steps = listOf(256, 512, 1024, 2048, 4096),
                onChange = vm::setMaxTokens,
                help = "Hard stop for a single answer.",
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "PocketLLM ${vm.appVersion}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Lets the system prompt be read and rewritten.
 *
 * Empty means "generate it from the dials", and the generated text shows as the
 * placeholder so it can be seen before deciding to override it. "Edit generated"
 * copies it into the field as a starting point.
 */
@Composable
private fun SystemPromptEditor(
    value: String,
    generated: String,
    onChange: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    val custom = value.isNotBlank()

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("System prompt", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(
                if (custom) "custom" else "automatic",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (custom) {
                "Your text is sent verbatim. The dials still control sampling, but the " +
                    "fact-checking wording above is no longer added - put any grounding " +
                    "rules you want directly in here."
            } else {
                "Generated from the dials. Type here to override it."
            },
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                onChange(it)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            placeholder = { Text(generated, style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    draft = generated
                    onChange(generated)
                },
            ) { Text("Edit generated") }

            TextButton(
                enabled = custom,
                onClick = {
                    draft = ""
                    onChange("")
                },
            ) { Text("Back to automatic") }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Takes effect on the next new chat - the prompt is the first turn of a " +
                "conversation.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun DialRow(
    label: String,
    help: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$value", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = Dials.MIN.toFloat()..Dials.MAX.toFloat(),
        )
        Text(help, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    suffix: String,
    steps: List<Int>,
    onChange: (Int) -> Unit,
    help: String,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("$label - $value $suffix", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { step ->
                FilterChip(
                    selected = step == value,
                    onClick = { onChange(step) },
                    label = { Text("$step") },
                )
            }
        }
        Text(help, style = MaterialTheme.typography.labelSmall)
    }
}
