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

data class AppSettings(
    val processingProfile: ProcessingProfile = ProcessingProfile.BALANCED,
    val keepScreenOnWhileRecording: Boolean = true,
)

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
