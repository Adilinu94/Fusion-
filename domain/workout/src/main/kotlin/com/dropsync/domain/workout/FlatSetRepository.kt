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
) {
    /** Volumen in kg (fuer PR und Tages-Chart). */
    val volumeKg: Double
        get() = (weightMilliKg * reps) / 1_000_000.0
}

/** Tagesvolumen fuer Verlauf-Chart. */
data class DayVolume(
    val dayStartEpochMs: Long,
    val totalVolumeKg: Double,
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

    /** Letzter Satz der Uebung (Gewichts-Platzhalter). */
    suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?>

    /** Satz speichern. */
    suspend fun logSet(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ): AppResult<Long>

    /** Satz loeschen (Undo). */
    suspend fun deleteSet(setId: Long): AppResult<Unit>

    /** Max Volumen je Uebung (PR). */
    suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?>

    /** Volumen-Summe pro Tag (Verlauf). */
    suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?>

    /** Letzte N Saetze (Mini-Verlauf). */
    suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>>
}
