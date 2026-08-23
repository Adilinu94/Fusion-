package com.dropsync.domain.sensor

/**
 * Umbauplan Phase 3: grobe Signalqualitaet, damit die App zuverlaessig von
 * unzuverlaessig unterscheiden kann. Startwerte muessen mit Hardware-Traces
 * validiert werden.
 */
enum class SignalQuality {
    GOOD,
    DEGRADED,
    UNRELIABLE,
}

/**
 * Gesundheitszustand der Sensorstrecke (BLE -> Jitterbuffer). Der Provider
 * aktualisiert diesen Zustand bei jedem Batch und jedem Zustandswechsel.
 */
data class SensorHealth(
    val connectionState: SensorConnectionState = SensorConnectionState.DISCONNECTED,
    val receivedBatches: Long = 0,
    val duplicateBatches: Long = 0,
    val missedBatches: Long = 0,
    val parseErrors: Long = 0,
    val jitterBufferDrops: Long = 0,
    val largestGapMs: Long = 0,
    val recentPacketLossRate: Double = 0.0,
) {
    /**
     * Startwerte (Umbauplan Phase 3): bei >= 20 % Paketverlust oder einem
     * Gap >= 500 ms ist der Stream UNRELIABLE, ab 5 % / 200 ms DEGRADED.
     */
    val quality: SignalQuality
        get() =
            when {
                connectionState != SensorConnectionState.STREAMING -> SignalQuality.UNRELIABLE
                recentPacketLossRate >= 0.20 || largestGapMs >= 500 -> SignalQuality.UNRELIABLE
                recentPacketLossRate >= 0.05 || largestGapMs >= 200 -> SignalQuality.DEGRADED
                else -> SignalQuality.GOOD
            }
}
