package com.vorontsov.neurostenographer.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `streaming writer produces a readable target format header`() {
        val file = temporaryFolder.newFile("test.wav")
        val samples = ShortArray(1_600) { index -> (index % 100).toShort() }

        WavFileWriter(file).use { writer ->
            writer.write(samples, samples.size)
            assertEquals(100L, writer.durationMs())
        }

        val info = WavFileReader.readInfo(file)
        assertNotNull(info)
        requireNotNull(info)
        assertTrue(info.isTargetFormat)
        assertTrue(info.isPcm16)
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(3_200L, info.dataLength)
        assertEquals(44L, info.dataOffset)
        assertEquals(100L, info.dataLength * 1000L / (info.sampleRate * 2L))
    }

    @Test
    fun `header builder encodes RIFF sizes and format fields`() {
        val header = WavFileWriter.buildHeader(
            dataBytes = 32_000,
            sampleRate = 16_000,
            channels = 1,
        )

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(32_036, readIntLe(header, 4))
        assertEquals(16_000, readIntLe(header, 24))
        assertEquals(32_000, readIntLe(header, 28))
        assertEquals(1, readShortLe(header, 22))
        assertEquals(16, readShortLe(header, 34))
        assertEquals(32_000, readIntLe(header, 40))
    }

    @Test
    fun `non riff files are rejected`() {
        val file = temporaryFolder.newFile("not-a-wav.bin")
        file.writeBytes(ByteArray(64))

        assertNull(WavFileReader.readInfo(file))
    }

    @Test
    fun `truncated files are rejected`() {
        val file = temporaryFolder.newFile("short.wav")
        file.writeBytes(ByteArray(20))

        assertNull(WavFileReader.readInfo(file))
        assertFalse(file.length() >= 44)
    }

    @Test
    fun `a file with an unsupported format code is rejected`() {
        val file = File(temporaryFolder.root, "float.wav")
        val header = WavFileWriter.buildHeader(32, 16_000, 1)
        putShortLe(header, 20, 3) // IEEE float, not PCM
        file.writeBytes(header)

        assertNull(WavFileReader.readInfo(file))
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun putShortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
