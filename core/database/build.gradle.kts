// :core:database — Room, DAOs, Migrationen; keine UI (Bauplan 3.2).
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dropsync.core.database"
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
            // Robolectric: Datenbanktests laufen auf der JVM (Schritt 3/13).
            isIncludeAndroidResources = true
        }
    }
}

// Schemas werden ins Repository exportiert und per Migrationstest geprueft
// (Bauplan Schritt 3.5). Exportziel sind die Test-Assets, damit der
// MigrationTestHelper jede Version direkt laden kann; die Dateien sind
// dadurch trotzdem versionskontrolliert.
room {
    schemaDirectory("$projectDir/src/test/assets")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))

    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
