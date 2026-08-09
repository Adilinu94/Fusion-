package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Trainingssession (Abschnitt 6); Zeitzone fuer Kalenderansichten. */
@Entity(
    tableName = "workout_sessions",
    indices = [Index(value = ["started_at_epoch_ms"])],
)
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "started_at_epoch_ms")
    val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_epoch_ms")
    val endedAtEpochMs: Long?,
    @ColumnInfo(name = "zone_id_at_start")
    val zoneIdAtStart: String,
    /** Stabiler String aus SessionStatus (ACTIVE, COMPLETED, DISCARDED). */
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "notes")
    val notes: String?,
)

/** Uebung; canonicalName ist ein sprachunabhaengiger Slug (Schritt 9.1). */
@Entity(
    tableName = "exercises",
    indices = [Index(value = ["canonical_name"], unique = true)],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "canonical_name")
    val canonicalName: String,
    /** Stabiler String aus ExerciseKind (STRENGTH, TIME, DISTANCE). */
    @ColumnInfo(name = "kind")
    val kind: String,
    /** Stabiler String aus Equipment (Schritt 9.2). */
    @ColumnInfo(name = "equipment")
    val equipment: String,
    @ColumnInfo(name = "is_custom")
    val isCustom: Boolean,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
)

/** Lokalisierte Anzeigenamen pro Uebung (Abschnitt 6, Schritt 9.1). */
@Entity(
    tableName = "exercise_names",
    primaryKeys = ["exercise_id", "locale"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["exercise_id"])],
)
data class ExerciseNameEntity(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    /** BCP-47-Sprachcode, in Version 1 "de" oder "en". */
    @ColumnInfo(name = "locale")
    val locale: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
)

/** Lookup-Tabelle stabiler Muskelgruppen-Schluessel (Schritt 3.1). */
@Entity(tableName = "muscle_groups")
data class MuscleGroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
)

/** Lookup-Tabelle stabiler SetRole-Schluessel (Schritt 3.1). */
@Entity(tableName = "set_roles")
data class SetRoleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
)

/** Muskelbeteiligung einer Uebung in Prozent (Abschnitt 6). */
@Entity(
    tableName = "exercise_muscles",
    primaryKeys = ["exercise_id", "muscle_group_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MuscleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["muscle_group_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["exercise_id"]),
        Index(value = ["muscle_group_id"]),
    ],
)
data class ExerciseMuscleEntity(
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "muscle_group_id")
    val muscleGroupId: String,
    /** 1..100; Validierung im Repository. */
    @ColumnInfo(name = "contribution_percent")
    val contributionPercent: Int,
)

/** Routine: nur Reihenfolge und Zielwerte, keine Historie (Schritt 9.7). */
@Entity(
    tableName = "routines",
    indices = [Index(value = ["name"], unique = true)],
)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

/** Uebung innerhalb einer Routine; Superset-Gruppe liegt hier (Schritt 9.6). */
@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(
            entity = RoutineEntity::class,
            parentColumns = ["id"],
            childColumns = ["routine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["routine_id"]),
        Index(value = ["exercise_id"]),
    ],
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "routine_id")
    val routineId: Long,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "superset_group_id")
    val supersetGroupId: Long?,
    @ColumnInfo(name = "target_sets")
    val targetSets: Int?,
    @ColumnInfo(name = "target_reps_min")
    val targetRepsMin: Int?,
    @ColumnInfo(name = "target_reps_max")
    val targetRepsMax: Int?,
    @ColumnInfo(name = "rest_seconds")
    val restSeconds: Int?,
)

/** Uebung innerhalb einer Session; Superset-Gruppe liegt hier (Schritt 9.6). */
@Entity(
    tableName = "session_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["exercise_id"]),
    ],
)
data class SessionExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "superset_group_id")
    val supersetGroupId: Long?,
)

/**
 * Satzcluster: fachliche Einheit, zaehlt als genau ein Arbeitsset
 * (Begriffsdefinition 1.1); ein Drop-Set hat mehrere Segmente.
 */
@Entity(
    tableName = "set_clusters",
    foreignKeys = [
        ForeignKey(
            entity = SessionExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SetRoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["set_role"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["session_exercise_id", "order_index"]),
        Index(value = ["set_role"]),
    ],
)
data class SetClusterEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "session_exercise_id")
    val sessionExerciseId: Long,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    /** Stabiler String aus SetRole (WARMUP, WORKING, FAILURE, TIME, DISTANCE). */
    @ColumnInfo(name = "set_role")
    val setRole: String,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "completed_at_epoch_ms")
    val completedAtEpochMs: Long?,
)

/**
 * Satzsegment; Gewichte als ganze Millikilogramm (Long), nie Double
 * (Abschnitt 6, Schritt 3.2).
 */
@Entity(
    tableName = "set_segments",
    foreignKeys = [
        ForeignKey(
            entity = SetClusterEntity::class,
            parentColumns = ["id"],
            childColumns = ["cluster_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cluster_id", "segment_index"])],
)
data class SetSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "cluster_id")
    val clusterId: Long,
    @ColumnInfo(name = "segment_index")
    val segmentIndex: Int,
    @ColumnInfo(name = "external_load_milli_kg_per_implement")
    val externalLoadMilliKgPerImplement: Long?,
    /** Nur 1 oder 2 (Abschnitt 5.4); Validierung in :domain:workout. */
    @ColumnInfo(name = "load_multiplier")
    val loadMultiplier: Int,
    @ColumnInfo(name = "reps")
    val reps: Int?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    @ColumnInfo(name = "distance_m")
    val distanceM: Long?,
)

/** Persoenlicher Rekord gemaess exakter Regeln in Abschnitt 5.4. */
@Entity(
    tableName = "personal_records",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["achieved_session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["exercise_id"]),
        Index(value = ["achieved_session_id"]),
    ],
)
data class PersonalRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    /** Stabiler String aus PrType. */
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "achieved_session_id")
    val achievedSessionId: Long,
    @ColumnInfo(name = "achieved_cluster_id")
    val achievedClusterId: Long?,
    @ColumnInfo(name = "value_long")
    val valueLong: Long,
    /** Stabiler String aus PrValueUnit (MILLI_KG, REPS). */
    @ColumnInfo(name = "value_unit")
    val valueUnit: String,
    @ColumnInfo(name = "comparable_load_milli_kg")
    val comparableLoadMilliKg: Long?,
    @ColumnInfo(name = "achieved_at_epoch_ms")
    val achievedAtEpochMs: Long,
)

/**
 * Append-only-Historie der optionalen Musikverknuepfung einer Session
 * (Abschnitt 6, Schritt 11.1).
 */
@Entity(
    tableName = "playback_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["media_store_id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongMarkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["marker_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["song_id"]),
        Index(value = ["marker_id"]),
    ],
)
data class PlaybackSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "marker_id")
    val markerId: Long?,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "captured_at_epoch_ms")
    val capturedAtEpochMs: Long,
)

/**
 * Pro Uebung gemerkter Resttimer (Abschnitt 8): individuelle Dauer und
 * Rest-Modus (normaler Resttimer oder DropSync). Kein globaler Standardwert;
 * geloescht per CASCADE, wenn die Uebung entfernt wird.
 */
@Entity(
    tableName = "exercise_rest_prefs",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExerciseRestPrefEntity(
    @PrimaryKey
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "rest_seconds")
    val restSeconds: Int,
    /** Stabiler String aus RestMode (NORMAL, DROPSYNC). */
    @ColumnInfo(name = "rest_mode")
    val restMode: String,
)
