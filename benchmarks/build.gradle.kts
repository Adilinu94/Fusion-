// :benchmarks — Macrobenchmark + Baseline-Profile-Generierung (Phase 4).
// Läuft auf einem Gerät/Emulator (com.android.test), nie in der CI-Testkette.
// AGP 9: com.android.test mit eingebautem Kotlin.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.dropsync.benchmarks"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    // Gemessene App (com.android.test-Pflichtfeld).
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.benchmark.macro.junit4.MacrobenchmarkRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Macrobenchmark misst die Release-App; debuggable=false ist Pflicht.
    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(project(":app"))
}
