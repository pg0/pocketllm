package com.redcoralstudios.pocketllm.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redcoralstudios.pocketllm.PocketLlmApp
import com.redcoralstudios.pocketllm.llm.Dials
import com.redcoralstudios.pocketllm.llm.EngineState
import com.redcoralstudios.pocketllm.llm.GenChunk
import com.redcoralstudios.pocketllm.llm.TurnStats
import com.redcoralstudios.pocketllm.media.Dictation
import com.redcoralstudios.pocketllm.media.DocumentImport
import com.redcoralstudios.pocketllm.media.ImagePrep
import com.redcoralstudios.pocketllm.media.MediaImport
import com.redcoralstudios.pocketllm.media.PdfPages
import com.redcoralstudios.pocketllm.media.PendingDocument
import com.redcoralstudios.pocketllm.media.SpeechToText
import com.redcoralstudios.pocketllm.model.CustomModels
import com.redcoralstudios.pocketllm.model.DownloadProgress
import com.redcoralstudios.pocketllm.model.ModelCatalog
import com.redcoralstudios.pocketllm.model.ModelSpec
import com.redcoralstudios.pocketllm.model.ModelStore
import com.redcoralstudios.pocketllm.model.RepoFile
import com.redcoralstudios.pocketllm.model.RepoListing
import com.redcoralstudios.pocketllm.net.WebAugmenter
import com.redcoralstudios.pocketllm.net.WebTools
import com.redcoralstudios.pocketllm.settings.AppSettings
import com.redcoralstudios.pocketllm.settings.ImageDetail
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

enum class Role { USER, ASSISTANT }

data class Attachment(val path: String, val isAudio: Boolean)

/** An image pulled from the web and shown inside a message bubble. */
data class InlineImage(val path: String, val caption: String, val sourceUrl: String)

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    /** File names of documents whose text was put into this turn. */
    val documents: List<String> = emptyList(),
    val streaming: Boolean = false,
    val isError: Boolean = false,
    /** URLs that were fetched and fed to the model for this answer. */
    val sources: List<String> = emptyList(),
    /** Set when retrieval was attempted and produced nothing. */
    val note: String? = null,
    /** Picture retrieved for a "show me ..." request. */
    val image: InlineImage? = null,
)

