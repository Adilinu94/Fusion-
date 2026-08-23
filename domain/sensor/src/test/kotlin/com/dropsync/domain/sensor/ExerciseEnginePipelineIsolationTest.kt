package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fund 1 (siehe TrainViewModelTest.kt, ADR-0014): TrainViewModel haelt zwei
 * ExerciseEnginePipeline-Instanzen (liveEngine mit echter Achse/Bias,
 * shadowEngine mit NEUTRAL_AXIS/NEUTRAL_BIAS). Dieser Test beweist auf
 * Domain-Ebene, dass zwei Instanzen keinerlei gemeinsamen Zustand teilen.
 *
 * Umbauplan Phase 4: die Test-Streams bestehen aus VOLLSTAENDIGEN
 * Zwei-Phasen-Zyklen (positiv -> Richtungswechsel -> negativ -> Rueckkehr
 * zur Baseline). Halbe Reps muessen jetzt abgelehnt werden.
 */
class ExerciseEnginePipelineIsolationTest {
    // Vollstaendiger Zyklus: 0 -> 60 (konzentrisch), 60 -> -60
    // (Richtungswechsel + exzentrisch), -60 -> 0 (Rueckkehr zur Baseline).
    private fun repCycle(): List<Double> =
        (1..15).map { 60.0 * it / 15.0 } +
            (1..30).map { 60.0 - 120.0 * it / 30.0 } +
            (1..15).map { -60.0 + 60.0 * it / 15.0 }

    // 60 ruhige Samples (> settleSamples=50 der SignalChain), zwei volle
    // Zyklen mit je 60 ruhigen Samples Abstand, danach 40 ruhige Samples.
    private fun twoRepRawStream(): List<Double> =
        List(60) { 0.0 } + repCycle() + List(60) { 0.0 } + repCycle() + List(40) { 0.0 }

    private fun engine(
        axis: List<Double> = listOf(1.0, 0.0, 0.0),
        threshold: Double = 32.5,
        accelEnabled: Boolean = false,
    ) = ExerciseEnginePipeline(
        ExerciseEngineConfig(
            rotationAxis = axis,
            gyroBias = listOf(0.0, 0.0, 0.0),
            detectionThreshold = threshold,
            expectedDurationMs = 2_000.0,
            expectedProminence = 1.0,
            accelEnabled = accelEnabled,
        ),
    )

    @Test
    fun `zwei Pipeline-Instanzen mit unterschiedlicher Achse teilen keinen Zustand`() {
        // Wie in TrainViewModel.startCountedSet(): echte Kalibrierungsachse.
        val live = engine(axis = listOf(1.0, 0.0, 0.0))
        // Wie in TrainViewModel.resetShadowEngine(): NEUTRAL_AXIS/NEUTRAL_BIAS.
        val shadow = engine(axis = listOf(0.0, 0.0, 1.0))

        // Derselbe Sample-Strom auf gx; live projiziert auf gx (Achse
        // [1,0,0]) und sieht die Reps, shadow projiziert auf gz (Achse
        // [0,0,1]) und sieht durchgehend 0.
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
        val live = engine()
        val shadow = engine()

        twoRepRawStream().forEachIndexed { i, gx ->
            live.processSample(i * 20L, gx, 0.0, 0.0)
            shadow.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, live.repCount.value)
        assertEquals(2, shadow.repCount.value)

        // reset() auf live darf shadow's Zaehlstand nicht beruehren.
        live.reset()
        assertEquals(0, live.repCount.value)
        assertEquals("shadow darf von live.reset() nicht beruehrt werden", 2, shadow.repCount.value)
    }

    @Test
    fun `updateThreshold propagates to peak detector`() {
        // Ohne updateThreshold zaehlt der Stream deterministisch 2 Reps
        // (theta=32.5). Nach updateThreshold(65) ist theta=65, die
        // Amplitude 60 liegt darunter - die Pipeline zaehlt 0 Reps.
        val engine = engine()
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, engine.repCount.value)

