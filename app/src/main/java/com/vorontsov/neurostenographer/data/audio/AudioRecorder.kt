package com.vorontsov.neurostenographer.data.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Owns a single AudioRecord instance. All blocking work runs on a dedicated
 * high-priority thread; callers must not call this from the main thread.
 */
class AudioRecorder @Inject constructor() {

    private val lock = Any()
    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var writer: WavFileWriter? = null
    private var lastDurationMs: Long = 0

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    var onAmplitude: ((Float) -> Unit)? = null

    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    val isRunning: Boolean get() = running

    fun start(file: File) {
        synchronized(lock) {
            check(!running) { "AudioRecorder is already running" }
            writer = WavFileWriter(file)
            lastDurationMs = 0
            running = true
            paused = false
            thread = Thread({ runLoop() }, "neurostenographer-audio").also { it.start() }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!running || paused) return
            paused = true
            runCatching { audioRecord?.stop() }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (!running || !paused) return
            paused = false
            runCatching { audioRecord?.startRecording() }
        }
    }

    fun stop(): Long {
        val worker: Thread?
        synchronized(lock) {
            if (!running) return lastDurationMs
            running = false
            runCatching { audioRecord?.stop() }
            worker = thread
            thread = null
        }
        worker?.join(3_000)
        synchronized(lock) {
            paused = false
            return lastDurationMs
        }
    }

    @SuppressLint("MissingPermission") // Checked by RecordingSessionManager; failures are reported via onError.
    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var created: AudioRecord? = null
        val wav = synchronized(lock) { writer }
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioRecord min buffer unavailable: $minBuffer" }
            val bufferBytes = maxOf(minBuffer * 2, BUFFER_BYTES)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            created = record
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord could not be initialized (state=${record.state})"
            }
            synchronized(lock) { audioRecord = record }
            record.startRecording()

            val samples = ShortArray(READ_SAMPLES)
            while (running) {
                if (paused) {
                    Thread.sleep(PAUSE_POLL_MS)
                    continue
                }
                val read = try {
                    record.read(samples, 0, samples.size)
                } catch (throwable: Throwable) {
                    if (!running) break
                    if (paused) {
                        Thread.sleep(PAUSE_POLL_MS)
                        continue
                    }
                    throw throwable
                }
                if (read > 0) {
                    wav?.write(samples, read)
                    onAmplitude?.invoke(calculateAmplitude(samples, read))
                } else if (read < 0 && running) {
                    // Error codes are expected after stop()/pause() races; re-check state.
                    Thread.sleep(ERROR_POLL_MS)
                }
            }
        } catch (throwable: Throwable) {
            if (running) onError?.invoke(throwable)
        } finally {
            runCatching { created?.stop() }
            runCatching { created?.release() }
            val duration = wav?.durationMs() ?: 0
            runCatching { wav?.close() }
            synchronized(lock) {
                audioRecord = null
                writer = null
                lastDurationMs = duration
                running = false
                paused = false
            }
        }
    }

    private fun calculateAmplitude(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index].toDouble()
            sum += value * value
        }
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return rms.coerceIn(0.0, 1.0).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val READ_SAMPLES = 2_048
        const val BUFFER_BYTES = 16_384
        const val PAUSE_POLL_MS = 30L
        const val ERROR_POLL_MS = 10L
    }
}