/** What the download banner shows while model files are being fetched. */
data class DownloadState(
    val active: Boolean = false,
    val label: String = "",
    val fraction: Float = 0f,
    val error: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = PocketLlmApp.from(app)
    private val speech = SpeechToText()
    private val augmenter = WebAugmenter()

    val engineState: StateFlow<EngineState> = container.engine.state
    val busy: StateFlow<Boolean> = container.engine.busy
    val stats: StateFlow<TurnStats?> = container.engine.stats

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pending.asStateFlow()

    /**
     * Documents waiting to go out with the next message. Separate from
     * [_pending] because they take a different road entirely: their text is
     * prepended to the prompt, where [_pending] goes through the encoders.
     */
    private val _pendingDocs = MutableStateFlow<List<PendingDocument>>(emptyList())
    val pendingDocuments: StateFlow<List<PendingDocument>> = _pendingDocs.asStateFlow()

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _recordingLevel = MutableStateFlow(0f)
    val recordingLevel: StateFlow<Float> = _recordingLevel.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * The composer's text. Owned here rather than by the screen so dictation
     * can write into it and the user can then read and correct it before
     * sending -- the whole point of transcribing instead of sending audio.
     *
     * A [TextFieldValue] rather than a String because the caret position is
     * part of the state: text arriving from dictation has to drag the caret to
     * the end with it, or the field keeps showing the first line while the
     * transcript grows out of sight below.
     */
    private val _input = MutableStateFlow(TextFieldValue(""))
    val input: StateFlow<TextFieldValue> = _input.asStateFlow()

    /**
     * Web search for the next message. Stays on once switched on -- it used to
     * clear itself after every send, which reads as the app turning the setting
     * off behind your back.
     */
    private val _searchArmed = MutableStateFlow(false)
    val searchArmed: StateFlow<Boolean> = _searchArmed.asStateFlow()

    /**
     * What the app is doing right now, in words: retrieval and a long prompt
     * evaluation both look identical from outside otherwise -- a Stop button and
     * no other sign of life.
     */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    /** Set when an attachment could not be prepared, so it is not lost silently. */
    private val _attachError = MutableStateFlow<String?>(null)
    val attachError: StateFlow<String?> = _attachError.asStateFlow()

    /** "First 5 of 12 pages", so a truncated attachment is never silent. */
    private val _attachNote = MutableStateFlow<String?>(null)
    val attachNote: StateFlow<String?> = _attachNote.asStateFlow()

    private var generation: Job? = null
    private var dictation: Job? = null
    private var nextId = 1L

    init {
        // The whole point of the app: no load screen, no model picker, no
        // confirmation. If the files are there, start loading immediately.
        viewModelScope.launch {
            val s = container.settings.settings.first()
            if (s.autoLoad) autoLoad(s)
        }
    }

    private suspend fun autoLoad(s: AppSettings) {
        val spec = ModelCatalog.byId(s.modelId, CustomModels.decode(s.customModels))
            ?: ModelCatalog.default
        container.engine.ensureLoaded(
            spec = spec,
            contextSize = s.contextSize,
            threads = ModelStore.inferenceThreads(),
        )
    }

    fun retryLoad() {
        viewModelScope.launch { autoLoad(settings.value) }
    }

    // ---------------------------------------------------------------- download

    /** Fetches weights, then the projector. Resumes from a previous attempt. */
    fun downloadModel(spec: ModelSpec = currentSpec()) {
        if (_download.value.active) return
        viewModelScope.launch {
            _download.value = DownloadState(active = true, label = "Preparing")

            val files = listOfNotNull(spec.weights, spec.projector)
            for ((index, remote) in files.withIndex()) {
                val prefix = "${index + 1}/${files.size} ${remote.fileName}"
                var failed = false

                container.downloader.download(remote, settings.value.hfToken).collect { p ->
                    when (p) {
                        is DownloadProgress.Running ->
                            _download.value = DownloadState(
                                active = true,
                                label = "$prefix - ${format(p.bytesDone)} of ${format(p.bytesTotal)}",
                                fraction = p.fraction,
                            )

                        is DownloadProgress.Done ->
                            _download.value = _download.value.copy(label = "$prefix done", fraction = 1f)

                        is DownloadProgress.Failed -> {
                            failed = true
                            _download.value = DownloadState(active = false, error = p.reason)
                        }
                    }
                }
                if (failed) return@launch
            }

            _download.value = DownloadState(active = false)
            autoLoad(settings.value)
        }
    }

    fun dismissDownloadError() {
        _download.value = _download.value.copy(error = null)
    }

    fun currentSpec(): ModelSpec =
        ModelCatalog.byId(settings.value.modelId, customModels()) ?: ModelCatalog.default

    // ------------------------------------------------------- Hugging Face models

    fun customModels(): List<ModelSpec> = CustomModels.decode(settings.value.customModels)

    /** Built-in models first, then whatever the user has added. */
    fun availableModels(): List<ModelSpec> = ModelCatalog.builtIn + customModels()

    /**
     * Result of looking up a repo, held here rather than in the dialog so a
     * rotation mid-search does not throw the listing away.
     */
    data class RepoState(
        val busy: Boolean = false,
        val listing: RepoListing? = null,
        val error: String? = null,
    )

    private val _repo = MutableStateFlow(RepoState())
    val repo: StateFlow<RepoState> = _repo.asStateFlow()

    fun searchRepo(input: String) {
        if (_repo.value.busy) return
        viewModelScope.launch {
            _repo.value = RepoState(busy = true)
            val result = container.huggingFace.list(input, settings.value.hfToken)
            _repo.value = result.fold(
                onSuccess = { RepoState(listing = it) },
                onFailure = { RepoState(error = it.message ?: "Lookup failed") },
            )
        }
    }

    fun clearRepo() {
        _repo.value = RepoState()
    }

    /**
     * Adds a model and selects it, but does not download it -- the file is up to
     * several gigabytes and that decision belongs to the user, on the screen
     * where the size is visible.
     */
    fun addCustomModel(repo: String, weights: RepoFile, projector: RepoFile?) {
        viewModelScope.launch {
            val spec = CustomModels.from(repo, weights, projector, settings.value.contextSize)
            // Same file added twice replaces the old entry instead of stacking a
            // duplicate: both would point at one download anyway.
            val updated = customModels().filterNot { it.id == spec.id } + spec
            container.settings.setCustomModels(CustomModels.encode(updated))
            container.settings.setModel(spec.id)
            _repo.value = RepoState()
        }
    }

    /** Removes the entry and any files it downloaded. */
    fun removeCustomModel(spec: ModelSpec) {
        if (!spec.isCustom) return
        viewModelScope.launch {
            container.modelStore.delete(spec)
            val updated = customModels().filterNot { it.id == spec.id }
            container.settings.setCustomModels(CustomModels.encode(updated))
            if (settings.value.modelId == spec.id) {
                container.settings.setModel(ModelCatalog.default.id)
            }
        }
    }

    fun setHfToken(value: String) {
        viewModelScope.launch { container.settings.setHfToken(value) }
    }

    /**
     * Whether this model plausibly fits. Weights are memory-mapped, so a model
     * larger than RAM does not fail cleanly - it thrashes, or the kernel kills
     * the app mid-answer.
     */
    fun fitsInRam(spec: ModelSpec): Boolean =
        ModelStore.totalRamGb(getApplication()) >= spec.minRamGb

    // ------------------------------------------------------------- attachments

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            _activity.value = "Preparing image"
            val file = ImagePrep.prepare(getApplication(), uri)
            if (file == null) {
                _activity.value = null
                _attachError.value = "Could not read that image."
                return@launch
            }

            // Attach first, load the encoder second: the projector is close to a
            // gigabyte and takes seconds, and until it is in the chip has to be
            // on screen or the pick looks like it did nothing.
            _pending.value = _pending.value + Attachment(file.absolutePath, isAudio = false)
            _activity.value = "Loading image encoder"
            val ok = ensureProjector()
            _activity.value = null
            if (!ok) _attachError.value = "The image encoder could not be loaded."
        }
    }

    fun dismissAttachError() {
        _attachError.value = null
    }

    /** Attaches an audio file for the model's audio encoder to hear directly. */
    fun attachAudio(uri: Uri) {
        viewModelScope.launch {
            _activity.value = "Preparing audio"
            val result = MediaImport.importAudio(getApplication(), uri)
            val file = result.getOrElse {
                _activity.value = null
                _attachError.value = it.message ?: "Could not read that audio file."
                return@launch
            }
            _pending.value = _pending.value + Attachment(file.absolutePath, isAudio = true)
            _activity.value = "Loading audio encoder"
            val ok = ensureProjector()
            _activity.value = null
            if (!ok) _attachError.value = "The audio encoder could not be loaded."
        }
    }

    /**
     * Attaches a document.
     *
     * Two roads, decided by the format. Text formats are read into the prompt,
     * because text costs a quarter of what a picture of the same text costs.
     * PDFs are rendered to page images instead: a PDF stores marks on a page
     * rather than a document, Android's renderer hands out pixels and nothing
     * else, and a scanned one has no text in it to extract at all.
     */
    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            if (DocumentImport.isPdf(getApplication(), uri)) {
                attachPdf(uri)
                return@launch
            }

            _activity.value = "Reading document"
            val result = DocumentImport.read(getApplication(), uri)
            _activity.value = null
            val doc = result.getOrElse {
                _attachError.value = it.message ?: "Could not read that document."
                return@launch
            }
            _pendingDocs.value = _pendingDocs.value + PendingDocument.of(doc)
        }
    }

    private suspend fun attachPdf(uri: Uri) {
        _activity.value = "Rendering PDF pages"
        val s = settings.value
        val result = PdfPages.render(
            context = getApplication(),
            uri = uri,
            // How many pages fit depends on what one page costs, which is the
            // detail setting, and on how much room there is, which is the
            // context size. A fixed five was only ever right for one of those.
            maxPages = ImageDetail.pagesFor(s.imageTokens, s.contextSize),
        )
        val pages = result.getOrElse {
            _activity.value = null
            _attachError.value = it.message ?: "Could not read that PDF."
            return
        }
        _pending.value = _pending.value +
            pages.files.map { Attachment(it.absolutePath, isAudio = false) }
        _attachNote.value = "${DocumentImport.displayName(getApplication(), uri).orEmpty()} " +
            pages.note
        _activity.value = "Loading image encoder"
        val ok = ensureProjector()
        _activity.value = null
        if (!ok) _attachError.value = "The image encoder could not be loaded."
    }

    fun removeAttachment(attachment: Attachment) {
        _pending.value = _pending.value - attachment
        if (_pending.value.isEmpty()) _attachNote.value = null
        File(attachment.path).delete()
    }

    fun removeDocument(doc: PendingDocument) {
        _pendingDocs.value = _pendingDocs.value - doc
    }

    fun setInput(value: TextFieldValue) {
        _input.value = value
    }

    /** Replaces the composer text and parks the caret at the end of it. */
    private fun setInputText(text: String) {
        _input.value = TextFieldValue(text, TextRange(text.length))
    }

    /**
     * Microphone -> text field.
     *
     * Dictation rather than audio-to-model: a spoken sentence costs a few
     * hundred audio tokens out of 4096 where its transcript costs a dozen, the
     * user gets to see and fix what was heard, and retrieval has something to
     * search with. Audio still reaches the model's encoder directly if an audio
     * *file* is attached.
     */
    fun startDictation() {
        if (_isRecording.value) return
        if (!speech.isAvailable(getApplication())) {
            _attachError.value = "Speech recognition is not available on this device."
            return
        }

        _isRecording.value = true
        // Anything already typed is kept; dictation appends to it.
        val prefix = _input.value.text.let { if (it.isBlank()) "" else it.trimEnd() + " " }

        dictation = viewModelScope.launch {
            try {
                speech.listen(getApplication()).collect { event ->
                    when (event) {
                        // Each partial rewrites the whole field, so the caret
                        // has to be moved with it - see [_input].
                        is Dictation.Partial -> setInputText(prefix + event.text)
                        is Dictation.Final -> if (event.text.isNotBlank()) {
                            setInputText(prefix + event.text)
                        }
                        is Dictation.Level -> _recordingLevel.value = event.rms
                        is Dictation.Failed -> _attachError.value = event.reason
                    }
                }
            } finally {
                _isRecording.value = false
                _recordingLevel.value = 0f
            }
        }
    }

    fun stopDictation() {
        speech.stop()
    }

    /**
     * Loads the mmproj encoders on first attachment.
     *
     * The image token budget is fixed when the encoder initialises, so changing
     * the detail setting reloads it - which is why the setting lives in the
     * sheet rather than next to the attach button.
     */
    private suspend fun ensureProjector(): Boolean =
        container.engine.ensureProjector(
            useGpu = settings.value.projectorOnGpu,
            imageMaxTokens = settings.value.imageTokens,
        )

    // ------------------------------------------------------------------- chat

    fun send(text: String = _input.value.text) {
        val trimmed = text.trim()
        val attachments = _pending.value
        val documents = _pendingDocs.value
        if (trimmed.isEmpty() && attachments.isEmpty() && documents.isEmpty()) return
        if (busy.value) return

        _pending.value = emptyList()
        _pendingDocs.value = emptyList()
        _attachNote.value = null
        _input.value = TextFieldValue("")

        val userMessage = ChatMessage(
            nextId++, Role.USER, trimmed, attachments, documents.map { it.name },
        )
        val replyId = nextId++
        _messages.value = _messages.value + userMessage +
            ChatMessage(replyId, Role.ASSISTANT, "", streaming = true)

        val s = settings.value
        // Deliberately not cleared here: the toggle stays where the user put it.
        val wantsSearch = _searchArmed.value
        val builder = StringBuilder()

        generation = viewModelScope.launch {
            try {
                // Retrieval happens before generation, so the model never has to
                // decide mid-answer whether to reach for the network.
                val urls = WebTools.extractUrls(trimmed)

                // An attachment makes the message a question *about* that
                // attachment, and its text is not a query for anything:
                // "classify this flower" names no flower, and nothing can be
                // looked up until the model has seen the picture. Retrieval on
                // it returns an unrelated article and spends the context the
                // picture needs. A URL typed by hand is different - that is an
                // instruction, and it still gets read.
                //
                // Documents count double here: "summarise this" is not a query,
                // and the document has already claimed the context an article
                // would have needed.
                val aboutMedia = attachments.isNotEmpty() || documents.isNotEmpty()

                val timeSensitive = !aboutMedia && WebTools.looksTimeSensitive(trimmed)
                // A question that turns on a date or on "latest" cannot be
                // answered from weights at all, so it searches without waiting
                // for the toggle.
                val searchNow = !aboutMedia && (wantsSearch || (s.webAccess && timeSensitive))
                val useWikipedia = !aboutMedia && s.wikipediaGrounding
                var retrieved: WebAugmenter.Augmented? = null

                if (s.webAccess && trimmed.isNotEmpty()) {
                    if (searchNow || urls.isNotEmpty() || useWikipedia) {
                        retrieved = retrieve(
                            replyId = replyId,
                            question = trimmed,
                            searchEnabled = searchNow,
                            wikipedia = useWikipedia,
                            label = when {
                                urls.isNotEmpty() -> "Reading ${hostOf(urls.first())}"
                                searchNow -> "Searching the web"
                                else -> "Checking Wikipedia"
                            },
                        )
                    } else if (aboutMedia && wantsSearch && urls.isEmpty()) {
                        // Say it rather than quietly ignoring an armed toggle.
                        update(replyId) {
                            it.copy(note = "Web search skipped: this question is about the attachment.")
                        }
                    }
                }

                var reply = generateInto(
                    replyId = replyId,
                    promptText = withDocuments(retrieved?.prompt ?: trimmed, documents),
                    attachments = attachments,
                    settings = s,
                    builder = builder,
                )

                // Second pass: the model said it could not answer, the network
                // is available, and no search has been run yet. Asking again
                // with sources is exactly what a person would do here.
                val alreadySearched = searchNow || urls.isNotEmpty()
                if (s.webAccess && !alreadySearched && !aboutMedia && trimmed.isNotEmpty() &&
                    WebTools.looksUnanswered(reply) && container.engine.rollbackTurn()
                ) {
                    builder.setLength(0)
                    update(replyId) {
                        it.copy(text = "", streaming = true, sources = emptyList(), note = null)
                    }

                    val second = retrieve(
                        replyId = replyId,
                        question = trimmed,
                        searchEnabled = true,
                        wikipedia = s.wikipediaGrounding,
                        label = "Answer wasn't known - searching the web",
                    )

                    if (second != null && second.sources.isNotEmpty()) {
                        reply = generateInto(
                            replyId = replyId,
                            promptText = second.prompt,
                            attachments = attachments,
                            settings = s,
                            builder = builder,
                        )
                        update(replyId) { it.copy(note = "Searched the web to answer this.") }
                    } else {
                        // Nothing found: put the original answer back rather
                        // than leaving an empty bubble.
                        update(replyId) {
                            it.copy(
                                text = reply,
                                streaming = false,
                                note = "Searched the web, found nothing usable.",
                            )
                        }
                    }
                }

                update(replyId) { it.copy(streaming = false) }
            } finally {
                _activity.value = null
                // Cancellation leaves the bubble mid-stream otherwise.
                update(replyId) { it.copy(streaming = false) }
            }
        }
    }

    /**
     * Puts the attached documents in front of the question.
     *
     * Same shape as the retrieval block, and for the same reason: the model
     * needs to be able to tell quoted material from the user's own words, and
     * a delimiter it has seen once already is cheaper than teaching it a
     * second convention.
     */
    private fun withDocuments(prompt: String, documents: List<PendingDocument>): String {
        if (documents.isEmpty()) return prompt
        return buildString {
            documents.forEach { append(it.block()).append("\n\n") }
            append(DOCUMENT_INSTRUCTION).append("\n\n")
            append(prompt)
        }
    }

    /** Runs retrieval and records what it found on the pending reply. */
    private suspend fun retrieve(
        replyId: Long,
        question: String,
        searchEnabled: Boolean,
        wikipedia: Boolean,
        label: String,
    ): WebAugmenter.Augmented? {
        _activity.value = label
        val augmented = runCatching {
            augmenter.augment(
                userText = question,
                searchEnabled = searchEnabled,
                wikipediaGrounding = wikipedia,
                fallbackLang = Locale.getDefault().language,
                // Retrieval is several sequential requests against other
                // people's servers. One static label for all of them makes a
                // slow site look exactly like a hang.
                onProgress = { _activity.value = it },
            )
        }.getOrNull()

        if (augmented != null) {
            fetchInlineImage(augmented)?.let { image ->
                update(replyId) { it.copy(image = image) }
            }
            update(replyId) {
                it.copy(sources = augmented.sources, note = augmented.failureNote)
            }
        }
        return augmented
    }

    /** Streams one generation into [replyId] and returns the finished text. */
    private suspend fun generateInto(
        replyId: Long,
        promptText: String,
        attachments: List<Attachment>,
        settings: AppSettings,
        builder: StringBuilder,
    ): String {
        _activity.value = when {
            attachments.any { it.isAudio } -> "Listening to the audio"
            attachments.isNotEmpty() -> "Looking at the images"
            else -> "Thinking"
        }

        container.engine.generate(
            userText = promptText,
            mediaPaths = attachments.map { it.path },
            creativity = settings.creativity,
            factuality = settings.factuality,
            maxTokens = settings.maxTokens,
            systemOverride = settings.systemPrompt,
        ).collect { chunk ->
            when (chunk) {
                is GenChunk.Token -> {
                    // First token means the prompt is through; the status line
                    // hands over to the text appearing in the bubble.
                    if (builder.isEmpty()) _activity.value = null
                    builder.append(chunk.text)
                    update(replyId) { it.copy(text = builder.toString()) }
                }

                is GenChunk.Complete ->
                    update(replyId) { it.copy(text = chunk.text, streaming = false) }

                is GenChunk.Error ->
                    update(replyId) {
                        it.copy(text = chunk.message, streaming = false, isError = true)
                    }
            }
        }
        return builder.toString()
    }

    /** Downloads the article image so the bubble can show it. */
    private suspend fun fetchInlineImage(augmented: WebAugmenter.Augmented): InlineImage? {
        val url = augmented.imageUrl ?: return null
        _activity.value = "Fetching the picture"
        val target = File(ImagePrep.cacheDir(getApplication()), "web_${System.currentTimeMillis()}.img")
        val file = augmenter.tools.downloadImage(url, target) ?: return null
        return InlineImage(
            path = file.absolutePath,
            caption = augmented.imageCaption.orEmpty(),
            sourceUrl = augmented.imagePageUrl ?: url,
        )
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    fun stopGeneration() {
        container.engine.stop()
        generation?.cancel()
    }

    fun newChat() {
        stopGeneration()
        viewModelScope.launch {
            container.engine.resetChat()
            _messages.value = emptyList()
            _pending.value = emptyList()
            _pendingDocs.value = emptyList()
            _attachNote.value = null
            ImagePrep.clearCache(getApplication())
        }
    }

    // --------------------------------------------------------------- settings

    fun setCreativity(v: Int) = viewModelScope.launch { container.settings.setCreativity(v) }
    fun setFactuality(v: Int) = viewModelScope.launch { container.settings.setFactuality(v) }
    fun setAutoLoad(v: Boolean) = viewModelScope.launch { container.settings.setAutoLoad(v) }
    fun setProjectorOnGpu(v: Boolean) = viewModelScope.launch { container.settings.setProjectorOnGpu(v) }

    fun setWebAccess(v: Boolean) = viewModelScope.launch {
        container.settings.setWebAccess(v)
        if (!v) _searchArmed.value = false
    }

    fun toggleSearchArmed() {
        if (settings.value.webAccess) _searchArmed.value = !_searchArmed.value
    }

    fun setWikipediaGrounding(v: Boolean) =
        viewModelScope.launch { container.settings.setWikipediaGrounding(v) }

    /**
     * Same switch as in the settings sheet, reachable from the + menu.
     *
     * One flag rather than a second per-message one: Wikipedia is a source you
     * want on or off for a stretch of conversation, not something to re-arm
     * every message, and two places disagreeing about whether it is on would be
     * worse than either.
     */
    fun toggleWikipedia() {
        if (settings.value.webAccess) setWikipediaGrounding(!settings.value.wikipediaGrounding)
    }

    fun setSystemPrompt(v: String) =
        viewModelScope.launch { container.settings.setSystemPrompt(v) }

    /** Back to the shipped creativity/fact-checking defaults and the auto prompt. */
    fun resetDials() = viewModelScope.launch { container.settings.resetDials() }

    /** The prompt the model would receive right now, for the settings editor. */
    fun generatedSystemPrompt(): String =
        Dials.systemPrompt(settings.value.creativity, settings.value.factuality)

    /** Read at runtime rather than from BuildConfig - see app/build.gradle.kts. */
    val appVersion: String
        get() = runCatching {
            val ctx = getApplication<Application>()
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

    fun setImageTokens(v: Int) = viewModelScope.launch { container.settings.setImageTokens(v) }

    fun setShowStats(v: Boolean) = viewModelScope.launch { container.settings.setShowStats(v) }

    fun setContextSize(v: Int) = viewModelScope.launch { container.settings.setContextSize(v) }
    fun setMaxTokens(v: Int) = viewModelScope.launch { container.settings.setMaxTokens(v) }

    /** Switching models tears down the resident session and loads the new one. */
    fun selectModel(spec: ModelSpec) {
        viewModelScope.launch {
            container.settings.setModel(spec.id)
            container.engine.release()
            _messages.value = emptyList()
            autoLoad(settings.value.copy(modelId = spec.id))
        }
    }

    /**
     * Hands the model's memory back without leaving the app.
     *
     * The conversation goes with it: the KV cache is what the model remembers,
     * and keeping the messages on screen after dropping it would show a history
     * the model can no longer see.
     */
    fun unloadModel() {
        viewModelScope.launch {
            stopGeneration()
            container.engine.unload(byUser = true)
            _messages.value = emptyList()
        }
    }

    fun isDownloaded(spec: ModelSpec): Boolean = container.modelStore.isReady(spec)
    fun hasProjector(spec: ModelSpec): Boolean = container.modelStore.hasProjector(spec)

    fun deleteModel(spec: ModelSpec) {
        viewModelScope.launch {
            container.engine.release()
            container.modelStore.delete(spec)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun update(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    private companion object {
        /**
         * Truncation is stated rather than hidden: a model that answers "the
         * document does not mention X" about a file whose second half was cut
         * off is worse than one that says which part it saw.
         */
        const val DOCUMENT_INSTRUCTION =
            "The document above was attached by the user just now. Answer from it, quote " +
                "from it where that helps, and say plainly if it does not contain what " +
                "was asked. If it is marked as truncated, only the part shown is " +
                "available to you - say so rather than assuming the rest is missing."
    }

    private fun format(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) String.format("%.2f GB", gb)
        else String.format("%.0f MB", bytes / 1_000_000.0)
    }
}
