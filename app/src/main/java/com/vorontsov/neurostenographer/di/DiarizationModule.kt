package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.diarization.DiarizationModelStore
import com.vorontsov.neurostenographer.data.diarization.SherpaDiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.diarization.DiarizationModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiarizationModule {

    @Binds
    @Singleton
    abstract fun bindDiarizationModelRepository(impl: DiarizationModelStore): DiarizationModelRepository

    @Binds
    @Singleton
    abstract fun bindDiarizationEngineFactory(
        impl: SherpaDiarizationEngineFactory,
    ): DiarizationEngineFactory
}
