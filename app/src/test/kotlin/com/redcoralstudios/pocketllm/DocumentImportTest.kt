package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.media.DocumentImport
import com.redcoralstudios.pocketllm.media.PendingDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Office formats are a zip of XML, and these are the regexes that turn that
 * XML back into text. They are the part most likely to be quietly wrong -
 * everything around them either works or throws.
 */
class DocumentImportTest {

    // ------------------------------------------------------------------ docx

    @Test
    fun `docx paragraphs become lines`() {
        val xml = """
            <w:body>
              <w:p><w:r><w:t>First line</w:t></w:r></w:p>
              <w:p><w:r><w:t>Second line</w:t></w:r></w:p>
            </w:body>
        """.trimIndent()

        val text = DocumentImport.docxToText(xml).trim()

        assertEquals("First line\nSecond line", text.lines().filter { it.isNotBlank() }
            .joinToString("\n") { it.trim() })
    }

    @Test
    fun `docx runs inside one paragraph stay on one line`() {
        // Word splits a sentence across runs whenever formatting changes, so a
        // bolded word must not turn into a line break.
        val xml = "<w:p><w:r><w:t>Total is </w:t></w:r>" +
            "<w:r><w:rPr><w:b/></w:rPr><w:t>42</w:t></w:r>" +
            "<w:r><w:t> euros</w:t></w:r></w:p>"

        assertEquals("Total is 42 euros", DocumentImport.docxToText(xml).trim())
    }

    @Test
    fun `docx line breaks and tabs survive`() {
        val xml = "<w:p><w:r><w:t>a</w:t><w:br/><w:t>b</w:t><w:tab/><w:t>c</w:t></w:r></w:p>"

        assertEquals("a\nb\tc", DocumentImport.docxToText(xml).trim())
    }

    @Test
    fun `docx entities are decoded`() {
        val xml = "<w:p><w:r><w:t>Fish &amp; Chips &lt;3</w:t></w:r></w:p>"

        assertEquals("Fish & Chips <3", DocumentImport.docxToText(xml).trim())
    }

    // ------------------------------------------------------------------ xlsx

    @Test
    fun `shared strings are read in order`() {
        val xml = """
            <sst><si><t>Name</t></si><si><t>Amount</t></si><si><t>Miete</t></si></sst>
        """.trimIndent()

        assertEquals(listOf("Name", "Amount", "Miete"), DocumentImport.sharedStrings(xml))
    }

    @Test
    fun `shared string cells are resolved by index`() {
        val shared = listOf("Name", "Amount", "Miete")
        val sheet = """
            <sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
              <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>1200</v></c></row>
            </sheetData>
        """.trimIndent()

        val text = DocumentImport.sheetToText(sheet, shared)

        assertEquals("Name\tAmount\nMiete\t1200\n", text)
    }

    @Test
    fun `numeric cells keep their value`() {
        val sheet = """<sheetData><row><c r="A1"><v>3.5</v></c></row></sheetData>"""

        assertEquals("3.5\n", DocumentImport.sheetToText(sheet, emptyList()))
    }

    @Test
    fun `an index past the end of the table does not crash`() {
        // A damaged file should lose one cell, not the whole spreadsheet.
        val sheet = """<sheetData><row><c r="A1" t="s"><v>9</v></c><c r="B1"><v>7</v></c></row></sheetData>"""

        assertEquals("\t7\n", DocumentImport.sheetToText(sheet, listOf("only one")))
    }

    @Test
    fun `blank rows are dropped`() {
        val sheet = """
            <sheetData>
              <row><c r="A1"><v>1</v></c></row>
              <row></row>
              <row><c r="A3"><v>2</v></c></row>
            </sheetData>
        """.trimIndent()

        assertEquals("1\n2\n", DocumentImport.sheetToText(sheet, emptyList()))
    }

    @Test
    fun `self-closing cells are tolerated`() {
        // An empty cell is written as <c r="B1"/>, which has no closing tag.
        val sheet = """<sheetData><row><c r="A1"><v>1</v></c><c r="B1"/></row></sheetData>"""

        assertTrue(DocumentImport.sheetToText(sheet, emptyList()).startsWith("1"))
    }

    // ----------------------------------------------------------------- block

    @Test
    fun `truncation is stated in the prompt block`() {
        val block = PendingDocument("budget.xlsx", "a\tb", truncated = true).block()

        assertTrue(block.contains("name=\"budget.xlsx\""))
        assertTrue(block.contains("truncated"))
    }

    @Test
    fun `a complete document says nothing about truncation`() {
        val block = PendingDocument("note.txt", "hello", truncated = false).block()

        assertTrue(block.contains("hello"))
        assertTrue(!block.contains("truncated"))
    }
}
