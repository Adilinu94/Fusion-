package com.dropsync.domain.audio

/**
 * Schluessel eines Pro-Ausgang-Profils (ADR-0008): Geraetetyp plus
 * Adresse bzw. Produktname, damit z. B. zwei BT-Kopfhoerer getrennte
 * Profile erhalten. Der [storageKey] ist stabil und DataStore-sicher.
 */
data class OutputProfileKey(
    val kind: OutputDeviceKind,
    val address: String?,
) {
    fun storageKey(): String {
        val suffix =
            address
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("[^a-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?: "default"
        return "${kind.name}:$suffix"
    }
}

/**
 * Bit-Perfect-Faehigkeiten des aktiven Ausgangs (ADR-0009). Nur ein
 * USB-DAC ab Android 14 kann Bit-Perfect; ueber Bluetooth ist es
 * prinzipiell unmoeglich (A2DP ist immer lossy-codiert).
 */
data class BitPerfectSupport(
    val available: Boolean,
    val deviceName: String?,
    /** Vom DAC unterstuetzte Abtastraten in Hz (leer wenn unbekannt). */
    val sampleRatesHz: List<Int> = emptyList(),
    /** Vom DAC unterstuetzte Kodierungen als Klartext (leer wenn unbekannt). */
    val encodings: List<String> = emptyList(),
) {
    companion object {
        val UNAVAILABLE = BitPerfectSupport(available = false, deviceName = null)
    }
}

/**
 * Vollstaendige String-Kodierung einer [DspConfig] fuer die
 * Geraeteprofil-Persistenz (ADR-0008): `schluessel=wert` je Feld, mit
 * `;` verbunden; EQ-Baender via [EqBandsCodec]. Unbekannte Schluessel
 * werden ignoriert (vorwaertskompatibel), defekte Werte liefern null.
 */
object DspConfigCodec {
    private const val ENTRY_SEPARATOR = ";"
    private const val KV_SEPARATOR = "="

    fun encode(config: DspConfig): String {
        val sanitized = DspConfig.sanitized(config)
        return listOf(
            "enabled" to sanitized.enabled.toString(),
            "preampDb" to sanitized.preampDb.toString(),
            "limiter" to sanitized.limiterEnabled.toString(),
            "eqEnabled" to sanitized.eq.enabled.toString(),
            "eqMode" to sanitized.eq.mode.name,
            "eqBands" to EqBandsCodec.encode(sanitized.eq.bands),
            "bassDb" to sanitized.bassGainDb.toString(),
            "trebleDb" to sanitized.trebleGainDb.toString(),
            "stereoWidth" to sanitized.stereoWidthPercent.toString(),
            "reverbEnabled" to sanitized.reverb.enabled.toString(),
            "reverbRoom" to sanitized.reverb.roomSize.toString(),
            "reverbDamping" to sanitized.reverb.damping.toString(),
            "reverbWet" to sanitized.reverb.wet.toString(),
            "resamplerRate" to (sanitized.resampler.targetRateHz ?: 0).toString(),
            "resamplerQuality" to sanitized.resampler.quality.name,
            "dither" to sanitized.ditherMode.name,
            "dvcEnabled" to sanitized.dvcEnabled.toString(),
            "dvcVolume" to sanitized.dvcVolume.toString(),
            "crossfadeSeconds" to sanitized.crossfadeSeconds.toString(),
            "mixPreset" to sanitized.mixPreset.name,
            "useSystemEffects" to sanitized.useSystemEffects.toString(),
            "bitPerfect" to sanitized.bitPerfectEnabled.toString(),
        ).joinToString(ENTRY_SEPARATOR) { (key, value) -> "$key$KV_SEPARATOR$value" }
    }

    fun decode(encoded: String): DspConfig? {
        if (encoded.isBlank()) return null
        val values = HashMap<String, String>()
        for (entry in encoded.split(ENTRY_SEPARATOR)) {
            val index = entry.indexOf(KV_SEPARATOR)
            if (index <= 0) return null
            values[entry.substring(0, index)] = entry.substring(index + 1)
        }
        val defaults = DspConfig()
        val bands =
            values["eqBands"]?.let(EqBandsCodec::decode)
                ?: defaults.eq.bands
        return DspConfig.sanitized(
            DspConfig(
                enabled = values.bool("enabled", defaults.enabled) ?: return null,
                preampDb = values.double("preampDb", defaults.preampDb) ?: return null,
                limiterEnabled = values.bool("limiter", defaults.limiterEnabled) ?: return null,
                eq =
                    EqSettings(
                        enabled = values.bool("eqEnabled", defaults.eq.enabled) ?: return null,
                        mode = values.enum("eqMode", defaults.eq.mode) ?: return null,
                        bands = bands,
                    ),
                bassGainDb = values.double("bassDb", defaults.bassGainDb) ?: return null,
                trebleGainDb = values.double("trebleDb", defaults.trebleGainDb) ?: return null,
                stereoWidthPercent =
                    values.int("stereoWidth", defaults.stereoWidthPercent) ?: return null,
                reverb =
                    ReverbSettings(
                        enabled = values.bool("reverbEnabled", defaults.reverb.enabled) ?: return null,
                        roomSize = values.double("reverbRoom", defaults.reverb.roomSize) ?: return null,
                        damping = values.double("reverbDamping", defaults.reverb.damping) ?: return null,
                        wet = values.double("reverbWet", defaults.reverb.wet) ?: return null,
                    ),
                resampler =
                    ResamplerSettings(
                        targetRateHz =
                            (values.int("resamplerRate", 0) ?: return null).takeIf { it > 0 },
                        quality = values.enum("resamplerQuality", defaults.resampler.quality) ?: return null,
                    ),
                ditherMode = values.enum("dither", defaults.ditherMode) ?: return null,
                dvcEnabled = values.bool("dvcEnabled", defaults.dvcEnabled) ?: return null,
                dvcVolume = values.double("dvcVolume", defaults.dvcVolume) ?: return null,
                crossfadeSeconds =
                    values.int("crossfadeSeconds", defaults.crossfadeSeconds) ?: return null,
                mixPreset = values.enum("mixPreset", defaults.mixPreset) ?: return null,
                useSystemEffects =
                    values.bool("useSystemEffects", defaults.useSystemEffects) ?: return null,
                bitPerfectEnabled =
                    values.bool("bitPerfect", defaults.bitPerfectEnabled) ?: return null,
            ),
        )
    }

    /** Fehlender Schluessel: Standardwert; defekter Wert: null. */
    private fun Map<String, String>.bool(
        key: String,
        default: Boolean,
    ): Boolean? =
        when (val raw = this[key]) {
            null -> default
            "true" -> true
            "false" -> false
            else -> null
        }

    private fun Map<String, String>.double(
        key: String,
        default: Double,
    ): Double? {
        val raw = this[key] ?: return default
        return raw.toDoubleOrNull()
    }

    private fun Map<String, String>.int(
        key: String,
        default: Int,
    ): Int? {
        val raw = this[key] ?: return default
        return raw.toIntOrNull()
    }

    private inline fun <reified T : Enum<T>> Map<String, String>.enum(
        key: String,
        default: T,
    ): T? =
        when (val raw = this[key]) {
            null -> {
                default
            }

            else -> {
                try {
                    enumValueOf<T>(raw)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }
}
