// :data:library — MediaStore, Song- und Marker-Repository (Bauplan 3.2).
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dropsync.data.library"
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
            // Robolectric fuer Room-/FTS-Tests der Bibliotheksansichten auf der JVM.
            isIncludeAndroidResources = true
            // 2026-09-27: `android.util.Log` in Unit-Tests erlauben.
            //
            // Ausgangslage: `LibraryRepositoryImpl.refreshLibrary` loggt
            // seit dem Reconciliation-Fix (Befund 6.7) den Originalfehler
            // — im Test las sich das als `RuntimeException: Method e in
            // android.util.Log not mocked`. Der Test schlug damit an
            // der Log-Zeile fehl statt an der Sachaussage, und die
            // eigentliche Ursache blieb unsichtbar.
            //
            // Dieser Block **ueberschreibt** die Konvention
            // (`dropsync.android.library.gradle.kts`), weshalb die
            // Einstellung hier noetig ist und nicht nur dort.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":domain:library"))
    // Auto-Analyse beim Import (Phase 5): neue Songs stossen ihre
    // Waveform-Analyse direkt nach dem Scan an (TrackAnalysisRepository).
    implementation(project(":domain:audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
