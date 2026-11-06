package com.vorontsov.neurostenographer.data.repository

import androidx.room.withTransaction
import com.vorontsov.neurostenographer.data.local.NeuroStenographerDatabase
import com.vorontsov.neurostenographer.data.local.dao.SegmentDao
import com.vorontsov.neurostenographer.data.local.dao.SpeakerDao
import com.vorontsov.neurostenographer.data.local.dao.TranscriptDao
import com.vorontsov.neurostenographer.data.local.entity.SpeakerEntity
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.domain.repository.SpeakerRepository
import com.vorontsov.neurostenographer.domain.transcript.TranscriptJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeakerRepositoryImpl @Inject constructor(
    private val database: NeuroStenographerDatabase,
    private val speakerDao: SpeakerDao,
    private val segmentDao: SegmentDao,
    private val transcriptDao: TranscriptDao,
) : SpeakerRepository {

    override fun observeSpeakers(recordingId: Long): Flow<List<Speaker>> =
        speakerDao.observeByRecording(recordingId).map { entities -> entities.map(SpeakerEntity::toDomain) }

    override suspend fun renameSpeaker(speakerId: Long, displayName: String?) {
        val entity = speakerDao.getById(speakerId) ?: return
        speakerDao.update(entity.copy(displayName = displayName?.trim()?.ifBlank { null }))
    }

    override suspend fun mergeSpeakers(recordingId: Long, sourceSpeakerId: Long, targetSpeakerId: Long) {
        if (sourceSpeakerId == targetSpeakerId) return
        database.withTransaction {
            val source = speakerDao.getById(sourceSpeakerId) ?: return@withTransaction
            val target = speakerDao.getById(targetSpeakerId) ?: return@withTransaction
            if (source.recordingId != recordingId || target.recordingId != recordingId) {
                return@withTransaction
            }
            speakerDao.reassignSegments(sourceSpeakerId, targetSpeakerId)
            speakerDao.delete(sourceSpeakerId)

            transcriptDao.getByRecording(recordingId)?.let { transcript ->
                val rows = segmentDao.getByRecording(recordingId)
                val json = TranscriptJson.encode(
                    rows.map { row ->
                        com.vorontsov.neurostenographer.domain.model.TranscriptSegment(
                            id = row.id,
                            speakerId = row.speakerKey ?: "unknown",
                            startMs = row.startMs,
                            endMs = row.endMs,
                            text = row.text,
                            isProvisional = row.isProvisional,
                            alignmentSource = row.alignmentSource,
                        )
                    },
                )
                transcriptDao.updateSnapshot(transcript.id, json, System.currentTimeMillis())
            }
        }
    }
}

private fun SpeakerEntity.toDomain(): Speaker = Speaker(
    id = id,
    recordingId = recordingId,
    speakerKey = speakerKey,
    displayName = displayName,
    colorArgb = colorArgb,
)
