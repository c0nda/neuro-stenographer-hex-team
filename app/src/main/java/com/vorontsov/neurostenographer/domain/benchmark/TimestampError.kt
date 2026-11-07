package com.vorontsov.neurostenographer.domain.benchmark

import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import kotlin.math.abs

/**
 * Word-boundary timestamp error against a labeled reference.
 *
 * References come from forced-alignment ground truth (e.g. a hand-checked
 * `.words.txt` next to a benchmark clip). Predicted boundaries are the
 * model-derived word timings (CTC frame, RNNT emission, or a future forced
 * aligner — see [com.vorontsov.neurostenographer.domain.model.AlignmentSource]).
 *
 * Matching is greedy in order of appearance over the SAME normalization as
 * WER (lowercase, `ё→е`, punctuation stripped), so a recognition error does
 * not poison the timing statistics of the following words.
 *
 * Everything in the report is measured; nothing is estimated or fabricated.
 */
object TimestampError {

    /** One labeled word boundary from the reference annotation. */
    data class WordBoundaryReference(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    data class TimestampErrorReport(
        val matchedWords: Int,
        val unmatchedReferenceWords: Int,
        val unmatchedPredictedWords: Int,
        val medianStartErrorMs: Double?,
        val meanStartErrorMs: Double?,
        val p90StartErrorMs: Double?,
        val medianEndErrorMs: Double?,
        val meanEndErrorMs: Double?,
        val p90EndErrorMs: Double?,
        /** Fraction of matched words whose start error is within [toleranceMs]. */
        val withinToleranceFraction: (Long) -> Double,
    )

    /**
     * Parses the reference format used by the benchmark harness: one word per
     * line, `word<TAB>startMs<TAB>endMs`. Blank lines and `#` comments are
     * ignored. Throws on a malformed line rather than silently skipping it.
     */
    fun parseReferences(content: String): List<WordBoundaryReference> =
        content.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            require(parts.size == 3) { "Malformed reference line: $line" }
            WordBoundaryReference(
                text = parts[0],
                startMs = parts[1].toLong(),
                endMs = parts[2].toLong(),
            )
        }

    fun compute(
        references: List<WordBoundaryReference>,
        predicted: List<WordTimestamp>,
    ): TimestampErrorReport {
        val normalizedRefs = references.map { Wer.normalize(it.text) to it }
        val normalizedPred = predicted.map { Wer.normalize(it.text) to it }

        // Greedy in-order matching on normalized text.
        val startErrors = mutableListOf<Double>()
        val endErrors = mutableListOf<Double>()
        var referenceIndex = 0
        var predictedIndex = 0
        var skippedReferences = 0
        var skippedPredictions = 0
        while (referenceIndex < normalizedRefs.size && predictedIndex < normalizedPred.size) {
            if (normalizedRefs[referenceIndex].first == normalizedPred[predictedIndex].first) {
                val ref = normalizedRefs[referenceIndex].second
                val pred = normalizedPred[predictedIndex].second
                startErrors += abs(pred.startMs - ref.startMs).toDouble()
                endErrors += abs(pred.endMs - ref.endMs).toDouble()
                referenceIndex++
                predictedIndex++
            } else {
                // Recognition mismatch: skip the side whose identical word
                // appears later in the other list (classic WER-style walk).
                val refHere = normalizedRefs[referenceIndex].first
                val predHere = normalizedPred[predictedIndex].first
                val nextRefInPred = normalizedPred.subList(predictedIndex, normalizedPred.size)
                    .indexOfFirst { it.first == refHere }
                val nextPredInRef = normalizedRefs.subList(referenceIndex, normalizedRefs.size)
                    .indexOfFirst { it.first == predHere }
                when {
                    nextRefInPred < 0 && nextPredInRef < 0 -> {
                        referenceIndex++
                        predictedIndex++
                        skippedReferences++
                        skippedPredictions++
                    }

                    nextRefInPred < 0 || (nextPredInRef >= 0 && nextPredInRef <= nextRefInPred) -> {
                        referenceIndex++
                        skippedReferences++
                    }

                    else -> {
                        predictedIndex++
                        skippedPredictions++
                    }
                }
            }
        }

        skippedPredictions += normalizedPred.size - predictedIndex
        skippedReferences += normalizedRefs.size - referenceIndex
        val unmatchedReference = skippedReferences
        val unmatchedPredicted = skippedPredictions

        val tolerances = longArrayOf(40, 80, 120)
        val fractions = tolerances.associateWith { tolerance ->
            fractionWithin(startErrors, tolerance)
        }

        return TimestampErrorReport(
            matchedWords = startErrors.size,
            unmatchedReferenceWords = unmatchedReference,
            unmatchedPredictedWords = unmatchedPredicted,
            medianStartErrorMs = startErrors.median(),
            meanStartErrorMs = startErrors.averageOrNull(),
            p90StartErrorMs = startErrors.percentile(90.0),
            medianEndErrorMs = endErrors.median(),
            meanEndErrorMs = endErrors.averageOrNull(),
            p90EndErrorMs = endErrors.percentile(90.0),
            withinToleranceFraction = { tolerance ->
                fractions[tolerance] ?: fractionWithin(startErrors, tolerance)
            },
        )
    }

    private fun fractionWithin(errors: List<Double>, toleranceMs: Long): Double =
        if (errors.isEmpty()) 0.0
        else errors.count { it <= toleranceMs.toDouble() }.toDouble() / errors.size

    private fun List<Double>.median(): Double? =
        if (isEmpty()) null else sorted().let { sorted ->
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]) / 2.0
        }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun List<Double>.percentile(percentile: Double): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val rank = (percentile / 100.0) * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val fraction = rank - low
        return sorted[low] * (1 - fraction) + sorted[high] * fraction
    }
}
