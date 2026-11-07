package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.SampleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRegionMergerTest {

    private companion object {
        const val RATE = 16_000
    }

    private fun samples(ms: Long): Long = ms * RATE / 1000

    @Test
    fun `regions are padded on both sides`() {
        val merged = SpeechRegionMerger.merge(
            regions = listOf(SampleRange(samples(1_000), samples(2_000))),
            totalSamples = samples(10_000),
            sampleRate = RATE,
        )
        assertEquals(1, merged.size)
        assertEquals(samples(750), merged.single().startSample)   // -250 ms
        assertEquals(samples(2_250), merged.single().endSampleExclusive) // +250 ms
    }

    @Test
    fun `padding is clamped to the file boundaries`() {
        val merged = SpeechRegionMerger.merge(
            regions = listOf(
                SampleRange(0, samples(1_000)),
                SampleRange(samples(9_500), samples(10_000)),
            ),
            totalSamples = samples(10_000),
            sampleRate = RATE,
        )
        assertEquals(2, merged.size)
        assertEquals(0L, merged[0].startSample)
        assertEquals(samples(10_000), merged[1].endSampleExclusive)
    }

    @Test
    fun `regions separated by a short gap are merged`() {
        val merged = SpeechRegionMerger.merge(
            regions = listOf(
                SampleRange(samples(1_000), samples(2_000)),
                SampleRange(samples(2_200), samples(3_000)), // 200 ms gap < 300 ms
            ),
            totalSamples = samples(10_000),
            sampleRate = RATE,
        )
        assertEquals(1, merged.size)
        assertEquals(samples(750), merged.single().startSample)
        assertEquals(samples(3_250), merged.single().endSampleExclusive)
    }

    @Test
    fun `regions separated by a long gap stay separate`() {
        val merged = SpeechRegionMerger.merge(
            regions = listOf(
                SampleRange(samples(1_000), samples(2_000)),
                SampleRange(samples(4_000), samples(5_000)), // 2 s gap
            ),
            totalSamples = samples(10_000),
            sampleRate = RATE,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `regions are sorted before merging`() {
        val merged = SpeechRegionMerger.merge(
            regions = listOf(
                SampleRange(samples(4_000), samples(5_000)),
                SampleRange(samples(1_000), samples(2_000)),
            ),
            totalSamples = samples(10_000),
            sampleRate = RATE,
        )
        assertEquals(2, merged.size)
        assertTrue(merged[0].startSample < merged[1].startSample)
    }

    @Test
    fun `empty input and zero-length file produce no regions`() {
        assertTrue(
            SpeechRegionMerger.merge(
                regions = emptyList(),
                totalSamples = samples(10_000),
                sampleRate = RATE,
            ).isEmpty(),
        )
        assertTrue(
            SpeechRegionMerger.merge(
                regions = listOf(SampleRange(0, 100)),
                totalSamples = 0,
                sampleRate = RATE,
            ).isEmpty(),
        )
    }
}
