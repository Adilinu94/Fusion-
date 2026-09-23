// :feature:workout — Trainings-Dashboard, Session, Routinen (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // D1: Screenshot-Gate fuer die Rest-Konsole (Roborazzi).
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
    namespace = "com.dropsync.feature.workout"
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
            // D1: Compose+Robolectric-Screenshots brauchen mehr Heap.
            it.maxHeapSize = "2g"
        }
        unitTests {
            // android.util.Log (Shadow-Pfad in TrainViewModel) ist in
            // JVM-Unit-Tests ein Stub; ohne diese Option wirft jeder
            // Log.d-Aufruf "Method not mocked" und beendet den Collector.
            isReturnDefaultValues = true
            // Robolectric fuer den JSONL-Recorder (Umbauplan 2026-09-04
            // Phase 0): der Schreibpfad haengt an Context.getExternalFilesDir,
            // das nur mit Android-Ressourcen aufloest.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:workout"))
    // Kalibrierungs-Wizard: Guided Calibration 2.0 (Stufen REST..REVIEW).
    implementation(project(":domain:sensor"))
    // Rest-Timer der Uebung nutzt die eine TimerEngine (Praezedenz
    // :feature:timer/:feature:player); DropSync-Rest via DropRestRequestBus.
    implementation(project(":domain:timer"))
    // Drop-Auto-Schalter ist persistiert (MP-13): Pausen-Musik-Einstellung.
    implementation(project(":domain:playback"))
    // C3: Bereitschaftsgrund am DropSync-Schalter (Pausen-/Work-Playlist).
    implementation(project(":domain:library"))
    // C3 (P-3/MP-9): Ducking der Pausenmusik am Ort (DspConfig.restDuckDb).
    implementation(project(":domain:audio"))
    // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Port ohne SDK-Leak.
    implementation(project(":domain:health"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // P3-Fix #28: Zurueck-Affordance im Kalibrierungs-Wizard braucht
    // Icons.AutoMirrored (RTL-korrekt) - wie in :feature:progress.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Interner NavHost des Trainings-Tabs (session/library/routines/...).
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    // Robolectric: JsonlShadowSessionRecorderTest braucht einen echten
    // Context fuer getExternalFilesDir (Umbauplan 2026-09-04 Phase 0.3).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // D1: Compose-Tests/Screenshots der Rest-Konsole (Roborazzi).
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
