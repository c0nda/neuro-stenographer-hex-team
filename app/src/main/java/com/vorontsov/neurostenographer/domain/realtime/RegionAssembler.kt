package com.vorontsov.neurostenographer.domain.realtime

/**
 * Accumulates PCM blocks from the audio capture thread into an addressable
 * sample space with absolute offsets, so VAD regions can be reconstructed as
 * contiguous float buffers for inference. Pure logic — unit-testable.
 *
 * Memory policy (PROMT §5: unprocessed audio must not accumulate
 * indefinitely):
 *  - [discardBefore] drops everything older than the given absolute sample;
 *    the streaming pipeline calls it with (oldest open region start - pad),
 *    so silence between utterances does not grow the buffer;
 *  - the currently open speech region itself is kept in full — it is real
 *    speech awaiting inference; very long regions are split by the pipeline
 *    (which discards behind them as regions complete).
 *
 * Blocks are copied on append ([onPcm] receives a reused capture buffer).
 */
class RegionAssembler {

    private val chunks = ArrayDeque<FloatChunk>()
    private var nextSample = 0L
    private var firstSample = 0L
    private var discardedSamples = 0L

    val totalSamplesReceived: Long get() = nextSample
    val bufferedSamples: Long get() = nextSample - firstSample
    val discardedByPolicy: Long get() = discardedSamples

    fun onPcm(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val copy = FloatArray(count)
        for (i in 0 until count) copy[i] = samples[i] / 32768f
        chunks.addLast(FloatChunk(startSample = nextSample, data = copy))
        nextSample += count
    }

    /** Absolute sample index one past the last received sample. */
    fun headSample(): Long = nextSample

    /**
     * Copies [startSample, endSampleExclusive) into a new buffer. Throws if
     * the range is not fully retained (caller discarded too much).
     */
    fun extract(startSample: Long, endSampleExclusive: Long): FloatArray {
        require(startSample >= firstSample && endSampleExclusive <= nextSample) {
            "Range [$startSample, $endSampleExclusive) is not fully retained " +
                "(retained: [$firstSample, $nextSample))"
        }
        require(endSampleExclusive > startSample) { "Empty range" }
        val result = FloatArray((endSampleExclusive - startSample).toInt())
        var written = 0
        val iterator = chunks.iterator()
        while (iterator.hasNext()) {
            val chunk = iterator.next()
            val chunkEnd = chunk.startSample + chunk.data.size
            if (chunkEnd <= startSample) continue
            if (chunk.startSample >= endSampleExclusive) break
            val from = maxOf(startSample - chunk.startSample, 0).toInt()
            val to = minOf(endSampleExclusive - chunk.startSample, chunk.data.size.toLong()).toInt()
            val length = to - from
            chunk.data.copyInto(result, written, from, to)
            written += length
        }
        require(written == result.size) { "Range spans discarded audio" }
        return result
    }

    /** Drops all samples strictly before [sample]. No-op when already dropped. */
    fun discardBefore(sample: Long) {
        val target = minOf(sample, nextSample)
        while (chunks.isNotEmpty() && chunks.first().startSample + chunks.first().data.size <= target) {
            discardedSamples += chunks.removeFirst().data.size
        }
        firstSample = if (chunks.isEmpty()) target else chunks.first().startSample
        if (chunks.isEmpty() && target > nextSample) firstSample = nextSample
    }

    fun reset() {
        chunks.clear()
        nextSample = 0
        firstSample = 0
        discardedSamples = 0
    }

    private class FloatChunk(val startSample: Long, val data: FloatArray)
}
