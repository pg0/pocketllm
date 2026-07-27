package com.redcoralstudios.pocketllm.net

/**
 * Builds the retrieval block that gets prepended to a user turn.
 *
 * Retrieval is deliberately **deterministic**, not model-driven. A Q2-quantized
 * 4B model asked to emit and parse its own tool calls mid-stream is unreliable,
 * and it costs an extra generation pass. Instead:
 *
 *  - a URL in the message means "read this page"
 *  - the web toggle means "search for this message"
 *
 * Both decisions are made before generation starts, so retrieval cannot misfire
 * halfway through an answer.
 *
 * The search query is the user's message verbatim. A model-refined query would
 * be better, but that needs a scratch completion that does not pollute the chat
 * history -- see docs/roadmap.md.
 */
class WebAugmenter(val tools: WebTools = WebTools()) {

    data class Augmented(
        val prompt: String,
        val sources: List<String>,
        /** Set when retrieval was attempted but produced nothing usable. */
        val failureNote: String? = null,
        /** Lead image of the grounding article, when the user asked to see one. */
        val imageUrl: String? = null,
        val imageCaption: String? = null,
        val imagePageUrl: String? = null,
    ) {
        val usedWeb: Boolean get() = sources.isNotEmpty()
    }

    /**
     * @param wikipediaGrounding pull the matching Wikipedia article for
     *        fact-shaped questions and put it ahead of everything else
     * @param fallbackLang used only if the English article is missing
     * @param onProgress called before each network round trip, with what is
     *        about to happen. Retrieval is several sequential requests and the
     *        slow ones are other people's servers; without this the UI shows one
     *        label for all of it and a slow site is indistinguishable from a
     *        hang.
     */
    suspend fun augment(
        userText: String,
        searchEnabled: Boolean,
        wikipediaGrounding: Boolean = false,
        fallbackLang: String = "de",
        onProgress: (String) -> Unit = {},
    ): Augmented {
        val urls = WebTools.extractUrls(userText)

        // An explicit link always wins: the user named the source.
        if (urls.isNotEmpty()) return readPages(userText, urls, onProgress)

        // "Show me a picture of X" is a lookup, not a generation request: the
        // model has no image output, but the encyclopedia article has a photo.
        // Such a message is worth grounding even when it does not read like a
        // question, and the lookup uses the subject rather than the whole ask.
        val wantsImage = WebTools.looksLikeImageRequest(userText)
        val query = if (wantsImage) WebTools.imageSubject(userText) else userText

        // The toggle is the decision. With grounding on the app looks things up;
        // the only messages skipped are ones no article could answer.
        val wiki = if (wikipediaGrounding && (wantsImage || WebTools.worthLookingUp(userText))) {
            onProgress("Checking Wikipedia")
            tools.wikipedia(query, fallbackLang)
        } else {
            null
        }

        return when {
            searchEnabled -> runSearch(userText, wiki, wantsImage, onProgress)
            wiki != null -> wikiOnly(userText, wiki, wantsImage)
            else -> Augmented(userText, emptyList())
        }
    }

    private fun wikiOnly(userText: String, wiki: WikiArticle, wantsImage: Boolean): Augmented {
        val image = wiki.imageUrl?.takeIf { wantsImage }
        val instruction = if (image != null) "$WIKI_INSTRUCTION $IMAGE_SHOWN_RULE" else WIKI_INSTRUCTION
        return Augmented(
            prompt = wrap(listOf(wikiBlock(wiki)), userText, instruction),
            sources = listOf(wiki.url),
            imageUrl = image,
            imageCaption = image?.let { wiki.title },
            imagePageUrl = image?.let { wiki.url },
        )
    }

    private fun wikiBlock(wiki: WikiArticle): String =
        "[Encyclopedia: ${wiki.title} - ${wiki.url}]\n${wiki.extract}"

    private suspend fun readPages(
        userText: String,
        urls: List<String>,
        onProgress: (String) -> Unit,
    ): Augmented {
        val budget = MAX_TOTAL_CHARS / urls.size
        val blocks = mutableListOf<String>()
        val sources = mutableListOf<String>()

        for ((index, url) in urls.withIndex()) {
            onProgress(
                if (urls.size > 1) "Reading ${index + 1}/${urls.size}: ${hostOf(url)}"
                else "Reading ${hostOf(url)}",
            )
            val page = tools.fetch(url) ?: continue
            if (page.text.isBlank()) continue
            sources += page.url
            blocks += buildString {
                append("[Source: ").append(page.url).append("]\n")
                if (page.title.isNotBlank()) append(page.title).append("\n")
                append(page.text.take(budget))
            }
        }

        if (blocks.isEmpty()) {
            return Augmented(
                prompt = userText,
                sources = emptyList(),
                failureNote = "Could not read ${urls.joinToString()} - answering without it.",
            )
        }

        return Augmented(wrap(blocks, userText, PAGE_INSTRUCTION), sources)
    }

