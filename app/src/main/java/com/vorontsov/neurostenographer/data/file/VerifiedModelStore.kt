package com.vorontsov.neurostenographer.data.file

import android.content.Context
import com.vorontsov.neurostenographer.domain.model.ModelRepository
import com.vorontsov.neurostenographer.domain.model.ModelState
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Generic owner of one manually provisioned ML model file.
 *
 * The app has no INTERNET permission and never downloads models. The user
 * selects a local file; it is streamed to private storage and accepted only if
 * both the byte size and SHA-256 match the expected published artifact. A
 * corrupt or truncated file is never loaded. Used for the ASR model, the VAD
 * model, and any future diarization models.
 */
class VerifiedModelStore(
    context: Context,
    directoryName: String,
    private val fileName: String,
    private val expectedSize: Long,
    private val expectedSha256: String,
    private val ioDispatcher: CoroutineDispatcher,
) : ModelRepository {

    private val directory = File(context.filesDir, directoryName)
    private val modelFile = File(directory, fileName)

    private val _status = MutableStateFlow(ModelStatus())
    override val status: StateFlow<ModelStatus> = _status.asStateFlow()

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
                _status.value = ModelStatus(state = ModelState.NOT_INSTALLED)
                return@withLock
            }
            val stamp = modelFile.length() to modelFile.lastModified()
            if (_status.value.isReady && verifiedStamp == stamp) return@withLock

            _status.value = ModelStatus(
                state = ModelState.VERIFYING,
                sizeBytes = modelFile.length(),
            )
            val result = runCatching { verify(modelFile) }
            val status = result.getOrElse { throwable ->
                ModelStatus(
                    state = ModelState.ERROR,
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
                val temporary = File(directory, "$fileName.part")
                try {
                    directory.mkdirs()
                    temporary.delete()
                    _status.value = ModelStatus(state = ModelState.IMPORTING, progress = 0f)

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
                                val progress = (total.toDouble() / expectedSize)
                                    .toFloat()
                                    .coerceIn(0f, 0.99f)
                                if (progress - lastProgress >= PROGRESS_STEP) {
                                    lastProgress = progress
                                    _status.value = ModelStatus(
                                        state = ModelState.IMPORTING,
                                        progress = progress,
                                        sizeBytes = total,
                                    )
                                }
                            }
                        }
                    }

                    if (total != expectedSize) {
                        temporary.delete()
                        val message = "Model size is $total bytes, expected $expectedSize bytes"
                        _status.value = ModelStatus(
                            state = ModelState.INVALID,
                            sizeBytes = total,
                            message = message,
                        )
                        return@withLock Result.failure(IllegalArgumentException(message))
                    }
                    val hash = digest.digest().toHex()
                    if (!hash.equals(expectedSha256, ignoreCase = true)) {
                        temporary.delete()
                        val message = "Model SHA-256 does not match the published artifact"
                        _status.value = ModelStatus(
                            state = ModelState.INVALID,
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
                    _status.value = ModelStatus(
                        state = ModelState.READY,
                        progress = 1f,
                        sizeBytes = modelFile.length(),
                    )
                    Result.success(Unit)
                } catch (cancelled: CancellationException) {
                    temporary.delete()
                    _status.value = ModelStatus(state = ModelState.ERROR)
                    throw cancelled
                } catch (throwable: Throwable) {
                    temporary.delete()
                    _status.value = ModelStatus(
                        state = ModelState.ERROR,
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
            File(directory, "$fileName.part").delete()
            _status.value = ModelStatus(state = ModelState.NOT_INSTALLED)
        }
    }

    private fun verify(file: File): ModelStatus {
        val size = file.length()
        if (size != expectedSize) {
            return ModelStatus(
                state = ModelState.INVALID,
                sizeBytes = size,
                message = "Model size is $size bytes, expected $expectedSize bytes",
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
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return ModelStatus(
                state = ModelState.INVALID,
                sizeBytes = size,
                message = "Model SHA-256 does not match the published artifact",
            )
        }
        return ModelStatus(
            state = ModelState.READY,
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
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP = 0.01f
        val HEX = "0123456789abcdef".toCharArray()
    }
}
