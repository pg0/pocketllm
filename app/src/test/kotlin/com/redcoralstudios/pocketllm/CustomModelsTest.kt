package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.model.CustomModels
import com.redcoralstudios.pocketllm.model.RepoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomModelsTest {

    private val weights = RepoFile("model-Q4_K_M.gguf", 2_500_000_000L)
    private val mmproj = RepoFile("mmproj-F16.gguf", 900_000_000L)

    private fun spec(projector: RepoFile? = mmproj) =
        CustomModels.from("owner/repo", weights, projector, contextSize = 4096)

    @Test
    fun `a spec survives a round trip`() {
        val decoded = CustomModels.decode(CustomModels.encode(listOf(spec())))
        assertEquals(1, decoded.size)
        assertEquals(spec().id, decoded.single().id)
        assertEquals(2_500_000_000L, decoded.single().weights.sizeBytes)
        assertEquals("mmproj-F16.gguf", decoded.single().projector?.fileName)
    }

    @Test
    fun `a text-only spec round trips without a projector`() {
        val decoded = CustomModels.decode(CustomModels.encode(listOf(spec(projector = null))))
        assertNull(decoded.single().projector)
    }

    @Test
    fun `decoded specs are marked custom so they can be removed`() {
        assertTrue(CustomModels.decode(CustomModels.encode(listOf(spec()))).single().isCustom)
    }

    @Test
    fun `several models round trip`() {
        val other = CustomModels.from(
            "other/repo", RepoFile("small-Q2.gguf", 1_000L), null, 2048,
        )
        val decoded = CustomModels.decode(CustomModels.encode(listOf(spec(), other)))
        assertEquals(listOf("owner/repo", "other/repo"), decoded.map { it.weights.repo })
    }

    @Test
    fun `the id is stable for the same file`() {
        assertEquals(spec().id, spec(projector = null).id)
    }

    @Test
    fun `a truncated line is dropped rather than guessed at`() {
        assertTrue(CustomModels.decode("only\ttwo").isEmpty())
    }

    @Test
    fun `a line with a non-numeric size is dropped`() {
        val broken = CustomModels.encode(listOf(spec())).replace("2500000000", "huge")
        assertTrue(CustomModels.decode(broken).isEmpty())
    }

    @Test
    fun `a good line survives a broken neighbour`() {
        val stored = "rubbish\n" + CustomModels.encode(listOf(spec()))
        assertEquals(1, CustomModels.decode(stored).size)
    }

    @Test
    fun `blank storage decodes to nothing`() {
        assertTrue(CustomModels.decode("").isEmpty())
        assertTrue(CustomModels.decode("\n\n").isEmpty())
    }

    @Test
    fun `display name drops the extension`() {
        assertEquals("model-Q4_K_M", spec().displayName)
    }

    @Test
    fun `the summary says whether attachments will work`() {
        assertTrue(spec().summary.contains("image/voice"))
        assertTrue(spec(projector = null).summary.contains("text only"))
    }

    @Test
    fun `ram estimate covers the files plus overhead`() {
        // 3.4 GB of files rounds to 4, plus 1 GB for KV cache and the system.
        assertEquals(5, CustomModels.minRamGbFor(3_400_000_000L))
    }

    @Test
    fun `ram estimate never drops below two gigabytes`() {
        assertEquals(2, CustomModels.minRamGbFor(1_000L))
    }

    @Test
    fun `sizes are labelled in gigabytes`() {
        assertEquals("2.3 GB", CustomModels.gbLabel(2_500_000_000L))
    }
}
