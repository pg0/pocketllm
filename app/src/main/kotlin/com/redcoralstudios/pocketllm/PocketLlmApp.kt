package com.redcoralstudios.pocketllm

import android.app.Application
import android.content.Context
import android.util.Log
import com.redcoralstudios.pocketllm.llm.LlmEngine
import com.redcoralstudios.pocketllm.media.ImagePrep
import com.redcoralstudios.pocketllm.model.HuggingFace
import com.redcoralstudios.pocketllm.model.ModelDownloader
import com.redcoralstudios.pocketllm.model.ModelStore
import com.redcoralstudios.pocketllm.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the single [LlmEngine] for the process.
 *
 * Application scope, not activity scope: a 3 GB model must survive rotation and
 * brief backgrounding, and reloading it would cost the user twenty seconds.
 */
class PocketLlmApp : Application() {

    lateinit var modelStore: ModelStore
        private set

    lateinit var engine: LlmEngine
        private set

    lateinit var settings: SettingsRepository
        private set

    lateinit var downloader: ModelDownloader
        private set

    lateinit var huggingFace: HuggingFace
        private set

    /** Outlives every activity, for work that must survive the UI going away. */
    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        modelStore = ModelStore(this)
        engine = LlmEngine(modelStore)
        settings = SettingsRepository(this)
        downloader = ModelDownloader(modelStore)
        huggingFace = HuggingFace()

        // Attachments are only meaningful within a session.
        ImagePrep.clearCache(this)
    }

    /**
     * Gives the model back before Android takes the whole process.
     *
     * `TRIM_MEMORY_COMPLETE` means the app is at the head of the kill queue.
     * Since the model is most of what makes this process worth killing, freeing
     * it here trades a twenty-second reload for keeping the conversation, the
     * settings and the activity alive - which is the better half of the trade,
     * because being killed costs that same reload *plus* everything else.
     *
     * Nothing is done at lighter levels: unloading whenever the app is
     * backgrounded would make every return to it a twenty-second wait.
     *
     * `onTerminate` is not the place for this - it is documented as never being
     * called on a real device.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_COMPLETE) return

        scope.launch {
            if (engine.unloadIfIdle()) {
                Log.i(TAG, "unloaded the model under memory pressure (level $level)")
            }
        }
    }

    companion object {
        private const val TAG = "PocketLLM-app"

        fun from(context: Context): PocketLlmApp =
            context.applicationContext as PocketLlmApp
    }
}
