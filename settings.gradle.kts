// DropSync Projektstruktur gemaess Bauplan Abschnitt 3.2.
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

// :app
include(":app")

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
