package com.vorontsov.neurostenographer.presentation.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vorontsov.neurostenographer.data.audio.RecordingSessionManager
import com.vorontsov.neurostenographer.domain.model.RecordingSessionState
import com.vorontsov.neurostenographer.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val sessionManager: RecordingSessionManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<RecordingSessionState> = sessionManager.state
    val finishedRecording: SharedFlow<Long> = sessionManager.finishedRecording

    val keepScreenOn: StateFlow<Boolean> = settingsRepository.settings
        .map { it.keepScreenOnWhileRecording }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun start() {
        viewModelScope.launch {
            sessionManager.start().onFailure { throwable ->
                _startError.value = throwable.message ?: "Could not start recording"
            }
        }
    }

    fun pause() {
        viewModelScope.launch { sessionManager.pause() }
    }

    fun resume() {
        viewModelScope.launch { sessionManager.resume() }
    }

    fun stop() {
        viewModelScope.launch {
            sessionManager.stop().onFailure { throwable ->
                _messages.emit(throwable.message ?: "Could not finish the recording")
            }
        }
    }

    fun dismissError() {
        sessionManager.dismissError()
        _startError.value = null
    }

    fun clearStartError() {
        _startError.value = null
    }
}
