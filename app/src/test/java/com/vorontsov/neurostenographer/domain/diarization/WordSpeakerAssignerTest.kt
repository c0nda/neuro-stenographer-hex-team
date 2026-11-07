package com.vorontsov.neurostenographer.domain.diarization

import com.vorontsov.neurostenographer.domain.asr.AsrSegmenter
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordSpeakerAssignerTest {

    private fun word(text: String, startMs: Long, endMs: Long) =
        AsrWord(text, startMs, endMs, 0.9f)

    private fun turn(startMs: Long, endMs: Long, cluster: Int, confidence: Float? = null) =
        DiarizationTurn(startMs, endMs, cluster, confidence)

    private fun assign(
        words: List<AsrWord>,
        turns: List<DiarizationTurn>,
        minConfidence: Float = 0.0f,
    ) = WordSpeakerAssigner.assign(words, turns, minConfidence)

    @Test
    fun `single speaker speaking continuously gets one stable id`() {
        val words = listOf(word("a", 0, 200), word("b", 250, 500), word("c", 550, 800))
        val result = assign(words, listOf(turn(0, 900, cluster = 0)))
        assertEquals(listOf("speaker_1", "speaker_1", "speaker_1"), result.map { it.speakerId })
    }

    @Test
    fun `two speakers alternating inside one former utterance`() {
        // No pauses at all: previously AsrSegmenter would keep this as ONE
        // utterance; word-level assignment must still separate the speakers.
        val words = listOf(word("а", 0, 300), word("б", 300, 600), word("в", 600, 900))
        val turns = listOf(
            turn(0, 320, cluster = 5),
            turn(320, 620, cluster = 1),
            turn(620, 950, cluster = 5),
        )
        val result = assign(words, turns)
        assertEquals(listOf("speaker_1", "speaker_2", "speaker_1"), result.map { it.speakerId })
    }

    @Test
    fun `speaker change between two consecutive words`() {
        val words = listOf(word("привет", 0, 250), word("здравствуйте", 250, 700))
        val turns = listOf(turn(0, 250, cluster = 2), turn(250, 800, cluster = 9))
        val result = assign(words, turns)
        assertEquals("speaker_1", result[0].speakerId)
        assertEquals("speaker_2", result[1].speakerId)
    }

    @Test
    fun `word overlapping two turns picks the greatest overlap`() {
        // Word spans both turns: 0..200 overlaps turn A by 100 ms and turn B
        // by 120 ms -> turn B (cluster 7) wins.
        val words = listOf(word("пере", 100, 400))
        val turns = listOf(
            turn(0, 200, cluster = 3),
            turn(280, 900, cluster = 7),
        )
        val result = assign(words, turns)
        // Turn A (cluster 3) appears first -> speaker_1; turn B (cluster 7)
        // appears second -> speaker_2. The greatest overlap is with turn B.
        assertEquals("speaker_2", result.single().speakerId)
    }

    @Test
    fun `word with no overlap stays unknown`() {
        val words = listOf(word("один", 5_000, 5_300))
        val result = assign(words, listOf(turn(0, 2_000, cluster = 0)))
        assertNull(result.single().speakerId)
    }

    @Test
    fun `missing diarization confidence does not block assignment`() {
        val words = listOf(word("а", 0, 200))
        val result = assign(words, listOf(turn(0, 900, cluster = 0, confidence = null)))
        assertEquals("speaker_1", result.single().speakerId)
    }

    @Test
    fun `low-confidence turn is not assigned when threshold set`() {
        val words = listOf(word("а", 0, 200))
        val turns = listOf(turn(0, 900, cluster = 0, confidence = 0.2f))
        assertNull(assign(words, turns, minConfidence = 0.5f).single().speakerId)
        // Without a threshold the same turn is used.
        assertEquals("speaker_1", assign(words, turns).single().speakerId)
    }

    @Test
    fun `zero-duration word is handled by point-in-interval`() {
        val inside = word("точка", 500, 500)
        val outside = word("вне", 2_000, 2_000)
        val result = assign(listOf(inside, outside), listOf(turn(0, 900, cluster = 4)))
        assertEquals("speaker_1", result[0].speakerId)
        assertNull(result[1].speakerId)
    }

    @Test
    fun `speaker-aware segmenter groups same-speaker words into one segment`() {
        val words = listOf(word("а", 0, 200), word("б", 250, 500))
        val turns = listOf(turn(0, 900, cluster = 0))
        val assigned = assign(words, turns)
        val byWord = assigned.associate { it.word to it.speakerId }
        val segments = AsrSegmenter.segment(
            assigned.map { it.word },
            speakerOf = { byWord[it] },
        )
        assertEquals(1, segments.size)
        assertEquals("а б", segments.single().text)
    }

    @Test
    fun `different speakers are never grouped into one segment even without pauses`() {
        val words = listOf(word("привет", 0, 250), word("здравствуйте", 250, 700))
        val turns = listOf(turn(0, 250, cluster = 2), turn(250, 800, cluster = 9))
        val assigned = assign(words, turns)
        val byWord = assigned.associate { it.word to it.speakerId }
        val segments = AsrSegmenter.segment(
            assigned.map { it.word },
            speakerOf = { byWord[it] },
        )
        assertEquals(2, segments.size)
    }

    @Test
    fun `unknown words are not glued to a neighbouring speaker`() {
        val words = listOf(word("известно", 0, 300), word("ммм", 300, 600), word("далее", 600, 900))
        val turns = listOf(turn(0, 300, cluster = 1), turn(600, 950, cluster = 1))
        val assigned = assign(words, turns)
        assertEquals(listOf("speaker_1", null, "speaker_1"), assigned.map { it.speakerId })
        val byWord = assigned.associate { it.word to it.speakerId }
        val segments = AsrSegmenter.segment(
            assigned.map { it.word },
            speakerOf = { byWord[it] },
        )
        // Three segments: known / unknown / known.
        assertEquals(3, segments.size)
        assertEquals(listOf("speaker_1", null, "speaker_1"), segments.map { byWord[it.words.first()] })
    }

    @Test
    fun `pause duration and character limits still apply within one speaker`() {
        val turns = listOf(turn(0, 60_000, cluster = 0))
        // 800 ms gap exceeds the default 700 ms max pause -> two segments.
        val withPause = listOf(word("а", 0, 200), word("б", 1_000, 1_300))
        val assigned = assign(withPause, turns)
        val byWord = assigned.associate { it.word to it.speakerId }
        assertEquals(
            2,
            AsrSegmenter.segment(assigned.map { it.word }, speakerOf = { byWord[it] }).size,
        )
        // Overly long utterance -> split even without pauses.
        val longUtterance = listOf(word("а", 0, 10_000), word("б", 10_100, 16_000))
        val assigned2 = assign(longUtterance, turns)
        val byWord2 = assigned2.associate { it.word to it.speakerId }
        assertEquals(
            2,
            AsrSegmenter.segment(assigned2.map { it.word }, speakerOf = { byWord2[it] }).size,
        )
    }
}
