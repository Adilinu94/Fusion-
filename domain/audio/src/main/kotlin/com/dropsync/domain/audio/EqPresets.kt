package com.dropsync.domain.audio

/**
 * Ein benanntes EQ-Preset (Plan Phase 2). Eingebaute Presets sind
 * unloeschbar; Nutzerpresets tragen frei gewaehlte, eindeutige Namen.
 * [id] == 0 kennzeichnet ein noch nicht persistiertes Preset.
 */
data class EqPreset(
    val id: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val bands: List<EqBand>,
)

/**
 * Mitgelieferte Grafik-EQ-Presets (Plan Phase 2, Punkt 8). Alle Presets
 * liegen auf den zehn ISO-Frequenzen [EqSettings.GRAPHIC_10]; nur die
 * Gains unterscheiden sich. Der Seeder in `:data:audio` spielt sie
 * idempotent ein, sodass Aenderungen an dieser Tabelle beim naechsten
 * Start ankommen, ohne Nutzerpresets zu beruehren.
 */
object BuiltInEqPresets {
    /** Gueteklasse der Grafik-Baender (identisch zu graphicBands(10)). */
    private const val GRAPHIC_Q: Double = 1.41

    /** Name -> zehn Gains (dB) in der Reihenfolge von GRAPHIC_10. */
    private val GAINS: Map<String, List<Double>> =
        linkedMapOf(
            "Flat" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            "Rock" to listOf(5.0, 3.5, 2.0, 0.0, -1.0, -0.5, 1.0, 2.5, 3.5, 4.5),
            "Pop" to listOf(-1.5, -1.0, 0.0, 2.0, 3.5, 3.5, 2.0, 0.0, -1.0, -1.5),
            "Jazz" to listOf(3.0, 2.0, 1.0, 1.5, -1.0, -1.0, 0.0, 1.0, 2.0, 3.0),
            "Klassik" to listOf(3.5, 2.5, 1.5, 0.0, 0.0, 0.0, -1.0, -1.5, 1.5, 2.5),
            "Bass Boost" to listOf(6.5, 5.5, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            "Höhen Boost" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 4.0, 5.0, 6.0),
            "Vocal" to listOf(-2.0, -1.5, -0.5, 1.5, 3.0, 3.5, 3.0, 1.5, 0.0, -1.0),
            "Elektronik" to listOf(5.0, 4.0, 1.0, 0.0, -1.5, 1.0, 0.0, 1.5, 3.5, 5.0),
            "Loudness" to listOf(6.0, 4.0, 1.5, 0.0, -1.5, 0.0, 1.5, 2.5, 4.0, 6.0),
        )

    /** Alle eingebauten Presets als Domainobjekte (id = 0, unpersistiert). */
    val presets: List<EqPreset> by lazy {
        GAINS.map { (name, gains) ->
            require(gains.size == EqSettings.GRAPHIC_10.size) {
                "Preset $name braucht ${EqSettings.GRAPHIC_10.size} Gains"
            }
            EqPreset(
                id = 0,
                name = name,
                isBuiltIn = true,
                bands =
                    EqSettings.GRAPHIC_10.mapIndexed { index, frequency ->
                        EqBand(
                            frequencyHz = frequency,
                            gainDb = gains[index],
                            q = GRAPHIC_Q,
                            type = BiquadType.PEAK,
                        )
                    },
            )
        }
    }

    /** Version des Preset-Katalogs; erhoeht sich bei inhaltlichen Aenderungen. */
    const val CATALOG_VERSION: Int = 1
}
