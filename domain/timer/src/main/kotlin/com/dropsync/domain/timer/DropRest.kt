package com.dropsync.domain.timer

// "Rest bis zum naechsten Drop" (Bauplan Schritt 11): reine Domainlogik
// ohne Media3- oder Room-Typen. Die Feature-Schicht liefert den
// Playerzustand als einfache Werte und ruft danach TimerEngine.startDropSync.

/** Aktiver Marker eines Songs als leichter Wertetyp (Position in ms). */
data class MarkerPoint(
    val markerId: Long,
    val positionMs: Long,
)

/** Momentaufnahme des Players fuer Gate und Ueberwachung. */
data class PlaybackSample(
    val songId: Long?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val hasError: Boolean = false,
)

/** Warum der Drop-Rest-Button deaktiviert ist (11.2, Abnahme 3). */
enum class DropRestBlockReason {
    /** Kein aktueller Song in einer laufenden Playback-Session. */
    NO_CURRENT_SONG,

    /** Playback pausiert oder gestoppt; der Modus braucht laufende Wiedergabe. */
    PLAYBACK_NOT_RUNNING,

    /** Der Song hat keinen zukuenftigen aktiven Marker. */
    NO_FUTURE_MARKER,

    /** Alle zukuenftigen Marker liegen unter der Mindestdauer von 5 s. */
    MARKER_TOO_CLOSE,
}

/**
 * Ergebnis der Vorbedingungspruefung. Die UI zeigt bei [Eligible] die
 * effektive Dauer `markerPosition - currentPlayerPosition` zur
 * Bestaetigung an; sie ist nie frei editierbar (11.3).
 */
sealed interface DropRestEligibility {
    data class Eligible(
        val markerId: Long,
        val markerPositionMs: Long,
        val effectiveDurationMs: Long,
    ) : DropRestEligibility

    data class Ineligible(
        val reason: DropRestBlockReason,
    ) : DropRestEligibility
}

/**
 * Vorbedingungen nach 11.2: genau ein aktueller Song, mindestens ein
 * zukuenftiger aktiver Marker und eine laufende Playback-Session.
 */
object DropRestGate {
    fun evaluate(
        sample: PlaybackSample,
        enabledMarkersOfCurrentSong: List<MarkerPoint>,
    ): DropRestEligibility {
        if (sample.songId == null) {
            return DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG)
        }
        if (!sample.isPlaying || sample.hasError) {
            return DropRestEligibility.Ineligible(DropRestBlockReason.PLAYBACK_NOT_RUNNING)
        }
        val future =
            enabledMarkersOfCurrentSong
                .filter { it.positionMs > sample.positionMs }
                .sortedBy { it.positionMs }
        if (future.isEmpty()) {
            return DropRestEligibility.Ineligible(DropRestBlockReason.NO_FUTURE_MARKER)
        }
        // Fruehester Marker, dessen effektive Dauer die Engine-Untergrenze
        // erfuellt; naehere Marker sind nicht startbar (7.4/11.3).
        val target =
            future.firstOrNull {
                it.positionMs - sample.positionMs >= TimerEngine.MIN_DROPSYNC_DURATION_MS
            } ?: return DropRestEligibility.Ineligible(DropRestBlockReason.MARKER_TOO_CLOSE)
        return DropRestEligibility.Eligible(
            markerId = target.markerId,
            markerPositionMs = target.positionMs,
            effectiveDurationMs = target.positionMs - sample.positionMs,
        )
    }
}

/**
 * Klares Ergebnis eines Abbruchs (11.4): Der Timer endet, die
 * Workout-Session bleibt unveraendert.
 */
enum class DropRestInterruption {
    SONG_CHANGED,
    SEEK,
    PAUSED,
    PLAYER_ERROR,
}

/**
 * Ueberwacht laufenden Drop-Rest anhand aufeinanderfolgender
 * [PlaybackSample]-Paare. Liefert null, solange die Wiedergabe normal
 * fortschreitet. Der Aufrufer beendet den Timer dann mit
 * [CancelReason.PLAYBACK_INTERRUPTED]; der Trainingssatz selbst bleibt
 * unveraendert (11.4). Es gibt keine automatische Satzbestaetigung beim
 * Drop (11.5).
 */
object DropRestMonitor {
    /** Toleranz zwischen erwarteter und realer Positionsaenderung. */
    const val SEEK_TOLERANCE_MS: Long = 1_500

    fun detect(
        startedSongId: Long,
        previous: PlaybackSample,
        current: PlaybackSample,
        elapsedMsBetweenSamples: Long,
    ): DropRestInterruption? {
        if (current.hasError) return DropRestInterruption.PLAYER_ERROR
        if (current.songId != startedSongId) return DropRestInterruption.SONG_CHANGED
        if (!current.isPlaying) return DropRestInterruption.PAUSED
        val expectedDelta = elapsedMsBetweenSamples
        val actualDelta = current.positionMs - previous.positionMs
        if (kotlin.math.abs(actualDelta - expectedDelta) > SEEK_TOLERANCE_MS) {
            return DropRestInterruption.SEEK
        }
        return null
    }
}
