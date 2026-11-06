package com.vorontsov.neurostenographer.data.repository

import androidx.room.withTransaction
import com.vorontsov.neurostenographer.data.local.NeuroStenographerDatabase
import com.vorontsov.neurostenographer.data.local.dao.RecordingDao
import com.vorontsov.neurostenographer.data.local.dao.SegmentDao
import com.vorontsov.neurostenographer.data.local.dao.SpeakerDao
import com.vorontsov.neurostenographer.data.local.dao.TranscriptDao
import com.vorontsov.neurostenographer.data.local.dao.WordDao
import com.vorontsov.neurostenographer.data.local.entity.SegmentEntity
import com.vorontsov.neurostenographer.data.local.entity.SegmentRow
import com.vorontsov.neurostenographer.data.local.entity.SpeakerEntity
import com.vorontsov.neurostenographer.data.local.entity.TranscriptEntity
import com.vorontsov.neurostenographer.data.local.entity.WordEntity
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import com.vorontsov.neurostenographer.domain.transcript.TranscriptJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptRepositoryImpl @Inject constructor(
    private val database: NeuroStenographerDatabase,
    private val transcriptDao: TranscriptDao,
    private val segmentDao: SegmentDao,
    private val speakerDao: SpeakerDao,
    private val wordDao: WordDao,
    private val recordingDao: RecordingDao,
) : TranscriptRepository {

    override fun observeSegments(recordingId: Long): Flow<List<TranscriptSegment>> = combine(
        segmentDao.observeByRecording(recordingId),
        wordDao.observeByRecording(recordingId),
    ) { rows, words ->
        val bySegment = words.groupBy { it.segmentId }
        rows.map { row -> row.toDomain(bySegment[row.id].orEmpty()) }
    }

    override suspend fun replaceSegments(recordingId: Long, segments: List<TranscriptSegment>) {
        database.withTransaction {
            val now = System.currentTimeMillis()
            val transcriptId = transcriptDao.getByRecording(recordingId)?.id
                ?: transcriptDao.insert(
                    TranscriptEntity(
                        recordingId = recordingId,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        jsonSnapshot = null,
                    ),
                )

            segmentDao.deleteByTranscript(transcriptId)

            val speakersByKey = mutableMapOf<String, Long>()
            segments.forEachIndexed { index, segment ->
                val speakerKey = TranscriptJson.normalizeSpeakerId(segment.speakerId)
                val speakerId = speakersByKey.getOrPut(speakerKey) {
                    speakerDao.getByKey(recordingId, speakerKey)?.id
                        ?: speakerDao.insert(
                            SpeakerEntity(
                                recordingId = recordingId,
                                speakerKey = speakerKey,
                                displayName = null,
                                colorArgb = null,
                            ),
                        )
                }
                val segmentId = segmentDao.insert(
                    SegmentEntity(
                        transcriptId = transcriptId,
                        speakerId = speakerId,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        text = segment.text,
                        isProvisional = segment.isProvisional,
                        alignmentSource = segment.alignmentSource,
                        orderIndex = index,
                    ),
                )
                if (segment.words.isNotEmpty()) {
                    wordDao.insertAll(
                        segment.words.map { word ->
                            WordEntity(
                                segmentId = segmentId,
                                text = word.text,
                                startMs = word.startMs,
                                endMs = word.endMs,
                                confidence = word.confidence,
                                alignmentSource = word.alignmentSource,
                            )
                        },
                    )
                }
            }

            val json = TranscriptJson.encode(
                segments.map { it.copy(speakerId = TranscriptJson.normalizeSpeakerId(it.speakerId)) },
            )
            transcriptDao.updateSnapshot(transcriptId, json, now)
            recordingDao.updateSpeakerCount(
                recordingId,
                speakersByKey.keys.count { it != UNKNOWN_SPEAKER },
            )
        }
    }

    override suspend fun getTranscriptJson(recordingId: Long): String? {
        val transcript = transcriptDao.getByRecording(recordingId)
        if (transcript?.jsonSnapshot != null) return transcript.jsonSnapshot
        val rows = segmentDao.getByRecording(recordingId)
        if (rows.isEmpty()) return null
        return TranscriptJson.encode(rows.map { row -> row.toDomain(emptyList()) })
    }

    private companion object {
        const val UNKNOWN_SPEAKER = "unknown"
    }
}

private fun SegmentRow.toDomain(words: List<WordEntity>): TranscriptSegment = TranscriptSegment(
    id = id,
    speakerId = speakerKey ?: TranscriptJson.normalizeSpeakerId(null),
    startMs = startMs,
    endMs = endMs,
    text = text,
    isProvisional = isProvisional,
    alignmentSource = alignmentSource,
    words = words.map { word ->
        WordTimestamp(
            text = word.text,
            startMs = word.startMs,
            endMs = word.endMs,
            confidence = word.confidence,
            alignmentSource = word.alignmentSource,
        )
    },
)
