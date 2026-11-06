package com.vorontsov.neurostenographer.data.local

import androidx.room.TypeConverter
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus

class Converters {
    @TypeConverter
    fun recordingStatusToString(value: RecordingStatus): String = value.name

    @TypeConverter
    fun stringToRecordingStatus(value: String): RecordingStatus =
        runCatching { RecordingStatus.valueOf(value) }.getOrDefault(RecordingStatus.FAILED)

    @TypeConverter
    fun recordingSourceToString(value: RecordingSource): String = value.name

    @TypeConverter
    fun stringToRecordingSource(value: String): RecordingSource =
        runCatching { RecordingSource.valueOf(value) }.getOrDefault(RecordingSource.MICROPHONE)

    @TypeConverter
    fun alignmentSourceToString(value: AlignmentSource): String = value.name

    @TypeConverter
    fun stringToAlignmentSource(value: String): AlignmentSource =
        runCatching { AlignmentSource.valueOf(value) }.getOrDefault(AlignmentSource.NONE)
}
