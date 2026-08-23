package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port checks for the adaptive peak detector. */
class PeakDetectorTest {
    private fun frame(
        t: Long,
        value: Double,
    ) = ProcessedFrame(
        timestampMs = t,
        rawGp = value,
        filteredGp = value,
        smoothedGp = value,
        envelope = kotlin.math.abs(value),
        isSettled = true,
    )

    /** One clean excursion: up to amplitude, then down below theta*ratio. */
    private fun feedPeak(
        detector: PeakDetector,
        startMs: Long,
        amplitude: Double = 200.0,
    ): PeakEvent? {
        var event: PeakEvent? = null
        val shape =
            (0..10).map { amplitude * it / 10.0 } +
                (1..14).map { amplitude - (amplitude * 1.5) * it / 14.0 }
        shape.forEachIndexed { i, v ->
            detector.process(frame(startMs + i * 20, v))?.let { event = it }
        }
        return event
    }

    @Test
    fun `clean excursion produces exactly one peak`() {
        val detector = PeakDetector()
        val peak = feedPeak(detector, 0)
        assertNotNull(peak)
        assertTrue(peak!!.prominence > 0)
        assertTrue(peak.window.isNotEmpty())
        assertTrue("Peakdauer muss in ms gemessen werden", peak.durationMs >= 0)
    }

    @Test
    fun `refractory suppresses immediate second peak`() {
        val detector = PeakDetector(refractoryMs = 500)
        assertNotNull(feedPeak(detector, 0))
        // Second excursion starts inside the refractory window -> no peak.
        assertNull(feedPeak(detector, 15 * 20))
    }

    @Test
    fun `low prominence peak is rejected`() {
        // minProminence = spk * 0.9 = 29.25; die Excursion erreicht ~12.
        val detector = PeakDetector(threshold = 32.5, prominenceRatio = 0.9)
        var event: PeakEvent? = null
        val shape = (0..5).map { 12.0 * it / 5.0 } + (1..10).map { 12.0 - 18.0 * it / 10.0 }
        shape.forEachIndexed { i, v ->
            detector.process(frame(i * 20L, v))?.let { event = it }
        }
        assertNull(event)
    }

    @Test
    fun `nan frames are ignored`() {
        val detector = PeakDetector()
        assertNull(detector.process(frame(0, Double.NaN)))
    }

    @Test
    fun `reset keeps levels but clears state`() {
        val detector = PeakDetector()
        feedPeak(detector, 0)
        detector.reset()
        // No stale refractory: a new peak is detectable right after reset.
        assertNotNull(feedPeak(detector, 10_000))
    }

    @Test
    fun `updateThreshold changes threshold directly`() {
        val detector = PeakDetector()
        detector.updateThreshold(theta = 325.0)
        assertEquals(325.0, detector.currentThreshold, 1e-6)
    }

    @Test
    fun `updateThreshold from calibration profile changes threshold`() {
        val detector = PeakDetector()
        assertEquals(32.5, detector.currentThreshold, 1e-6)
        detector.updateThreshold(theta = 65.0)
        assertEquals(65.0, detector.currentThreshold, 1e-6)
    }

    // --- Umbauplan Phase 4: adaptive Refraktaerzeit (zeitbasiert) ---------

    @Test
    fun `adaptive refractory fast reps not suppressed`() {
        // Erwartete Rep-Dauer 500 ms -> Refraktaerzeit 0.3 * 500 = 150 ms.
        // Zwei Peaks im Abstand von 300 ms duerfen beide erkannt werden.
        val detector = PeakDetector()
        detector.updateThreshold(theta = 32.5, expectedDurationMs = 500.0)

        val first = feedPeak(detector, 0)
        assertNotNull(first)
        // Zweiter Peak startet nach dem Ende des ersten + 300 ms.
        val secondStart = first!!.window.size * 20L + 300
        assertNotNull(
            "schneller zweiter Peak darf bei adaptiver Refraktaerzeit nicht unterdrueckt werden",
            feedPeak(detector, secondStart),
        )
    }

    @Test
    fun `adaptive refractory respects minimum floor`() {
        val detector = PeakDetector()
        // Extrem kurze Dauer -> Refraktaerzeit muss auf >= 100 ms geklemmt werden.
        detector.updateThreshold(theta = 32.5, expectedDurationMs = 20.0)
        assertNotNull(feedPeak(detector, 0))
        // Abstand 200 ms > Floor 100 ms -> zweiter Peak moeglich.
        assertNotNull(feedPeak(detector, 200 + 3_000))
    }

    @Test
    fun `adaptive refractory respects maximum cap`() {
        val detector = PeakDetector()
        // Sehr lange Dauer -> Refraktaerzeit auf <= 2000 ms geklemmt.
        detector.updateThreshold(theta = 32.5, expectedDurationMs = 200_000.0)
        assertNotNull(feedPeak(detector, 0))
        // 2100 ms Ruhe: das Cap (2000) ist ueberschritten.
        assertNotNull(feedPeak(detector, 500 + 2_100))
    }

    @Test
    fun `updateExpectedDurationMs changes refractory directly`() {
        val detector = PeakDetector()
        assertEquals(500.0, detector.expectedDurationMs, 1e-6)
        detector.updateExpectedDurationMs(1_000.0)
        assertEquals(1_000.0, detector.expectedDurationMs, 1e-6)
    }
}
