// :data:health — Health-Connect-Anbindung der Herzfrequenz (Herzfrequenz-Plan 3.3).
// Einziges Modul mit connect-client-Abhaengigkeit; :domain:health bleibt SDK-frei.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dropsync.data.health"
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
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain:health"))

    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
