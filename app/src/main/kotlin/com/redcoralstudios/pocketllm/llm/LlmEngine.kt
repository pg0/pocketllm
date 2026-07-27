package com.redcoralstudios.pocketllm.llm

import android.util.Log
import com.redcoralstudios.pocketllm.model.ModelSpec
import com.redcoralstudios.pocketllm.model.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.random.Random

private const val TAG = "PocketLLM-engine"

sealed interface EngineState {
    /** Nothing loaded yet. */
    data object Idle : EngineState

    /** The configured model is not on disk. The UI routes to the downloader. */
    data object MissingFiles : EngineState

    data class Loading(val progress: Float) : EngineState

    data class Ready(
        val spec: ModelSpec,
        val contextSize: Int,
        val vision: Boolean,
        val audio: Boolean,
        /** GGUF `general.architecture`, e.g. `gemma4`, `qwen3`, `llama`. */
        val architecture: String = "",
        /** How prompts are being built. See [LlamaBridge.nativeModelInfo]. */
        val template: String = "",
    ) : EngineState {
        /**
         * True when the prompt format is a guess rather than the model's own.
         * Worth surfacing: a wrong template does not fail, it just makes the
         * model answer badly, which is easy to blame on the model.
         */
        val templateIsGuessed: Boolean get() = template == "chatml"
    }

    data class Failed(val message: String) : EngineState
}

sealed interface GenChunk {
    data class Token(val text: String) : GenChunk
    data class Complete(val text: String) : GenChunk
    data class Error(val message: String) : GenChunk
}

/**
 * What the last turn cost, and how full the window is.
 *
 * Context used is the number that decides whether the conversation is about to
 * end; tokens per second is the one that says whether the phone is coping.
 * Both were already logged per turn, where a phone user cannot see them.
 */
data class TurnStats(
    val contextUsed: Int,
    val contextSize: Int,
    val promptMs: Long,
    val decodeMs: Long,
    val decodedTokens: Int,
    val mediaTokens: Int,
) {
    val tokensPerSecond: Float
        get() = if (decodeMs > 0) decodedTokens * 1000f / decodeMs else 0f

    val contextFraction: Float
        get() = if (contextSize > 0) contextUsed.toFloat() / contextSize else 0f
}

/**
 * Application-scoped owner of the native session.
 *
 * All native calls are funnelled onto one thread, both because llama.cpp
 * contexts are not thread-safe and because it keeps model loading alive across
 * activity recreation (rotation must not restart a 20-second load).
 */
class LlmEngine(private val store: ModelStore) {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocketllm-inference").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    private val lock = Mutex()

    @Volatile
    private var handle: Long = 0L

    private var loadedSpec: ModelSpec? = null
    private var projectorLoaded = false

    /** The image token budget the resident projector was initialised with. */
    private var loadedImageTokens = -1

    /**
     * System prompt already baked into the current conversation. Gemma has no
     * system role, so the text rides on the first user turn; when the dials move
     * mid-chat we re-inject it rather than throwing the history away.
     */
    private var appliedSystemPrompt: String? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _stats = MutableStateFlow<TurnStats?>(null)

    /** Null until the first turn has finished. */
    val stats: StateFlow<TurnStats?> = _stats.asStateFlow()

    /** Must run on [worker]: it reads session state the inference thread owns. */
    private fun refreshStats(h: Long) {
        if (h == 0L) return
        val raw = runCatching { LlamaBridge.nativeLastTurnStats(h) }.getOrNull() ?: return
        if (raw.size < 4) return
        _stats.value = TurnStats(
            contextUsed = LlamaBridge.nativeContextUsed(h),
            contextSize = LlamaBridge.nativeContextSize(h),
            promptMs = raw[0],
            decodeMs = raw[1],
            decodedTokens = raw[2].toInt(),
            mediaTokens = raw[3].toInt(),
        )
    }

    init {
        // Backend init is cheap and idempotent; do it before any load is requested.
        CoroutineScope(worker).launch { runCatching { LlamaBridge.nativeInit() } }
    }

    val isReady: Boolean get() = handle != 0L

    /**
     * Loads [spec] if it is not already the resident model. Safe to call on
     * every app start -- a second call for the same model is a no-op.
     */
    suspend fun ensureLoaded(spec: ModelSpec, contextSize: Int, threads: Int) = lock.withLock {
        if (handle != 0L && loadedSpec?.id == spec.id) return@withLock

        if (!store.isReady(spec)) {
            _state.value = EngineState.MissingFiles
            return@withLock
        }

        withContext(worker) {
            releaseLocked()
            _state.value = EngineState.Loading(0f)

            val path = store.fileFor(spec.weights).absolutePath
            val h = LlamaBridge.nativeLoadModel(
                path = path,
                nCtx = contextSize,
                nThreads = threads,
                // GPU offload for the LLM itself is a roadmap item: it needs an
                // OpenCL/Vulkan ggml backend compiled in, which this build omits.
                nGpuLayers = 0,
            ) { p ->
                _state.value = EngineState.Loading(p)
                true
            }

            if (h == 0L) {
                _state.value = EngineState.Failed("Could not load ${spec.displayName}")
                return@withContext
            }

            handle = h
            loadedSpec = spec
            projectorLoaded = false
            appliedSystemPrompt = null

            val info = LlamaBridge.nativeModelInfo(h)
            _state.value = EngineState.Ready(
                spec = spec,
                contextSize = LlamaBridge.nativeContextSize(h),
                vision = false,
                audio = false,
                architecture = info.getOrElse(0) { "" },
                template = info.getOrElse(1) { "" },
            )
            Log.i(TAG, "loaded ${spec.id} arch=${info.getOrElse(0) { "?" }} " +
                "template=${info.getOrElse(1) { "?" }}")
        }
    }

