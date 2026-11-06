package com.vorontsov.neurostenographer.ml.asr.gigaam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden test against the reference NumPy implementation from
 * `istupakov/onnx-asr` (GigaamPreprocessorNumpy). The fixture was generated with
 * the exact published parameters and a deterministic 1600-sample signal.
 */
class GigaamV3FeatureExtractorTest {

    @Test
    fun `frame count matches reference formula`() {
        val extractor = GigaamV3FeatureExtractor()
        assertEquals(0, extractor.frameCount(0))
        assertEquals(0, extractor.frameCount(319))
        assertEquals(1, extractor.frameCount(320))
        assertEquals(2, extractor.frameCount(480))
        assertEquals(9, extractor.frameCount(1600))
    }

    @Test
    fun `features match the numpy reference within float tolerance`() {
        val fixture = loadFixture()
        val extractor = GigaamV3FeatureExtractor()

        val actual = extractor.extract(fixture.samples)

        assertEquals(fixture.frames, actual.frames)
        assertEquals(fixture.expected.size, actual.data.size)
        var maxDifference = 0.0
        for (index in fixture.expected.indices) {
            maxDifference = maxOf(maxDifference, abs(actual.data[index] - fixture.expected[index]).toDouble())
        }
        assertTrue(
            "max feature difference was $maxDifference, expected <= 2e-3",
            maxDifference <= 2e-3,
        )
    }

    @Test
    fun `audio shorter than one window produces no frames`() {
        val features = GigaamV3FeatureExtractor().extract(FloatArray(100))
        assertEquals(0, features.frames)
        assertEquals(0, features.data.size)
    }

    private fun loadFixture(): Fixture {
        val stream = checkNotNull(javaClass.getResourceAsStream("/gigaam/gigaam_v3_fixture.txt")) {
            "Missing GigaAM fixture"
        }
        val lines = stream.bufferedReader().use { it.readText() }.lineSequence().toList()
        val header = lines.first().split(' ').filter { it.isNotBlank() }
        val frames = header[1].toInt()
        val mels = header[3].toInt()
        assertEquals(GigaamV3FeatureExtractor.MELS, mels)
        val samples = lines[1]
            .trim()
            .split(Regex("\\s+"))
            .map { it.toInt() }
            .map { it / 32768f }
            .toFloatArray()
        val expected = lines.drop(2)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toFloat() }
            .toFloatArray()
        assertEquals(frames * mels, expected.size)
        return Fixture(frames, samples, expected)
    }

    private data class Fixture(
        val frames: Int,
        val samples: FloatArray,
        val expected: FloatArray,
    )
}
