package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.TargetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [TargetRepository]-Fake mit In-Memory-Zustand. Rein JVM.
 *
 * Anders als [FakeWorkoutGoalRepository] veraenderbar: Die Ziel-Anzeige
 * haengt davon ab, dass ein neu gesetztes Ziel sofort im Flow erscheint —
 * ein statischer Fake koennte das nicht pruefen.
 *
 * [clock] liefert `updatedAtEpochMs`, damit Tests deterministisch bleiben.
 */
class FakeTargetRepository(
    initial: List<ExerciseTarget> = emptyList(),
    private val clock: () -> Long = { 0L },
) : TargetRepository {
    private val targets = MutableStateFlow(initial.associateBy { it.exerciseId })

    override fun observeTarget(exerciseId: Long): Flow<ExerciseTarget?> = targets.map { it[exerciseId] }

    override fun observeAllTargets(): Flow<List<ExerciseTarget>> = targets.map { it.values.toList() }

    override suspend fun getTarget(exerciseId: Long): AppResult<ExerciseTarget?> =
        AppResult.success(targets.value[exerciseId])

    override suspend fun setTarget(
        exerciseId: Long,
        targetWeightMilliKg: Long,
        targetReps: Int,
    ): AppResult<Unit> {
        targets.value =
            targets.value +
            (
                exerciseId to
                    ExerciseTarget(
                        exerciseId = exerciseId,
                        targetWeightMilliKg = targetWeightMilliKg,
                        targetReps = targetReps,
                        updatedAtEpochMs = clock(),
                    )
            )
        return AppResult.success(Unit)
    }

    override suspend fun clearTarget(exerciseId: Long): AppResult<Unit> {
        targets.value = targets.value - exerciseId
        return AppResult.success(Unit)
    }
}
