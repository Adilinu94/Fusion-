// :feature:player — Player-UI (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem;
// kein ExoPlayer-Import (Zugriff nur ueber PlaybackRepository-Vertraege).
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // D1: Screenshot-Gate fuer den Now-Playing-Hero (Roborazzi).
    alias(libs.plugins.roborazzi)
}

// D1: CaptureType explizit — `recordRoborazziDebug` schreibt die
// Referenzbilder unter src/test/screenshots (nur lokal, nach Review),
// `verifyRoborazziDebug` vergleicht sie (CI-Gate). Der normale `test`-Task
// dumpt nur nach build/intermediates/roborazzi und laesst die Referenzen
// unberuehrt.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

android {
    namespace = "com.dropsync.feature.player"
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
            it.maxHeapSize = "2g"
        }
        unitTests {
            // C9: Compose-Tests brauchen die gemergten Ressourcen
            // (Robolectric + ui-test-junit4).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:audio"))
    implementation(project(":domain:playback"))
    implementation(project(":domain:library"))
    implementation(project(":domain:timer"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    // C9: Compose-Tests fuer die Marker-Textliste (Robolectric, ohne Geraet).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // D1: Screenshot-Tests des Now-Playing-Heros (Roborazzi).
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
