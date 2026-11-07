package com.vorontsov.neurostenographer.data.vad

import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.vad.VadEngine
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.domain.vad.VadModelUnavailableException
import com.vorontsov.neurostenographer.ml.vad.SileroVadEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lazily creates the single Silero VAD session used by the app. The model is
 * tiny (~2 MB) and cheap to keep resident, so unlike the ASR engine it does
 * not need a release path for peak-RAM control.
 */
@Singleton
class SileroVadEngineFactory @Inject constructor(
    private val modelStore: VadModelStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VadEngineFactory {

    private val gate = Mutex()
    private var engine: VadEngine? = null

    override fun isAvailable(): Boolean = modelStore.verifiedModelFile() != null

    override suspend fun getOrCreate(): VadEngine = gate.withLock {
        engine?.let { return it }
        val file = modelStore.verifiedModelFile()
            ?: throw VadModelUnavailableException(
                "The Silero VAD model is not installed. Import silero_vad.onnx on the Home screen.",
            )
        val loaded = withContext(ioDispatcher) { SileroVadEngine.load(file) }
        engine = loaded
        loaded
    }

    override suspend fun release() = gate.withLock {
        engine?.close()
        engine = null
    }
}
