package com.vorontsov.neurostenographer.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus

@Entity(
    tableName = "recordings",
    indices = [
        Index("createdAtEpochMs"),
        Index("status"),
    ],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["recordingId"], unique = true)],
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val jsonSnapshot: String?,
)

@Entity(
    tableName = "speakers",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["recordingId", "speakerKey"], unique = true)],
)
data class SpeakerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val speakerKey: String,
    val displayName: String?,
    val colorArgb: Long?,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["transcriptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SpeakerEntity::class,
            parentColumns = ["id"],
            childColumns = ["speakerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("transcriptId"),
        Index("speakerId"),
        Index("startMs"),
    ],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcriptId: Long,
    val speakerId: Long?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isProvisional: Boolean,
    val alignmentSource: AlignmentSource,
    val orderIndex: Int,
)

@Entity(
    tableName = "words",
    foreignKeys = [
        ForeignKey(
            entity = SegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("segmentId")],
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float?,
    val alignmentSource: AlignmentSource,
)

/**
 * Projection used by the transcript screen: speaker key is resolved with a LEFT JOIN
 * so a segment without an assigned speaker is still returned and serialized as "unknown".
 */
data class SegmentRow(
    val id: Long,
    val transcriptId: Long,
    val speakerId: Long?,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isProvisional: Boolean,
    val alignmentSource: AlignmentSource,
    val speakerKey: String?,
)
