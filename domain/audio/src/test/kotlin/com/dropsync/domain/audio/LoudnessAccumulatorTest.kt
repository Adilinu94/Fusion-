package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offtrack Phase 8: integrierte Lautheit und True-Peak-Naeherung. */
class LoudnessAccumulatorTest {
    @Test
    fun `lautes konstantes signal liefert negative lufs nahe null`() {
        val rate = 44_100
        val loudness = LoudnessAccumulator(rate, windowMs = 100)
        repeat(rate / 2) { loudness.accept(0.5) }
        val lufs = loudness.integratedLufs()
        assertNotNull(lufs)
        // 0.5 Amplitude = 0.25 Leistung = -6.02 dBFS, hier LUFS (400 ms Fenster)
        // leicht daneben durch das Gate; der Wert muss negativ und plausibel sein.
        assertTrue("lufs=$lufs", lufs!! in -20.0f..-2.0f)
        assertEquals(0.5, loudness.truePeakLinear(), 1e-9)
    }

    @Test
    fun `stille liefert keine integrierte lautheit`() {
        val loudness = LoudnessAccumulator(44_100, windowMs = 100)
        repeat(44_100) { loudness.accept(0.0) }
        assertNull(loudness.integratedLufs())
        assertEquals(0.0, loudness.truePeakLinear(), 1e-9)
    }

    @Test
    fun `true peak erfasst den groessten betrag`() {
        val loudness = LoudnessAccumulator(44_100, windowMs = 100)
        repeat(1_000) { loudness.accept(0.2) }
        loudness.accept(0.9)
        loudness.accept(-0.95)
        repeat(1_000) { loudness.accept(0.1) }
        assertEquals(0.95, loudness.truePeakLinear(), 1e-9)
    }

    @Test
    fun `kurzes signal mit halbem fenster zaehlt mit`() {
        val loudness = LoudnessAccumulator(44_100, windowMs = 100)
        // 50 ms konstant: das Restfenster ist mindestens halb gefuellt.
        repeat(44_100 / 20) { loudness.accept(0.4) }
        assertNotNull(loudness.integratedLufs())
    }
}
