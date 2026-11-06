package com.vorontsov.neurostenographer.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.vorontsov.neurostenographer.data.file.AudioFileStore
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Imports a user-selected audio file. The original is preserved, and a 16 kHz
 * mono PCM16 working WAV is produced by streaming decode/resample so the whole
 * file is never held in RAM.
 */
@Singleton
class AudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFileStore: AudioFileStore,
    private val recordingRepository: RecordingRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun import(uri: Uri): Result<Long> = withContext(ioDispatcher) {
        var original: File? = null
        var working: File? = null
        try {
            val displayName = queryDisplayName(uri) ?: "Imported audio"
            val title = displayName.substringBeforeLast('.').ifBlank { "Imported audio" }

            val copiedOriginal = context.contentResolver.openInputStream(uri)?.use { input ->
                audioFileStore.copyToOriginal(input, displayName)
            } ?: error("Could not open the selected file")
            original = copiedOriginal

            val wavInfo = WavFileReader.readInfo(copiedOriginal)
            val resultFile: File
            val originalPath: String?
            when {
                wavInfo?.isTargetFormat == true -> {
                    // Already 16 kHz mono PCM16: use the preserved copy directly.
                    resultFile = copiedOriginal
                    originalPath = null
                }

                wavInfo != null && wavInfo.isPcm16 -> {
                    val converted = audioFileStore.createImportedWorkingFile(displayName)
                    working = converted
                    convertWavToTarget(copiedOriginal, wavInfo, converted)
                    resultFile = converted
                    originalPath = copiedOriginal.absolutePath
                }

                else -> {
                    val converted = audioFileStore.createImportedWorkingFile(displayName)
                    working = converted
                    val temporaryRaw = File(context.cacheDir, "import_${UUID.randomUUID()}.pcm")
                    try {
                        val sourceRate = decodeToMonoPcm(copiedOriginal, temporaryRaw)
                        resampleRawToWav(temporaryRaw, sourceRate, converted)
                    } finally {
                        temporaryRaw.delete()
                    }
                    resultFile = converted
                    originalPath = copiedOriginal.absolutePath
                }
            }

            val durationMs = wavDurationMs(resultFile)
            val recordingId = recordingRepository.createRecording(
                title = title,
                filePath = resultFile.absolutePath,
                originalPath = originalPath,
                source = RecordingSource.IMPORT,
            )
            recordingRepository.finalizeRecording(recordingId, durationMs, RecordingStatus.RECORDED)
            Result.success(recordingId)
        } catch (cancelled: CancellationException) {
            audioFileStore.delete(listOf(original?.absolutePath, working?.absolutePath))
            throw cancelled
        } catch (throwable: Throwable) {
            audioFileStore.delete(listOf(original?.absolutePath, working?.absolutePath))
            Result.failure(throwable)
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun convertWavToTarget(source: File, info: WavFileReader.Info, target: File) {
        val frameBytes = info.channels * 2
        val resampler = if (info.sampleRate == TARGET_SAMPLE_RATE) {
            null
        } else {
            StreamingLinearResampler(info.sampleRate, TARGET_SAMPLE_RATE)
        }
        RandomAccessFile(source, "r").use { input ->
            input.seek(info.dataOffset)
            WavFileWriter(target).use { writer ->
                var framesRemaining = info.dataLength / frameBytes
                val frameBuffer = ByteArray(FRAMES_PER_CHUNK * frameBytes)
                val mono = ShortArray(FRAMES_PER_CHUNK)
                while (framesRemaining > 0) {
                    val frames = min(framesRemaining, FRAMES_PER_CHUNK.toLong()).toInt()
                    val bytes = frames * frameBytes
                    try {
                        input.readFully(frameBuffer, 0, bytes)
                    } catch (_: java.io.EOFException) {
                        break
                    }
                    val monoFrames = StreamingLinearResampler.downmixToMono(
                        frameBuffer,
                        bytes,
                        info.channels,
                        mono,
                    )
                    if (resampler != null) {
                        resampler.process(mono, monoFrames) { out, count -> writer.write(out, count) }
                    } else {
                        writer.write(mono, monoFrames)
                    }
                    framesRemaining -= frames
                }
                resampler?.flush { out, count -> writer.write(out, count) }
            }
        }
    }

    /**
     * Decodes any MediaExtractor-supported audio track into a mono PCM16 raw file
     * at its native sample rate. Returns that sample rate.
     */
    private fun decodeToMonoPcm(source: File, target: File): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("The file has no audio track")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Unknown audio format")
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            BufferedOutputStream(FileOutputStream(target), RAW_BUFFER_BYTES).use { rawOutput ->
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var byteScratch = ByteArray(0)
                var shortScratch = ShortArray(0)
                var outputScratch = ByteArray(0)

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("Decoder input buffer unavailable")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                if (byteScratch.size < bufferInfo.size) {
                                    byteScratch = ByteArray(bufferInfo.size)
                                }
                                outputBuffer.get(byteScratch, 0, bufferInfo.size)
                                val frameBytes = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    channels * 4
                                } else {
                                    channels * 2
                                }
                                val frames = bufferInfo.size / frameBytes
                                if (shortScratch.size < frames) shortScratch = ShortArray(frames)
                                when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> {
                                        StreamingLinearResampler.downmixToMono(
                                            byteScratch,
                                            frames * frameBytes,
                                            channels,
                                            shortScratch,
                                        )
                                    }

                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        downmixFloat(
                                            byteScratch,
                                            frames * frameBytes,
                                            channels,
                                            shortScratch,
                                        )
                                    }

                                    else -> error("Unsupported decoder PCM encoding $pcmEncoding")
                                }
                                val outBytes = frames * 2
                                if (outputScratch.size < outBytes) {
                                    outputScratch = ByteArray(outBytes)
                                }
                                writeShorts(shortScratch, frames, outputScratch)
                                rawOutput.write(outputScratch, 0, outBytes)
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            return sampleRate
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun resampleRawToWav(raw: File, sourceRate: Int, target: File) {
        val resampler = StreamingLinearResampler(sourceRate, TARGET_SAMPLE_RATE)
        RandomAccessFile(raw, "r").use { input ->
            WavFileWriter(target).use { writer ->
                val bytes = ByteArray(FRAMES_PER_CHUNK * 2)
                val samples = ShortArray(FRAMES_PER_CHUNK)
                var pendingByte = -1
                while (true) {
                    val read = input.read(bytes)
                    if (read <= 0) break
                    var offset = 0
                    var count = 0
                    if (pendingByte >= 0) {
                        val low = pendingByte
                        val high = bytes[0].toInt() and 0xFF
                        samples[count++] = (((high shl 8) or low).toShort())
                        offset = 1
                        pendingByte = -1
                    }
                    while (offset + 1 < read) {
                        val low = bytes[offset].toInt() and 0xFF
                        val high = bytes[offset + 1].toInt()
                        samples[count++] = ((high shl 8) or low).toShort()
                        offset += 2
                    }
                    if (offset < read) pendingByte = bytes[offset].toInt() and 0xFF
                    if (count > 0) {
                        resampler.process(samples, count) { out, outputCount ->
                            writer.write(out, outputCount)
                        }
                    }
                }
                resampler.flush { out, count -> writer.write(out, count) }
            }
        }
    }

    private fun downmixFloat(
        bytes: ByteArray,
        byteCount: Int,
        channels: Int,
        output: ShortArray,
    ) {
        val floatBuffer = ByteBuffer.wrap(bytes, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val frames = byteCount / (channels * 4)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += floatBuffer.get(frame * channels + channel)
            }
            val sample = (sum / channels * Short.MAX_VALUE)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[frame] = sample.toShort()
        }
    }

    private fun writeShorts(samples: ShortArray, count: Int, output: ByteArray) {
        var index = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            output[index++] = (value and 0xFF).toByte()
            output[index++] = ((value shr 8) and 0xFF).toByte()
        }
    }

    private fun wavDurationMs(file: File): Long {
        val dataBytes = (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0)
        return dataBytes * 1000L / (TARGET_SAMPLE_RATE * 2L)
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val FRAMES_PER_CHUNK = 4_096
        const val WAV_HEADER_BYTES = 44L
        const val RAW_BUFFER_BYTES = 64 * 1024
        const val TIMEOUT_US = 10_000L
    }
}
