package com.vorontsov.neurostenographer.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StreamingLinearResamplerTest {

    @Test
    fun `identity resampling preserves all samples`() {
        val resampler = StreamingLinearResampler(16_000, 16_000)
        val input = ShortArray(1_000) { index -> (index * 7 % 30_000).toShort() }
        val output = collect(resampler) { sink -> resampler.process(input, input.size, sink) }

        assertArrayEquals(input, output.toShortArray())
    }

    @Test
    fun `48k to 16k produces approximately one third of the samples`() {
        val resampler = StreamingLinearResampler(48_000, 16_000)
        val input = sineWave(sampleRate = 48_000, durationMs = 200)
        val output = collect(resampler) { sink -> resampler.process(input, input.size, sink) }

        val expected = 3_200
        assertTrue(
            "expected about $expected samples, got ${output.size}",
            kotlin.math.abs(output.size - expected) <= 4,
        )
    }

    @Test
    fun `chunked processing matches single-shot processing`() {
        val input = sineWave(sampleRate = 44_100, durationMs = 300)

        val wholeResampler = StreamingLinearResampler(44_100, 16_000)
        val whole = mutableListOf<Short>().apply {
            wholeResampler.process(input, input.size) { out, count -> addAll(out.take(count)) }
            wholeResampler.flush { out, count -> addAll(out.take(count)) }
        }

        val chunkedResampler = StreamingLinearResampler(44_100, 16_000)
        val chunked = mutableListOf<Short>().apply {
            var offset = 0
            while (offset < input.size) {
                val count = minOf(997, input.size - offset)
                val chunk = input.copyOfRange(offset, offset + count)
                chunkedResampler.process(chunk, chunk.size) { out, outputCount ->
                    addAll(out.take(outputCount))
                }
                offset += count
            }
            chunkedResampler.flush { out, count -> addAll(out.take(count)) }
        }

        assertTrue(whole.isNotEmpty())
        assertArrayEquals(whole.toShortArray(), chunked.toShortArray())
    }

    @Test
    fun `flush emits the final interpolated sample`() {
        val resampler = StreamingLinearResampler(16_000, 16_000)
        val output = mutableListOf<Short>()
        resampler.process(ShortArray(4) { 10 }, 4) { out, count ->
            output.addAll(out.take(count))
        }
        assertEquals(3, output.size)
        resampler.flush { out, count -> output.addAll(out.take(count)) }
        assertEquals(4, output.size)
    }

    @Test
    fun `stereo downmix averages channels`() {
        val interleaved = byteArrayOf(
            100, 0, 44, 1, // 100 and 300 -> 200
            200.toByte(), 0, 100, 0, // 200 and 100 -> 150
        )
        val output = ShortArray(2)

        val frames = StreamingLinearResampler.downmixToMono(
            bytes = interleaved,
            byteCount = interleaved.size,
            channels = 2,
            output = output,
        )

        assertEquals(2, frames)
        assertArrayEquals(shortArrayOf(200, 150), output)
    }

    private fun collect(
        resampler: StreamingLinearResampler,
        process: ((ShortArray, Int) -> Unit) -> Unit,
    ): List<Short> {
        val output = mutableListOf<Short>()
        val sink: (ShortArray, Int) -> Unit = { samples, count ->
            output.addAll(samples.take(count))
        }
        process(sink)
        resampler.flush(sink)
        return output
    }

    private fun sineWave(sampleRate: Int, durationMs: Int): ShortArray {
        val count = sampleRate * durationMs / 1000
        return ShortArray(count) { index ->
            (sin(2.0 * Math.PI * 440.0 * index / sampleRate) * 12_000).toInt().toShort()
        }
    }
}
