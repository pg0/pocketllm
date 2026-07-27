package com.redcoralstudios.pocketllm.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.redcoralstudios.pocketllm.llm.Dials
import com.redcoralstudios.pocketllm.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketllm")

data class AppSettings(
    val modelId: String,
    val creativity: Int,
    val factuality: Int,
    val contextSize: Int,
    val maxTokens: Int,
    /** Load the model as soon as the app opens, without asking. */
    val autoLoad: Boolean,
    /** Offload the vision/audio encoder to the GPU. Off by default: driver support varies. */
    val projectorOnGpu: Boolean,
    /**
     * Master switch for the only feature that leaves the device during a chat.
     * Off by default: with it off, a conversation touches the network never.
     */
    val webAccess: Boolean,
    /**
     * Pull the matching Wikipedia article for fact-shaped questions and rank it
     * above every other source.
     */
    val wikipediaGrounding: Boolean,
    /**
     * User-written system prompt. Blank means "generate it from the dials".
     * When set, this text is used verbatim and the dials affect only sampling.
     */
    val systemPrompt: String,
) {
    companion object {
        val DEFAULT = AppSettings(
            modelId = ModelCatalog.default.id,
            creativity = Dials.DEFAULT_CREATIVITY,
            factuality = Dials.DEFAULT_FACTUALITY,
            contextSize = 4096,
            maxTokens = 1024,
            autoLoad = true,
            projectorOnGpu = false,
            webAccess = false,
            // Off by default and toggled from the + menu next to web search:
            // a lookup that happens on its own is one nobody asked for, and it
            // spends context on an article that may have nothing to do with
            // the question.
            wikipediaGrounding = false,
            systemPrompt = "",
        )
    }

    /** The prompt actually sent: the custom text if set, otherwise the dials'. */
    val effectiveSystemPrompt: String
        get() = systemPrompt.ifBlank { Dials.systemPrompt(creativity, factuality) }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            modelId = p[KEY_MODEL_ID] ?: AppSettings.DEFAULT.modelId,
            creativity = p[KEY_CREATIVITY] ?: AppSettings.DEFAULT.creativity,
            factuality = p[KEY_FACTUALITY] ?: AppSettings.DEFAULT.factuality,
            contextSize = p[KEY_CONTEXT] ?: AppSettings.DEFAULT.contextSize,
            maxTokens = p[KEY_MAX_TOKENS] ?: AppSettings.DEFAULT.maxTokens,
            autoLoad = p[KEY_AUTOLOAD] ?: AppSettings.DEFAULT.autoLoad,
            projectorOnGpu = p[KEY_PROJECTOR_GPU] ?: AppSettings.DEFAULT.projectorOnGpu,
            webAccess = p[KEY_WEB_ACCESS] ?: AppSettings.DEFAULT.webAccess,
            wikipediaGrounding = p[KEY_WIKIPEDIA] ?: AppSettings.DEFAULT.wikipediaGrounding,
            systemPrompt = p[KEY_SYSTEM_PROMPT] ?: AppSettings.DEFAULT.systemPrompt,
        )
    }

    suspend fun setModel(id: String) = edit { it[KEY_MODEL_ID] = id }
    suspend fun setCreativity(v: Int) = edit { it[KEY_CREATIVITY] = v.coerceIn(Dials.MIN, Dials.MAX) }
    suspend fun setFactuality(v: Int) = edit { it[KEY_FACTUALITY] = v.coerceIn(Dials.MIN, Dials.MAX) }
    suspend fun setContextSize(v: Int) = edit { it[KEY_CONTEXT] = v.coerceIn(1024, 32768) }
    suspend fun setMaxTokens(v: Int) = edit { it[KEY_MAX_TOKENS] = v.coerceIn(64, 8192) }
    suspend fun setAutoLoad(v: Boolean) = edit { it[KEY_AUTOLOAD] = v }
    suspend fun setProjectorOnGpu(v: Boolean) = edit { it[KEY_PROJECTOR_GPU] = v }
    suspend fun setWebAccess(v: Boolean) = edit { it[KEY_WEB_ACCESS] = v }
    suspend fun setWikipediaGrounding(v: Boolean) = edit { it[KEY_WIKIPEDIA] = v }
    suspend fun setSystemPrompt(v: String) = edit { it[KEY_SYSTEM_PROMPT] = v }

    /** Restores the response-style dials only, leaving model and web settings alone. */
    suspend fun resetDials() = edit {
        it[KEY_CREATIVITY] = AppSettings.DEFAULT.creativity
        it[KEY_FACTUALITY] = AppSettings.DEFAULT.factuality
        it[KEY_SYSTEM_PROMPT] = AppSettings.DEFAULT.systemPrompt
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_MODEL_ID = stringPreferencesKey("model_id")
        val KEY_CREATIVITY = intPreferencesKey("creativity")
        val KEY_FACTUALITY = intPreferencesKey("factuality")
        val KEY_CONTEXT = intPreferencesKey("context_size")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
        val KEY_AUTOLOAD = booleanPreferencesKey("auto_load")
        val KEY_PROJECTOR_GPU = booleanPreferencesKey("projector_gpu")
        val KEY_WEB_ACCESS = booleanPreferencesKey("web_access")
        val KEY_WIKIPEDIA = booleanPreferencesKey("wikipedia_grounding")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
    }
}
