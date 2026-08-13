package com.dropsync.domain.audio

import com.dropsync.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Nachhall-Einstellungen der DSP-Kette. */
data class ReverbSettings(
    val enabled: Boolean = false,
    val roomSize: Double = 0.5,
    val damping: Double = 0.5,
    val wet: Double = 0.33,
)

/** Resampler-Konfiguration; [targetRateHz] null = Automatik (Quellrate). */
data class ResamplerSettings(
    val targetRateHz: Int? = null,
    val quality: ResamplerQuality = ResamplerQuality.SINC,
) {
    companion object {
        /** Waehlbare Zielraten (Plan Phase 2). */
        val SUPPORTED_RATES: List<Int> =
            listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    }
}

/**
 * Konfiguration der DSP-Kette (ADR-0005). Reihenfolge der Stufen:
 * Preamp -> EQ -> Bass/Hoehen -> Stereo Expansion -> Reverb ->
 * Resampler -> Limiter -> DVC -> Dither. Jede Stufe ist einzeln
 * bypassbar; neutrale Werte kosten praktisch nichts.
 */
data class DspConfig(
    /** Master-Schalter: aus = alle Stufen Bypass (auch fuer Bit-Perfect). */
    val enabled: Boolean = true,
    /** Vorverstaerker in dB, begrenzt auf [PREAMP_MIN_DB]..[PREAMP_MAX_DB]. */
    val preampDb: Double = 0.0,
    /** Soft-Limiter am Kettenende (Clipping-Schutz). */
    val limiterEnabled: Boolean = true,
    val eq: EqSettings = EqSettings(),
    /** Bassregler: Low-Shelf bei 100 Hz, unabhaengig vom EQ. */
    val bassGainDb: Double = 0.0,
    /** Hoehenregler: High-Shelf bei 8 kHz, unabhaengig vom EQ. */
    val trebleGainDb: Double = 0.0,
    /** Stereobreite in Prozent; 100 = neutral. */
    val stereoWidthPercent: Int = StereoMatrix.NEUTRAL_WIDTH_PERCENT,
    val reverb: ReverbSettings = ReverbSettings(),
    val resampler: ResamplerSettings = ResamplerSettings(),
    /** Dither greift nur, wenn das System auf 16 Bit reduziert. */
    val ditherMode: DitherMode = DitherMode.TPDF,
    /** DVC: verlustfreie digitale Lautstaerke am Kettenende. */
    val dvcEnabled: Boolean = false,
    /** DVC-Lautstaerke 0..1 (nur wirksam bei [dvcEnabled]). */
    val dvcVolume: Double = 1.0,
    /** Crossfade-Dauer in Sekunden (Plan Phase 4); 0 = aus. */
    val crossfadeSeconds: Int = 0,
    /** Uebergangs-Preset des Crossfade (Mix-Uebergaenge-Plan Phase 2). */
    val mixPreset: MixPreset = MixPreset.FADE,
    /**
     * Systemeffekte (MusicFX) verwenden: die interne DSP-Kette wird
     * stummgeschaltet, um Doppel-EQ zu vermeiden (Plan Phase 4).
     */
    val useSystemEffects: Boolean = false,
    /**
     * Bit-Perfect (ADR-0009, Android 14+ nur USB): DSP-Bypass und
     * Float-Output aus; exklusiv zur DSP-Kette und zum Crossfade.
     * Wirkt auf den Wiedergabepfad beim naechsten Service-Start.
     */
    val bitPerfectEnabled: Boolean = false,
    /**
     * Ducking der Pausenmusik in dB (Design Phase 7): -12..0, Default -8.
     * Kombiniert mit dem Cue-Ducking ueber `min()` - es duckt nie doppelt,
     * der staerkere Wert gewinnt.
     */
    val restDuckDb: Double = -8.0,
) {
    companion object {
        const val PREAMP_MIN_DB: Double = -12.0
        const val PREAMP_MAX_DB: Double = 12.0
        const val TONE_MIN_DB: Double = -15.0
        const val TONE_MAX_DB: Double = 15.0
        const val REST_DUCK_MIN_DB: Double = -12.0
        const val REST_DUCK_MAX_DB: Double = 0.0

        /** Erzwingt gueltige Wertebereiche (UI und Persistenz teilen sie). */
        fun sanitized(config: DspConfig): DspConfig =
            config.copy(
                preampDb = config.preampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB),
                eq = EqSettings.sanitized(config.eq),
                bassGainDb = config.bassGainDb.coerceIn(TONE_MIN_DB, TONE_MAX_DB),
                trebleGainDb = config.trebleGainDb.coerceIn(TONE_MIN_DB, TONE_MAX_DB),
                stereoWidthPercent =
                    config.stereoWidthPercent.coerceIn(
                        StereoMatrix.MIN_WIDTH_PERCENT,
                        StereoMatrix.MAX_WIDTH_PERCENT,
                    ),
                reverb =
                    config.reverb.copy(
                        roomSize = config.reverb.roomSize.coerceIn(0.0, 1.0),
                        damping = config.reverb.damping.coerceIn(0.0, 1.0),
                        wet = config.reverb.wet.coerceIn(0.0, 1.0),
                    ),
                resampler =
                    config.resampler.copy(
                        targetRateHz =
                            config.resampler.targetRateHz?.takeIf {
                                it in ResamplerSettings.SUPPORTED_RATES
                            },
                    ),
                dvcVolume = config.dvcVolume.coerceIn(0.0, 1.0),
                crossfadeSeconds = config.crossfadeSeconds.coerceIn(0, CrossfadeCurves.MAX_SECONDS),
                restDuckDb = config.restDuckDb.coerceIn(REST_DUCK_MIN_DB, REST_DUCK_MAX_DB),
            )
    }
}

