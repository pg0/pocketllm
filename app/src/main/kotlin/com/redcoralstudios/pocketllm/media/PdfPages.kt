package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PocketLLM-pdf"

/**
 * Renders a PDF into page images for the vision encoder.
 *
 * A PDF is a description of marks on a page, not a document with text in it -
 * words are drawn at coordinates, in whatever order the producer felt like, and
 * recovering reading order (let alone tables and columns) is what makes PDF
 * extraction libraries big. Android's own [PdfRenderer] hands out pixels and
 * nothing else.
 *
 * So the pages go to the vision encoder, which is a real advantage on the case
 * that defeats text extraction entirely: a scanned or photographed document has
 * no text layer at all, and looking at it is the only way to read it.
 *
 * The cost is honest and bounded: one page costs whatever the image detail
 * setting is worth in tokens, out of a window that also holds the question and
 * the answer, so the page limit is computed from both rather than fixed.
 */
object PdfPages {

    /**
     * Rendered generously and left to clip to resize.
     *
     * There is no point matching the encoder's target exactly: clip scales
     * every image to its own token budget anyway, and a crisp 2048 px render
     * downsampled by it beats a coarse render upscaled. The cost is disk and a
     * few hundred milliseconds, both of which are cheap here.
     */
    private const val RENDER_EDGE = 2048
    private const val JPEG_QUALITY = 85

    data class Pages(val files: List<File>, val note: String)

    suspend fun render(context: Context, uri: Uri, maxPages: Int): Result<Pages> =
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor: ParcelFileDescriptor =
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("Could not open that PDF.")

                descriptor.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val total = renderer.pageCount
                        if (total == 0) error("That PDF has no pages.")
                        val count = minOf(total, maxPages.coerceAtLeast(1))

                        val files = (0 until count).map { index ->
                            renderer.openPage(index).use { page ->
                                val scale = RENDER_EDGE.toFloat() / maxOf(page.width, page.height)
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)

                                val bitmap = Bitmap.createBitmap(
                                    width, height, Bitmap.Config.ARGB_8888,
                                )
                                // PDF pages are transparent where nothing is
                                // drawn, and transparent flattens to black in a
                                // JPEG - black paper with black text.
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap, null, null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )

                                val out = File(
                                    ImagePrep.cacheDir(context),
                                    "pdf_${System.currentTimeMillis()}_$index.jpg",
                                )
                                out.outputStream().use { stream ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                                }
                                bitmap.recycle()
                                out
                            }
                        }

                        Log.i(TAG, "rendered $count of $total pages")
                        Pages(
                            files = files,
                            note = if (total > count) {
                                "First $count of $total pages - the rest will not fit in the context."
                            } else {
                                "$count page${if (count == 1) "" else "s"} as images."
                            },
                        )
                    }
                }
            }
        }
}
