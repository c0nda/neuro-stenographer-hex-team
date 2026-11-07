package com.vorontsov.neurostenographer.domain.vad

import com.vorontsov.neurostenographer.domain.asr.SampleRange

/**
 * Stateless contract of the VAD stage. Implementations live in the ML/data
 * layers; domain code only sees these interfaces, so Silero can be replaced
 * without touching business logic.
 */
interface VadEngine : AutoCloseable {

    /** Clears all internal state; subsequent regions start again at sample 0. */
    fun reset()

    /**
     * Feeds a chunk of mono float PCM in [-1, 1]. Buffers that do not align
     * with the model frame size are carried over internally, so callers may
     * pass arbitrary block sizes.
     */
    fun process(samples: FloatArray)

    /**
     * Ends the stream and returns all detected speech regions in samples
     * relative to the last [reset]. Timestamps are later translated to
     * absolute recording time by the pipeline via integer sample offsets.
     */
    fun flush(): List<SampleRange>

    /**
     * Streaming: returns regions closed since the last [drainRegions] or
     * [reset] without ending the stream. Default is a no-op for engines
     * without streaming region delivery.
     */
    fun drainRegions(): List<SampleRange> = emptyList()
}

interface VadEngineFactory {
    /** True when a verified VAD model is installed and can be loaded. */
    fun isAvailable(): Boolean

    /** Loads the model once and reuses it. Throws [VadModelUnavailableException]. */
    suspend fun getOrCreate(): VadEngine

    /** Releases the cached engine; the next [getOrCreate] reloads the model. */
    suspend fun release()
}

class VadModelUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
