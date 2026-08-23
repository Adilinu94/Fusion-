// :benchmarks - Macrobenchmark + Baseline-Profile-Generierung (Phase 4).
// Laeuft auf einem Geraet/Emulator (com.android.test), nie in der CI-Testkette.
// AGP 9: com.android.test mit eingebautem Kotlin. Kein Compose-Plugin: der
// Benchmark enthaelt keine Composables, und ohne Compose-Runtime im
// Classpath bricht der Compose-Compiler den Build ab.
plugins {
    alias(libs.plugins.android.test)
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

    // Macrobenchmark misst die Release-App. Die Build-Typen legt das
    // baselineprofile-Plugin an (benchmarkRelease/nonMinifiedRelease); ein
    // eigener "benchmark"-Typ wuerde damit kollidieren.
    //
    // benchmarkRelease misst die minifizierte App. AGP verlangt, dass das
    // Testprojekt dann ebenfalls minifiziert (checkTestedAppObfuscation),
    // sonst finden die Testklassen die umbenannten App-Symbole nicht.
    // configureEach, weil das Plugin den Typ erst nach diesem Block anlegt.
    // AGP warnt, dass der Schalter bei einem debuggable Test-APK wirkungslos
    // ist -- das ist richtig und gewollt: er erfuellt nur die Pruefung.
    buildTypes.configureEach {
        if (name == "benchmarkRelease") {
            isMinifyEnabled = true
        }
    }
}

// Laeuft gegen ein angeschlossenes Geraet/Emulator, nie in der CI.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.junit4)
    implementation(project(":app"))
}
