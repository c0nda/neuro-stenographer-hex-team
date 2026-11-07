package com.vorontsov.neurostenographer.data.realtime

import android.util.Log
import com.vorontsov.neurostenographer.data.diarization.DiarizationModelStore
import com.vorontsov.neurostenographer.di.ApplicationScope
import com.vorontsov.neurostenographer.di.DefaultDispatcher
import com.vorontsov.neurostenographer.domain.asr.AsrEngine
import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrSegmenter
import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.asr.SampleRange
import com.vorontsov.neurostenographer.domain.realtime.OnlineSpeakerClusterer
import com.vorontsov.neurostenographer.domain.realtime.RegionAssembler
import com.vorontsov.neurostenographer.domain.realtime.StreamingState
import com.vorontsov.neurostenographer.domain.realtime.StreamingTranscriber
import com.vorontsov.neurostenographer.domain.realtime.StreamingTranscriptSegment
import com.vorontsov.neurostenographer.domain.vad.VadEngine
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.ml.diarization.SherpaEmbeddingExtractor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Real-time pipeline (PROMT §5). GigaAM v3 has NO native streaming mode:
 * completed VAD speech regions are transcribed utterance-by-utterance on a
 * single background worker. This is chunk-based processing, not native
 * streaming inference — the UI and docs say so explicitly.
 *
 * Backpressure: completed regions are handed to the inference worker through
 * a bounded channel; when the worker falls behind (RTF > 1 on slow hardware)
 * the OLDEST unprocessed region is dropped and counted — provisional data
 * only, the offline reprocessing after the stop is authoritative.
 *
 * Heavy inference is strictly serial: VAD runs inline (sub-millisecond per
 * 32 ms frame), ASR and embedding extraction run in one worker loop. Nothing
 * heavy ever runs on the audio capture thread or the main thread.
 */
