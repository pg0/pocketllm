package com.redcoralstudios.pocketllm.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Locale

private const val TAG = "PocketLLM-stt"

/** What dictation reports back while the microphone is open. */
sealed interface Dictation {
    /** Best guess so far. Replaces the previous partial, never appends. */
    data class Partial(val text: String) : Dictation

    /** Recognition finished with this text. */
    data class Final(val text: String) : Dictation

    /** Microphone level, 0..1, for the level meter. */
    data class Level(val rms: Float) : Dictation

    data class Failed(val reason: String) : Dictation
}

/**
 * Dictation into the text field, using Android's own recogniser.
 *
 * This is what the microphone button does. The model *can* take raw audio
 * through its projector, and that path still exists for attached audio files,
 * but it is the wrong default for asking a question:
 *
 *  - a spoken sentence costs a few hundred audio tokens against a 4096-token
 *    context, where its transcript costs a dozen
 *  - the audio encoder has to run before a single reply token appears
 *  - nothing is shown on screen, so a misheard question looks like a stupid
 *    model rather than a bad transcript
 *  - retrieval needs text: an audio-only turn cannot be grounded at all,
 *    because there is no query to search with
 *
 * On-device recognition is preferred where the platform offers it, so
 * dictation does not quietly turn an offline app into an online one.
 */
class SpeechToText {

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Opens the microphone and emits results until recognition ends.
     *
     * SpeechRecognizer must be created and driven from the main thread, hence
     * the explicit dispatcher rather than relying on the caller's.
     */
    fun listen(context: Context): Flow<Dictation> = callbackFlow {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val engine = withContext(Dispatchers.Main) {
            runCatching {
                if (onDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            }.getOrNull()
        }

        if (engine == null) {
            trySend(Dictation.Failed("Speech recognition is not available on this device."))
            close()
            return@callbackFlow
        }
        recognizer = engine

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The callback is in dB, roughly -2..10 in practice.
                trySend(Dictation.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { trySend(Dictation.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = first(results)
                if (text != null) trySend(Dictation.Final(text))
                else trySend(Dictation.Failed("Nothing was recognised."))
                close()
            }

            override fun onError(error: Int) {
                // A no-match or timeout after the user has already stopped is
                // normal, not something to put an error card on screen for.
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    trySend(Dictation.Final(""))
                } else {
                    Log.w(TAG, "recognition error $error")
                    trySend(Dictation.Failed(describe(error)))
                }
                close()
            }

            private fun first(bundle: Bundle?): String? =
                bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
        }

        withContext(Dispatchers.Main) {
            engine.setRecognitionListener(listener)
            engine.startListening(intent(onDevice))
        }

        awaitClose {
            // destroy() must also happen on the main thread; the recogniser
            // leaks its service binding otherwise.
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        engine.stopListening()
                        engine.destroy()
                    }
                }
            }
            recognizer = null
        }
    }

    /** Stops capture but lets the final result arrive. */
    fun stop() {
        val engine = recognizer ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { engine.stopListening() }
        }
    }

    private fun intent(onDevice: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Only meaningful for the networked recogniser; the on-device one
            // is already offline by construction.
            if (!onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "Speech recognition needs a network connection on this device."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition is already running."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> "This language is not installed for offline dictation."
        else -> "Dictation failed."
    }
}
