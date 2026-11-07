package com.vorontsov.neurostenographer.domain.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WerTest {

    @Test
    fun `identical texts have zero WER`() {
        val result = Wer.compute("добрый день как дела", "добрый день как дела")
        assertEquals(0, result.errors)
        assertEquals(0f, result.wer, 1e-6f)
        assertEquals(4, result.referenceWords)
    }

    @Test
    fun `normalization ignores case punctuation and yo`() {
        val result = Wer.compute("Добрый день! Как дела?", "добрый день, как дела")
        assertEquals(0, result.errors)

        val yo = Wer.compute("Ёлка стоит", "елка стоит")
        assertEquals(0, yo.errors)
    }

    @Test
    fun `substitutions count once per differing word`() {
        val result = Wer.compute("кот сидит на крыше", "кот сидит под крышей")
        assertEquals(2, result.substitutions)
        assertEquals(0, result.deletions)
        assertEquals(0, result.insertions)
        assertEquals(0.5f, result.wer, 1e-6f)
    }

    @Test
    fun `missing word counts as deletion`() {
        val result = Wer.compute("раз два три четыре", "раз два три")
        assertEquals(1, result.deletions)
        assertEquals(0.25f, result.wer, 1e-6f)
    }

    @Test
    fun `extra word counts as insertion`() {
        val result = Wer.compute("раз два три", "раз два три четыре")
        assertEquals(1, result.insertions)
        assertEquals(1f / 3f, result.wer, 1e-6f)
    }

    @Test
    fun `empty hypothesis is all deletions`() {
        val result = Wer.compute("раз два", "")
        assertEquals(2, result.deletions)
        assertEquals(2, result.errors)
        assertEquals(1f, result.wer, 1e-6f)
    }

    @Test
    fun `empty reference yields NaN WER`() {
        val result = Wer.compute("", "что-то")
        assertEquals(0, result.referenceWords)
        assertTrue(result.wer.isNaN())
        // "что-то" normalizes to two words because the hyphen is punctuation.
        assertEquals(2, result.insertions)
    }

    @Test
    fun `both empty yields zero errors and NaN WER`() {
        val result = Wer.compute("", "")
        assertEquals(0, result.errors)
        assertTrue(result.wer.isNaN())
    }

    @Test
    fun `completely different texts have WER one`() {
        val result = Wer.compute("мама мыла раму", "папа чинил окно")
        assertEquals(3, result.substitutions)
        assertEquals(1f, result.wer, 1e-6f)
    }
}
