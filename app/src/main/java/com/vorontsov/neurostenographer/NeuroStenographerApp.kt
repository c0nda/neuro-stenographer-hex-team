package com.vorontsov.neurostenographer

import android.app.Application
import com.vorontsov.neurostenographer.data.file.BundledModelProvisioner
import com.vorontsov.neurostenographer.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class NeuroStenographerApp : Application() {

    @Inject
    lateinit var bundledModelProvisioner: BundledModelProvisioner

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Eagerly load the ONNX Runtime core with global symbol visibility
        // BEFORE anything else touches native code. Mixed ORT versions in one
        // process are a documented source of
        // "dlopen failed: cannot locate symbol OrtGetApiBase"
        // (k2-fsa/sherpa-onnx#566); loading the matching core first binds the
        // JNI library to the right image. Version-pinned in libs.versions.toml.
        runCatching { System.loadLibrary("onnxruntime") }
            .onFailure { android.util.Log.w("NeuroStenographerApp", "preload libonnxruntime failed", it) }
        // If the APK was built with bundled model artifacts, install them on
        // first launch (verified import; no-op when already imported or when
        // the build used -PskipBundledModels).
        applicationScope.launch {
            bundledModelProvisioner.provisionBundledModels()
        }
    }
}
