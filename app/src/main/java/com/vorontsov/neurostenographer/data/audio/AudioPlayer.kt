package com.vorontsov.neurostenographer.data.audio

import android.media.MediaPlayer
import com.vorontsov.neurostenographer.di.ApplicationScope
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.model.PlaybackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin MediaPlayer wrapper used by the transcript screen for playback and
 * "jump to segment" behaviour. Preparation never runs on the main thread.
 *
 * Loads and releases carry a token so a slow preparation from a screen that was
 * already left can never replace the player of the next screen.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    @Volatile
    private var player: MediaPlayer? = null
    private var tickerJob: Job? = null
    private var loadToken: Long = 0

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun load(path: String) {
        val token = ++loadToken
        scope.launch {
            _state.value = PlaybackState()
            val prepared = runCatching {
                withContext(ioDispatcher) {
                    MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                    }
                }
            }

            if (token != loadToken) {
                prepared.getOrNull()?.let { stale -> runCatching { stale.release() } }
                return@launch
            }

            stopTicker()
            releasePlayer()

            prepared.onSuccess { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setOnCompletionListener {
                    stopTicker()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        positionMs = mediaPlayer.duration.toLong().coerceAtLeast(0),
                    )
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        errorMessage = "Playback failed",
                    )
                    true
                }
                _state.value = PlaybackState(
                    isPrepared = true,
                    durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0),
                )
            }.onFailure { throwable ->
                _state.value = PlaybackState(errorMessage = throwable.message ?: "Could not open audio")
            }
        }
    }

    fun togglePlayback() {
        val mediaPlayer = player ?: return
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            stopTicker()
            _state.value = _state.value.copy(
                isPlaying = false,
                positionMs = mediaPlayer.currentPosition.toLong(),
            )
        } else {
            mediaPlayer.start()
            _state.value = _state.value.copy(isPlaying = true)
            startTicker()
        }
    }

    fun seekTo(positionMs: Long, play: Boolean = false) {
        val mediaPlayer = player ?: return
        mediaPlayer.seekTo(positionMs.coerceAtLeast(0).toInt())
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0))
        if (play && !mediaPlayer.isPlaying) {
            mediaPlayer.start()
            _state.value = _state.value.copy(isPlaying = true)
            startTicker()
        }
    }

    fun pause() {
        val mediaPlayer = player ?: return
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            stopTicker()
            _state.value = _state.value.copy(
                isPlaying = false,
                positionMs = mediaPlayer.currentPosition.toLong(),
            )
        }
    }

    /**
     * Invalidates any in-flight preparation synchronously, so a new screen can
     * call [load] immediately after the previous screen is destroyed.
     */
    fun release() {
        loadToken++
        stopTicker()
        releasePlayer()
        _state.value = PlaybackState()
    }

    private fun releasePlayer() {
        val current = player
        player = null
        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    private fun startTicker() {
        stopTicker()
        tickerJob = scope.launch {
            while (true) {
                val mediaPlayer = player ?: break
                val isPlaying = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
                if (!isPlaying) break
                _state.value = _state.value.copy(
                    positionMs = mediaPlayer.currentPosition.toLong(),
                )
                delay(TICKER_INTERVAL_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private companion object {
        const val TICKER_INTERVAL_MS = 200L
    }
}
