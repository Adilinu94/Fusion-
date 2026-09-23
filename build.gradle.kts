// Root-Build: deklariert Plugins zentral und konfiguriert Formatierung.
// Versionen stehen ausschliesslich in gradle/libs.versions.toml (Bauplan 3.1).
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

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
    // D3: Coverage-Gate (nur auf die Kern-Module angewendet, s. u.).
    alias(libs.plugins.kover) apply false
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

// D3 (Entscheidung 5.4): Coverage-Gate fuer die Kern-Module `domain:*` und
// `data:*` mit dem Ziel 60 % Linien-Coverage. Die UI-Feature-Module bleiben
// bewusst aussen vor (ihr Wert liegt in Compose-/Screenshot-Tests, nicht in
// Zeilen-Coverage).
//
// Die Untergrenzen sind der gemessene Ist-Stand (Kover LINE, 2026-09-22,
// nach Ausschluss des generierten Hilt-/Dagger-Codes), abgerundet — eine
// RATSCHE: sie duerfen nur steigen. Module unter dem Ziel sind
// Abbau-Kandidaten; 0 = faktisch ungetestet, vorerst nur Report.
// Gate: `./gradlew koverVerify` (laeuft in der CI mit den Unit-Tests).
private val coverageFloors: Map<String, Int> =
    mapOf(
        "domain:audio" to 87,
        "domain:health" to 81,
        "domain:library" to 77,
        "domain:playback" to 41,
        "domain:sensor" to 88,
        "domain:settings" to 0,
        "domain:timer" to 86,
        "domain:workout" to 75,
        "data:audio" to 64,
        "data:health" to 42,
        "data:library" to 62,
        "data:playback" to 24,
        "data:sensor" to 47,
        "data:settings" to 55,
        "data:timer" to 53,
        "data:workout" to 78,
    )

subprojects {
    val floor = coverageFloors[path.removePrefix(":")]
    if (floor != null) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
        extensions.configure<KoverProjectExtension>("kover") {
            reports {
                // Generierter Hilt-/Dagger-Code (Factories, Member-Injectors,
                // Hilt_-Wrapper, Aggregations-Paket) ist nicht von Hand
                // testbar und wuerde die Linien-Coverage mit 0 %-Klassen je
                // Injektionspunkt verwaessern; er zaehlt nicht in die Messung.
                filters {
                    excludes {
                        classes(
                            "*_Factory",
                            "*_MembersInjector",
                            "*_GeneratedInjector",
                            "*Hilt_*",
                            "*hilt_aggregated_deps*",
                        )
                    }
                }
                if (floor > 0) {
                    verify {
                        rule {
                            minBound(floor)
                        }
                    }
                }
            }
        }
    }
}
