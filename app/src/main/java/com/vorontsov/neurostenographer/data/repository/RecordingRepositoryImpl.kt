package com.vorontsov.neurostenographer.data.repository

import com.vorontsov.neurostenographer.data.file.AudioFileStore
import com.vorontsov.neurostenographer.data.local.dao.RecordingDao
import com.vorontsov.neurostenographer.data.local.entity.RecordingEntity
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepositoryImpl @Inject constructor(
    private val recordingDao: RecordingDao,
    private val audioFileStore: AudioFileStore,
) : RecordingRepository {

    override fun observeRecordings(query: String): Flow<List<Recording>> =
        recordingDao.observeAll().map { entities ->
            entities
                .asSequence()
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                .map(RecordingEntity::toDomain)
                .toList()
        }

    override fun observeRecording(recordingId: Long): Flow<Recording?> =
        recordingDao.observeById(recordingId).map { it?.toDomain() }

    override suspend fun getRecording(recordingId: Long): Recording? =
        recordingDao.getById(recordingId)?.toDomain()

    override suspend fun createRecording(
        title: String,
        filePath: String,
        originalPath: String?,
        source: RecordingSource,
        sampleRate: Int,
        channels: Int,
    ): Long = recordingDao.insert(
        RecordingEntity(
            title = title,
            filePath = filePath,
            originalPath = originalPath,
            durationMs = 0,
            createdAtEpochMs = System.currentTimeMillis(),
            status = if (source == RecordingSource.MICROPHONE) {
                RecordingStatus.RECORDING
            } else {
                RecordingStatus.RECORDED
            },
            speakerCount = 0,
            sampleRate = sampleRate,
            channels = channels,
            source = source,
            errorMessage = null,
        ),
    )

    override suspend fun finalizeRecording(
        recordingId: Long,
        durationMs: Long,
        status: RecordingStatus,
    ) {
        recordingDao.finalize(recordingId, durationMs, status)
    }

    override suspend fun updateStatus(
        recordingId: Long,
        status: RecordingStatus,
        errorMessage: String?,
    ) {
        recordingDao.updateStatus(recordingId, status, errorMessage)
    }

    override suspend fun renameRecording(recordingId: Long, title: String) {
        val normalized = title.trim().ifBlank { "Untitled recording" }
        recordingDao.rename(recordingId, normalized)
    }

    override suspend fun deleteRecording(recordingId: Long) {
        val entity = recordingDao.getById(recordingId) ?: return
        recordingDao.delete(recordingId)
        audioFileStore.delete(listOf(entity.filePath, entity.originalPath))
    }
}

private fun RecordingEntity.toDomain(): Recording = Recording(
    id = id,
    title = title,
    filePath = filePath,
    originalPath = originalPath,
    durationMs = durationMs,
    createdAtEpochMs = createdAtEpochMs,
    status = status,
    speakerCount = speakerCount,
    sampleRate = sampleRate,
    channels = channels,
    source = source,
    errorMessage = errorMessage,
)
