plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.vorontsov.neurostenographer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vorontsov.neurostenographer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // x86 is excluded: the sherpa-onnx static-link AAR ships a dynamically
        // linked x86 build that would collide with onnxruntime-android's
        // libonnxruntime.so, and modern emulators are x86_64 anyway. Physical
        // devices (arm64-v8a, armeabi-v7a) are unaffected.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Only the x86 sherpa-onnx build links ONNX Runtime dynamically and
            // would collide with onnxruntime-android's copy at merge time; all
            // other ABIs link it statically inside libsherpa-onnx-jni.so. x86
            // is 32-bit emulator-only (devices are arm64-v8a/armeabi-v7a,
            // modern emulators x86_64), so picking the app-bundled ORT there
            // is an acceptable, documented trade-off.
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.onnxruntime.android)
    implementation(libs.jtransforms)

    // sherpa-onnx is not published to Maven Central for Android; the official
    // prebuilt AAR (static-link-onnxruntime variant, SHA-256
    // 07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575)
    // from https://github.com/k2-fsa/sherpa-onnx/releases (v2.0.1, Apache-2.0)
    // is vendored. See docs/PHASE4B_DIARIZATION.md.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
