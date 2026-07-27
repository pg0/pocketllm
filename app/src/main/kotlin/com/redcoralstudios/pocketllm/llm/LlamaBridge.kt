package com.redcoralstudios.pocketllm.llm

/**
 * Thin 1:1 binding over pocketllm_jni.cpp. No policy lives here -- see [LlmEngine]
 * for lifecycle and threading.
 *
 * Every call except [nativeCancel] must run on the engine's single worker thread.
 */
object LlamaBridge {

    init {
        System.loadLibrary("pocketllm")
    }

    /** Invoked on the worker thread for each chunk of decoded text. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    /** Return false to abort model loading. */
    fun interface ProgressCallback {
        fun onProgress(progress: Float): Boolean
    }

    /** A reply prefixed with this byte is an error message, not model output. */
    const val ERROR_PREFIX: Char = '\u0001'

    external fun nativeInit()

    /** @return an opaque session handle, or 0 on failure. */
    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        callback: ProgressCallback?,
    ): Long

    /** Loads the mmproj file that supplies the vision + audio encoders. */
    external fun nativeLoadProjector(handle: Long, path: String, useGpu: Boolean): Boolean

    external fun nativeSupportsVision(handle: Long): Boolean
    external fun nativeSupportsAudio(handle: Long): Boolean

    /** Drops the KV cache and the conversation history. */
    external fun nativeResetChat(handle: Long)

    /**
     * Undoes the most recent [nativeGenerate], history and KV cache both.
     *
     * Used to retry a question with web context after the model answered that
     * it does not know: leaving the refusal in the history biases the retry.
     */
    external fun nativeRollbackTurn(handle: Long): Boolean

    /** Thread-safe. Aborts prompt evaluation and token generation. */
    external fun nativeCancel(handle: Long)

    external fun nativeContextUsed(handle: Long): Int
    external fun nativeContextSize(handle: Long): Int

    external fun nativeFree(handle: Long)

    /**
     * Appends a user turn and streams the reply.
     *
     * @param media absolute paths to images (jpg/png/...) or audio (wav/mp3/flac)
     * @return the full reply, or an error message prefixed with [ERROR_PREFIX]
     */
    external fun nativeGenerate(
        handle: Long,
        user: String,
        media: Array<String>,
        system: String,
        temp: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        seed: Int,
        callback: TokenCallback?,
    ): String
}
