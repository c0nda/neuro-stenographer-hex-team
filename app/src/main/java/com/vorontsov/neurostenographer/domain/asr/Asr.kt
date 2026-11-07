package com.vorontsov.neurostenographer.domain.asr

import com.vorontsov.neurostenographer.domain.model.ModelRepository

/**
 * A single recognized word with a model-derived timestamp. Timestamps are
 * relative to the audio buffer that produced them; the transcription pipeline
 * translates them to absolute recording time.
 *
 * [confidence] is the mean CTC token probability for the word, or null when the
 * engine cannot provide it. It is internal metadata and never enters the public
 * transcript JSON.
 */
data class AsrWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float?,
)

/**
 * A continuous utterance built from one or more words. This is what becomes a
 * public transcript segment.
 */
data class AsrUtterance(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<AsrWord>,
)

/**
 * Acoustic model boundary. Implementations live in the ML/data layers; domain
 * and presentation code only see this interface, so the recognizer can be
 * replaced without touching business logic.
 */
interface AsrEngine : AutoCloseable {
    /**
     * Transcribes mono float PCM in [-1, 1]. Implementations must only accept
     * the sample rate they were built for and must be safe to call off the main
     * thread. The call blocks until inference completes.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int): List<AsrWord>
}

interface AsrEngineFactory {
    /** True when a verified model is installed and can be loaded. */
    fun isAvailable(): Boolean

    /**
     * Loads the model once and reuses it. Throws [AsrModelUnavailableException]
     * when no verified model is installed.
     */
    suspend fun getOrCreate(): AsrEngine

    /**
     * Closes the cached engine and releases its native session. The next
     * [getOrCreate] reloads the model. Heavy models (ASR) are normally kept
     * resident for the process lifetime; this exists so a competing heavy
     * model (e.g. diarization) can force a sequential lifecycle and cap peak
     * RAM. Default is a no-op for engines that hold no heavy resources.
     */
    suspend fun release() = Unit
}

/** Random-access, mono float PCM source used by the chunked offline pipeline. */
interface AudioSource : AutoCloseable {
    val sampleRate: Int

    /** Total number of samples in the source. */
    val totalSamples: Long

    /**
     * Reads at most [count] samples starting at [startSample]. The returned array
     * may be shorter only at the end of the source.
     */
    fun read(startSample: Long, count: Int): FloatArray
}

interface AudioSourceFactory {
    /**
     * Opens a 16 kHz mono PCM16 WAV file. Throws
     * [UnsupportedAudioFormatException] for any other format.
     */
    fun open(path: String): AudioSource
}

class AsrModelUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class UnsupportedAudioFormatException(message: String) : IllegalArgumentException(message)

/**
 * Manages the ASR model file that is imported by the user. The app never
 * downloads it, so the runtime remains fully offline. Provisioning semantics
 * are shared with every other model via [ModelRepository].
 */
interface AsrModelRepository : ModelRepository
