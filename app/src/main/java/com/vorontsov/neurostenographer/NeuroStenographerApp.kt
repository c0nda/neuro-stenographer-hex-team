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
        // If the APK was built with bundled model artifacts, install them on
        // first launch (verified import; no-op when already imported or when
        // the build used -PskipBundledModels).
        applicationScope.launch {
            bundledModelProvisioner.provisionBundledModels()
        }
    }
}
