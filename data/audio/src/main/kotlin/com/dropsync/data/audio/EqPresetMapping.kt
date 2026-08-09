package com.dropsync.data.audio

import com.dropsync.core.database.dao.EqPresetWithBands
import com.dropsync.core.database.entity.EqPresetBandEntity
import com.dropsync.domain.audio.BiquadType
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Umrechnung zwischen Domain-Baendern und Persistenz (Plan Phase 2).
 * Werte liegen in der DB als Milli-Einheiten (Long/Int), weil Double in
 * Entities verboten ist (Bauplan 3.2): 1000 = 1 Hz/dB/Q.
 */
private const val MILLI: Double = 1_000.0

/** Domain-Baender -> Persistenz-Zeilen in stabiler Reihenfolge. */
internal fun List<EqBand>.toBandEntities(presetId: Long): List<EqPresetBandEntity> =
    mapIndexed { index, band ->
        EqPresetBandEntity(
            presetId = presetId,
            orderIndex = index,
            frequencyMilliHz = (band.frequencyHz * MILLI).roundToLong(),
            gainMilliDb = (band.gainDb * MILLI).roundToInt(),
            qMilli = (band.q * MILLI).roundToInt(),
            type = band.type.name,
        )
    }

/** Persistiertes Preset -> Domainobjekt (Baender nach order_index sortiert). */
internal fun EqPresetWithBands.toDomain(): EqPreset =
    EqPreset(
        id = preset.id,
        name = preset.name,
        isBuiltIn = preset.isBuiltIn,
        bands =
            bands
                .sortedBy { it.orderIndex }
                .map { entity ->
                    EqBand(
                        frequencyHz = entity.frequencyMilliHz / MILLI,
                        gainDb = entity.gainMilliDb / MILLI,
                        q = entity.qMilli / MILLI,
                        type = runCatching { BiquadType.valueOf(entity.type) }.getOrDefault(BiquadType.PEAK),
                    )
                },
    )
