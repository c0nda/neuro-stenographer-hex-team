package com.vorontsov.neurostenographer.ml.asr.gigaam

import com.vorontsov.neurostenographer.domain.asr.AsrWord
import kotlin.math.exp

/**
 * One non-blank token selected by greedy CTC decoding.
 *
 * [frame] is the encoder output frame that emitted the token. GigaAM v3 uses a
 * convolution subsampling factor of 4 and a 10 ms frontend hop, so one encoder
 * frame equals 40 ms of audio.
 */
data class CtcToken(
    val id: Int,
    val frame: Int,
    val logProbability: Float,
)

/**
 * Greedy CTC decoding for the char-level GigaAM v3 vocabulary.
 *
 * [logProbs] must be the model output `log_probs` flattened as
 * `[frames][classes]`. Blank is class 33; classes 0..32 are characters
 * (0 is the word separator).
 */
object GigaamCtcDecoder {

    const val CLASSES = 34
    const val BLANK_ID = 33
    const val SPACE_ID = 0
    const val ENCODER_FRAME_MS = 40L

    /**
     * Collapses repeated frames and drops blanks, as required by CTC. A token is
     * emitted only when the argmax changes and is not blank.
     */
    fun decode(logProbs: FloatArray, frames: Int, classes: Int = CLASSES): List<CtcToken> {
        require(classes > 0) { "classes must be positive" }
        require(logProbs.size >= frames * classes) {
            "log_probs has ${logProbs.size} values, expected at least ${frames * classes}"
        }
        val tokens = ArrayList<CtcToken>()
        var previous = -1
        for (frame in 0 until frames) {
            val offset = frame * classes
            var best = 0
            var bestValue = logProbs[offset]
            for (classIndex in 1 until classes) {
                val value = logProbs[offset + classIndex]
                if (value > bestValue) {
                    bestValue = value
                    best = classIndex
                }
            }
            if (best != blankFor(classes)) {
                if (best != previous || previous == blankFor(classes)) {
                    tokens += CtcToken(best, frame, bestValue)
                }
                previous = best
            } else {
                previous = blankFor(classes)
            }
        }
        return tokens
    }

    private fun blankFor(classes: Int): Int = if (classes == CLASSES) BLANK_ID else classes - 1
}

/**
 * Maps char-level GigaAM tokens to words and model-derived timestamps.
 *
 * The vocabulary is fixed by the published `v3_vocab.txt`:
 * 0 = word boundary (`▁`), 1 = а … 32 = я, 33 = blank (already dropped by the
 * decoder). GigaAM v3 CTC is lower-case and has no punctuation, so the result is
 * intentionally not post-processed or capitalized.
 */
object GigaamWordBuilder {

    /** Index 0 is the separator, indices 1..32 are the Russian letters without ё. */
    const val VOCABULARY = " абвгдежзийклмнопрстуфхцчшщъыьэюя"

    fun buildWords(tokens: List<CtcToken>): List<AsrWord> {
        if (tokens.isEmpty()) return emptyList()
        val words = ArrayList<AsrWord>()
        val characters = ArrayList<CtcToken>()
        var probabilities = 0.0
        var probabilityCount = 0

        fun flush() {
            if (characters.isEmpty()) return
            val text = buildString(characters.size) {
                characters.forEach { token ->
                    val index = token.id
                    if (index in 1 until VOCABULARY.length) append(VOCABULARY[index])
                }
            }
            if (text.isNotBlank()) {
                val start = characters.first().frame * GigaamCtcDecoder.ENCODER_FRAME_MS
                val end = (characters.last().frame + 1) * GigaamCtcDecoder.ENCODER_FRAME_MS
                val confidence = if (probabilityCount == 0) {
                    null
                } else {
                    (probabilities / probabilityCount).toFloat().coerceIn(0f, 1f)
                }
                words += AsrWord(text, start, end, confidence)
            }
            characters.clear()
            probabilities = 0.0
            probabilityCount = 0
        }

        for (token in tokens) {
            when {
                token.id == GigaamCtcDecoder.SPACE_ID -> flush()
                token.id in 1 until VOCABULARY.length -> {
                    characters += token
                    probabilities += exp(token.logProbability.toDouble())
                    probabilityCount++
                }
                else -> Unit
            }
        }
        flush()
        return words
    }
}
