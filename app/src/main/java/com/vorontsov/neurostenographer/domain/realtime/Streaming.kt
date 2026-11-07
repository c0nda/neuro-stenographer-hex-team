package com.vorontsov.neurostenographer.domain.realtime

import kotlinx.coroutines.flow.StateFlow

/**
 * One provisional transcript segment produced while recording.
 *
 * Provisional segments are an IN-MEMORY preview only: they are never written
 * to Room during recording and never enter the public transcript JSON. The
 * authoritative transcript comes from the offline reprocessing after the
 * recording stops (PROMT §5: offline reprocessing improves accuracy, speaker
 * assignments and timestamps).
 *
 * [regionId] is the stable identity of the VAD speech region: an update with
 * the same id REPLACES the earlier version instead of appending a duplicate.
 */
data class StreamingTranscriptSegment(
    val regionId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String?,
    val isProvisional: Boolean = true,
)

sealed interface StreamingState {
    data object Idle : StreamingState
    data object Listening : StreamingState

    /** Recognition is running; [backlog] is the number of queued regions. */
    data class Processing(val backlog: Int) : StreamingState

    data class Error(val message: String) : StreamingState
}

/**
 * Real-time transcription pipeline. GigaAM v3 has no native streaming mode —
 * this pipeline processes completed VAD speech regions on a single background
 * worker. It MUST NOT be described as native streaming inference (PROMT §3.2).
 */
interface StreamingTranscriber {

    /** Provisional segments, ordered by start time; updates replace by regionId. */
    val segments: StateFlow<List<StreamingTranscriptSegment>>

    val state: StateFlow<StreamingState>

    /** Speaker of the most recently processed region (provisional). */
    val currentSpeaker: StateFlow<String?>

    /**
     * True when the ASR model is installed and the pipeline actually started.
     * False means the live transcript is unavailable (UI shows an honest
     * notice); recording itself is unaffected.
     */
    val isActive: Boolean

    /**
     * Starts the pipeline (loads models lazily). No-op returning false when
     * the ASR model is not installed.
     */
    suspend fun start(): Boolean

    /**
     * Feeds a block of mono PCM16 samples. Called on the audio capture
     * thread: implementations must only copy/enqueue here and never run
     * heavy work.
     */
    fun onPcm(samples: ShortArray, count: Int)

    /**
     * Stops the pipeline: flushes the trailing speech region, drains queued
     * regions (bounded wait) and returns the final provisional snapshot.
     */
    suspend fun stopAndFlush(): List<StreamingTranscriptSegment>
}
