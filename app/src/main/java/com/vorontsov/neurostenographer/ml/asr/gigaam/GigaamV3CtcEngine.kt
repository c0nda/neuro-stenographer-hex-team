package com.vorontsov.neurostenographer.ml.asr.gigaam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vorontsov.neurostenographer.domain.asr.AsrEngine
import com.vorontsov.neurostenographer.domain.asr.AsrModelUnavailableException
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of the GigaAM v3 CTC acoustic model.
 *
 * Verified model contract (`v3_ctc.int8.onnx`):
 *  - inputs:  `features` float32 [1, 64, T], `feature_lengths` int64 [1]
 *  - output:  `log_probs` float32 [1, ~T/4, 34]
 *  - classes: 0 = separator, 1..32 = Russian letters, 33 = CTC blank
 *
 * Timestamps are derived from encoder frames (40 ms each), not distributed
 * uniformly over the utterance.
 */
class GigaamV3CtcEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val featureExtractor: GigaamV3FeatureExtractor,
) : AsrEngine {

    override fun transcribe(samples: FloatArray, sampleRate: Int): List<AsrWord> {
        require(sampleRate == GigaamV3FeatureExtractor.SAMPLE_RATE) {
            "GigaAM v3 expects 16 kHz audio, got $sampleRate Hz"
        }
        val features = featureExtractor.extract(samples)
        if (features.frames <= 0) return emptyList()

        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(features.data),
            longArrayOf(1, GigaamV3FeatureExtractor.MELS.toLong(), features.frames.toLong()),
        )
        val lengths = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(features.frames.toLong())),
            longArrayOf(1),
        )

        input.use { featureTensor ->
            lengths.use { lengthTensor ->
                session.run(
                    mapOf(
                        INPUT_FEATURES to featureTensor,
                        INPUT_LENGTHS to lengthTensor,
                    ),
                ).use { result ->
                    val output = result.get(OUTPUT_LOG_PROBS)
                        .orElseThrow { IllegalStateException("Model returned no log_probs output") }
                    @Suppress("UNCHECKED_CAST")
                    val logProbs = output.value as Array<Array<FloatArray>>
                    val frames = logProbs.firstOrNull()?.size ?: 0
                    if (frames == 0) return emptyList()
                    val classes = logProbs[0][0].size
                    if (classes != GigaamCtcDecoder.CLASSES) {
                        throw IllegalStateException(
                            "Unexpected GigaAM vocabulary size $classes, expected " +
                                GigaamCtcDecoder.CLASSES,
                        )
                    }
                    val flat = FloatArray(frames * classes)
                    var offset = 0
                    for (frame in 0 until frames) {
                        val row = logProbs[0][frame]
                        row.copyInto(flat, offset)
                        offset += classes
                    }
                    val tokens = GigaamCtcDecoder.decode(flat, frames, classes)
                    return GigaamWordBuilder.buildWords(tokens)
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val INPUT_FEATURES = "features"
        private const val INPUT_LENGTHS = "feature_lengths"
        private const val OUTPUT_LOG_PROBS = "log_probs"

        fun load(modelFile: File, threads: Int): GigaamV3CtcEngine {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            return try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads.coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                val session = environment.createSession(modelFile.absolutePath, options)
                val inputNames = session.inputNames
                if (INPUT_FEATURES !in inputNames || INPUT_LENGTHS !in inputNames) {
                    session.close()
                    throw AsrModelUnavailableException(
                        "The model does not expose the expected GigaAM inputs: $inputNames",
                    )
                }
                if (OUTPUT_LOG_PROBS !in session.outputNames) {
                    session.close()
                    throw AsrModelUnavailableException(
                        "The model does not expose the expected GigaAM output: ${session.outputNames}",
                    )
                }
                GigaamV3CtcEngine(environment, session, GigaamV3FeatureExtractor())
            } catch (throwable: Throwable) {
                if (throwable is AsrModelUnavailableException) throw throwable
                throw AsrModelUnavailableException(
                    "Could not load the GigaAM model: ${throwable.message}",
                    throwable,
                )
            } finally {
                options.close()
            }
        }
    }
}
