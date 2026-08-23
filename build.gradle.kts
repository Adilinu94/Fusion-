// Root-Build: deklariert Plugins zentral und konfiguriert Formatierung.
// Versionen stehen ausschliesslich in gradle/libs.versions.toml (Bauplan 3.1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// Kotlin-Formatierung und statische Analyse (Bauplan Schritt 1.4).
// Lizenzpruefung: Spotless (Apache-2.0) und ktlint (MIT) sind in
// THIRD_PARTY_NOTICES.md dokumentiert.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("**/*.md", "**/.gitignore")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
