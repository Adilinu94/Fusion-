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
 * Kaltstart-Benchmark (Verbesserungsplan Phase 4): misst StartupTimingMetric
 * in CompilationMode.None (Baseline) und mit erzwungenem Baseline-Profil. Die
 * Differenz ist der Gewinn des generierten Profils; der Lauf braucht ein
 * Geraet/Emulator.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    // Seit benchmark 1.5 gibt es kein CompilationMode.BaselineProfile mehr:
    // Partial + BaselineProfileMode.Require erzwingt das Profil und schlaegt
    // fehl, wenn keines installiert ist.
    @Test
    fun startupBaselineProfile() =
        startup(
            CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
                warmupIterations = 0,
            ),
        )

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = "com.dropsync.app.debug",
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
}