    private suspend fun runSearch(
        userText: String,
        wiki: WikiArticle?,
        wantsImage: Boolean,
        onProgress: (String) -> Unit,
    ): Augmented {
        onProgress("Searching DuckDuckGo")
        val results = tools.search(userText)
        if (results.isEmpty() && wiki == null) {
            return Augmented(
                prompt = userText,
                sources = emptyList(),
                failureNote = "Web search returned nothing - answering from the model alone.",
            )
        }
        if (results.isEmpty() && wiki != null) return wikiOnly(userText, wiki, wantsImage)

        val blocks = mutableListOf<String>()
        val sources = mutableListOf<String>()

        // Wikipedia goes first, and the instruction below names it as the
        // source to trust when accounts disagree.
        //
        // The numbering runs across both, which it did not use to: the search
        // hits were numbered from 1 while Wikipedia sat unnumbered above them,
        // so a citation of "[2]" pointed at the third entry of the list shown
        // under the answer. Numbering by position in `sources` keeps what the
        // model cites and what the user can tap on the same footing.
        if (wiki != null) {
            sources += wiki.url
            blocks += "[${sources.size}] " + wikiBlock(wiki)
        }

        results.forEach { r ->
            sources += r.url
            blocks += "[${sources.size}] ${r.title}\n${r.url}\n${r.snippet}"
        }

        // Snippets alone are thin, so pull the full text of the top hit -- but
        // only when Wikipedia has not already supplied a solid article, or the
        // two together would crowd out the conversation. Only the top hit: the
        // other four contribute their snippet and nothing is downloaded for
        // them.
        if (wiki == null) {
            onProgress("Reading ${hostOf(results.first().url)}")
            tools.fetch(results.first().url)?.let { page ->
                if (page.text.isNotBlank()) {
                    blocks += "[Full text of ${page.url}]\n${page.text.take(TOP_RESULT_CHARS)}"
                }
            }
        }

        val image = wiki?.imageUrl?.takeIf { wantsImage }
        val base = if (wiki != null) WIKI_PLUS_SEARCH_INSTRUCTION else SEARCH_INSTRUCTION
        val instruction = if (image != null) "$base $IMAGE_SHOWN_RULE" else base

        return Augmented(
            prompt = wrap(blocks, userText, instruction),
            sources = sources,
            imageUrl = image,
            imageCaption = image?.let { wiki.title },
            imagePageUrl = image?.let { wiki.url },
        )
    }

    /** Host only: a full URL does not fit on a status line. */
    private fun hostOf(url: String): String = runCatching {
        java.net.URI(if (url.startsWith("http")) url else "https://$url").host ?: url
    }.getOrDefault(url).removePrefix("www.")

    private fun wrap(blocks: List<String>, userText: String, instruction: String): String =
        buildString {
            append("<web_context>\n")
            append(blocks.joinToString("\n\n"))
            append("\n</web_context>\n\n")
            append(instruction)
            append("\n\n")
            append(userText)
        }

    private companion object {
        /**
         * Roughly 1000 tokens. The default context window is 4096 and has to
         * hold the conversation and the answer as well.
         */
        const val MAX_TOTAL_CHARS = 4_000
        const val TOP_RESULT_CHARS = 2_500

        /**
         * Repeated in every retrieval block on purpose. The system prompt says
         * the same thing, but "I am a language model and cannot provide
         * real-time information" is a strong enough habit that it is worth
         * restating right next to the evidence.
         */
        const val NO_REFUSAL_RULE =
            "Do not say you are a language model, that you lack internet access, or " +
                "that you cannot give real-time information: the text above came from " +
                "the live internet just now."

        const val PAGE_INSTRUCTION =
            "The text above was fetched from the linked page just now. Use it as the " +
                "primary source. If it does not contain the answer, say so rather than " +
                "filling the gap from memory. $NO_REFUSAL_RULE"

        const val SEARCH_INSTRUCTION =
            "The results above come from a live web search run just now. Prefer them over " +
                "your own recollection, cite the sources you use by their URL, and say " +
                "plainly if they do not answer the question. $NO_REFUSAL_RULE"

        /**
         * Sources are fetched from English Wikipedia first, so the answer has to
         * be translated back into whatever language the user wrote in.
         */
        private const val LANGUAGE_RULE =
            "The source may be in English. Answer in the same language the question " +
                "was asked in, translating anything you quote."

        /**
         * Without this the model answers "I cannot generate images" while the
         * app is already displaying one right underneath the reply.
         */
        const val IMAGE_SHOWN_RULE =
            "An image from the article is being displayed to the user directly below your " +
                "answer, so do not say that you cannot show or generate pictures. Describe " +
                "what the image shows and give its context instead."

        const val WIKI_INSTRUCTION =
            "The encyclopedia article above was fetched just now and is your primary " +
                "source. Prefer it over your own recollection for names, dates and " +
                "numbers. If it does not cover the question, say so instead of filling " +
                "the gap from memory. Cite it by URL when you use it. $NO_REFUSAL_RULE " +
                "$LANGUAGE_RULE"

        const val WIKI_PLUS_SEARCH_INSTRUCTION =
            "All of the above was fetched just now. The encyclopedia article is the " +
                "most reliable source: when it disagrees with the search results, follow " +
                "the article and say that the sources conflict. Prefer all of it over " +
                "your own recollection, cite what you use by URL, and say plainly if it " +
                "does not answer the question. $NO_REFUSAL_RULE $LANGUAGE_RULE"
    }
}
