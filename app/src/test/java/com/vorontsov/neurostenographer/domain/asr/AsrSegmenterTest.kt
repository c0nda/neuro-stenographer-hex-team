package com.vorontsov.neurostenographer.domain.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrSegmenterTest {

    @Test
    fun `long pause starts a new utterance`() {
        val words = listOf(
            word("привет", 0, 400),
            word("мир", 500, 800),
            word("дальше", 1_600, 2_000),
        )

        val utterances = AsrSegmenter.segment(words)

        assertEquals(2, utterances.size)
        assertEquals("привет мир", utterances[0].text)
        assertEquals(0, utterances[0].startMs)
        assertEquals(800, utterances[0].endMs)
        assertEquals(1_600, utterances[1].startMs)
    }

    @Test
    fun `duration limit splits a long monologue`() {
        val words = (0 until 10).map { index ->
            word("слово$index", index * 2_000L, index * 2_000L + 1_500)
        }

        val utterances = AsrSegmenter.segment(words, maxUtteranceMs = 6_000)

        assertTrue(utterances.size >= 3)
        utterances.forEach { utterance ->
            assertTrue(utterance.endMs - utterance.startMs <= 7_500)
        }
    }

    @Test
    fun `blank words are ignored and timestamps stay ordered`() {
        val words = listOf(
            word(" ", 0, 100),
            word("второе", 300, 500),
            word("первое", 100, 200),
        )

        val utterances = AsrSegmenter.segment(words)

        assertEquals(listOf("первое второе"), utterances.map { it.text })
        assertEquals(100, utterances[0].startMs)
        assertEquals(500, utterances[0].endMs)
        assertEquals(2, utterances[0].words.size)
    }

    @Test
    fun `empty input produces no utterances`() {
        assertTrue(AsrSegmenter.segment(emptyList()).isEmpty())
    }

    private fun word(text: String, start: Long, end: Long) =
        AsrWord(text = text, startMs = start, endMs = end, confidence = 0.9f)
}
