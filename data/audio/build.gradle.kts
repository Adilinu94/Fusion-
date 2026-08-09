// :data:audio — DSP-Pipeline, Audio-Settings, RenderersFactory (ADR-0005).
// Neben :data:playback das einzige Modul mit Media3-Abhaengigkeit.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dropsync.data.audio"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric fuer AudioProcessor-/DataStore-Tests auf der JVM.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":domain:audio"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Aufschiebbare Track-Analyse (Marker/Waveform-Plan Phase 2):
    // OneTimeWorkRequest, dedupliziert ueber track_analysis_<songId>.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Optionale FFmpeg-Decoder-Extension (Plan Phase 3, ADR-0006): nur wenn
    // per Flag aktiviert UND das lokale Modul vorliegt. Die DspRenderersFactory
    // bevorzugt sie dann via EXTENSION_RENDERER_MODE_PREFER; Default ist aus
    // (Plattformdecoder). Aufbau/Skript: docs/ffmpeg-build.md.
    if (providers.gradleProperty("dropsync.enableFfmpeg").orNull.toBoolean() &&
        findProject(":libs:media3-ffmpeg") != null
    ) {
        implementation(project(":libs:media3-ffmpeg"))
    }

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
