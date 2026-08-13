package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port checks for the adaptive peak detector (shadow pipeline only). */
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
    }

    @Test
    fun `refractory suppresses immediate second peak`() {
        val detector = PeakDetector(refractorySeconds = 0.5) // 25 samples
        assertNotNull(feedPeak(detector, 0))
        // Second excursion starts inside the refractory window -> no peak.
        assertNull(feedPeak(detector, 15 * 20))
    }

    @Test
    fun `low prominence peak is rejected`() {
        val detector = PeakDetector(initialSpk = 100.0, initialNpk = 10.0)
        // theta = 32.5; amplitude 12 rises above theta but prominence ~18
        // stays below min = spk * 0.2 = 20 -> rejected.
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
    fun `updateLevels changes threshold`() {
        val detector = PeakDetector()
        detector.updateLevels(spk = 1000.0, npk = 100.0)
        assertEquals(325.0, detector.currentThreshold, 1e-6)
    }

    @Test
    fun `updateLevels from calibration profile values changes threshold`() {
        val detector = PeakDetector()
        assertEquals(32.5, detector.currentThreshold, 1e-6)
        detector.updateLevels(spk = 200.0, npk = 20.0)
        assertEquals(65.0, detector.currentThreshold, 1e-6)
    }
}
