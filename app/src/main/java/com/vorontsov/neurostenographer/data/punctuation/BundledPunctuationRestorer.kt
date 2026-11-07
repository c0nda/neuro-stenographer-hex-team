package com.vorontsov.neurostenographer.data.punctuation

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.asr.AsrWord
import com.vorontsov.neurostenographer.domain.punctuation.PunctuationModelUnavailableException
import com.vorontsov.neurostenographer.domain.punctuation.PunctuationRestorer
import com.vorontsov.neurostenographer.ml.punctuation.OnnxPunctuationRestorer
import com.vorontsov.neurostenographer.ml.punctuation.PunctuationTokenizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Punctuation restorer backed by the APK-bundled Horizon-Labs
 * punctuation-restoration-small INT8 model (Apache-2.0).
 *
 * Bundling follows the project convention: the model ONNX is fetched and
 * verified at BUILD time into assets (downloadBundledModels task), the
 * tokenizer tables are committed next to it, and on first use everything
 * is copied from assets into private storage — verified against the
 * pinned size+SHA-256 constants below — before the ONNX session starts.
 * No internet, no external storage, no permissions.
 *
 * Artifacts (Horizon-Labs/punctuation-restoration-small @ 12b02a8):
 *  - onnx/model_quantized.onnx: 268,414,381 bytes, SHA-256 from SHA256SUMS
 *  - vocab.txt / merges.txt: extracted from the repo's tokenizer.json
 *    (SHA-256 f34b44a6...) by scripts-equivalent tooling, byte-faithful.
 */
@Singleton
class BundledPunctuationRestorer @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PunctuationRestorer {

    private data class BundledFile(
        val assetPath: String,
        val fileName: String,
        val expectedSize: Long,
        val sha256: String,
    )

    private val files = listOf(
        BundledFile(
            assetPath = "models/punctuation/model_quantized.onnx",
            fileName = "model_quantized.onnx",
            expectedSize = 268_414_381L,
            sha256 = "5679870be92c7fa47c9be9b2872fac389ef28d43d2c33f35e1315b93a82f4ace",
        ),
        BundledFile(
            assetPath = "models/punctuation/vocab.txt",
            fileName = "vocab.txt",
            expectedSize = 3_870_608L,
            sha256 = "597fc89c7b189f6a3807f6a5d35a11833ff3fee1c6d44bb9bf6b1037cacb37e8",
        ),
        BundledFile(
            assetPath = "models/punctuation/merges.txt",
            fileName = "merges.txt",
            expectedSize = 5_815_563L,
            sha256 = "2562b5c2f0411449bc3a621dbae392da7d0360ffc00dc19b6ccc3bcb9f3d9548",
        ),
    )

    private val directory = File(context.filesDir, "models/punctuation")

    private val gate = Mutex()
    private var delegate: OnnxPunctuationRestorer? = null

    override val isAvailable: Boolean
        get() = files.all { assetExists(context.assets, it.assetPath) }

    override suspend fun restore(words: List<AsrWord>): List<AsrWord> {
        if (words.isEmpty()) return words
        val engine = gate.withLock {
            delegate?.let { return@withLock it }
            val loaded = withContext(ioDispatcher) { createDelegate() }
            delegate = loaded
            loaded
        }
        return engine.restore(words)
    }

    override fun close() {
        delegate?.let { runCatching { it.close() } }
        delegate = null
    }

    private fun createDelegate(): OnnxPunctuationRestorer {
        directory.mkdirs()
        for (spec in files) {
            copyVerified(context.assets, spec, File(directory, spec.fileName))
        }
        val tokenizer = PunctuationTokenizer.load(
            vocabText = File(directory, "vocab.txt").readText(),
            mergesText = File(directory, "merges.txt").readText(),
        )
        val loadStart = System.nanoTime()
        val engine = OnnxPunctuationRestorer.load(
            modelFile = File(directory, "model_quantized.onnx"),
            tokenizer = tokenizer,
            threads = 2,
        )
        Log.i(TAG, "Punctuation model initialized in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
        return engine
    }

    /** Copies asset -> files unless a complete, hash-verified copy exists. */
    private fun copyVerified(assets: AssetManager, spec: BundledFile, target: File) {
        if (target.isFile && target.length() == spec.expectedSize && sha256(target) == spec.sha256) {
            return
        }
        val temporary = File(directory, spec.fileName + ".part")
        temporary.delete()
        try {
            assets.open(spec.assetPath).buffered().use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temporary.length() == spec.expectedSize) {
                "${spec.fileName}: size ${temporary.length()}, expected ${spec.expectedSize}"
            }
            check(sha256(temporary) == spec.sha256) {
                "${spec.fileName}: SHA-256 does not match the pinned artifact"
            }
            check(!target.exists() || target.delete()) { "Could not replace $target" }
            check(temporary.renameTo(target)) { "Could not move $temporary to $target" }
        } catch (throwable: Throwable) {
            temporary.delete()
            if (throwable is PunctuationModelUnavailableException) throw throwable
            throw PunctuationModelUnavailableException(
                "Could not provision ${spec.fileName}: ${throwable.message}",
                throwable,
            )
        }
    }

    private fun assetExists(assets: AssetManager, path: String): Boolean =
        runCatching { assets.open(path).close(); true }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "PunctuationRestorer"
    }
}
