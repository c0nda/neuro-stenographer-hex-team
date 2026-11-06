package com.vorontsov.neurostenographer.ml.asr.gigaam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GigaamCtcDecoderTest {

    private val classes = GigaamCtcDecoder.CLASSES
    private val blank = GigaamCtcDecoder.BLANK_ID

    @Test
    fun `repeated frames are collapsed and blanks dropped`() {
        val frames = intArrayOf(1, 1, blank, 2, 1, 1)
        val logProbs = logits(frames)

        val tokens = GigaamCtcDecoder.decode(logProbs, frames.size)

        // CTC rule: "a a _ b a" -> "a b a"; the second "a" is a new emission.
        assertEquals(listOf(1, 2, 1), tokens.map { it.id })
        assertEquals(listOf(0, 3, 4), tokens.map { it.frame })
    }

    @Test
    fun `single character word is decoded with frame timestamps`() {
        val tokens = GigaamCtcDecoder.decode(logits(intArrayOf(6)), 1)
        val words = GigaamWordBuilder.buildWords(tokens)

        assertEquals(1, words.size)
        assertEquals("е", words[0].text)
        assertEquals(0, words[0].startMs)
        assertEquals(40, words[0].endMs)
    }

    @Test
    fun `space token splits words and timestamps follow token frames`() {
        // "да" at frames 0..1, separator at 2, "нет" at frames 3..5
        val frames = intArrayOf(5, 1, GigaamCtcDecoder.SPACE_ID, 14, 6, 19)
        val tokens = GigaamCtcDecoder.decode(logits(frames), frames.size)
        val words = GigaamWordBuilder.buildWords(tokens)

        assertEquals(listOf("да", "нет"), words.map { it.text })
        assertEquals(0, words[0].startMs)
        assertEquals(80, words[0].endMs)
        assertEquals(120, words[1].startMs)
        assertEquals(240, words[1].endMs)
    }

    @Test
    fun `multiple separators do not create empty words`() {
        val frames = intArrayOf(1, 0, 0, 2)
        val tokens = GigaamCtcDecoder.decode(logits(frames), frames.size)
        val words = GigaamWordBuilder.buildWords(tokens)

        // Repeated separators collapse under CTC, so they only delimit the two
        // real emissions and never produce a blank word.
        assertEquals(listOf("а", "б"), words.map { it.text })
        assertTrue(words.none { it.text.isBlank() })
    }

    @Test
    fun `blank only audio produces an empty transcript`() {
        val frames = intArrayOf(blank, blank, blank)
        val tokens = GigaamCtcDecoder.decode(logits(frames), frames.size)
        assertTrue(tokens.isEmpty())
        assertTrue(GigaamWordBuilder.buildWords(tokens).isEmpty())
    }

    @Test
    fun `confidence comes from the selected token log probabilities`() {
        val frames = intArrayOf(6)
        val logProbs = FloatArray(classes) { -10f }
        logProbs[6] = (Math.log(0.8)).toFloat()
        val tokens = GigaamCtcDecoder.decode(logProbs, 1)
        val words = GigaamWordBuilder.buildWords(tokens)
        assertEquals(0.8f, words[0].confidence!!, 1e-5f)
    }

    @Test
    fun `tokens outside the vocabulary are ignored`() {
        val frames = intArrayOf(1, blank, 2)
        val tokens = GigaamCtcDecoder.decode(logits(frames), frames.size)
        assertNull(GigaamWordBuilder.buildWords(tokens).firstOrNull { it.text.isEmpty() })
        assertEquals(1, GigaamWordBuilder.buildWords(tokens).size)
    }

    /** One-hot logits with a strong value at the selected class per frame. */
    private fun logits(ids: IntArray): FloatArray {
        val result = FloatArray(ids.size * classes) { -20f }
        ids.forEachIndexed { frame, id ->
            result[frame * classes + id] = 0f
        }
        return result
    }
}
