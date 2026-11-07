package com.vorontsov.neurostenographer.ml.asr.gigaam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM-only frontend throughput check.
 *
 * This is not the end-to-end ASR real-time factor: it measures only feature
 * extraction (windowing, FFT, mel projection, log). The ONNX session runs on a
 * device via `onnxruntime-android` and is measured separately on hardware.
 *
 * The bound is deliberately loose so the test fails only on pathological
 * regressions, not on a slow CI machine. Measured values are printed so a
 * benchmark run can record them.
 */
class GigaamV3FrontendBenchmarkTest {

    @Test
    fun `frontend processes ten seconds of audio faster than real time`() {
        val sampleRate = GigaamV3FeatureExtractor.SAMPLE_RATE
        val seconds = 10
        val samples = FloatArray(sampleRate * seconds) { index ->
            val time = index.toDouble() / sampleRate
            val envelope = 0.5 + 0.5 * sin(2 * PI * 0.7 * time)
            (
                envelope * (
                    0.4 * sin(2 * PI * 140 * time) +
                        0.2 * sin(2 * PI * 620 * time) +
                        0.1 * sin(2 * PI * 2_400 * time)
                    )
                ).toFloat()
        }
        val extractor = GigaamV3FeatureExtractor()

        // Warm up JIT and the lazily built filterbank.
        extractor.extract(FloatArray(sampleRate))

        val runs = 3
        var bestNanos = Long.MAX_VALUE
        var frames = 0
        repeat(runs) {
            val started = System.nanoTime()
            val features = extractor.extract(samples)
            val elapsed = System.nanoTime() - started
            frames = features.frames
            if (elapsed < bestNanos) bestNanos = elapsed
        }

        val audioNanos = seconds * 1_000_000_000L
        val rtf = bestNanos.toDouble() / audioNanos
        println(
            "GigaAM v3 frontend: ${frames} frames, " +
                "best ${bestNanos / 1_000_000} ms for ${seconds}s audio, " +
                "RTF=${String.format(Locale.US, "%.4f", rtf)}",
        )

        assertEquals(999, frames)
        assertTrue("frontend RTF was $rtf, expected < 1.0", rtf < 1.0)
    }
}
