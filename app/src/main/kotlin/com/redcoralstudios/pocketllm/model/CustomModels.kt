package com.redcoralstudios.pocketllm.model

/**
 * User-added models: the ones pulled in from an arbitrary Hugging Face repo.
 *
 * These are stored as text in the settings store rather than in a database. The
 * record is a handful of flat fields and there will never be many of them, so a
 * tab-separated line per model is enough - and unlike JSON it needs no parser
 * that behaves differently under unit test than on the device.
 *
 * Tab and newline cannot occur in a Hugging Face repo id or file path (the Hub
 * restricts both to path-safe characters), which is what makes the separator
 * safe. A line that does not have exactly [FIELDS] fields is dropped rather than
 * guessed at: a half-read model spec would produce a download that fails much
 * later with an unrelated-looking error.
 */
object CustomModels {

    private const val SEP = '\t'
    private const val FIELDS = 9

    fun encode(models: List<ModelSpec>): String =
        models.joinToString("\n") { spec ->
            listOf(
                spec.id,
                spec.displayName,
                spec.weights.repo,
                spec.weights.fileName,
                spec.weights.sizeBytes.toString(),
                spec.projector?.fileName.orEmpty(),
                (spec.projector?.sizeBytes ?: 0L).toString(),
                spec.recommendedContext.toString(),
                spec.minRamGb.toString(),
            ).joinToString(SEP.toString())
        }

    fun decode(stored: String): List<ModelSpec> =
        stored.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { decodeLine(it) }
            .toList()

    private fun decodeLine(line: String): ModelSpec? {
        val f = line.split(SEP)
        if (f.size != FIELDS) return null

        val weightSize = f[4].toLongOrNull() ?: return null
        val projSize = f[6].toLongOrNull() ?: return null
        val context = f[7].toIntOrNull() ?: return null
        val ram = f[8].toIntOrNull() ?: return null
        val repo = f[2]
        if (f[0].isBlank() || repo.isBlank() || f[3].isBlank()) return null

        return ModelSpec(
            id = f[0],
            displayName = f[1],
            summary = summaryFor(repo, weightSize, projSize > 0),
            weights = RemoteFile(repo, f[3], weightSize),
            projector = f[5].takeIf { it.isNotBlank() }
                ?.let { RemoteFile(repo, it, projSize) },
            recommendedContext = context,
            minRamGb = ram,
            isCustom = true,
        )
    }

    /**
     * Builds a spec from a repo listing choice.
     *
     * The id is derived from repo and filename rather than generated, so adding
     * the same file twice updates the entry instead of stacking duplicates that
     * both point at one download.
     */
    fun from(
        repo: String,
        weights: RepoFile,
        projector: RepoFile?,
        contextSize: Int,
    ): ModelSpec = ModelSpec(
        id = "hf:$repo/${weights.path}",
        displayName = weights.name.removeSuffix(".gguf"),
        summary = summaryFor(repo, weights.sizeBytes, projector != null),
        weights = RemoteFile(repo, weights.path, weights.sizeBytes),
        projector = projector?.let { RemoteFile(repo, it.path, it.sizeBytes) },
        recommendedContext = contextSize,
        minRamGb = minRamGbFor(weights.sizeBytes + (projector?.sizeBytes ?: 0L)),
        isCustom = true,
    )

    /**
     * Weights are memory-mapped, so the file size is close to the resident cost.
     * The extra gigabyte covers the KV cache, the compute buffers and Android
     * itself - it is a floor for the warning, not a promise.
     */
    fun minRamGbFor(totalBytes: Long): Int {
        val gb = (totalBytes + GB - 1) / GB
        return (gb + 1).toInt().coerceAtLeast(2)
    }

    private fun summaryFor(repo: String, weightBytes: Long, hasProjector: Boolean): String =
        buildString {
            append(repo)
            append(" - ")
            append(gbLabel(weightBytes))
            append(if (hasProjector) ", with image/voice encoder" else ", text only")
        }

    fun gbLabel(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GB", bytes.toDouble() / GB)

    private const val GB = 1L shl 30
}
