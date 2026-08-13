package com.dropsync.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7: Ducking-Mixer (min-Logik) und Rampe (Attack/Release). */
class DuckingMixerTest {
    @Test
    fun `rest ducking allein wirkt mit konfiguriertem db`() {
        // Rest -8 dB, kein Cue-Ducking (0): ~0.398.
        val gain = DuckingMixer.effectiveGain(restDuckDb = -8.0, cueDuckDb = 0.0)
        assertEquals(0.398, gain, 0.01)
    }

    @Test
    fun `cue ducking staerker als rest ducking gewinnt`() {
        // Rest -8, Cue -12 (TTS-Ansage): das staerkere -12 gewinnt.
        val gain = DuckingMixer.effectiveGain(restDuckDb = -8.0, cueDuckDb = -12.0)
        assertEquals(0.251, gain, 0.01)
    }

    @Test
    fun `rest ducking staerker als cue ducking gewinnt`() {
        // Rest -12, Cue -6: -12 gewinnt, nie additiv.
        val gain = DuckingMixer.effectiveGain(restDuckDb = -12.0, cueDuckDb = -6.0)
        assertEquals(0.251, gain, 0.01)
    }

    @Test
    fun `beide null ergibt eins`() {
        assertEquals(1.0, DuckingMixer.effectiveGain(0.0, 0.0), 1e-9)
    }

    @Test
    fun `rampe attack ist schneller als release`() {
        val ramp = DuckingRamp(attackMs = 40, releaseMs = 200)
        // Attack (1.0 -> 0.4) nach 20 ms: haelfte des Weges.
        assertEquals(0.7, ramp.next(1.0, 0.4, 20), 0.01)
        // Release (0.4 -> 1.0) nach 20 ms: nur ein Zehntel des Weges.
        assertEquals(0.46, ramp.next(0.4, 1.0, 20), 0.01)
    }

    @Test
    fun `rampe erreicht das ziel nach ablauf`() {
        val ramp = DuckingRamp(attackMs = 40, releaseMs = 200)
        assertEquals(0.4, ramp.next(1.0, 0.4, 100), 1e-9)
        assertEquals(1.0, ramp.next(0.4, 1.0, 500), 1e-9)
        assertTrue(ramp.next(0.4, 1.0, 500) <= 1.0)
    }
}
