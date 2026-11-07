package com.vorontsov.neurostenographer.ml.asr.gigaam

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * On-device benchmark harness for the real GigaAM v3 INT8 model. It is skipped
 * unless the verified model is already present in app-private storage, so a
 * normal instrumentation run does not need a 215 MB binary.
 *
 * Provision it once (the app's own SAF import is the supported path, the adb
 * command below is only for automated testing):
 *
 *   adb shell run-as com.vorontsov.neurostenographer mkdir -p files/models/gigaam-v3
 *   adb push v3_ctc.int8.onnx /data/local/tmp/v3_ctc.int8.onnx
 *   adb shell run-as com.vorontsov.neurostenographer cp \
 *       /data/local/tmp/v3_ctc.int8.onnx files/models/gigaam-v3/v3_ctc.int8.onnx
 *   adb shell run-as com.vorontsov.neurostenographer ls -l files/models/gigaam-v3
 *
 * Then:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamOnDeviceBenchmarkTest
 *
 * The test uses synthetic audio, so it measures latency, RTF and memory, not WER.
 * WER/DER need a labelled Russian corpus and are not measured here.
 */
@RunWith(AndroidJUnit4::class)
class GigaamOnDeviceBenchmarkTest {

    @Test
    fun measureModelLatencyRtfAndMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "models/gigaam-v3/v3_ctc.int8.onnx")
        assumeTrue(
            "Push the verified v3_ctc.int8.onnx to ${modelFile.absolutePath} first",
            modelFile.isFile && modelFile.length() == EXPECTED_SIZE,
        )

        val runtime = Runtime.getRuntime()
        val baselinePssKb = pssKb()

        val loadStarted = System.nanoTime()
        val engine = GigaamV3CtcEngine.load(
            modelFile = modelFile,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        )
        val loadMs = (System.nanoTime() - loadStarted) / 1_000_000

        val sampleRate = GigaamV3FeatureExtractor.SAMPLE_RATE
        val seconds = 15
        val samples = FloatArray(sampleRate * seconds) { index ->
            val time = index.toDouble() / sampleRate
            val envelope = 0.4 + 0.4 * sin(2 * PI * 0.8 * time)
            (
                envelope * (
                    0.35 * sin(2 * PI * 150 * time) +
                        0.18 * sin(2 * PI * 640 * time) +
                        0.08 * sin(2 * PI * 2_100 * time)
                    )
                ).toFloat()
        }

        // First call includes session warm-up; the second is the steady-state figure.
        engine.use {
            it.transcribe(samples, sampleRate)
            val started = System.nanoTime()
            val words = it.transcribe(samples, sampleRate)
            val inferenceMs = (System.nanoTime() - started) / 1_000_000
            val rtf = inferenceMs.toDouble() / (seconds * 1000.0)
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val pssMb = (pssKb() - baselinePssKb) / 1024

            println(
                "GigaAM v3 on-device: load=${loadMs}ms, inference=${inferenceMs}ms for " +
                    "${seconds}s audio, RTF=${String.format(Locale.US, "%.3f", rtf)}, " +
                    "heapUsed=${usedMb}MB, deltaPss=${pssMb}MB, words=${words.size}",
            )
            assertTrue("RTF must be positive", rtf > 0.0)
        }
    }

    private fun pssKb(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong()
    }

    private companion object {
        const val EXPECTED_SIZE = 224_721_181L
    }
}
