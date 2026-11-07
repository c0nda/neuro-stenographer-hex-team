package com.vorontsov.neurostenographer.data.asr

import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.asr.AsrEngine
import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrModelUnavailableException
import com.vorontsov.neurostenographer.ml.asr.gigaam.GigaamV3CtcEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily creates the single ONNX session used by the whole app. Model loading is
 * expensive (hundreds of MB), so it happens once, off the main thread, and the
 * session is shared by subsequent transcriptions.
 */
@Singleton
class GigaamAsrEngineFactory @Inject constructor(
    private val modelStore: AsrModelStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AsrEngineFactory {

    private val gate = Mutex()
    private var engine: AsrEngine? = null

    override fun isAvailable(): Boolean = modelStore.verifiedModelFile() != null

    override suspend fun getOrCreate(): AsrEngine = gate.withLock {
        engine?.let { return it }
        val file = modelStore.verifiedModelFile()
            ?: throw AsrModelUnavailableException(
                "The GigaAM model is not installed. Import v3_ctc.int8.onnx on the Home screen.",
            )
        val loaded = withContext(ioDispatcher) {
            GigaamV3CtcEngine.load(
                modelFile = file,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            )
        }
        engine = loaded
        return loaded
    }

    override suspend fun release() = gate.withLock {
        engine?.close()
        engine = null
    }
}
