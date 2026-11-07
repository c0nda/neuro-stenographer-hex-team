package com.vorontsov.neurostenographer.domain.diarization

import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerTurnMergerTest {

    private fun segment(startMs: Long, endMs: Long, text: String = "слово") = TranscriptSegment(
        id = null,
        speakerId = null,
        startMs = startMs,
        endMs = endMs,
        text = text,
        isProvisional = false,
        alignmentSource = AlignmentSource.CTC_FRAME,
        words = listOf(
            WordTimestamp(text, startMs, endMs, 0.9f, AlignmentSource.CTC_FRAME),
        ),
    )

    private fun turn(
        startMs: Long,
        endMs: Long,
        cluster: Int,
        confidence: Float? = null,
    ) = DiarizationTurn(startMs, endMs, cluster, confidence)

    @Test
    fun `segment gets the speaker of the most overlapping turn`() {
        val segments = listOf(segment(1_000, 4_000))
        val turns = listOf(
            turn(0, 1_200, cluster = 7),
            turn(1_100, 5_000, cluster = 3),
        )
        // Turn (1100..5000) overlaps 2900 ms vs 200 ms for (0..1200), so the
        // segment gets cluster 3, which appears second → speaker_2.
        val merged = SpeakerTurnMerger.assign(segments, turns)
        assertEquals("speaker_2", merged.single().speakerId)
    }

    @Test
    fun `speaker keys follow first appearance not cluster index`() {
        val segments = listOf(
            segment(0, 1_000),
            segment(1_000, 2_000),
        )
        // Cluster 5 appears first, cluster 1 second: keys must be speaker_1
        // and speaker_2 respectively regardless of raw indices.
        val turns = listOf(
            turn(0, 900, cluster = 5),
            turn(1_000, 2_000, cluster = 1),
        )
        val merged = SpeakerTurnMerger.assign(segments, turns)
        assertEquals("speaker_1", merged[0].speakerId)
        assertEquals("speaker_2", merged[1].speakerId)
    }

    @Test
    fun `segment overlapping no turn stays unknown`() {
        val segments = listOf(segment(10_000, 11_000))
        val turns = listOf(turn(0, 2_000, cluster = 0))
        val merged = SpeakerTurnMerger.assign(segments, turns)
        assertNull(merged.single().speakerId)
    }

    @Test
    fun `low-confidence turn is not assigned`() {
        val segments = listOf(segment(1_000, 2_000))
        val turns = listOf(turn(1_000, 2_000, cluster = 0, confidence = 0.1f))
        val merged = SpeakerTurnMerger.assign(segments, turns, minConfidence = 0.5f)
        assertNull(merged.single().speakerId)
    }

    @Test
    fun `unavailable confidence does not block assignment`() {
        val segments = listOf(segment(1_000, 2_000))
        val turns = listOf(turn(1_000, 2_000, cluster = 0, confidence = null))
        val merged = SpeakerTurnMerger.assign(segments, turns, minConfidence = 0.5f)
        assertEquals("speaker_1", merged.single().speakerId)
    }

    @Test
    fun `words text and order of segments are untouched`() {
        val segments = listOf(
            segment(5_000, 6_000, text = "привет"),
            segment(1_000, 2_000, text = "пока"),
        )
        val turns = listOf(turn(0, 3_000, cluster = 0), turn(4_000, 7_000, cluster = 2))
        val merged = SpeakerTurnMerger.assign(segments, turns)
        assertEquals(listOf("пока", "привет"), merged.map { it.text }.sorted())
        assertEquals("привет", merged[0].text)
        assertEquals(1, merged[0].words.size)
        assertEquals(5_000L, merged[0].words.single().startMs)
    }

    @Test
    fun `same cluster keeps one stable key across distant turns`() {
        val segments = listOf(
            segment(0, 1_000),
            segment(60_000, 61_000),
        )
        val turns = listOf(
            turn(0, 1_000, cluster = 4),
            turn(60_000, 61_000, cluster = 4),
        )
        val merged = SpeakerTurnMerger.assign(segments, turns)
        assertEquals("speaker_1", merged[0].speakerId)
        assertEquals("speaker_1", merged[1].speakerId)
    }

    @Test
    fun `empty turns make every segment unknown`() {
        val segments = listOf(segment(0, 1_000), segment(2_000, 3_000))
        val merged = SpeakerTurnMerger.assign(segments, emptyList())
        assertEquals(listOf(null, null), merged.map { it.speakerId })
    }

    @Test
    fun `empty segments stay empty`() {
        assertEquals(emptyList<TranscriptSegment>(), SpeakerTurnMerger.assign(emptyList(), listOf(turn(0, 1, 0))))
    }
}
