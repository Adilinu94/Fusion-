// :benchmarks — Macrobenchmark + Baseline-Profile-Generierung (Phase 4).
// Läuft auf einem Gerät/Emulator (com.android.test), nie in der CI-Testkette.
// AGP 9: com.android.test mit eingebautem Kotlin.
plugins {
    alias(libs.plugins.android.test)
    // Bewusst KEIN kotlin.compose (Befund B-UI-5): das Modul enthaelt keine
    // Composables, nur Macrobenchmark-Tests. Der Compose-Compiler verlangt
    // aber die Compose-Runtime auf dem Compile-Classpath, und die kommt hier
    // nicht an — `implementation(project(":app"))` reicht sie nicht weiter.
    // Das Plugin brach den Build, ohne je etwas zu leisten.
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
    // JUnit4 und der AndroidJUnit4-Runner (Befund B-UI-5): beide wurden in
    // cb7efda beim Modulumbau entfernt, wodurch :benchmarks seit diesem
    // Commit nicht mehr kompilierte. Aufgefallen ist es nie, weil die CI
    // com.android.test-Module nicht baut - `test`, `assembleDebug` und
    // `assembleRelease` fassen sie nicht an. Ein Modul ohne
    // Compiler-Abdeckung verrottet still.
    implementation(libs.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(project(":app"))
}
