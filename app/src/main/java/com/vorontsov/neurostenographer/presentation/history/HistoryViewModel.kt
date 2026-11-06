package com.vorontsov.neurostenographer.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vorontsov.neurostenographer.data.transcription.StartResult
import com.vorontsov.neurostenographer.data.transcription.TranscriptionManager
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HistoryEvent {
    data object Renamed : HistoryEvent
    data object RenameFailed : HistoryEvent
    data object Deleted : HistoryEvent
    data object DeleteFailed : HistoryEvent
    data object ReprocessStarted : HistoryEvent
    data object ReprocessBusy : HistoryEvent
    data object ReprocessUnavailable : HistoryEvent
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val transcriptionManager: TranscriptionManager,
    private val modelRepository: AsrModelRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val recordings: StateFlow<List<Recording>> = _query
        .flatMapLatest { query -> recordingRepository.observeRecordings(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<HistoryEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HistoryEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { modelRepository.refresh() }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun rename(recordingId: Long, title: String) {
        viewModelScope.launch {
            runCatching { recordingRepository.renameRecording(recordingId, title) }
                .onSuccess { _events.emit(HistoryEvent.Renamed) }
                .onFailure { _events.emit(HistoryEvent.RenameFailed) }
        }
    }

    fun delete(recordingId: Long) {
        viewModelScope.launch {
            runCatching { recordingRepository.deleteRecording(recordingId) }
                .onSuccess { _events.emit(HistoryEvent.Deleted) }
                .onFailure { _events.emit(HistoryEvent.DeleteFailed) }
        }
    }

    fun reprocess(recordingId: Long) {
        viewModelScope.launch {
            if (!modelRepository.status.value.isReady) {
                _events.emit(HistoryEvent.ReprocessUnavailable)
                return@launch
            }
            when (transcriptionManager.start(recordingId)) {
                StartResult.BUSY -> _events.emit(HistoryEvent.ReprocessBusy)
                StartResult.STARTED -> _events.emit(HistoryEvent.ReprocessStarted)
            }
        }
    }
}
