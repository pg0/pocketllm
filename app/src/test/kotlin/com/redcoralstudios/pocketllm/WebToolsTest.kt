package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.net.WebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {

    private val tools = WebTools()

    @Test
    fun `extracts http urls from a message`() {
        val urls = WebTools.extractUrls("look at https://example.com/page and tell me")
        assertEquals(listOf("https://example.com/page"), urls)
    }

    @Test
    fun `strips trailing punctuation from a url`() {
        val urls = WebTools.extractUrls("see https://example.com/page.")
        assertEquals(listOf("https://example.com/page"), urls)
    }

    @Test
    fun `ignores bare domains to avoid false positives`() {
        // "example.com" alone is far more often prose than an instruction to fetch.
        assertTrue(WebTools.extractUrls("i like example.com a lot").isEmpty())
    }

    @Test
    fun `caps the number of urls fetched`() {
        val text = (1..5).joinToString(" ") { "https://example.com/$it" }
        assertEquals(2, WebTools.extractUrls(text).size)
    }

    @Test
    fun `deduplicates repeated urls`() {
        val urls = WebTools.extractUrls("https://a.com/x and again https://a.com/x")
        assertEquals(1, urls.size)
    }

    @Test
    fun `html to text drops scripts and styles`() {
        val html = """
            <html><head><style>body{color:red}</style></head>
            <body><script>alert('x')</script><p>Real content</p></body></html>
        """.trimIndent()
        val text = tools.htmlToText(html)
        assertTrue(text.contains("Real content"))
        assertTrue("script body leaked", !text.contains("alert"))
        assertTrue("style body leaked", !text.contains("color:red"))
    }

    @Test
    fun `html to text decodes entities`() {
        val text = tools.htmlToText("<p>Tom &amp; Jerry &#39;n&#39; friends &nbsp;here</p>")
        assertTrue(text.contains("Tom & Jerry"))
        assertTrue(text.contains("'n'"))
    }

    @Test
    fun `html to text turns block tags into line breaks`() {
        val text = tools.htmlToText("<p>one</p><p>two</p>")
        assertTrue("expected a break between paragraphs, got: $text", text.contains("\n"))
        assertTrue(text.contains("one"))
        assertTrue(text.contains("two"))
    }

    @Test
    fun `html to text collapses runaway whitespace`() {
        val text = tools.htmlToText("<p>a</p>\n\n\n\n\n<p>b</p>")
        assertTrue("blank-line run not collapsed", !text.contains("\n\n\n"))
    }

    // ------------------------------------------------------- grounding gate

    @Test
    fun `does not ground a plain task request`() {
        // The bug this pins: "count people" used to trip a Wikipedia lookup.
        assertFalse(WebTools.looksFactual("count people"))
        assertFalse(WebTools.looksFactual("count the people in this list"))
        assertFalse(WebTools.looksFactual("write me a poem about the sea"))
        assertFalse(WebTools.looksFactual("translate this to German"))
        assertFalse(WebTools.looksFactual("summarize the text above"))
    }

    @Test
    fun `does not ground a question that is not about the world`() {
        assertFalse(WebTools.looksFactual("how many r are in strawberry?"))
        assertFalse(WebTools.looksFactual("what should i cook tonight?"))
    }

    @Test
    fun `grounds an entity question`() {
        assertTrue(WebTools.looksFactual("who was Nikola Tesla"))
        assertTrue(WebTools.looksFactual("what is the Ariane 6"))
        assertTrue(WebTools.looksFactual("tell me about quantum tunnelling"))
        assertTrue(WebTools.looksFactual("wer ist Angela Merkel"))
    }

    @Test
    fun `grounds a proper name but not a capitalised sentence start`() {
        assertTrue(WebTools.looksFactual("I read about Marie Curie yesterday"))
        assertFalse(WebTools.looksFactual("Please do that again"))
    }

    @Test
    fun `rejects a wikipedia hit that shares nothing with the query`() {
        assertFalse(WebTools.titleMatchesQuery("Census", "count people"))
        assertTrue(WebTools.titleMatchesQuery("Nikola Tesla", "picture of nikola tesla"))
        assertTrue(WebTools.titleMatchesQuery("Ariane 6", "what is the ariane 6 rocket"))
    }

    // -------------------------------------------------------- image requests

    @Test
    fun `detects a request to see something`() {
        assertTrue(WebTools.looksLikeImageRequest("show me a picture of Nikola Tesla"))
        assertTrue(WebTools.looksLikeImageRequest("hast du ein Foto von Tesla?"))
        assertFalse(WebTools.looksLikeImageRequest("who was Nikola Tesla"))
    }

    @Test
    fun `reduces an image request to its subject`() {
        assertEquals("Nikola Tesla", WebTools.imageSubject("show me a picture of Nikola Tesla"))
        assertEquals("Nikola Tesla", WebTools.imageSubject("picture of Nikola Tesla"))
    }
}
