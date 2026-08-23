package com.dropsync.data.workout

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.entity.FlatSetEntity
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Flat set log implementation (FlowRep Phase 2).
 * Uses FlatSetDao; no session logic.
 */
class FlatSetRepositoryImpl(
    private val flatSetDao: FlatSetDao,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : FlatSetRepository {
    override fun observeSetsForExercise(exerciseId: Long): Flow<List<FlatSet>> =
        flatSetDao.observeForExercise(exerciseId).map { list ->
            list.map { it.toDomain() }
        }

    override fun observeAllSets(): Flow<List<FlatSet>> =
        flatSetDao.observeAll().map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?> =
        withContext(dispatchers.io) {
            try {
                AppResult.success(flatSetDao.getLastForExercise(exerciseId)?.toDomain())
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getLastSet"))
            }
        }

    override suspend fun logSet(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ): AppResult<Long> {
        // Umbauplan Phase 10.5: die Repository-Grenze validiert unabhaengig
        // von der UI - negative Gewichte, NaN-Infinity oder unsinnige
        // Rep-Zahlen duerfen nie in die Datenbank gelangen.
        if (exerciseId <= 0) {
            return AppResult.failure(AppError.Unknown("Ungueltige Uebungs-ID: $exerciseId"))
        }
        if (weightMilliKg < 0 || weightMilliKg > MAX_WEIGHT_MILLI_KG) {
            return AppResult.failure(AppError.Unknown("Gewicht ausserhalb des gueltigen Bereichs"))
        }
        if (reps <= 0 || reps > MAX_REPS) {
            return AppResult.failure(AppError.Unknown("Wiederholungen ausserhalb des gueltigen Bereichs"))
        }
        return withContext(dispatchers.io) {
            try {
                AppResult.success(
                    flatSetDao.insert(
                        FlatSetEntity(
                            exerciseId = exerciseId,
                            weightMilliKg = weightMilliKg,
                            reps = reps,
                            loggedAtEpochMs = clock.epochMillis(),
                        ),
                    ),
                )
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("logSet"))
            }
        }
    }

    override suspend fun deleteSet(setId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                flatSetDao.delete(setId)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("deleteSet"))
            }
        }

    override suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?> =
        withContext(dispatchers.io) {
            try {
                AppResult.success(flatSetDao.getMaxVolumeForExercise(exerciseId))
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getMaxVolumeForExercise"))
            }
        }

    override suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?> =
        withContext(dispatchers.io) {
            try {
                val dayEnd = dayStart + DAY_MS
                AppResult.success(flatSetDao.getVolumeForDay(dayStart, dayEnd))
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getVolumeForDay"))
            }
        }

    override suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>> =
        withContext(dispatchers.io) {
            try {
                AppResult.success(flatSetDao.getRecent(limit).map { it.toDomain() })
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getRecentSets"))
            }
        }

    private fun FlatSetEntity.toDomain(): FlatSet =
        FlatSet(
            id = id,
            exerciseId = exerciseId,
            weightMilliKg = weightMilliKg,
            reps = reps,
            loggedAtEpochMs = loggedAtEpochMs,
        )

    private companion object {
        const val DAY_MS = 86_400_000L

        /** 1 Tonne in Milli-Kilogramm (Umbauplan Phase 10.5). */
        const val MAX_WEIGHT_MILLI_KG = 1_000_000_000L

        /** Sinnvolle Obergrenze pro Set (Umbauplan Phase 10.5). */
        const val MAX_REPS = 10_000
    }
}
