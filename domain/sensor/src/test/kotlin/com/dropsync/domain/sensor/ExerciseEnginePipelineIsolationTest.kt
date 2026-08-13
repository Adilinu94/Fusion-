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
 *
 * WICHTIG (Korrektur 2026-08-13): die urspruengliche peakShape endete
 * abrupt bei -100 und sprang auf 0 zurueck. Der OneEuro-Filter macht
 * daraus einen langen negativen Tail, das Pending-Window schliesst erst
 * ueber MAX_EXTRA_PHASE_SAMPLES (120), und der PhaseValidator lehnt das
 * Fenster als asymmetrisch ab (pos=17, neg=122). Die urspruengliche
 * Python-"Verifikation" hatte die Pending-Logik nicht nachgebildet.
 * Neue Form: rein positives Dreieck (0 -> 60 -> 0 in je 15 Samples) -
 * kein negativer Anteil, Pending schliesst sofort, deterministisch
 * 2 Reps auch mit minScore=0.55 (Score ~0.72).
 */
class ExerciseEnginePipelineIsolationTest {
    // Rein positives Dreieck: 0 -> 60 in 15 Schritten, 60 -> 0 in 15
    // Schritten. Ein sauber erkennbarer Rep ohne negativen Signalanteil.
    private fun repExcursion(): List<Double> =
        (1..15).map { 60.0 * it / 15.0 } + (1..15).map { 60.0 - 60.0 * it / 15.0 }

    // 60 ruhige Samples (> settleSamples=50 der SignalChain), zwei Reps mit
    // je 60 ruhigen Samples Abstand (> Refraktaerzeit von 25 Samples bei
    // 50 Hz), danach 40 ruhige Samples. Numerisch verifiziert
    // (sim_search.py): exakt 2 gezaehlte Reps, auch mit minScore=0.55.
    private fun twoRepRawStream(): List<Double> =
        List(60) { 0.0 } + repExcursion() + List(60) { 0.0 } + repExcursion() + List(40) { 0.0 }

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

    @Test
    fun `updateLevels propagates to peak detector`() {
        // Ohne updateLevels zaehlt der Stream deterministisch 2 Reps
        // (theta=32.5). Nach updateLevels(1000, 100) ist theta=325, die
        // Amplitude 60 liegt darunter - die Pipeline zaehlt 0 Reps. Das
        // beweist die Durchreichung Pipeline -> RepCounter -> PeakDetector.
        val engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, engine.repCount.value)

        engine.reset()
        engine.updateLevels(spk = 1000.0, npk = 100.0)
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(
            "nach updateLevels(1000, 100) ist theta=325 > Amplitude 60: kein Rep",
            0,
            engine.repCount.value,
        )
    }

    @Test
    fun `config default minQualityScore is 0_55`() {
        // Umbauplan Punkt 3: Config-Default muss dem QualityScorer-Default
        // (0.55) entsprechen - der Scorer-Default ist der aus der
        // Dart-Vorlage validierte Wert.
        val config =
            ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0))
        assertEquals(0.55, config.minQualityScore, 1e-9)
    }

    // --- Punkt 4: Accel-Voting ---------------------------------------------

    @Test
    fun `accel voting unterdrueckt reinen Gyro-Peak`() {
        // accelEnabled=true, aber der Accel-Kanal bleibt ruhig (ax=1.0):
        // der Gyro-Peak hat keinen Accel-Partner -> Voting schlaegt fehl.
        val engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = listOf(1.0, 0.0, 0.0),
                    gyroBias = listOf(0.0, 0.0, 0.0),
                    accelEnabled = true,
                ),
            )
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0, ax = 1.0, ay = 0.0, az = 0.0)
        }
        assertEquals(
            "Gyro-Peak ohne Accel-Partner darf bei aktivem Voting nicht zaehlen",
            0,
            engine.repCount.value,
        )
    }

    @Test
    fun `accel voting zaehlt wenn beide Kanaele peaken`() {
        // Gleichphasige Accel-Abweichung waehrend der Reps (Magnitude
        // schlaegt von 1 g auf bis 1.5 g aus): beide Kanaele peaken.
        val engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = listOf(1.0, 0.0, 0.0),
                    gyroBias = listOf(0.0, 0.0, 0.0),
                    accelEnabled = true,
                ),
            )
        twoRepRawStream().forEachIndexed { i, gx ->
            val accelDev = 0.5 * (gx / 60.0)
            engine.processSample(i * 20L, gx, 0.0, 0.0, ax = 1.0 + accelDev, ay = 0.0, az = 0.0)
        }
        assertEquals("Gyro- und Accel-Peak gleichphasig: Voting muss beide Reps zaehlen", 2, engine.repCount.value)
    }

    @Test
    fun `accel disabled verhaelt sich wie vorher`() {
        // Default accelEnabled=false: reine Gyro-Zaehlung wie bisher.
        val engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0)),
            )
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, engine.repCount.value)
    }
}
