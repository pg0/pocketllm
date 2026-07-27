package com.redcoralstudios.pocketllm

import android.app.Application
import android.content.Context
import com.redcoralstudios.pocketllm.llm.LlmEngine
import com.redcoralstudios.pocketllm.media.ImagePrep
import com.redcoralstudios.pocketllm.model.HuggingFace
import com.redcoralstudios.pocketllm.model.ModelDownloader
import com.redcoralstudios.pocketllm.model.ModelStore
import com.redcoralstudios.pocketllm.settings.SettingsRepository

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

    companion object {
        fun from(context: Context): PocketLlmApp =
            context.applicationContext as PocketLlmApp
    }
}
