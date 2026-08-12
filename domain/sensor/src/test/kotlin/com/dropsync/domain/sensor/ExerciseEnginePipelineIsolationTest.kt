package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fund 1 (siehe TrainViewModelTest.kt, ADR-0014): TrainViewModel haelt zwei
 * ExerciseEnginePipeline-Instanzen (liveEngine mit echter Achse/Bias,
 * shadowEngine mit NEUTRAL_AXIS/NEUTRAL_BIAS). Dieser Test beweist auf
 * Domain-Ebene, dass zwei Instanzen keinerlei gemeinsamen Zustand teilen:
 * derselbe rohe Sample-Strom, unter den in TrainViewModel tatsaechlich
 * verwendeten Achsen projiziert, liefert zwei unabhaengige, korrekt
 * unterschiedliche Zaehlstaende - kein Uebersprechen zwischen den
 * Instanzen ueber Companion-Object oder sonstigen geteilten Zustand.
 *
 * Deckt NICHT die ViewModel-Verdrahtung selbst ab (also nicht, welche der
 * beiden Instanzen tatsaechlich an `_repsInput`/`_liveCountedReps` haengt -
 * das ist laut Code aktuell `liveEngine`, siehe ADR-0014). Ein
 * View-Model-Ebenen-Test dafuer (`ShadowEngineIsolationTest`, siehe
 * TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md) braucht eine echte
 * Kalibrierungsprofil-Fake und eine steuerbare Sensor-Verbindung und ist
 * bewusst nicht Teil dieses Commits (siehe Fence-Report).
 *
 * Sample-Sequenz numerisch gegen eine Python-Nachbildung von SignalChain +
 * PeakDetector + RepCounter (inkl. Pending-Window-Erweiterung,
 * PhaseValidator, QualityScorer) verifiziert: liefert deterministisch
 * genau 2 gezaehlte Reps.
 */
class ExerciseEnginePipelineIsolationTest {
    // Identisch zur peakShape in RepPipelineTest.kt: 0 -> 200 in 10
    // Schritten, 200 -> -100 in 14 Schritten - ein sauber erkennbarer Rep.
    private fun repExcursion(): List<Double> =
        (0..10).map { 200.0 * it / 10.0 } + (1..14).map { 200.0 - 300.0 * it / 14.0 }

    // 60 ruhige Samples (> settleSamples=50 der SignalChain), zwei Reps mit
    // je 40 ruhigen Samples Abstand (> Refraktaerzeit von 25 Samples bei
    // 50 Hz). Numerisch verifiziert: exakt 2 gezaehlte Reps.
    private fun twoRepRawStream(): List<Double> =
        List(60) { 0.0 } + repExcursion() + List(40) { 0.0 } + repExcursion() + List(40) { 0.0 }

    @Test
    fun `zwei Pipeline-Instanzen mit unterschiedlicher Achse teilen keinen Zustand`() {
        // Wie in TrainViewModel.startCountedSet(): echte Kalibrierungsachse.
        val live =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )
        // Wie in TrainViewModel.resetShadowEngine(): NEUTRAL_AXIS/NEUTRAL_BIAS.
        val shadow =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(0.0, 0.0, 1.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )

        // Derselbe Sample-Strom auf gx; live projiziert auf gx (Achse
        // [1,0,0]) und sieht die Reps, shadow projiziert auf gz (Achse
        // [0,0,1]) und sieht durchgehend 0 - exakt das reale Verhalten aus
        // TrainViewModel mit unkalibrierter Shadow-Achse ("shadow accuracy
        // is bounded by this until a full profile").
        twoRepRawStream().forEachIndexed { i, gx ->
            val ts = i * 20L
            live.processSample(ts, gx, 0.0, 0.0)
            shadow.processSample(ts, gx, 0.0, 0.0)
        }

        assertEquals("live muss beide Reps auf der echten Achse zaehlen", 2, live.repCount.value)
        assertEquals("shadow auf NEUTRAL_AXIS sieht nur Rauschen (projiziert auf gz=0)", 0, shadow.repCount.value)
    }

    @Test
    fun `reset einer Instanz beeinflusst die andere nicht`() {
        val live =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )
        val shadow =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )

        twoRepRawStream().forEachIndexed { i, gx ->
            live.processSample(i * 20L, gx, 0.0, 0.0)
            shadow.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, live.repCount.value)
        assertEquals(2, shadow.repCount.value)

        // reset() auf live (z. B. TrainViewModel.finishExercise) darf
        // shadow's Zaehlstand nicht beruehren - kein geteilter Zustand.
        live.reset()
        assertEquals(0, live.repCount.value)
        assertEquals("shadow darf von live.reset() nicht beruehrt werden", 2, shadow.repCount.value)
    }
}
