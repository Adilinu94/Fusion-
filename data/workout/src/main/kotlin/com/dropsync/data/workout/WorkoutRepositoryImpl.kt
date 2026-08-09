package com.dropsync.data.workout

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.common.getOrNull
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.ExerciseDao
import com.dropsync.core.database.dao.RoutineDao
import com.dropsync.core.database.dao.WorkoutDao
import com.dropsync.core.database.entity.ExerciseEntity
import com.dropsync.core.database.entity.ExerciseMuscleEntity
import com.dropsync.core.database.entity.ExerciseNameEntity
import com.dropsync.core.database.entity.ExerciseRestPrefEntity
import com.dropsync.core.database.entity.PersonalRecordEntity
import com.dropsync.core.database.entity.PlaybackSnapshotEntity
import com.dropsync.core.database.entity.RoutineEntity
import com.dropsync.core.database.entity.RoutineExerciseEntity
import com.dropsync.core.database.entity.SessionExerciseEntity
import com.dropsync.core.database.entity.SetClusterEntity
import com.dropsync.core.database.entity.SetSegmentEntity
import com.dropsync.core.database.entity.WorkoutSessionEntity
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SessionStatus
import com.dropsync.core.model.SetRole
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseDetail
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.ExerciseProgressPoint
import com.dropsync.domain.workout.MuscleContribution
import com.dropsync.domain.workout.PlaybackSnapshotInfo
import com.dropsync.domain.workout.PlayedTrackInfo
import com.dropsync.domain.workout.PrCalculator
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.ProgressSeriesBuilder
import com.dropsync.domain.workout.QualifiedSegment
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.RoutineDetail
import com.dropsync.domain.workout.RoutineEntry
import com.dropsync.domain.workout.RoutineExerciseDetail
import com.dropsync.domain.workout.RoutineExpander
import com.dropsync.domain.workout.RoutineInfo
import com.dropsync.domain.workout.SegmentInput
import com.dropsync.domain.workout.SessionExerciseInfo
import com.dropsync.domain.workout.Slugs
import com.dropsync.domain.workout.SupersetRules
import com.dropsync.domain.workout.SwapStrategy
import com.dropsync.domain.workout.WorkoutMath
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.domain.workout.WorkoutSessionInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Trainingslog (Bauplan Schritt 9/10).
 *
 * - Satzabschluss + PR-Bestimmung sind EINE Transaktion (10.3).
 * - PRs entstehen immer durch vollstaendige Neuberechnung der Uebung
 *   (10.4); Gleichstand erzeugt nie eine neue PR, weil das frueheste
 *   Segment den Rekord haelt.
 * - Verwerfen setzt nur status = DISCARDED und loescht nichts (9.8).
 */
