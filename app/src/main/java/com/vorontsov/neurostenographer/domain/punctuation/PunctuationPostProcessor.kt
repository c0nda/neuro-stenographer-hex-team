package com.vorontsov.neurostenographer.domain.punctuation

import com.vorontsov.neurostenographer.domain.asr.AsrWord

/**
 * Capitalization post-processing. The punctuation model restores marks only
 * and deliberately does not change casing, so sentence starts are
 * capitalized here as a separate step (per task spec).
 *
 * Rules:
 *  - the first alphabetic character of the first word is uppercased;
 *  - a word that follows a word ending with `.`, `?` or `!` starts a new
 *    sentence and is capitalized;
 *  - words are never otherwise modified; `е` is NOT converted to `ё`.
 */
object PunctuationPostProcessor {

    private val SENTENCE_ENDS = charArrayOf('.', '?', '!')

    fun capitalizeSentenceStarts(words: List<AsrWord>): List<AsrWord> {
        if (words.isEmpty()) return words
        var sentenceStart = true
        return words.map { word ->
            val text = if (sentenceStart) capitalizeFirstLetter(word.text) else word.text
            sentenceStart = endsSentence(text)
            if (text === word.text) word else word.copy(text = text)
        }
    }

    private fun capitalizeFirstLetter(text: String): String {
        for (index in text.indices) {
            val char = text[index]
            if (char.isLetter()) {
                if (char.isUpperCase()) return text
                return text.substring(0, index) + char.uppercaseChar() + text.substring(index + 1)
            }
        }
        return text
    }

    private fun endsSentence(text: String): Boolean {
        for (index in text.length - 1 downTo 0) {
            val char = text[index]
            if (char == ' ') continue
            return char in SENTENCE_ENDS
        }
        return false
    }
}
