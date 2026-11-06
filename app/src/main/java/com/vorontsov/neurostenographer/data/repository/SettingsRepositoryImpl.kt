package com.vorontsov.neurostenographer.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vorontsov.neurostenographer.domain.model.AppSettings
import com.vorontsov.neurostenographer.domain.model.ProcessingProfile
import com.vorontsov.neurostenographer.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw throwable
        }
        .map { preferences ->
            AppSettings(
                processingProfile = preferences[PROCESSING_PROFILE]
                    ?.let { runCatching { ProcessingProfile.valueOf(it) }.getOrNull() }
                    ?: ProcessingProfile.BALANCED,
                keepScreenOnWhileRecording = preferences[KEEP_SCREEN_ON] ?: true,
            )
        }

    override suspend fun setProcessingProfile(profile: ProcessingProfile) {
        context.settingsDataStore.edit { it[PROCESSING_PROFILE] = profile.name }
    }

    override suspend fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    private companion object {
        val PROCESSING_PROFILE = stringPreferencesKey("processing_profile")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
