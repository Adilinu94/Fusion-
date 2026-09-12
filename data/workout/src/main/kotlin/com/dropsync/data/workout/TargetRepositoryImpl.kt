package com.dropsync.data.workout

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.dao.ExerciseTargetDao
import com.dropsync.core.database.entity.ExerciseTargetEntity
import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.TargetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Target repository implementation (DB v9, decision 14).
 *
 * Validates at the repository boundary independently of the UI, following
 * FlatSetRepositoryImpl: no negative or absurd values may reach the
 * database, no matter which caller.
 */
class TargetRepositoryImpl(
    private val targetDao: ExerciseTargetDao,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : TargetRepository {
    override fun observeTarget(exerciseId: Long): Flow<ExerciseTarget?> =
        targetDao.observeForExercise(exerciseId).map { it?.toDomain() }

    override fun observeAllTargets(): Flow<List<ExerciseTarget>> =
        targetDao.observeAll().map { list ->
            list.map {
                it.toDomain()
            }
        }

    override suspend fun getTarget(exerciseId: Long): AppResult<ExerciseTarget?> =
        withContext(dispatchers.io) {
            try {
                AppResult.success(targetDao.getForExercise(exerciseId)?.toDomain())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getTarget"))
            }
        }

    override suspend fun setTarget(
        exerciseId: Long,
        targetWeightMilliKg: Long,
        targetReps: Int,
    ): AppResult<Unit> {
        if (exerciseId <= 0) {
            return AppResult.failure(AppError.Unknown("Ungueltige Uebungs-ID: $exerciseId"))
        }
        // Ein Ziel von 0 kg oder 0 Reps ist kein Ziel — es waere immer
        // sofort erreicht und die Statuszeile saehe erfuellt aus, ohne dass
        // trainiert wurde.
        if (targetWeightMilliKg <= 0 || targetWeightMilliKg > MAX_WEIGHT_MILLI_KG) {
            return AppResult.failure(AppError.Unknown("Zielgewicht ausserhalb des gueltigen Bereichs"))
        }
        if (targetReps <= 0 || targetReps > MAX_REPS) {
            return AppResult.failure(AppError.Unknown("Ziel-Wiederholungen ausserhalb des gueltigen Bereichs"))
        }
        return withContext(dispatchers.io) {
            try {
                targetDao.upsert(
                    ExerciseTargetEntity(
                        exerciseId = exerciseId,
                        targetWeightMilliKg = targetWeightMilliKg,
                        targetReps = targetReps,
                        updatedAtEpochMs = clock.epochMillis(),
                    ),
                )
                AppResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("setTarget"))
            }
        }
    }

    override suspend fun clearTarget(exerciseId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                targetDao.delete(exerciseId)
                AppResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("clearTarget"))
            }
        }

    private fun ExerciseTargetEntity.toDomain(): ExerciseTarget =
        ExerciseTarget(
            exerciseId = exerciseId,
            targetWeightMilliKg = targetWeightMilliKg,
            targetReps = targetReps,
            updatedAtEpochMs = updatedAtEpochMs,
        )

    private companion object {
        /** 1 Tonne in Milli-Kilogramm, wie FlatSetRepositoryImpl. */
        const val MAX_WEIGHT_MILLI_KG = 1_000_000_000L

        /** Sinnvolle Obergrenze pro Satz, wie FlatSetRepositoryImpl. */
        const val MAX_REPS = 10_000
    }
}
