package com.vorontsov.neurostenographer.ml.diarization

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Thin wrapper over sherpa-onnx `SpeakerEmbeddingExtractor` (3D-Speaker CAM++)
 * used for PROVISIONAL real-time speaker assignments. Loads the same verified
 * embedding model file as the offline diarization pipeline.
 *
 * Verified API contract (sherpa-onnx v2.0.1, com.k2fsa.sherpa.onnx):
 * `createStream()` → `OnlineStream.acceptWaveform(samples, sampleRate)` →
 * `inputFinished()` → `compute(stream): FloatArray`.
 */
class SherpaEmbeddingExtractor private constructor(
    private val extractor: SpeakerEmbeddingExtractor,
) : AutoCloseable {

    /**
     * Extracts an embedding for mono float PCM in [-1, 1]. Returns null when
     * the native side is not ready (should not happen after inputFinished,
     * but never crashes the recording pipeline).
     */
    fun extract(samples: FloatArray, sampleRate: Int): FloatArray? {
        if (samples.isEmpty()) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } catch (throwable: Throwable) {
            null
        } finally {
            stream.release()
        }
    }

    override fun close() {
        extractor.release()
    }

    companion object {
        fun load(modelFile: File, threads: Int = 1): SherpaEmbeddingExtractor =
            SherpaEmbeddingExtractor(
                SpeakerEmbeddingExtractor(
                    config = SpeakerEmbeddingExtractorConfig(
                        model = modelFile.absolutePath,
                        numThreads = threads.coerceAtLeast(1),
                    ),
                ),
            )
    }
}
