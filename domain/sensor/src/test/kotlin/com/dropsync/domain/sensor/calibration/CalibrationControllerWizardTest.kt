package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetischer Komplett-Durchlauf des Guided-Calibration-Wizards
 * (REST -> SINGLE_REP -> KNOWN_SET -> SLOW_SET -> REVIEW).
 *
 * Zweck: deterministische Sample-Daten fuer den CalibrationViewModelTest
 * in :feature:workout (Umbauplan Punkt 2a, SPK/NPK-Ableitung). Dieser
 * Domain-Test beweist, dass der Wizard mit den synthetischen Samples
 * wirklich alle Gates besteht und finalize() ein Ergebnis liefert.
 *
 * Dreieck-Rep auf gz: 0 -> 200 -> 0 deg/s (rein positiv, kein negativer
 * Anteil). Rest: reine Stille mit konstanter Schwerkraft auf az.
 */
class CalibrationControllerWizardTest {
    // Ruhe in g-Einheiten (1 g Schwerkraft, wie die Live-Pipeline rechnet) —
    // die GP-Zaehlung nutzt nur Gyro, aber die Accel-Review-Fakten brauchen
    // realistische Werte.
    private fun restSample(t: Long) = SensorSample(t, ax = 0.0, ay = 0.0, az = 1.0, gx = 0.0, gy = 0.0, gz = 0.0)

