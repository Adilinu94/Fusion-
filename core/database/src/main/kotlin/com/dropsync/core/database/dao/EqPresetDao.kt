package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.dropsync.core.database.entity.EqPresetBandEntity
import com.dropsync.core.database.entity.EqPresetEntity
import kotlinx.coroutines.flow.Flow

/** Preset inklusive Baender in stabiler Reihenfolge. */
data class EqPresetWithBands(
    @Embedded val preset: EqPresetEntity,
    @Relation(parentColumn = "id", entityColumn = "preset_id")
    val bands: List<EqPresetBandEntity>,
)

@Dao
interface EqPresetDao {
    @Transaction
    @Query("SELECT * FROM eq_presets ORDER BY is_built_in DESC, name COLLATE NOCASE")
    fun observePresets(): Flow<List<EqPresetWithBands>>

    @Transaction
    @Query("SELECT * FROM eq_presets WHERE id = :id")
    suspend fun getPreset(id: Long): EqPresetWithBands?

    /** Seed: existierende Namen bleiben unangetastet (idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPresetIgnoring(preset: EqPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPreset(preset: EqPresetEntity): Long

    @Insert
    suspend fun insertBands(bands: List<EqPresetBandEntity>)

    @Query("SELECT id FROM eq_presets WHERE name = :name")
    suspend fun getIdByName(name: String): Long?

    /** Nutzerpresets ueberschreiben: alte Baender entfernen. */
    @Query("DELETE FROM eq_preset_bands WHERE preset_id = :presetId")
    suspend fun deleteBands(presetId: Long)

    /** Eingebaute Presets sind unloeschbar. */
    @Query("DELETE FROM eq_presets WHERE id = :presetId AND is_built_in = 0")
    suspend fun deleteUserPreset(presetId: Long): Int
}
