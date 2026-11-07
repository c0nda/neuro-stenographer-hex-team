package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.SampleRange
import kotlin.math.roundToLong

/**
 * Turns the per-frame speech probabilities produced by a VAD model into
 * completed speech regions. Pure state machine — no ML, no Android — so it is
 * fully unit-testable.
 *
 * Hysteresis: a region opens when the probability reaches [thresholdOn] and
 * stays open until the probability has stayed below [thresholdOff] for
 * [minSilenceMs]. This prevents a single low frame in the middle of a word
 * from splitting an utterance. Regions shorter than [minSpeechMs] are
 * discarded as clicks/noise.
 *
 * Frame timeline: frame i covers samples [i * frameSamples, (i+1) * frameSamples).
 */
class VadRegionTracker(
    private val sampleRate: Int = SAMPLE_RATE,
    private val frameSamples: Int = FRAME_SAMPLES,
    private val thresholdOn: Float = THRESHOLD_ON,
    private val thresholdOff: Float = THRESHOLD_OFF,
    private val minSilenceMs: Long = MIN_SILENCE_MS,
    private val minSpeechMs: Long = MIN_SPEECH_MS,
) {

    private enum class State { IDLE, SPEAKING }

    private var state = State.IDLE
    private var regionStartSample = 0L
    private var lastSpeechEndSample = 0L
    private var processedFrames = 0L
    private val completed = mutableListOf<SampleRange>()

    private val minSilenceSamples: Long
        get() = msToSamples(minSilenceMs)

    private val minSpeechSamples: Long
        get() = msToSamples(minSpeechMs)

    /** Feeds one frame probability (0..1) into the state machine. */
    fun accept(probability: Float) {
        val frameStart = processedFrames * frameSamples
        val frameEnd = frameStart + frameSamples
        when (state) {
            State.IDLE -> {
                if (probability >= thresholdOn) {
                    state = State.SPEAKING
                    regionStartSample = frameStart
                    lastSpeechEndSample = frameEnd
                }
            }

            State.SPEAKING -> {
                if (probability >= thresholdOff) {
                    lastSpeechEndSample = frameEnd
                } else if (frameStart - lastSpeechEndSample >= minSilenceSamples) {
                    closeRegion()
                }
                // Otherwise we are inside the silence hangover: the region
                // stays open and a returning voice extends it again.
            }
        }
        processedFrames++
    }

    /**
     * Returns regions that have already been closed (min silence reached),
     * clearing them from the internal buffer. An still-open region is NOT
     * returned and keeps accumulating — this is what makes streaming
     * operation possible. Idempotent: regions are returned once.
     */
    fun drainCompleted(): List<SampleRange> {
        val result = completed.toList()
        completed.clear()
        return result
    }

    /**
     * Ends the stream: closes a trailing open region (if long enough) and
     * returns all regions detected since [reset], clearing the internal
     * buffer. Calling [flush] twice never returns the same region twice.
     */
    fun flush(): List<SampleRange> {
        if (state == State.SPEAKING) closeRegion()
        return drainCompleted()
    }

    fun reset() {
        state = State.IDLE
        regionStartSample = 0L
        lastSpeechEndSample = 0L
        processedFrames = 0L
        completed.clear()
    }

    private fun closeRegion() {
        val region = SampleRange(regionStartSample, lastSpeechEndSample)
        if (region.sampleCount >= minSpeechSamples) {
            completed += region
        }
        state = State.IDLE
    }

    private fun msToSamples(ms: Long): Long =
        (ms * sampleRate / 1000.0).roundToLong()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
        const val THRESHOLD_ON = 0.5f
        const val THRESHOLD_OFF = 0.35f
        const val MIN_SILENCE_MS = 300L
        const val MIN_SPEECH_MS = 250L
    }
}
