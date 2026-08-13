package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tests fuer den Madgwick-Orientierungs-Tracker (Umbauplan Punkt 8).
 *
 * Statische Checks pruefen die reine Quaternion-Arithmetik (setOrientation
 * + rotateVector), weil sie ohne Filter-Konvergenz deterministisch sind.
 * Der dynamische Konvergenz-Test fuettert konstante Gyro-Raten und
 * prüft die Drehrichtung des Tracking-Ergebnisses.
 */
class OrientationTrackerTest {
    private fun assertQuaternionClose(
        expected: Quaternion,
        actual: Quaternion,
        tolerance: Double,
        message: String,
    ) {
        assertEquals("$message (q0)", expected.q0, actual.q0, tolerance)
        assertEquals("$message (q1)", expected.q1, actual.q1, tolerance)
        assertEquals("$message (q2)", expected.q2, actual.q2, tolerance)
        assertEquals("$message (q3)", expected.q3, actual.q3, tolerance)
    }

    @Test
    fun `initial orientation is identity`() {
        val tracker = OrientationTracker()
        val q = tracker.orientation()
        assertEquals(1.0, q.q0, 1e-12)
        assertEquals(0.0, q.q1, 1e-12)
        assertEquals(0.0, q.q2, 1e-12)
        assertEquals(0.0, q.q3, 1e-12)
    }

    @Test
    fun `quaternion stays unit length after updates`() {
        val tracker = OrientationTracker(sampleRateHz = 50.0, beta = 0.5)
        for (i in 0 until 200) {
            // Gemischte Gyro-Raten + ruhende Accel (1 g in -z).
            tracker.update(
                ax = 0.0,
                ay = 0.0,
                az = 1.0,
                gx = 40.0 * kotlin.math.sin(i * 0.2),
                gy = -30.0 * kotlin.math.cos(i * 0.15),
                gz = 20.0,
            )
        }
        assertEquals(1.0, tracker.orientation().norm(), 1e-9)
    }

    @Test
    fun `rotateVector 90 deg around X maps z to y`() {
        // Sensor um +90 Grad um X gedreht: sensor_z zeigt auf -ref_y,
        // also liegt ref_z im Sensor-Frame auf +sensor_y.
        val half = PI / 4.0
        val qx90 = Quaternion(kotlin.math.cos(half), kotlin.math.sin(half), 0.0, 0.0)
        val tracker = OrientationTracker()
        tracker.setOrientation(qx90)

        val (rx, ry, rz) = tracker.rotateVector(0.0, 0.0, 1.0)
        assertEquals(0.0, rx, 1e-9)
        assertEquals(1.0, ry, 1e-9)
        assertEquals(0.0, rz, 1e-9)
    }

    @Test
    fun `rotateVector 90 deg around Y maps x to z`() {
        // Sensor um +90 Grad um Y gedreht: sensor_x zeigt auf -ref_z,
        // also liegt ref_x im Sensor-Frame auf +sensor_z.
        val half = PI / 4.0
        val qy90 = Quaternion(kotlin.math.cos(half), 0.0, kotlin.math.sin(half), 0.0)
        val tracker = OrientationTracker()
        tracker.setOrientation(qy90)

        val (rx, ry, rz) = tracker.rotateVector(1.0, 0.0, 0.0)
        assertEquals(0.0, rx, 1e-9)
        assertEquals(0.0, ry, 1e-9)
        assertEquals(1.0, rz, 1e-9)
    }

    @Test
    fun `rotateVector 90 deg around Z maps x to -y`() {
        // Sensor um +90 Grad um Z gedreht: sensor_x zeigt auf +ref_y,
        // also liegt ref_x im Sensor-Frame auf -sensor_y.
        val half = PI / 4.0
        val qz90 = Quaternion(kotlin.math.cos(half), 0.0, 0.0, kotlin.math.sin(half))
        val tracker = OrientationTracker()
        tracker.setOrientation(qz90)

        val (rx, ry, rz) = tracker.rotateVector(1.0, 0.0, 0.0)
        assertEquals(0.0, rx, 1e-9)
        assertEquals(-1.0, ry, 1e-9)
        assertEquals(0.0, rz, 1e-9)
    }

    @Test
    fun `tracking a constant gyro rotation about Y turns the z-axis into the -x direction`() {
        // Physikalisch konsistent: der Sensor dreht mit 90 Grad/s um Y.
        // Die Schwerkraft wandert im Sensor-Frame von (0,0,1) nach (-1,0,0)
        // (Madgwick-Modell: a_x = 2(q1*q3 - q0*q2) = -sin(theta)).
        // Die kalibrierte Achse (Referenz-Frame) liegt im Sensor-Frame
        // entsprechend auf (-1,0,0).
        val tracker = OrientationTracker(sampleRateHz = 50.0, beta = 0.1)
        for (i in 0 until 50) {
            val theta = (Math.PI / 2.0) * i / 50.0
            tracker.update(
                ax = -kotlin.math.sin(theta),
                ay = 0.0,
                az = kotlin.math.cos(theta),
                gx = 0.0,
                gy = 90.0,
                gz = 0.0,
            )
        }
        val (rx, ry, rz) = tracker.rotateVector(0.0, 0.0, 1.0)
        assertTrue("z-Achse muss nach 90 Grad um Y auf -x drehen (rx=$rx)", rx < -0.95)
        assertEquals("keine y-Komponente nach reiner Y-Rotation", 0.0, ry, 0.08)
        assertEquals("keine z-Komponente nach 90 Grad um Y", 0.0, rz, 0.08)
    }

    @Test
    fun `reset restores identity`() {
        val tracker = OrientationTracker(sampleRateHz = 50.0)
        for (i in 0 until 100) {
            tracker.update(ax = 0.0, ay = 0.0, az = 1.0, gx = 45.0, gy = 10.0, gz = -20.0)
        }
        tracker.reset()
        val q = tracker.orientation()
        assertEquals(1.0, q.q0, 1e-12)
        assertEquals(0.0, q.q1, 1e-12)
        assertEquals(0.0, q.q2, 1e-12)
        assertEquals(0.0, q.q3, 1e-12)
    }

    @Test
    fun `zero gyro and rest accel keep orientation near identity`() {
        val tracker = OrientationTracker(sampleRateHz = 50.0, beta = 0.1)
        for (i in 0 until 100) {
            tracker.update(ax = 0.0, ay = 0.0, az = 1.0, gx = 0.0, gy = 0.0, gz = 0.0)
        }
        val q = tracker.orientation()
        assertTrue("Ruhezustand darf die Orientierung nicht wegdriften (q0=${q.q0})", abs(q.q0 - 1.0) < 1e-6)
        assertEquals(0.0, q.q1, 1e-6)
        assertEquals(0.0, q.q2, 1e-6)
        assertEquals(0.0, q.q3, 1e-6)
    }

    @Test
    fun `rotateVector preserves vector length`() {
        val tracker = OrientationTracker()
        tracker.setOrientation(
            Quaternion(0.5, 0.5, 0.5, 0.5), // 120 Grad um (1,1,1)
        )
        val v = doubleArrayOf(1.0, -2.0, 0.5)
        val vNorm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val (rx, ry, rz) = tracker.rotateVector(v[0], v[1], v[2])
        val rNorm = sqrt(rx * rx + ry * ry + rz * rz)
        assertEquals(vNorm, rNorm, 1e-9)
    }
}