        engine.reset()
        engine.updateThreshold(theta = 65.0)
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(
            "nach updateThreshold(65) ist theta=65 > Amplitude 60: kein Rep",
            0,
            engine.repCount.value,
        )
    }

    @Test
    fun `config default minQualityScore is 0_55`() {
        val config =
            ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0))
        assertEquals(0.55, config.minQualityScore, 1e-9)
    }

    @Test
    fun `config default detectionThreshold is 32_5`() {
        val config =
            ExerciseEngineConfig(rotationAxis = listOf(1.0, 0.0, 0.0), gyroBias = listOf(0.0, 0.0, 0.0))
        assertEquals(32.5, config.detectionThreshold, 1e-9)
    }

    // --- Punkt 4: Accel-Voting ---------------------------------------------

    @Test
    fun `accel voting unterdrueckt reinen Gyro-Peak`() {
        // accelEnabled=true, aber der Accel-Kanal bleibt ruhig (ax=1.0):
        // der Gyro-Peak hat keinen Accel-Partner -> Voting schlaegt fehl.
        val engine = engine(accelEnabled = true)
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
        val engine = engine(accelEnabled = true)
        twoRepRawStream().forEachIndexed { i, gx ->
            val accelDev = 0.5 * (gx / 60.0)
            engine.processSample(i * 20L, gx, 0.0, 0.0, ax = 1.0 + accelDev, ay = 0.0, az = 0.0)
        }
        assertEquals("Gyro- und Accel-Peak gleichphasig: Voting muss beide Reps zaehlen", 2, engine.repCount.value)
    }

    @Test
    fun `accel disabled verhaelt sich wie vorher`() {
        val engine = engine()
        twoRepRawStream().forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals(2, engine.repCount.value)
    }

    // --- Umbauplan Phase 4: halbe Reps muessen abgelehnt werden ------------

    @Test
    fun `halbe Rep wird abgelehnt`() {
        // Reines Anheben (0 -> 60 -> 0, nie negativ): keine Rueckbewegung
        // durch die Baseline - darf nicht als Rep zaehlen.
        val halfRep = (1..15).map { 60.0 * it / 15.0 } + (1..15).map { 60.0 - 60.0 * it / 15.0 }
        val stream = List(60) { 0.0 } + halfRep + List(60) { 0.0 }
        val engine = engine()
        stream.forEachIndexed { i, gx ->
            engine.processSample(i * 20L, gx, 0.0, 0.0)
        }
        assertEquals("halbe Rep darf nicht zaehlen", 0, engine.repCount.value)
    }

    // --- Umbauplan Phase 2.6: Zeitluecken -----------------------------------

    @Test
    fun `grosse Zeitluecke erhoeht largeGapCount und laesst Filter neu einschwingen`() {
        val engine = engine()
        // Normale Samples, dann eine Luecke von 1000 ms, danach wieder
        // normale Samples: die Pipeline darf die Luecke nicht als physische
        // Zeit komprimieren, sondern zaehlt sie als grossen Gap.
        val stream = List(60) { 0.0 } + repCycle() + List(20) { 0.0 }
        var ts = 0L
        stream.forEach { gx ->
            engine.processSample(ts, gx, 0.0, 0.0)
            ts += 20L
        }
        assertEquals(0, engine.largeGapCount)

        // Luecke: der naechste Sample-Timestamp springt um 1000 ms.
        engine.processSample(ts + 1_000L, 0.0, 0.0, 0.0)
        assertEquals(1, engine.largeGapCount)
        // Nach dem Gap sind die Filter neu eingeschwungen: isSettled ist
        // erst nach settleSamples wieder true.
        assertEquals(false, engine.isSettled)
    }

    @Test
    fun `kleine Luecke unter 250 ms ist kein grosser Gap`() {
        val engine = engine()
        val stream = List(60) { 0.0 }
        var ts = 0L
        stream.forEach { gx ->
            engine.processSample(ts, gx, 0.0, 0.0)
            ts += 20L
        }
        engine.processSample(ts + 100L, 0.0, 0.0, 0.0) // 120 ms seit letztem
        assertEquals(0, engine.largeGapCount)
    }
}
