package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.asr.AsrModelStore
import com.vorontsov.neurostenographer.data.asr.GigaamAsrEngineFactory
import com.vorontsov.neurostenographer.data.asr.WavAudioSource
import com.vorontsov.neurostenographer.data.transcription.TranscriptionManager
import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.asr.AudioSourceFactory
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.transcription.TranscribeRecordingUseCase
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.domain.vad.VadSpeechPlanner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AsrBindingsModule {

    @Binds
    @Singleton
    abstract fun bindAsrModelRepository(impl: AsrModelStore): AsrModelRepository

    @Binds
    @Singleton
    abstract fun bindAsrEngineFactory(impl: GigaamAsrEngineFactory): AsrEngineFactory

    @Binds
    @Singleton
    abstract fun bindAudioSourceFactory(impl: WavAudioSource.Factory): AudioSourceFactory
}

@Module
@InstallIn(SingletonComponent::class)
object AsrModule {

    @Provides
    @Singleton
    fun provideChunkPlanner(): ChunkPlanner = ChunkPlanner()

    @Provides
    @Singleton
    fun provideTranscribeRecordingUseCase(
        recordingRepository: com.vorontsov.neurostenographer.domain.repository.RecordingRepository,
        transcriptRepository: com.vorontsov.neurostenographer.domain.repository.TranscriptRepository,
        engineFactory: AsrEngineFactory,
        audioSourceFactory: AudioSourceFactory,
        chunkPlanner: ChunkPlanner,
        vadEngineFactory: VadEngineFactory,
        vadSpeechPlanner: VadSpeechPlanner,
        diarizationEngineFactory: DiarizationEngineFactory,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @DefaultDispatcher cpuDispatcher: CoroutineDispatcher,
    ): TranscribeRecordingUseCase = TranscribeRecordingUseCase(
        recordingRepository = recordingRepository,
        transcriptRepository = transcriptRepository,
        engineFactory = engineFactory,
        audioSourceFactory = audioSourceFactory,
        chunkPlanner = chunkPlanner,
        vadEngineFactory = vadEngineFactory,
        vadSpeechPlanner = vadSpeechPlanner,
        diarizationEngineFactory = diarizationEngineFactory,
        ioDispatcher = ioDispatcher,
        cpuDispatcher = cpuDispatcher,
    )

    @Provides
    @Singleton
    fun provideTranscriptionManager(
        useCase: TranscribeRecordingUseCase,
        @ApplicationScope scope: CoroutineScope,
    ): TranscriptionManager = TranscriptionManager(useCase, scope)
}
