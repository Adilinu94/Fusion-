// :core:designsystem — Theme, wiederverwendbare Compose-Komponenten (Bauplan 3.2).
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // C1: Screenshot-Tests ohne Geraet (Roborazzi-Gate).
    alias(libs.plugins.roborazzi)
}

// C1: Referenzbilder versioniert unter src/test/screenshots (CI vergleicht).
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

android {
    namespace = "com.dropsync.core.designsystem"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Phase 1: Groesserer Heap fuer Unit-Tests mit Compose-Abhaengigkeiten.
    testOptions {
        unitTests.all {
            it.maxHeapSize = "2g"
        }
        unitTests {
            // Roborazzi braucht die gemergten Ressourcen/Manifest.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    androidTestImplementation(composeBom)

    // Stabile Domaenen-Enums (z. B. AccentColor fuer das Theme).
    implementation(project(":core:model"))

    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM-Test der Waveform-Koordinaten (WaveformBucketMappingTest).
    testImplementation(libs.junit4)
    // C1: Screenshot-Tests der Designsystem-Komponenten (+ Robolectric-Basis).
    testImplementation(libs.roborazzi)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
