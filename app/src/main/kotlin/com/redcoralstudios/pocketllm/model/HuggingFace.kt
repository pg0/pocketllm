package com.redcoralstudios.pocketllm.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** One GGUF file in a repo, as the Hub reports it. */
data class RepoFile(
    val path: String,
    val sizeBytes: Long,
) {
    val name: String get() = path.substringAfterLast('/')

    /**
     * mmproj files carry the vision and audio encoders. Naming is a convention,
     * not a spec, but every multimodal GGUF repo in practice follows it.
     */
    val isProjector: Boolean get() = name.startsWith("mmproj", ignoreCase = true)

    /**
     * A drafter for speculative decoding, not a model to chat with. Listing it
     * as a choice would only invite someone to load a 60 MB file and wonder why
     * it talks nonsense.
     */
    val isDrafter: Boolean get() = name.contains("mtp-", ignoreCase = true)

    /** `model-00001-of-00004.gguf`: one shard of a split file. */
    val shardIndex: Int? get() = SHARD.find(name)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        val SHARD = Regex("""-(\d{5})-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)
    }
}

/** What a repo offers, split into the two things the app downloads. */
data class RepoListing(
    val repo: String,
    val weights: List<RepoFile>,
    val projectors: List<RepoFile>,
    /** Non-fatal things the user should know before picking, e.g. skipped shards. */
    val notes: List<String>,
)

/**
 * Reads a model repo off huggingface.co.
 *
 * Only the file tree is fetched - no model card, no config - because the only
 * questions the app has are "which GGUF files are here" and "how big are they".
 * Sizes matter beyond display: [ModelDownloader] resumes and validates against
 * an exact byte count, so a guessed size would turn into a corrupt download.
 */
class HuggingFace(private val client: OkHttpClient = defaultClient()) {

    /**
     * @param repo either `owner/name` or a full huggingface.co URL
     * @param token a user access token, required for gated and private repos
     */
    suspend fun list(repo: String, token: String? = null): Result<RepoListing> =
        withContext(Dispatchers.IO) {
            val id = normalizeRepoId(repo)
            if (id == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("Not a repo id. Expected something like unsloth/gemma-4-E2B-it-GGUF")
                )
            }

            val request = Request.Builder()
                .url("https://huggingface.co/api/models/$id/tree/main?recursive=true")
                .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException(explain(response.code, id, token))
                    }
                    parseTree(id, body)
                }
            }
        }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun explain(code: Int, id: String, token: String?): String = when (code) {
            401, 403 ->
                if (token.isNullOrBlank()) "$id needs an access token - add one in settings."
                else "Your token does not grant access to $id. Gated repos need the licence accepted on the website first."
            404 -> "No repo called $id."
            else -> "Hugging Face returned HTTP $code."
        }
    }
}

/**
 * Accepts what people actually paste: a bare id, a full URL, a `/tree/main`
 * deep link, or any of those with stray whitespace.
 *
 * @return `owner/name`, or null if it is not a repo reference at all.
 */
internal fun normalizeRepoId(input: String): String? {
    var s = input.trim()
    if (s.isEmpty()) return null

    s = s.removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/")
    s = s.removePrefix("hf.co/")
    // Drop /tree/main, /blob/main/file.gguf, ?query and #fragment.
    s = s.substringBefore('?').substringBefore('#')

    val parts = s.split('/').filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val owner = parts[0]
    val name = parts[1]
    if (!VALID_SEGMENT.matches(owner) || !VALID_SEGMENT.matches(name)) return null
    return "$owner/$name"
}

private val VALID_SEGMENT = Regex("""[A-Za-z0-9._\-]+""")

/**
 * Turns the tree endpoint's JSON into a listing.
 *
 * Split GGUFs are dropped rather than offered: the downloader fetches exactly
 * one file, so picking shard 1 of 4 would download 2 GB and then fail to load
 * with an error that says nothing about shards. Saying so up front is kinder.
 */
internal fun parseTree(repo: String, json: String): RepoListing {
    val array = JSONArray(json)
    val files = mutableListOf<RepoFile>()
    var shards = 0

    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        if (entry.optString("type") != "file") continue
        val path = entry.optString("path")
        if (!path.endsWith(".gguf", ignoreCase = true)) continue

        val file = RepoFile(path, entry.optLong("size"))
        if (file.shardIndex != null) {
            if (file.shardIndex == 1) shards++
            continue
        }
        if (file.isDrafter) continue
        files += file
    }

    val notes = mutableListOf<String>()
    if (shards > 0) {
        notes += "$shards split model${if (shards == 1) "" else "s"} hidden - " +
            "PocketLLM cannot join multi-part GGUFs yet."
    }
    if (files.none { !it.isProjector }) {
        notes += "No single-file GGUF weights in this repo."
    }

    return RepoListing(
        repo = repo,
        weights = files.filter { !it.isProjector }.sortedBy { it.sizeBytes },
        projectors = files.filter { it.isProjector }.sortedBy { it.sizeBytes },
        notes = notes,
    )
}
