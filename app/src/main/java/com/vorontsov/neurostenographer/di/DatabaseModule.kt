package com.vorontsov.neurostenographer.di

import android.content.Context
import androidx.room.Room
import com.vorontsov.neurostenographer.data.local.NeuroStenographerDatabase
import com.vorontsov.neurostenographer.data.local.dao.RecordingDao
import com.vorontsov.neurostenographer.data.local.dao.SegmentDao
import com.vorontsov.neurostenographer.data.local.dao.SpeakerDao
import com.vorontsov.neurostenographer.data.local.dao.TranscriptDao
import com.vorontsov.neurostenographer.data.local.dao.WordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): NeuroStenographerDatabase = Room.databaseBuilder(
        context,
        NeuroStenographerDatabase::class.java,
        NeuroStenographerDatabase.NAME,
    )
        .addMigrations(*NeuroStenographerDatabase.MIGRATIONS)
        .build()

    @Provides
    fun provideRecordingDao(database: NeuroStenographerDatabase): RecordingDao = database.recordingDao()

    @Provides
    fun provideTranscriptDao(database: NeuroStenographerDatabase): TranscriptDao = database.transcriptDao()

    @Provides
    fun provideSpeakerDao(database: NeuroStenographerDatabase): SpeakerDao = database.speakerDao()

    @Provides
    fun provideSegmentDao(database: NeuroStenographerDatabase): SegmentDao = database.segmentDao()

    @Provides
    fun provideWordDao(database: NeuroStenographerDatabase): WordDao = database.wordDao()
}
