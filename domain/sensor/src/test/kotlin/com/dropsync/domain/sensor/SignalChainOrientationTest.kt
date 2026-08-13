package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verdrahtungs-Check fuer den OrientationTracker in der SignalChain
 * (Umbauplan Punkt 8): in Ruhe (keine Rotation, Schwerkraft auf +z) muss
 * der aktivierte Tracker die Projektion exakt identisch lassen wie die
 * Kette ohne Tracker. Das Tracking-Verhalten selbst (Konvergenz unter
 * Rotation) prueft OrientationTrackerTest.
 */
class SignalChainOrientationTest {
    @Test
    fun `tracker at rest does not change the projection`() {
        val axis = doubleArrayOf(1.0, 0.0, 0.0)
        val bias = doubleArrayOf(0.0, 0.0, 0.0)
        val plain = SignalChain(axis, bias, settleSamples = 0)
        val tracked = SignalChain(axis, bias, settleSamples = 0, orientationTracker = OrientationTracker())

        // Ruhe: Bias-freies Gyro-Signal + Schwerkraft exakt auf +z.
        // Der Madgwick-Filter bleibt bei Identity -> Projektion identisch.
        repeat(80) { i ->
            val plainFrame = plain.process(i * 20L, 0.0, 0.0, 0.0, ax = 0.0, ay = 0.0, az = 1.0)
            val trackedFrame = tracked.process(i * 20L, 0.0, 0.0, 0.0, ax = 0.0, ay = 0.0, az = 1.0)
            assertEquals(
                "in Ruhe muss der Tracker die Projektion unveraendert lassen (Sample $i)",
                plainFrame.rawGp,
                trackedFrame.rawGp,
                1e-9,
            )
            assertEquals(0.0, trackedFrame.rawGp, 1e-9)
        }
    }

    @Test
    fun `tracked chain still projects a moving signal`() {
        // Verdrahtung: mit Tracker und Bewegung liefert die Kette weiterhin
        // ein nicht-triviales rawGp (die Achse wird nachgefuehrt, die
        // Pipeline bleibt funktional).
        val axis = doubleArrayOf(0.0, 1.0, 0.0)
        val bias = doubleArrayOf(0.0, 0.0, 0.0)
        val tracked = SignalChain(axis, bias, settleSamples = 0, orientationTracker = OrientationTracker())

        val gy = (1..20).map { 50.0 * it / 20.0 }
        var sawNonZero = false
        gy.forEachIndexed { i, g ->
            val frame = tracked.process(i * 20L, 0.0, g, 0.0, ax = 0.0, ay = 0.0, az = 1.0)
            if (kotlin.math.abs(frame.rawGp) > 1.0) sawNonZero = true
        }
        assertTrue("die Projektion muss trotz aktivem Tracker Signal liefern", sawNonZero)
    }

    @Test
    fun `settled flag is independent of the tracker`() {
        val axis = doubleArrayOf(1.0, 0.0, 0.0)
        val bias = doubleArrayOf(0.0, 0.0, 0.0)
        val tracked = SignalChain(axis, bias, settleSamples = 10, orientationTracker = OrientationTracker())
        repeat(20) { i ->
            tracked.process(i * 20L, 0.0, 0.0, 0.0, ax = 0.0, ay = 0.0, az = 1.0)
        }
        assertTrue(tracked.isSettled)
    }
}
