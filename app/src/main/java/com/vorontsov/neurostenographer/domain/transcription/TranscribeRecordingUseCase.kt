package com.vorontsov.neurostenographer.domain.transcription

import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrSegmenter
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.AudioSourceFactory
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.asr.SampleRange
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngine
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.diarization.DiarizationTurn
import com.vorontsov.neurostenographer.domain.diarization.SpeakerTurnMerger
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.AppSettings
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.SettingsRepository
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import com.vorontsov.neurostenographer.domain.vad.SpeechRegionMerger
import com.vorontsov.neurostenographer.domain.vad.VadEngine
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.domain.vad.VadSpeechPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class TranscriptionStage {
    PREPARING,
    DETECTING_SPEECH,
    RECOGNIZING,
    DIARIZING,
    SAVING,
}

data class TranscriptionOutcome(
    val segmentCount: Int,
    val wordCount: Int,
)

/**
 * Offline transcription pipeline: WAV read (VAD-gated when the Silero model
 * is installed, energy-based chunking otherwise) -> GigaAM v3 CTC ->
 * word-level grouping -> optional speaker diarization (sherpa-onnx) ->
 * normalized segments -> transactional persistence.
 *
 * Heavy-model lifecycle is strictly sequential: the 225 MB ASR session is
 * released BEFORE the diarization models load, so both are never resident
 * at the same time (Phase 1 §3.2).
 *
 * Deliberately not implemented yet, and reported honestly in the UI:
 *  - forced alignment beyond CTC frame resolution (Phase 5)
 */