    /**
     * Loads the mmproj encoders on first use.
     *
     * Deferred on purpose: the projector costs close to a gigabyte of RAM, and
     * a text-only conversation never needs it.
     */
    suspend fun ensureProjector(useGpu: Boolean, imageMaxTokens: Int = -1): Boolean =
        lock.withLock {
        val spec = loadedSpec ?: return@withLock false
        val projector = spec.projector ?: return@withLock false
        if (handle == 0L) return@withLock false
        // The token budget is fixed when the encoder is initialised, so
        // changing it means loading the projector again.
        if (projectorLoaded && imageMaxTokens == loadedImageTokens) return@withLock true
        if (!store.isComplete(projector)) return@withLock false

        withContext(worker) {
            val ok = LlamaBridge.nativeLoadProjector(
                handle, store.fileFor(projector).absolutePath, useGpu, imageMaxTokens,
            )
            if (ok) {
                projectorLoaded = true
                loadedImageTokens = imageMaxTokens
                val current = _state.value
                if (current is EngineState.Ready) {
                    _state.value = current.copy(
                        vision = LlamaBridge.nativeSupportsVision(handle),
                        audio = LlamaBridge.nativeSupportsAudio(handle),
                    )
                }
            } else {
                Log.e(TAG, "projector load failed")
            }
            ok
        }
    }

    /**
     * Streams a reply. Cancelling the returned flow aborts generation, including
     * a long prompt-evaluation pass.
     */
    fun generate(
        userText: String,
        mediaPaths: List<String>,
        creativity: Int,
        factuality: Int,
        maxTokens: Int,
        /** Overrides the dial-generated prompt when the user wrote their own. */
        systemOverride: String? = null,
    ): Flow<GenChunk> = callbackFlow {
        val h = handle
        if (h == 0L) {
            trySend(GenChunk.Error("No model loaded"))
            close()
            return@callbackFlow
        }

        val params = Dials.params(creativity, factuality, maxTokens)
        // A custom prompt replaces the style guidance, never the date, the
        // reply language or the retrieval rule: what day it is and what the
        // app already fetched are facts about the world, not preferences, and
        // answering in the language you were asked in is not a style choice.
        val system = systemOverride?.takeIf { it.isNotBlank() }
            ?.let {
                listOf(Dials.dateLine(), Dials.LANGUAGE_RULE, Dials.WEB_CONTEXT_RULE, it)
                    .joinToString("\n\n")
            }
            ?: Dials.systemPrompt(creativity, factuality)

        // Native only injects the system prompt on the first turn. If the dials
        // moved since, restate it inline so the change takes effect immediately
        // instead of silently waiting for a new chat.
        val restated = appliedSystemPrompt != null && appliedSystemPrompt != system
        val text = if (restated && system.isNotBlank()) {
            "[Updated instructions]\n$system\n\n$userText"
        } else {
            userText
        }
        appliedSystemPrompt = system

        val job = launch(worker) {
            _busy.value = true
            try {
                val result = LlamaBridge.nativeGenerate(
                    handle = h,
                    user = text,
                    media = mediaPaths.toTypedArray(),
                    system = system,
                    temp = params.temp,
                    topP = params.topP,
                    topK = params.topK,
                    minP = params.minP,
                    repeatPenalty = params.repeatPenalty,
                    maxTokens = params.maxTokens,
                    seed = Random.nextInt(Int.MAX_VALUE),
                ) { piece -> trySend(GenChunk.Token(piece)) }

                if (result.isNotEmpty() && result[0] == LlamaBridge.ERROR_PREFIX) {
                    trySend(GenChunk.Error(result.substring(1)))
                } else {
                    trySend(GenChunk.Complete(result))
                }
            } catch (t: Throwable) {
                trySend(GenChunk.Error(t.message ?: "generation failed"))
            } finally {
                // Read on the worker thread while the session is still ours,
                // and after a cancelled turn too - a partial answer still used
                // context and still took time.
                refreshStats(h)
                _busy.value = false
                close()
            }
        }

        awaitClose {
            // Thread-safe by contract; unblocks the worker if it is mid-decode.
            if (job.isActive) LlamaBridge.nativeCancel(h)
        }
    }

    /** Stops the current generation without dropping the conversation. */
    fun stop() {
        val h = handle
        if (h != 0L) LlamaBridge.nativeCancel(h)
    }

    /**
     * Undoes the last exchange so the same question can be asked again with
     * better context. Without it, a retry sits underneath the model's own
     * "I don't know", which it then tends to agree with.
     */
    suspend fun rollbackTurn(): Boolean = lock.withLock {
        val h = handle
        if (h == 0L) return@withLock false
        withContext(worker) { LlamaBridge.nativeRollbackTurn(h) }
    }

    suspend fun resetChat() = lock.withLock {
        val h = handle
        if (h == 0L) return@withLock
        withContext(worker) {
            LlamaBridge.nativeResetChat(h)
            appliedSystemPrompt = null
            // A new chat has spent nothing yet; leaving the old numbers up
            // would say the window is half full when it is empty.
            _stats.value = null
        }
    }

    suspend fun contextUsage(): Pair<Int, Int> {
        val h = handle
        if (h == 0L) return 0 to 0
        return withContext(worker) {
            LlamaBridge.nativeContextUsed(h) to LlamaBridge.nativeContextSize(h)
        }
    }

    suspend fun release() = lock.withLock {
        withContext(worker) { releaseLocked() }
        _state.value = EngineState.Idle
    }

    private fun releaseLocked() {
        val h = handle
        if (h != 0L) {
            LlamaBridge.nativeFree(h)
            handle = 0L
        }
        loadedSpec = null
        projectorLoaded = false
        appliedSystemPrompt = null
    }
}
