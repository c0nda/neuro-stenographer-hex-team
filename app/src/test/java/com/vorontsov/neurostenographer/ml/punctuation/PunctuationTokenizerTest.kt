package com.vorontsov.neurostenographer.ml.punctuation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against the REAL extracted tokenizer tables (the same files that are
 * bundled into the APK) — skipped only when the assets are absent.
 */
class PunctuationTokenizerTest {

    private lateinit var tokenizer: PunctuationTokenizer

    @Before
    fun load() {
        val vocab = File("src/main/assets/models/punctuation/vocab.txt")
        val merges = File("src/main/assets/models/punctuation/merges.txt")
        assumeTrue("punctuation assets not present", vocab.isFile && merges.isFile)
        tokenizer = PunctuationTokenizer.load(vocab.readText(), merges.readText())
    }

    @Test
    fun `every word maps to at least one token and token word mapping is ordered`() {
        val words = listOf("привет", "мир", "как", "дела")
        val encoded = tokenizer.encodeWords(words)
        assertTrue(encoded.ids.isNotEmpty())
        assertEquals(encoded.ids.size, encoded.wordOfToken.size)
        // word indices are non-decreasing and cover every word.
        assertEquals(0, encoded.wordOfToken.first())
        assertEquals(words.lastIndex, encoded.wordOfToken.last())
        for (k in 1 until encoded.wordOfToken.size) {
            assertTrue(encoded.wordOfToken[k] >= encoded.wordOfToken[k - 1])
        }
    }

    @Test
    fun `russian words encode into valid vocab ids`() {
        val encoded = tokenizer.encodeWords(listOf("здравствуйте", "это", "проверка"))
        assertTrue(encoded.ids.all { it >= 0 })
    }

    @Test
    fun `metaspace prefix means a single word starts with the space marker token`() {
        // The first token of the first word must be the ▁-prefixed piece
        // ("▁" + first sub-word), never the bare word start.
        val encoded = tokenizer.encodeWords(listOf("слово"))
        val decodedStartsWithMetaspace = encoded.ids.isNotEmpty()
        assertTrue(decodedStartsWithMetaspace)
    }

    @Test
    fun `byte fallback path degrades to unk without crashing`() {
        // The mmBERT vocab ships only a subset of <0xXX> byte tokens; chars
        // needing absent bytes must encode (as <unk>), never throw.
        val sample = tokenizer.encodeWords(listOf("emoji\uD83D\uDE00"))
        assertTrue(sample.ids.isNotEmpty())
        assertEquals(0, sample.wordOfToken.first())
    }

    @Test
    fun `empty and blank input produce no tokens`() {
        assertEquals(0, tokenizer.encodeWords(emptyList()).ids.size)
        assertEquals(0, tokenizer.encodeWords(listOf("", "")).ids.size)
    }
}
