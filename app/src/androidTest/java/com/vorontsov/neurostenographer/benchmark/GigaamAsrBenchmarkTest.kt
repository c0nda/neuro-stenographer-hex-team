package com.vorontsov.neurostenographer.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vorontsov.neurostenographer.data.audio.WavFileReader
import com.vorontsov.neurostenographer.domain.benchmark.TimestampError
import com.vorontsov.neurostenographer.domain.benchmark.Wer
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamV3CtcEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device benchmark for the GigaAM v3 CTC recognizer.
 *
 * Measures REAL numbers only: model load time, per-clip latency, RTF, process
 * PSS/heap deltas, and WER when a reference transcript is provided. Nothing is
 * estimated or fabricated; every figure in the report comes from a measurement
 * taken during the run.
 *
 * Preparation (one-time, from a developer machine):
 * ```
 * adb push v3_ctc.int8.onnx \
 *   /sdcard/Android/data/com.vorontsov.neurostenographer/files/models/gigaam-v3/
 * adb push clips/ \
 *   /sdcard/Android/data/com.vorontsov.neurostenographer/files/benchmark/
 * ```
 * Each clip must be a 16 kHz mono PCM16 WAV. An optional UTF-8 `.txt` file with
 * the same base name provides the reference transcript for WER.
 *
 * Run:
 * ```
 * gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * com.vorontsov.neurostenographer.benchmark.GigaamAsrBenchmarkTest
 * ```
 *
 * The report is written to `files/benchmark/results/` and echoed to logcat
 * under the [TAG]. When the model or clips are absent the test SKIPS via
 * JUnit assumptions instead of failing, so an unprepared device stays green.
 */
@RunWith(AndroidJUnit4::class)
class GigaamAsrBenchmarkTest {

    private data class ClipResult(
        val file: String,
        val durationMs: Long,
        val inferenceMs: Long,
        val rtf: Float,
        val words: Int,
        val pssDeltaKb: Int,
        val wer: Float?,
        val werErrors: Int?,
        val referenceWords: Int?,
        val timestampError: TimestampError.TimestampErrorReport? = null,
    )

