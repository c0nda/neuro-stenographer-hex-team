package com.vorontsov.neurostenographer.data.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streaming PCM16 WAV writer. The header is patched on close, so the writer never
 * needs the full recording in memory.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = CHANNELS,
) : Closeable {

    private val output = BufferedOutputStream(FileOutputStream(file), BUFFER_SIZE)
    private var dataBytes: Long = 0
    private var closed = false

    init {
        file.parentFile?.mkdirs()
        output.write(ByteArray(HEADER_SIZE))
    }

    fun write(samples: ShortArray, count: Int) {
        if (closed) error("WavFileWriter is closed")
        if (count <= 0) return
        val bytes = ByteArray(count * 2)
        var index = 0
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[index++] = (value and 0xFF).toByte()
            bytes[index++] = ((value shr 8) and 0xFF).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size
    }

    fun durationMs(): Long = dataBytes * 1000L / (sampleRate.toLong() * channels * 2)

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            output.flush()
            output.close()
        }
        runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(buildHeader(dataBytes, sampleRate, channels))
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private const val HEADER_SIZE = 44
        private const val BUFFER_SIZE = 64 * 1024

        fun buildHeader(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
            val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
            val blockAlign = channels * BITS_PER_SAMPLE / 8
            val header = ByteArray(HEADER_SIZE)
            header.putAscii(0, "RIFF")
            header.putIntLe(4, (36 + dataBytes).toInt())
            header.putAscii(8, "WAVE")
            header.putAscii(12, "fmt ")
            header.putIntLe(16, 16)
            header.putShortLe(20, 1)
            header.putShortLe(22, channels)
            header.putIntLe(24, sampleRate)
            header.putIntLe(28, byteRate)
            header.putShortLe(32, blockAlign)
            header.putShortLe(34, BITS_PER_SAMPLE)
            header.putAscii(36, "data")
            header.putIntLe(40, dataBytes.toInt())
            return header
        }

        private fun ByteArray.putAscii(offset: Int, value: String) {
            value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
        }

        private fun ByteArray.putIntLe(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value shr 8) and 0xFF).toByte()
            this[offset + 2] = ((value shr 16) and 0xFF).toByte()
            this[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun ByteArray.putShortLe(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }
}
