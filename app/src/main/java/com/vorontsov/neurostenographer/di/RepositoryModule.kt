package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.repository.RecordingRepositoryImpl
import com.vorontsov.neurostenographer.data.repository.SettingsRepositoryImpl
import com.vorontsov.neurostenographer.data.repository.SpeakerRepositoryImpl
import com.vorontsov.neurostenographer.data.repository.TranscriptRepositoryImpl
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.SettingsRepository
import com.vorontsov.neurostenographer.domain.repository.SpeakerRepository
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindRecordingRepository(impl: RecordingRepositoryImpl): RecordingRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptRepository(impl: TranscriptRepositoryImpl): TranscriptRepository

    @Binds
    @Singleton
    abstract fun bindSpeakerRepository(impl: SpeakerRepositoryImpl): SpeakerRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
