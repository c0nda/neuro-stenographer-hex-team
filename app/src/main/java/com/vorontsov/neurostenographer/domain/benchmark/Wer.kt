package com.vorontsov.neurostenographer.domain.benchmark

/**
 * Word Error Rate for Russian speech recognition evaluation.
 *
 * Normalization follows common Russian ASR evaluation practice:
 *  - lowercase;
 *  - `ё` folds to `е` (recognizers and reference transcripts do not
 *    distinguish them consistently);
 *  - punctuation and other non-letter/non-digit characters are removed;
 *  - whitespace is collapsed.
 *
 * WER is the word-level Levenshtein distance divided by the number of
 * reference words: (S + D + I) / N. An empty reference yields [Float.NaN]
 * because the metric is undefined there; callers must handle it explicitly
 * instead of silently treating it as zero error.
 */
object Wer {

    data class WerResult(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWords: Int,
    ) {
        val errors: Int get() = substitutions + deletions + insertions

        /** Word error rate in [0, +inf); [Float.NaN] when the reference is empty. */
        val wer: Float
            get() = if (referenceWords == 0) Float.NaN else errors.toFloat() / referenceWords
    }

    /** Normalizes Russian text into a word list for fair WER comparison. */
    fun normalize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (char in text.lowercase()) {
            val folded = if (char == 'ё') 'е' else char
            cleaned.append(if (folded.isLetterOrDigit()) folded else ' ')
        }
        return cleaned.split(' ').filter { it.isNotEmpty() }
    }

    /** Computes WER between a reference and a hypothesis transcript. */
    fun compute(reference: String, hypothesis: String): WerResult =
        compute(normalize(reference), normalize(hypothesis))

    /** Computes WER between already normalized word sequences. */
    fun compute(reference: List<String>, hypothesis: List<String>): WerResult {
        if (reference.isEmpty()) {
            return WerResult(
                substitutions = 0,
                deletions = 0,
                insertions = hypothesis.size,
                referenceWords = 0,
            )
        }
        // Classic edit-distance DP followed by operation backtracking.
        val rows = reference.size + 1
        val columns = hypothesis.size + 1
        val distance = IntArray(rows * columns)
        fun at(row: Int, column: Int) = distance[row * columns + column]
        fun set(row: Int, column: Int, value: Int) {
            distance[row * columns + column] = value
        }
        for (row in 0 until rows) set(row, 0, row)
        for (column in 0 until columns) set(0, column, column)
        for (row in 1 until rows) {
            for (column in 1 until columns) {
                val cost = if (reference[row - 1] == hypothesis[column - 1]) 0 else 1
                set(
                    row,
                    column,
                    minOf(
                        at(row - 1, column - 1) + cost, // match / substitution
                        at(row - 1, column) + 1,        // deletion
                        at(row, column - 1) + 1,        // insertion
                    ),
                )
            }
        }
        // Backtrack to classify operations. Ties prefer match/substitution so
        // the counts stay deterministic.
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        var row = reference.size
        var column = hypothesis.size
        while (row > 0 || column > 0) {
            when {
                row > 0 && column > 0 &&
                    at(row, column) == at(row - 1, column - 1) +
                    (if (reference[row - 1] == hypothesis[column - 1]) 0 else 1) -> {
                    if (reference[row - 1] != hypothesis[column - 1]) substitutions++
                    row--
                    column--
                }
                row > 0 && at(row, column) == at(row - 1, column) + 1 -> {
                    deletions++
                    row--
                }
                else -> {
                    insertions++
                    column--
                }
            }
        }
        return WerResult(
            substitutions = substitutions,
            deletions = deletions,
            insertions = insertions,
            referenceWords = reference.size,
        )
    }
}
