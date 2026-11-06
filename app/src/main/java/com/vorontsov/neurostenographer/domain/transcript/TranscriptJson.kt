package com.vorontsov.neurostenographer.domain.transcript

import com.vorontsov.neurostenographer.domain.model.TranscriptSegment

/**
 * Serializer for the single public transcript contract:
 *
 * {"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}
 *
 * The serializer is deliberately strict: invalid segments fail loudly instead of
 * silently producing a corrupt contract. It never adds fields beyond the four
 * required ones.
 */
object TranscriptJson {

    private val speakerIdPattern = Regex("^(speaker_[1-9][0-9]*|unknown)$")

    fun encode(segments: List<TranscriptSegment>): String {
        validate(segments)
        val ordered = segments.sortedWith(
            compareBy<TranscriptSegment> { it.startMs }
                .thenBy { it.endMs }
                .thenBy { it.id ?: Long.MAX_VALUE },
        )
        return buildString {
            append("{\"transcript\":[")
            ordered.forEachIndexed { index, segment ->
                if (index > 0) append(',')
                append('{')
                append("\"speaker_id\":\"")
                append(escape(normalizeSpeakerId(segment.speakerId)))
                append("\",")
                append("\"start_ms\":")
                append(segment.startMs)
                append(',')
                append("\"end_ms\":")
                append(segment.endMs)
                append(',')
                append("\"text\":\"")
                append(escape(segment.text))
                append("\"}")
            }
            append("]}")
        }
    }

    fun validate(segments: List<TranscriptSegment>): List<String> {
        val errors = mutableListOf<String>()
        segments.forEachIndexed { index, segment ->
            if (segment.startMs < 0) {
                errors += "segment[$index].start_ms must be >= 0"
            }
            if (segment.endMs < segment.startMs) {
                errors += "segment[$index].end_ms must be >= start_ms"
            }
            val speakerId = normalizeSpeakerId(segment.speakerId)
            if (!speakerIdPattern.matches(speakerId)) {
                errors += "segment[$index].speaker_id must match speaker_N or unknown, was '$speakerId'"
            }
            if (segment.text.isBlank()) {
                errors += "segment[$index].text must not be blank"
            }
        }
        return errors
    }

    /**
     * Null, blank and the legacy literal "unknown" all map to the required
     * public identifier "unknown".
     */
    fun normalizeSpeakerId(speakerId: String?): String {
        val value = speakerId?.trim().orEmpty()
        return if (value.isEmpty()) "unknown" else value
    }

    private fun escape(value: String): String {
        val result = StringBuilder(value.length + 8)
        value.forEach { character ->
            when (character) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        result.append("\\u")
                        result.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        result.append(character)
                    }
                }
            }
        }
        return result.toString()
    }
}
