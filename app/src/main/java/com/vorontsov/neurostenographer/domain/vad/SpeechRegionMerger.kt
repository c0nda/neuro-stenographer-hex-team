package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.SampleRange
import kotlin.math.roundToLong

/**
 * Post-processing of raw VAD regions before they become ASR input:
 *
 *  1. pad each side by [padMs] so word onsets clipped by the VAD threshold
 *     are recovered;
 *  2. merge regions separated by less than [minGapMs] of silence so mid-word
 *     pauses do not split one utterance into two ASR calls;
 *  3. clamp everything to [0, totalSamples);
 *  4. drop regions shorter than [minSpeechMs] (defensive; the tracker already
 *     filters, padding can only grow regions).
 *
 * Pure function over sample indices — timestamps never leave integer sample
 * space, so no drift is introduced here.
 */
object SpeechRegionMerger {

    fun merge(
        regions: List<SampleRange>,
        totalSamples: Long,
        sampleRate: Int,
        padMs: Long = PAD_MS,
        minGapMs: Long = MIN_GAP_MS,
        minSpeechMs: Long = MIN_SPEECH_MS,
    ): List<SampleRange> {
        if (totalSamples <= 0) return emptyList()
        val pad = msToSamples(padMs, sampleRate)
        val minGap = msToSamples(minGapMs, sampleRate)

        val padded = regions
            .filter { it.sampleCount > 0 }
            .sortedBy { it.startSample }
            .map { region ->
                SampleRange(
                    startSample = (region.startSample - pad).coerceAtLeast(0L),
                    endSampleExclusive = (region.endSampleExclusive + pad)
                        .coerceAtMost(totalSamples),
                )
            }
            .filter { it.startSample < it.endSampleExclusive }

        val merged = mutableListOf<SampleRange>()
        for (region in padded) {
            val previous = merged.lastOrNull()
            if (previous != null && region.startSample - previous.endSampleExclusive < minGap) {
                merged[merged.lastIndex] = SampleRange(
                    previous.startSample,
                    maxOf(previous.endSampleExclusive, region.endSampleExclusive),
                )
            } else {
                merged += region
            }
        }

        val minSpeech = msToSamples(minSpeechMs, sampleRate)
        return merged.filter { it.sampleCount >= minSpeech }
    }

    private fun msToSamples(ms: Long, sampleRate: Int): Long =
        (ms * sampleRate / 1000.0).roundToLong()

    private const val PAD_MS = 250L
    private const val MIN_GAP_MS = 300L
    private const val MIN_SPEECH_MS = 250L
}
