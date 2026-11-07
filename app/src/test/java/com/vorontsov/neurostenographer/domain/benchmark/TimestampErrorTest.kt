package com.vorontsov.neurostenographer.domain.benchmark

import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampErrorTest {

    private fun word(text: String, startMs: Long, endMs: Long) = WordTimestamp(
        text = text,
        startMs = startMs,
        endMs = endMs,
        confidence = null,
        alignmentSource = AlignmentSource.CTC_FRAME,
    )

    private fun ref(text: String, startMs: Long, endMs: Long) =
        TimestampError.WordBoundaryReference(text, startMs, endMs)

    @Test
    fun `perfect alignment has zero error`() {
        val report = TimestampError.compute(
            references = listOf(ref("добрый", 0, 300), ref("день", 350, 700)),
            predicted = listOf(word("добрый", 0, 300), word("день", 350, 700)),
        )
        assertEquals(2, report.matchedWords)
        assertEquals(0.0, report.medianStartErrorMs!!, 1e-9)
        assertEquals(0.0, report.medianEndErrorMs!!, 1e-9)
        assertEquals(1.0, report.withinToleranceFraction(40), 1e-9)
    }

    @Test
    fun `constant shift is measured exactly`() {
        val report = TimestampError.compute(
            references = listOf(ref("а", 100, 200), ref("б", 500, 600)),
            predicted = listOf(word("а", 140, 240), word("б", 560, 660)),
        )
        // Errors are 40 and 60 ms: median is 50, half of the words within 40 ms.
        assertEquals(50.0, report.medianStartErrorMs!!, 1e-9)
        assertEquals(50.0, report.medianEndErrorMs!!, 1e-9)
        assertEquals(0.5, report.withinToleranceFraction(40), 1e-9)
        assertEquals(1.0, report.withinToleranceFraction(80), 1e-9)
    }

    @Test
    fun `recognition mismatch does not poison following matches`() {
        // Reference says "кот сидит", model said "код сидит": one substitution,
        // "сидит" must still match with its own timing error.
        val report = TimestampError.compute(
            references = listOf(ref("кот", 0, 200), ref("сидит", 300, 700)),
            predicted = listOf(word("код", 0, 200), word("сидит", 400, 800)),
        )
        assertEquals(1, report.matchedWords)
        assertEquals(1, report.unmatchedReferenceWords)
        assertEquals(1, report.unmatchedPredictedWords)
        assertEquals(100.0, report.medianStartErrorMs!!, 1e-9)
    }

    @Test
    fun `extra predicted word counts as unmatched prediction`() {
        val report = TimestampError.compute(
            references = listOf(ref("а", 0, 100)),
            predicted = listOf(word("а", 0, 100), word("лишнее", 200, 300)),
        )
        assertEquals(1, report.matchedWords)
        assertEquals(0, report.unmatchedReferenceWords)
        assertEquals(1, report.unmatchedPredictedWords)
    }

    @Test
    fun `normalization matches case punctuation and yo`() {
        val report = TimestampError.compute(
            references = listOf(ref("Ёлка!", 0, 100)),
            predicted = listOf(word("елка", 10, 90)),
        )
        assertEquals(1, report.matchedWords)
        assertEquals(10.0, report.medianStartErrorMs!!, 1e-9)
    }

    @Test
    fun `empty inputs produce null statistics`() {
        val report = TimestampError.compute(emptyList(), emptyList())
        assertEquals(0, report.matchedWords)
        assertNull(report.medianStartErrorMs)
        assertNull(report.p90EndErrorMs)
    }

    @Test
    fun `p90 sits between median and max`() {
        val references = (0 until 10).map { ref("w$it", it * 100L, it * 100L + 50) }
        val predicted = (0 until 10).map { word("w$it", it * 100L + it * 10, it * 100L + 50) }
        val report = TimestampError.compute(references, predicted)
        // Start errors are 0,10,20,...,90: median 45, p90 81.
        assertEquals(45.0, report.medianStartErrorMs!!, 1e-9)
        assertEquals(81.0, report.p90StartErrorMs!!, 1e-9)
    }

    @Test
    fun `parser reads tab separated references and skips comments`() {
        val parsed = TimestampError.parseReferences(
            """
            # reference for clip.wav
            добрый	0	300
            день	350	700

            """.trimIndent(),
        )
        assertEquals(2, parsed.size)
        assertEquals("добрый", parsed[0].text)
        assertEquals(350L, parsed[1].startMs)
    }

    @Test
    fun `parser rejects malformed lines loudly`() {
        var thrown: Throwable? = null
        try {
            TimestampError.parseReferences("добрый 0 300")
        } catch (throwable: Throwable) {
            thrown = throwable
        }
        assertTrue(thrown is IllegalArgumentException)
    }
}
