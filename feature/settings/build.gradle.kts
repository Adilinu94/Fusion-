// :feature:settings — Einstellungen, Markerimport-UI (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Paket 5 (Befund 7.1.5): Screenshot-Gate fuer die Einstellungen.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.dropsync.feature.settings"
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
            // D4: Compose+Robolectric-Test der Sektionsliste braucht mehr Heap.
            it.maxHeapSize = "2g"
        }
        unitTests {
            // Robolectric: SettingsViewModel bekommt einen Context per
            // Konstruktor (SAF-Import/Export). Die Zustandslogik selbst
            // braucht ihn nicht, aber ohne Android-Ressourcen laesst sich das
            // ViewModel gar nicht bauen.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:audio"))
    implementation(project(":domain:health"))
    implementation(project(":domain:library"))
    implementation(project(":domain:playback"))
    implementation(project(":domain:sensor"))
    implementation(project(":domain:settings"))
    implementation(project(":domain:timer"))
    implementation(project(":domain:workout"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // D4: Compose-UI-Test der Einstellungs-Sektionen (Robolectric, ohne Geraet).
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Paket 5: Screenshot-Referenzen (captureRoboImage).
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
