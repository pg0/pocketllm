package com.redcoralstudios.pocketllm.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

private const val TAG = "PocketLLM-web"

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
)

data class PageText(
    val url: String,
    val title: String,
    val text: String,
)

data class WikiArticle(
    val title: String,
    val extract: String,
    val url: String,
    val lang: String,
    /**
     * Lead image of the article, if it has one. The model cannot produce images,
     * so "show me a picture of X" is answered by displaying this next to the
     * answer rather than by the model apologising.
     */
    val imageUrl: String? = null,
)

/**
 * Web retrieval, implemented entirely inside the app.
 *
 * No API key, no account, no self-hosted service: search goes through
 * DuckDuckGo's plain-HTML endpoint and page reading is a plain GET plus a
 * hand-rolled HTML-to-text pass. That keeps the app installable and working
 * with nothing else set up.
 *
 * The trade-off is honesty about scraping: DuckDuckGo can change its markup and
 * break parsing. Failures degrade to "no results" rather than throwing, so the
 * model simply answers without web context.
 */
class WebTools {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ------------------------------------------------------------------ search

    suspend fun search(query: String, limit: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()

            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=" + encode(query))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "search HTTP ${response.code}")
                        return@use emptyList<SearchResult>()
                    }
                    parseResults(response.body?.string().orEmpty(), limit)
                }
            }.onFailure { Log.w(TAG, "search failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

    private fun parseResults(html: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Anchor on the result link, then take the snippet that follows it.
        val linkRegex = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val snippetRegex = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { stripTags(it.groupValues[1]) }.toList()

        for ((index, match) in links.withIndex()) {
            if (results.size >= limit) break
            val href = unwrapRedirect(match.groupValues[1])
            if (href.isBlank()) continue
            results += SearchResult(
                title = stripTags(match.groupValues[2]),
                snippet = snippets.getOrElse(index) { "" },
                url = href,
            )
        }
        return results
    }

    /** DuckDuckGo wraps outbound links as /l/?uddg=<encoded target>. */
    private fun unwrapRedirect(href: String): String {
        val raw = if (href.startsWith("//")) "https:$href" else href
        val marker = "uddg="
        val at = raw.indexOf(marker)
        if (at < 0) return raw
        val encoded = raw.substring(at + marker.length).substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(raw)
    }

    // -------------------------------------------------------------- wikipedia

    /**
     * Looks up the best-matching Wikipedia article and returns its plain-text
     * lead section.
     *
     * Wikipedia gets its own path rather than going through [search] because it
     * offers a real keyless JSON API. That makes it both more reliable than
     * scraping result pages and better suited to grounding: one curated,
     * citable article beats five snippets from arbitrary sites.
     *
     * English is tried first and the local language is only a fallback: the
     * English article is usually longer, better cited and more current, and the
     * model is asked to answer in the user's language regardless of the source
     * language.
     */
    suspend fun wikipedia(query: String, fallbackLang: String = "de"): WikiArticle? =
        withContext(Dispatchers.IO) {
            val langs = if (fallbackLang == "en") listOf("en") else listOf("en", fallbackLang)
            for (code in langs) {
                val title = wikiSearchTitle(query, code) ?: continue
                // The search endpoint always returns its best guess, however bad.
                if (!titleMatchesQuery(title, query)) {
                    Log.i(TAG, "wikipedia: dropped irrelevant hit '$title' for '$query'")
                    continue
                }
                val extract = wikiExtract(title, code) ?: continue
                if (extract.length < MIN_WIKI_CHARS) continue
                return@withContext WikiArticle(
                    title = title,
                    extract = extract.take(MAX_WIKI_CHARS),
                    url = "https://$code.wikipedia.org/wiki/" + encode(title.replace(' ', '_')),
                    lang = code,
                    imageUrl = wikiImage(title, code),
                )
            }
            null
        }

    private fun wikiSearchTitle(query: String, lang: String): String? {
        val direct = wikiSearchOnce(query, lang)
        if (direct.title != null) return direct.title

        // Zero hits but a spelling suggestion: this is what a run-together term
        // like "WM2026" does. The API's own suggestion ("wm 2026") finds the
        // article that the raw string never would.
        val suggestion = direct.suggestion?.takeIf { !it.equals(query, ignoreCase = true) }
            ?: return null
        Log.i(TAG, "wikipedia: retrying '$query' as '$suggestion'")
        return wikiSearchOnce(suggestion, lang).title
    }

    private data class WikiSearch(val title: String?, val suggestion: String?)

    private fun wikiSearchOnce(query: String, lang: String): WikiSearch {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${encode(query)}&srlimit=1&format=json&origin=*"
        val json = getString(url) ?: return WikiSearch(null, null)

        val suggestion = Regex(""""suggestion"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(json)?.groupValues?.get(1)?.let(::unescapeJson)

        // Deliberately a targeted regex rather than a JSON parser: one field,
        // and it keeps the app free of a serialization dependency. The search
        // array is empty on a miss, so no "title" key means no result.
        val title = Regex(""""search"\s*:\s*\[\s*\{.*?"title"\s*:\s*"((?:[^"\\]|\\.)*)"""",
            RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1)?.let(::unescapeJson)

        return WikiSearch(title, suggestion)
    }

    private fun wikiExtract(title: String, lang: String): String? {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts" +
            "&explaintext=1&exintro=0&redirects=1&titles=${encode(title)}&format=json&origin=*"
        val json = getString(url) ?: return null
        val match = Regex(""""extract"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json) ?: return null
        return unescapeJson(match.groupValues[1]).trim().ifBlank { null }
    }

    /**
     * Lead image of an article, as a thumbnail rather than the original: the
     * originals are routinely 4000 px scans of public-domain photographs, and
     * this is going into a chat bubble on a phone.
     */
    private fun wikiImage(title: String, lang: String): String? {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&prop=pageimages" +
            "&piprop=thumbnail&pithumbsize=$WIKI_THUMB_PX&redirects=1" +
            "&titles=${encode(title)}&format=json&origin=*"
        val json = getString(url) ?: return null
        val source = Regex(""""source"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: return null
        return source.replace("\\/", "/").takeIf { it.startsWith("http") }
    }

    private fun unescapeJson(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                out.append(c); i++; continue
            }
            when (val n = s[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) {
                        out.append(code.toChar()); i += 6
                    } else {
                        out.append(n); i += 2
                    }
                }
                else -> { out.append(n); i += 2 }
            }
        }
        return out.toString()
    }

    private fun getString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WIKI_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.onFailure { Log.w(TAG, "wikipedia request failed: ${it.message}") }.getOrNull()
    }

    // -------------------------------------------------------------- page fetch

    suspend fun fetch(url: String): PageText? = withContext(Dispatchers.IO) {
        val normalised = if (url.startsWith("http")) url else "https://$url"

        val request = Request.Builder()
            .url(normalised)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetch HTTP ${response.code} for $normalised")
                    return@use null
                }
                val contentType = response.header("Content-Type").orEmpty()
                // Binary bodies (PDF, images) would produce garbage text.
                if (contentType.isNotEmpty() &&
                    !contentType.contains("html", true) &&
                    !contentType.contains("text", true) &&
                    !contentType.contains("xml", true)
                ) {
                    Log.w(TAG, "unsupported content type: $contentType")
                    return@use null
                }

                // Whole HTML document, then stripped locally - there is no
                // text-only endpoint to ask for. What there is instead is a
                // byte cap: only ~6000 characters of stripped text are ever
                // used, and a news page is easily megabytes of markup, so
                // reading the entire body would spend somebody's mobile data
                // on markup that gets thrown away microseconds later.
                val stream = response.body?.byteStream() ?: return@use null
                val charset = response.body?.contentType()?.charset() ?: Charsets.UTF_8
                val capped = readCapped(stream, MAX_PAGE_BYTES, charset)
                if (capped.text.isBlank()) return@use null

                // Cutting mid-tag would leave "<div class=" as visible text.
                val html = if (capped.truncated) capped.text.substringBeforeLast('>') + '>'
                else capped.text

                PageText(
                    url = normalised,
                    title = extractTitle(html),
                    text = htmlToText(html).take(MAX_PAGE_CHARS),
                )
            }
        }.onFailure { Log.w(TAG, "fetch failed: ${it.message}") }.getOrNull()
    }

    private class Capped(val text: String, val truncated: Boolean)

    /** Reads at most [limit] bytes, so a huge page cannot run up the bill. */
    private fun readCapped(stream: InputStream, limit: Long, charset: Charset): Capped {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            val want = minOf(buffer.size.toLong(), limit - total).toInt()
            val read = stream.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return Capped(out.toString(charset.name()), truncated = total >= limit)
    }

    /**
     * Pulls an image to a local file so the chat can display it.
     *
     * Hand-rolled rather than an image-loading library: one URL per answer, the
     * app already carries OkHttp, and a cached file is what the rest of the
     * media path (attachments) already speaks.
     */
    suspend fun downloadImage(url: String, target: File): File? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WIKI_USER_AGENT)
            .header("Accept", "image/*")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "image HTTP ${response.code}")
                    return@use null
                }
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "image too large: ${body.contentLength()}")
                    return@use null
                }
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (target.length() == 0L) null else target
            }
        }.onFailure { Log.w(TAG, "image download failed: ${it.message}") }.getOrNull()
    }

    // ------------------------------------------------------------------ html

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html) ?: return ""
        return stripTags(match.groupValues[1])
    }

    /**
     * Crude but dependency-free readability pass: drop the non-content
     * elements, turn block tags into newlines, strip the rest, decode entities.
     */
    fun htmlToText(html: String): String {
        var s = html
        for (tag in DROPPED_TAGS) {
            s = s.replace(
                Regex("<$tag[^>]*>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)),
                " ",
            )
        }
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(
            Regex("</?(p|div|br|li|tr|h[1-6]|section|article)[^>]*>", RegexOption.IGNORE_CASE),
            "\n",
        )
        s = stripTags(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun stripTags(fragment: String): String =
        decodeEntities(fragment.replace(Regex("<[^>]*>"), "")).trim()

    private fun decodeEntities(text: String): String {
        var s = text
        ENTITIES.forEach { (from, to) -> s = s.replace(from, to) }
        // Numeric entities, decimal and hex.
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        s = Regex("&#[xX]([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return s
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        const val MAX_RESULTS = 5
        const val MAX_PAGE_CHARS = 6_000

        /**
         * Ceiling on what is downloaded per page. 512 KB of markup strips to
         * far more than [MAX_PAGE_CHARS] of text on any real page, so the cap
         * costs nothing in quality and stops a bloated site from pulling
         * megabytes over mobile data to be truncated on arrival.
         */
        const val MAX_PAGE_BYTES = 512L * 1024

        const val MAX_WIKI_CHARS = 3_500
        const val MIN_WIKI_CHARS = 120
        const val WIKI_THUMB_PX = 640
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        // Wikipedia's API policy asks for an identifying agent rather than a
        // spoofed browser string.
        private const val WIKI_USER_AGENT =
            "PocketLLM/0.1 (Android; https://github.com/pg0) okhttp"

        /**
         * Imperatives that are work *for* the model, not questions about the
         * world. These never get grounded, however capitalised their arguments.
         */
        private val TASK_VERB = Regex(
            """^\s*(count|write|make|create|generate|draft|rewrite|rephrase|summari[sz]e""" +
                """|translate|convert|fix|debug|refactor|code|implement|calculate|compute""" +
                """|solve|sort|list|compare|review|check|test|explain\s+(this|the\s+code)""" +
                """|zähl|zähle|schreib|schreibe|erstell|erstelle|übersetz|übersetze""" +
                """|fasse|korrigier|korrigiere|berechne|löse|sortiere)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Whether a message is worth an encyclopedia lookup.
         *
         * The setting means what it says: with Wikipedia grounding on, the app
         * tries to look things up. So this is a *narrow exclusion*, not a
         * permission check -- only messages that are plainly work for the model
         * ("write me a poem", "count these", "translate this") are skipped,
         * because no article can help with those.
         *
         * Everything else attempts the lookup. Relevance is enforced afterwards
         * by [titleMatchesQuery] rather than by guessing here: Wikipedia's
         * search always returns *something*, and checking what actually came
         * back is a better filter than trying to predict what will.
         */
        fun worthLookingUp(text: String): Boolean {
            val t = text.trim()
            if (t.length < 6) return false
            return !TASK_VERB.containsMatchIn(t)
        }

        /**
         * Signals that an answer depends on something the model cannot know
         * from training data alone: a specific year, or a word meaning "now".
         *
         * A local model has no clock. Asked about 2026 it will place it in the
         * future with total confidence, because as far as its weights are
         * concerned it is. That failure is invisible to any fact-checking
         * prompt, so it has to be caught here and answered with a lookup.
         */
        private val TIME_SENSITIVE = Regex(
            """\b(19|20)\d{2}\b""" +
                """|\b(current|currently|latest|newest|recent|recently|today|now|this\s+(year|month|week))\b""" +
                """|\b(news|score|result|results|standings|winner|won|champion)\b""" +
                """|\b(aktuell|aktuelle[rsn]?|neueste[rsn]?|heute|gerade|jetzt|dieses\s+Jahr)\b""" +
                """|\b(nachrichten|ergebnis|ergebnisse|tabelle|sieger|gewonnen|meister)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun looksTimeSensitive(text: String): Boolean = TIME_SENSITIVE.containsMatchIn(text)

        /**
         * Phrases that mean "I could not answer that".
         *
         * Used to decide whether to spend a web search on a second attempt, so
         * a false positive costs one lookup and a false negative costs a wrong
         * answer. Tuned accordingly.
         */
        private val NO_ANSWER = Regex(
            """\b(i (don't|do not|can't|cannot) (know|say|tell|confirm|provide|access|browse))\b""" +
                """|\bi('m| am) not (sure|certain|aware)\b""" +
                """|\b(no|not enough) (information|data|details) (about|on|available)\b""" +
                """|\b(knowledge (cut-?off|base)|training data|last update|as of my)\b""" +
                """|\b(has|have)(n't| not) (yet )?(happened|taken place|occurred)\b""" +
                """|\b(future|upcoming) event\b""" +
                """|\b(will|is going to) (take place|happen|occur|be held)\b""" +
                """|\bhas not been (played|held|announced|decided)\b""" +
                """|\bich (weiß|kann) (es )?nicht\b""" +
                """|\bkeine (informationen|angaben|daten)\b""" +
                """|\b(mein )?(wissensstand|trainingsdaten|kenntnisstand)\b""" +
                """|\bnoch nicht (stattgefunden|entschieden|bekannt)\b""" +
                """|\b(zukünftiges|kommendes) (ereignis|turnier)\b""" +
                """|\b(findet|wird) .{0,20}(statt|stattfinden)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** True when the reply reads as a non-answer worth retrying with sources. */
        fun looksUnanswered(reply: String): Boolean {
            if (reply.isBlank()) return false
            return NO_ANSWER.containsMatchIn(reply)
        }

        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "list", "der", "die", "das",
            "und", "von", "für", "mit", "eine", "einer", "über",
        )

        /**
         * Guard against Wikipedia's search always returning *something*.
         *
         * The search API never says "no match" -- ask it about "count people"
         * and it hands back an article, which then gets injected as an
         * authoritative source. Requiring the title and the query to actually
         * share a word throws those away.
         */
        fun titleMatchesQuery(title: String, query: String): Boolean {
            val haystack = query.lowercase()
            val words = title.lowercase()
                .split(Regex("""[^\p{L}\p{N}]+"""))
                .filter { it.length >= 4 && it !in STOPWORDS }
            if (words.isEmpty()) return title.lowercase() in haystack
            return words.any { haystack.contains(it) }
        }

        /**
         * "show me a picture of X" phrasings, plus the noise words to strip
         * before the article lookup so the search sees the subject and not the
         * request wrapped around it.
         */
        private val IMAGE_REQUEST = Regex(
            """\b(pictures?|photos?|photographs?|images?|portraits?|""" +
                """bild|bilder|foto|fotos|abbildung|aussehen)\b""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Filler that can precede the subject of an image request. Stripped from
         * the front one token at a time rather than with a single regex, so
         * "show me a picture of X" and "picture of X" reduce identically.
         */
        private val LEADING_FILLER = setOf(
            "please", "bitte", "can", "could", "would", "you", "do", "have", "got",
            "show", "give", "find", "get", "send", "see", "want", "need", "like",
            "me", "us", "my", "a", "an", "the", "of", "from", "for", "to", "with", "any",
            "zeig", "zeige", "zeigen", "gib", "such", "suche", "hast", "hättest", "du",
            "mir", "uns", "ein", "eine", "einen", "einem", "der", "die", "das",
            "von", "vom", "für", "über", "gibt", "es",
        )

        /** True when the message is asking to *see* something, not just read about it. */
        fun looksLikeImageRequest(text: String): Boolean =
            IMAGE_REQUEST.containsMatchIn(text)

        /** Strips "show me a picture of ..." down to the subject for lookup. */
        fun imageSubject(text: String): String {
            val stripped = IMAGE_REQUEST
                .replace(text.trim().trimEnd('?', '.', '!', ','), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()

            val words = stripped.split(' ').filter { it.isNotBlank() }.toMutableList()
            while (words.isNotEmpty() && words.first().lowercase().trim(',') in LEADING_FILLER) {
                words.removeAt(0)
            }
            return words.joinToString(" ").ifBlank { text.trim() }
        }

        private val DROPPED_TAGS = listOf("script", "style", "noscript", "svg", "nav", "footer", "header", "form")

        private val ENTITIES = listOf(
            "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
            "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
            "&mdash;" to "-", "&ndash;" to "-", "&hellip;" to "...",
            "&rsquo;" to "'", "&lsquo;" to "'", "&ldquo;" to "\"", "&rdquo;" to "\"",
        )

        /** Bare-domain matches are deliberately excluded to avoid false positives. */
        private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

        fun extractUrls(text: String, limit: Int = 2): List<String> =
            URL_REGEX.findAll(text)
                .map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }
                .distinct()
                .take(limit)
                .toList()
    }
}
