package com.vorontsov.neurostenographer.domain.model

/**
 * Lifecycle of a recording as it moves through capture, recognition and diarization.
 */
enum class RecordingStatus {
    RECORDING,
    RECORDED,
    PROCESSING,
    READY,
    FAILED,
}

enum class RecordingSource {
    MICROPHONE,
    IMPORT,
}

enum class ProcessingProfile {
    PERFORMANCE,
    BALANCED,
    ACCURACY,
}

/**
 * How a word boundary was produced. This is internal metadata and is never added
 * to the public transcript JSON contract.
 */
enum class AlignmentSource {
    NONE,
    CTC_FRAME,
    RNNT_EMISSION,
    FORCED_ALIGNER,
}

data class Recording(
    val id: Long,
    val title: String,
    val filePath: String,
    val originalPath: String?,
    val durationMs: Long,
    val createdAtEpochMs: Long,
    val status: RecordingStatus,
    val speakerCount: Int,
    val sampleRate: Int,
    val channels: Int,
    val source: RecordingSource,
    val errorMessage: String?,
)

data class Speaker(
    val id: Long,
    val recordingId: Long,
    val speakerKey: String,
    val displayName: String?,
    val colorArgb: Long?,
)

data class WordTimestamp(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float?,
    val alignmentSource: AlignmentSource,
)

data class TranscriptSegment(
    val id: Long?,
    val speakerId: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isProvisional: Boolean = false,
    val alignmentSource: AlignmentSource = AlignmentSource.NONE,
    val words: List<WordTimestamp> = emptyList(),
)

/**
 * User-tunable recognition parameters. Defaults are conservative starting
 * values — they are NOT tuned against measured DER on Russian data (Phase 7).
 * Every value is exposed in the Home "Recognition tuning" card so the right
 * setting can be found empirically on real recordings.
 *
 * - [diarizationThreshold]: sherpa fast-clustering cosine threshold. LOWER
 *   (0.3) -> more speakers, splits similar voices; HIGHER (0.6-0.7) -> fewer
 *   speakers, merges one person split across turns.
 * - [diarizationMinOffMs]: sherpa minDurationOff. Turns separated by less
 *   than this gap are merged. Lower -> finer alternation between speakers.
 * - [asrMaxPauseMs]: ASR utterance splitting pause. Pauses longer than this
 *   start a new transcript segment. Lower -> more, shorter segments.
 */
data class AppSettings(
    val processingProfile: ProcessingProfile = ProcessingProfile.BALANCED,
    val keepScreenOnWhileRecording: Boolean = true,
    val diarizationThreshold: Float = DEFAULT_DIARIZATION_THRESHOLD,
    val diarizationMinOffMs: Long = DEFAULT_DIARIZATION_MIN_OFF_MS,
    val asrMaxPauseMs: Long = DEFAULT_ASR_MAX_PAUSE_MS,
) {
    companion object {
        const val DEFAULT_DIARIZATION_THRESHOLD = 0.4f
        const val DEFAULT_DIARIZATION_MIN_OFF_MS = 500L
        const val DEFAULT_ASR_MAX_PAUSE_MS = 700L

        val DIARIZATION_THRESHOLD_CHOICES = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f)
        val DIARIZATION_MIN_OFF_CHOICES_MS = listOf(200L, 500L, 1_000L)
        val ASR_MAX_PAUSE_CHOICES_MS = listOf(500L, 700L, 1_000L, 1_500L)
    }
}

enum class CaptureStatus {
    IDLE,
    RECORDING,
    PAUSED,
    STOPPING,
    ERROR,
}

data class RecordingSessionState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val recordingId: Long? = null,
    val elapsedMs: Long = 0,
    val amplitudes: List<Float> = emptyList(),
    val errorMessage: String? = null,
)

data class PlaybackState(
    val isPrepared: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null,
)
