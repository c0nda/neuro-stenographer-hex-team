package com.vorontsov.neurostenographer.data.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal RIFF/WAVE header reader. Only PCM (format 1) and WAVE_FORMAT_EXTENSIBLE
 * with a PCM sub-format are accepted, because the pipeline works with PCM16.
 */
object WavFileReader {

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataLength: Long,
    ) {
        val isPcm16: Boolean get() = bitsPerSample == 16
        val isTargetFormat: Boolean
            get() = sampleRate == 16_000 && channels == 1 && bitsPerSample == 16
    }

    fun readInfo(file: File): Info? {
        if (!file.isFile || file.length() < 44) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(12)
                raf.readFully(riff)
                if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
                if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null

                var sampleRate = 0
                var channels = 0
                var bits = 0
                var formatCode = 0
                var dataOffset = -1L
                var dataLength = -1L

                while (raf.filePointer + 8 <= raf.length()) {
                    val chunkHeader = ByteArray(8)
                    raf.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val size = chunkHeader.readIntLe(4).toLong() and 0xFFFFFFFFL
                    val payloadStart = raf.filePointer
                    when (id) {
                        "fmt " -> {
                            if (size < 16) return null
                            val fmt = ByteArray(minOf(size, 40L).toInt())
                            raf.readFully(fmt)
                            formatCode = fmt.readShortLe(0)
                            channels = fmt.readShortLe(2)
                            sampleRate = fmt.readIntLe(4)
                            bits = fmt.readShortLe(14)
                            if (formatCode == 0xFFFE && fmt.size >= 26) {
                                // WAVE_FORMAT_EXTENSIBLE: first two bytes of the sub-format GUID.
                                formatCode = fmt.readShortLe(24)
                            }
                            if (size > fmt.size) raf.seek(payloadStart + size + (size and 1L))
                        }

                        "data" -> {
                            dataOffset = payloadStart
                            dataLength = if (size == 0L) {
                                file.length() - payloadStart
                            } else {
                                minOf(size, file.length() - payloadStart)
                            }
                            raf.seek(payloadStart + size + (size and 1L))
                        }

                        else -> raf.seek(payloadStart + size + (size and 1L))
                    }
                    if (dataOffset >= 0 && sampleRate > 0) break
                }

                if (formatCode != 1 || sampleRate <= 0 || channels <= 0 || bits <= 0 || dataOffset < 0) {
                    null
                } else {
                    Info(sampleRate, channels, bits, dataOffset, dataLength)
                }
            }
        }.getOrNull()
    }

    private fun ByteArray.readIntLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.readShortLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}
