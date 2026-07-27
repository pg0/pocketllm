package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.model.normalizeRepoId
import com.redcoralstudios.pocketllm.model.parseTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceTest {

    // ------------------------------------------------------------- repo ids

    @Test
    fun `bare id passes through`() {
        assertEquals("unsloth/gemma-4-E2B-it-GGUF", normalizeRepoId("unsloth/gemma-4-E2B-it-GGUF"))
    }

    @Test
    fun `full url is reduced to the id`() {
        assertEquals(
            "unsloth/Qwen3-4B-GGUF",
            normalizeRepoId("https://huggingface.co/unsloth/Qwen3-4B-GGUF"),
        )
    }

    @Test
    fun `tree deep link is reduced to the id`() {
        assertEquals(
            "bartowski/Phi-4-GGUF",
            normalizeRepoId("https://huggingface.co/bartowski/Phi-4-GGUF/tree/main"),
        )
    }

    @Test
    fun `blob link to a file is reduced to the id`() {
        assertEquals(
            "owner/repo",
            normalizeRepoId("huggingface.co/owner/repo/blob/main/model-Q4_K_M.gguf"),
        )
    }

    @Test
    fun `query and fragment are dropped`() {
        assertEquals("owner/repo", normalizeRepoId("owner/repo?show_file_info=x#readme"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("owner/repo", normalizeRepoId("  owner/repo  "))
    }

    @Test
    fun `a single segment is not a repo`() {
        assertNull(normalizeRepoId("gemma"))
    }

    @Test
    fun `empty input is not a repo`() {
        assertNull(normalizeRepoId("   "))
    }

    // ---------------------------------------------------------------- listing

    private val tree = """
        [
          {"type":"file","path":"README.md","size":1200},
          {"type":"directory","path":"nested"},
          {"type":"file","path":"model-Q4_K_M.gguf","size":2500000000},
          {"type":"file","path":"model-Q8_0.gguf","size":4300000000},
          {"type":"file","path":"mmproj-F16.gguf","size":900000000}
        ]
    """.trimIndent()

    @Test
    fun `only gguf files are listed`() {
        val listing = parseTree("owner/repo", tree)
        assertEquals(listOf("model-Q4_K_M.gguf", "model-Q8_0.gguf"), listing.weights.map { it.name })
    }

    @Test
    fun `weights are ordered smallest first`() {
        val listing = parseTree("owner/repo", tree)
        assertEquals(2_500_000_000L, listing.weights.first().sizeBytes)
    }

    @Test
    fun `mmproj is separated from the weights`() {
        val listing = parseTree("owner/repo", tree)
        assertEquals(listOf("mmproj-F16.gguf"), listing.projectors.map { it.name })
    }

    @Test
    fun `sizes come through unrounded`() {
        val listing = parseTree("owner/repo", tree)
        assertEquals(900_000_000L, listing.projectors.single().sizeBytes)
    }

    @Test
    fun `mtp drafters are not offered as models`() {
        val listing = parseTree(
            "owner/repo",
            """[{"type":"file","path":"mtp-gemma-4-E4B-it.gguf","size":60000000},
                {"type":"file","path":"model-Q4.gguf","size":100}]""",
        )
        assertEquals(listOf("model-Q4.gguf"), listing.weights.map { it.name })
    }

    @Test
    fun `split ggufs are hidden and counted once`() {
        val listing = parseTree(
            "owner/repo",
            """[{"type":"file","path":"big-00001-of-00003.gguf","size":1},
                {"type":"file","path":"big-00002-of-00003.gguf","size":1},
                {"type":"file","path":"big-00003-of-00003.gguf","size":1}]""",
        )
        assertTrue(listing.weights.isEmpty())
        assertTrue(listing.notes.any { it.startsWith("1 split model") })
    }

    @Test
    fun `a repo with no weights says so`() {
        val listing = parseTree("owner/repo", """[{"type":"file","path":"README.md","size":10}]""")
        assertTrue(listing.notes.any { it.contains("No single-file GGUF") })
    }

    @Test
    fun `nested paths keep their directory but display the file name`() {
        val listing = parseTree(
            "owner/repo",
            """[{"type":"file","path":"Q4_K_M/model.gguf","size":5}]""",
        )
        assertEquals("Q4_K_M/model.gguf", listing.weights.single().path)
        assertEquals("model.gguf", listing.weights.single().name)
    }
}
