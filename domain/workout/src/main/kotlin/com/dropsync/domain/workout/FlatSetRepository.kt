package com.dropsync.domain.workout

import com.dropsync.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Flacher Satz (FlowRep-Design Phase 2): direkt einer Uebung zugeordnet,
 * ohne Session/Cluster/Segment. Gewicht in Millikilogramm (Long).
 */
data class FlatSet(
    val id: Long,
    val exerciseId: Long,
    val weightMilliKg: Long,
    val reps: Int,
    val loggedAtEpochMs: Long,
    /**
     * MediaStore-ID des Titels, der waehrend dieses Satzes lief
     * (2026-09-27, Befund 11.3/13.4). `null`, wenn nichts lief.
     */
    val songId: Long? = null,
    /**
     * Position im Titel in Millisekunden beim Loggen. Zusammen mit
     * [songId] verortet das den Satz zeitlich im Track.
     */
    val playbackPositionMs: Long? = null,
    /**
     * Marker, der beim Loggen als Drop-Ziel aktiv war — die wertvolle
     * Spalte: sie macht sichtbar, ob der Satz auf einen Drop landete.
     */
    val markerId: Long? = null,
) {
    /** Volumen in kg (fuer PR und Tages-Chart). */
    val volumeKg: Double
        get() = (weightMilliKg * reps) / 1_000_000.0

    /**
     * true, wenn der Satz mit Musikbezug geloggt wurde. Die UI nutzt das
     * fuer die Anzeige, nicht fuer eine Bedingung im Code — der
     * Musikbezug ist eine **Information**, kein Kriterium.
     */
    val hasMusicContext: Boolean
        get() = songId != null
}

/**
 * Musikbezug beim Satz-Loggen (2026-09-27, Befund 11.3/13.4).
 *
 * Eine eigene Klasse statt drei Parameter: die drei Werte gehoren
 * zusammen (alle drei oder keiner), und an drei Stellen im Code (SetLog,
 * Undo, Test-Seed) waeren drei nullable Long sonst drei Fehlerquellen.
 */
data class MusicContext(
    val songId: Long?,
    val playbackPositionMs: Long?,
    val markerId: Long?,
) {
    companion object {
        /** Kein Musikbezug (manuelles Logging ohne laufende Wiedergabe). */
        val NONE = MusicContext(songId = null, playbackPositionMs = null, markerId = null)

        /**
         * Baut den Bezug aus dem aktuellen Player-Zustand, oder [NONE]
         * wenn nichts laeuft.
         *
         * [activeMarkerId] kommt aus dem DropSync-Zustand: es ist der
         * Marker, den der Koordinator **fuer diese Pause** geplant hat,
         * nicht irgendein Marker des Titels. Genau der macht die Aussage
         * "dieser Satz landete auf diesem Drop" moeglich.
         */
        fun of(
            songId: Long?,
            positionMs: Long?,
            activeMarkerId: Long?,
        ): MusicContext {
            val id = songId ?: return NONE
            return MusicContext(
                songId = id,
                playbackPositionMs = positionMs?.coerceAtLeast(0),
                markerId = activeMarkerId,
            )
        }
    }
}

/** Tagesvolumen fuer Verlauf-Chart. */
data class DayVolume(
    val dayStartEpochMs: Long,
    val totalVolumeKg: Double,
)

/**
 * Gebuendelte Zusammenfassung nach Log/Undo (Befund 5.2): ein Aufruf, eine
 * Transaktion — statt drei getrennten Roundtrips je geloggtem Satz.
 */
data class SetSummaries(
    val lastSet: FlatSet?,
    val maxVolumeMilliKg: Long?,
    val recentSets: List<FlatSet>,
)

/**
 * Repository fuer das flache Satz-Log (FlowRep Phase 2).
 * Ersetzt nicht WorkoutRepository, sondern ergaenzt es als
 * einfache Alternative ohne Session-Overhead.
 */
interface FlatSetRepository {
    /** Alle Saetze einer Uebung, neueste zuerst. */
    fun observeSetsForExercise(exerciseId: Long): Flow<List<FlatSet>>

    /** Alle Saetze, neueste zuerst (Verlauf). */
    fun observeAllSets(): Flow<List<FlatSet>>

    /**
     * Befund 5.3: begrenzter Verlauf-Strom (neueste zuerst, max. [limit]).
     * Listen-UI laedt seitenweise nach, statt die gesamte Historie in den
     * Speicher zu holen.
     */
    fun observeRecentSets(limit: Int): Flow<List<FlatSet>>

    /** Letzter Satz der Uebung (Gewichts-Platzhalter). */
    suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?>

    /**
     * Satz speichern.
     *
     * [musicContext] ist der Musikbezug des Satzes (2026-09-27, Befund
     * 11.3/13.4). Er wird in **derselben** Transaktion geschrieben wie
     * Gewicht und Reps — das war die ganze Crux des Befunds: der
     * vorhandene `PlaybackSnapshotEntity`-Pfad lief ueber
     * `completeCluster()`, was der Produktivpfad nie aufruft, und kostete
     * einen zweiten Roundtrip. Drei nullable Spalten im Satz sind
     * billiger und koennen nicht auseinanderlaufen.
     */
    suspend fun logSet(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
        musicContext: MusicContext = MusicContext.NONE,
    ): AppResult<Long>

    /** Satz loeschen (Undo). */
    suspend fun deleteSet(setId: Long): AppResult<Unit>

    /** Max Volumen je Uebung (PR). */
    suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?>

    /** Volumen-Summe pro Tag (Verlauf). */
    suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?>

    /** Letzte N Saetze (Mini-Verlauf). */
    suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>>

    /**
     * Gebuendelter Refetch nach Log/Undo (Befund 5.2): letzter Satz,
     * Max-Volumen und Mini-Verlauf in einem Aufruf statt drei Roundtrips.
     */
    suspend fun getSetSummaries(
        exerciseId: Long,
        recentLimit: Int,
    ): AppResult<SetSummaries>
}
