package com.dropsync.data.audio

import com.dropsync.core.database.dao.EqPresetDao
import com.dropsync.core.database.entity.EqPresetEntity
import com.dropsync.domain.audio.BuiltInEqPresets
import com.dropsync.domain.audio.EqPreset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spielt die eingebauten EQ-Presets idempotent ein (Plan Phase 2).
 *
 * - Vorhandene Presets werden nie ueberschrieben (OnConflict IGNORE);
 *   Nutzerpresets bleiben unberuehrt.
 * - Fehlt bei einem eingebauten Preset die Band-Zeile (z. B. abgebrochener
 *   frueherer Seed), werden nur die Baender nachgetragen.
 * - Werte werden in Milli-Einheiten abgelegt, weil Double in Entities
 *   verboten ist (Bauplan 3.2).
 */
@Singleton
class EqPresetSeeder
    @Inject
    constructor(
        private val dao: EqPresetDao,
    ) {
        suspend fun seed() {
            for (preset in BuiltInEqPresets.presets) {
                seedPreset(preset)
            }
        }

        private suspend fun seedPreset(preset: EqPreset) {
            val inserted =
                dao.insertPresetIgnoring(
                    EqPresetEntity(name = preset.name, isBuiltIn = true),
                )
            val presetId =
                if (inserted == -1L) {
                    dao.getIdByName(preset.name) ?: return
                } else {
                    inserted
                }
            // Nur einspielen, wenn noch keine Baender existieren (idempotent
            // und robust gegen abgebrochene Seeds).
            val existing = dao.getPreset(presetId)
            if (existing != null && existing.bands.isNotEmpty()) return
            dao.insertBands(preset.bands.toBandEntities(presetId))
        }
    }
