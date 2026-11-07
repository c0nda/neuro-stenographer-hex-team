package com.vorontsov.neurostenographer.ml.punctuation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.domain.punctuation.PunctuationModelUnavailableException
import com.vorontsov.neurostenographer.domain.punctuation.PunctuationRestorer
import java.io.File
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime implementation of punctuation restoration using
 * Horizon-Labs/punctuation-restoration-small (ModernBert token
 * classification, INT8, Apache-2.0).
 *
 * Verified model facts (config.json / tokenizer_config.json / SHA256SUMS):
 *  - labels (logit index -> mark): 0="0", 1=".", 2=",", 3="?", 4="-", 5=":"
 *  - specials: <pad>=0, <eos>=1, <bos>=2, <unk>=3; windows wrap ids as
 *    [<bos>] + tokens + [<eos>], exactly like the reference punctuate.py
 *  - a word's label is the label of its LAST token
 *  - long inputs: overlapping token windows (chunk/overlap below mirror the
 *    reference helper), each token labeled exactly once — no duplicates,
 *    nothing lost at boundaries
 *
 * Marks append to the word text ("-" becomes " -"). Timestamps, confidence
 * and word order are never touched. If a window inference fails the whole
 * call throws — punctuation is never fabricated.
 */
class OnnxPunctuationRestorer private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: PunctuationTokenizer,
    private val useAttentionMask: Boolean,
) : PunctuationRestorer {

    data class Window(val start: Int, val end: Int, val keepTo: Int)

    override val isAvailable: Boolean get() = true

    override suspend fun restore(words: List<AsrWord>): List<AsrWord> {
        if (words.isEmpty()) return words
        val encoded = tokenizer.encodeWords(words.map { it.text })
        if (encoded.ids.isEmpty()) return words

        val marks = Array(words.size) { "" }
        for (window in planWindows(encoded.ids.size)) {
            val logits = runWindow(encoded.ids, window)
            for (tokenIndex in window.start until window.keepTo) {
                val labelIndex = argmax(logits, tokenIndex - window.start + 1)
                if (labelIndex in LABELS.indices) {
                    marks[encoded.wordOfToken[tokenIndex]] = LABELS[labelIndex]
                }
            }
        }

        return words.mapIndexed { index, word ->
            val mark = marks[index]
            if (mark.isEmpty()) word else word.copy(text = word.text + mark)
        }
    }

    override fun close() {
        session.close()
    }

    /**
     * Runs one window [bos] + ids[start, end) + [eos] and returns the raw
     * logits row [windowTokens + 2][6] (index 0 = <bos>, last = <eos>).
     */
    private fun runWindow(ids: IntArray, window: Window): Array<FloatArray> {
        val length = window.end - window.start + 2
        val inputIds = LongArray(length)
        inputIds[0] = PunctuationTokenizer.BOS_ID.toLong()
        for (i in window.start until window.end) inputIds[i - window.start + 1] = ids[i].toLong()
        inputIds[length - 1] = PunctuationTokenizer.EOS_ID.toLong()

        val inputTensor = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(inputIds),
            longArrayOf(1, length.toLong()),
        )
        val maskTensor = if (useAttentionMask) {
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(LongArray(length) { 1 }),
                longArrayOf(1, length.toLong()),
            )
        } else {
            null
        }
        val inputs = if (maskTensor != null) {
            mapOf(INPUT_IDS to inputTensor, ATTENTION_MASK to maskTensor)
        } else {
            mapOf(INPUT_IDS to inputTensor)
        }
        val outputs = try {
            session.run(inputs)
        } catch (throwable: Throwable) {
            throw PunctuationModelUnavailableException(
                "Punctuation inference failed: ${throwable.message}",
                throwable,
            )
        } finally {
            inputTensor.close()
            maskTensor?.close()
        }
        return try {
            val tensor = outputs.get(OUTPUT_LOGITS).orElseThrow {
                PunctuationModelUnavailableException("The model returned no logits output")
            }
            @Suppress("UNCHECKED_CAST")
            val batch = tensor.value as Array<Array<FloatArray>>
            batch[0]
        } finally {
            outputs.close()
        }
    }

    private fun argmax(logits: Array<FloatArray>, position: Int): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        val row = logits[position]
        for (index in row.indices) {
            if (row[index] > bestValue) {
                bestValue = row[index]
                best = index
            }
        }
        return best
    }

    companion object {
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val OUTPUT_LOGITS = "logits"

        /** chunk/overlap mirror the reference punctuate.py helper. */
        const val CHUNK_TOKENS = 400
        const val OVERLAP_TOKENS = 50

        /** logit index -> mark appended to the word (empty = no mark). */
        val LABELS = arrayOf("", ".", ",", "?", " -", ":")

        /**
         * Overlapping token windows. Each token is emitted in exactly one
         * window's [start, keepTo) range; the trailing [keepTo, end) overlap
         * gives the model context for the boundary tokens.
         */
        fun planWindows(tokenCount: Int, chunk: Int = CHUNK_TOKENS, overlap: Int = OVERLAP_TOKENS): List<Window> {
            require(chunk > overlap) { "chunk ($chunk) must exceed overlap ($overlap)" }
            val windows = mutableListOf<Window>()
            var start = 0
            while (start < tokenCount) {
                val end = minOf(tokenCount, start + chunk)
                val keepTo = if (end == tokenCount) end else end - overlap
                windows += Window(start, end, keepTo)
                if (end == tokenCount) break
                start = keepTo
            }
            return windows
        }

        fun load(modelFile: File, tokenizer: PunctuationTokenizer, threads: Int): OnnxPunctuationRestorer {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            return try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(threads.coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                val session = environment.createSession(modelFile.absolutePath, options)
                val inputNames = session.inputNames
                if (INPUT_IDS !in inputNames) {
                    session.close()
                    throw PunctuationModelUnavailableException(
                        "The model does not expose '$INPUT_IDS': $inputNames",
                    )
                }
                if (OUTPUT_LOGITS !in session.outputNames) {
                    session.close()
                    throw PunctuationModelUnavailableException(
                        "The model does not expose '$OUTPUT_LOGITS': ${session.outputNames}",
                    )
                }
                OnnxPunctuationRestorer(
                    environment = environment,
                    session = session,
                    tokenizer = tokenizer,
                    useAttentionMask = ATTENTION_MASK in inputNames,
                )
            } catch (throwable: Throwable) {
                if (throwable is PunctuationModelUnavailableException) throw throwable
                throw PunctuationModelUnavailableException(
                    "Could not load the punctuation model: ${throwable.message}",
                    throwable,
                )
            } finally {
                options.close()
            }
        }
    }
}
