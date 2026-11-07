package com.vorontsov.neurostenographer.domain.realtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionAssemblerTest {

    private fun pcm(values: IntArray): ShortArray =
        ShortArray(values.size) { values[it].toShort() }

    @Test
    fun `blocks are addressable with absolute sample offsets`() {
        val assembler = RegionAssembler()
        assembler.onPcm(pcm(intArrayOf(1, 2, 3)), 3)
        assembler.onPcm(pcm(intArrayOf(4, 5)), 2)
        assembler.onPcm(pcm(intArrayOf(6, 7, 8, 9)), 4)
        assertEquals(9L, assembler.headSample())

        val region = assembler.extract(2, 7) // samples 2..6
        val expected = FloatArray(5) { (it + 3) / 32768f } // values 3,4,5,6,7
        assertArrayEquals(expected, region, 1e-6f)
    }

    @Test
    fun `extract across many small blocks reconstructs contiguous audio`() {
        val assembler = RegionAssembler()
        repeat(100) { i ->
            assembler.onPcm(pcm(intArrayOf(i % 32768)), 1)
        }
        val region = assembler.extract(10, 90)
        assertEquals(80, region.size)
        assertEquals(10 / 32768f, region[0], 1e-6f)
        assertEquals(89 / 32768f, region[79], 1e-6f)
    }

    @Test
    fun `input arrays are copied and may be reused by the caller`() {
        val assembler = RegionAssembler()
        val reusable = pcm(intArrayOf(7, 7, 7, 7))
        assembler.onPcm(reusable, 4)
        reusable[0] = 99
        val region = assembler.extract(0, 1)
        assertEquals(7 / 32768f, region[0], 1e-6f)
    }

    @Test
    fun `discardBefore drops old audio and bounds memory`() {
        val assembler = RegionAssembler()
        repeat(10) { assembler.onPcm(pcm(intArrayOf(it, it, it, it)), 4) }
        assembler.discardBefore(20)
        assertEquals(40L, assembler.headSample())
        assertEquals(20L, assembler.bufferedSamples)
        assertEquals(20L, assembler.discardedByPolicy)

        val region = assembler.extract(20, 40)
        assertEquals(20, region.size)

        var thrown: Throwable? = null
        try {
            assembler.extract(0, 4)
        } catch (throwable: Throwable) {
            thrown = throwable
        }
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `discard beyond head keeps the assembler consistent`() {
        val assembler = RegionAssembler()
        assembler.onPcm(pcm(intArrayOf(1, 2)), 2)
        assembler.discardBefore(100)
        assertEquals(0L, assembler.bufferedSamples)
    }

    @Test
    fun `reset starts the sample space over`() {
        val assembler = RegionAssembler()
        assembler.onPcm(pcm(intArrayOf(1, 2, 3)), 3)
        assembler.reset()
        assertEquals(0L, assembler.headSample())
        assembler.onPcm(pcm(intArrayOf(9)), 1)
        assertEquals(9 / 32768f, assembler.extract(0, 1)[0], 1e-6f)
    }
}
