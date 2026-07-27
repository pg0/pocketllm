package com.redcoralstudios.pocketllm.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.redcoralstudios.pocketllm.PocketLlmApp
import com.redcoralstudios.pocketllm.llm.Dials
import com.redcoralstudios.pocketllm.llm.EngineState
import com.redcoralstudios.pocketllm.llm.GenChunk
import com.redcoralstudios.pocketllm.media.AudioRecorder
import com.redcoralstudios.pocketllm.media.ImagePrep
import com.redcoralstudios.pocketllm.model.DownloadProgress
import com.redcoralstudios.pocketllm.model.ModelCatalog
import com.redcoralstudios.pocketllm.model.ModelSpec
import com.redcoralstudios.pocketllm.model.ModelStore
import com.redcoralstudios.pocketllm.net.WebAugmenter
import com.redcoralstudios.pocketllm.net.WebTools
import com.redcoralstudios.pocketllm.settings.AppSettings
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
    private val recorder = AudioRecorder()
    private val augmenter = WebAugmenter()

    val engineState: StateFlow<EngineState> = container.engine.state
    val busy: StateFlow<Boolean> = container.engine.busy

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pending = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pending.asStateFlow()

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _recordingLevel = MutableStateFlow(0f)
    val recordingLevel: StateFlow<Float> = _recordingLevel.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

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

    private var generation: Job? = null
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
        val spec = ModelCatalog.byId(s.modelId) ?: ModelCatalog.default
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

                container.downloader.download(remote).collect { p ->
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
        ModelCatalog.byId(settings.value.modelId) ?: ModelCatalog.default

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

    fun removeAttachment(attachment: Attachment) {
        _pending.value = _pending.value - attachment
        File(attachment.path).delete()
    }

    fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        viewModelScope.launch {
            if (!ensureProjector()) {
                _isRecording.value = false
                _attachError.value = "The voice encoder could not be loaded."
                return@launch
            }
            val file = recorder.record(getApplication()) { level -> _recordingLevel.value = level }
            _isRecording.value = false
            _recordingLevel.value = 0f
            if (file != null) {
                _pending.value = _pending.value + Attachment(file.absolutePath, isAudio = true)
            }
        }
    }

    fun stopRecording() {
        recorder.stop()
    }

    /** Loads the mmproj encoders on first attachment. */
    private suspend fun ensureProjector(): Boolean =
        container.engine.ensureProjector(useGpu = settings.value.projectorOnGpu)

    // ------------------------------------------------------------------- chat

    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = _pending.value
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (busy.value) return

        _pending.value = emptyList()

        val userMessage = ChatMessage(nextId++, Role.USER, trimmed, attachments)
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
                var promptText = trimmed
                var sources = emptyList<String>()
                var note: String? = null

                if (s.webAccess && trimmed.isNotEmpty()) {
                    val urls = WebTools.extractUrls(trimmed)
                    val needsWork = wantsSearch || urls.isNotEmpty()
                    if (needsWork || s.wikipediaGrounding) {
                        _activity.value = when {
                            urls.isNotEmpty() -> "Reading ${hostOf(urls.first())}"
                            wantsSearch -> "Searching the web"
                            else -> "Checking Wikipedia"
                        }

                        val augmented = runCatching {
                            augmenter.augment(
                                userText = trimmed,
                                searchEnabled = wantsSearch,
                                wikipediaGrounding = s.wikipediaGrounding,
                                fallbackLang = Locale.getDefault().language,
                            )
                        }.getOrNull()

                        if (augmented != null) {
                            promptText = augmented.prompt
                            sources = augmented.sources
                            note = augmented.failureNote
                            fetchInlineImage(augmented)?.let { image ->
                                update(replyId) { it.copy(image = image) }
                            }
                        }
                        update(replyId) { it.copy(sources = sources, note = note) }
                    }
                }

                _activity.value =
                    if (attachments.any { it.isAudio }) "Listening to your recording"
                    else if (attachments.isNotEmpty()) "Looking at the image"
                    else "Thinking"

                container.engine.generate(
                    userText = promptText,
                    mediaPaths = attachments.map { it.path },
                    creativity = s.creativity,
                    factuality = s.factuality,
                    maxTokens = s.maxTokens,
                    systemOverride = s.systemPrompt,
                ).collect { chunk ->
                    when (chunk) {
                        is GenChunk.Token -> {
                            // First token means the prompt is through; the status
                            // line hands over to the text appearing in the bubble.
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
                update(replyId) { it.copy(streaming = false) }
            } finally {
                _activity.value = null
                // Cancellation leaves the bubble mid-stream otherwise.
                update(replyId) { it.copy(streaming = false) }
            }
        }
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

    private fun format(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) String.format("%.2f GB", gb)
        else String.format("%.0f MB", bytes / 1_000_000.0)
    }
}
