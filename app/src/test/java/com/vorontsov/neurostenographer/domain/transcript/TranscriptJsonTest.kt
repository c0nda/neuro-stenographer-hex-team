package com.vorontsov.neurostenographer.domain.transcript

import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptJsonTest {

    @Test
    fun `empty transcript uses the public envelope`() {
        assertEquals("{\"transcript\":[]}", TranscriptJson.encode(emptyList()))
    }

    @Test
    fun `segment is serialized with exactly the four contract fields`() {
        val json = TranscriptJson.encode(
            listOf(
                TranscriptSegment(
                    id = 1,
                    speakerId = "speaker_1",
                    startMs = 1250,
                    endMs = 4380,
                    text = "Привет",
                    isProvisional = true,
                ),
            ),
        )

        assertEquals(
            "{\"transcript\":[{\"speaker_id\":\"speaker_1\",\"start_ms\":1250," +
                "\"end_ms\":4380,\"text\":\"Привет\"}]}",
            json,
        )
        assertTrue(!json.contains("provisional"))
        assertTrue(!json.contains("words"))
        assertTrue(!json.contains("alignment"))
    }

    @Test
    fun `segments are ordered by start then end`() {
        val json = TranscriptJson.encode(
            listOf(
                TranscriptSegment(null, "speaker_2", 3000, 4000, "third"),
                TranscriptSegment(null, "speaker_2", 1000, 2500, "second"),
                TranscriptSegment(null, "speaker_1", 1000, 2000, "first"),
            ),
        )

        assertEquals(
            "{\"transcript\":[" +
                "{\"speaker_id\":\"speaker_1\",\"start_ms\":1000,\"end_ms\":2000,\"text\":\"first\"}," +
                "{\"speaker_id\":\"speaker_2\",\"start_ms\":1000,\"end_ms\":2500,\"text\":\"second\"}," +
                "{\"speaker_id\":\"speaker_2\",\"start_ms\":3000,\"end_ms\":4000,\"text\":\"third\"}" +
                "]}",
            json,
        )
    }

    @Test
    fun `null and blank speakers normalize to unknown`() {
        val json = TranscriptJson.encode(
            listOf(
                TranscriptSegment(null, null, 0, 100, "one"),
                TranscriptSegment(null, "   ", 100, 200, "two"),
            ),
        )

        assertEquals(2, Regex("\"speaker_id\":\"unknown\"").findAll(json).count())
    }

    @Test
    fun `overlapping segments keep both entries`() {
        val segments = listOf(
            TranscriptSegment(null, "speaker_1", 1000, 4000, "long"),
            TranscriptSegment(null, "speaker_2", 1500, 2500, "overlap"),
        )

        val json = TranscriptJson.encode(segments)

        assertTrue(json.contains("\"start_ms\":1000,\"end_ms\":4000"))
        assertTrue(json.contains("\"start_ms\":1500,\"end_ms\":2500"))
    }

    @Test
    fun `text is escaped without breaking the contract`() {
        val json = TranscriptJson.encode(
            listOf(
                TranscriptSegment(
                    null,
                    "speaker_1",
                    0,
                    1000,
                    "quote \" backslash \\ newline \n tab \t control \u0001",
                ),
            ),
        )

        assertTrue(json.contains("quote \\\" backslash \\\\ newline \\n tab \\t control \\u0001"))
    }

    @Test
    fun `validation rejects timestamps and blank text`() {
        val errors = TranscriptJson.validate(
            listOf(
                TranscriptSegment(null, "speaker_1", -1, 100, "negative"),
                TranscriptSegment(null, "speaker_1", 200, 100, "reversed"),
                TranscriptSegment(null, "speaker_1", 0, 100, "   "),
            ),
        )

        assertTrue(errors.any { it.contains("start_ms") })
        assertTrue(errors.any { it.contains("end_ms") })
        assertTrue(errors.any { it.contains("text") })
    }

    @Test
    fun `validation rejects speaker identifiers outside the contract`() {
        val errors = TranscriptJson.validate(
            listOf(TranscriptSegment(null, "speaker_0", 0, 100, "hello")),
        )

        assertTrue(errors.any { it.contains("speaker_id") })
    }

    @Test
    fun `valid speaker identifiers pass validation`() {
        val errors = TranscriptJson.validate(
            listOf(
                TranscriptSegment(null, "speaker_1", 0, 100, "one"),
                TranscriptSegment(null, "speaker_42", 100, 200, "two"),
                TranscriptSegment(null, "unknown", 200, 300, "three"),
                TranscriptSegment(null, null, 300, 400, "four"),
            ),
        )

        assertTrue(errors.isEmpty())
    }
}
