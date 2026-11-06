package com.vorontsov.neurostenographer.ml.asr.gigaam

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Feature matrix produced by the GigaAM v3 frontend.
 *
 * [data] is laid out for the ONNX input `features` with shape [1, 64, frames]:
 * index = melBin * frames + frame.
 */
data class GigaamFeatures(
    val frames: Int,
    val data: FloatArray,
)

/**
 * Exact Kotlin port of the GigaAM v3 log-mel frontend.
 *
 * Reference: `onnx-asr` `preprocessors/gigaam.py`, `preprocessors/fbanks.py` and
 * `onnx_asr/preprocessors/numpy_preprocessor.py` (GigaamPreprocessorNumpy).
 *
 * Parameters verified from `v3_ctc.yaml`:
 *  - 16 kHz mono input
 *  - 64 mel bins, HTK mel scale, no mel normalization
 *  - n_fft = win_length = 320, hop_length = 160, center = false
 *  - f_min = 0, f_max = 8000
 *  - periodic Hann window (`np.hanning(321)[:-1]`)
 *  - filterbank and window are rounded to bfloat16, then widened to float32
 *  - power spectrum = |rfft|^2, then log(clamp(mel, 1e-9, 1e9))
 *
 * No padding is applied, matching the reference: for N samples the frame count
 * is (N - 320) / 160 + 1.
 */
class GigaamV3FeatureExtractor {

    private val fft = FloatFFT_1D(N_FFT.toLong())
    private val window = WINDOW
    private val melBank = MEL_BANK

    fun frameCount(sampleCount: Int): Int =
        if (sampleCount < N_FFT) 0 else (sampleCount - N_FFT) / HOP + 1

    fun extract(samples: FloatArray): GigaamFeatures {
        val frames = frameCount(samples.size)
        if (frames == 0) return GigaamFeatures(0, FloatArray(0))

        val output = FloatArray(MELS * frames)
        val frame = FloatArray(N_FFT)
        val power = DoubleArray(BINS)

        for (frameIndex in 0 until frames) {
            val offset = frameIndex * HOP
            for (i in 0 until N_FFT) {
                frame[i] = samples[offset + i] * window[i]
            }
            fft.realForward(frame)

            // JTransforms packed layout for even n:
            // a[0] = Re(0), a[1] = Re(n/2), a[2k] = Re(k), a[2k+1] = Im(k).
            power[0] = frame[0].toDouble() * frame[0]
            power[BINS - 1] = frame[1].toDouble() * frame[1]
            for (bin in 1 until BINS - 1) {
                val real = frame[bin * 2]
                val imaginary = frame[bin * 2 + 1]
                val magnitude = sqrt(real.toDouble() * real + imaginary.toDouble() * imaginary).toFloat()
                power[bin] = magnitude.toDouble() * magnitude
            }

            for (mel in 0 until MELS) {
                var energy = 0.0
                for (bin in 0 until BINS) {
                    val weight = melBank[bin * MELS + mel]
                    if (weight != 0f) energy += power[bin] * weight
                }
                val clamped = energy.coerceIn(CLAMP_MIN, CLAMP_MAX)
                output[mel * frames + frameIndex] = ln(clamped).toFloat()
            }
        }
        return GigaamFeatures(frames, output)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 320
        const val WIN_LENGTH = 320
        const val HOP = 160
        const val MELS = 64
        const val BINS = N_FFT / 2 + 1
        const val CLAMP_MIN = 1e-9
        const val CLAMP_MAX = 1e9

        /**
         * Filterbank in [bin][mel] order, mirroring `melscale_fbanks(161, 0,
         * 8000, 64, 16000, norm=null, mel_scale="htk")` followed by a bfloat16
         * round trip.
         */
        val MEL_BANK: FloatArray by lazy {
            val bank = buildMelFilterBank()
            FloatArray(bank.size) { index -> bfloat16(bank[index].toDouble()) }
        }

        /** Periodic Hann window, bfloat16-rounded exactly like the reference. */
        val WINDOW: FloatArray by lazy {
            FloatArray(WIN_LENGTH) { index ->
                val value = 0.5 - 0.5 * cos(2.0 * Math.PI * index / WIN_LENGTH)
                bfloat16(value)
            }
        }

        private fun buildMelFilterBank(): FloatArray {
            val frequencies = DoubleArray(BINS) { index ->
                index.toDouble() * (SAMPLE_RATE / 2.0) / (BINS - 1)
            }
            val melMin = hzToMel(0.0)
            val melMax = hzToMel(SAMPLE_RATE / 2.0)
            val points = DoubleArray(MELS + 2) { index ->
                melToHz(melMin + (melMax - melMin) * index / (MELS + 1))
            }
            val bank = FloatArray(BINS * MELS)
            for (bin in 0 until BINS) {
                val frequency = frequencies[bin]
                for (mel in 0 until MELS) {
                    val up = (frequency - points[mel]) / (points[mel + 1] - points[mel])
                    val down = (points[mel + 2] - frequency) / (points[mel + 2] - points[mel + 1])
                    val weight = minOf(up, down).coerceAtLeast(0.0)
                    bank[bin * MELS + mel] = weight.toFloat()
                }
            }
            return bank
        }

        private fun hzToMel(frequency: Double): Double =
            2595.0 * log10(1.0 + frequency / 700.0)

        private fun melToHz(mel: Double): Double =
            700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        /**
         * Round-to-nearest-even conversion to bfloat16 precision, then back to
         * float32. This matches `np.float32(x).astype(ml_dtypes.bfloat16)
         * .astype(np.float32)` used to build the GigaAM v3 window and bank.
         */
        fun bfloat16(value: Double): Float {
            val bits = java.lang.Float.floatToRawIntBits(value.toFloat())
            val leastSignificant = (bits ushr 16) and 1
            val rounded = bits + 0x7FFF + leastSignificant
            return java.lang.Float.intBitsToFloat(rounded and 0xFFFF0000.toInt())
        }
    }
}
