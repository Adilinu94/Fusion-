package com.dropsync.data.timer

import com.dropsync.core.model.DuckingPercent
import com.dropsync.domain.playback.PlayerVolumeGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ducking gemaess Bauplan 5.3 und Schritt 8.4:
 *
 * - Es wird nur der EIGENE Player veraendert, nie die Systemlautstaerke.
 * - Unmittelbar vor der Ausgabe wird `baseVolume` gespeichert,
 *   `effectiveVolume = baseVolume * duckingFactor` gesetzt und nach der
 *   Ausgabe exakt der zuletzt bekannte `baseVolume` wiederhergestellt.
 * - Aendert die Person waehrend einer Ansage die Lautstaerke, wird der
 *   neue Wert als `baseVolume` uebernommen.
 * - Jede Antwort ist an die Cue-Session gebunden: eine veraltete
 *   TTS-Antwort darf die Lautstaerke einer neuen Session nie veraendern
 *   (Abnahme Schritt 8).
 */
class DuckingController(
    private val volumeGate: PlayerVolumeGate,
) {
    private val mutex = Mutex()
    private var activeSessionId: String? = null
    private var baseVolume: Float? = null

    /** true, wenn geduckt wurde; false bei ungueltigem Wert/fremder Session. */
    suspend fun beginCue(
        cueSessionId: String,
        duckingPercent: Int,
    ): Boolean =
        mutex.withLock {
            if (!DuckingPercent.isValid(duckingPercent)) return@withLock false
            if (activeSessionId != null && activeSessionId != cueSessionId) return@withLock false

            val base = baseVolume?.takeIf { activeSessionId == cueSessionId } ?: volumeGate.currentVolume()
            activeSessionId = cueSessionId
            baseVolume = base
            val factor = 1f - duckingPercent / 100f
            volumeGate.setVolume(base * factor)
            true
        }

    /** Nutzeraenderung waehrend der Ansage wird neuer baseVolume (5.3). */
    suspend fun onUserVolumeChanged(
        cueSessionId: String,
        newVolume: Float,
    ) {
        mutex.withLock {
            if (cueSessionId == activeSessionId) baseVolume = newVolume
        }
    }

    /** Stellt den zuletzt bekannten baseVolume wieder her (genau einmal). */
    suspend fun endCue(cueSessionId: String) {
        mutex.withLock {
            // Veraltete Antwort einer alten Session: ignorieren.
            if (cueSessionId != activeSessionId) return@withLock
            baseVolume?.let { volumeGate.setVolume(it) }
            activeSessionId = null
            baseVolume = null
        }
    }

    /** Sofortige Ruecknahme bei Cancel/Fehler/Completion (Schritt 7.7). */
    suspend fun abort(cueSessionId: String) = endCue(cueSessionId)
}
