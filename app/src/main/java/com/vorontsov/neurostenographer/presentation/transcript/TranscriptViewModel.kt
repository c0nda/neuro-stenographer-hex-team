package com.vorontsov.neurostenographer.presentation.transcript

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vorontsov.neurostenographer.data.audio.AudioPlayer
import com.vorontsov.neurostenographer.data.export.TranscriptExporter
import com.vorontsov.neurostenographer.data.transcription.StartResult
import com.vorontsov.neurostenographer.data.transcription.TranscriptionManager
import com.vorontsov.neurostenographer.data.transcription.TranscriptionState
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import com.vorontsov.neurostenographer.domain.model.PlaybackState
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.Speaker
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.SpeakerRepository
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import com.vorontsov.neurostenographer.presentation.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranscriptUiState(
    val recording: Recording? = null,
    val segments: List<TranscriptSegment> = emptyList(),
    val speakers: List<Speaker> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val isLoading: Boolean = true,
)

sealed interface TranscriptEvent {
    data object Exported : TranscriptEvent
    data object NothingToExport : TranscriptEvent
    data object ExportFailed : TranscriptEvent
    data object TranscriptionBusy : TranscriptEvent
    data object ModelRequired : TranscriptEvent
}

@HiltViewModel
class TranscriptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    recordingRepository: RecordingRepository,
    private val transcriptRepository: TranscriptRepository,
    speakerRepository: SpeakerRepository,
    private val audioPlayer: AudioPlayer,
    private val exporter: TranscriptExporter,
    private val transcriptionManager: TranscriptionManager,
    private val modelRepository: AsrModelRepository,
) : ViewModel() {

    val recordingId: Long = checkNotNull(
        savedStateHandle.get<Long>(Destinations.RECORDING_ID_ARG),
    ) { "recordingId navigation argument is required" }

    val uiState: StateFlow<TranscriptUiState> = combine(
        recordingRepository.observeRecording(recordingId),
        transcriptRepository.observeSegments(recordingId),
        speakerRepository.observeSpeakers(recordingId),
        audioPlayer.state,
    ) { recording, segments, speakers, playback ->
        TranscriptUiState(
            recording = recording,
            segments = segments,
            speakers = speakers,
            playback = playback,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TranscriptUiState(),
    )

    private val _events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<TranscriptEvent> = _events.asSharedFlow()

    val transcription: StateFlow<TranscriptionState> = transcriptionManager
        .observe(recordingId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TranscriptionState.Idle,
        )

    val modelStatus: StateFlow<ModelStatus> = modelRepository.status

    init {
        viewModelScope.launch { modelRepository.refresh() }
        viewModelScope.launch {
            recordingRepository.getRecording(recordingId)?.let { recording ->
                audioPlayer.load(recording.filePath)
            }
        }
    }

    fun togglePlayback() {
        audioPlayer.togglePlayback()
    }

    fun seekTo(positionMs: Long, play: Boolean = false) {
        audioPlayer.seekTo(positionMs, play = play)
    }

    fun stopPlayback() {
        audioPlayer.pause()
        audioPlayer.seekTo(0)
    }

    fun playFromSegment(segment: TranscriptSegment) {
        audioPlayer.seekTo(segment.startMs, play = true)
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val json = transcriptRepository.getTranscriptJson(recordingId)
            if (json == null) {
                _events.emit(TranscriptEvent.NothingToExport)
                return@launch
            }
            exporter.export(uri, json)
                .onSuccess { _events.emit(TranscriptEvent.Exported) }
                .onFailure { _events.emit(TranscriptEvent.ExportFailed) }
        }
    }

    fun transcribe() {
        if (!modelRepository.status.value.isReady) {
            viewModelScope.launch { _events.emit(TranscriptEvent.ModelRequired) }
            return
        }
        when (transcriptionManager.start(recordingId)) {
            StartResult.BUSY -> viewModelScope.launch {
                _events.emit(TranscriptEvent.TranscriptionBusy)
            }

            StartResult.STARTED -> Unit
        }
    }

    fun cancelTranscription() {
        transcriptionManager.cancel()
    }

    fun dismissTranscription() {
        transcriptionManager.dismiss(recordingId)
    }

    override fun onCleared() {
        audioPlayer.release()
    }
}
