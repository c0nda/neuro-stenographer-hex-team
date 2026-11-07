package com.vorontsov.neurostenographer.presentation.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vorontsov.neurostenographer.data.audio.AudioImporter
import com.vorontsov.neurostenographer.data.audio.RecordingSessionManager
import com.vorontsov.neurostenographer.data.file.BundledModelProvisioner
import com.vorontsov.neurostenographer.di.VadModel
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.diarization.DiarizationModelRepository
import com.vorontsov.neurostenographer.domain.model.AppSettings
import com.vorontsov.neurostenographer.domain.model.ModelRepository
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.RecordingSessionState
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Importing : ImportUiState
    data class Success(val recordingId: Long) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

sealed interface ModelEvent {
    data object Imported : ModelEvent
    data class ImportFailed(val message: String) : ModelEvent
    data object VadImported : ModelEvent
    data class VadImportFailed(val message: String) : ModelEvent
    data object DiarizationSegmentationImported : ModelEvent
    data object DiarizationEmbeddingImported : ModelEvent
    data class DiarizationImportFailed(val message: String) : ModelEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    recordingRepository: RecordingRepository,
    private val audioImporter: AudioImporter,
    recordingSessionManager: RecordingSessionManager,
    private val modelRepository: AsrModelRepository,
    @VadModel private val vadModelRepository: ModelRepository,
    private val diarizationModelRepository: DiarizationModelRepository,
    private val bundledModelProvisioner: BundledModelProvisioner,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setDiarizationThreshold(threshold: Float) {
        viewModelScope.launch { settingsRepository.setDiarizationThreshold(threshold) }
    }

    fun setDiarizationMinOffMs(minOffMs: Long) {
        viewModelScope.launch { settingsRepository.setDiarizationMinOffMs(minOffMs) }
    }

    fun setAsrMaxPauseMs(maxPauseMs: Long) {
        viewModelScope.launch { settingsRepository.setAsrMaxPauseMs(maxPauseMs) }
    }

    val recordings: StateFlow<List<Recording>> = recordingRepository
        .observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val session: StateFlow<RecordingSessionState> = recordingSessionManager.state

    val modelStatus: StateFlow<ModelStatus> = modelRepository.status

    val vadModelStatus: StateFlow<ModelStatus> = vadModelRepository.status

    val diarizationModelStatus: StateFlow<ModelStatus> = diarizationModelRepository.status

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _modelEvents = MutableSharedFlow<ModelEvent>(extraBufferCapacity = 1)
    val modelEvents: SharedFlow<ModelEvent> = _modelEvents.asSharedFlow()

    init {
        // Install bundled models if the APK shipped them, then verify the
        // installed files once per process before enabling transcription.
        viewModelScope.launch {
            bundledModelProvisioner.provisionBundledModels()
            modelRepository.refresh()
            vadModelRepository.refresh()
            diarizationModelRepository.refresh()
        }
    }

    fun import(uri: Uri) {
        if (_importState.value == ImportUiState.Importing) return
        _importState.value = ImportUiState.Importing
        viewModelScope.launch {
            audioImporter.import(uri)
                .onSuccess { recordingId -> _importState.value = ImportUiState.Success(recordingId) }
                .onFailure { throwable ->
                    _importState.value = ImportUiState.Error(
                        throwable.message ?: "The selected file could not be imported",
                    )
                }
        }
    }

    fun importModel(uri: Uri) {
        if (modelStatus.value.isBusy) return
        viewModelScope.launch {
            val result = modelRepository.importModel {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file could not be opened")
            }
            result
                .onSuccess { _modelEvents.emit(ModelEvent.Imported) }
                .onFailure { throwable ->
                    _modelEvents.emit(
                        ModelEvent.ImportFailed(
                            throwable.message ?: "The selected model file was rejected",
                        ),
                    )
                }
        }
    }

    fun importVadModel(uri: Uri) {
        if (vadModelStatus.value.isBusy) return
        viewModelScope.launch {
            val result = vadModelRepository.importModel {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file could not be opened")
            }
            result
                .onSuccess { _modelEvents.emit(ModelEvent.VadImported) }
                .onFailure { throwable ->
                    _modelEvents.emit(
                        ModelEvent.VadImportFailed(
                            throwable.message ?: "The selected model file was rejected",
                        ),
                    )
                }
        }
    }

    fun importDiarizationSegmentationModel(uri: Uri) {
        if (diarizationModelStatus.value.isBusy) return
        viewModelScope.launch {
            diarizationModelRepository.importSegmentationModel {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file could not be opened")
            }
                .onSuccess { _modelEvents.emit(ModelEvent.DiarizationSegmentationImported) }
                .onFailure { throwable ->
                    _modelEvents.emit(
                        ModelEvent.DiarizationImportFailed(
                            throwable.message ?: "The selected model file was rejected",
                        ),
                    )
                }
        }
    }

    fun importDiarizationEmbeddingModel(uri: Uri) {
        if (diarizationModelStatus.value.isBusy) return
        viewModelScope.launch {
            diarizationModelRepository.importEmbeddingModel {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("The selected file could not be opened")
            }
                .onSuccess { _modelEvents.emit(ModelEvent.DiarizationEmbeddingImported) }
                .onFailure { throwable ->
                    _modelEvents.emit(
                        ModelEvent.DiarizationImportFailed(
                            throwable.message ?: "The selected model file was rejected",
                        ),
                    )
                }
        }
    }

    fun consumeImportState() {
        if (_importState.value !is ImportUiState.Importing) {
            _importState.value = ImportUiState.Idle
        }
    }
}
