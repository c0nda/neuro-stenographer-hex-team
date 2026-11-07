package com.vorontsov.neurostenographer.ml.punctuation

import com.vorontsov.neurostenographer.domain.asr.AsrWord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Desktop verification of the REAL punctuation pipeline: production
 * tokenizer + OnnxPunctuationRestorer on the desktop ONNX Runtime, using
 * the same INT8 model that ships in the APK. Not CI-gated: runs only with
 *
 * ```
 * gradlew :app:testDebugUnitTest --tests "*PunctuationDesktopInferenceTest*" \
 *   -Dpunct.model=C:/path/to/model_quantized.onnx
 * ```
 */
class PunctuationDesktopInferenceTest {

    @Test
    fun restoresPunctuationInRussianSentences() = runBlocking {
        val modelPath = System.getProperty("punct.model").orEmpty()
        val model = File(modelPath)
        assumeTrue("punct.model not set", model.isFile)
        val assets = File("src/main/assets/models/punctuation")
        assumeTrue("tokenizer assets missing", File(assets, "vocab.txt").isFile)

        val tokenizer = PunctuationTokenizer.load(
            File(assets, "vocab.txt").readText(),
            File(assets, "merges.txt").readText(),
        )
        val loadStart = System.nanoTime()
        OnnxPunctuationRestorer.load(model, tokenizer, threads = 2).use { restorer ->
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000

            val cases = listOf(
                listOf("где", "ты", "живешь", "в", "общежитии"),
                listOf("а", "я", "ненавижу", "жить", "в", "общежитии"),
                listOf("ты", "снимаешь", "квартиру"),
                listOf(
                    "скажите", "пожалуйста", "как", "пройти", "к", "центру",
                    "города", "мне", "нужно", "найти", "библиотеку",
                ),
            )
            for (words in cases) {
                val start = System.nanoTime()
                val restored = restorer.restore(words.map { AsrWord(it, 0, 10, null) })
                val inferMs = (System.nanoTime() - start) / 1_000_000
                println(
                    "[desktop-punct] load_ms=$loadMs infer_ms=$inferMs words=${words.size} " +
                        "out=\"${restored.joinToString(" ") { it.text }}\"",
                )
            }

            // Long input across many windows: no word may be lost or
            // duplicated, and every word must keep its position.
            val longWords = (1..900).map { AsrWord("слово$it", it * 100L, it * 100L + 50, null) }
            val longStart = System.nanoTime()
            val longRestored = restorer.restore(longWords)
            val longMs = (System.nanoTime() - longStart) / 1_000_000
            check(longRestored.size == longWords.size) { "word count changed" }
            check(longRestored.mapIndexed { i, w -> w.startMs == (i + 1) * 100L }.all { it }) {
                "timestamps not preserved"
            }
            val tokenCount = tokenizer.encodeWords(longWords.map { it.text }).ids.size
            println(
                "[desktop-punct] long_words=${longWords.size} tokens=$tokenCount " +
                    "windows=${OnnxPunctuationRestorer.planWindows(tokenCount).size} " +
                    "infer_ms=$longMs timestamps_preserved=true",
            )
        }
    }
}
