package com.dropsync.domain.audio

/**
 * Kompakte String-Kodierung der EQ-Baender fuer die Key-Value-Persistenz
 * (Plan Phase 2): `freq:gain:q:typ` je Band, mit `|` verbunden.
 * Fehlerhafte Eingaben liefern null; der Aufrufer faellt auf die
 * Standardbaender zurueck.
 */
object EqBandsCodec {
    private const val BAND_SEPARATOR = "|"
    private const val FIELD_SEPARATOR = ":"

    fun encode(bands: List<EqBand>): String =
        bands.joinToString(BAND_SEPARATOR) { band ->
            listOf(
                band.frequencyHz.toString(),
                band.gainDb.toString(),
                band.q.toString(),
                band.type.name,
            ).joinToString(FIELD_SEPARATOR)
        }

    fun decode(encoded: String): List<EqBand>? {
        if (encoded.isBlank()) return null
        val bands = ArrayList<EqBand>()
        for (entry in encoded.split(BAND_SEPARATOR)) {
            val fields = entry.split(FIELD_SEPARATOR)
            if (fields.size != 4) return null
            val frequency = fields[0].toDoubleOrNull() ?: return null
            val gain = fields[1].toDoubleOrNull() ?: return null
            val q = fields[2].toDoubleOrNull() ?: return null
            val type =
                try {
                    BiquadType.valueOf(fields[3])
                } catch (e: IllegalArgumentException) {
                    return null
                }
            bands += EqBand.sanitized(EqBand(frequency, gain, q, type))
            if (bands.size > EqSettings.MAX_BANDS) return null
        }
        return bands
    }
}
