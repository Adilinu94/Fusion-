package com.dropsync.benchmarks

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kaltstart-Benchmark: misst StartupTimingMetric ohne Profil gegen den Lauf
 * mit Baseline Profile. Die Differenz ist der Gewinn des generierten Profils;
 * der Lauf braucht ein Geraet/Emulator.
 *
 * Das gemessene Profil erzeugt [BaselineProfileGenerator] (Befund B-UI-5);
 * ohne einen vorherigen `:app:generateBaselineProfile`-Lauf schlaegt
 * [startupBaselineProfile] fehl, statt stumm nichts zu messen — siehe
 * [BaselineProfileMode.Require] unten.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() =
        startup(
            // `CompilationMode.BaselineProfile()` gab es in benchmark 1.0 und
            // ist seither entfernt; mit 1.5.0-alpha01 hat diese Datei nie
            // kompiliert (Befund B-UI-5). Ersatz ist `Partial`.
            //
            // `Require` statt `UseIfAvailable` bewusst: fehlt das Profil,
            // soll der Benchmark abbrechen. `UseIfAvailable` wuerde ohne
            // Profil messen und ein Ergebnis liefern, das wie "kein Gewinn"
            // aussieht — genau die Art stiller Fehlmessung, die diesen
            // Befund ausgeloest hat.
            CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        )

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            // Ohne `.debug`-Suffix (Befund B-UI-5): Macrobenchmark verlangt
            // debuggable=false, die `benchmark`-Variante faellt per
            // matchingFallbacks auf `release`, und `release` hat keinen
            // applicationIdSuffix. Der alte Wert zielte auf ein Paket, das
            // im Benchmark-Build nicht existiert.
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
        ) {
            // Start bis die Library sichtbar ist (erste Top-Level-Ziel).
            startActivityAndWait()
            device.waitForIdle()
        }

    private companion object {
        /** Deckungsgleich mit `applicationId` in `app/build.gradle.kts`. */
        const val TARGET_PACKAGE = "com.dropsync.app"
    }
}
