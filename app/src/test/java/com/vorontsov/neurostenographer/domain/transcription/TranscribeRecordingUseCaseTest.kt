package com.vorontsov.neurostenographer.domain.transcription

import com.vorontsov.neurostenographer.domain.asr.AsrEngine
import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.AsrModelUnavailableException
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.domain.asr.AudioSource
import com.vorontsov.neurostenographer.domain.asr.AudioSourceFactory
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.asr.SampleRange
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngine
import com.vorontsov.neurostenographer.domain.diarization.DiarizationEngineFactory
import com.vorontsov.neurostenographer.domain.diarization.DiarizationTurn
import com.vorontsov.neurostenographer.domain.model.AlignmentSource
import com.vorontsov.neurostenographer.domain.model.Recording
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.model.TranscriptSegment
import com.vorontsov.neurostenographer.domain.repository.RecordingRepository
import com.vorontsov.neurostenographer.domain.repository.TranscriptRepository
import com.vorontsov.neurostenographer.domain.transcript.TranscriptJson
import com.vorontsov.neurostenographer.domain.vad.VadEngine
import com.vorontsov.neurostenographer.domain.vad.VadEngineFactory
import com.vorontsov.neurostenographer.domain.vad.VadSpeechPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeRecordingUseCaseTest {

    @Test
    fun `pipeline maps model tokens to absolute recording time and persists the contract`() = runTest {
        val sampleRate = 16_000
        val source = FakeAudioSource(sampleRate, FloatArray(sampleRate * 3))
        val recordings = FakeRecordingRepository()
        val transcripts = FakeTranscriptRepository()
        val planner = ChunkPlanner(minChunkMs = 1_000, maxChunkMs = 2_000)
        val engine = FakeEngine()
        val useCase = useCase(
            recordings = recordings,
            transcripts = transcripts,
            engineFactory = FakeEngineFactory(engine),
            source = source,
            planner = planner,
            scheduler = testScheduler,
        )

        var lastProgress = 0f
        val outcome = useCase.execute(RECORDING_ID) { fraction, _ -> lastProgress = fraction }

        val ranges = planner.plan(source)
        assertTrue("expected more than one chunk", ranges.size >= 2)
        assertEquals(ranges.size, transcripts.segments.size)
        assertEquals(ranges.size, outcome.segmentCount)

        transcripts.segments.forEachIndexed { index, segment ->
            val offset = ranges[index].startSample * 1000 / sampleRate
            assertEquals(offset, segment.startMs)
            assertEquals(offset + 40, segment.endMs)
            assertEquals("тест", segment.text)
            assertNull(segment.speakerId)
            assertEquals(AlignmentSource.CTC_FRAME, segment.alignmentSource)
            assertEquals(1, segment.words.size)
            assertEquals(offset, segment.words[0].startMs)
            assertEquals(offset + 40, segment.words[0].endMs)
            assertEquals(AlignmentSource.CTC_FRAME, segment.words[0].alignmentSource)
        }

        assertEquals(RecordingStatus.READY, recordings.status)
        assertEquals(1f, lastProgress, 1e-6f)

        val json = transcripts.json()
        assertTrue(json.startsWith("{\"transcript\":["))
        assertTrue(json.contains("\"speaker_id\":\"unknown\""))
        assertTrue(!json.contains("alignment"))
        assertTrue(!json.contains("confidence"))
        assertTrue(TranscriptJson.validate(transcripts.segments).isEmpty())
    }

    @Test
    fun `missing model fails honestly and marks the recording failed`() = runTest {
        val recordings = FakeRecordingRepository()
        val transcripts = FakeTranscriptRepository()
        val failure = AsrModelUnavailableException("model not installed")
        val useCase = useCase(
            recordings = recordings,
            transcripts = transcripts,
            engineFactory = FakeEngineFactory(engine = null, failure = failure),
            source = FakeAudioSource(16_000, FloatArray(16_000)),
            planner = ChunkPlanner(),
            scheduler = testScheduler,
        )

        var thrown: Throwable? = null
        try {
            useCase.execute(RECORDING_ID) { _, _ -> }
        } catch (throwable: Throwable) {
            thrown = throwable
        }

        assertTrue(thrown is AsrModelUnavailableException)
        assertEquals(RecordingStatus.FAILED, recordings.status)
        assertEquals("model not installed", recordings.errorMessage)
        assertTrue(transcripts.segments.isEmpty())
    }

    @Test
    fun `VAD branch gates ASR on speech regions and maps timestamps absolutely`() = runTest {
        val sampleRate = 16_000
        // 5 s of audio; VAD reports speech only in the third second.
        val source = FakeAudioSource(sampleRate, FloatArray(sampleRate * 5))
        val recordings = FakeRecordingRepository()
        val transcripts = FakeTranscriptRepository()
        val planner = ChunkPlanner()
        val useCase = useCase(
            recordings = recordings,
            transcripts = transcripts,
            engineFactory = FakeEngineFactory(FakeEngine()),
            source = source,
            planner = planner,
            scheduler = testScheduler,
            vadEngineFactory = FakeVadEngineFactory(
                FakeVadEngine(listOf(SampleRange(sampleRate * 2L, sampleRate * 3L))),
            ),
        )

        val outcome = useCase.execute(RECORDING_ID) { _, _ -> }

        // Exactly one ASR call. The region 2.0–3.0 s is padded by 250 ms on
        // each side before recognition, so the absolute offsets shift to
        // 1.75 s (and the model's relative 40 ms word timing is preserved).
        assertEquals(1, transcripts.segments.size)
        assertEquals(1, outcome.segmentCount)
        val segment = transcripts.segments.single()
        assertEquals(1_750L, segment.startMs)
        assertEquals(1_790L, segment.endMs)
        assertEquals(1_750L, segment.words[0].startMs)
        assertEquals(1_790L, segment.words[0].endMs)
        assertEquals(RecordingStatus.READY, recordings.status)
    }

    @Test
    fun `silence-only recording yields an empty transcript and still completes`() = runTest {
        val recordings = FakeRecordingRepository()
        val transcripts = FakeTranscriptRepository()
        val useCase = useCase(
            recordings = recordings,
            transcripts = transcripts,
            engineFactory = FakeEngineFactory(FakeEngine()),
            source = FakeAudioSource(16_000, FloatArray(16_000 * 2)),
            planner = ChunkPlanner(),
            scheduler = testScheduler,
            vadEngineFactory = FakeVadEngineFactory(FakeVadEngine(emptyList())),
        )

        val outcome = useCase.execute(RECORDING_ID) { _, _ -> }

        assertEquals(0, outcome.segmentCount)
        assertTrue(transcripts.segments.isEmpty())
        assertEquals(RecordingStatus.READY, recordings.status)
        assertTrue(TranscriptJson.validate(transcripts.segments).isEmpty())
    }

    @Test
    fun `diarization assigns stable speaker ids inside the public JSON contract`() = runTest {
        val sampleRate = 16_000
        val source = FakeAudioSource(sampleRate, FloatArray(sampleRate * 3))
        val recordings = FakeRecordingRepository()
        val transcripts = FakeTranscriptRepository()
        val engineFactory = FakeEngineFactory(FakeEngine())
        val diarizationFactory = FakeDiarizationEngineFactory(
            FakeDiarizationEngine(
                listOf(
                    // First appearance: cluster 0 covers the first chunk → speaker_1.
                    DiarizationTurn(0, 1_000, clusterIndex = 0, confidence = null),
                    // Cluster 9 appears later → speaker_2 (not speaker_9).
                    DiarizationTurn(1_001, 3_000, clusterIndex = 9, confidence = null),
                ),
            ),
        )
        val useCase = useCase(
            recordings = recordings,
            transcripts = transcripts,
            engineFactory = engineFactory,
            source = source,
            planner = ChunkPlanner(minChunkMs = 1_000, maxChunkMs = 2_000),
            scheduler = testScheduler,
            diarizationEngineFactory = diarizationFactory,
        )

        useCase.execute(RECORDING_ID) { _, _ -> }

        assertEquals(1, diarizationFactory.releaseCalls)
        // Two chunks (0–2 s, 2–3 s): first gets speaker_1, second speaker_2.
        assertEquals(2, transcripts.segments.size)
        assertEquals("speaker_1", transcripts.segments[0].speakerId)
        assertEquals("speaker_2", transcripts.segments[1].speakerId)

        val json = transcripts.json()
        assertTrue(json.contains("\"speaker_id\":\"speaker_1\""))
        assertTrue(json.contains("\"speaker_id\":\"speaker_2\""))
        assertTrue(TranscriptJson.validate(transcripts.segments).isEmpty())
        assertEquals(RecordingStatus.READY, recordings.status)
    }

    private fun useCase(
        recordings: RecordingRepository,
        transcripts: TranscriptRepository,
        engineFactory: AsrEngineFactory,
        source: AudioSource,
        planner: ChunkPlanner,
        scheduler: TestCoroutineScheduler,
        vadEngineFactory: VadEngineFactory? = null,
        diarizationEngineFactory: DiarizationEngineFactory? = null,
    ): TranscribeRecordingUseCase = TranscribeRecordingUseCase(
        recordingRepository = recordings,
        transcriptRepository = transcripts,
        engineFactory = engineFactory,
        audioSourceFactory = object : AudioSourceFactory {
            override fun open(path: String): AudioSource = source
        },
        chunkPlanner = planner,
        vadEngineFactory = vadEngineFactory,
        vadSpeechPlanner = VadSpeechPlanner(planner),
        diarizationEngineFactory = diarizationEngineFactory,
        ioDispatcher = UnconfinedTestDispatcher(scheduler),
        cpuDispatcher = UnconfinedTestDispatcher(scheduler),
    )

    private class FakeEngine : AsrEngine {
        override fun transcribe(samples: FloatArray, sampleRate: Int): List<AsrWord> =
            listOf(AsrWord("тест", 0, 40, 0.9f))

        override fun close() = Unit
    }

    private class FakeEngineFactory(
        private val engine: AsrEngine?,
        private val failure: Throwable? = null,
    ) : AsrEngineFactory {
        override fun isAvailable(): Boolean = engine != null

        override suspend fun getOrCreate(): AsrEngine {
            failure?.let { throw it }
            return checkNotNull(engine)
        }
    }

    /** VAD engine that reports fixed regions regardless of input. */
    private class FakeVadEngine(private val regions: List<SampleRange>) : VadEngine {
        override fun reset() = Unit
        override fun process(samples: FloatArray) = Unit
        override fun flush(): List<SampleRange> = regions
        override fun close() = Unit
    }

    private class FakeVadEngineFactory(
        private val engine: VadEngine?,
    ) : VadEngineFactory {
        override fun isAvailable(): Boolean = engine != null

        override suspend fun getOrCreate(): VadEngine = checkNotNull(engine)

        override suspend fun release() = Unit
    }

    /** Diarization engine that reports fixed turns regardless of input. */
    private class FakeDiarizationEngine(private val turns: List<DiarizationTurn>) : DiarizationEngine {
        override fun diarize(samples: FloatArray, sampleRate: Int): List<DiarizationTurn> = turns
        override fun close() = Unit
    }

    private class FakeDiarizationEngineFactory(
        private val engine: DiarizationEngine?,
    ) : DiarizationEngineFactory {
        var releaseCalls = 0

        override fun isAvailable(): Boolean = engine != null

        override suspend fun getOrCreate(): DiarizationEngine = checkNotNull(engine)

        override suspend fun release() {
            releaseCalls++
        }
    }

    private class FakeAudioSource(
        override val sampleRate: Int,
        private val samples: FloatArray,
    ) : AudioSource {
        override val totalSamples: Long = samples.size.toLong()

        override fun read(startSample: Long, count: Int): FloatArray {
            val start = startSample.toInt().coerceIn(0, samples.size)
            val end = (start + count).coerceAtMost(samples.size)
            return samples.copyOfRange(start, end)
        }

        override fun close() = Unit
    }

    private class FakeRecordingRepository : RecordingRepository {
        var status: RecordingStatus = RecordingStatus.RECORDED
        var errorMessage: String? = null

        private val recording = Recording(
            id = RECORDING_ID,
            title = "Test",
            filePath = "unused.wav",
            originalPath = null,
            durationMs = 3_000,
            createdAtEpochMs = 0,
            status = RecordingStatus.RECORDED,
            speakerCount = 0,
            sampleRate = 16_000,
            channels = 1,
            source = RecordingSource.IMPORT,
            errorMessage = null,
        )

        override fun observeRecordings(query: String): Flow<List<Recording>> = flowOf(emptyList())
        override fun observeRecording(recordingId: Long): Flow<Recording?> = flowOf(recording)
        override suspend fun getRecording(recordingId: Long): Recording = recording

        override suspend fun createRecording(
            title: String,
            filePath: String,
            originalPath: String?,
            source: RecordingSource,
            sampleRate: Int,
            channels: Int,
        ): Long = error("not used")

        override suspend fun finalizeRecording(
            recordingId: Long,
            durationMs: Long,
            status: RecordingStatus,
        ) = Unit

        override suspend fun updateStatus(
            recordingId: Long,
            status: RecordingStatus,
            errorMessage: String?,
        ) {
            this.status = status
            this.errorMessage = errorMessage
        }

        override suspend fun renameRecording(recordingId: Long, title: String) = Unit
        override suspend fun deleteRecording(recordingId: Long) = Unit
    }

    private class FakeTranscriptRepository : TranscriptRepository {
        private val state = MutableStateFlow<List<TranscriptSegment>>(emptyList())
        val segments: List<TranscriptSegment> get() = state.value

        override fun observeSegments(recordingId: Long): Flow<List<TranscriptSegment>> = state
        override suspend fun replaceSegments(recordingId: Long, segments: List<TranscriptSegment>) {
            state.value = segments
        }

        override suspend fun getTranscriptJson(recordingId: Long): String = json()
        fun json(): String = TranscriptJson.encode(segments)
    }

    private companion object {
        const val RECORDING_ID = 1L
    }
}
