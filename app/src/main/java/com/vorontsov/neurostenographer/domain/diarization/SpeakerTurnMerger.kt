package com.vorontsov.neurostenographer.domain.diarization

import com.vorontsov.neurostenographer.domain.model.TranscriptSegment

/**
 * Merges diarization turns with ASR transcript segments.
 *
 * Rules (PROMT §4 and §3.4):
 *  - Cluster indices become stable `speaker_N` keys in order of first
 *    appearance, so ids stay stable within a recording.
 *  - A segment is assigned the speaker of the turn with the largest time
 *    overlap. Segments overlapping no turn keep `speakerId = null` (which
 *    serializes as `"unknown"`).
 *  - A turn whose [DiarizationTurn.confidence] is available but below
 *    [minConfidence] is treated as unreliable and never assigned.
 *  - Words, text and timestamps of segments are never modified, and segment
 *    order is preserved. Overlapping speech is NOT fabricated: the
 *    diarization output is a sequence of single-speaker turns and each
 *    segment receives exactly one speaker.
 */
object SpeakerTurnMerger {

    fun assign(
        segments: List<TranscriptSegment>,
        turns: List<DiarizationTurn>,
        minConfidence: Float = 0.0f,
    ): List<TranscriptSegment> {
        if (segments.isEmpty()) return segments
        if (turns.isEmpty()) return segments.map { it.copy(speakerId = null) }

        val sortedTurns = turns.sortedBy { it.startMs }

        // Stable public speaker keys in order of first appearance.
        val keyByCluster = LinkedHashMap<Int, String>()
        var nextSpeaker = 1
        for (turn in sortedTurns) {
            if (turn.clusterIndex !in keyByCluster) {
                keyByCluster[turn.clusterIndex] = "speaker_${nextSpeaker++}"
            }
        }

        return segments.map { segment ->
            val best = bestTurn(segment, sortedTurns, minConfidence)
            segment.copy(speakerId = best?.let { keyByCluster[it.clusterIndex] })
        }
    }

    private fun bestTurn(
        segment: TranscriptSegment,
        sortedTurns: List<DiarizationTurn>,
        minConfidence: Float,
    ): DiarizationTurn? {
        var best: DiarizationTurn? = null
        var bestOverlapMs = 0L
        for (turn in sortedTurns) {
            if (turn.startMs > segment.endMs) break // sorted by start
            val overlap = minOf(segment.endMs, turn.endMs) - maxOf(segment.startMs, turn.startMs)
            if (overlap > bestOverlapMs) {
                bestOverlapMs = overlap
                best = turn
            }
        }
        if (best == null || bestOverlapMs <= 0) return null
        val confidence = best.confidence
        if (confidence != null && confidence < minConfidence) return null
        return best
    }
}