    @Test
    fun gigaamCtcBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "models/gigaam-v3/v3_ctc.int8.onnx")
        assumeTrue(
            "GigaAM model not provisioned; see class KDoc for adb push instructions",
            modelFile.isFile,
        )
        val clipsDir = File(context.filesDir, "benchmark")
        val clips = clipsDir.listFiles { file -> file.extension.lowercase() == "wav" }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue(
            "No benchmark clips in ${clipsDir.absolutePath}; see class KDoc",
            clips.isNotEmpty(),
        )

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        Log.i(TAG, "Loading GigaAM v3 CTC ($threads threads) from ${modelFile.absolutePath}")

        val loadStart = System.nanoTime()
        val engine = GigaamV3CtcEngine.load(modelFile, threads)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Log.i(TAG, "Model loaded in ${loadMs} ms")

        val results = mutableListOf<ClipResult>()
        try {
            for (clip in clips) {
                val samples = readPcm16(clip)
                val durationMs = samples.size.toLong() * 1000L / SAMPLE_RATE
                val pssBefore = pssKb(context)
                val heapBefore = heapUsedBytes()

                val start = System.nanoTime()
                val words = engine.transcribe(samples, SAMPLE_RATE)
                val inferenceMs = (System.nanoTime() - start) / 1_000_000

                val pssAfter = pssKb(context)
                val heapAfter = heapUsedBytes()
                val rtf = if (durationMs > 0) inferenceMs.toFloat() / durationMs else 0f

                val reference = File(clipsDir, clip.nameWithoutExtension + ".txt")
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                val wer = reference?.let {
                    Wer.compute(it, words.joinToString(" ") { word -> word.text })
                }

                // Optional labeled word boundaries: <clip>.words.txt with lines
                // "word<TAB>startMs<TAB>endMs". Feeds the Phase 5 decision gate
                // for the forced-alignment question.
                val wordBoundaries = File(clipsDir, clip.nameWithoutExtension + ".words.txt")
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    ?.let { TimestampError.parseReferences(it) }
                val timestampError = wordBoundaries?.let { references ->
                    TimestampError.compute(
                        references,
                        words.map { word ->
                            WordTimestamp(
                                text = word.text,
                                startMs = word.startMs,
                                endMs = word.endMs,
                                confidence = word.confidence,
                                alignmentSource = AlignmentSource.CTC_FRAME,
                            )
                        },
                    )
                }

                results += ClipResult(
                    file = clip.name,
                    durationMs = durationMs,
                    inferenceMs = inferenceMs,
                    rtf = rtf,
                    words = words.size,
                    pssDeltaKb = pssAfter - pssBefore,
                    wer = wer?.wer?.takeUnless { it.isNaN() },
                    werErrors = wer?.errors,
                    referenceWords = wer?.referenceWords,
                    timestampError = timestampError,
                )
                Log.i(
                    TAG,
                    "${clip.name}: ${durationMs} ms audio, $inferenceMs ms inference, " +
                        "RTF %.3f".format(Locale.US, rtf) +
                        ", ${words.size} words, PSS delta ${pssAfter - pssBefore} kB" +
                        (wer?.let { ", WER %.3f".format(Locale.US, it.wer) } ?: ""),
                )
                // Heap delta is logged for completeness; PSS is the primary signal.
                Log.d(TAG, "heap delta ${heapAfter - heapBefore} bytes")
            }
        } finally {
            engine.close()
        }

        val report = buildReport(context, threads, loadMs, results)
        val reportDir = File(clipsDir, "results").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val reportFile = File(reportDir, "benchmark-$stamp.json")
        reportFile.writeText(report, Charsets.UTF_8)
        Log.i(TAG, "Benchmark report written to ${reportFile.absolutePath}")
        Log.i(TAG, report)

        // Sanity guard: a benchmark that produced no measurements is a failure,
        // not a success with an empty report.
        assertTrue("No clips were measured", results.isNotEmpty())
        assertTrue(
            "Every RTF must be positive",
            results.all { it.rtf > 0f },
        )
    }

    private fun readPcm16(file: File): FloatArray {
        val info = WavFileReader.readInfo(file)
            ?: throw IllegalArgumentException("${file.name} is not a PCM WAV file")
        require(info.isTargetFormat) {
            "${file.name} must be 16 kHz mono PCM16, got " +
                "${info.sampleRate} Hz, ${info.channels} ch, ${info.bitsPerSample} bit"
        }
        val sampleCount = (info.dataLength / 2).toInt()
        val samples = FloatArray(sampleCount)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val buffer = ByteArray(BUFFER_SAMPLES * 2)
            var index = 0
            while (index < sampleCount) {
                val toRead = minOf(buffer.size, (sampleCount - index) * 2)
                val read = raf.read(buffer, 0, toRead)
                if (read < 0) break
                var byteIndex = 0
                while (byteIndex + 1 < read && index < sampleCount) {
                    val sample = (buffer[byteIndex].toInt() and 0xFF) or
                        (buffer[byteIndex + 1].toInt() shl 8)
                    samples[index] = (sample.toShort()) / 32768f
                    index++
                    byteIndex += 2
                }
            }
            if (index < sampleCount) return samples.copyOf(index)
        }
        return samples
    }

    private fun pssKb(context: Context): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = manager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        return info.firstOrNull()?.totalPss ?: 0
    }

    private fun heapUsedBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun buildReport(
        context: Context,
        threads: Int,
        loadMs: Long,
        results: List<ClipResult>,
    ): String {
        val totalAudioMs = results.sumOf { it.durationMs }
        val totalInferenceMs = results.sumOf { it.inferenceMs }
        val overallRtf = if (totalAudioMs > 0) {
            totalInferenceMs.toFloat() / totalAudioMs
        } else {
            0f
        }
        val scored = results.filter { it.wer != null }
        val overallWer = if (scored.isNotEmpty()) {
            val errors = scored.sumOf { it.werErrors ?: 0 }
            val words = scored.sumOf { it.referenceWords ?: 0 }
            if (words > 0) errors.toFloat() / words else null
        } else {
            null
        }

        return buildString {
            append("{\n")
            append("  \"device\": \"").append(escape(android.os.Build.MODEL)).append("\",\n")
            append("  \"api_level\": ").append(android.os.Build.VERSION.SDK_INT).append(",\n")
            append("  \"abi\": \"").append(escape(android.os.Build.SUPPORTED_ABIS.first())).append("\",\n")
            append("  \"threads\": ").append(threads).append(",\n")
            append("  \"model_load_ms\": ").append(loadMs).append(",\n")
            append("  \"total_audio_ms\": ").append(totalAudioMs).append(",\n")
            append("  \"total_inference_ms\": ").append(totalInferenceMs).append(",\n")
            append("  \"overall_rtf\": ").append(formatFloat(overallRtf)).append(",\n")
            append("  \"overall_wer\": ")
                .append(overallWer?.let { formatFloat(it) } ?: "null").append(",\n")
            append("  \"clips\": [\n")
            results.forEachIndexed { index, clip ->
                append("    {\n")
                append("      \"file\": \"").append(escape(clip.file)).append("\",\n")
                append("      \"duration_ms\": ").append(clip.durationMs).append(",\n")
                append("      \"inference_ms\": ").append(clip.inferenceMs).append(",\n")
                append("      \"rtf\": ").append(formatFloat(clip.rtf)).append(",\n")
                append("      \"words\": ").append(clip.words).append(",\n")
                append("      \"pss_delta_kb\": ").append(clip.pssDeltaKb).append(",\n")
                append("      \"wer\": ")
                    .append(clip.wer?.let { formatFloat(it) } ?: "null").append(",\n")
                val error = clip.timestampError
                if (error != null) {
                    append("      \"word_timestamp_error\": {\n")
                    append("        \"matched_words\": ").append(error.matchedWords).append(",\n")
                    append("        \"median_start_error_ms\": ")
                        .append(error.medianStartErrorMs?.let { formatFloat(it.toFloat()) } ?: "null")
                        .append(",\n")
                    append("        \"p90_start_error_ms\": ")
                        .append(error.p90StartErrorMs?.let { formatFloat(it.toFloat()) } ?: "null")
                        .append(",\n")
                    append("        \"within_40ms\": ")
                        .append(formatFloat(error.withinToleranceFraction(40).toFloat())).append(",\n")
                    append("        \"within_80ms\": ")
                        .append(formatFloat(error.withinToleranceFraction(80).toFloat())).append(",\n")
                    append("        \"within_120ms\": ")
                        .append(formatFloat(error.withinToleranceFraction(120).toFloat())).append("\n")
                    append("      }\n")
                } else {
                    append("      \"word_timestamp_error\": null\n")
                }
                append("    }").append(if (index < results.lastIndex) ",\n" else "\n")
            }
            append("  ]\n")
            append("}\n")
        }
    }

    private fun formatFloat(value: Float): String =
        String.format(Locale.US, "%.4f", value)

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(char)
            }
        }
    }

    private companion object {
        const val TAG = "GigaamBenchmark"
        const val SAMPLE_RATE = 16_000
        const val BUFFER_SAMPLES = 8192
    }
}
