package com.vorontsov.neurostenographer.ml.diarization

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngine
import com.vorontsov.neurostenographer.domain.diarization.DiarizationModelUnavailableException
import com.vorontsov.neurostenographer.domain.diarization.DiarizationTurn
import java.io.File
import kotlin.math.roundToLong

/**
 * sherpa-onnx implementation of offline speaker diarization:
 * pyannote segmentation 3.0 (INT8) + 3D-Speaker CAM++ embeddings + threshold
 * fast clustering with an unknown number of speakers.
 *
 * Verified API contract (sherpa-onnx v2.0.1, com.k2fsa.sherpa.onnx):
 *  - `OfflineSpeakerDiarization(config)` loads from file paths;
 *  - `process(samples)` returns turns with `start`/`end` in SECONDS,
 *    `speaker` as a cluster index counting from 0, and `confidence` in
 *    [-1, 1], or -2 when the engine does not provide it;
 *  - the high-level output is a sequence of single-speaker turns — overlap is
 *    not preserved through this API (documented limitation, not fabricated).
 *
 * Clustering uses `numClusters = -1` + [threshold]: a larger threshold
 * produces fewer speakers. 0.5 is the library default and our starting value;
 * it must be validated against measured DER on Russian multi-speaker data
 * (Phase 7) before being treated as tuned.
 */
class SherpaDiarizationEngine private constructor(
    private val diarization: OfflineSpeakerDiarization,
) : DiarizationEngine {

    override fun diarize(samples: FloatArray, sampleRate: Int): List<DiarizationTurn> {
        require(sampleRate == SAMPLE_RATE) {
            "Diarization expects 16 kHz audio, got $sampleRate Hz"
        }
        if (samples.isEmpty()) return emptyList()
        return try {
            diarization.process(samples).map { segment ->
                DiarizationTurn(
                    startMs = (segment.start * 1000f).roundToLong(),
                    endMs = (segment.end * 1000f).roundToLong(),
                    clusterIndex = segment.speaker,
                    confidence = segment.confidence.takeUnless { it == CONFIDENCE_UNAVAILABLE },
                )
            }
        } catch (throwable: Throwable) {
            throw DiarizationModelUnavailableException(
                "Diarization failed: ${throwable.message}",
                throwable,
            )
        }
    }

    override fun close() {
        diarization.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CONFIDENCE_UNAVAILABLE = -2f
        private const val CLUSTER_THRESHOLD = 0.5f

        fun load(
            segmentationModel: File,
            embeddingModel: File,
            threads: Int,
        ): SherpaDiarizationEngine {
            return try {
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = segmentationModel.absolutePath,
                        ),
                        numThreads = threads.coerceAtLeast(1),
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(
                        model = embeddingModel.absolutePath,
                        numThreads = threads.coerceAtLeast(1),
                    ),
                    clustering = FastClusteringConfig(
                        numClusters = -1,
                        threshold = CLUSTER_THRESHOLD,
                    ),
                )
                SherpaDiarizationEngine(OfflineSpeakerDiarization(config = config))
            } catch (throwable: Throwable) {
                if (throwable is DiarizationModelUnavailableException) throw throwable
                throw DiarizationModelUnavailableException(
                    "Could not load the diarization models: ${throwable.message}",
                    throwable,
                )
            }
        }
    }
}
