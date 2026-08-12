package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SetRole
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseDetail
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.PlaybackSnapshotInfo
import com.dropsync.domain.workout.PlayedTrackInfo
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.SegmentInput
import com.dropsync.domain.workout.SessionExerciseInfo
import com.dropsync.domain.workout.SwapStrategy
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.domain.workout.WorkoutSessionInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [WorkoutRepository]-Fake: minimal, liefert leere Listen und Success(0L).
 * Für das flache Satz-Log (Phase 2) reicht das; das eigentliche Loggen läuft
 * über [FakeFlatSetRepository]. Rein JVM.
 */
class FakeWorkoutRepository : WorkoutRepository {
    override val activeSession: Flow<WorkoutSessionInfo?> = flowOf(null)
    override fun observeExercises(locale: String): Flow<List<ExerciseInfo>> = flowOf(emptyList())
    override fun observeSessionExercises(sessionId: Long, locale: String): Flow<List<SessionExerciseInfo>> =
        flowOf(emptyList())

    override suspend fun startSession(title: String?, fromRoutineId: Long?): AppResult<Long> = AppResult.Success(0L)
    override suspend fun completeSession(sessionId: Long): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun discardSession(sessionId: Long): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun addExercise(sessionId: Long, exerciseId: Long, supersetGroupId: Long?): AppResult<Long> =
        AppResult.Success(0L)

    override suspend fun completeCluster(
        sessionExerciseId: Long,
        setRole: SetRole,
        segments: List<SegmentInput>,
        note: String?,
    ): AppResult<Long> = AppResult.Success(0L)

    override suspend fun undoCompleteCluster(clusterId: Long): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun recomputeRecords(exerciseId: Long): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun lastCompletedClusterPrefill(exerciseId: Long): AppResult<List<SegmentInput>> =
        AppResult.Success(emptyList())

    override suspend fun recordPlaybackSnapshot(
        sessionId: Long,
        songId: Long,
        markerId: Long?,
        positionMs: Long,
    ): AppResult<Long> = AppResult.Success(0L)

    override suspend fun getPlaybackSnapshots(sessionId: Long): AppResult<List<PlaybackSnapshotInfo>> =
        AppResult.Success(emptyList())

    override fun observeExerciseLibrary(locale: String): Flow<List<ExerciseLibraryItem>> = flowOf(emptyList())
    override suspend fun createCustomExercise(input: CustomExerciseInput): AppResult<Long> = AppResult.Success(0L)
    override suspend fun getExerciseDetail(exerciseId: Long, locale: String): AppResult<ExerciseDetail> =
        throw UnsupportedOperationException("not needed for this test")

    override suspend fun archiveExercise(exerciseId: Long): AppResult<Unit> = AppResult.Success(Unit)
    override suspend fun getRestPref(exerciseId: Long): AppResult<RestPref?> = AppResult.Success(null)
    override suspend fun setRestPref(exerciseId: Long, restSeconds: Int, restMode: RestMode): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun swapSessionExercise(
        sessionExerciseId: Long,
        newExerciseId: Long,
        strategy: SwapStrategy,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun repeatLastSession(): AppResult<Long> = AppResult.Success(0L)
    override fun observePersonalRecords(exerciseId: Long): Flow<List<PrRecord>> = flowOf(emptyList())
    override suspend fun getSessionMusic(sessionId: Long): AppResult<List<PlayedTrackInfo>> =
        AppResult.Success(emptyList())
}
