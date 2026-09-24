// build-logic: Convention-Plugins (Paket 4.19). typeSafe Katalog-Zugriff
// auf die Plugin-Aliase des Hauptbuilds fuer compileOnly-Abhaengigkeiten.
plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Plugin-Marker-Koordinaten: <id>:<id>.gradle.plugin:<version>.
    compileOnly(
        libs.plugins.android.library.get().let {
            "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
        },
    )
    compileOnly(
        libs.plugins.kotlin.jvm.get().let {
            "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
        },
    )
    compileOnly(
        libs.plugins.kotlin.compose.get().let {
            "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
        },
    )
    compileOnly(
        libs.plugins.kover.get().let {
            "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version.requiredVersion}"
        },
    )
}
