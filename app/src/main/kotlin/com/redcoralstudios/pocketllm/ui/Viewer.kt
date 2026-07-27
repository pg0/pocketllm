package com.redcoralstudios.pocketllm.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Full-screen view of an attachment, opened by tapping its thumbnail.
 *
 * Shows the file the app prepared rather than handing the original to another
 * app. That is deliberate: what is on screen here is exactly what the model was
 * given, which is the thing worth checking when an answer looks wrong - a
 * PDF page that rendered badly or a photo that came out sideways is invisible
 * at 64 dp.
 */
@Composable
fun ImageViewer(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        // Full resolution here, unlike the thumbnail: the point of opening it
        // is to see detail. The prepared files are at most 1536 px.
        value = ImageCache.decode(File(path), maxEdge = 2048)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Tap closes; double tap toggles between fit and 2x,
                        // which is what every photo viewer does.
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Attachment",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                    contentScale = ContentScale.Fit,
                )
            } ?: Text("Loading...", color = Color.White)
        }
    }
}

/**
 * The text that was actually extracted from a document.
 *
 * Worth having for the same reason as the image viewer: when the answer misses
 * something that is plainly in the file, this says whether the model failed or
 * the extraction did.
 */
@Composable
fun DocumentViewer(name: String, text: String, truncated: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (truncated) {
                        "${text.length} characters - the file was longer and was cut here."
                    } else {
                        "${text.length} characters, complete."
                    },
                    style = MaterialTheme.typography.labelSmall,
                )

                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            // Bounded, or a 6000-character file pushes the
                            // Close button off the bottom of the screen.
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
