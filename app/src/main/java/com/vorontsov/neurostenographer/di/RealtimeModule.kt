package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.realtime.ChunkedStreamingTranscriber
import com.vorontsov.neurostenographer.domain.realtime.StreamingTranscriber
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RealtimeModule {

    @Binds
    @Singleton
    abstract fun bindStreamingTranscriber(impl: ChunkedStreamingTranscriber): StreamingTranscriber
}
