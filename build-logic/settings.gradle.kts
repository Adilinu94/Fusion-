// build-logic — Convention-Plugins (Paket 4.19).
// Enthaelt die Modul-Konfiguration, die vorher in jeder build.gradle.kts
// wiederholt wurde: JVM-Target, Android-SDK-Versionen, Java-Toolchain und
// Robolectric-Testoptionen. Versionen kommen ausschliesslich aus dem
// Versionskatalog des Hauptbuilds (typeSafe accessors auf "libs").
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
