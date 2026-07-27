package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.llm.Dials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialsTest {

    @Test
    fun `creativity raises temperature`() {
        val cold = Dials.params(creativity = 0, factuality = 0)
        val hot = Dials.params(creativity = 100, factuality = 0)
        assertTrue("expected ${hot.temp} > ${cold.temp}", hot.temp > cold.temp)
        assertTrue(hot.topK > cold.topK)
        assertTrue(hot.minP < cold.minP)
    }

    @Test
    fun `fact-checking caps creativity`() {
        val loose = Dials.params(creativity = 100, factuality = 0)
        val strict = Dials.params(creativity = 100, factuality = 100)
        assertTrue(
            "strict mode must sample colder even at max creativity",
            strict.temp < loose.temp,
        )
    }

    @Test
    fun `max factuality clamps creativity to a quarter of its range`() {
        assertEquals(0.25f, Dials.effectiveCreativity(100, 100), 0.001f)
    }

    @Test
    fun `low factuality leaves creativity untouched`() {
        assertEquals(1.0f, Dials.effectiveCreativity(100, 0), 0.001f)
        assertEquals(0.5f, Dials.effectiveCreativity(50, 0), 0.001f)
    }

    @Test
    fun `strict factuality forbids invented specifics in the system prompt`() {
        val prompt = Dials.systemPrompt(creativity = 50, factuality = 100)
        assertTrue(prompt.contains("Never invent specifics"))
        assertTrue(prompt.contains("say so plainly"))
    }

    @Test
    fun `zero factuality adds no grounding clause`() {
        val prompt = Dials.systemPrompt(creativity = 50, factuality = 0)
        assertTrue(!prompt.contains("Never invent specifics"))
    }

    @Test
    fun `temperature never reaches zero which would silently switch to greedy`() {
        for (c in 0..100 step 5) {
            for (f in 0..100 step 5) {
                assertTrue(Dials.params(c, f).temp > 0f)
            }
        }
    }

    @Test
    fun `describe flags when the cap is active`() {
        assertTrue(Dials.describe(100, 100).contains("capping"))
        assertTrue(!Dials.describe(10, 0).contains("capping"))
    }
}
