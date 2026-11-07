package com.vorontsov.neurostenographer.data.file

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.vorontsov.neurostenographer.data.asr.AsrModelStore
import com.vorontsov.neurostenographer.data.diarization.DiarizationModelStore
import com.vorontsov.neurostenographer.data.vad.VadModelStore
import com.vorontsov.neurostenographer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * "Models out of the box": when the APK was built with the bundled model
 * artifacts (see the `downloadBundledModels` Gradle task), the first launch
 * copies them from assets into private storage through the same verified
 * import path the manual Home-screen import uses — [VerifiedModelStore]
 * checks the exact size and SHA-256 of every file. If a model was already
 * imported or replaced by the user, it is left untouched.
 *
 * This is the "first-run model manager" from Phase 1 §9.6. The app remains
 * fully offline at runtime: downloading happens at BUILD time only.
 */
@Singleton
class BundledModelProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val asrModelStore: AsrModelStore,
    private val vadModelStore: VadModelStore,
    private val diarizationModelStore: DiarizationModelStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private data class BundledModel(
        val store: VerifiedModelStoreAccess,
        val assetPath: String,
        val label: String,
    )

    /** Minimal surface of the stores the provisioner needs. */
    private interface VerifiedModelStoreAccess {
        val isReady: Boolean
        suspend fun import(open: () -> java.io.InputStream): Result<Unit>
    }

    suspend fun provisionBundledModels() = withContext(ioDispatcher) {
        val assets = context.assets
        val models = listOf(
            BundledModel(
                store = object : VerifiedModelStoreAccess {
                    override val isReady get() = asrModelStore.status.value.isReady
                    override suspend fun import(open: () -> java.io.InputStream) =
                        asrModelStore.importModel(open)
                },
                assetPath = "models/gigaam-v3/v3_ctc.int8.onnx",
                label = "ASR (GigaAM v3)",
            ),
            BundledModel(
                store = object : VerifiedModelStoreAccess {
                    override val isReady get() = vadModelStore.status.value.isReady
                    override suspend fun import(open: () -> java.io.InputStream) =
                        vadModelStore.importModel(open)
                },
                assetPath = "models/silero-vad/silero_vad.onnx",
                label = "VAD (Silero)",
            ),
            BundledModel(
                store = object : VerifiedModelStoreAccess {
                    override val isReady get() = diarizationModelStore.isSegmentationReady()
                    override suspend fun import(open: () -> java.io.InputStream): Result<Unit> =
                        diarizationModelStore.importSegmentationModel(open)
                },
                assetPath = "models/diarization-segmentation/model.int8.onnx",
                label = "Diarization segmentation (pyannote 3.0)",
            ),
            BundledModel(
                store = object : VerifiedModelStoreAccess {
                    override val isReady get() = diarizationModelStore.isEmbeddingReady()
                    override suspend fun import(open: () -> java.io.InputStream): Result<Unit> =
                        diarizationModelStore.importEmbeddingModel(open)
                },
                assetPath = "models/diarization-embedding/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
                label = "Diarization embedding (CAM++)",
            ),
        )
        for (model in models) {
            if (model.store.isReady) continue
            if (!assetExists(assets, model.assetPath)) continue
            runCatching {
                model.store.import { assets.open(model.assetPath) }
            }
                .onSuccess { Log.i(TAG, "Provisioned bundled model: ${model.label}") }
                .onFailure { Log.w(TAG, "Bundled model provisioning failed: ${model.label}", it) }
        }
    }

    private fun assetExists(assets: AssetManager, path: String): Boolean =
        runCatching { assets.open(path).close(); true }.getOrDefault(false)

    private companion object {
        const val TAG = "BundledModels"
    }
}
