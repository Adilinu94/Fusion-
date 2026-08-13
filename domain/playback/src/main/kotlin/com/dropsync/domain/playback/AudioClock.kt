package com.dropsync.domain.playback

/**
 * Abstraktion der Audio-Zeitbasis (Design Phase 6). Der Timer rechnet auf
 * der monotonen Systemuhr; die Audio-Ausgabe hat aber ihre eigene
 * Frame-Uhr mit Geraetelatenz. Diese Schnittstelle liefert die Position,
 * die der Zuhoerer gerade hoert:
 *
 *   audibleFrame ~= framePosition + (nowNs - tsNano) * rate  (5c-Extrapolation)
 *
 * - [Mode.EXACT]: AudioTimestamp valide, Extrapolation aktiv.
 * - [Mode.BEST_EFFORT]: Fallback auf Playhead-Position (kein valider
 *   Timestamp, Warm-up, Uhr rueckwaerts) - Positionen sind weiterhin
 *   monoton, aber die Latenz ist nicht herausgerechnet.
 * - [Mode.UNAVAILABLE]: keine brauchbare Quelle.
 *
 * Bewusst delta-basiert: Absolute Latenz ist geraeteabhaengig, Tests
 * pruefen nur Deltas (5c).
 */
interface AudioClock {
    /** Aktuell gehoerte Position in Millisekunden (Basis: [SystemClock.elapsedRealtime]). */
    fun audiblePositionMs(): Long

    /** Wie die Position zustande kam (fuer Timing-Hinweise in der UI). */
    val mode: Mode

    /** Liefert die Frame-Position zurueck auf einer einheitlichen Zeitleiste. */
    fun playheadPositionMs(): Long

    enum class Mode { EXACT, BEST_EFFORT, UNAVAILABLE }
}

/**
 * Latenzprofil einer Audio-Route (Design Phase 6, Abschnitt 10):
 * interner Lautsprecher / USB / Kabel / BT-Geraet + Codec + Sample-Rate.
 * Ohne Loopback-Messung (kein Mikrofon) entsteht das Profil aus
 * Latenz-Tabellen je Codec und wird durch Messungen verfeinert.
 */
data class AudioRouteProfile(
    val routeKey: String,
    val sampleRate: Int,
    val channels: Int,
    val estimatedLatencyMs: Long,
    val p50ErrorMs: Long? = null,
    val p95ErrorMs: Long? = null,
    val calibratedAt: Long? = null,
    val confidence: Confidence = Confidence.ESTIMATED,
) {
    enum class Confidence {
        /** Nur Tabellenwert, nie gemessen. */
        ESTIMATED,

        /** Mindestens einmal lokal gemessen/verfeinert. */
        CALIBRATED,

        /** Profil passt nicht mehr zur Route (Wechsel / Fokusverlust). */
        STALE,
    }

    val isReliable: Boolean
        get() = confidence == Confidence.CALIBRATED
}

/**
 * Invalidierungs-Token fuer geplante Audio-Events (Design Phase 6):
 * Bei jedem Skip, Satzwechsel oder Route-Wechsel wird die Generation
 * erhoeht; laufende Landungen/Fades mit alter Generation werden
 * verworfen.
 */
data class PlaybackGeneration(
    val value: Long,
) {
    fun next(): PlaybackGeneration = PlaybackGeneration(value + 1)

    companion object {
        val INITIAL = PlaybackGeneration(0L)
    }
}
