package com.vorontsov.neurostenographer.di

import com.vorontsov.neurostenographer.data.punctuation.BundledPunctuationRestorer
import com.vorontsov.neurostenographer.domain.punctuation.PunctuationRestorer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PunctuationModule {

    @Binds
    @Singleton
    abstract fun bindPunctuationRestorer(impl: BundledPunctuationRestorer): PunctuationRestorer
}