class TranscribeRecordingUseCase(
    private val recordingRepository: RecordingRepository,
    private val transcriptRepository: TranscriptRepository,
    private val engineFactory: AsrEngineFactory,
    private val audioSourceFactory: AudioSourceFactory,
    private val chunkPlanner: ChunkPlanner,
    private val vadEngineFactory: VadEngineFactory?,
    private val vadSpeechPlanner: VadSpeechPlanner,
    private val diarizationEngineFactory: DiarizationEngineFactory?,
    private val ioDispatcher: CoroutineDispatcher,
    private val cpuDispatcher: CoroutineDispatcher,
    private val settingsRepository: SettingsRepository? = null,
) {

    suspend fun execute(
        recordingId: Long,
        onProgress: (fraction: Float, stage: TranscriptionStage) -> Unit,
    ): TranscriptionOutcome {
        val recording = recordingRepository.getRecording(recordingId)
            ?: throw IllegalArgumentException("Recording $recordingId does not exist")

        // Utterance-splitting pause comes from the user tuning, if wired.
        val maxPauseMs = settingsRepository?.settings?.first()?.asrMaxPauseMs
            ?: AppSettings.DEFAULT_ASR_MAX_PAUSE_MS

        recordingRepository.updateStatus(recordingId, RecordingStatus.PROCESSING, null)
        try {
            onProgress(0.01f, TranscriptionStage.PREPARING)
            val engine = engineFactory.getOrCreate()

            val source = withContext(ioDispatcher) { audioSourceFactory.open(recording.filePath) }
            source.use { audio ->
                val vad = vadEngineFactory?.takeIf { it.isAvailable() }?.getOrCreate()
                val ranges = if (vad != null) {
                    detectSpeechRanges(vad, audio) { fraction ->
                        onProgress(fraction, TranscriptionStage.DETECTING_SPEECH)
                    }
                } else {
                    chunkPlanner.plan(audio)
                }
                onProgress(0.15f, TranscriptionStage.RECOGNIZING)

                val segments = mutableListOf<TranscriptSegment>()
                var wordCount = 0

                ranges.forEachIndexed { index, range ->
                    coroutineContext.ensureActive()
                    val samples = withContext(ioDispatcher) {
                        audio.read(range.startSample, range.sampleCount.toInt())
                    }
                    val words = withContext(cpuDispatcher) {
                        engine.transcribe(samples, audio.sampleRate)
                    }
                    if (words.isNotEmpty()) {
                        wordCount += words.size
                        val offsetMs = range.startSample * 1000L / audio.sampleRate
                        segments += toSegments(words, offsetMs, maxPauseMs)
                    }
                    val fraction = if (ranges.isEmpty()) {
                        0.9f
                    } else {
                        0.15f + 0.77f * (index + 1) / ranges.size
                    }
                    onProgress(fraction, TranscriptionStage.RECOGNIZING)
                }

                coroutineContext.ensureActive()

                var finalSegments: List<TranscriptSegment> = segments
                val diarizationFactory = diarizationEngineFactory
                    ?.takeIf { it.isAvailable() }
                if (diarizationFactory != null) {
                    // Sequential heavy-model lifecycle: release the 225 MB ASR
                    // session BEFORE the diarization models load into memory.
                    engineFactory.release()
                    val diarization = diarizationFactory.getOrCreate()
                    try {
                        onProgress(0.9f, TranscriptionStage.DIARIZING)
                        val turns = diarizeRecording(diarization, audio)
                        coroutineContext.ensureActive()
                        finalSegments = SpeakerTurnMerger.assign(
                            segments = segments,
                            turns = turns,
                        )
                    } finally {
                        diarizationFactory.release()
                    }
                }

                onProgress(0.95f, TranscriptionStage.SAVING)
                transcriptRepository.replaceSegments(
                    recordingId,
                    finalSegments.sortedWith(compareBy({ it.startMs }, { it.endMs })),
                )
                recordingRepository.updateStatus(recordingId, RecordingStatus.READY, null)
                onProgress(1f, TranscriptionStage.SAVING)
                return TranscriptionOutcome(segments.size, wordCount)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    recordingRepository.updateStatus(
                        recordingId,
                        RecordingStatus.RECORDED,
                        "Transcription cancelled",
                    )
                }
            }
            throw cancelled
        } catch (throwable: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    recordingRepository.updateStatus(
                        recordingId,
                        RecordingStatus.FAILED,
                        throwable.message ?: "Transcription failed",
                    )
                }
            }
            throw throwable
        }
    }

    /**
     * Runs VAD over the whole source in bounded blocks (never the whole file
     * in RAM), pads/merges the regions, and plans ASR chunks from them.
     * Timestamps stay in integer sample space until the final ms translation,
     * so no drift accumulates.
     */
    private suspend fun detectSpeechRanges(
        vad: VadEngine,
        audio: AudioSource,
        onProgress: (fraction: Float) -> Unit,
    ): List<SampleRange> {
        vad.reset()
        var offset = 0L
        val total = audio.totalSamples
        while (offset < total) {
            coroutineContext.ensureActive()
            val block = withContext(ioDispatcher) {
                audio.read(offset, minOf(VAD_BLOCK_SAMPLES, (total - offset).toInt()))
            }
            if (block.isEmpty()) break
            vad.process(block)
            offset += block.size
            onProgress(0.05f + 0.1f * offset / total)
        }
        val regions = vad.flush()
        val merged = SpeechRegionMerger.merge(
            regions = regions,
            totalSamples = total,
            sampleRate = audio.sampleRate,
        )
        return vadSpeechPlanner.plan(audio, merged)
    }

    private companion object {
        const val VAD_BLOCK_SAMPLES = 16_384
        const val DIARIZATION_BLOCK_SAMPLES = 16_384
    }

    /**
     * Diarization needs the whole recording as one contiguous buffer
     * (pyannote works on global 10 s windows + global clustering).
     * 16 kHz float32 = 64 KB per second of audio; this whole-file buffer is a
     * documented v1 limitation for very long recordings (Phase 7: sliding
     * windows). Reads happen on the IO dispatcher in bounded blocks.
     */
    private suspend fun diarizeRecording(
        diarization: DiarizationEngine,
        audio: AudioSource,
    ): List<DiarizationTurn> {
        require(audio.totalSamples <= Int.MAX_VALUE) {
            "Recording is too long for diarization: ${audio.totalSamples} samples"
        }
        val samples = withContext(ioDispatcher) {
            val all = FloatArray(audio.totalSamples.toInt())
            var offset = 0
            while (offset < all.size) {
                coroutineContext.ensureActive()
                val block = audio.read(
                    offset.toLong(),
                    minOf(DIARIZATION_BLOCK_SAMPLES, all.size - offset),
                )
                if (block.isEmpty()) break
                block.copyInto(all, offset)
                offset += block.size
            }
            if (offset < all.size) all.copyOf(offset) else all
        }
        return withContext(cpuDispatcher) { diarization.diarize(samples, audio.sampleRate) }
    }

    private fun toSegments(
        words: List<AsrWord>,
        offsetMs: Long,
        maxPauseMs: Long = AppSettings.DEFAULT_ASR_MAX_PAUSE_MS,
    ): List<TranscriptSegment> =
        AsrSegmenter.segment(words, maxPauseMs = maxPauseMs).map { utterance ->
            val absoluteWords = utterance.words.map { word ->
                WordTimestamp(
                    text = word.text,
                    startMs = word.startMs + offsetMs,
                    endMs = word.endMs + offsetMs,
                    confidence = word.confidence,
                    alignmentSource = AlignmentSource.CTC_FRAME,
                )
            }
            TranscriptSegment(
                id = null,
                speakerId = null,
                startMs = utterance.startMs + offsetMs,
                endMs = utterance.endMs + offsetMs,
                text = utterance.text,
                isProvisional = false,
                alignmentSource = AlignmentSource.CTC_FRAME,
                words = absoluteWords,
            )
        }
}
