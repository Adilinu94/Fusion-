package com.dropsync.domain.library

import com.dropsync.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Bevorzugtes DropSync-Ziel je Song (P2-21, "Als DropSync-Ziel waehlen"
 * im Now-Playing).
 *
 * Das Ziel ist eine Nutzerentscheidung, keine zweite Wahrheit ueber
 * Marker: Es zeigt auf eine bestehende [com.dropsync.core.model.SongMarker]-ID
 * und wird nur ausgewertet, solange der Marker existiert und aktiv ist.
 * Ohne Ziel plant der [com.dropsync.domain.timer.DropLandingPlanner] wie
 * bisher den naechsten Marker des Titels.
 *
 * Persistenz bewusst ausserhalb von Room (DataStore): Es gibt keine neue
 * Entitaet und keine Migration; ein geloeschter Marker macht sein Ziel
 * wirkungslos, ohne dass eine Fremdschluessel-Beziehung aufgeraeumt
 * werden muss.
 */
interface DropTargetRepository {
    /** Ziel-Marker eines Songs; null = kein bevorzugtes Ziel. */
    fun observeTargetMarkerId(songId: Long): Flow<Long?>

    /**
     * Alle gesetzten Ziele (songId -> markerId) fuer die Planung. Die
     * Planung liest einmal je Lauf, nicht je Kandidat.
     */
    val targets: Flow<Map<Long, Long>>

    /** Setzt (oder ersetzt) das bevorzugte Ziel eines Songs. */
    suspend fun setTarget(
        songId: Long,
        markerId: Long,
    ): AppResult<Unit>

    /** Entfernt das bevorzugte Ziel eines Songs (Rest bleibt automatisch). */
    suspend fun clearTarget(songId: Long): AppResult<Unit>
}
