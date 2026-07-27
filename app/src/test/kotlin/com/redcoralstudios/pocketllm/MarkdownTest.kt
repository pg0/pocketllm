package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.ui.MdBlock
import com.redcoralstudios.pocketllm.ui.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `headings carry their level`() {
        val blocks = parseBlocks("## Title")
        assertEquals(listOf(MdBlock.Heading(2, "Title")), blocks)
    }

    @Test
    fun `consecutive lines join into one paragraph`() {
        val blocks = parseBlocks("one\ntwo\n\nthree")
        assertEquals(
            listOf(MdBlock.Paragraph("one two"), MdBlock.Paragraph("three")),
            blocks,
        )
    }

    @Test
    fun `bullets and numbers both become list items`() {
        val blocks = parseBlocks("- first\n2. second")
        assertEquals(
            listOf(
                MdBlock.ListItem("•", "first", 0),
                MdBlock.ListItem("2.", "second", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `nested bullets keep their indent`() {
        val blocks = parseBlocks("- top\n  - nested")
        assertEquals(1, (blocks[1] as MdBlock.ListItem).indent)
    }

    @Test
    fun `fenced code keeps its body verbatim`() {
        val blocks = parseBlocks("```kotlin\nval x = 1\n```")
        assertEquals(listOf(MdBlock.Code("val x = 1")), blocks)
    }

    @Test
    fun `an unclosed fence still renders as code`() {
        // This is the streaming case: the closing fence has not arrived yet.
        val blocks = parseBlocks("```\nhalf a block")
        assertEquals(listOf(MdBlock.Code("half a block")), blocks)
    }

    @Test
    fun `a pipe table becomes a table block`() {
        val blocks = parseBlocks(
            """
            | Name | Year |
            |------|------|
            | Tesla | 1856 |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Name", "Year"), table.header)
        assertEquals(listOf(listOf("Tesla", "1856")), table.rows)
    }

    @Test
    fun `a lone image line becomes an image block`() {
        val blocks = parseBlocks("![Nikola Tesla](https://example.com/t.jpg)")
        assertEquals(
            listOf(MdBlock.Image("Nikola Tesla", "https://example.com/t.jpg")),
            blocks,
        )
    }

    @Test
    fun `a rule is not confused with a bullet`() {
        assertEquals(listOf(MdBlock.Rule), parseBlocks("---"))
        assertTrue(parseBlocks("- item").single() is MdBlock.ListItem)
    }

    @Test
    fun `blockquotes drop their marker`() {
        assertEquals(listOf(MdBlock.Quote("quoted")), parseBlocks("> quoted"))
    }
}
