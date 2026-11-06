package com.vorontsov.neurostenographer.data.audio

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Streaming linear-interpolation resampler for mono PCM16. It keeps one sample of
 * overlap between chunks so chunk boundaries are seamless and allocates its work
 * buffers once.
 */
class StreamingLinearResampler(
    private val sourceRate: Int,
    private val targetRate: Int = 16_000,
) {
    private val step = sourceRate.toDouble() / targetRate.toDouble()
    private var position = 0.0
    private var chunkStart = 0L

    /**
     * Interpolation at a chunk boundary needs the samples immediately before
     * the new chunk. Two are retained because the next target position can be
     * just past the previous chunk's interpolation limit (for example, with a
     * non-integer step such as 44100/16000).
     */
    private val history = ShortArray(2)
    private var historyCount = 0

    private var work = ShortArray(0)
    private var output = ShortArray(0)

    fun process(input: ShortArray, count: Int, sink: (ShortArray, Int) -> Unit) {
        if (count <= 0) return
        val prepend = historyCount
        val workCount = count + prepend
        ensureWork(workCount)
        if (prepend > 0) {
            System.arraycopy(history, 0, work, 0, prepend)
        }
        System.arraycopy(input, 0, work, prepend, count)

        val absStart = chunkStart - prepend
        val absEnd = chunkStart + count - 1
        // Interpolation needs the next sample, so stop one sample before the chunk end.
        val maxPosition = absEnd - 1.0
        var outCount = 0

        while (position <= maxPosition) {
            val relative = position - absStart
            val index = floor(relative).toInt()
            val fraction = relative - index
            if (index < 0 || index + 1 >= workCount) break
            val a = work[index].toInt()
            val b = work[index + 1].toInt()
            val sample = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767)
            ensureOutput()
            output[outCount++] = sample.toShort()
            if (outCount == output.size) {
                sink(output, outCount)
                outCount = 0
            }
            position += step
        }
        if (outCount > 0) sink(output, outCount)

        when {
            count >= 2 -> {
                history[0] = input[count - 2]
                history[1] = input[count - 1]
                historyCount = 2
            }

            count == 1 && historyCount >= 1 -> {
                history[0] = history[historyCount - 1]
                history[1] = input[0]
                historyCount = 2
            }

            else -> {
                history[0] = input[0]
                historyCount = 1
            }
        }
        chunkStart += count
    }

    fun flush(sink: (ShortArray, Int) -> Unit) {
        if (historyCount == 0) return
        val lastIndex = chunkStart - 1
        val lastSample = history[historyCount - 1]
        var outCount = 0
        while (position <= lastIndex.toDouble()) {
            ensureOutput()
            output[outCount++] = lastSample
            if (outCount == output.size) {
                sink(output, outCount)
                outCount = 0
            }
            position += step
        }
        if (outCount > 0) sink(output, outCount)
    }

    private fun ensureWork(size: Int) {
        if (work.size < size) work = ShortArray(size)
    }

    private fun ensureOutput() {
        if (output.isEmpty()) {
            val estimate = (sourceRate.toDouble() / targetRate.toDouble() * 8192).toInt() + 4
            output = ShortArray(maxOf(64, estimate))
        }
    }

    companion object {
        /**
         * Converts interleaved PCM16 frames to mono by averaging all channels.
         * Input must be little-endian and frame-aligned.
         */
        fun downmixToMono(
            bytes: ByteArray,
            byteCount: Int,
            channels: Int,
            output: ShortArray,
        ): Int {
            if (channels <= 0) return 0
            val frameBytes = channels * 2
            val frames = byteCount / frameBytes
            var byteIndex = 0
            for (frame in 0 until frames) {
                var sum = 0
                for (channel in 0 until channels) {
                    val low = bytes[byteIndex].toInt() and 0xFF
                    val high = bytes[byteIndex + 1].toInt()
                    sum += (high shl 8) or low
                    byteIndex += 2
                }
                output[frame] = (sum / channels).toShort()
            }
            return frames
        }
    }
}
