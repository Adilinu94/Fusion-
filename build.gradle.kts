// Root-Build: deklariert Plugins zentral und konfiguriert Formatierung.
// Versionen stehen ausschliesslich in gradle/libs.versions.toml (Bauplan 3.1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Kotlin-Formatierung und statische Analyse (Bauplan Schritt 1.4).
// Lizenzpruefung: Spotless (Apache-2.0) und ktlint (MIT) sind in
// THIRD_PARTY_NOTICES.md dokumentiert.
spotless {
    kotlin {
        target("**/*.kt")
        // training-core ist ein eigenes Repo mit eigener CI (CONTEXT E3);
        // Fusion formatiert dessen Quellen nicht mit.
        targetExclude("**/build/**", "training-core/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "training-core/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("**/*.md", "**/.gitignore")
        targetExclude("**/build/**", "training-core/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Statische Analyse (Umbauplan Phase 11): Detekt ergaenzt ktlint um
// Code-Smell-Erkennung. Ein Lauf im Root-Projekt ueber die src-Verzeichnisse
// (Build-Outputs sind nicht enthalten, damit keine Task-Kollisionen mit
// process*-Tasks entstehen). Lizenz: detekt (Apache-2.0), siehe
// THIRD_PARTY_NOTICES.md.
//
// C5 (B-ARCH-4): Die Quelle wird ABGELEITET statt manuell aufgezaehlt.
// Vorher fehlten Module still (Befund: benchmarks/progress), weil die Liste
// per Hand gepflegt wurde. Hier werden alle `src`-Verzeichnisse der App-Module
// eingesammelt; neue Module laufen automatisch mit. Bewusst NICHT dabei:
// training-core (Git-Submodul, Fremdcode) und libs/media3-ffmpeg (kein Kotlin).
private val detektSourceDirs: List<File> =
    rootDir
        .listFiles()
        .orEmpty()
        .filter {
            it.isDirectory &&
                it.name !in setOf("build", "training-core", "libs", "gradle", "docs", "config", "tools", "scripts")
        }.flatMap { top ->
            buildList {
                File(top, "src").takeIf { it.isDirectory }?.let(::add)
                top
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .map { File(it, "src") }
                    .filter { it.isDirectory }
                    .forEach(::add)
            }
        }

detekt {
    source.setFrom(detektSourceDirs)
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline.set(file("config/detekt/baseline.xml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
}
