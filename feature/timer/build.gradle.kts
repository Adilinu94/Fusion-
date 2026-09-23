// :feature:timer — Timer-Setup und laufender Timer (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Paket 5 (Befund 7.1.5): Screenshot-Gate fuer das Timer-Stellrad.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.dropsync.feature.timer"
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
            // Robolectric braucht die gemergten Ressourcen/Manifest fuer
            // createComposeRule (ComponentActivity-Aufloesung).
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:timer"))
    // C15 (PR-3): DropSync-Schalter im Standalone-Timer (Drop-Auto-Wahrheit).
    implementation(project(":domain:playback"))

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
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // B2: Stellrad-Interaktionstest (Robolectric + createComposeRule).
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Paket 5: Screenshot-Referenzen (captureRoboImage).
    testImplementation(libs.roborazzi)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
