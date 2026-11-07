package com.vorontsov.neurostenographer.ml.asr.gigaam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vorontsov.neurostenographer.data.asr.WavAudioSource
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device end-to-end recognition of a real Russian utterance.
 *
 * Skipped unless both artifacts are present in app-private storage:
 *
 *   files/models/gigaam-v3/v3_ctc.int8.onnx  (size-verified model)
 *   files/test/ru_sample.wav                 (16 kHz mono PCM16)
 *
 * Push them with `adb` using `run-as` (the app has no INTERNET permission and
 * no way to fetch a fixture):
 *
 *   adb push ru_sample.wav /data/local/tmp/ru_sample.wav
 *   adb shell run-as com.vorontsov.neurostenographer mkdir -p files/test
 *   adb shell run-as com.vorontsov.neurostenographer cp \
 *       /data/local/tmp/ru_sample.wav files/test/ru_sample.wav
 *
 * The spoken sentence is "Привет. Это проверка распознавания русской речи.
 * На улице сегодня холодно, но солнце светит ярко." This is a smoke test for
 * frontend + model + CTC reconstruction, not a WER measurement.
 */
@RunWith(AndroidJUnit4::class)
class GigaamOnDeviceRecognitionTest {

    @Test
    fun recognizesRussianSampleWithModelTimestamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "models/gigaam-v3/v3_ctc.int8.onnx")
        val audioFile = File(context.filesDir, "test/ru_sample.wav")
        assumeTrue(
            "Push the verified v3_ctc.int8.onnx to ${modelFile.absolutePath}",
            modelFile.isFile && modelFile.length() == EXPECTED_SIZE,
        )
        assumeTrue(
            "Push ru_sample.wav to ${audioFile.absolutePath}",
            audioFile.isFile,
        )

        val source = WavAudioSource.Factory().open(audioFile.absolutePath)
        val samples = source.use {
            it.read(0, it.totalSamples.toInt())
        }

        val words = GigaamV3CtcEngine.load(
            modelFile = modelFile,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        ).use { engine ->
            engine.transcribe(samples, source.sampleRate)
        }

        val text = words.joinToString(" ") { it.text }
        println(
            "GigaAM v3 recognition: ${samples.size} samples, ${words.size} words, " +
                "first=${words.firstOrNull()?.startMs}ms, last=${words.lastOrNull()?.endMs}ms",
        )
        println("GigaAM v3 text: $text")

        assertTrue("Expected recognized words, got none", words.isNotEmpty())
        assertTrue(
            "Expected the word 'привет' in '$text'",
            words.any { it.text == "привет" },
        )
        assertTrue(
            "Expected the word 'проверка' in '$text'",
            words.any { it.text == "проверка" },
        )
        assertTrue(
            "Word timestamps must be ordered and non-empty",
            words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } &&
                words.all { it.startMs < it.endMs },
        )
        val durationMs = source.totalSamples * 1000 / source.sampleRate
        assertTrue(
            "Timestamps must stay inside the recording ($durationMs ms): $text",
            words.all { it.startMs >= 0 && it.endMs <= durationMs + 1_000 },
        )
    }

    private companion object {
        const val EXPECTED_SIZE = 224_721_181L
    }
}
