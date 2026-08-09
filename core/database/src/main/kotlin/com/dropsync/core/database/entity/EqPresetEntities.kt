package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EQ-Preset (Plan Phase 2). Eingebaute Presets sind unloeschbar;
 * Nutzerpresets tragen frei gewaehlte Namen (eindeutig).
 */
@Entity(
    tableName = "eq_presets",
    indices = [Index(value = ["name"], unique = true)],
)
data class EqPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
)

/**
 * Ein Band eines Presets. Werte in Milli-Einheiten (Long/Int), weil
 * Double in Entities verboten ist (Schritt 3.2): 1000 = 1 Hz/dB/Q.
 */
@Entity(
    tableName = "eq_preset_bands",
    foreignKeys = [
        ForeignKey(
            entity = EqPresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["preset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["preset_id", "order_index"])],
)
data class EqPresetBandEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "preset_id")
    val presetId: Long,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "frequency_milli_hz")
    val frequencyMilliHz: Long,
    @ColumnInfo(name = "gain_milli_db")
    val gainMilliDb: Int,
    @ColumnInfo(name = "q_milli")
    val qMilli: Int,
    /** Stabiler String aus BiquadType. */
    @ColumnInfo(name = "type")
    val type: String,
)
