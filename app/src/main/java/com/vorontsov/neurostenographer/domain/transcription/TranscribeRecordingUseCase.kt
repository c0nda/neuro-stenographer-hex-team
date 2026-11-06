package com.vorontsov.neurostenographer.domain.transcription

import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrSegmenter
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.domain.asr.AudioSourceFactory
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.model.WordTimestamp
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class TranscriptionStage {
    PREPARING,
    RECOGNIZING,
    SAVING,
}

data class TranscriptionOutcome(
    val segmentCount: Int,
    val wordCount: Int,
)

/**
 * Offline transcription pipeline: chunked WAV read -> GigaAM v3 CTC ->
 * word-level grouping -> normalized segments -> transactional persistence.
 *
 * Deliberately not implemented yet, and reported honestly in the UI:
 *  - VAD gating (Phase 4); chunk boundaries use a simple energy minimum
 *  - speaker diarization (Phase 5); speaker_id is "unknown"
 *  - forced alignment beyond CTC frame resolution (Phase 5)
 */
class TranscribeRecordingUseCase(
    private val recordingRepository: RecordingRepository,
    private val transcriptRepository: TranscriptRepository,
    private val engineFactory: AsrEngineFactory,
    private val audioSourceFactory: AudioSourceFactory,
    private val chunkPlanner: ChunkPlanner,
    private val ioDispatcher: CoroutineDispatcher,
    private val cpuDispatcher: CoroutineDispatcher,
) {

    suspend fun execute(
        recordingId: Long,
        onProgress: (fraction: Float, stage: TranscriptionStage) -> Unit,
    ): TranscriptionOutcome {
        val recording = recordingRepository.getRecording(recordingId)
            ?: throw IllegalArgumentException("Recording $recordingId does not exist")

        recordingRepository.updateStatus(recordingId, RecordingStatus.PROCESSING, null)
        try {
            onProgress(0.01f, TranscriptionStage.PREPARING)
            val engine = engineFactory.getOrCreate()

            val source = withContext(ioDispatcher) { audioSourceFactory.open(recording.filePath) }
            source.use { audio ->
                val ranges = chunkPlanner.plan(audio)
                onProgress(0.05f, TranscriptionStage.RECOGNIZING)

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
                        segments += toSegments(words, offsetMs)
                    }
                    val fraction = if (ranges.isEmpty()) {
                        0.9f
                    } else {
                        0.05f + 0.85f * (index + 1) / ranges.size
                    }
                    onProgress(fraction, TranscriptionStage.RECOGNIZING)
                }

                coroutineContext.ensureActive()
                onProgress(0.92f, TranscriptionStage.SAVING)
                transcriptRepository.replaceSegments(
                    recordingId,
                    segments.sortedWith(compareBy({ it.startMs }, { it.endMs })),
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

    private fun toSegments(words: List<AsrWord>, offsetMs: Long): List<TranscriptSegment> =
        AsrSegmenter.segment(words).map { utterance ->
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
