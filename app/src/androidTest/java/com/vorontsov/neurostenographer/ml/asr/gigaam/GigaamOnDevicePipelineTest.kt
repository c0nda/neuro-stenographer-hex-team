package com.vorontsov.neurostenographer.ml.asr.gigaam

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vorontsov.neurostenographer.data.asr.WavAudioSource
import com.vorontsov.neurostenographer.data.file.AudioFileStore
import com.vorontsov.neurostenographer.data.local.NeuroStenographerDatabase
import com.vorontsov.neurostenographer.data.repository.RecordingRepositoryImpl
import com.vorontsov.neurostenographer.data.repository.TranscriptRepositoryImpl
import com.vorontsov.neurostenographer.domain.asr.AsrEngine
import com.vorontsov.neurostenographer.domain.asr.AsrEngineFactory
import com.vorontsov.neurostenographer.domain.asr.ChunkPlanner
import com.vorontsov.neurostenographer.domain.model.RecordingSource
import com.vorontsov.neurostenographer.domain.model.RecordingStatus
import com.vorontsov.neurostenographer.domain.transcription.TranscribeRecordingUseCase
import com.vorontsov.neurostenographer.domain.transcription.TranscriptionStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device end-to-end offline pipeline: real WAV -> GigaAM v3 -> segments ->
 * Room transaction -> normalized JSON snapshot.
 *
 * Skipped unless the size-verified model and a 16 kHz mono PCM16 Russian sample
 * are present in app-private storage (see [GigaamOnDeviceRecognitionTest]).
 */
@RunWith(AndroidJUnit4::class)
class GigaamOnDevicePipelineTest {

    @Test
    fun offlinePipelinePersistsContractCompliantTranscript() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "models/gigaam-v3/v3_ctc.int8.onnx")
        val audioFile = File(context.filesDir, "test/ru_sample.wav")
        assumeTrue(
            "Push the verified v3_ctc.int8.onnx to ${modelFile.absolutePath}",
            modelFile.isFile && modelFile.length() == EXPECTED_SIZE,
        )
        assumeTrue(
            "Push ru_sample.wav to ${audioFile.absolutePath}",
            audioFile.isFile,
        )

        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeuroStenographerDatabase::class.java,
        ).build()
        val engine = GigaamV3CtcEngine.load(
            modelFile = modelFile,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        )

        try {
            val recordingDao = database.recordingDao()
            val recordingRepository = RecordingRepositoryImpl(
                recordingDao = recordingDao,
                audioFileStore = AudioFileStore(context),
            )
            val transcriptRepository = TranscriptRepositoryImpl(
                database = database,
                transcriptDao = database.transcriptDao(),
                segmentDao = database.segmentDao(),
                speakerDao = database.speakerDao(),
                wordDao = database.wordDao(),
                recordingDao = recordingDao,
            )
            val engineFactory = object : AsrEngineFactory {
                override fun isAvailable(): Boolean = true
                override suspend fun getOrCreate(): AsrEngine = engine
            }
            val useCase = TranscribeRecordingUseCase(
                recordingRepository = recordingRepository,
                transcriptRepository = transcriptRepository,
                engineFactory = engineFactory,
                audioSourceFactory = WavAudioSource.Factory(),
                chunkPlanner = ChunkPlanner(),
                ioDispatcher = Dispatchers.IO,
                cpuDispatcher = Dispatchers.Default,
            )

            val recordingId = recordingRepository.createRecording(
                title = "ru_sample",
                filePath = audioFile.absolutePath,
                originalPath = null,
                source = RecordingSource.IMPORT,
                sampleRate = 16_000,
                channels = 1,
            )
            recordingRepository.finalizeRecording(recordingId, durationMs = 10_225)

            val stages = mutableListOf<TranscriptionStage>()
            val outcome = useCase.execute(recordingId) { _, stage -> stages += stage }

            val segments = transcriptRepository.observeSegments(recordingId).first()
            val json = transcriptRepository.getTranscriptJson(recordingId)
                ?: error("Transcript JSON was not persisted")
            val recording = recordingRepository.getRecording(recordingId)
                ?: error("Recording disappeared")

            println(
                "GigaAM v3 pipeline: segments=${outcome.segmentCount}, " +
                    "words=${outcome.wordCount}, status=${recording.status}",
            )
            println("GigaAM v3 pipeline JSON: $json")

            assertTrue("Expected at least one segment", segments.isNotEmpty())
            assertEquals(outcome.segmentCount, segments.size)
            assertEquals(
                outcome.wordCount,
                segments.sumOf { it.words.size },
            )
            assertEquals(RecordingStatus.READY, recording.status)
            assertEquals(0, recording.speakerCount)
            assertTrue(stages.contains(TranscriptionStage.PREPARING))
            assertTrue(stages.contains(TranscriptionStage.RECOGNIZING))
            assertTrue(stages.contains(TranscriptionStage.SAVING))

            val root = JSONObject(json)
            assertEquals(setOf("transcript"), root.keys().asSequence().toSet())
            val array = root.getJSONArray("transcript")
            assertEquals(segments.size, array.length())

            var previousStart = -1L
            for (index in 0 until array.length()) {
                val segment = array.getJSONObject(index)
                assertEquals(
                    setOf("speaker_id", "start_ms", "end_ms", "text"),
                    segment.keys().asSequence().toSet(),
                )
                assertEquals("unknown", segment.getString("speaker_id"))
                val start = segment.getLong("start_ms")
                val end = segment.getLong("end_ms")
                assertTrue("timestamps must fit in integer milliseconds", start <= Int.MAX_VALUE)
                assertTrue("start_ms must not decrease", start >= previousStart)
                assertTrue("start_ms must be <= end_ms", start <= end)
                assertFalse(segment.getString("text").isBlank())
                previousStart = start
            }
        } finally {
            engine.close()
            database.close()
        }
    }

    private companion object {
        const val EXPECTED_SIZE = 224_721_181L
    }
}
