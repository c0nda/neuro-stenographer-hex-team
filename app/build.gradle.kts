import java.net.URI
import java.security.MessageDigest

buildscript {
    dependencies {
        // Used by downloadBundledModels to extract the segmentation model
        // from its .tar.bz2 distribution archive.
        classpath("org.apache.commons:commons-compress:1.28.0")
    }
}

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
    testOptions {
        // android.util.Log calls in the pipeline diagnostics become no-ops
        // (isLoggable returns false) under the local unit-test JVM.
        unitTests.isReturnDefaultValues = true
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

// Forward the desktop verification harness properties (-Dgigaam.*) into the
// unit-test JVM; Gradle does not propagate arbitrary -D system properties.
tasks.withType<Test>().configureEach {
    listOf("gigaam.model", "gigaam.clip", "gigaam.reference").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

// Bundled model provisioning (Phase 1 sanctioned "Gradle download task"
// path): the four model artifacts are fetched at BUILD time from their
// official sources, verified against the same size+SHA-256 constants the
// runtime stores enforce, and packaged into assets. At the first app launch
// BundledModelProvisioner copies them into private storage through the same
// verified import path. Model binaries are gitignored — not committed.
// Skip with -PskipBundledModels.
tasks.register("downloadBundledModels") {
    description = "Downloads and verifies the bundled ML model artifacts"
    group = "build"
    // Accesses project/layout at execution time (external download).
    notCompatibleWithConfigurationCache("model download task")
    onlyIf { !project.hasProperty("skipBundledModels") }

    val specs = listOf(
        ModelSpec(
            url = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/main/v3_ctc.int8.onnx",
            assetPath = "models/gigaam-v3/v3_ctc.int8.onnx",
            expectedSize = 224_721_181L,
            sha256 = "ceb61454e2e1a2dec5872cbac1de0fe0a4271d1148f6b26b5bda53ff30a12acd",
        ),
        ModelSpec(
            url = "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx",
            assetPath = "models/silero-vad/silero_vad.onnx",
            expectedSize = 2_327_524L,
            sha256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
        ),
        ModelSpec(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            assetPath = "models/diarization-segmentation/model.int8.onnx",
            expectedSize = 1_540_506L,
            sha256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
            archiveMember = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx",
        ),
        ModelSpec(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
            assetPath = "models/diarization-embedding/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
            expectedSize = 29_596_978L,
            sha256 = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
        ),
    )

    outputs.upToDateWhen { false } // cheap no-op when cache is valid; checked per file

    doLast {
        val assetsDir = layout.projectDirectory.dir("src/main/assets")
        for (spec in specs) {
            val target = assetsDir.file(spec.assetPath).asFile
            target.parentFile.mkdirs()
            if (target.isFile && target.length() == spec.expectedSize &&
                sha256(target) == spec.sha256
            ) {
                println("[downloadBundledModels] cached: ${spec.assetPath}")
                continue
            }
            println("[downloadBundledModels] downloading ${spec.url}")
            val part = File(target.parentFile, target.name + ".part")
            part.delete()
            URI(spec.url).toURL().openStream().buffered().use { input ->
                part.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            // The segmentation artifact ships inside a .tar.bz2 archive.
            val extracted: File = if (spec.archiveMember != null) {
                val tmp = File(target.parentFile, "archive.part")
                part.copyTo(tmp, overwrite = true)
                extractTarBz2Member(tmp, spec.archiveMember).also {
                    tmp.delete()
                    part.delete()
                }
            } else {
                part
            }
            check(extracted.length() == spec.expectedSize) {
                "${spec.assetPath}: size ${extracted.length()}, expected ${spec.expectedSize}"
            }
            check(sha256(extracted) == spec.sha256) {
                "${spec.assetPath}: SHA-256 does not match the pinned artifact"
            }
            check(!target.exists() || target.delete()) { "Could not replace $target" }
            check(extracted.renameTo(target)) { "Could not move $extracted to $target" }
            println("[downloadBundledModels] ready: ${spec.assetPath}")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach {
        dependsOn("downloadBundledModels")
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

    // sherpa-onnx is not published to Maven Central for Android. The official
    // prebuilt AAR (static-link-onnxruntime variant, SHA-256
    // 07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575)
    // from https://github.com/k2-fsa/sherpa-onnx/releases (v2.0.1, Apache-2.0)
    // is vendored in the in-project repository at <root>/local-repo (see
    // settings.gradle.kts) and consumed as a normal coordinate. A plain
    // files("*.aar") dependency hits the AGP "Null extracted folder" bug in
    // Android Studio. See docs/PHASE4B_DIARIZATION.md.
    // Consumed as a committed vendored file: a bare maven coordinate made
    // some AGP tasks probe for a nonexistent sherpa-onnx-2.0.1.jar, and the
    // file dependency only failed earlier because the root .gitignore
    // ignored *.aar so the file never reached the repository (fixed).
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Desktop ONNX Runtime for the JVM-only verification harness
    // (GigaamDesktopWerTest): runs the exact production ASR engine on a PC
    // against real audio to measure WER without an Android device. Same
    // ai.onnxruntime API as onnxruntime-android; never packaged into the APK.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

class ModelSpec(
    val url: String,
    val assetPath: String,
    val expectedSize: Long,
    val sha256: String,
    val archiveMember: String? = null,
)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Extracts one member of a .tar.bz2 archive (no external tools needed). */
fun extractTarBz2Member(archive: File, memberName: String): File {
    val bzIn = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
        archive.inputStream().buffered(),
    )
    val tarIn = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bzIn)
    tarIn.use {
        while (true) {
            val entry = it.nextTarEntry ?: break
            if (entry.name == memberName && entry.isFile) {
                // Distinct temp name: the member often has the same final name
                // as the target file, and renameTo() cannot replace itself.
                val out = File(archive.parentFile, memberName.substringAfterLast('/') + ".extracted")
                out.delete()
                out.outputStream().buffered().use { output -> it.copyTo(output) }
                return out
            }
        }
    }
    error("Member $memberName not found in $archive")
}
