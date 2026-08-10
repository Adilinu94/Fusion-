package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Flacher Satz (FlowRep-Design Phase 2): direkt einer Uebung zugeordnet,
 * ohne Session/Cluster/Segment-Kette. Gewicht in Millikilogramm (Long).
 */
@Entity(
    tableName = "flat_sets",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["exercise_id"]),
        Index(value = ["logged_at_epoch_ms"]),
    ],
)
data class FlatSetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "weight_milli_kg")
    val weightMilliKg: Long,
    @ColumnInfo(name = "reps")
    val reps: Int,
    @ColumnInfo(name = "logged_at_epoch_ms")
    val loggedAtEpochMs: Long,
)
