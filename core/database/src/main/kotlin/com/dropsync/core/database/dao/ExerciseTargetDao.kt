package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.dropsync.core.database.entity.ExerciseTargetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO fuer Trainingsziele je Uebung (Flowtimer-Integration Entscheidung 14).
 *
 * `@Upsert` statt `@Insert` + `@Update`: Da `exercise_id` der Primary Key
 * ist, ist "Ziel setzen" fachlich immer dasselbe — egal ob vorher eines
 * existierte. Zwei getrennte Wege waeren eine Fallunterscheidung ohne
 * fachlichen Unterschied.
 */
@Dao
interface ExerciseTargetDao {
    @Upsert
    suspend fun upsert(target: ExerciseTargetEntity)

    @Query("DELETE FROM exercise_targets WHERE exercise_id = :exerciseId")
    suspend fun delete(exerciseId: Long)

    @Query("SELECT * FROM exercise_targets WHERE exercise_id = :exerciseId")
    suspend fun getForExercise(exerciseId: Long): ExerciseTargetEntity?

    @Query("SELECT * FROM exercise_targets WHERE exercise_id = :exerciseId")
    fun observeForExercise(exerciseId: Long): Flow<ExerciseTargetEntity?>

    /**
     * Alle Ziele. Das Dashboard braucht sie gesammelt, um die
     * Zielstatus-Zeile ohne N+1-Abfragen zu bauen.
     */
    @Query("SELECT * FROM exercise_targets")
    fun observeAll(): Flow<List<ExerciseTargetEntity>>
}
