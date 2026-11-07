package com.vorontsov.neurostenographer.ml.asr.gigaam

import com.vorontsov.neurostenographer.domain.asr.AsrSegmenter
import com.vorontsov.neurostenographer.domain.benchmark.Wer
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Desktop verification harness for the production GigaAM v3 CTC engine.
 *
 * Runs the exact code that ships in the app (GigaamV3CtcEngine +
 * GigaamV3FeatureExtractor + GigaamCtcDecoder + GigaamWordBuilder) on the
 * JVM against the desktop ONNX Runtime, transcribes a real Russian clip and
 * prints the WER against a reference transcript. It answers the question
 * "is the model/frontend/decoder chain itself good?" independent of Android
 * devices, microphones and recording conditions.
 *
 * Not a CI test: it is assumption-gated and only runs when the three system
 * properties are provided:
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*GigaamDesktopWerTest*" \
 *   -Dgigaam.model=C:/models/v3_ctc.int8.onnx \
 *   -Dgigaam.clip=C:/models/eval/clip.wav \
 *   -Dgigaam.reference="эталонный текст клипа"
 * ```
 */
class GigaamDesktopWerTest {

    @Test
    fun transcribeClipAndReportWer() {
        val modelPath = System.getProperty("gigaam.model").orEmpty()
        val clipPath = System.getProperty("gigaam.clip").orEmpty()
        val reference = System.getProperty("gigaam.reference").orEmpty()
        assumeTrue("gigaam.model not set", modelPath.isNotEmpty() && File(modelPath).isFile)
        assumeTrue("gigaam.clip not set", clipPath.isNotEmpty() && File(clipPath).isFile)
        assumeTrue("gigaam.reference not set", reference.isNotEmpty())

        val clip = File(clipPath)
        val samples = readPcm16(clip)
        println("[desktop-wer] clip=${clip.name} samples=${samples.size} " +
            "duration=${samples.size / 16.0 / 1000.0}s")

        val loadStart = System.nanoTime()
        GigaamV3CtcEngine.load(File(modelPath), threads = 4).use { engine ->
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            val start = System.nanoTime()
            val words = engine.transcribe(samples, 16_000)
            val inferMs = (System.nanoTime() - start) / 1_000_000

            val text = words.joinToString(" ") { it.text }
            val utterances = AsrSegmenter.segment(words)
            val result = Wer.compute(reference, text)

            println("[desktop-wer] model_load_ms=$loadMs inference_ms=$inferMs " +
                "rtf=${(inferMs / 1000.0) / (samples.size / 16000.0)}")
            println("[desktop-wer] words=${words.size} utterances=${utterances.size}")
            println("[desktop-wer] REFERENCE: $reference")
            println("[desktop-wer] HYPOTHESIS: $text")
            println("[desktop-wer] WER=${"%.3f".format(result.wer)} " +
                "(S=${result.substitutions} D=${result.deletions} I=${result.insertions} " +
                "N=${result.referenceWords})")
        }
    }

    private class WavFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val isFloat: Boolean,
        val dataOffset: Long,
        val dataLength: Long,
    )

    /** Minimal RIFF/WAVE header walk: accepts PCM16 and IEEE-float32. */
    private fun parseWav(file: File): WavFormat {
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { raf ->
            raf.readFully(header)
            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
            check(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "not WAVE" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var isFloat = false
            var dataOffset = -1L
            var dataLength = -1L
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = (chunkHeader[4].toInt() and 0xFF) or
                    ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                    ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                    ((chunkHeader[7].toInt() and 0xFF) shl 24)
                val payload = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(minOf(size, 40).toInt())
                        raf.readFully(fmt)
                        val format = (fmt[0].toInt() and 0xFF) or (fmt[1].toInt() shl 8)
                        isFloat = format == 3
                        check(format == 1 || format == 3) { "unsupported WAV format $format" }
                        channels = (fmt[2].toInt() and 0xFF) or (fmt[3].toInt() shl 8)
                        sampleRate = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                            ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
                        bits = (fmt[14].toInt() and 0xFF) or (fmt[15].toInt() shl 8)
                        raf.seek(payload + size + (size and 1))
                    }

                    "data" -> {
                        dataOffset = payload
                        dataLength = minOf(size.toLong(), raf.length() - payload)
                        raf.seek(payload + size + (size and 1))
                    }

                    else -> raf.seek(payload + size + (size and 1))
                }
                if (dataOffset >= 0 && sampleRate > 0) break
            }
            check(dataOffset >= 0 && sampleRate > 0) { "incomplete WAV header" }
            return WavFormat(sampleRate, channels, bits, isFloat, dataOffset, dataLength)
        }
    }

    /**
     * Reads any PCM16 or IEEE-float WAV (any sample rate, any channel count)
     * and converts it to 16 kHz mono float in [-1, 1] — linear resampling is
     * plenty for a speech-quality smoke measurement.
     */
    private fun readPcm16(file: File): FloatArray {
        val fmt = parseWav(file)
        val bytesPerSample = fmt.bitsPerSample / 8
        val frameCount = (fmt.dataLength / (bytesPerSample * fmt.channels)).toInt()
        val mono = FloatArray(frameCount)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(fmt.dataOffset)
            val frameBytes = ByteArray(bytesPerSample * fmt.channels)
            for (frame in 0 until frameCount) {
                raf.readFully(frameBytes)
                var mixed = 0.0
                for (ch in 0 until fmt.channels) {
                    mixed += when {
                        fmt.isFloat -> java.lang.Float.intBitsToFloat(
                            (frameBytes[ch * 4].toInt() and 0xFF) or
                                ((frameBytes[ch * 4 + 1].toInt() and 0xFF) shl 8) or
                                ((frameBytes[ch * 4 + 2].toInt() and 0xFF) shl 16) or
                                ((frameBytes[ch * 4 + 3].toInt() and 0xFF) shl 24),
                        ).toDouble()

                        else -> {
                            val raw = (frameBytes[ch * 2].toInt() and 0xFF) or
                                (frameBytes[ch * 2 + 1].toInt() shl 8)
                            raw.toShort() / 32768.0
                        }
                    }
                }
                mono[frame] = (mixed / fmt.channels).toFloat().coerceIn(-1f, 1f)
            }
        }
        return resampleTo16k(mono, fmt.sampleRate)
    }

    private fun resampleTo16k(input: FloatArray, sourceRate: Int): FloatArray {
        if (sourceRate == 16_000) return input
        val ratio = 16_000.0 / sourceRate
        val outLength = (input.size * ratio).toInt()
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val pos = i / ratio
            val left = pos.toInt()
            val right = minOf(left + 1, input.size - 1)
            val frac = (pos - left).toFloat()
            output[i] = input[left] * (1 - frac) + input[right] * frac
        }
        return output
    }
}
