// :feature:library — Musikbibliothek-UI (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Paket 5 (Befund 7.1.5): Screenshot-Gate fuer die Bibliotheks-Startseite.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.dropsync.feature.library"
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            // D4: Compose+Robolectric-Tests brauchen mehr Heap.
            it.maxHeapSize = "2g"
        }
        unitTests {
            // D4: Compose-UI-Tests (Marker-Review, Work-/Rest-Karten) laufen
            // mit Robolectric gegen die gemergten Ressourcen.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:audio"))
    implementation(project(":domain:library"))
    implementation(project(":domain:playback"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    // C4: LibraryViewModel-Tests (Flows + Turbine).
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // D4: Compose-UI-Tests der Home-Sektionen (Robolectric, ohne Geraet).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Paket 5: Screenshot-Referenzen (captureRoboImage).
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