@Singleton
class ChunkedStreamingTranscriber @Inject constructor(
    private val asrEngineFactory: AsrEngineFactory,
    private val vadEngineFactory: VadEngineFactory?,
    private val diarizationModelStore: DiarizationModelStore,
    private val chunkPlanner: ChunkPlanner,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val workerDispatcher: CoroutineDispatcher,
) : StreamingTranscriber {

    private data class RegionJob(
        val regionId: Long,
        val startSample: Long,
        val endSampleExclusive: Long,
    )

    private val assembler = RegionAssembler()
    private val clusterer = OnlineSpeakerClusterer()

    private val _segments = MutableStateFlow<List<StreamingTranscriptSegment>>(emptyList())
    override val segments: StateFlow<List<StreamingTranscriptSegment>> = _segments.asStateFlow()

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)
    override val state: StateFlow<StreamingState> = _state.asStateFlow()

    private val _currentSpeaker = MutableStateFlow<String?>(null)
    override val currentSpeaker: StateFlow<String?> = _currentSpeaker.asStateFlow()

    @Volatile
    override var isActive: Boolean = false
        private set

    // Set on start(), nulled on stop — only touched from the capture thread
    // and the worker, both while `running` serializes their handoff.
    @Volatile
    private var vad: VadEngine? = null
    private var asr: AsrEngine? = null
    private var extractor: SherpaEmbeddingExtractor? = null

    @Volatile
    private var running = false

    private var nextRegionId = 0L
    private var droppedRegions = 0

    /** Starts of queued regions in FIFO order; head = oldest pending. */
    private val pendingStarts = ConcurrentLinkedDeque<Long>()

    private var jobChannel = Channel<RegionJob>(capacity = QUEUE_CAPACITY)
    private var worker: Job? = null

    override suspend fun start(): Boolean {
        if (running) return true
        if (!asrEngineFactory.isAvailable()) return false
        return withContext(workerDispatcher) {
            try {
                asr = asrEngineFactory.getOrCreate()
                vad = vadEngineFactory?.takeIf { it.isAvailable() }?.getOrCreate()
                extractor = diarizationModelStore.verifiedEmbeddingFile()
                    ?.let { SherpaEmbeddingExtractor.load(it) }
            } catch (throwable: Throwable) {
                Log.e(TAG, "Streaming pipeline failed to start", throwable)
                releaseModels()
                _state.value = StreamingState.Error(
                    throwable.message ?: "Could not start live recognition",
                )
                return@withContext false
            }
            clusterer.reset()
            assembler.reset()
            nextRegionId = 0L
            droppedRegions = 0
            pendingStarts.clear()
            _segments.value = emptyList()
            _currentSpeaker.value = null
            _state.value = StreamingState.Listening
            jobChannel = Channel(QUEUE_CAPACITY)
            worker = scope.launch(workerDispatcher) { workerLoop() }
            running = true
            isActive = true
            true
        }
    }

    override fun onPcm(samples: ShortArray, count: Int) {
        if (!running) return
        // Cheap path on the capture thread: copy + VAD only.
        assembler.onPcm(samples, count)
        val engine = vad ?: run {
            // Without a VAD model there is no real-time path: every block is
            // cheaply discarded from the assembler to keep memory bounded.
            assembler.discardBefore(assembler.headSample())
            return
        }
        val frame = FloatArray(count) { samples[it] / 32768f }
        engine.process(frame)
        deliverCompletedRegions(engine)
    }

    override suspend fun stopAndFlush(): List<StreamingTranscriptSegment> {
        if (!running) return _segments.value
        running = false
        isActive = false
        vad?.flush()?.let { regions -> enqueueRegions(regions) }
        val channel = jobChannel
        // Give the worker a bounded chance to drain; whatever is left is
        // dropped (provisional preview only — offline pass is authoritative).
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
            while (!channel.isEmpty) kotlinx.coroutines.yield()
        }
        channel.close()
        worker?.join()
        _state.value = StreamingState.Idle
        releaseModels()
        return _segments.value
    }

    private fun deliverCompletedRegions(engine: VadEngine) {
        val regions = engine.drainRegions()
        if (regions.isEmpty()) {
            return
        }
        enqueueRegions(regions)
    }

    private fun enqueueRegions(regions: List<SampleRange>) {
        if (regions.isEmpty()) return
        for (region in regions) {
            val job = RegionJob(
                regionId = nextRegionId++,
                startSample = region.startSample,
                endSampleExclusive = region.endSampleExclusive,
            )
            val result = jobChannel.trySend(job)
            if (result.isFailure) {
                // Backpressure: drop the OLDEST queued region, log, retry once.
                val dropped = jobChannel.tryReceive().getOrNull()
                if (dropped != null) {
                    droppedRegions++
                    pendingStarts.pollFirst()
                    Log.w(
                        TAG,
                        "Worker behind: dropped region ${dropped.regionId} " +
                            "(total dropped: $droppedRegions)",
                    )
                    removeSegment(dropped.regionId)
                }
                jobChannel.trySend(job)
            }
            pendingStarts.addLast(region.startSample)
        }
        // Audio older than the oldest pending region (minus pad) is no longer
        // needed; the open VAD region keeps accumulating from its own start,
        // which is retained because it begins after the pending ones.
        val oldestPending = pendingStarts.peekFirst() ?: assembler.headSample()
        assembler.discardBefore((oldestPending - RETENTION_PAD_SAMPLES).coerceAtLeast(0L))
        _state.value = StreamingState.Processing(backlog = pendingStarts.size)
    }

    private suspend fun workerLoop() {
        for (job in jobChannel) {
            try {
                processRegion(job)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Region ${job.regionId} failed", throwable)
            }
            _state.value = if (pendingStarts.isEmpty()) {
                StreamingState.Listening
            } else {
                StreamingState.Processing(pendingStarts.size)
            }
        }
    }

    private suspend fun processRegion(job: RegionJob) {
        val engine = asr ?: return
        val sampleRate = SAMPLE_RATE
        // While this entry stays queued in pendingStarts the capture thread
        // retains the region's audio; release it only AFTER the copy.
        val regionSamples = assembler.extract(job.startSample, job.endSampleExclusive)
        pendingStarts.pollFirst()
        // Regions longer than the model window are split on quiet frames.
        val pieces = splitIfNeeded(regionSamples)
        val words = pieces.flatMap { engine.transcribe(it, sampleRate) }
        if (words.isEmpty()) {
            removeSegment(job.regionId)
            return
        }
        val startMs = job.startSample * 1000L / sampleRate
        val utterances = AsrSegmenter.segment(words)
        val text = utterances.joinToString(" ") { it.text }
        val endMs = startMs + (words.last().endMs)

        // Provisional speaker: embed the region audio (short regions are too
        // unreliable — CAM++ needs a few seconds of speech).
        val ext = extractor
        val speaker = if (ext != null && regionSamples.size >= MIN_EMBED_SAMPLES) {
            ext.extract(regionSamples, sampleRate)?.let { embedding ->
                val cluster = clusterer.add(embedding)
                "speaker_${cluster + 1}"
            }
        } else {
            null
        }
        if (speaker != null) _currentSpeaker.value = speaker

        upsertSegment(
            StreamingTranscriptSegment(
                regionId = job.regionId,
                startMs = startMs,
                endMs = endMs,
                text = text,
                speakerId = speaker,
            ),
        )
    }

    private fun splitIfNeeded(region: FloatArray): List<FloatArray> {
        val maxSamples = MAX_REGION_SECONDS * SAMPLE_RATE
        if (region.size <= maxSamples) return listOf(region)
        val source = BufferAudioSource(region, SAMPLE_RATE)
        return chunkPlanner.planRange(
            source,
            SampleRange(0, region.size.toLong()),
        ).map { range ->
            region.copyOfRange(range.startSample.toInt(), range.endSampleExclusive.toInt())
        }
    }

    private fun upsertSegment(segment: StreamingTranscriptSegment) {
        _segments.update { list ->
            (list.filterNot { it.regionId == segment.regionId } + segment)
                .sortedBy { it.startMs }
        }
    }

    private fun removeSegment(regionId: Long) {
        _segments.update { list -> list.filterNot { it.regionId == regionId } }
    }

    private fun releaseModels() {
        worker = null
        vad = null
        extractor?.close()
        extractor = null
        // ASR engine stays cached in its factory for reuse by the offline
        // pipeline; we only drop our reference.
        asr = null
    }

    private class BufferAudioSource(
        private val samples: FloatArray,
        override val sampleRate: Int,
    ) : AudioSource {
        override val totalSamples: Long get() = samples.size.toLong()
        override fun read(startSample: Long, count: Int): FloatArray =
            samples.copyOfRange(startSample.toInt(), (startSample + count).toInt().coerceAtMost(samples.size))
        override fun close() = Unit
    }

    private companion object {
        const val TAG = "StreamingTranscriber"
        const val SAMPLE_RATE = 16_000
        const val QUEUE_CAPACITY = 4
        const val DRAIN_TIMEOUT_MS = 30_000L
        const val RETENTION_PAD_SAMPLES = 16_000
        const val MAX_REGION_SECONDS = 20
        const val MIN_EMBED_SAMPLES = 16_000 * 3 / 2 // 1.5 s of speech
    }
}