/** Grobklasse des aktiven Ausgabegeraets (fuer Anzeige und Profile). */
enum class OutputDeviceKind {
    SPEAKER,
    WIRED,
    BLUETOOTH_A2DP,
    USB,
    OTHER,
}

/**
 * Detaillierte Audioinformationen der laufenden Wiedergabe (Plan Phase 1).
 * Quelle: Decoder-Eingangsformat; Ausgabe: konfigurierter Audiotrack.
 */
data class AudioInfo(
    /** MIME-Typ des Quellcodecs, z. B. audio/flac. */
    val codecMimeType: String?,
    /** Bitrate der Quelle in Bit/s, falls bekannt. */
    val bitrateBps: Int?,
    val sourceSampleRateHz: Int?,
    val sourceChannelCount: Int?,
    /** Bittiefe der dekodierten Quelle, falls PCM-Kodierung bekannt. */
    val sourceBitDepth: Int?,
    val outputSampleRateHz: Int?,
    /** Klartextname der Ausgabekodierung, z. B. "32-Bit Float". */
    val outputEncoding: String?,
    /** true, wenn der Sink im Hi-Res-Float-Pfad arbeitet. */
    val floatOutput: Boolean,
    /** true, wenn mindestens eine DSP-Stufe aktiv eingreift. */
    val dspActive: Boolean,
    val outputDevice: OutputDeviceKind?,
    /** Anzeigename des Ausgabegeraets, falls verfuegbar. */
    val outputDeviceName: String?,
)

/**
 * App-Zugang zur DSP-Konfiguration und den Audioinformationen; einzige
 * Schnittstelle fuer Features (Modulregel 3.2). Implementierung in
 * `:data:audio`.
 */
interface AudioEngineRepository {
    /** Aktuelle DSP-Konfiguration; Aenderungen wirken sofort. */
    val dspConfig: Flow<DspConfig>

    /** Live-Audioinformationen; null solange nichts spielt. */
    val audioInfo: Flow<AudioInfo?>

    /** Persistiert und aktiviert [config] (bereichsgeprueft). */
    suspend fun updateDspConfig(config: DspConfig)

    /**
     * Alle EQ-Presets (eingebaute zuerst, dann Nutzerpresets nach Namen).
     * Aktualisiert sich nach jedem Speichern/Loeschen automatisch.
     */
    val eqPresets: Flow<List<EqPreset>>

    /**
     * Legt ein Nutzerpreset an oder ueberschreibt ein gleichnamiges
     * Nutzerpreset; eingebaute Namen sind gesperrt. Liefert die ID.
     */
    suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long>

    /** Loescht ein Nutzerpreset; eingebaute Presets sind unloeschbar. */
    suspend fun deleteEqPreset(id: Long): AppResult<Unit>

    /**
     * Uebernimmt die Baender des Presets in die aktive DSP-Konfiguration
     * (aktiviert den grafischen EQ) und persistiert sie.
     */
    suspend fun applyEqPreset(id: Long): AppResult<Unit>

    /**
     * Schluessel des aktiven Ausgabegeraeteprofils (ADR-0008) fuer die
     * Anzeige "Aktives Profil"; null solange kein Profil angelegt ist.
     */
    val activeOutputProfileKey: Flow<String?>

    /**
     * Bit-Perfect-Faehigkeiten des aktuellen Ausgangs (ADR-0009):
     * nur USB-DAC ab Android 14; sonst available = false.
     */
    val bitPerfectSupport: Flow<BitPerfectSupport>
}
