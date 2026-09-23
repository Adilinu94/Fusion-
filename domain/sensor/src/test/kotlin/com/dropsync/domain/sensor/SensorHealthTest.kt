package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A3/S-2: Qualitaetsmatrix des [SensorHealth]. Bewertet wird der Gap im
 * Fenster ([SensorHealth.largestRecentGapMs]); der kumulative
 * [SensorHealth.largestGapMs] darf die Qualitaet NICHT mehr festnageln.
 */
class SensorHealthTest {
    private fun health(
        connection: SensorConnectionState = SensorConnectionState.STREAMING,
        loss: Double = 0.0,
        recentGapMs: Long = 0L,
        cumulativeGapMs: Long = 0L,
    ) = SensorHealth(
        connectionState = connection,
        recentPacketLossRate = loss,
        largestRecentGapMs = recentGapMs,
        largestGapMs = cumulativeGapMs,
    )

    @Test
    fun `nicht streamend ist immer unreliable`() {
        assertEquals(
            SignalQuality.UNRELIABLE,
            health(connection = SensorConnectionState.DISCONNECTED).quality,
        )
        assertEquals(
            SignalQuality.UNRELIABLE,
            health(connection = SensorConnectionState.CONNECTING).quality,
        )
    }

    @Test
    fun `gute werte ergeben GOOD`() {
        assertEquals(SignalQuality.GOOD, health().quality)
    }

    @Test
    fun `kumulativer gap ohne fenster-gap bleibt GOOD`() {
        // Der Kern von A3/S-2: der Gap ist laengst aus dem Fenster gefallen.
        assertEquals(
            SignalQuality.GOOD,
            health(recentGapMs = 0L, cumulativeGapMs = 560L).quality,
        )
    }

    @Test
    fun `fenster-gap ab 200 ms ist DEGRADED`() {
        assertEquals(SignalQuality.DEGRADED, health(recentGapMs = 200L).quality)
        assertEquals(SignalQuality.DEGRADED, health(recentGapMs = 499L).quality)
    }

    @Test
    fun `fenster-gap ab 500 ms ist UNRELIABLE`() {
        assertEquals(SignalQuality.UNRELIABLE, health(recentGapMs = 500L).quality)
    }

    @Test
    fun `verlustrate stufen`() {
        assertEquals(SignalQuality.GOOD, health(loss = 0.04).quality)
        assertEquals(SignalQuality.DEGRADED, health(loss = 0.05).quality)
        assertEquals(SignalQuality.DEGRADED, health(loss = 0.19).quality)
        assertEquals(SignalQuality.UNRELIABLE, health(loss = 0.20).quality)
    }
}
