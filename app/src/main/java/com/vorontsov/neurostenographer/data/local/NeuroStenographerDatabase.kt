package com.vorontsov.neurostenographer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.vorontsov.neurostenographer.data.local.dao.RecordingDao
import com.vorontsov.neurostenographer.data.local.dao.SegmentDao
import com.vorontsov.neurostenographer.data.local.dao.SpeakerDao
import com.vorontsov.neurostenographer.data.local.dao.TranscriptDao
import com.vorontsov.neurostenographer.data.local.dao.WordDao
import com.vorontsov.neurostenographer.data.local.entity.RecordingEntity
import com.vorontsov.neurostenographer.data.local.entity.SegmentEntity
import com.vorontsov.neurostenographer.data.local.entity.SpeakerEntity
import com.vorontsov.neurostenographer.data.local.entity.TranscriptEntity
import com.vorontsov.neurostenographer.data.local.entity.WordEntity

@Database(
    entities = [
        RecordingEntity::class,
        TranscriptEntity::class,
        SpeakerEntity::class,
        SegmentEntity::class,
        WordEntity::class,
    ],
    version = NeuroStenographerDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NeuroStenographerDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun speakerDao(): SpeakerDao
    abstract fun segmentDao(): SegmentDao
    abstract fun wordDao(): WordDao

    companion object {
        const val VERSION = 1
        const val NAME = "neurostenographer.db"

        /**
         * Intentionally empty for schema v1. Every future schema change must add an
         * explicit migration here; destructive fallback is not allowed in release.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
