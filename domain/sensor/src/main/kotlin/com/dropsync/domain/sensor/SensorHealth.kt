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
 * P2-17/RC-7: Transportweg der Sample-Zustellung. Der BLE-Provider startet
 * per Poll (Read) und wechselt auf Notify, sobald die Firmware es bestaetigt;
 * der Fake-Provider liefert ohne Funkstrecke.
 */
enum class SensorTransport {
    /** Firmware-Benachrichtigungen (bestaetigt). */
    BLE_NOTIFY,

    /** Aktives Abfragen per GATT-Read (Fallback vor Notify-Bestaetigung). */
    BLE_POLL,

    /** Manueller Fake ohne Chip (Entwicklungsbetrieb). */
    FAKE,

    /** Noch keine Aussage (nicht verbunden oder Health noch nie befuellt). */
    UNKNOWN,
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
    /**
     * RC-10: Samples, die der zentrale Fan-out wegen Ueberlast verdrangt
     * hat (DROP_OLDEST). Getrennt von [jitterBufferDrops] (dort geht es um
     * das Puffern der Ankunftsreihenfolge, hier um die Verteilung an die
     * Verbraucher).
     */
    val samplesDropped: Long = 0,
    val largestGapMs: Long = 0,
    /**
     * A3/S-2: groesster Gap im gleitenden Fenster (~5 s). Nur dieser Wert
     * fliesst in [quality] ein — der kumulative [largestGapMs] bleibt fuer
     * die Diagnose erhalten. Ein einzelner Aussetzer sperrt die Zaehlung
     * damit nicht mehr bis zum Reconnect.
     */
    val largestRecentGapMs: Long = 0,
    val recentPacketLossRate: Double = 0.0,
    /**
     * T-10/S-7: fehlgeschlagene Geraete-Event-Polls (M5-Taste). Der Pfad
     * lief vorher in einem stummen `runCatching`; jetzt zaehlt er hier,
     * damit das Diagnose-Panel ihn zeigen kann.
     */
    val deviceEventPollErrors: Long = 0,
    /** P2-17/RC-7: aktiver Transportweg (Diagnose-Panel). */
    val transport: SensorTransport = SensorTransport.UNKNOWN,
    /** P2-17/RC-7: ausgehandelte MTU in Bytes; null, wenn nie ausgehandelt. */
    val negotiatedMtu: Int? = null,
) {
    /**
     * Startwerte (Umbauplan Phase 3): bei >= 20 % Paketverlust oder einem
     * Gap >= 500 ms ist der Stream UNRELIABLE, ab 5 % / 200 ms DEGRADED.
     *
     * A3/S-2: bewertet wird der Gap im Fenster ([largestRecentGapMs]), nicht
     * der kumulative Session-Wert.
     */
    val quality: SignalQuality
        get() =
            when {
                connectionState != SensorConnectionState.STREAMING -> SignalQuality.UNRELIABLE
                recentPacketLossRate >= 0.20 || largestRecentGapMs >= 500 -> SignalQuality.UNRELIABLE
                recentPacketLossRate >= 0.05 || largestRecentGapMs >= 200 -> SignalQuality.DEGRADED
                else -> SignalQuality.GOOD
            }
}
