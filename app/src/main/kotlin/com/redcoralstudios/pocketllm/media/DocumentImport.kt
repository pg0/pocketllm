package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private const val TAG = "PocketLLM-doc"

/**
 * A document turned into text the model can read.
 *
 * Documents go into the **prompt**, not through an encoder: they are text, and
 * text costs a quarter of what a picture of the same text costs. PDFs are the
 * exception and are handled by [PdfPages], because a PDF is a description of
 * marks on a page and pulling reliable reading order out of one needs a real
 * library.
 */
data class DocumentText(
    val name: String,
    val text: String,
    /** Set when the file was longer than the context can hold. */
    val truncated: Boolean = false,
)

object DocumentImport {

    /**
     * About 1500 tokens. The window is 4096 and still has to hold the
     * conversation, the question and the answer, so a long document is cut
     * rather than allowed to evict everything else.
     */
    const val MAX_CHARS = 6_000

    /** Read as-is. Anything whose bytes are already the content. */
    private val PLAIN = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yml", "yaml",
        "log", "ini", "cfg", "conf", "kt", "java", "py", "js", "ts", "c", "cpp",
        "h", "sql", "sh", "rs", "go", "html", "htm",
    )

    /** Office formats that are a zip of XML underneath. */
    private const val DOCX = "docx"
    private const val XLSX = "xlsx"

    /**
     * The pre-2007 binary formats. Refused by name rather than by producing
     * mojibake: they are OLE compound files, and reading them needs a library
     * that would cost more than the case is worth.
     */
    private val LEGACY_OFFICE = setOf("doc", "xls", "ppt")

    /** What the picker offers. */
    val MIME_TYPES = arrayOf(
        "text/*",
        "application/pdf",
        "application/json",
        "application/xml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/msword",
        "application/vnd.ms-excel",
    )

    fun isPdf(context: Context, uri: Uri): Boolean =
        extensionOf(displayName(context, uri)) == "pdf" ||
            context.contentResolver.getType(uri) == "application/pdf"

    suspend fun read(context: Context, uri: Uri): Result<DocumentText> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(context, uri) ?: "document"
                val ext = extensionOf(name)

                val raw = when {
                    ext in LEGACY_OFFICE ->
                        error("Old .$ext files are not readable - save it as .${modernOf(ext)}, CSV or text.")

                    ext == DOCX -> readDocx(context, uri)
                    ext == XLSX -> readXlsx(context, uri)
                    ext in PLAIN || ext.isEmpty() -> readPlain(context, uri)

                    // An unknown extension that is really text reads fine, and
                    // one that is not produces obvious rubbish rather than a
                    // wrong answer, so it is worth trying.
                    else -> readPlain(context, uri)
                }

                if (raw.isBlank()) error("There is no readable text in that file.")

                val trimmed = raw.trim()
                Log.i(TAG, "read $name: ${trimmed.length} chars")
                DocumentText(
                    name = name,
                    text = trimmed.take(MAX_CHARS),
                    truncated = trimmed.length > MAX_CHARS,
                )
            }
        }

    private fun readPlain(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Capped read: a 40 MB log would otherwise be pulled into memory in
            // full only to have the first few thousand characters used.
            val buffer = CharArray(MAX_CHARS * 2)
            val read = input.reader().read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        } ?: error("Could not open that file.")

    /**
     * .docx is a zip; the text lives in word/document.xml as `<w:t>` runs.
     *
     * Hand-rolled rather than adding a library: this is one entry, two regexes,
     * and the alternative is several megabytes of APK for a format that is
     * mostly styling information the model does not want anyway.
     */
    private fun readDocx(context: Context, uri: Uri): String {
        val xml = zipEntry(context, uri, "word/document.xml")
            ?: error("That .docx file is damaged.")
        return docxToText(xml)
    }

    /** Split out from the file handling so the parsing can be tested. */
    internal fun docxToText(xml: String): String = xml
        // Paragraph and line breaks have to survive as newlines, or the whole
        // document arrives as one run-on sentence.
        .replace(Regex("</w:p>"), "\n")
        .replace(Regex("<w:br[^>]*/>"), "\n")
        .replace(Regex("<w:tab[^>]*/>"), "\t")
        .let { stripTags(it) }
        .replace(Regex("\n{3,}"), "\n\n")

    /**
     * .xlsx is a zip too, but the cell values are indices into a shared string
     * table, so the table has to be read before the sheet means anything.
     */
    private fun readXlsx(context: Context, uri: Uri): String {
        val shared = zipEntry(context, uri, "xl/sharedStrings.xml")
            ?.let(::sharedStrings)
            .orEmpty()

        val sheet = zipEntry(context, uri, "xl/worksheets/sheet1.xml")
            ?: error("That .xlsx file has no readable first sheet.")

        return sheetToText(sheet, shared)
    }

    /** The string table an .xlsx sheet's cells refer to by index. */
    internal fun sharedStrings(xml: String): List<String> =
        SI.findAll(xml).map { stripTags(it.value).trim() }.toList()

    /**
     * One row per line, cells tab-separated - which is to say CSV-shaped, the
     * form a model handles best. Formatting, formulas and merged cells are
     * dropped: the values are what a question about a spreadsheet is about.
     */
    internal fun sheetToText(sheet: String, shared: List<String>): String = buildString {
        for (row in ROW.findAll(sheet)) {
            val cells = CELL.findAll(row.value).map { cell ->
                val body = cell.value
                val value = V.find(body)?.groupValues?.get(1).orEmpty()
                when {
                    // t="s" means the value is an index into sharedStrings.
                    body.contains("t=\"s\"") ->
                        value.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()

                    body.contains("t=\"inlineStr\"") -> stripTags(body).trim()
                    else -> value
                }
            }.toList()

            if (cells.any { it.isNotBlank() }) {
                append(cells.joinToString("\t"))
                append('\n')
            }
        }
    }

    private fun zipEntry(context: Context, uri: Uri, path: String): String? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name == path }
                    ?.let { zip.readBytes().toString(Charsets.UTF_8) }
            }
        }

    internal fun stripTags(xml: String): String =
        xml.replace(TAG_RE, "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun modernOf(ext: String): String = when (ext) {
        "doc" -> "docx"
        "xls" -> "xlsx"
        else -> "pptx"
    }

    private fun extensionOf(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private val TAG_RE = Regex("<[^>]*>")
    private val SI = Regex("<si>.*?</si>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row[^>]*>.*?</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c[ >].*?(?:</c>|/>)", RegexOption.DOT_MATCHES_ALL)
    private val V = Regex("<v[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
}

/** Where a read document is parked until the message it belongs to is sent. */
data class PendingDocument(val name: String, val text: String, val truncated: Boolean) {
    /** Fed to the model as its own block, the way retrieved web text is. */
    fun block(): String = buildString {
        append("<document name=\"").append(name).append("\">\n")
        append(text)
        if (truncated) append("\n[... truncated: only the first part of the file is shown]")
        append("\n</document>")
    }

    companion object {
        fun of(doc: DocumentText) = PendingDocument(doc.name, doc.text, doc.truncated)
    }
}