    /** Ein Dreieck-Rep auf gz: up 20 Samples 0->amp, down 20 Samples amp->0. */
    private fun rep(
        startMs: Long,
        amplitude: Double = 200.0,
        up: Int = 20,
        down: Int = 20,
    ): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        for (i in 1..up) {
            out.add(SensorSample(startMs + i * 20L, 0.0, 0.0, 1.0, 0.0, 0.0, amplitude * i / up))
        }
        for (i in 1..down) {
            out.add(SensorSample(startMs + (up + i) * 20L, 0.0, 0.0, 1.0, 0.0, 0.0, amplitude - amplitude * i / down))
        }
        return out
    }

    /** N Reps mit fester Pause dazwischen. */
    private fun reps(
        n: Int,
        amplitude: Double = 200.0,
        pauseSamples: Int = 60,
        up: Int = 20,
        down: Int = 20,
    ): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        var t = 0L
        repeat(n) {
            out += rep(t, amplitude, up, down)
            t += (up + down + pauseSamples) * 20L
        }
        return out
    }

    private fun driveToReview(controller: CalibrationController) {
        controller.start()
        // REST: 3 s Stille (150 Samples @ 50 Hz) - Gate braucht >= 2 s.
        for (i in 0 until 150) controller.onSample(restSample(i * 20L))
        assertNull("Rest-Gate muss mit stillen Samples bestehen", controller.finishStage())
        assertEquals(CalibrationController.Stage.SINGLE_REP, controller.stage)

        // SINGLE_REP: genau ein deutlicher Rep.
        rep(0).forEach { controller.onSample(it) }
        assertNull("Einzelner Rep muss eine Achse liefern", controller.finishStage())
        assertEquals(CalibrationController.Stage.KNOWN_SET, controller.stage)

        // KNOWN_SET: 5 Reps (Default knownSetCount=5).
        reps(5).forEach { controller.onSample(it) }
        assertNull("Known-Set muss durchlaufen", controller.finishStage())
        assertEquals(CalibrationController.Stage.SLOW_SET, controller.stage)

        // SLOW_SET: 3 langsame Reps (Default slowSetCount=3, 3x Dauer).
        reps(3, up = 60, down = 60, pauseSamples = 120).forEach { controller.onSample(it) }
        assertNull("Slow-Set muss durchlaufen", controller.finishStage())
        assertEquals(CalibrationController.Stage.REVIEW, controller.stage)
    }

    @Test
    fun `synthetic wizard run reaches review and finalizes`() {
        val controller = CalibrationController()
        driveToReview(controller)
        val result = controller.finalize()
        assertNotNull("finalize() muss nach REVIEW ein Ergebnis liefern", result)
        assertTrue(result!!.rotationAxis.size == 3)
        assertTrue(result.repTemplate.isNotEmpty())
        assertTrue(result.theta > 0)
        assertTrue(result.expectedProminence > 0)
        assertTrue(result.expectedDurationSamples > 0)
        assertTrue("Dauer muss auch in ms vorliegen", result.expectedDurationMs > 0)
        assertTrue("NoiseFloor muss nichtnegativ sein", result.noiseFloor >= 0)
        // Umbauplan Phase 1.3: der Sweep darf nur GP waehlen.
        assertEquals(
            "Kalibrierung muss auf dem signierten GP-Signal laufen",
            ChosenSignal.GP,
            result.chosenSignal,
        )
    }

    // --- RC-8: Live-Schaetzung, Stufe wiederholen, Review-Fakten -----------

    /** Fuehrt den Controller bis in die angegebene Stufe (inkl. Samples). */
    private fun driveTo(
        controller: CalibrationController,
        stage: CalibrationController.Stage,
    ) {
        controller.start()
        if (stage == CalibrationController.Stage.REST) return
        for (i in 0 until 150) controller.onSample(restSample(i * 20L))
        assertNull(controller.finishStage())
        if (stage == CalibrationController.Stage.SINGLE_REP) return
        rep(0).forEach { controller.onSample(it) }
        assertNull(controller.finishStage())
        if (stage == CalibrationController.Stage.KNOWN_SET) return
        reps(5).forEach { controller.onSample(it) }
        assertNull(controller.finishStage())
    }

    @Test
    fun `liveRepEstimate folgt dem Puffer jeder Stufe`() {
        val controller = CalibrationController()
        controller.start()
        assertNull("In der Ruhe gibt es keine Rep-Schaetzung", controller.liveRepEstimate)

        // Stufe A: jeder Bewegungs-Burst zaehlt als eine Wiederholung.
        for (i in 0 until 150) controller.onSample(restSample(i * 20L))
        assertNull(controller.finishStage())
        assertEquals(0, controller.liveRepEstimate)
        rep(0).forEach { controller.onSample(it) }
        assertEquals("Ein Rep muss als eine Bewegung sichtbar sein", 1, controller.liveRepEstimate)
        rep(10_000).forEach { controller.onSample(it) }
        assertEquals(2, controller.liveRepEstimate)

        // Stufe B: vorlaeufige Zaehlung auf dem GP-Signal.
        assertNull(controller.finishStage())
        assertEquals(0, controller.liveRepEstimate)
        reps(5).forEach { controller.onSample(it) }
        assertEquals("Fuenf Reps muessen schon vor dem Abschluss sichtbar sein", 5, controller.liveRepEstimate)

        // Stufe C: gelernte Config aus Stufe B.
        assertNull(controller.finishStage())
        assertEquals(0, controller.liveRepEstimate)
        reps(3, up = 60, down = 60, pauseSamples = 120).forEach { controller.onSample(it) }
        assertEquals("Drei langsame Reps muessen sichtbar sein", 3, controller.liveRepEstimate)

        assertNull(controller.finishStage())
        assertNull("Im Review gibt es keine Live-Schaetzung mehr", controller.liveRepEstimate)
    }

    @Test
    fun `repeatStage leert den Puffer ohne die Stufe zu verlassen`() {
        val controller = CalibrationController()
        driveTo(controller, CalibrationController.Stage.SINGLE_REP)
        rep(0).forEach { controller.onSample(it) }
        assertEquals(1, controller.liveRepEstimate)
        assertEquals(40, controller.bufferedSampleCount)

        controller.repeatStage()

        assertEquals(
            "Die Stufe bleibt stehen",
            CalibrationController.Stage.SINGLE_REP,
            controller.stage,
        )
        assertEquals("Der Puffer ist leer", 0, controller.bufferedSampleCount)
        assertEquals(0, controller.liveRepEstimate)

        // Nach dem Wiederholen laeuft die Stufe normal weiter.
        rep(0).forEach { controller.onSample(it) }
        assertNull(controller.finishStage())
        assertEquals(CalibrationController.Stage.KNOWN_SET, controller.stage)
    }

    @Test
    fun `redoFrom known set verwirft den alten sweep und rechnet neu`() {
        val controller = CalibrationController()
        driveTo(controller, CalibrationController.Stage.SLOW_SET)
        val first = controller.finalize()
        assertNotNull("Erster Durchlauf muss ein Ergebnis liefern", first)

        controller.redoFrom(CalibrationController.Stage.KNOWN_SET)

        assertEquals(CalibrationController.Stage.KNOWN_SET, controller.stage)
        assertEquals("Der 5er-Puffer ist leer", 0, controller.bufferedSampleCount)
        assertNull("Der alte Sweep darf nicht weiterleben", controller.finalize())

        // Frischer 5er-Satz + Langsam-Satz -> neues, gueltiges Ergebnis.
        reps(5).forEach { controller.onSample(it) }
        assertNull(controller.finishStage())
        reps(3, up = 60, down = 60, pauseSamples = 120).forEach { controller.onSample(it) }
        assertNull(controller.finishStage())
        val second = controller.finalize()
        assertNotNull("Nach dem Wiederholen muss wieder ein Ergebnis entstehen", second)
        assertNotNull(second!!.review)
    }

    @Test
    fun `redoFrom verbietet den Sprung nach vorn und aus dem review`() {
        val controller = CalibrationController()
        driveTo(controller, CalibrationController.Stage.SINGLE_REP)
        controller.redoFrom(CalibrationController.Stage.SLOW_SET)
        assertEquals(
            "Vorwaerts-Spruenge sind verboten",
            CalibrationController.Stage.SINGLE_REP,
            controller.stage,
        )

        driveToReview(controller)
        controller.redoFrom(CalibrationController.Stage.REVIEW)
        assertEquals(
            "Review ist kein Wiederholungsziel",
            CalibrationController.Stage.REVIEW,
            controller.stage,
        )
    }

    @Test
    fun `review facts beschreiben was die Kalibrierung kann`() {
        val controller = CalibrationController()
        driveToReview(controller)
        val review = controller.finalize()!!.review
        assertNotNull("Nach dem Durchlauf muessen Review-Fakten vorliegen", review)
        review!!
        assertEquals("Alle fuenf Reps muessen wiedergefunden werden", 5, review.detectedRepsKnownSet)
        assertEquals(5, review.expectedRepsKnownSet)
        assertEquals("Auch der Langsam-Satz muss vollstaendig sein", 3, review.detectedRepsSlowSet)
        assertEquals(3, review.expectedRepsSlowSet)
        assertNotNull("Bei fuenf Reps gibt es Intervalle", review.intervalCv)
        assertTrue(
            "Die synthetischen Reps sind gleichmaessig",
            review.intervalCv!! < 0.05,
        )
        assertTrue(
            "Die Schwelle muss ueber dem Ruherauschen liegen",
            review.thresholdOverNoise > 0.0,
        )
        // Konstante synthetische Accel -> keine trennscharfe Schwelle.
        assertEquals(false, review.accelVotingEnabled)
    }
}
