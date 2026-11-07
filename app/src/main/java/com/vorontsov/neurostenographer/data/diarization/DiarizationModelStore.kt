package com.vorontsov.neurostenographer.data.diarization

import android.content.Context
import com.vorontsov.neurostenographer.data.file.VerifiedModelStore
import com.vorontsov.neurostenographer.di.IoDispatcher
import com.vorontsov.neurostenographer.domain.diarization.DiarizationModelRepository
import com.vorontsov.neurostenographer.domain.model.ModelState
import com.vorontsov.neurostenographer.domain.model.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the two manually provisioned diarization models. Same offline contract
 * as the ASR/VAD models: user imports local files, each accepted only when its
 * size and SHA-256 match the published artifacts. The combined status is
 * READY only when BOTH files are verified.
 *
 * Expected artifacts (https://github.com/k2-fsa/sherpa-onnx/releases):
 *  - segmentation: tag speaker-segmentation-models,
 *    sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx
 *    (pyannote segmentation 3.0 conversion, MIT), 1,540,506 bytes
 *  - embedding: tag speaker-recongition-models,
 *    3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx
 *    (3D-Speaker CAM++ trained on VoxCeleb, Apache-2.0), 29,596,978 bytes
 */
@Singleton
class DiarizationModelStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : DiarizationModelRepository {

    private val segmentationStore = VerifiedModelStore(
        context = context,
        directoryName = SEGMENTATION_DIRECTORY,
        fileName = SEGMENTATION_FILE_NAME,
        expectedSize = SEGMENTATION_SIZE,
        expectedSha256 = SEGMENTATION_SHA256,
        ioDispatcher = ioDispatcher,
    )

    private val embeddingStore = VerifiedModelStore(
        context = context,
        directoryName = EMBEDDING_DIRECTORY,
        fileName = EMBEDDING_FILE_NAME,
        expectedSize = EMBEDDING_SIZE,
        expectedSha256 = EMBEDDING_SHA256,
        ioDispatcher = ioDispatcher,
    )

    private val _status = MutableStateFlow(ModelStatus())
    override val status: StateFlow<ModelStatus> = _status.asStateFlow()

    fun verifiedSegmentationFile(): File? = segmentationStore.verifiedModelFile()

    fun verifiedEmbeddingFile(): File? = embeddingStore.verifiedModelFile()

    override suspend fun refresh() {
        segmentationStore.refresh()
        embeddingStore.refresh()
        combine()
    }

    override suspend fun importSegmentationModel(open: () -> InputStream): Result<Unit> =
        segmentationStore.importModel(open).also { combine() }

    override suspend fun importEmbeddingModel(open: () -> InputStream): Result<Unit> =
        embeddingStore.importModel(open).also { combine() }

    override suspend fun deleteModels() {
        segmentationStore.deleteModel()
        embeddingStore.deleteModel()
        combine()
    }

    private fun combine() {
        val segmentation = segmentationStore.status.value
        val embedding = embeddingStore.status.value
        _status.value = when {
            segmentation.isReady && embedding.isReady -> ModelStatus(
                state = ModelState.READY,
                progress = 1f,
                sizeBytes = (segmentation.sizeBytes ?: 0) + (embedding.sizeBytes ?: 0),
            )

            segmentation.state == ModelState.IMPORTING -> segmentation
            embedding.state == ModelState.IMPORTING -> embedding
            segmentation.state == ModelState.VERIFYING -> segmentation
            embedding.state == ModelState.VERIFYING -> embedding
            segmentation.state == ModelState.INVALID || embedding.state == ModelState.INVALID -> {
                val which = if (segmentation.state == ModelState.INVALID) {
                    "Segmentation"
                } else {
                    "Embedding"
                }
                val bad = listOf(segmentation, embedding)
                    .first { it.state == ModelState.INVALID }
                bad.copy(message = bad.message?.let { "$which model: $it" })
            }

            segmentation.state == ModelState.ERROR || embedding.state == ModelState.ERROR ->
                listOf(segmentation, embedding).first { it.state == ModelState.ERROR }

            else -> ModelStatus(
                state = ModelState.NOT_INSTALLED,
                message = buildString {
                    if (!segmentation.isReady) append("Segmentation model missing")
                    if (!segmentation.isReady && !embedding.isReady) append("; ")
                    if (!embedding.isReady) append("Embedding model missing")
                }.ifBlank { null },
            )
        }
    }

    private companion object {
        const val SEGMENTATION_DIRECTORY = "models/diarization-segmentation"
        const val SEGMENTATION_FILE_NAME = "model.int8.onnx"
        const val SEGMENTATION_SIZE = 1_540_506L
        const val SEGMENTATION_SHA256 =
            "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"

        const val EMBEDDING_DIRECTORY = "models/diarization-embedding"
        const val EMBEDDING_FILE_NAME = "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
        const val EMBEDDING_SIZE = 29_596_978L
        const val EMBEDDING_SHA256 =
            "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
    }
}
