package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

    /** Alle Saetze, neueste zuerst (Verlauf). */
    @Query("SELECT * FROM flat_sets ORDER BY logged_at_epoch_ms DESC")
    fun observeAll(): Flow<List<FlatSetEntity>>

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
}
