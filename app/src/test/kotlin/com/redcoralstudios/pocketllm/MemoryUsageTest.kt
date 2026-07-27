package com.redcoralstudios.pocketllm

import com.redcoralstudios.pocketllm.settings.MemorySnapshot
import com.redcoralstudios.pocketllm.settings.MemoryUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryUsageTest {

    private fun snapshot(
        app: Long = 0,
        available: Long = 4L shl 30,
        total: Long = 12L shl 30,
        low: Boolean = false,
    ) = MemorySnapshot(
        appBytes = app,
        nativeHeapBytes = 0,
        codeBytes = 0,
        deviceAvailableBytes = available,
        deviceTotalBytes = total,
        lowMemory = low,
    )

    // ------------------------------------------------------------- formatting

    @Test
    fun `gigabytes get one decimal`() {
        assertEquals("4.2 GB", MemoryUsage.format((4.18 * (1L shl 30)).toLong()))
    }

    @Test
    fun `exactly one gigabyte is gigabytes`() {
        assertEquals("1.0 GB", MemoryUsage.format(1L shl 30))
    }

    @Test
    fun `megabytes are whole numbers`() {
        assertEquals("620 MB", MemoryUsage.format(620L shl 20))
    }

    @Test
    fun `just under a gigabyte stays in megabytes`() {
        assertEquals("1023 MB", MemoryUsage.format((1L shl 30) - (1L shl 20)))
    }

    @Test
    fun `small values fall back to kilobytes`() {
        assertEquals("512 kB", MemoryUsage.format(512L * 1024))
    }

    @Test
    fun `zero formats without crashing`() {
        assertEquals("0 kB", MemoryUsage.format(0))
    }

    // ---------------------------------------------------------------- derived

    @Test
    fun `used is total minus available`() {
        assertEquals(8L shl 30, snapshot().deviceUsedBytes)
    }

    @Test
    fun `used fraction is a ratio of the total`() {
        assertEquals(0.666f, snapshot().deviceUsedFraction, 0.01f)
    }

    @Test
    fun `available above total cannot report negative use`() {
        assertEquals(0L, snapshot(available = 16L shl 30, total = 12L shl 30).deviceUsedBytes)
    }

    @Test
    fun `an unknown total does not divide by zero`() {
        assertEquals(0f, snapshot(available = 0, total = 0).deviceUsedFraction, 0f)
    }

    @Test
    fun `a comfortable phone is not tight`() {
        assertFalse(snapshot().tight)
    }

    @Test
    fun `past ninety percent counts as tight`() {
        assertTrue(snapshot(available = 1L shl 30, total = 12L shl 30).tight)
    }

    @Test
    fun `the system low-memory flag counts as tight on its own`() {
        assertTrue(snapshot(low = true).tight)
    }
}
