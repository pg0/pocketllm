package com.redcoralstudios.pocketllm.model

/**
 * A downloadable file belonging to a model.
 *
 * [sizeBytes] comes from the Hugging Face API and is used to detect truncated
 * downloads and to resume them.
 */
data class RemoteFile(
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
) {
    /** Local name, namespaced by repo so two models cannot collide. */
    val localName: String
        get() = repo.substringAfterLast('/') + "__" + fileName.substringAfterLast('/')

    val url: String
        get() = "https://huggingface.co/$repo/resolve/main/$fileName"
}

/**
 * A model the app knows how to fetch and run.
 *
 * @param projector the mmproj file carrying the vision + audio encoders. It is
 *        a separate download because it is only needed once an attachment is
 *        actually sent, so it can be deferred on tight storage.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val summary: String,
    val weights: RemoteFile,
    val projector: RemoteFile?,
    val recommendedContext: Int,
    val minRamGb: Int,
    /** Added by the user from a Hugging Face repo, so it can also be removed. */
    val isCustom: Boolean = false,
) {
    val totalBytes: Long get() = weights.sizeBytes + (projector?.sizeBytes ?: 0L)
}

/**
 * A repo offered as a starting point in the add dialog.
 *
 * [note] says the thing that only shows up after downloading: whether it can
 * see images, and whether it thinks out loud.
 */
data class SuggestedRepo(
    val repo: String,
    val note: String,
)

/**
 * Sizes below are the real byte counts reported by the Hugging Face API, not
 * estimates. Both repos are ungated, so no access token is required.
 */
object ModelCatalog {

    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b-qat-mobile",
        displayName = "Gemma 4 E4B (mobile QAT)",
        summary = "Stronger. Needs roughly 5 GB of free RAM with attachments enabled.",
        weights = RemoteFile(
            repo = "unsloth/gemma-4-E4B-it-qat-mobile-GGUF",
            fileName = "gemma-4-E4B-it-qat-UD-Q2_K_XL.gguf",
            sizeBytes = 3_219_532_192L,
        ),
        projector = RemoteFile(
            repo = "unsloth/gemma-4-E4B-it-qat-mobile-GGUF",
            fileName = "mmproj-F16.gguf",
            sizeBytes = 990_372_672L,
        ),
        recommendedContext = 4096,
        minRamGb = 8,
    )

    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b-qat-mobile",
        displayName = "Gemma 4 E2B (mobile QAT)",
        summary = "Lighter and faster. The fallback when E4B will not fit.",
        weights = RemoteFile(
            repo = "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
            fileName = "gemma-4-E2B-it-qat-UD-Q2_K_XL.gguf",
            sizeBytes = 2_186_186_784L,
        ),
        projector = RemoteFile(
            repo = "unsloth/gemma-4-E2B-it-qat-mobile-GGUF",
            fileName = "mmproj-F16.gguf",
            sizeBytes = 985_654_080L,
        ),
        recommendedContext = 4096,
        minRamGb = 6,
    )

    /**
     * The models shipped with the app. Anything the user adds from a Hugging
     * Face repo lives in [CustomModels] instead, so a bad entry can be deleted
     * without the app losing its two known-good defaults.
     */
    val builtIn: List<ModelSpec> = listOf(GEMMA_4_E4B, GEMMA_4_E2B)

    val default: ModelSpec = GEMMA_4_E4B

    /**
     * One-tap repos for the add dialog, ordered lightest first.
     *
     * Every entry is a `-GGUF` repo on purpose. The base LiquidAI repos hold
     * safetensors, so looking one up returns an empty file list and reads like
     * the app is broken rather than like the wrong repo was pasted.
     */
    val suggestedRepos: List<SuggestedRepo> = listOf(
        SuggestedRepo(
            repo = "LiquidAI/LFM2.5-2.6B-GGUF",
            note = "Text only. Q4_K_M is about 1.7 GB.",
        ),
        SuggestedRepo(
            repo = "LiquidAI/LFM2.5-VL-3B-GGUF",
            note = "Sees images. Q4_K_M is about 1.7 GB, plus 0.6 GB for the " +
                "encoder that comes with it.",
        ),
        SuggestedRepo(
            repo = "Ma7ee7/Qwen3.8_4B_Distilled_GGUF",
            note = "Stronger, text only. A thinking model: every reply opens " +
                "with a <think> block before the answer.",
        ),
    )

    fun byId(id: String?, custom: List<ModelSpec> = emptyList()): ModelSpec? =
        (builtIn + custom).firstOrNull { it.id == id }

    /**
     * Picks a default for this device. E4B is the better model but wants ~5 GB
     * free with the projector resident; below that E2B is the honest choice.
     */
    fun recommendedFor(totalRamGb: Int): ModelSpec =
        if (totalRamGb >= 8) GEMMA_4_E4B else GEMMA_4_E2B
}
