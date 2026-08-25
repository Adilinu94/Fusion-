package com.dropsync.domain.workout

import com.dropsync.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Trainingsziel einer Uebung (Flowtimer-Integration Entscheidung 14, E4):
 * Zielgewicht UND Ziel-Wiederholungen, beide von EINEM einzelnen Satz zu
 * erfuellen. Kein Volumenvergleich.
 *
 * Gewicht in ganzen Millikilogramm (Long) wie ueberall in Fusions
 * Datenmodell; die Umrechnung nach Kilogramm passiert erst an der
 * Kern-Grenze.
 */
data class ExerciseTarget(
    val exerciseId: Long,
    val targetWeightMilliKg: Long,
    val targetReps: Int,
    val updatedAtEpochMs: Long,
) {
    init {
        require(targetReps > 0) { "targetReps muss positiv sein: $targetReps" }
        require(targetWeightMilliKg > 0) {
            "targetWeightMilliKg muss positiv sein: $targetWeightMilliKg"
        }
    }
}

/**
 * Zielstatus einer Uebung fuer die Anzeige (UI-Vertrag R7). [reached] gilt,
 * wenn der beste Satz beide Bedingungen erfuellt; [progress] ist der
 * Fortschritt 0..1 zum Zielgewicht, damit die Zeile auch vor dem Treffer
 * etwas aussagt.
 */
data class TargetStatus(
    val target: ExerciseTarget,
    val reached: Boolean,
    val progress: Float,
    val bestWeightMilliKg: Long?,
    val bestReps: Int?,
)

/**
 * Repository fuer Trainingsziele (Room, DB v9). Ein Ziel je Uebung —
 * `exerciseId` ist der Primary Key, `setTarget` ist deshalb ein Upsert.
 *
 * Bewusst getrennt von [WorkoutGoalRepository]: Das Wochenziel ist eine
 * Geraeteeinstellung im DataStore, ein Uebungsziel ist ein Trainingsdatum
 * in der Datenbank.
 */
interface TargetRepository {
    /** Ziel einer Uebung; `null` wenn keines gesetzt ist. */
    fun observeTarget(exerciseId: Long): Flow<ExerciseTarget?>

    /** Alle Ziele — das Dashboard braucht sie gesammelt, ohne N+1-Abfragen. */
    fun observeAllTargets(): Flow<List<ExerciseTarget>>

    suspend fun getTarget(exerciseId: Long): AppResult<ExerciseTarget?>

    /**
     * Setzt oder ersetzt das Ziel einer Uebung. Beides ist fachlich
     * derselbe Vorgang, weil es je Uebung nur ein Ziel gibt.
     */
    suspend fun setTarget(
        exerciseId: Long,
        targetWeightMilliKg: Long,
        targetReps: Int,
    ): AppResult<Unit>

    /** Entfernt das Ziel; die Uebung selbst bleibt unangetastet. */
    suspend fun clearTarget(exerciseId: Long): AppResult<Unit>
}
