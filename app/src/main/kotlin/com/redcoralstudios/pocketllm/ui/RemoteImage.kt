package com.redcoralstudios.pocketllm.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.redcoralstudios.pocketllm.net.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal disk-backed image loader.
 *
 * No image library: the app fetches at most one or two small images per answer,
 * already carries OkHttp, and a dependency whose whole job is caching and
 * transformation would be dead weight here.
 */
object ImageCache {

    private val tools = WebTools()

    private fun dir(context: Context): File =
        File(context.cacheDir, "web-images").also { if (!it.exists()) it.mkdirs() }

    suspend fun get(context: Context, url: String): File? {
        val file = File(dir(context), url.hashCode().toUInt().toString(16) + ".img")
        if (file.exists() && file.length() > 0) return file
        return tools.downloadImage(url, file)
    }

    /** Decodes at a bounded size: a chat bubble never needs a 4000 px scan. */
    suspend fun decode(file: File, maxEdge: Int = 1280): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0) return@runCatching null

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

                BitmapFactory
                    .decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    ?.asImageBitmap()
            }.getOrNull()
        }
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/**
 * Renders a remote image, falling back to its alt text.
 *
 * The fallback matters more than usual here: a local model asked for a picture
 * will happily invent a plausible-looking URL, and a dead link has to read as a
 * dead link rather than as a broken app.
 */
@Composable
fun RemoteImage(url: String, alt: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by produceState<ImageLoad>(ImageLoad.Loading, url) {
        val file = ImageCache.get(context, url)
        val bitmap = file?.let { ImageCache.decode(it) }
        value = if (bitmap != null) ImageLoad.Ready(bitmap) else ImageLoad.Failed
    }
    ImageBody(state, alt, modifier)
}

/** Same, for a file the app already downloaded. */
@Composable
fun LocalImage(path: String, alt: String, modifier: Modifier = Modifier) {
    val state by produceState<ImageLoad>(ImageLoad.Loading, path) {
        val bitmap = ImageCache.decode(File(path))
        value = if (bitmap != null) ImageLoad.Ready(bitmap) else ImageLoad.Failed
    }
    ImageBody(state, alt, modifier)
}

/**
 * Square thumbnail of a local file, for attachment chips and message bubbles.
 * Shows what was actually picked -- a chip reading "Image" tells the user
 * nothing about whether they attached the right one.
 */
@Composable
fun LocalThumbnail(path: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = ImageCache.decode(File(path), maxEdge = 320)
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Attached image",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ImageBody(state: ImageLoad, alt: String, modifier: Modifier) {
    when (state) {
        is ImageLoad.Ready -> Image(
            bitmap = state.bitmap,
            contentDescription = alt.ifBlank { null },
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Fit,
        )

        ImageLoad.Loading -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = if (alt.isBlank()) "Loading image..." else "Loading $alt...",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        ImageLoad.Failed -> Text(
            text = if (alt.isBlank()) "[image unavailable]" else "[image unavailable: $alt]",
            style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
            modifier = modifier.padding(vertical = 2.dp),
        )
    }
}
