package com.dropsync.domain.audio

/**
 * Ein EQ-Band (Plan Phase 2). Im grafischen Modus sind Frequenz, Q und
 * Typ fest vorgegeben und nur [gainDb] aenderbar; im parametrischen
 * Modus ist alles frei.
 */
data class EqBand(
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = DEFAULT_Q,
    val type: BiquadType = BiquadType.PEAK,
) {
    companion object {
        const val DEFAULT_Q: Double = 1.0
        const val MIN_FREQUENCY_HZ: Double = 20.0
        const val MAX_FREQUENCY_HZ: Double = 20_000.0
        const val MIN_GAIN_DB: Double = -15.0
        const val MAX_GAIN_DB: Double = 15.0
        const val MIN_Q: Double = 0.1
        const val MAX_Q: Double = 12.0

        fun sanitized(band: EqBand): EqBand =
            band.copy(
                frequencyHz = band.frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
                gainDb = band.gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
                q = band.q.coerceIn(MIN_Q, MAX_Q),
            )
    }
}

/** Betriebsart des Equalizers. */
enum class EqMode {
    /** Feste ISO-Frequenzen, nur Gain aenderbar. */
    GRAPHIC,

    /** Frequenz, Q, Gain und Filtertyp je Band frei. */
    PARAMETRIC,
}

/** EQ-Einstellungen; hoechstens [MAX_BANDS] Baender. */
data class EqSettings(
    val enabled: Boolean = false,
    val mode: EqMode = EqMode.GRAPHIC,
    val bands: List<EqBand> = graphicBands(10),
) {
    companion object {
        const val MAX_BANDS: Int = 32

        /** ISO-Mittenfrequenzen fuer 10/15/31-Band-Grafik-EQs. */
        val GRAPHIC_10: List<Double> =
            listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
        val GRAPHIC_15: List<Double> =
            listOf(
                25.0,
                40.0,
                63.0,
                100.0,
                160.0,
                250.0,
                400.0,
                630.0,
                1_000.0,
                1_600.0,
                2_500.0,
                4_000.0,
                6_300.0,
                10_000.0,
                16_000.0,
            )
        val GRAPHIC_31: List<Double> =
            listOf(
                20.0,
                25.0,
                31.5,
                40.0,
                50.0,
                63.0,
                80.0,
                100.0,
                125.0,
                160.0,
                200.0,
                250.0,
                315.0,
                400.0,
                500.0,
                630.0,
                800.0,
                1_000.0,
                1_250.0,
                1_600.0,
                2_000.0,
                2_500.0,
                3_150.0,
                4_000.0,
                5_000.0,
                6_300.0,
                8_000.0,
                10_000.0,
                12_500.0,
                16_000.0,
                20_000.0,
            )

        /** Neutrale Grafik-Baender fuer 10, 15 oder 31 Frequenzen. */
        fun graphicBands(count: Int): List<EqBand> {
            val frequencies =
                when (count) {
                    15 -> GRAPHIC_15
                    31 -> GRAPHIC_31
                    else -> GRAPHIC_10
                }
            // Konstante Gueteklasse benachbarter Terz-/Oktavbaender.
            val q =
                if (count >= 31) {
                    4.32
                } else if (count == 15) {
                    2.14
                } else {
                    1.41
                }
            return frequencies.map { EqBand(frequencyHz = it, q = q) }
        }

        fun sanitized(settings: EqSettings): EqSettings =
            settings.copy(
                bands = settings.bands.take(MAX_BANDS).map(EqBand::sanitized),
            )
    }
}
