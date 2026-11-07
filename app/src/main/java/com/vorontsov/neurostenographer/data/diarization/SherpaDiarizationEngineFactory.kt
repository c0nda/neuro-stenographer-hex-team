package com.vorontsov.neurostenographer.data.diarization

import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngine
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.diarization.DiarizationModelUnavailableException
import com.vorontsov.neurostenographer.ml.diarization.SherpaDiarizationEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Creates the diarization engine on demand and owns its lifecycle. The engine
 * is NOT kept resident: it is created after ASR has released its 225 MB
 * session and must be released as soon as diarization finishes, so the two
 * heavy model sets are never in memory at the same time (Phase 1 §3.2).
 */
@Singleton
class SherpaDiarizationEngineFactory @Inject constructor(
    private val modelStore: DiarizationModelStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DiarizationEngineFactory {

    private val gate = Mutex()
    private var engine: DiarizationEngine? = null

    override fun isAvailable(): Boolean =
        modelStore.verifiedSegmentationFile() != null &&
            modelStore.verifiedEmbeddingFile() != null

    override suspend fun getOrCreate(): DiarizationEngine = gate.withLock {
        engine?.let { return it }
        val segmentation = modelStore.verifiedSegmentationFile()
        val embedding = modelStore.verifiedEmbeddingFile()
        if (segmentation == null || embedding == null) {
            throw DiarizationModelUnavailableException(
                "The diarization models are not installed. Import them on the Home screen.",
            )
        }
        val loaded = withContext(ioDispatcher) {
            SherpaDiarizationEngine.load(
                segmentationModel = segmentation,
                embeddingModel = embedding,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }
        engine = loaded
        loaded
    }

    override suspend fun release() = gate.withLock {
        engine?.close()
        engine = null
    }
}