class WorkoutRepositoryImpl(
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val transactionRunner: TransactionRunner,
    private val playbackRepository: PlaybackRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : WorkoutRepository {
    override val activeSession: Flow<WorkoutSessionInfo?> =
        workoutDao.observeActiveSession().map { entity ->
            entity?.let {
                WorkoutSessionInfo(
                    id = it.id,
                    startedAtEpochMs = it.startedAtEpochMs,
                    status = SessionStatus.valueOf(it.status),
                    title = it.title,
                )
            }
        }

    override fun observeExercises(locale: String): Flow<List<ExerciseInfo>> =
        exerciseDao.observeActiveWithNames(locale).map { rows ->
            rows.map { row ->
                ExerciseInfo(
                    id = row.id,
                    slug = row.slug,
                    // Fallback auf den sprachneutralen Slug (Abschnitt 2).
                    displayName = row.displayName ?: row.slug,
                )
            }
        }

    override fun observeSessionExercises(
        sessionId: Long,
        locale: String,
    ): Flow<List<SessionExerciseInfo>> =
        workoutDao.observeSessionExercises(sessionId, locale).map { rows ->
            rows.map { row ->
                SessionExerciseInfo(
                    id = row.id,
                    exerciseId = row.exerciseId,
                    orderIndex = row.orderIndex,
                    displayName = row.displayName ?: row.slug,
                )
            }
        }

    override suspend fun startSession(
        title: String?,
        fromRoutineId: Long?,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val sessionId =
                        workoutDao.insertSession(
                            WorkoutSessionEntity(
                                startedAtEpochMs = clock.epochMillis(),
                                endedAtEpochMs = null,
                                zoneIdAtStart = ZoneId.systemDefault().id,
                                status = SessionStatus.ACTIVE.name,
                                title = title,
                                notes = null,
                            ),
                        )
                    if (fromRoutineId != null) {
                        // Routinen-Expansion (9.7): Reihenfolge und
                        // Supersetgruppen, keine historischen Gewichte.
                        val entries =
                            routineDao.getExercisesForRoutine(fromRoutineId).map {
                                RoutineEntry(
                                    exerciseId = it.exerciseId,
                                    orderIndex = it.orderIndex,
                                    supersetGroupId = it.supersetGroupId,
                                    targetSets = it.targetSets,
                                    targetRepsMin = it.targetRepsMin,
                                    targetRepsMax = it.targetRepsMax,
                                    restSeconds = it.restSeconds,
                                )
                            }
                        for (planned in RoutineExpander.expand(entries)) {
                            workoutDao.insertSessionExercise(
                                SessionExerciseEntity(
                                    sessionId = sessionId,
                                    exerciseId = planned.exerciseId,
                                    orderIndex = planned.orderIndex,
                                    supersetGroupId = planned.supersetGroupId,
                                ),
                            )
                        }
                    }
                    AppResult.success(sessionId)
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("startSession"))
            }
        }

    override suspend fun completeSession(sessionId: Long): AppResult<Unit> =
        setStatus(sessionId, SessionStatus.COMPLETED)

    override suspend fun discardSession(sessionId: Long): AppResult<Unit> =
        setStatus(sessionId, SessionStatus.DISCARDED)

    private suspend fun setStatus(
        sessionId: Long,
        status: SessionStatus,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                workoutDao.getSession(sessionId)
                    ?: return@withContext AppResult.failure(
                        AppError.DatabaseFailure("Session $sessionId fehlt"),
                    )
                workoutDao.updateSessionStatus(sessionId, status.name, clock.epochMillis())
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("setStatus"))
            }
        }

    override suspend fun addExercise(
        sessionId: Long,
        exerciseId: Long,
        supersetGroupId: Long?,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            try {
                val orderIndex = workoutDao.nextExerciseOrderIndex(sessionId)
                val id =
                    workoutDao.insertSessionExercise(
                        SessionExerciseEntity(
                            sessionId = sessionId,
                            exerciseId = exerciseId,
                            orderIndex = orderIndex,
                            supersetGroupId = supersetGroupId,
                        ),
                    )
                AppResult.success(id)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("addExercise"))
            }
        }

    override suspend fun completeCluster(
        sessionExerciseId: Long,
        setRole: SetRole,
        segments: List<SegmentInput>,
        note: String?,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            if (segments.isEmpty()) {
                return@withContext AppResult.failure(
                    AppError.Unknown("Cluster ohne Segmente"),
                )
            }
            if (segments.any { !WorkoutMath.isValidLoadMultiplier(it.loadMultiplier) }) {
                return@withContext AppResult.failure(
                    AppError.Unknown("loadMultiplier muss 1 oder 2 sein (5.4)"),
                )
            }
            try {
                val result =
                    transactionRunner {
                        val sessionExercise =
                            workoutDao.getSessionExercise(sessionExerciseId)
                                ?: throw IllegalStateException("SessionExercise fehlt")
                        val clusterId =
                            workoutDao.insertCluster(
                                SetClusterEntity(
                                    sessionExerciseId = sessionExerciseId,
                                    orderIndex = workoutDao.nextClusterOrderIndex(sessionExerciseId),
                                    setRole = setRole.name,
                                    isCompleted = false,
                                    note = note,
                                    completedAtEpochMs = null,
                                ),
                            )
                        workoutDao.insertSegments(
                            segments.mapIndexed { index, segment ->
                                SetSegmentEntity(
                                    clusterId = clusterId,
                                    segmentIndex = index,
                                    externalLoadMilliKgPerImplement = segment.externalLoadMilliKgPerImplement,
                                    loadMultiplier = segment.loadMultiplier,
                                    reps = segment.reps,
                                    durationMs = segment.durationMs,
                                    distanceM = segment.distanceM,
                                )
                            },
                        )
                        workoutDao.markClusterCompleted(clusterId, clock.epochMillis())
                        // PR-Bestimmung in derselben Transaktion (10.3) als
                        // vollstaendige Neuberechnung (10.4).
                        recomputeInTransaction(sessionExercise.exerciseId)
                        AppResult.success(clusterId)
                    }
                // Optionale Musikverknuepfung (11.1): best-effort, ausserhalb der
                // Transaktion; ein Fehler darf den Satz nie fehlschlagen lassen.
                capturePlaybackSnapshotBestEffort(sessionExerciseId)
                result
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("completeCluster"))
            }
        }

    override suspend fun undoCompleteCluster(clusterId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val cluster =
                        workoutDao.getCluster(clusterId)
                            ?: throw IllegalStateException("Cluster $clusterId fehlt")
                    val sessionExercise =
                        workoutDao.getSessionExercise(cluster.sessionExerciseId)
                            ?: throw IllegalStateException("SessionExercise fehlt")
                    workoutDao.deleteSegmentsForCluster(clusterId)
                    workoutDao.deleteCluster(clusterId)
                    // Loeschung erzwingt vollstaendige PR-Neuberechnung (10.4).
                    recomputeInTransaction(sessionExercise.exerciseId)
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("undoCompleteCluster"))
            }
        }

    override suspend fun recomputeRecords(exerciseId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner { recomputeInTransaction(exerciseId) }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("recomputeRecords"))
            }
        }

    override suspend fun lastCompletedClusterPrefill(exerciseId: Long): AppResult<List<SegmentInput>> =
        withContext(dispatchers.io) {
            try {
                val segments =
                    workoutDao.getSegmentsOfLastCompletedCluster(exerciseId).map {
                        SegmentInput(
                            externalLoadMilliKgPerImplement = it.externalLoadMilliKgPerImplement,
                            loadMultiplier = it.loadMultiplier,
                            reps = it.reps,
                            durationMs = it.durationMs,
                            distanceM = it.distanceM,
                        )
                    }
                AppResult.success(segments)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("lastCompletedClusterPrefill"))
            }
        }

    override suspend fun recordPlaybackSnapshot(
        sessionId: Long,
        songId: Long,
        markerId: Long?,
        positionMs: Long,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            try {
                // 11.1: append-only; nur Referenzen und Position, nie eine
                // Queuekopie. Fremdschluessel sichern Song/Session-Existenz.
                workoutDao.getSession(sessionId)
                    ?: return@withContext AppResult.failure(
                        AppError.DatabaseFailure("Session $sessionId fehlt fuer Snapshot"),
                    )
                val id =
                    workoutDao.insertPlaybackSnapshot(
                        PlaybackSnapshotEntity(
                            sessionId = sessionId,
                            songId = songId,
                            markerId = markerId,
                            positionMs = positionMs,
                            capturedAtEpochMs = clock.epochMillis(),
                        ),
                    )
                AppResult.success(id)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("recordPlaybackSnapshot"))
            }
        }

    override suspend fun getPlaybackSnapshots(sessionId: Long): AppResult<List<PlaybackSnapshotInfo>> =
        withContext(dispatchers.io) {
            try {
                val snapshots =
                    workoutDao.getPlaybackSnapshotsForSession(sessionId).map {
                        PlaybackSnapshotInfo(
                            id = it.id,
                            sessionId = it.sessionId,
                            songId = it.songId,
                            markerId = it.markerId,
                            positionMs = it.positionMs,
                            capturedAtEpochMs = it.capturedAtEpochMs,
                        )
                    }
                AppResult.success(snapshots)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getPlaybackSnapshots"))
            }
        }

    override fun observeExerciseLibrary(locale: String): Flow<List<ExerciseLibraryItem>> =
        exerciseDao.observeLibrary(locale).map { rows ->
            rows.map { row ->
                ExerciseLibraryItem(
                    id = row.id,
                    slug = row.slug,
                    displayName = row.displayName ?: row.slug,
                    equipment = runCatching { Equipment.valueOf(row.equipment) }.getOrDefault(Equipment.OTHER),
                    isCustom = row.isCustom,
                )
            }
        }

    override suspend fun createCustomExercise(input: CustomExerciseInput): AppResult<Long> =
        withContext(dispatchers.io) {
            // Mindestens de + en (analog Seeder, Schritt 9.1).
            if (!input.displayNames.containsKey("de") || !input.displayNames.containsKey("en")) {
                return@withContext AppResult.failure(AppError.Unknown("Namen fuer de und en noetig"))
            }
            if (input.muscles.isEmpty() || input.muscles.any { it.percent !in 1..100 }) {
                return@withContext AppResult.failure(AppError.Unknown("Muskelbeitrag 1..100 noetig"))
            }
            try {
                transactionRunner {
                    val baseName = input.displayNames["en"] ?: input.displayNames.values.first()
                    val slug = uniqueSlug(baseName)
                    val exerciseId =
                        exerciseDao.insertCustomExercise(
                            ExerciseEntity(
                                canonicalName = slug,
                                kind = input.kind.name,
                                equipment = input.equipment.name,
                                isCustom = true,
                                isArchived = false,
                            ),
                        )
                    exerciseDao.insertNamesIgnoring(
                        input.displayNames.map { (locale, name) ->
                            ExerciseNameEntity(exerciseId = exerciseId, locale = locale, displayName = name)
                        },
                    )
                    exerciseDao.insertMusclesIgnoring(
                        input.muscles.map {
                            ExerciseMuscleEntity(
                                exerciseId = exerciseId,
                                muscleGroupId = it.group.name,
                                contributionPercent = it.percent,
                            )
                        },
                    )
                    AppResult.success(exerciseId)
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("createCustomExercise"))
            }
        }

    override suspend fun getExerciseDetail(
        exerciseId: Long,
        locale: String,
    ): AppResult<ExerciseDetail> =
        withContext(dispatchers.io) {
            try {
                val exercise =
                    exerciseDao.getExercise(exerciseId)
                        ?: return@withContext AppResult.failure(AppError.DatabaseFailure("Uebung fehlt"))
                val name = exerciseDao.getDisplayName(exerciseId, locale) ?: exercise.canonicalName
                val muscles =
                    exerciseDao.getMuscles(exerciseId).mapNotNull { m ->
                        runCatching { MuscleGroup.valueOf(m.muscleGroupId) }.getOrNull()?.let {
                            MuscleContribution(it, m.contributionPercent)
                        }
                    }
                AppResult.success(
                    ExerciseDetail(
                        id = exercise.id,
                        slug = exercise.canonicalName,
                        displayName = name,
                        kind = ExerciseKind.valueOf(exercise.kind),
                        equipment = runCatching { Equipment.valueOf(exercise.equipment) }.getOrDefault(Equipment.OTHER),
                        muscles = muscles.sortedByDescending { it.percent },
                    ),
                )
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getExerciseDetail"))
            }
        }

    override suspend fun archiveExercise(exerciseId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                exerciseDao.archiveExercise(exerciseId)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("archiveExercise"))
            }
        }

    override suspend fun getRestPref(exerciseId: Long): AppResult<RestPref?> =
        withContext(dispatchers.io) {
            try {
                val pref =
                    workoutDao.getRestPref(exerciseId)?.let {
                        RestPref(
                            it.restSeconds,
                            runCatching { RestMode.valueOf(it.restMode) }.getOrDefault(RestMode.NORMAL),
                        )
                    }
                AppResult.success(pref)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getRestPref"))
            }
        }

    override suspend fun setRestPref(
        exerciseId: Long,
        restSeconds: Int,
        restMode: RestMode,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (restSeconds <= 0) {
                return@withContext AppResult.failure(AppError.Unknown("restSeconds muss positiv sein"))
            }
            try {
                workoutDao.upsertRestPref(
                    ExerciseRestPrefEntity(
                        exerciseId = exerciseId,
                        restSeconds = restSeconds,
                        restMode = restMode.name,
                    ),
                )
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("setRestPref"))
            }
        }

    override suspend fun swapSessionExercise(
        sessionExerciseId: Long,
        newExerciseId: Long,
        strategy: SwapStrategy,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val old =
                        workoutDao.getSessionExercise(sessionExerciseId)
                            ?: throw IllegalStateException("SessionExercise fehlt")
                    val oldExerciseId = old.exerciseId
                    when (strategy) {
                        SwapStrategy.KEEP -> {
                            // Alte Uebung mit Saetzen bleibt; neue wird angehaengt (9).
                            workoutDao.insertSessionExercise(
                                SessionExerciseEntity(
                                    sessionId = old.sessionId,
                                    exerciseId = newExerciseId,
                                    orderIndex = workoutDao.nextExerciseOrderIndex(old.sessionId),
                                    supersetGroupId = old.supersetGroupId,
                                ),
                            )
                        }

                        SwapStrategy.MOVE -> {
                            workoutDao.updateSessionExerciseExerciseId(sessionExerciseId, newExerciseId)
                            recomputeInTransaction(oldExerciseId)
                            recomputeInTransaction(newExerciseId)
                        }

                        SwapStrategy.DISCARD -> {
                            workoutDao.deleteSegmentsForSessionExercise(sessionExerciseId)
                            workoutDao.deleteClustersForSessionExercise(sessionExerciseId)
                            workoutDao.updateSessionExerciseExerciseId(sessionExerciseId, newExerciseId)
                            recomputeInTransaction(oldExerciseId)
                            recomputeInTransaction(newExerciseId)
                        }
                    }
                    AppResult.success(Unit)
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("swapSessionExercise"))
            }
        }

    override suspend fun repeatLastSession(): AppResult<Long> =
        withContext(dispatchers.io) {
            try {
                val last =
                    workoutDao.getLastCompletedSession()
                        ?: return@withContext AppResult.failure(AppError.Unknown("Keine abgeschlossene Session"))
                val exercises = workoutDao.getSessionExercisesRaw(last.id)
                val newSessionId =
                    transactionRunner {
                        val id =
                            workoutDao.insertSession(
                                WorkoutSessionEntity(
                                    startedAtEpochMs = clock.epochMillis(),
                                    endedAtEpochMs = null,
                                    zoneIdAtStart = ZoneId.systemDefault().id,
                                    status = SessionStatus.ACTIVE.name,
                                    title = last.title,
                                    notes = null,
                                ),
                            )
                        for (se in exercises) {
                            workoutDao.insertSessionExercise(
                                SessionExerciseEntity(
                                    sessionId = id,
                                    exerciseId = se.exerciseId,
                                    orderIndex = se.orderIndex,
                                    supersetGroupId = se.supersetGroupId,
                                ),
                            )
                        }
                        id
                    }
                AppResult.success(newSessionId)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("repeatLastSession"))
            }
        }

    override fun observeRoutines(): Flow<List<RoutineInfo>> =
        routineDao.observeActive().map { rows -> rows.map { RoutineInfo(it.id, it.name) } }

    override suspend fun getRoutineDetail(
        routineId: Long,
        locale: String,
    ): AppResult<RoutineDetail> =
        withContext(dispatchers.io) {
            try {
                val routine =
                    routineDao.getRoutine(routineId)
                        ?: return@withContext AppResult.failure(AppError.DatabaseFailure("Routine fehlt"))
                val entries =
                    routineDao.getRoutineExercisesWithNames(routineId, locale).map { row ->
                        RoutineExerciseDetail(
                            exerciseId = row.exerciseId,
                            orderIndex = row.orderIndex,
                            supersetGroupId = row.supersetGroupId,
                            targetSets = row.targetSets,
                            targetRepsMin = row.targetRepsMin,
                            targetRepsMax = row.targetRepsMax,
                            restSeconds = row.restSeconds,
                            displayName = row.displayName ?: row.slug,
                        )
                    }
                AppResult.success(RoutineDetail(routine.id, routine.name, entries))
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getRoutineDetail"))
            }
        }

    override suspend fun createRoutine(
        name: String,
        entries: List<RoutineEntry>,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            if (name.isBlank()) {
                return@withContext AppResult.failure(AppError.Unknown("Routinenname leer"))
            }
            if (!SupersetRules.validateGroups(entries.map { it.exerciseId to it.supersetGroupId })) {
                return@withContext AppResult.failure(AppError.Unknown("Ungueltige Supersetgruppe"))
            }
            try {
                transactionRunner {
                    val now = clock.epochMillis()
                    val routineId =
                        routineDao.insertRoutine(
                            RoutineEntity(
                                name = name.trim(),
                                isArchived = false,
                                createdAtEpochMs = now,
                                updatedAtEpochMs = now,
                            ),
                        )
                    routineDao.insertRoutineExercises(
                        entries.sortedBy { it.orderIndex }.mapIndexed { index, entry ->
                            RoutineExerciseEntity(
                                routineId = routineId,
                                exerciseId = entry.exerciseId,
                                orderIndex = index,
                                supersetGroupId = entry.supersetGroupId,
                                targetSets = entry.targetSets,
                                targetRepsMin = entry.targetRepsMin,
                                targetRepsMax = entry.targetRepsMax,
                                restSeconds = entry.restSeconds,
                            )
                        },
                    )
                    AppResult.success(routineId)
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("createRoutine"))
            }
        }

    override suspend fun createRoutineFromSession(
        sessionId: Long,
        name: String,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            if (name.isBlank()) {
                return@withContext AppResult.failure(AppError.Unknown("Routinenname leer"))
            }
            try {
                val sessionExercises = workoutDao.getSessionExercisesRaw(sessionId)
                if (sessionExercises.isEmpty()) {
                    return@withContext AppResult.failure(AppError.Unknown("Session ohne Uebungen"))
                }
                val entries =
                    sessionExercises.map { se ->
                        val sets = workoutDao.countCompletedWorkingClusters(se.id)
                        val restSeconds = workoutDao.getRestPref(se.exerciseId)?.restSeconds
                        RoutineEntry(
                            exerciseId = se.exerciseId,
                            orderIndex = se.orderIndex,
                            supersetGroupId = se.supersetGroupId,
                            targetSets = if (sets > 0) sets else null,
                            targetRepsMin = null,
                            targetRepsMax = null,
                            restSeconds = restSeconds,
                        )
                    }
                createRoutine(name, entries)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("createRoutineFromSession"))
            }
        }

    override fun observePersonalRecords(exerciseId: Long): Flow<List<PrRecord>> =
        workoutDao.observePersonalRecordsForExercise(exerciseId).map { rows ->
            rows.map { e ->
                PrRecord(
                    type = PrType.valueOf(e.type),
                    achievedSessionId = e.achievedSessionId,
                    achievedClusterId = e.achievedClusterId,
                    valueLong = e.valueLong,
                    valueUnit = PrValueUnit.valueOf(e.valueUnit),
                    comparableLoadMilliKg = e.comparableLoadMilliKg,
                    achievedAtEpochMs = e.achievedAtEpochMs,
                )
            }
        }

    override suspend fun getExerciseProgress(exerciseId: Long): AppResult<List<ExerciseProgressPoint>> =
        withContext(dispatchers.io) {
            try {
                val history =
                    workoutDao.getQualifiedSegments(exerciseId).map {
                        QualifiedSegment(
                            sessionId = it.sessionId,
                            sessionStartedAtEpochMs = it.sessionStartedAtEpochMs,
                            clusterId = it.clusterId,
                            completedAtEpochMs = it.completedAtEpochMs,
                            loadMilliKg = it.loadMilliKg,
                            loadMultiplier = it.loadMultiplier,
                            reps = it.reps,
                        )
                    }
                AppResult.success(ProgressSeriesBuilder.build(history))
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getExerciseProgress"))
            }
        }

    override suspend fun getSessionMusic(sessionId: Long): AppResult<List<PlayedTrackInfo>> =
        withContext(dispatchers.io) {
            try {
                val tracks =
                    workoutDao.getPlayedTracksForSession(sessionId).map { row ->
                        PlayedTrackInfo(
                            songId = row.songId,
                            title = row.title ?: row.displayName,
                            artist = row.artist,
                            positionMs = row.positionMs,
                            capturedAtEpochMs = row.capturedAtEpochMs,
                        )
                    }
                AppResult.success(tracks)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getSessionMusic"))
            }
        }

    /** Eindeutiger Slug per DB-Pruefung; muss in einer Transaktion laufen. */
    private suspend fun uniqueSlug(baseName: String): String {
        val base = Slugs.fromDisplayName(baseName)
        if (exerciseDao.getByCanonicalName(base) == null) return base
        var i = 2
        while (exerciseDao.getByCanonicalName("${base}_$i") != null) i++
        return "${base}_$i"
    }

    /** Optionale Musikverknuepfung (11.1); Fehler werden bewusst geschluckt. */
    private suspend fun capturePlaybackSnapshotBestEffort(sessionExerciseId: Long) {
        runCatching {
            val se = workoutDao.getSessionExercise(sessionExerciseId) ?: return
            val snapshot = playbackRepository.snapshotNow().getOrNull() ?: return
            val songId = snapshot.currentSongId ?: return
            workoutDao.insertPlaybackSnapshot(
                PlaybackSnapshotEntity(
                    sessionId = se.sessionId,
                    songId = songId,
                    markerId = null,
                    positionMs = snapshot.positionMs,
                    capturedAtEpochMs = clock.epochMillis(),
                ),
            )
        }
    }

    /** Muss innerhalb einer laufenden Transaktion aufgerufen werden. */
    private suspend fun recomputeInTransaction(exerciseId: Long) {
        val history =
            workoutDao.getQualifiedSegments(exerciseId).map {
                QualifiedSegment(
                    sessionId = it.sessionId,
                    sessionStartedAtEpochMs = it.sessionStartedAtEpochMs,
                    clusterId = it.clusterId,
                    completedAtEpochMs = it.completedAtEpochMs,
                    loadMilliKg = it.loadMilliKg,
                    loadMultiplier = it.loadMultiplier,
                    reps = it.reps,
                )
            }
        val records = PrCalculator.computeAll(history)
        workoutDao.deletePersonalRecordsForExercise(exerciseId)
        if (records.isNotEmpty()) {
            workoutDao.insertPersonalRecords(
                records.map {
                    PersonalRecordEntity(
                        exerciseId = exerciseId,
                        type = it.type.name,
                        achievedSessionId = it.achievedSessionId,
                        achievedClusterId = it.achievedClusterId,
                        valueLong = it.valueLong,
                        valueUnit = it.valueUnit.name,
                        comparableLoadMilliKg = it.comparableLoadMilliKg,
                        achievedAtEpochMs = it.achievedAtEpochMs,
                    )
                },
            )
        }
    }
}
