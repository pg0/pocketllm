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
    fun `does not look up work that is meant for the model`() {
        // The bug this pins: "count people" used to trip a Wikipedia lookup.
        assertFalse(WebTools.worthLookingUp("count people"))
        assertFalse(WebTools.worthLookingUp("count the people in this list"))
        assertFalse(WebTools.worthLookingUp("write me a poem about the sea"))
        assertFalse(WebTools.worthLookingUp("translate this to German"))
        assertFalse(WebTools.worthLookingUp("summarize the text above"))
        assertFalse(WebTools.worthLookingUp("schreibe mir ein Gedicht"))
    }

    @Test
    fun `looks up anything else once grounding is on`() {
        // The setting means "try to find it online". Relevance is enforced on
        // what comes back, not by guessing here.
        assertTrue(WebTools.worthLookingUp("who was Nikola Tesla"))
        assertTrue(WebTools.worthLookingUp("wer hat die WM2026 gewonnen?"))
        assertTrue(WebTools.worthLookingUp("tell me about quantum tunnelling"))
        assertTrue(WebTools.worthLookingUp("the Ariane 6 launcher"))
    }

    @Test
    fun `ignores messages too short to be a query`() {
        assertFalse(WebTools.worthLookingUp("ok"))
        assertFalse(WebTools.worthLookingUp("thx"))
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

    // ------------------------------------------------------- time sensitivity

    @Test
    fun `a year or a now-word forces a lookup`() {
        // The model has no clock: without a search it will call a 2026 event
        // upcoming, because as far as its weights go it is.
        assertTrue(WebTools.looksTimeSensitive("wer hat die WM2026 gewonnen?"))
        assertTrue(WebTools.looksTimeSensitive("what is the latest news"))
        assertTrue(WebTools.looksTimeSensitive("aktuelle Ergebnisse"))
        assertTrue(WebTools.looksTimeSensitive("who won the world cup"))
    }

    @Test
    fun `timeless questions do not force a lookup`() {
        assertFalse(WebTools.looksTimeSensitive("explain how a diode works"))
        assertFalse(WebTools.looksTimeSensitive("write me a haiku"))
    }

    // ------------------------------------------------------ non-answer detect

    @Test
    fun `recognises a refusal worth retrying with sources`() {
        assertTrue(WebTools.looksUnanswered("I don't know who won that match."))
        assertTrue(WebTools.looksUnanswered("That is a future event and has not taken place yet."))
        assertTrue(
            WebTools.looksUnanswered(
                "As of my last update I have no information about this.",
            ),
        )
        assertTrue(WebTools.looksUnanswered("Das hat noch nicht stattgefunden."))
    }

    @Test
    fun `does not treat a real answer as a refusal`() {
        assertFalse(WebTools.looksUnanswered("The 2026 World Cup was hosted by three countries."))
        assertFalse(WebTools.looksUnanswered("A diode conducts current in one direction."))
    }
}
