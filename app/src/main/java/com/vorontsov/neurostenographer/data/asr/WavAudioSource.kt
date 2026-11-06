package com.vorontsov.neurostenographer.data.asr

import com.vorontsov.neurostenographer.data.audio.WavFileReader
import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.AudioSourceFactory
import com.vorontsov.neurostenographer.domain.asr.UnsupportedAudioFormatException
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

/**
 * Random-access reader over a 16 kHz mono PCM16 WAV file. Only the requested
 * window is decoded into float PCM, so a long recording is never fully resident
 * in memory.
 */
class WavAudioSource private constructor(
    private val file: RandomAccessFile,
    private val dataOffset: Long,
    private val dataLength: Long,
    override val sampleRate: Int,
) : AudioSource {

    override val totalSamples: Long = dataLength / BYTES_PER_SAMPLE

    override fun read(startSample: Long, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        val start = startSample.coerceIn(0, totalSamples)
        val available = (totalSamples - start).coerceAtMost(count.toLong()).toInt()
        if (available <= 0) return FloatArray(0)
        val bytes = ByteArray(available * BYTES_PER_SAMPLE)
        file.seek(dataOffset + start * BYTES_PER_SAMPLE)
        file.readFully(bytes)
        val output = FloatArray(available)
        var index = 0
        while (index < available) {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            val sample = (high shl 8) or low
            output[index] = sample.toShort() / 32768f
            index++
        }
        return output
    }

    override fun close() {
        file.close()
    }

    class Factory @Inject constructor() : AudioSourceFactory {
        override fun open(path: String): AudioSource {
            val target = File(path)
            val info = WavFileReader.readInfo(target)
                ?: throw UnsupportedAudioFormatException("The recording is not a readable PCM WAV file")
            if (!info.isTargetFormat) {
                throw UnsupportedAudioFormatException(
                    "Expected 16 kHz mono PCM16 audio, got ${info.sampleRate} Hz, " +
                        "${info.channels} channel(s), ${info.bitsPerSample}-bit",
                )
            }
            val file = RandomAccessFile(target, "r")
            return try {
                WavAudioSource(file, info.dataOffset, info.dataLength, info.sampleRate)
            } catch (throwable: Throwable) {
                runCatching { file.close() }
                throw throwable
            }
        }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}
