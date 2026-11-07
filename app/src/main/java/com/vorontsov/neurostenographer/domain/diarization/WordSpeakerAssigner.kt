package com.vorontsov.neurostenographer.domain.diarization

import com.vorontsov.neurostenographer.domain.asr.AsrWord
import kotlin.math.max
import kotlin.math.min

/**
 * Assigns a speaker id to every individual word BEFORE utterance
 * segmentation. Replaces the old segment-level merge: a transcript segment
 * can no longer mix speakers, because words of different speakers are split
 * by the speaker-aware segmenter downstream.
 *
 * Rules:
 *  - each word is compared against the diarization turns; the turn with the
 *    GREATEST temporal overlap wins (ties keep the earliest-starting turn, so
 *    results are deterministic even when turns overlap);
 *  - cluster indices become stable `speaker_N` keys in order of first
 *    appearance of the cluster among the eligible turns;
 *  - a turn whose [DiarizationTurn.confidence] is available but below
 *    [minConfidence] is treated as unreliable and never assigned — it is
 *    diarization confidence and has nothing to do with ASR word confidence;
 *  - a word with no meaningful overlap keeps `speakerId = null` (serializes
 *    as "unknown"); assignments are never guessed from neighbours;
 *  - zero-duration words are handled by a point-in-interval test.
 */
object WordSpeakerAssigner {

    data class AssignedWord(
        val word: AsrWord,
        val speakerId: String?,
    )

    fun assign(
        words: List<AsrWord>,
        turns: List<DiarizationTurn>,
        minConfidence: Float = 0.0f,
    ): List<AssignedWord> {
        if (words.isEmpty()) return emptyList()

        val eligible = turns
            .filter { it.confidence == null || it.confidence >= minConfidence }
            .sortedBy { it.startMs }

        // Stable public speaker keys in order of first appearance.
        val keyByCluster = LinkedHashMap<Int, String>()
        var nextSpeaker = 1
        for (turn in eligible) {
            if (turn.clusterIndex !in keyByCluster) {
                keyByCluster[turn.clusterIndex] = "speaker_${nextSpeaker++}"
            }
        }

        return words.map { word -> AssignedWord(word, bestTurn(word, eligible)?.let { keyByCluster[it.clusterIndex] }) }
    }

    private fun bestTurn(word: AsrWord, sortedTurns: List<DiarizationTurn>): DiarizationTurn? {
        var best: DiarizationTurn? = null
        var bestOverlapMs = 0L
        for (turn in sortedTurns) {
            if (turn.startMs > word.endMs) break // sorted by start; nothing later can overlap
            val overlap = overlapMs(word, turn)
            if (overlap > bestOverlapMs) {
                bestOverlapMs = overlap
                best = turn
            }
        }
        return best
    }

    private fun overlapMs(word: AsrWord, turn: DiarizationTurn): Long =
        if (word.endMs > word.startMs) {
            max(0L, min(word.endMs, turn.endMs) - max(word.startMs, turn.startMs))
        } else {
            // Zero-duration word: point-in-interval counts as a minimal hit.
            if (turn.startMs <= word.startMs && word.startMs <= turn.endMs) 1L else 0L
        }
}
