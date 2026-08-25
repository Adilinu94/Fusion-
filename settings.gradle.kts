// DropSync Projektstruktur gemaess Bauplan Abschnitt 3.2.
// Windows-Workaround (Gradle #6164): AGP propagiert den Daemon-Wert von
// java.library.path unquotiert in die JVM-Kommandozeile der Test-Worker.
// Enthaelt der Wert Leerzeichen ("C:\Program Files\..."), zerlegt die JVM
// das Argument und meldet "ClassNotFoundException: Files".
// Das Settings-Script laeuft vor allen Plugins; hier gesetzter Wert wird
// von AGP unveraendert uebernommen.
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
    System.setProperty("java.library.path", "$systemRoot\\System32")
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DropSync"

// :training-core — gemeinsamer Trainings-Kern (Git-Submodule, eigenes Repo).
// CONTEXT E3 + Punkt 1 (entschieden 2026-08-24): gewoehnliches Subprojekt mit
// gesetztem projectDir, KEIN Composite-Build. Fusion kompiliert den Quelltext
// mit der eigenen Toolchain; das settings.gradle.kts im Submodule bleibt dabei
// ohne Wirkung und dient nur dessen Standalone-CI.
// Guard: Nach einem Checkout ohne `--recurse-submodules` ist das Verzeichnis
// leer — dann bricht der Build mit einer lesbaren Meldung ab statt mit einem
// unverstaendlichen Plugin-Fehler.
val trainingCoreDir = file("training-core")
require(trainingCoreDir.resolve("build.gradle.kts").exists()) {
    "training-core fehlt. Submodule holen: git submodule update --init --recursive"
}
include(":training-core")
project(":training-core").projectDir = trainingCoreDir

// :app
include(":app")

// :benchmarks — Macrobenchmark + Baseline Profiles (Verbesserungsplan Phase 4)
include(":benchmarks")

// :core
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:designsystem")
include(":core:testing")

// :data
include(":data:audio") // ADR-0005
include(":data:health") // Herzfrequenz-Plan Phase 1
include(":data:library")
include(":data:playback")
include(":data:sensor") // Fusion Phase 4 (BLE-Zaehlung)
include(":data:settings")
include(":data:timer")
include(":data:workout")

// :domain
include(":domain:audio") // ADR-0005
include(":domain:health") // Herzfrequenz-Plan Phase 1
include(":domain:library") // ADR-0003
include(":domain:playback") // ADR-0004
include(":domain:sensor") // Fusion Phase 4 (BLE-Zaehlung)
include(":domain:settings")
include(":domain:timer")
include(":domain:workout")

// :feature
include(":feature:library")
include(":feature:audio") // ADR-0005 (DSP-/Audio-UI)
include(":feature:player")
include(":feature:progress") // Flowtimer-Integration (CONTEXT E8)
include(":feature:timer")
include(":feature:workout")
include(":feature:settings")

// :libs — optionale, extern gebaute Artefakte (Plan Phase 3, ADR-0006).
// Die FFmpeg-Decoder-Extension wird nur eingebunden, wenn sie per Flag
// aktiviert ist UND das Modul vorliegt (siehe docs/ffmpeg-build.md);
// Default bleibt aus, damit der Standardbuild ohne NDK-Artefakt laeuft.
val ffmpegEnabled = providers.gradleProperty("dropsync.enableFfmpeg").orNull.toBoolean()
val ffmpegModule = rootProject.projectDir.resolve("libs/media3-ffmpeg/build.gradle.kts")
if (ffmpegEnabled && ffmpegModule.exists()) {
    include(":libs:media3-ffmpeg")
}

// :baselineprofile wird gemaess ADR-0001 erst in Schritt 13 angelegt.
