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
 * The cost is honest and bounded: roughly 250 tokens a page against a 4096
 * window that also holds the question and the answer.
 */
object PdfPages {

    /**
     * Five pages, ~1250 tokens. Above this a document crowds out the
     * conversation, and the count is surfaced in the UI rather than a longer
     * file quietly arriving with its tail cut off.
     */
    const val MAX_PAGES = 5

    /**
     * Higher than a photo's 1024: a photo is of a scene, a page is of text, and
     * body copy at 1024 px on A4 is where the small print stops being legible.
     */
    private const val RENDER_EDGE = 1536
    private const val JPEG_QUALITY = 85

    data class Pages(val files: List<File>, val note: String)

    suspend fun render(context: Context, uri: Uri): Result<Pages> =
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor: ParcelFileDescriptor =
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("Could not open that PDF.")

                descriptor.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val total = renderer.pageCount
                        if (total == 0) error("That PDF has no pages.")
                        val count = minOf(total, MAX_PAGES)

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
