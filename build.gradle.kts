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
detekt {
    source.setFrom(
        files(
            "app/src",
            // benchmarks fehlte neben feature/progress (Befund B-ARCH-4):
            // die Liste wird manuell gepflegt, jede Auslassung bleibt still.
            // Bewusst NICHT hier: training-core (Git-Submodul, gepinnt auf
            // v1.0.0 - Fremdcode, dessen Findings nur im eigenen Repo
            // behebbar sind) und libs/media3-ffmpeg (kein Kotlin-Code).
            "benchmarks/src",
            "core/common/src",
            "core/model/src",
            "core/database/src",
            "core/designsystem/src",
            "core/testing/src",
            "data/audio/src",
            "data/health/src",
            "data/library/src",
            "data/playback/src",
            "data/sensor/src",
            "data/settings/src",
            "data/timer/src",
            "data/workout/src",
            "domain/audio/src",
            "domain/health/src",
            "domain/library/src",
            "domain/playback/src",
            "domain/sensor/src",
            "domain/settings/src",
            "domain/timer/src",
            "domain/workout/src",
            "feature/library/src",
            "feature/audio/src",
            "feature/player/src",
            "feature/progress/src",
            "feature/timer/src",
            "feature/workout/src",
            "feature/settings/src",
        ),
    )
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline.set(file("config/detekt/baseline.xml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
}
