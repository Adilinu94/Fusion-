package com.dropsync.core.testing

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Modulabhaengigkeitstest (Bauplan Schritt 2.6): prueft die Regeln aus
 * Abschnitt 3.2 auf **zwei** Ebenen — Gradle-Deklaration und Kotlin-Import.
 * Ein Verstoss muss den Testlauf — und damit CI — fehlschlagen lassen.
 *
 * Regeln:
 * 1. `:core:model` haengt von keinem anderen App-Modul ab.
 * 2. `:domain:*` ist ein reines JVM-Modul ohne Android-, Room- oder
 *    Media3-/ExoPlayer-Abhaengigkeit.
 * 3. `:feature:*` kennt weder Room noch Media3 noch `:core:database`
 *    und importiert kein anderes Feature.
 * 4. `:data:*` kennt keine Feature-Module.
 *
 * Warum zwei Ebenen (Verbesserungsplan B-ARCH-1): Die Deklarationspruefung
 * findet nur, was ein Modul selbst als Abhaengigkeit auffuehrt. Sie ist blind
 * fuer **transitive Sichtbarkeit** — `core/database` gibt Room per `api(...)`
 * weiter, `core/designsystem` ebenso Compose. Ein `import androidx.room.*`
 * in einem Modul, das Room dadurch sieht, kompiliert und lief bisher durch
 * jeden Test. Die Importpruefung schliesst genau diese Luecke.
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

    // --- Importpruefung (B-ARCH-1) ------------------------------------------

    /** Alle `import`-Zeilen im Produktivcode eines Moduls, mit Fundort. */
    private fun productionImports(modulePath: String): List<Pair<File, String>> {
        val sourceRoot = File(repoRoot, "$modulePath/src/main")
        if (!sourceRoot.isDirectory) return emptyList()
        return sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file
                    .readLines()
                    .map(String::trim)
                    .filter { it.startsWith("import ") }
                    .map { file to it.removePrefix("import ").removeSuffix(";") }
            }.toList()
    }

    private fun assertNoImportMatching(
        modulePath: String,
        forbiddenPrefixes: List<String>,
        reason: String,
        allow: (String) -> Boolean = { false },
    ) {
        val violations =
            productionImports(modulePath)
                .filter { (_, import) ->
                    forbiddenPrefixes.any { import.startsWith(it) } && !allow(import)
                }.map { (file, import) ->
                    "${file.relativeTo(repoRoot).path}: import $import"
                }
        assertTrue(
            "Regelverstoss in $modulePath ($reason):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * Selbsttest: findet die Pruefung ueberhaupt Imports? Ohne diesen Fall
     * waeren alle Importtests auch dann gruen, wenn `productionImports` nichts
     * liefert — etwa weil sich eine Pfadkonvention geaendert hat.
     */
    @Test
    fun `die importpruefung liest wirklich quelldateien`() {
        val imports = productionImports("domain/audio")
        assertTrue(
            "Keine Imports in domain/audio gefunden - die Pruefung greift ins Leere",
            imports.size > 20,
        )
        assertTrue(
            "Erwartet mindestens eine Kotlin-Datei mit kotlin.math-Import",
            imports.any { it.second.startsWith("kotlin.") },
        )
    }

    @Test
    fun `core model importiert kein anderes app modul`() {
        assertNoImportMatching(
            "core/model",
            listOf(
                "com.dropsync.domain.",
                "com.dropsync.data.",
                "com.dropsync.feature.",
                "com.dropsync.core.database.",
                "com.dropsync.core.designsystem.",
                "androidx.room.",
                "androidx.media3.",
            ),
            "Regel 3.2/1: core:model ist das Fundament und importiert nichts aus der App",
        )
    }

    @Test
    fun `domain module importieren kein android room oder media3`() {
        for ((module, _) in modulesUnder("domain")) {
            assertNoImportMatching(
                module,
                listOf(
                    "android.",
                    "androidx.",
                    "com.google.android.exoplayer",
                    "com.dropsync.core.database.",
                    "com.dropsync.data.",
                    "com.dropsync.feature.",
                ),
                "Regel 3.2/2: Domain ist reines JVM ohne Android, Room oder ExoPlayer",
                // javax.inject und jakarta sind JVM-Standard, keine
                // Android-Abhaengigkeit - sie beginnen ohnehin nicht mit den
                // Praefixen oben und sind hier nur der Vollstaendigkeit wegen
                // erwaehnt.
            )
        }
    }

    @Test
    fun `feature module importieren weder room noch media3 noch andere features`() {
        for ((module, _) in modulesUnder("feature")) {
            val ownFeature = module.substringAfterLast('/')
            assertNoImportMatching(
                module,
                listOf(
                    "androidx.room.",
                    "androidx.media3.",
                    "com.google.android.exoplayer",
                    "com.dropsync.core.database.",
                    "com.dropsync.data.",
                    "com.dropsync.feature.",
                ),
                "Regel 3.2/4: Features nutzen nur Domain, UI-State und Designsystem",
                // Das eigene Feature-Paket ist erlaubt; verboten sind fremde.
                allow = { import -> import.startsWith("com.dropsync.feature.$ownFeature") },
            )
        }
    }

    @Test
    fun `data module importieren keine feature module`() {
        for ((module, _) in modulesUnder("data")) {
            assertNoImportMatching(
                module,
                listOf("com.dropsync.feature.", "com.dropsync.app."),
                "Regel 3.2/3: Data implementiert Domain-Schnittstellen, keine UI",
            )
        }
    }

    // --- Cancellation-Regel (Ausbauplan A3) ----------------------------------

    /**
     * Jeder `catch (e: Exception)` braucht direkt davor einen
     * `catch (e: CancellationException)` mit Rethrow. Sonst verwandelt der
     * Fehlervertrag (`AppResult.failure`) Coroutine-Abbrueche in Werte und
     * Scopes raeumen nie auf. Detekts `SwallowedException` bleibt bewusst aus
     * (der AppResult-Vertrag ist Absicht) — diese Pruefung sichert stattdessen
     * genau die Gefahrenklasse.
     */
    @Test
    fun `cancellation wird nicht verschluckt`() {
        val roots = listOf("app", "data", "domain", "feature", "core")
        val violations = mutableListOf<String>()
        var guarded = 0
        for (root in roots) {
            val sourceRoot = File(repoRoot, root)
            if (!sourceRoot.isDirectory) continue
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.path.contains("src${File.separator}main") }
                .forEach { file ->
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        if (line.trim() == "} catch (e: Exception) {") {
                            val guard = lines.getOrNull(index - 2)?.trim()
                            if (guard == "} catch (e: CancellationException) {") {
                                guarded++
                            } else {
                                violations += "${file.relativeTo(repoRoot).path}:${index + 1}"
                            }
                        }
                    }
                }
        }
        assertTrue(
            "Cancellation-Pruefung greift ins Leere: kein bewachter Catch gefunden",
            guarded >= 10,
        )
        assertTrue(
            "catch (e: Exception) ohne Cancellation-Rethrow davor (Ausbauplan A3):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
