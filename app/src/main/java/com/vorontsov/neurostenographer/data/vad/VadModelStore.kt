package com.vorontsov.neurostenographer.data.vad

import android.content.Context
import com.vorontsov.neurostenographer.data.file.VerifiedModelStore
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.model.ModelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Owns the manually provisioned Silero VAD model (official v5/v6 `silero_vad.onnx`,
 * MIT license). Same offline provisioning contract as the ASR model: the user
 * imports a local file and it is accepted only when both the byte size and
 * SHA-256 match the published artifact.
 *
 * Expected artifact (https://github.com/snakers4/silero-vad):
 *  - path in repo: src/silero_vad/data/silero_vad.onnx
 *  - size: 2,327,524 bytes
 *  - sha256: 1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3
 */
@Singleton
class VadModelStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : ModelRepository {

    private val store = VerifiedModelStore(
        context = context,
        directoryName = MODEL_DIRECTORY,
        fileName = MODEL_FILE_NAME,
        expectedSize = EXPECTED_SIZE,
        expectedSha256 = EXPECTED_SHA256,
        ioDispatcher = ioDispatcher,
    )

    override val status = store.status

    /** Returns the model file only when its content has been verified this session. */
    fun verifiedModelFile(): File? = store.verifiedModelFile()

    override suspend fun refresh() = store.refresh()

    override suspend fun importModel(open: () -> InputStream): Result<Unit> = store.importModel(open)

    override suspend fun deleteModel() = store.deleteModel()

    private companion object {
        const val MODEL_DIRECTORY = "models/silero-vad"
        const val MODEL_FILE_NAME = "silero_vad.onnx"
        const val EXPECTED_SIZE = 2_327_524L
        const val EXPECTED_SHA256 =
            "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    }
}
