package com.vorontsov.neurostenographer.data.asr

import android.content.Context
import com.vorontsov.neurostenographer.data.file.VerifiedModelStore
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.asr.AsrModelRepository
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

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
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : AsrModelRepository {

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
        const val MODEL_DIRECTORY = "models/gigaam-v3"
        const val MODEL_FILE_NAME = "v3_ctc.int8.onnx"
        const val EXPECTED_SIZE = 224_721_181L
        const val EXPECTED_SHA256 =
            "ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd"
    }
}
