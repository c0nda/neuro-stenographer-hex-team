package com.vorontsov.neurostenographer.data.asr

import android.content.Context
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.asr.AsrModelState
import com.vorontsov.neurostenographer.domain.asr.AsrModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the manually provisioned GigaAM v3 INT8 model.
 *
 * The app has no INTERNET permission and never downloads the model. The user
 * selects a local file; it is streamed to private storage and accepted only if
 * both the byte size and SHA-256 match the published artifact. A corrupt or
 * truncated file is never loaded.
 *
 * Expected artifact (`istupakov/gigaam-v3-onnx`):
 *  - name: v3_ctc.int8.onnx
 *  - size: 224,721,181 bytes
 *  - sha256: ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd
 */
@Singleton
class AsrModelStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AsrModelRepository {

    private val directory = File(context.filesDir, MODEL_DIRECTORY)
    private val modelFile = File(directory, MODEL_FILE_NAME)

    private val _status = MutableStateFlow(AsrModelStatus())
    override val status: StateFlow<AsrModelStatus> = _status.asStateFlow()

    private val gate = Mutex()
    private var verifiedStamp: Pair<Long, Long>? = null

    /** Returns the model file only when its content has been verified this session. */
    fun verifiedModelFile(): File? {
        if (!_status.value.isReady) return null
        return modelFile.takeIf { it.isFile }
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        gate.withLock {
            if (_status.value.isBusy) return@withLock
            if (!modelFile.isFile) {
                verifiedStamp = null
                _status.value = AsrModelStatus(state = AsrModelState.NOT_INSTALLED)
                return@withLock
            }
            val stamp = modelFile.length() to modelFile.lastModified()
            if (_status.value.isReady && verifiedStamp == stamp) return@withLock

            _status.value = AsrModelStatus(
                state = AsrModelState.VERIFYING,
                sizeBytes = modelFile.length(),
            )
            val result = runCatching { verify(modelFile) }
            val status = result.getOrElse { throwable ->
                AsrModelStatus(
                    state = AsrModelState.ERROR,
                    sizeBytes = modelFile.length(),
                    message = throwable.message,
                )
            }
            if (status.isReady) verifiedStamp = stamp
            _status.value = status
        }
    }

    override suspend fun importModel(open: () -> InputStream): Result<Unit> =
        withContext(ioDispatcher) {
            gate.withLock {
                val temporary = File(directory, "$MODEL_FILE_NAME.part")
                try {
                    directory.mkdirs()
                    temporary.delete()
                    _status.value = AsrModelStatus(state = AsrModelState.IMPORTING, progress = 0f)

                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastProgress = 0f
                    open().use { input ->
                        temporary.outputStream().buffered(BUFFER_SIZE).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                total += read
                                val progress = (total.toDouble() / EXPECTED_SIZE)
                                    .toFloat()
                                    .coerceIn(0f, 0.99f)
                                if (progress - lastProgress >= PROGRESS_STEP) {
                                    lastProgress = progress
                                    _status.value = AsrModelStatus(
                                        state = AsrModelState.IMPORTING,
                                        progress = progress,
                                        sizeBytes = total,
                                    )
                                }
                            }
                        }
                    }

                    if (total != EXPECTED_SIZE) {
                        temporary.delete()
                        val message = "Model size is $total bytes, expected $EXPECTED_SIZE bytes"
                        _status.value = AsrModelStatus(
                            state = AsrModelState.INVALID,
                            sizeBytes = total,
                            message = message,
                        )
                        return@withLock Result.failure(IllegalArgumentException(message))
                    }
                    val hash = digest.digest().toHex()
                    if (!hash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                        temporary.delete()
                        val message = "Model SHA-256 does not match the published artifact"
                        _status.value = AsrModelStatus(
                            state = AsrModelState.INVALID,
                            sizeBytes = total,
                            message = message,
                        )
                        return@withLock Result.failure(IllegalArgumentException(message))
                    }

                    if (modelFile.exists() && !modelFile.delete()) {
                        error("Could not replace the existing model file")
                    }
                    if (!temporary.renameTo(modelFile)) {
                        temporary.copyTo(modelFile, overwrite = true)
                        temporary.delete()
                    }
                    verifiedStamp = modelFile.length() to modelFile.lastModified()
                    _status.value = AsrModelStatus(
                        state = AsrModelState.READY,
                        progress = 1f,
                        sizeBytes = modelFile.length(),
                    )
                    Result.success(Unit)
                } catch (cancelled: CancellationException) {
                    temporary.delete()
                    _status.value = AsrModelStatus(state = AsrModelState.ERROR)
                    throw cancelled
                } catch (throwable: Throwable) {
                    temporary.delete()
                    _status.value = AsrModelStatus(
                        state = AsrModelState.ERROR,
                        message = throwable.message,
                    )
                    Result.failure(throwable)
                }
            }
        }

    override suspend fun deleteModel() = withContext(ioDispatcher) {
        gate.withLock {
            verifiedStamp = null
            modelFile.delete()
            File(directory, "$MODEL_FILE_NAME.part").delete()
            _status.value = AsrModelStatus(state = AsrModelState.NOT_INSTALLED)
        }
    }

    private fun verify(file: File): AsrModelStatus {
        val size = file.length()
        if (size != EXPECTED_SIZE) {
            return AsrModelStatus(
                state = AsrModelState.INVALID,
                sizeBytes = size,
                message = "Model size is $size bytes, expected $EXPECTED_SIZE bytes",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            return AsrModelStatus(
                state = AsrModelState.INVALID,
                sizeBytes = size,
                message = "Model SHA-256 does not match the published artifact",
            )
        }
        return AsrModelStatus(
            state = AsrModelState.READY,
            progress = 1f,
            sizeBytes = size,
        )
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            append(HEX[(byte.toInt() ushr 4) and 0xF])
            append(HEX[byte.toInt() and 0xF])
        }
    }

    private companion object {
        const val MODEL_DIRECTORY = "models/gigaam-v3"
        const val MODEL_FILE_NAME = "v3_ctc.int8.onnx"
        const val EXPECTED_SIZE = 224_721_181L
        const val EXPECTED_SHA256 =
            "ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP = 0.01f
        val HEX = "0123456789abcdef".toCharArray()
    }
}
