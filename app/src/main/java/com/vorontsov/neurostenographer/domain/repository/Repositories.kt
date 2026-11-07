package com.vorontsov.neurostenographer.domain.repository

import com.vorontsov.neurostenographer.domain.model.AppSettings
import com.vorontsov.neurostenographer.domain.model.ProcessingProfile
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

interface RecordingRepository {
    fun observeRecordings(query: String = ""): Flow<List<Recording>>
    fun observeRecording(recordingId: Long): Flow<Recording?>
    suspend fun getRecording(recordingId: Long): Recording?
    suspend fun createRecording(
        title: String,
        filePath: String,
        originalPath: String?,
        source: RecordingSource,
        sampleRate: Int = 16_000,
        channels: Int = 1,
    ): Long
    suspend fun finalizeRecording(
        recordingId: Long,
        durationMs: Long,
        status: RecordingStatus = RecordingStatus.RECORDED,
    )
    suspend fun updateStatus(recordingId: Long, status: RecordingStatus, errorMessage: String? = null)
    suspend fun renameRecording(recordingId: Long, title: String)
    suspend fun deleteRecording(recordingId: Long)
}

interface TranscriptRepository {
    fun observeSegments(recordingId: Long): Flow<List<TranscriptSegment>>
    suspend fun replaceSegments(recordingId: Long, segments: List<TranscriptSegment>)
    suspend fun getTranscriptJson(recordingId: Long): String?
}

interface SpeakerRepository {
    fun observeSpeakers(recordingId: Long): Flow<List<Speaker>>
    suspend fun renameSpeaker(speakerId: Long, displayName: String?)
    suspend fun mergeSpeakers(recordingId: Long, sourceSpeakerId: Long, targetSpeakerId: Long)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setProcessingProfile(profile: ProcessingProfile)
    suspend fun setKeepScreenOnWhileRecording(enabled: Boolean)
    suspend fun setDiarizationThreshold(threshold: Float)
    suspend fun setDiarizationMinOffMs(minOffMs: Long)
    suspend fun setAsrMaxPauseMs(maxPauseMs: Long)
}
