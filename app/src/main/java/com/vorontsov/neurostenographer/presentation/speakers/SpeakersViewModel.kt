package com.vorontsov.neurostenographer.presentation.speakers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.SpeakerRepository
import com.vorontsov.neurostenographer.presentation.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SpeakerEvent {
    data object Renamed : SpeakerEvent
    data object RenameFailed : SpeakerEvent
}

@HiltViewModel
class SpeakersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    recordingRepository: RecordingRepository,
    private val speakerRepository: SpeakerRepository,
) : ViewModel() {

    val recordingId: Long = checkNotNull(
        savedStateHandle.get<Long>(Destinations.RECORDING_ID_ARG),
    ) { "recordingId navigation argument is required" }

    val recording: StateFlow<Recording?> = recordingRepository
        .observeRecording(recordingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val speakers: StateFlow<List<Speaker>> = speakerRepository
        .observeSpeakers(recordingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<SpeakerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SpeakerEvent> = _events.asSharedFlow()

    fun rename(speakerId: Long, displayName: String?) {
        viewModelScope.launch {
            runCatching { speakerRepository.renameSpeaker(speakerId, displayName) }
                .onSuccess { _events.emit(SpeakerEvent.Renamed) }
                .onFailure { _events.emit(SpeakerEvent.RenameFailed) }
        }
    }
}
