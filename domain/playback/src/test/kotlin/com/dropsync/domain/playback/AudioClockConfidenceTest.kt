package com.dropsync.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifikation Offtrack Phase 8: Die Abbildung von AudioClock.Mode auf
 * die grobere ClockConfidence ist stabil und nachvollziehbar.
 */
class AudioClockConfidenceTest {
    @Test
    fun `exact wird als audio track estimate abgebildet`() {
        assertEquals(ClockConfidence.AUDIO_TRACK_ESTIMATE, AudioClock.Mode.EXACT.toConfidence())
    }

    @Test
    fun `best effort wird als software estimate abgebildet`() {
        assertEquals(ClockConfidence.SOFTWARE_ESTIMATE, AudioClock.Mode.BEST_EFFORT.toConfidence())
    }

    @Test
    fun `unavailable wird als unknown abgebildet`() {
        assertEquals(ClockConfidence.UNKNOWN, AudioClock.Mode.UNAVAILABLE.toConfidence())
    }
}
