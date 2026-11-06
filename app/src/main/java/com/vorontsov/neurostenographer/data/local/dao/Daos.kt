package com.vorontsov.neurostenographer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vorontsov.neurostenographer.data.local.entity.RecordingEntity
import com.vorontsov.neurostenographer.data.local.entity.SegmentEntity
import com.vorontsov.neurostenographer.data.local.entity.SegmentRow
import com.vorontsov.neurostenographer.data.local.entity.SpeakerEntity
import com.vorontsov.neurostenographer.data.local.entity.TranscriptEntity
import com.vorontsov.neurostenographer.data.local.entity.WordEntity
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :recordingId")
    fun observeById(recordingId: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :recordingId")
    suspend fun getById(recordingId: Long): RecordingEntity?

    @Insert
    suspend fun insert(entity: RecordingEntity): Long

    @Query("UPDATE recordings SET durationMs = :durationMs, status = :status WHERE id = :recordingId")
    suspend fun finalize(
        recordingId: Long,
        durationMs: Long,
        status: RecordingStatus,
    )

    @Query("UPDATE recordings SET status = :status, errorMessage = :errorMessage WHERE id = :recordingId")
    suspend fun updateStatus(
        recordingId: Long,
        status: RecordingStatus,
        errorMessage: String?,
    )

    @Query("UPDATE recordings SET speakerCount = :speakerCount WHERE id = :recordingId")
    suspend fun updateSpeakerCount(recordingId: Long, speakerCount: Int)

    @Query("UPDATE recordings SET title = :title WHERE id = :recordingId")
    suspend fun rename(recordingId: Long, title: String)

    @Query("DELETE FROM recordings WHERE id = :recordingId")
    suspend fun delete(recordingId: Long)
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId LIMIT 1")
    suspend fun getByRecording(recordingId: Long): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId LIMIT 1")
    fun observeByRecording(recordingId: Long): Flow<TranscriptEntity?>

    @Insert
    suspend fun insert(entity: TranscriptEntity): Long

    @Query("UPDATE transcripts SET jsonSnapshot = :json, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :transcriptId")
    suspend fun updateSnapshot(transcriptId: Long, json: String?, updatedAtEpochMs: Long)
}

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY id")
    fun observeByRecording(recordingId: Long): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId ORDER BY id")
    suspend fun getByRecording(recordingId: Long): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE recordingId = :recordingId AND speakerKey = :speakerKey LIMIT 1")
    suspend fun getByKey(recordingId: Long, speakerKey: String): SpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SpeakerEntity): Long

    @Update
    suspend fun update(entity: SpeakerEntity)

    @Query("SELECT * FROM speakers WHERE id = :speakerId")
    suspend fun getById(speakerId: Long): SpeakerEntity?

    @Query("UPDATE segments SET speakerId = :targetSpeakerId WHERE speakerId = :sourceSpeakerId")
    suspend fun reassignSegments(sourceSpeakerId: Long, targetSpeakerId: Long)

    @Query("DELETE FROM speakers WHERE id = :speakerId")
    suspend fun delete(speakerId: Long)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE transcriptId = :transcriptId ORDER BY startMs, endMs, id")
    suspend fun getByTranscript(transcriptId: Long): List<SegmentEntity>

    @Query(
        """
        SELECT s.id, s.transcriptId, s.speakerId, s.startMs, s.endMs, s.text,
               s.isProvisional, s.alignmentSource, sp.speakerKey AS speakerKey
        FROM segments s
        INNER JOIN transcripts t ON t.id = s.transcriptId
        LEFT JOIN speakers sp ON sp.id = s.speakerId
        WHERE t.recordingId = :recordingId
        ORDER BY s.startMs, s.endMs, s.id
        """,
    )
    fun observeByRecording(recordingId: Long): Flow<List<SegmentRow>>

    @Query(
        """
        SELECT s.id, s.transcriptId, s.speakerId, s.startMs, s.endMs, s.text,
               s.isProvisional, s.alignmentSource, sp.speakerKey AS speakerKey
        FROM segments s
        INNER JOIN transcripts t ON t.id = s.transcriptId
        LEFT JOIN speakers sp ON sp.id = s.speakerId
        WHERE t.recordingId = :recordingId
        ORDER BY s.startMs, s.endMs, s.id
        """,
    )
    suspend fun getByRecording(recordingId: Long): List<SegmentRow>

    @Insert
    suspend fun insert(entity: SegmentEntity): Long

    @Query("DELETE FROM segments WHERE transcriptId = :transcriptId")
    suspend fun deleteByTranscript(transcriptId: Long)

    @Query("UPDATE segments SET speakerId = :speakerId WHERE id = :segmentId")
    suspend fun updateSpeaker(segmentId: Long, speakerId: Long?)
}

@Dao
interface WordDao {
    @Insert
    suspend fun insertAll(entities: List<WordEntity>)

    @Query("SELECT * FROM words WHERE segmentId = :segmentId ORDER BY startMs, id")
    suspend fun getBySegment(segmentId: Long): List<WordEntity>

    @Query(
        """
        SELECT w.* FROM words w
        INNER JOIN segments s ON s.id = w.segmentId
        INNER JOIN transcripts t ON t.id = s.transcriptId
        WHERE t.recordingId = :recordingId
        ORDER BY w.startMs, w.id
        """,
    )
    fun observeByRecording(recordingId: Long): Flow<List<WordEntity>>
}
