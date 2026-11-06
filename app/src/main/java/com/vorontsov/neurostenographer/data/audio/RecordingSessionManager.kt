package com.vorontsov.neurostenographer.data.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vorontsov.neurostenographer.data.file.AudioFileStore
import com.vorontsov.neurostenographer.di.ApplicationScope
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.model.CaptureStatus
import com.vorontsov.neurostenographer.domain.model.RecordingSessionState
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: AudioRecorder,
    private val recordingRepository: RecordingRepository,
    private val audioFileStore: AudioFileStore,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(RecordingSessionState())
    val state: StateFlow<RecordingSessionState> = _state.asStateFlow()

    private val _finishedRecording = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val finishedRecording: SharedFlow<Long> = _finishedRecording.asSharedFlow()

    private var startedAtRealtime: Long = 0
    private var accumulatedMs: Long = 0
    private var timerJob: Job? = null
    private var recordingFile: File? = null

    init {
        recorder.onAmplitude = { amplitude ->
            _state.update { current ->
                current.copy(amplitudes = (current.amplitudes + amplitude).takeLast(WAVEFORM_POINTS))
            }
        }
        recorder.onError = { throwable ->
            scope.launch { handleRecorderFailure(throwable) }
        }
    }

    suspend fun start(): Result<Long> = mutex.withLock {
        if (_state.value.status == CaptureStatus.RECORDING || _state.value.status == CaptureStatus.PAUSED) {
            return@withLock Result.failure(IllegalStateException("A recording is already in progress"))
        }
        if (!hasMicrophonePermission()) {
            return@withLock Result.failure(SecurityException("Microphone permission is required"))
        }
        return@withLock withContext(ioDispatcher) {
            runCatching {
                val file = audioFileStore.createRecordingFile()
                val title = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
                val recordingId = recordingRepository.createRecording(
                    title = title,
                    filePath = file.absolutePath,
                    originalPath = null,
                    source = RecordingSource.MICROPHONE,
                )
                try {
                    recorder.start(file)
                } catch (throwable: Throwable) {
                    recordingRepository.finalizeRecording(
                        recordingId = recordingId,
                        durationMs = 0,
                        status = RecordingStatus.FAILED,
                    )
                    runCatching { file.delete() }
                    throw throwable
                }
                recordingFile = file
                accumulatedMs = 0
                startedAtRealtime = SystemClock.elapsedRealtime()
                _state.value = RecordingSessionState(
                    status = CaptureStatus.RECORDING,
                    recordingId = recordingId,
                    elapsedMs = 0,
                    amplitudes = emptyList(),
                )
                startTimer()
                startForegroundService()
                recordingId
            }
        }
    }

    suspend fun pause() = mutex.withLock {
        if (_state.value.status != CaptureStatus.RECORDING) return@withLock
        withContext(ioDispatcher) {
            accumulatedMs += SystemClock.elapsedRealtime() - startedAtRealtime
            recorder.pause()
        }
        timerJob?.cancel()
        timerJob = null
        _state.update {
            it.copy(status = CaptureStatus.PAUSED, elapsedMs = accumulatedMs)
        }
    }

    suspend fun resume() = mutex.withLock {
        if (_state.value.status != CaptureStatus.PAUSED) return@withLock
        withContext(ioDispatcher) {
            startedAtRealtime = SystemClock.elapsedRealtime()
            recorder.resume()
        }
        _state.update { it.copy(status = CaptureStatus.RECORDING) }
        startTimer()
    }

    fun dismissError() {
        if (_state.value.status == CaptureStatus.ERROR) {
            _state.value = RecordingSessionState()
        }
    }

    suspend fun stop(): Result<Long> = mutex.withLock {        val current = _state.value
        if (current.status != CaptureStatus.RECORDING && current.status != CaptureStatus.PAUSED) {
            return@withLock Result.failure(IllegalStateException("No active recording"))
        }
        timerJob?.cancel()
        timerJob = null
        if (current.status == CaptureStatus.RECORDING) {
            accumulatedMs += SystemClock.elapsedRealtime() - startedAtRealtime
        }
        _state.update { it.copy(status = CaptureStatus.STOPPING, elapsedMs = accumulatedMs) }

        val recordingId = current.recordingId
        return@withLock withContext(ioDispatcher) {
            runCatching {
                val durationMs = recorder.stop().takeIf { it > 0 } ?: accumulatedMs
                if (recordingId != null) {
                    recordingRepository.finalizeRecording(
                        recordingId = recordingId,
                        durationMs = durationMs,
                        status = RecordingStatus.RECORDED,
                    )
                }
                recordingFile = null
                _state.value = RecordingSessionState()
                stopForegroundService()
                if (recordingId != null) _finishedRecording.emit(recordingId)
                durationMs
            }.onFailure { throwable ->
                if (recordingId != null) {
                    recordingRepository.updateStatus(
                        recordingId,
                        RecordingStatus.FAILED,
                        throwable.message,
                    )
                }
                _state.value = RecordingSessionState(
                    status = CaptureStatus.ERROR,
                    errorMessage = throwable.message ?: "Could not finish the recording",
                )
                stopForegroundService()
            }
        }
    }

    private suspend fun handleRecorderFailure(throwable: Throwable) {
        mutex.withLock {
            val recordingId = _state.value.recordingId
            timerJob?.cancel()
            timerJob = null
            _state.value = RecordingSessionState(
                status = CaptureStatus.ERROR,
                recordingId = recordingId,
                errorMessage = throwable.message ?: "Audio capture failed",
            )
            if (recordingId != null) {
                recordingRepository.updateStatus(recordingId, RecordingStatus.FAILED, throwable.message)
            }
            stopForegroundService()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - startedAtRealtime)
                _state.update { it.copy(elapsedMs = elapsed) }
                delay(TIMER_INTERVAL_MS)
            }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java),
            )
        }
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
    }

    private companion object {
        const val WAVEFORM_POINTS = 64
        const val TIMER_INTERVAL_MS = 200L
    }
}
