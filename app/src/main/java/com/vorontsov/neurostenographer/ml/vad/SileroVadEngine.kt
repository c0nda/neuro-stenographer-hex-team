package com.vorontsov.neurostenographer.ml.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.vorontsov.neurostenographer.domain.asr.SampleRange
import com.vorontsov.neurostenographer.domain.vad.VadEngine
import com.vorontsov.neurostenographer.domain.vad.VadModelUnavailableException
import com.vorontsov.neurostenographer.domain.vad.VadRegionTracker
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of Silero VAD (official `silero_vad.onnx`,
 * v5/v6, MIT license) for 16 kHz mono audio.
 *
 * Verified model contract:
 *  - inputs:  `input` float32 [1, 576] (64 samples of context + the 512-sample
 *             frame), `state` float32 [2, 1, D] (LSTM hidden+cell; D = 128 for
 *             v5/v6, 64 for v4 — read from the session, never hardcoded),
 *             `sr` int64 [1] = 16000
 *  - outputs: `output` float32 [1, 1] speech probability,
 *             `stateN` float32 [2, 1, D] updated state
 *
 * The model is stateful: the LSTM state and the 64-sample context are carried
 * between frames. [reset] clears both, which the pipeline uses at the start of
 * each recording.
 */
class SileroVadEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val stateSize: Long,
) : VadEngine {

    private val tracker = VadRegionTracker()
    private val state = FloatArray((2 * stateSize).toInt())
    private val context = FloatArray(CONTEXT_SAMPLES)
    private val pending = FloatArray(FRAME_SAMPLES)
    private var pendingCount = 0

    override fun reset() {
        state.fill(0f)
        context.fill(0f)
        pending.fill(0f)
        pendingCount = 0
        tracker.reset()
    }

    override fun process(samples: FloatArray) {
        var index = 0
        while (index < samples.size) {
            val take = minOf(FRAME_SAMPLES - pendingCount, samples.size - index)
            samples.copyInto(pending, pendingCount, index, index + take)
            pendingCount += take
            index += take
            if (pendingCount == FRAME_SAMPLES) {
                tracker.accept(predictFrame(pending))
                pendingCount = 0
            }
        }
    }

    override fun flush(): List<SampleRange> = tracker.flush()

    override fun drainRegions(): List<SampleRange> = tracker.drainCompleted()

    override fun close() {
        session.close()
    }

    private fun predictFrame(frame: FloatArray): Float {
        val input = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
        context.copyInto(input, 0, 0, CONTEXT_SAMPLES)
        frame.copyInto(input, CONTEXT_SAMPLES, 0, FRAME_SAMPLES)

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, (CONTEXT_SAMPLES + FRAME_SAMPLES).toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(state),
            longArrayOf(2, 1, stateSize),
        )
        val srTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
            longArrayOf(1),
        )
        val outputs = try {
            session.run(
                mapOf(
                    INPUT to inputTensor,
                    STATE to stateTensor,
                    SR to srTensor,
                ),
            )
        } catch (throwable: Throwable) {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
            throw throwable
        }
        inputTensor.close()
        stateTensor.close()
        srTensor.close()

        val probability = try {
            val probabilityTensor = outputs.get(OUTPUT).orElseThrow {
                IllegalStateException("Silero VAD returned no output")
            }
            val value = extractProbability(probabilityTensor.value)
            val stateTensorOut = outputs.get(OUTPUT_STATE).orElseThrow {
                IllegalStateException("Silero VAD returned no state")
            }
            @Suppress("UNCHECKED_CAST")
            val stateOut = stateTensorOut.value as Array<Array<FloatArray>>
            var index = 0
            for (layer in stateOut) {
                for (batch in layer) {
                    for (component in batch) {
                        state[index++] = component
                    }
                }
            }
            value
        } finally {
            outputs.close()
        }
        // Carry the last context samples into the next frame.
        input.copyInto(context, 0, FRAME_SAMPLES, CONTEXT_SAMPLES + FRAME_SAMPLES)
        return probability
    }

    private fun extractProbability(value: Any): Float = when (value) {
        is Array<*> -> when (val first = value[0]) {
            is FloatArray -> first[0]
            is Array<*> -> (first[0] as FloatArray)[0]
            else -> throw IllegalStateException("Unexpected Silero VAD output type: ${value.javaClass}")
        }
        is FloatArray -> value[0]
        else -> throw IllegalStateException("Unexpected Silero VAD output type: ${value.javaClass}")
    }

    companion object {
        private const val INPUT = "input"
        private const val STATE = "state"
        private const val SR = "sr"
        private const val OUTPUT = "output"
        private const val OUTPUT_STATE = "stateN"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val DEFAULT_STATE_SIZE = 128L

        fun load(modelFile: File, threads: Int = 1): SileroVadEngine {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            return try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads.coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                val session = environment.createSession(modelFile.absolutePath, options)

                val inputNames = session.inputNames
                if (INPUT !in inputNames || STATE !in inputNames || SR !in inputNames) {
                    session.close()
                    throw VadModelUnavailableException(
                        "The model does not expose the expected Silero VAD inputs: $inputNames",
                    )
                }
                if (OUTPUT !in session.outputNames || OUTPUT_STATE !in session.outputNames) {
                    session.close()
                    throw VadModelUnavailableException(
                        "The model does not expose the expected Silero VAD outputs: ${session.outputNames}",
                    )
                }
                val stateSize = (session.inputInfo[STATE]?.info as? TensorInfo)
                    ?.shape
                    ?.getOrNull(2)
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_STATE_SIZE

                SileroVadEngine(environment, session, stateSize)
            } catch (throwable: Throwable) {
                if (throwable is VadModelUnavailableException) throw throwable
                throw VadModelUnavailableException(
                    "Could not load the Silero VAD model: ${throwable.message}",
                    throwable,
                )
            } finally {
                options.close()
            }
        }
    }
}
