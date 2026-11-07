package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.asr.SampleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSpeechPlannerTest {

    private companion object {
        const val RATE = 16_000
        const val TOTAL = 60L * RATE // one minute
    }

    private class SilentSource : AudioSource {
        override val sampleRate: Int = RATE
        override val totalSamples: Long = TOTAL
        override fun read(startSample: Long, count: Int): FloatArray = FloatArray(count)
        override fun close() = Unit
    }

    private fun samples(ms: Long): Long = ms * RATE / 1000

    @Test
    fun `short region is used as a single chunk`() {
        val planner = VadSpeechPlanner(ChunkPlanner())
        val ranges = planner.plan(
            SilentSource(),
            listOf(SampleRange(samples(10_000), samples(12_000))),
        )
        assertEquals(listOf(SampleRange(samples(10_000), samples(12_000))), ranges)
    }

    @Test
    fun `regions longer than the model window are split inside their own bounds`() {
        val chunkPlanner = ChunkPlanner(minChunkMs = 1_000, maxChunkMs = 2_000)
        val planner = VadSpeechPlanner(chunkPlanner, maxChunkMs = 2_000)
        val region = SampleRange(0, samples(10_000))
        val ranges = planner.plan(SilentSource(), listOf(region))

        assertTrue(ranges.size >= 2)
        // Never outside the region, never empty, contiguous, ordered.
        assertEquals(0L, ranges.first().startSample)
        assertEquals(samples(10_000), ranges.last().endSampleExclusive)
        ranges.forEach { assertTrue(it.startSample >= region.startSample) }
        ranges.forEach { assertTrue(it.endSampleExclusive <= region.endSampleExclusive) }
        ranges.forEach { assertTrue(it.sampleCount > 0) }
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.endSampleExclusive, b.startSample) }
    }

    @Test
    fun `multiple regions produce ordered non-overlapping chunks`() {
        val planner = VadSpeechPlanner(ChunkPlanner())
        val ranges = planner.plan(
            SilentSource(),
            listOf(
                SampleRange(samples(30_000), samples(32_000)),
                SampleRange(samples(5_000), samples(7_000)),
            ),
        )
        assertEquals(2, ranges.size)
        assertEquals(samples(5_000), ranges[0].startSample)
        assertEquals(samples(30_000), ranges[1].startSample)
    }

    @Test
    fun `empty regions produce an empty plan`() {
        val planner = VadSpeechPlanner(ChunkPlanner())
        assertTrue(planner.plan(SilentSource(), emptyList()).isEmpty())
    }

    @Test
    fun `regions are clamped to the source bounds`() {
        val planner = VadSpeechPlanner(ChunkPlanner())
        val ranges = planner.plan(
            SilentSource(),
            listOf(SampleRange(-samples(1_000), samples(1_000))),
        )
        assertEquals(1, ranges.size)
        assertEquals(0L, ranges.single().startSample)
        assertEquals(samples(1_000), ranges.single().endSampleExclusive)
    }
}
