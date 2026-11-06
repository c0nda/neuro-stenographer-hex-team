package com.vorontsov.neurostenographer.data.transcription

import com.vorontsov.neurostenographer.di.ApplicationScope
import com.vorontsov.neurostenographer.domain.transcription.TranscribeRecordingUseCase
import com.vorontsov.neurostenographer.domain.transcription.TranscriptionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TranscriptionState {
    data object Idle : TranscriptionState
    data object Queued : TranscriptionState
    data class Running(val progress: Float, val stage: TranscriptionStage) : TranscriptionState
    data class Completed(val segmentCount: Int, val wordCount: Int) : TranscriptionState
    data class Failed(val message: String) : TranscriptionState
    data object Cancelled : TranscriptionState
}

enum class StartResult {
    STARTED,
    BUSY,
}

/**
 * Serializes transcription jobs (one ONNX session is shared) and exposes
 * observable progress to the UI. Jobs run in an application-scoped coroutine,
 * never on the main thread, so they survive configuration changes.
 */
@Singleton
class TranscriptionManager @Inject constructor(
    private val useCase: TranscribeRecordingUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<Long, TranscriptionState>>(emptyMap())
    val states: StateFlow<Map<Long, TranscriptionState>> = _states.asStateFlow()

    private val gate = Any()
    private var job: Job? = null

    fun observe(recordingId: Long): Flow<TranscriptionState> =
        states.map { it[recordingId] ?: TranscriptionState.Idle }.distinctUntilChanged()

    fun start(recordingId: Long): StartResult = synchronized(gate) {
        if (job?.isActive == true) return StartResult.BUSY

        _states.update { it + (recordingId to TranscriptionState.Queued) }
        job = scope.launch {
            try {
                val outcome = useCase.execute(recordingId) { fraction, stage ->
                    _states.update {
                        it + (
                            recordingId to TranscriptionState.Running(
                                progress = fraction.coerceIn(0f, 1f),
                                stage = stage,
                            )
                            )
                    }
                }
                _states.update {
                    it + (
                        recordingId to TranscriptionState.Completed(
                            segmentCount = outcome.segmentCount,
                            wordCount = outcome.wordCount,
                        )
                        )
                }
            } catch (cancelled: CancellationException) {
                _states.update { it + (recordingId to TranscriptionState.Cancelled) }
                throw cancelled
            } catch (throwable: Throwable) {
                _states.update {
                    it + (
                        recordingId to TranscriptionState.Failed(
                            throwable.message ?: "Transcription failed",
                        )
                        )
                }
            } finally {
                synchronized(gate) { job = null }
            }
        }
        StartResult.STARTED
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss(recordingId: Long) {
        _states.update { it - recordingId }
    }
}
