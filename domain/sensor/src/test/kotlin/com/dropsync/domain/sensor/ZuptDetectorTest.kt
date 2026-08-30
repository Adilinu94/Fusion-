package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-Fix #24: ZUPT-Segmentierung. Geprueft wird, dass der Detektor echte
 * Ruhefenster erkennt, den dort messbaren Gyro-Bias liefert und den
 * Umkehrpunkt einer Wiederholung NICHT als Ruhe missversteht.
 */
class ZuptDetectorTest {
    private val sampleIntervalMs = 20L

    /** Ruhiges Sample: Gyro nahe null (bis auf Bias), Accel auf 1 g. */
    private fun quiet(
        t: Long,
        bias: Double = 0.0,
    ) = { d: ZuptDetector -> d.onSample(t, bias, bias, bias, 0.0, 0.0, 1.0) }

    @Test
    fun `ruhefenster wird erst nach der mindestdauer bestaetigt`() {
        val detector = ZuptDetector()
        var confirmedAt = -1L

        // 30 ruhige Samples = 600 ms > DEFAULT_MIN_STATIONARY_MS (400 ms).
        for (i in 0 until 30) {
            val t = i * sampleIntervalMs
            val result = quiet(t)(detector)
            if (result.zuptConfirmed && confirmedAt < 0) confirmedAt = t
        }

        assertTrue("Ruhe muss irgendwann bestaetigt werden", confirmedAt >= 0)
        assertTrue(
            "Bestaetigung darf nicht vor der Mindestdauer kommen (war ${confirmedAt}ms)",
            confirmedAt >= ZuptDetector.DEFAULT_MIN_STATIONARY_MS,
        )
        assertTrue(detector.isStationary)
        assertEquals(ZuptDetector.Segment.STATIONARY, detector.segment)
    }

    @Test
    fun `kurze ruhe unter der mindestdauer wird nicht bestaetigt`() {
        val detector = ZuptDetector()
        // 10 Samples = 200 ms, klar unter 400 ms.
        for (i in 0 until 10) quiet(i * sampleIntervalMs)(detector)
        assertFalse("200 ms Ruhe sind kein ZUPT-Fenster", detector.isStationary)
        assertNull("ohne bestaetigtes Fenster gibt es keinen Bias", detector.biasEstimate)
    }

    @Test
    fun `bias wird im ruhefenster gemessen`() {
        val detector = ZuptDetector()
        // Konstanter Bias von 3 deg/s auf allen Achsen - genau das, was der
        // MPU6886 mit Temperaturdrift liefert.
        val bias = 3.0
        for (i in 0 until 40) quiet(i * sampleIntervalMs, bias)(detector)

        val estimate = detector.biasEstimate
        assertNotNull("bestaetigtes Fenster muss einen Bias liefern", estimate)
        estimate!!
        assertEquals("Bias x", bias, estimate[0], 1e-9)
        assertEquals("Bias y", bias, estimate[1], 1e-9)
        assertEquals("Bias z", bias, estimate[2], 1e-9)
    }

    @Test
    fun `bewegung beendet das ruhefenster und meldet die segmentgrenze`() {
        val detector = ZuptDetector()
        for (i in 0 until 40) quiet(i * sampleIntervalMs)(detector)
        assertTrue(detector.isStationary)

        // Deutliche Drehrate: das Fenster muss sofort enden.
        val result = detector.onSample(40 * sampleIntervalMs, 80.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(ZuptDetector.Segment.MOTION, result.segment)
        assertTrue("Uebergang Ruhe -> Bewegung muss gemeldet werden", result.motionStarted)
        assertFalse(detector.isStationary)
        assertNull("nach Bewegungsbeginn gibt es keinen gueltigen Bias", detector.biasEstimate)
    }

    @Test
    fun `umkehrpunkt einer wiederholung gilt nicht als ruhe`() {
        val detector = ZuptDetector()
        // Am Umkehrpunkt ist die Drehrate nahe null, die Accel-Magnitude
        // weicht aber wegen der Richtungsumkehr deutlich von 1 g ab.
        // Ausserdem dauert der Umkehrpunkt nur wenige Samples.
        for (i in 0 until 5) {
            detector.onSample(i * sampleIntervalMs, 1.0, 0.0, 0.0, 0.0, 0.0, 1.4)
        }
        assertFalse("Umkehrpunkt darf kein ZUPT-Fenster ausloesen", detector.isStationary)
    }

    @Test
    fun `restloses gyro aber bewegter accel ist keine ruhe`() {
        val detector = ZuptDetector()
        // Reine Translation ohne Rotation (Hantel wird gerade angehoben):
        // Gyro ~0, Accel deutlich ueber 1 g. Wuerde der Detektor das als Ruhe
        // werten, wanderte echte Bewegung in den Bias.
        for (i in 0 until 40) {
            detector.onSample(i * sampleIntervalMs, 0.5, 0.0, 0.0, 0.0, 0.0, 1.3)
        }
        assertFalse(detector.isStationary)
        assertEquals(0, detector.stationaryWindows)
    }

    @Test
    fun `mehrere ruhefenster werden gezaehlt`() {
        val detector = ZuptDetector()
        var t = 0L

        repeat(3) {
            // Ruhe (40 Samples = 800 ms).
            repeat(40) {
                quiet(t)(detector)
                t += sampleIntervalMs
            }
            // Bewegung (10 Samples).
            repeat(10) {
                detector.onSample(t, 60.0, 0.0, 0.0, 0.0, 0.0, 1.0)
                t += sampleIntervalMs
            }
        }

        assertEquals("drei getrennte Ruhefenster", 3, detector.stationaryWindows)
    }

    @Test
    fun `reset verwirft den zustand`() {
        val detector = ZuptDetector()
        for (i in 0 until 40) quiet(i * sampleIntervalMs, bias = 2.0)(detector)
        assertTrue(detector.isStationary)

        detector.reset()

        assertEquals(ZuptDetector.Segment.UNKNOWN, detector.segment)
        assertFalse(detector.isStationary)
        assertEquals(0, detector.stationaryWindows)
        assertNull(detector.biasEstimate)
    }

    @Test
    fun `zeitluecke im ruhefenster verlaengert es nicht kuenstlich`() {
        val detector = ZuptDetector()
        // Zwei ruhige Samples mit 5 s Abstand: die Zeitspanne ueberschreitet
        // die Mindestdauer, aber es liegen zu wenige Samples fuer einen
        // belastbaren Bias-Mittelwert vor.
        detector.onSample(0L, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0)
        val result = detector.onSample(5_000L, 1.0, 1.0, 1.0, 0.0, 0.0, 1.0)

        assertTrue("die Zeitspanne bestaetigt das Fenster", result.zuptConfirmed)
        assertNull(
            "mit 2 Samples darf kein Bias gemeldet werden (MIN_BIAS_SAMPLES)",
            detector.biasEstimate,
        )
    }
}
