package com.vorontsov.neurostenographer.domain.diarization

import com.vorontsov.neurostenographer.domain.model.ModelStatus
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

/**
 * One speaker turn as produced by the diarization engine. Timestamps are
 * absolute (relative to the beginning of the original recording).
 *
 * [clusterIndex] is the raw cluster id from the clustering algorithm — NOT a
 * public speaker id. [SpeakerTurnMerger] maps cluster indices to stable
 * `speaker_N` keys in order of first appearance.
 *
 * [confidence] is the clusterer/segmenter confidence when the engine provides
 * it, or null when unavailable. Turn-level confidence is internal metadata.
 */
data class DiarizationTurn(
    val startMs: Long,
    val endMs: Long,
    val clusterIndex: Int,
    val confidence: Float?,
)

/**
 * Offline speaker diarization with an unknown number of speakers.
 * Implementations live in the ML/data layers; domain code only sees these
 * interfaces, so sherpa-onnx can be replaced without touching business logic.
 */
interface DiarizationEngine : AutoCloseable {

    /**
     * Diarizes the whole recording. [samples] is mono float PCM in [-1, 1];
     * implementations must only accept the sample rate they were built for.
     * The call blocks until clustering completes.
     */
    fun diarize(samples: FloatArray, sampleRate: Int): List<DiarizationTurn>
}

interface DiarizationEngineFactory {
    /** True when both verified diarization models are installed. */
    fun isAvailable(): Boolean

    /** Loads both models once. Throws [DiarizationModelUnavailableException]. */
    suspend fun getOrCreate(): DiarizationEngine

    /**
     * Releases the engine and its native models. MUST be called before the
     * engine leaves scope: diarization models are heavy and the app keeps ASR
     * and diarization sessions strictly sequential to cap peak RAM.
     */
    suspend fun release()
}

class DiarizationModelUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Provisioning of the two diarization model files (segmentation + speaker
 * embedding). [ModelStatus] is READY only when BOTH files are verified.
 */
interface DiarizationModelRepository {
    val status: StateFlow<ModelStatus>

    /** Re-checks both installed files against the expected artifacts. */
    suspend fun refresh()

    /** Imports and verifies the pyannote segmentation model file. */
    suspend fun importSegmentationModel(open: () -> InputStream): Result<Unit>

    /** Imports and verifies the speaker embedding model file. */
    suspend fun importEmbeddingModel(open: () -> InputStream): Result<Unit>

    suspend fun deleteModels()
}
