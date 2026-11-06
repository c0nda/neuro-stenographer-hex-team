package com.vorontsov.neurostenographer.domain.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ChunkPlannerTest {

    @Test
    fun `short recording is a single range`() {
        val source = FakeAudioSource(16_000, FloatArray(16_000 * 5))
        val ranges = ChunkPlanner().plan(source)

        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].startSample)
        assertEquals(source.totalSamples, ranges[0].endSampleExclusive)
    }

    @Test
    fun `empty source produces no ranges`() {
        assertTrue(ChunkPlanner().plan(FakeAudioSource(16_000, FloatArray(0))).isEmpty())
    }

    @Test
    fun `boundaries prefer quiet regions instead of a fixed offset`() {
        val sampleRate = 16_000
        val samples = FloatArray(sampleRate * 60) { index ->
            val ms = index * 1000 / sampleRate
            val quiet = ms in 19_000..20_000 || ms in 39_000..40_000
            if (quiet) 0f else (sin(2 * PI * 220 * index / sampleRate) * 0.3).toFloat()
        }
        val ranges = ChunkPlanner().plan(FakeAudioSource(sampleRate, samples))

        assertEquals(3, ranges.size)
        val firstBoundaryMs = ranges[0].endSampleExclusive * 1000 / sampleRate
        val secondBoundaryMs = ranges[1].endSampleExclusive * 1000 / sampleRate
        assertTrue("first boundary was $firstBoundaryMs", firstBoundaryMs in 19_000..19_100)
        assertTrue("second boundary was $secondBoundaryMs", secondBoundaryMs in 39_000..39_100)
        ranges.forEach { range ->
            val durationMs = range.sampleCount * 1000 / sampleRate
            assertTrue("chunk duration $durationMs", durationMs <= 23_000)
        }
        assertEquals(samples.size.toLong(), ranges.last().endSampleExclusive)
    }

    @Test
    fun `constant energy picks the earliest candidate boundary`() {
        val sampleRate = 16_000
        // Every 20 ms frame has identical energy, so the strict "quietest"
        // comparison keeps the first candidate in the search window.
        val samples = FloatArray(sampleRate * 30) { 0.5f }
        val ranges = ChunkPlanner().plan(FakeAudioSource(sampleRate, samples))

        assertEquals(2, ranges.size)
        val firstBoundaryMs = ranges[0].sampleCount * 1000 / sampleRate
        assertEquals(12_010, firstBoundaryMs)
        assertEquals(30_000 - 12_010, ranges[1].sampleCount * 1000 / sampleRate)
    }

    private class FakeAudioSource(
        override val sampleRate: Int,
        private val samples: FloatArray,
    ) : AudioSource {
        override val totalSamples: Long = samples.size.toLong()

        override fun read(startSample: Long, count: Int): FloatArray {
            val start = startSample.toInt().coerceIn(0, samples.size)
            val end = (start + count).coerceAtMost(samples.size)
            return samples.copyOfRange(start, end)
        }

        override fun close() = Unit
    }
}
