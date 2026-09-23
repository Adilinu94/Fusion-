package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.SetSummaries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [FlatSetRepository]-Fake (Testinfra-Umbau Schritt 2): protokolliert jeden
 * geloggten Satz in [logged], liefert sonst leere Ergebnisse. Rein JVM.
 */
class FakeFlatSetRepository : FlatSetRepository {
    val logged = mutableListOf<FlatSet>()

    override fun observeSetsForExercise(exerciseId: Long): Flow<List<FlatSet>> = flowOf(emptyList())

    /** C6: steuerbarer Satz-Strom (Fehler-/Ladepfade testen). */
    var allSetsFlow: Flow<List<FlatSet>> = flowOf(emptyList())

    override fun observeAllSets(): Flow<List<FlatSet>> = allSetsFlow

    /**
     * Befund 5.3: dieselbe steuerbare Quelle wie [observeAllSets] (Fehler-
     * und Ladepfade der Tests wirken auf beide Stroeme), vereinfacht auf
     * die ersten [limit] Zeilen — die Testdaten sind bereits sortiert.
     */
    override fun observeRecentSets(limit: Int): Flow<List<FlatSet>> = allSetsFlow.map { it.take(limit) }

    override suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?> = AppResult.Success(null)

    override suspend fun logSet(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ): AppResult<Long> {
        val set =
            FlatSet(
                id = logged.size + 1L,
                exerciseId = exerciseId,
                weightMilliKg = weightMilliKg,
                reps = reps,
                loggedAtEpochMs = 0L,
            )
        logged += set
        return AppResult.Success(set.id)
    }

    override suspend fun deleteSet(setId: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?> = AppResult.Success(null)

    override suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?> = AppResult.Success(null)

    override suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>> = AppResult.Success(emptyList())

    override suspend fun getSetSummaries(
        exerciseId: Long,
        recentLimit: Int,
    ): AppResult<SetSummaries> {
        val forExercise = logged.filter { it.exerciseId == exerciseId }
        return AppResult.Success(
            SetSummaries(
                lastSet = forExercise.maxByOrNull { it.id },
                maxVolumeMilliKg = forExercise.maxOfOrNull { it.weightMilliKg * it.reps },
                recentSets = logged.takeLast(recentLimit).reversed(),
            ),
        )
    }
}
