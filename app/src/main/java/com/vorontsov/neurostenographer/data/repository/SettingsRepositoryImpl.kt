package com.vorontsov.neurostenographer.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
                diarizationThreshold = (preferences[DIARIZATION_THRESHOLD]
                    ?: AppSettings.DEFAULT_DIARIZATION_THRESHOLD).coerceIn(0.1f, 0.9f),
                diarizationMinOffMs = (preferences[DIARIZATION_MIN_OFF]
                    ?: AppSettings.DEFAULT_DIARIZATION_MIN_OFF_MS).coerceIn(100L, 3_000L),
                asrMaxPauseMs = (preferences[ASR_MAX_PAUSE]
                    ?: AppSettings.DEFAULT_ASR_MAX_PAUSE_MS).coerceIn(200L, 3_000L),
            )
        }

    override suspend fun setProcessingProfile(profile: ProcessingProfile) {
        context.settingsDataStore.edit { it[PROCESSING_PROFILE] = profile.name }
    }

    override suspend fun setKeepScreenOnWhileRecording(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setDiarizationThreshold(threshold: Float) {
        context.settingsDataStore.edit { it[DIARIZATION_THRESHOLD] = threshold.coerceIn(0.1f, 0.9f) }
    }

    override suspend fun setDiarizationMinOffMs(minOffMs: Long) {
        context.settingsDataStore.edit { it[DIARIZATION_MIN_OFF] = minOffMs.coerceIn(100L, 3_000L) }
    }

    override suspend fun setAsrMaxPauseMs(maxPauseMs: Long) {
        context.settingsDataStore.edit { it[ASR_MAX_PAUSE] = maxPauseMs.coerceIn(200L, 3_000L) }
    }

    private companion object {
        val PROCESSING_PROFILE = stringPreferencesKey("processing_profile")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DIARIZATION_THRESHOLD = floatPreferencesKey("diarization_threshold")
        val DIARIZATION_MIN_OFF = longPreferencesKey("diarization_min_off_ms")
        val ASR_MAX_PAUSE = longPreferencesKey("asr_max_pause_ms")
    }
}
