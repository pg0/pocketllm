package com.redcoralstudios.pocketllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small Markdown renderer for chat bubbles.
 *
 * Deliberately hand-rolled rather than pulled in as a dependency: the model
 * emits a narrow, predictable subset (headings, lists, bold, code, tables,
 * links) and a full CommonMark implementation would be several hundred
 * kilobytes to render text that is at most a few paragraphs long.
 *
 * It is also re-parsed on every streamed token, so the parser is line-based and
 * allocation-light on purpose. An unterminated code fence mid-stream renders as
 * a code block that runs to the end, which is what a half-arrived block should
 * look like.
 */

private const val URL_TAG = "URL"

// ------------------------------------------------------------------- blocks

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String, val indent: Int) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock
    data object Rule : MdBlock
}

/** `![alt](url)` on a line of its own. */
private val IMAGE_LINE = Regex("""^\s*!\[([^\]]*)]\(\s*(\S+?)(?:\s+"[^"]*")?\s*\)\s*$""")

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d{1,2})[.)]\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])\s*\1\s*\1[\s\-*_]*$""")
private val TABLE_DIVIDER = Regex("""^\s*\|?[\s:|-]+\|[\s:|-]*$""")

internal fun parseBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // Fenced code. An unclosed fence swallows the rest, which is the right
        // shape for a block that is still streaming in.
        if (line.trimStart().startsWith("```")) {
            flush()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                body.appendLine(lines[i])
                i++
            }
            i++ // closing fence, or past the end
            blocks += MdBlock.Code(body.toString().trimEnd('\n'))
            continue
        }

        // Pipe table: a header row followed by a |---|---| divider.
        if (line.contains('|') && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1])) {
            flush()
            val header = splitRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }

        when {
            line.isBlank() -> flush()

            IMAGE_LINE.matches(line) -> {
                flush()
                val m = IMAGE_LINE.find(line)!!
                blocks += MdBlock.Image(m.groupValues[1], m.groupValues[2])
            }

            RULE.matches(line) -> {
                flush()
                blocks += MdBlock.Rule
            }

            HEADING.matches(line) -> {
                flush()
                val m = HEADING.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }

            line.trimStart().startsWith("> ") -> {
                flush()
                blocks += MdBlock.Quote(line.trimStart().removePrefix("> "))
            }

            NUMBERED.matches(line) -> {
                flush()
                val m = NUMBERED.find(line)!!
                blocks += MdBlock.ListItem(
                    bullet = "${m.groupValues[2]}.",
                    text = m.groupValues[3],
                    indent = m.groupValues[1].length / 2,
                )
            }

            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                blocks += MdBlock.ListItem(
                    bullet = "•",
                    text = m.groupValues[2],
                    indent = m.groupValues[1].length / 2,
                )
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

// ------------------------------------------------------------------- inline

/**
 * One alternation covering every inline form, so the string is walked once.
 * Order matters: code first (its contents must stay literal), links before the
 * bare-URL catch-all.
 */
private val INLINE = Regex(
    """`([^`]+)`""" +
        """|\*\*(.+?)\*\*""" +
        """|__(.+?)__""" +
        """|~~(.+?)~~""" +
        """|\*(?!\s)([^*]+?)\*""" +
        // Image before link: otherwise the link branch eats the "[alt](url)"
        // half of "![alt](url)" and leaves a stray "!" in the text.
        """|!\[([^\]]*)]\(\s*(\S+?)(?:\s+"[^"]*")?\s*\)""" +
        """|\[([^\]]*)]\(\s*(\S+?)(?:\s+"[^"]*")?\s*\)""" +
        """|(https?://[^\s<>"')\]]+)""",
    RegexOption.DOT_MATCHES_ALL,
)

private fun AnnotatedString.Builder.link(url: String, label: String, linkColor: Color) {
    pushStringAnnotation(URL_TAG, url)
    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(label)
    }
    pop()
}

private fun AnnotatedString.Builder.appendInline(text: String, linkColor: Color) {
    var cursor = 0
    while (true) {
        val m = INLINE.find(text, cursor) ?: break
        if (m.range.first > cursor) append(text.substring(cursor, m.range.first))

        val g = m.groups
        when {
            g[1] != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            ) { append(g[1]!!.value) }

            g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(g[2]!!.value, linkColor)
            }

            g[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(g[3]!!.value, linkColor)
            }

            g[4] != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInline(g[4]!!.value, linkColor)
            }

            g[5] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(g[5]!!.value, linkColor)
            }

            // An image inside a sentence cannot be laid out as a picture, so it
            // becomes a link labelled with its alt text -- never raw syntax.
            g[7] != null -> link(g[7]!!.value, g[6]?.value?.ifBlank { "image" } ?: "image", linkColor)

            g[9] != null -> link(g[9]!!.value, g[8]?.value?.ifBlank { g[9]!!.value } ?: g[9]!!.value, linkColor)

            g[10] != null -> link(g[10]!!.value, g[10]!!.value, linkColor)
        }
        // Zero-width matches are impossible here, but guard anyway so a regex
        // change can never spin this loop.
        cursor = maxOf(m.range.last + 1, m.range.first + 1)
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private fun inlineOf(text: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString { appendInline(text, linkColor) }

// ----------------------------------------------------------------- rendering

/** A run of inline markdown whose links open in the browser when tapped. */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { inlineOf(text, linkColor) }
    val uriHandler = LocalUriHandler.current

    // ClickableText does not inherit the ambient content colour the way Text
    // does, so the base colour has to be folded into the style here.
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset)
                .firstOrNull()
                ?.let { runCatching { uriHandler.openUri(it.item) } }
        },
    )
}

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val muted = color.copy(alpha = 0.7f)

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Box(Modifier.padding(top = 4.dp))
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, style, color)

                is MdBlock.Heading -> InlineText(
                    text = block.text,
                    style = style.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (block.level) {
                            1 -> style.fontSize * 1.35f
                            2 -> style.fontSize * 1.2f
                            3 -> style.fontSize * 1.1f
                            else -> style.fontSize
                        },
                    ),
                    color = color,
                )

                is MdBlock.ListItem -> Row(
                    Modifier.padding(start = (block.indent * 12).dp),
                ) {
                    Text(
                        text = block.bullet,
                        style = style,
                        color = color,
                        modifier = Modifier.width(if (block.bullet == "•") 14.dp else 22.dp),
                    )
                    InlineText(block.text, style, color, Modifier.weight(1f))
                }

                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .padding(vertical = 2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    InlineText(
                        text = block.text,
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = muted,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = block.code,
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = color,
                        softWrap = false,
                    )
                }

                is MdBlock.Table -> Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(6.dp),
                ) {
                    TableRow(block.header, style.copy(fontWeight = FontWeight.Bold), color)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = muted)
                    block.rows.forEach { TableRow(it, style, color) }
                }

                is MdBlock.Image -> RemoteImage(block.url, block.alt)

                MdBlock.Rule -> HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = muted,
                )
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, style: TextStyle, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            InlineText(
                text = cell,
                style = style.copy(fontSize = style.fontSize * 0.92f),
                color = color,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp),
            )
        }
    }
}
