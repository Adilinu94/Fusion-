package com.dropsync.feature.player

import com.dropsync.domain.playback.AudioRouteProfile.Confidence
import com.dropsync.domain.timer.TimingConfidence
import org.junit.Assert.assertEquals
import org.junit.Test

class TimingConfidencePolicyTest {
    @Test
    fun `nur eine eingestellte Route ist EXACT`() {
        assertEquals(TimingConfidence.EXACT, timingConfidenceFor(120L, Confidence.CALIBRATED))
    }

    @Test
    fun `Tabellenwert ist nur eine Schaetzung`() {
        assertEquals(TimingConfidence.DEGRADED, timingConfidenceFor(120L, Confidence.ESTIMATED))
    }

    @Test
    fun `veraltetes oder fehlendes Profil ist nie EXACT`() {
        assertEquals(TimingConfidence.DEGRADED, timingConfidenceFor(120L, Confidence.STALE))
        assertEquals(TimingConfidence.DEGRADED, timingConfidenceFor(120L, null))
    }

    @Test
    fun `ohne Latenzwert ist nie EXACT, auch nicht bei CALIBRATED`() {
        assertEquals(TimingConfidence.DEGRADED, timingConfidenceFor(null, Confidence.CALIBRATED))
    }
}
