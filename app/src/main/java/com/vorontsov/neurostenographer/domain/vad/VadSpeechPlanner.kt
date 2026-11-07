package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.asr.SampleRange

/**
 * Converts merged VAD speech regions into ASR chunk ranges.
 *
 * A region that already fits the model's practical window is used as-is —
 * unlike energy-based chunking, VAD regions never split mid-word. A region
 * longer than [maxChunkMs] is split by the shared [ChunkPlanner] at its
 * quietest frames, still never crossing the region's own boundaries.
 */
class VadSpeechPlanner(
    private val chunkPlanner: ChunkPlanner,
    private val maxChunkMs: Long = MAX_CHUNK_MS,
) {

    fun plan(source: AudioSource, regions: List<SampleRange>): List<SampleRange> {
        val maxSamples = source.sampleRate.toLong() * maxChunkMs / 1000
        val result = mutableListOf<SampleRange>()
        for (region in regions.sortedBy { it.startSample }) {
            val clamped = SampleRange(
                startSample = region.startSample.coerceIn(0L, source.totalSamples),
                endSampleExclusive = region.endSampleExclusive.coerceIn(0L, source.totalSamples),
            )
            if (clamped.sampleCount <= 0) continue
            if (clamped.sampleCount <= maxSamples) {
                result += clamped
            } else {
                result += chunkPlanner.planRange(source, clamped)
            }
        }
        return result
    }

    private companion object {
        const val MAX_CHUNK_MS = 23_000L
    }
}
