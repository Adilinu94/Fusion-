// dropsync.android.library: Android-Library-Module (core:*, data:*, feature:*).
// Zentralisiert: namespace-Regel (Paketname), SDK-Versionen aus dem Katalog,
// Java 17, Instrumentation-Runner und Robolectric-Ressourcen (Bericht
// Paket 4.19). AGP 9: Kotlin-Support ist im Android-Plugin eingebaut.
//
// Katalog-Zugriff ueber die VersionCatalogsExtension-API: im included Build
// erzeugt Gradle fuer die Precompiled-Script-Plugins keine typeSafe
// "libs"-Accessors, die Extension-API funktioniert in beiden Faellen.
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
}

// Versionskatalog des Hauptbuilds ("libs" aus gradle/libs.versions.toml).
private val catalog =
    extensions
        .getByName("versionCatalogs")
        as VersionCatalogsExtension

private val catalogLibs = catalog.named("libs")

android {
    namespace =
        project.path
            .removePrefix(":")
            .replace(":", ".")
            .let { segments ->
                // data/audio -> com.dropsync.data.audio (Gruppe + Pfadsegmente).
                "com.dropsync.$segments"
            }
    compileSdk =
        catalogLibs
            .findVersion("compileSdk")
            .get()
            .requiredVersion
            .toInt()

    defaultConfig {
        minSdk =
            catalogLibs
                .findVersion("minSdk")
                .get()
                .requiredVersion
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric-Tests (DataStore, Room, Compose) brauchen die
            // Manifest-/Ressourcen-Zusammenfuehrung auf der JVM.
            isIncludeAndroidResources = true
        }
    }
}
