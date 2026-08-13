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
    private fun restSample(t: Long) = SensorSample(t, ax = 0.0, ay = 0.0, az = 9.81, gx = 0.0, gy = 0.0, gz = 0.0)

    /** Ein Dreieck-Rep auf gz: up 20 Samples 0->amp, down 20 Samples amp->0. */
    private fun rep(
        startMs: Long,
        amplitude: Double = 200.0,
        up: Int = 20,
        down: Int = 20,
    ): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        for (i in 1..up) {
            out.add(SensorSample(startMs + i * 20L, 0.0, 0.0, 9.81, 0.0, 0.0, amplitude * i / up))
        }
        for (i in 1..down) {
            out.add(SensorSample(startMs + (up + i) * 20L, 0.0, 0.0, 9.81, 0.0, 0.0, amplitude - amplitude * i / down))
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
    }
}
