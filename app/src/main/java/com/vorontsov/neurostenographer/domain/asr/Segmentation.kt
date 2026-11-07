package com.vorontsov.neurostenographer.domain.asr

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Half-open sample range inside an [AudioSource].
 */
data class SampleRange(
    val startSample: Long,
    val endSampleExclusive: Long,
) {
    val sampleCount: Long get() = endSampleExclusive - startSample
}

/**
 * Splits long recordings into short-form windows that the GigaAM encoder can
 * process (its practical limit is roughly 25 seconds).
 *
 * The split point is chosen at the quietest 20 ms frame in the
 * [minChunkMs]..[maxChunkMs] window rather than at a fixed offset. This is a
 * deliberately small, energy-based boundary heuristic: it is not a VAD and it
 * does not detect speech. It simply makes it much less likely that a word is
 * cut in half. Chunks never overlap, and a boundary is never placed in the
 * first [minChunkMs] of a chunk.
 */
class ChunkPlanner(
    private val minChunkMs: Long = 12_000,
    private val maxChunkMs: Long = 23_000,
    private val energyFrameMs: Int = 20,
) {
    init {
        require(minChunkMs in 1..maxChunkMs)
        require(energyFrameMs in 10..100)
    }

    fun plan(source: AudioSource): List<SampleRange> =
        planRange(source, SampleRange(0, source.totalSamples))

    /**
     * Plans chunk boundaries inside a single [range] instead of the whole
     * source. Used to split one long VAD speech region without crossing its
     * edges. Package-visible: this is an implementation detail of the VAD
     * planner, not part of the public planning API.
     */
    internal fun planRange(source: AudioSource, range: SampleRange): List<SampleRange> {
        val total = range.endSampleExclusive
        val rate = source.sampleRate.toLong()
        if (total <= range.startSample || range.startSample < 0) return emptyList()
        val minSamples = rate * minChunkMs / 1000
        val maxSamples = rate * maxChunkMs / 1000
        val result = mutableListOf<SampleRange>()

        var start = range.startSample
        while (start < total) {
            val remaining = total - start
            if (remaining <= maxSamples) {
                result += SampleRange(start, total)
                break
            }
            val searchStart = start + minSamples
            val searchEnd = minOf(start + maxSamples, total)
            val boundary = quietestBoundary(source, searchStart, searchEnd)
            result += SampleRange(start, boundary)
            start = boundary
        }
        return result
    }

    /**
     * Returns the centre of the quietest frame in [from, to), or [to] when the
     * window cannot produce any complete frame.
     */
    private fun quietestBoundary(source: AudioSource, from: Long, to: Long): Long {
        val rate = source.sampleRate
        val frame = (rate.toLong() * energyFrameMs / 1000).coerceAtLeast(1L).toInt()
        val available = to - from
        if (available < frame) return to

        // Read the whole search window at once (<= 11 s at 16 kHz, ~700 KB).
        val window = source.read(from, available.toInt())
        val frames = window.size / frame
        if (frames == 0) return to

        var bestFrame = 0
        var bestEnergy = Double.MAX_VALUE
        for (index in 0 until frames) {
            val offset = index * frame
            var sum = 0.0
            for (sample in 0 until frame) {
                val value = window[offset + sample].toDouble()
                sum += value * value
            }
            val rms = sqrt(sum / frame)
            if (rms < bestEnergy) {
                bestEnergy = rms
                bestFrame = index
            }
        }
        val centre = from + bestFrame.toLong() * frame + frame / 2
        return centre.coerceIn(from + 1, to)
    }
}

/**
 * Groups adjacent words into utterances using pauses, length limits and —
 * when [speakerOf] is provided — speaker changes. No timestamps are
 * generated here; every boundary comes from a model token or from the
 * caller-supplied speaker identity.
 *
 * A speaker change ALWAYS starts a new utterance, so a transcript segment
 * can never mix speakers. `null` (unknown) is its own identity: unknown
 * words are never silently glued to a neighbouring speaker.
 */
object AsrSegmenter {

    fun segment(
        words: List<AsrWord>,
        maxPauseMs: Long = 700,
        maxUtteranceMs: Long = 15_000,
        maxCharacters: Int = 240,
        speakerOf: (AsrWord) -> String? = { null },
    ): List<AsrUtterance> {
        val cleaned = words
            .filter { it.text.isNotBlank() }
            .map { word ->
                val start = word.startMs.coerceAtLeast(0)
                word.copy(
                    text = word.text.trim(),
                    startMs = start,
                    endMs = word.endMs.coerceAtLeast(start),
                )
            }
            .sortedBy { it.startMs }
        if (cleaned.isEmpty()) return emptyList()

        val utterances = mutableListOf<AsrUtterance>()
        var current = mutableListOf<AsrWord>()

        fun flush() {
            if (current.isEmpty()) return
            val words = current.toList()
            utterances += AsrUtterance(
                text = words.joinToString(" ") { it.text },
                startMs = words.first().startMs,
                endMs = words.last().endMs,
                words = words,
            )
            current = mutableListOf()
        }

        for (word in cleaned) {
            val previous = current.lastOrNull()
            if (previous != null) {
                val gap = word.startMs - previous.endMs
                val duration = word.endMs - current.first().startMs
                val characters = current.sumOf { it.text.length + 1 } + word.text.length
                val speakerChanged = speakerOf(word) != speakerOf(previous)
                if (
                    gap > maxPauseMs ||
                    duration > maxUtteranceMs ||
                    characters > maxCharacters ||
                    speakerChanged
                ) {
                    flush()
                }
            }
            current += word
        }
        flush()
        return utterances
    }
}
