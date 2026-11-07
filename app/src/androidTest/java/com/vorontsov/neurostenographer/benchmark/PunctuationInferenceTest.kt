package com.vorontsov.neurostenographer.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vorontsov.neurostenographer.data.punctuation.BundledPunctuationRestorer
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the REAL bundled punctuation pipeline
 * (asset provisioning -> ONNX session -> punctuation marks appended).
 * Skips via assumptions when the assets were not bundled into the APK.
 */
@RunWith(AndroidJUnit4::class)
class PunctuationInferenceTest {

    @Test
    fun bundledModelRestoresPunctuationOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restorer = BundledPunctuationRestorer(
            context = context,
            ioDispatcher = Dispatchers.IO as CoroutineDispatcher,
        )
        assumeTrue("punctuation assets not bundled", restorer.isAvailable)

        val words = listOf("где", "ты", "живешь", "в", "общежитии")
        val start = System.nanoTime()
        val restored = restorer.restore(words.map { AsrWord(it, 0, 10, null) })
        val inferMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(words.size, restored.size)
        assertTrue(
            "expected at least one mark, got: ${restored.joinToString(" ") { it.text }}",
            restored.any { it.text.endsWith('.') || it.text.endsWith('?') || it.text.endsWith(',') },
        )
        // Original words are preserved as prefixes; timestamps untouched.
        restored.forEachIndexed { index, word ->
            assertTrue(word.text.startsWith(words[index]))
        }
        android.util.Log.i(
            "PunctuationInferenceTest",
            "restored=\"${restored.joinToString(" ") { it.text }}\" in ${inferMs}ms",
        )
    }
}
