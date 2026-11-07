package com.vorontsov.neurostenographer.ml.asr.gigaam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vorontsov.neurostenographer.data.asr.AsrModelStore
import com.vorontsov.neurostenographer.domain.asr.AsrModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the build actually bundled the size-verified GigaAM v3 asset and
 * that [AsrModelStore] installs it into private storage without user input.
 *
 * Skipped when the APK was built without the git-ignored model binary.
 */
@RunWith(AndroidJUnit4::class)
class BundledAsrModelTest {

    @Test
    fun bundledAssetIsProvisionedIntoPrivateStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assetName = "models/gigaam-v3/v3_ctc.int8.onnx"
        assumeTrue(
            "The APK was built without the bundled model asset",
            context.assets.list("models/gigaam-v3")?.contains("v3_ctc.int8.onnx") == true,
        )

        context.assets.openFd(assetName).use { descriptor ->
            assertEquals(EXPECTED_SIZE, descriptor.declaredLength)
        }

        val installed = File(context.filesDir, assetName)
        val store = AsrModelStore(context, Dispatchers.IO)
        store.deleteModel()
        assertTrue(!installed.exists())

        val firstStarted = System.nanoTime()
        store.ensureProvisioned()
        val provisionMs = (System.nanoTime() - firstStarted) / 1_000_000
        val firstStatus = store.status.value
        println("Bundled model provisioned in ${provisionMs}ms")

        assertEquals(AsrModelState.READY, firstStatus.state)
        assertEquals(EXPECTED_SIZE, firstStatus.sizeBytes)
        assertEquals(EXPECTED_SIZE, installed.length())

        // A second call must re-verify rather than copy again.
        store.ensureProvisioned()
        assertEquals(AsrModelState.READY, store.status.value.state)
        assertEquals(EXPECTED_SIZE, installed.length())
    }

    private companion object {
        const val EXPECTED_SIZE = 224_721_181L
    }
}
