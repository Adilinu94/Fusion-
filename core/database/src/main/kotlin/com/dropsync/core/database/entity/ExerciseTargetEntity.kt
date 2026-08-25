package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Trainingsziel je Uebung (Flowtimer-Integration Entscheidung 14, E4).
 *
 * `exerciseId` ist Primary Key, nicht bloss Unique-Index (CONTEXT Punkt 3):
 * Genau ein Ziel je Uebung. Ein eigener Autoincrement-Schluessel mit
 * Unique-Index koennte dasselbe garantieren, waere aber eine zweite
 * Identitaet fuer dieselbe Sache — und `ExerciseRestPrefEntity` loest die
 * gleiche 1:1-Beziehung bereits so.
 *
 * Gewicht als ganze Millikilogramm (Long); Double ist in Entities
 * verboten (Schritt 3.2). Die Umrechnung nach Kilogramm passiert erst an
 * der Kern-Grenze in `:data:workout`.
 *
 * CASCADE: Wird eine Uebung geloescht, verschwindet ihr Ziel mit. Beim
 * Archivieren (`is_archived`) bleibt es erhalten — eine wiederhergestellte
 * Uebung soll ihr Ziel behalten.
 */
@Entity(
    tableName = "exercise_targets",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ExerciseTargetEntity(
    @PrimaryKey
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "target_weight_milli_kg")
    val targetWeightMilliKg: Long,
    @ColumnInfo(name = "target_reps")
    val targetReps: Int,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)
