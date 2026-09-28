package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.dropsync.core.database.entity.FlatSetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO fuer das flache Satz-Log (FlowRep-Design Phase 2).
 * Jeder Satz haengt direkt an einer Uebung; keine Session noetig.
 */
@Dao
interface FlatSetDao {
    @Insert
    suspend fun insert(set: FlatSetEntity): Long

    @Query("DELETE FROM flat_sets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM flat_sets WHERE id = :id")
    suspend fun getById(id: Long): FlatSetEntity?

    /** Alle Saetze einer Uebung, neueste zuerst. */
    @Query("SELECT * FROM flat_sets WHERE exercise_id = :exerciseId ORDER BY logged_at_epoch_ms DESC")
    fun observeForExercise(exerciseId: Long): Flow<List<FlatSetEntity>>

    /** Alle Saetze einer Uebung fuer die PR-Neuberechnung (A1). */
    @Query("SELECT * FROM flat_sets WHERE exercise_id = :exerciseId ORDER BY logged_at_epoch_ms")
    suspend fun getForExercise(exerciseId: Long): List<FlatSetEntity>

    /** Alle Saetze, neueste zuerst (Verlauf). */
    @Query("SELECT * FROM flat_sets ORDER BY logged_at_epoch_ms DESC")
    fun observeAll(): Flow<List<FlatSetEntity>>

    /**
     * Befund 5.3: begrenzter Verlauf-Strom — die unbegrenzte Liste waechst
     * mit jedem Training (Speicher + Mapping je Emission). Aufrufer laden
     * seitenweise nach (`limit` erhoehen), statt alles auf einmal.
     */
    @Query("SELECT * FROM flat_sets ORDER BY logged_at_epoch_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FlatSetEntity>>

    /** Letzter Satz einer Uebung (fuer Gewichts-Platzhalter). */
    @Query("SELECT * FROM flat_sets WHERE exercise_id = :exerciseId ORDER BY logged_at_epoch_ms DESC LIMIT 1")
    suspend fun getLastForExercise(exerciseId: Long): FlatSetEntity?

    /** Max Volumen (weight * reps) je Uebung (PR). */
    @Query(
        "SELECT MAX(weight_milli_kg * reps) FROM flat_sets WHERE exercise_id = :exerciseId",
    )
    suspend fun getMaxVolumeForExercise(exerciseId: Long): Long?

    /** Volumen-Summe pro Tag (Verlauf-Chart). */
    @Query(
        "SELECT SUM(weight_milli_kg * reps) FROM flat_sets " +
            "WHERE logged_at_epoch_ms >= :dayStart AND logged_at_epoch_ms < :dayEnd",
    )
    suspend fun getVolumeForDay(
        dayStart: Long,
        dayEnd: Long,
    ): Long?

    /** Letzte N Saetze (Mini-Verlauf). */
    @Query("SELECT * FROM flat_sets ORDER BY logged_at_epoch_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<FlatSetEntity>

    /**
     * Haengt den Musikbezug der Historie auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7 in Verbindung mit 13.4).
     *
     * `flat_sets` hat bewusst **keinen** Fremdschluessel auf `songs` (ein
     * Satz soll das Verschwinden eines Titels ueberleben) — deshalb muss
     * die Zuordnung hier per UPDATE nachgezogen werden. Sonst zeigt die
     * Historie nach dem Verschieben einen Titel, den es nicht mehr gibt,
     * und die Frage "welche Musik lief bei meinem letzten PR?" ist
     * dauerhaft unbeantwortbar.
     */
    @Query("UPDATE flat_sets SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int

    /**
     * Gebuendelter Refetch nach Log/Undo (Befund 5.2): letzter Satz,
     * Max-Volumen und Mini-Verlauf in EINER Transaktion statt drei
     * getrennten Fahrten. Room darf Default-Methoden mit `@Transaction`
     * umgeben; die drei Queries laufen atomar im selben Snapshot.
     */
    @Transaction
    suspend fun getSummaries(
        exerciseId: Long,
        recentLimit: Int,
    ): FlatSetSummaries =
        FlatSetSummaries(
            last = getLastForExercise(exerciseId),
            maxVolumeMilliKg = getMaxVolumeForExercise(exerciseId),
            recent = getRecent(recentLimit),
        )
}

/** Ergebnis des gebuendelten Refetchs ([FlatSetDao.getSummaries]). */
data class FlatSetSummaries(
    val last: FlatSetEntity?,
    val maxVolumeMilliKg: Long?,
    val recent: List<FlatSetEntity>,
)
