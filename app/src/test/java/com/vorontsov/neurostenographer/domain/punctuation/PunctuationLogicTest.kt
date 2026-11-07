package com.vorontsov.neurostenographer.domain.punctuation

import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.ml.punctuation.OnnxPunctuationRestorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationLogicTest {

    private fun word(text: String, startMs: Long = 0, endMs: Long = 10) =
        AsrWord(text, startMs, endMs, 0.9f)

    @Test
    fun `sentence starts are capitalized`() {
        val words = listOf(
            word("привет"),
            word("как"),
            word("дела."),
            word("хорошо"),
            word("а"),
            word("ты?"),
            word("я"),
            word("тоже"),
        )
        val result = PunctuationPostProcessor.capitalizeSentenceStarts(words)
        assertEquals("Привет", result[0].text)   // first word capitalized
        assertEquals("как", result[1].text)      // mid-sentence untouched
        assertEquals("Хорошо", result[3].text)   // after "." -> new sentence
        assertEquals("а", result[4].text)        // mid-sentence untouched
        assertEquals("Я", result[6].text)        // after "?" -> new sentence
        assertEquals("тоже", result[7].text)
    }

    @Test
    fun `question and exclamation ends start a new sentence`() {
        val words = listOf(word("ты дома?"), word("да"), word("отлично!"), word("ура"))
        val result = PunctuationPostProcessor.capitalizeSentenceStarts(words)
        assertEquals("Да", result[1].text)
        assertEquals("Ура", result[3].text)
    }

    @Test
    fun `comma and colon do not end a sentence`() {
        val words = listOf(word("итак,"), word("второе:"), word("третье"))
        val result = PunctuationPostProcessor.capitalizeSentenceStarts(words)
        assertEquals("второе:", result[1].text)
        assertEquals("третье", result[2].text)
    }

    @Test
    fun `capitalization preserves timestamps and confidence`() {
        val words = listOf(word("привет", 100, 200))
        val result = PunctuationPostProcessor.capitalizeSentenceStarts(words)
        assertEquals(100L, result[0].startMs)
        assertEquals(200L, result[0].endMs)
        assertEquals(0.9f, result[0].confidence!!, 1e-6f)
    }

    @Test
    fun `windows cover every token exactly once`() {
        for (count in listOf(1, 50, 400, 401, 800, 850, 2000)) {
            val windows = OnnxPunctuationRestorer.planWindows(count)
            var cursor = 0
            for (window in windows) {
                assertEquals(cursor, window.start)
                assertTrue(window.keepTo > window.start)
                assertTrue(window.end <= count)
                assertTrue(window.keepTo <= window.end)
                cursor = window.keepTo
            }
            assertEquals(count, cursor)
        }
    }

    @Test
    fun `windows beyond the first carry overlap context`() {
        val windows = OnnxPunctuationRestorer.planWindows(1000)
        assertTrue(windows.size >= 3)
        // Overlap: every window except the last starts its keepTo inside the
        // following window's context range.
        for (i in 0 until windows.lastIndex) {
            assertTrue(windows[i].keepTo < windows[i].end)
        }
    }
}
