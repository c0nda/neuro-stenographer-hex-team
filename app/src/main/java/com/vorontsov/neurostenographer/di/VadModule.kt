package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.vad.SileroVadEngineFactory
import com.vorontsov.neurostenographer.data.vad.VadModelStore
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.model.ModelRepository
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.domain.vad.VadSpeechPlanner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marker for the VAD model's [ModelRepository]; the ASR model binds the same interface. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VadModel

@Module
@InstallIn(SingletonComponent::class)
abstract class VadBindingsModule {

    @Binds
    @Singleton
    @VadModel
    abstract fun bindVadModelRepository(impl: VadModelStore): ModelRepository

    @Binds
    @Singleton
    abstract fun bindVadEngineFactory(impl: SileroVadEngineFactory): VadEngineFactory
}

@Module
@InstallIn(SingletonComponent::class)
object VadModule {

    @Provides
    @Singleton
    fun provideVadSpeechPlanner(chunkPlanner: ChunkPlanner): VadSpeechPlanner =
        VadSpeechPlanner(chunkPlanner)
}
