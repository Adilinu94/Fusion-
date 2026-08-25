package com.dropsync.core.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Modulabhaengigkeitstest (Bauplan Schritt 2.6): prueft die Regeln aus
 * Abschnitt 3.2 auf Ebene der Gradle-Build-Dateien. Ein Verstoss muss den
 * Testlauf — und damit CI — fehlschlagen lassen.
 *
 * Regeln:
 * 1. `:core:model` haengt von keinem anderen App-Modul ab.
 * 2. `:domain:*` ist ein reines JVM-Modul ohne Android-, Room- oder
 *    Media3-/ExoPlayer-Abhaengigkeit.
 * 3. `:feature:*` kennt weder Room noch Media3 noch `:core:database`
 *    und importiert kein anderes Feature.
 * 4. `:data:*` kennt keine Feature-Module.
 */
class ModuleDependencyRulesTest {
    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
                ?: error("Repo-Wurzel mit settings.gradle.kts nicht gefunden")
        }
        dir
    }

    private fun buildFile(modulePath: String): String {
        val file = File(repoRoot, "$modulePath/build.gradle.kts")
        assertTrue("Build-Datei fehlt: ${file.path}", file.exists())
        return file.readText()
    }

    private fun modulesUnder(dirName: String): List<Pair<String, String>> {
        val dir = File(repoRoot, dirName)
        assertTrue("Modulverzeichnis fehlt: $dirName", dir.isDirectory)
        return dir
            .listFiles { f -> f.isDirectory && File(f, "build.gradle.kts").exists() }!!
            .map { "$dirName/${it.name}" to it.resolve("build.gradle.kts").readText() }
    }

    private fun assertNotContains(
        module: String,
        content: String,
        forbidden: List<String>,
        reason: String,
    ) {
        for (token in forbidden) {
            assertTrue(
                "Regelverstoss in $module: '$token' ist verboten ($reason)",
                !content.contains(token),
            )
        }
    }

    @Test
    fun `core model haengt von keinem anderen app modul ab`() {
        val content = buildFile("core/model")
        assertNotContains(
            "core/model",
            content,
            listOf("project(\":"),
            "Regel 3.2/1: keine Projektabhaengigkeiten",
        )
    }

    @Test
    fun `domain module sind reine jvm module ohne room und player`() {
        for ((module, content) in modulesUnder("domain")) {
            assertNotContains(
                module,
                content,
                listOf(
                    "android.library",
                    "android.application",
                    "androidx.room",
                    "libs.androidx.room",
                    "androidx.media3",
                    "libs.androidx.media3",
                    "exoplayer",
                    "project(\":core:database\")",
                    "project(\":data:",
                    "project(\":feature:",
                ),
                "Regel 3.2/2: Domain kennt kein Android-UI, Room oder ExoPlayer",
            )
        }
    }

    @Test
    fun `feature module kennen weder room noch media3 noch andere features`() {
        for ((module, content) in modulesUnder("feature")) {
            assertNotContains(
                module,
                content,
                listOf(
                    "androidx.room",
                    "libs.androidx.room",
                    "androidx.media3",
                    "libs.androidx.media3",
                    "exoplayer",
                    "project(\":core:database\")",
                    "project(\":feature:",
                    "project(\":data:",
                ),
                "Regel 3.2/4: Features nutzen nur Domain, UI-State und Designsystem",
            )
        }
    }

    @Test
    fun `data module kennen keine feature module`() {
        for ((module, content) in modulesUnder("data")) {
            assertNotContains(
                module,
                content,
                listOf("project(\":feature:", "project(\":app\")"),
                "Regel 3.2/3: Data implementiert Domain-Schnittstellen, keine UI",
            )
        }
    }

    @Test
    fun `alle bauplan module existieren`() {
        // Vollstaendige Liste aller Pflichtmodule gemaess settings.gradle.kts
        // (ADR-0016): zuvor fehlten health, sensor, audio und settings. Die
        // optionalen Module :benchmarks und :libs:media3-ffmpeg bleiben
        // bewusst draussen, da sie nicht in jedem Build existieren.
        // :training-core ist Pflicht, obwohl Submodule: settings.gradle.kts
        // bricht ohne dieses Verzeichnis ab (CONTEXT E3, Punkt 1).
        val required =
            listOf(
                "app",
                "training-core",
                "core/common",
                "core/model",
                "core/database",
                "core/designsystem",
                "core/testing",
                "data/audio",
                "data/health",
                "data/library",
                "data/playback",
                "data/sensor",
                "data/settings",
                "data/timer",
                "data/workout",
                "domain/audio",
                "domain/health",
                "domain/library",
                "domain/playback",
                "domain/sensor",
                "domain/settings",
                "domain/timer",
                "domain/workout",
                "feature/audio",
                "feature/library",
                "feature/player",
                "feature/progress",
                "feature/settings",
                "feature/timer",
                "feature/workout",
            )
        for (module in required) {
            assertTrue(
                "Bauplan-Modul fehlt: $module",
                File(repoRoot, "$module/build.gradle.kts").exists(),
            )
        }
    }
}
