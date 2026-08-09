package com.dropsync.data.audio

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.audio.DitherMode
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBandsCodec
import com.dropsync.domain.audio.EqMode
import com.dropsync.domain.audio.EqSettings
import com.dropsync.domain.audio.MixPreset
import com.dropsync.domain.audio.ResamplerQuality
import com.dropsync.domain.audio.ResamplerSettings
import com.dropsync.domain.audio.ReverbSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dspDataStore by preferencesDataStore(name = "audio_dsp")

/**
 * Persistenz der DSP-Konfiguration (Plan Phase 2). EQ-Baender liegen als
 * kompakter String (EqBandsCodec); defekte Werte fallen auf Standards
 * zurueck. Ab Phase 5 erhalten die Schluessel ein Geraeteprofil-Praefix
 * (ADR-0008).
 */
@Singleton
class DspSettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val enabledKey = booleanPreferencesKey("dsp_enabled")
        private val preampDbKey = doublePreferencesKey("preamp_db")
        private val limiterKey = booleanPreferencesKey("limiter_enabled")
        private val eqEnabledKey = booleanPreferencesKey("eq_enabled")
        private val eqModeKey = stringPreferencesKey("eq_mode")
        private val eqBandsKey = stringPreferencesKey("eq_bands")
        private val bassDbKey = doublePreferencesKey("bass_db")
        private val trebleDbKey = doublePreferencesKey("treble_db")
        private val stereoWidthKey = intPreferencesKey("stereo_width_percent")
        private val reverbEnabledKey = booleanPreferencesKey("reverb_enabled")
        private val reverbRoomKey = doublePreferencesKey("reverb_room")
        private val reverbDampingKey = doublePreferencesKey("reverb_damping")
        private val reverbWetKey = doublePreferencesKey("reverb_wet")
        private val resamplerRateKey = intPreferencesKey("resampler_rate_hz")
        private val resamplerQualityKey = stringPreferencesKey("resampler_quality")
        private val ditherKey = stringPreferencesKey("dither_mode")
        private val dvcEnabledKey = booleanPreferencesKey("dvc_enabled")
        private val dvcVolumeKey = doublePreferencesKey("dvc_volume")
        private val crossfadeSecondsKey = intPreferencesKey("crossfade_seconds")
        private val mixPresetKey = stringPreferencesKey("mix_preset")
        private val useSystemEffectsKey = booleanPreferencesKey("use_system_effects")
        private val bitPerfectKey = booleanPreferencesKey("bit_perfect_enabled")

        val config: Flow<DspConfig> = context.dspDataStore.data.map(::readConfig)

        suspend fun save(config: DspConfig) {
            val sanitized = DspConfig.sanitized(config)
            context.dspDataStore.edit { prefs ->
                prefs[enabledKey] = sanitized.enabled
                prefs[preampDbKey] = sanitized.preampDb
                prefs[limiterKey] = sanitized.limiterEnabled
                prefs[eqEnabledKey] = sanitized.eq.enabled
                prefs[eqModeKey] = sanitized.eq.mode.name
                prefs[eqBandsKey] = EqBandsCodec.encode(sanitized.eq.bands)
                prefs[bassDbKey] = sanitized.bassGainDb
                prefs[trebleDbKey] = sanitized.trebleGainDb
                prefs[stereoWidthKey] = sanitized.stereoWidthPercent
                prefs[reverbEnabledKey] = sanitized.reverb.enabled
                prefs[reverbRoomKey] = sanitized.reverb.roomSize
                prefs[reverbDampingKey] = sanitized.reverb.damping
                prefs[reverbWetKey] = sanitized.reverb.wet
                prefs[resamplerRateKey] = sanitized.resampler.targetRateHz ?: 0
                prefs[resamplerQualityKey] = sanitized.resampler.quality.name
                prefs[ditherKey] = sanitized.ditherMode.name
                prefs[dvcEnabledKey] = sanitized.dvcEnabled
                prefs[dvcVolumeKey] = sanitized.dvcVolume
                prefs[crossfadeSecondsKey] = sanitized.crossfadeSeconds
                prefs[mixPresetKey] = sanitized.mixPreset.name
                prefs[useSystemEffectsKey] = sanitized.useSystemEffects
                prefs[bitPerfectKey] = sanitized.bitPerfectEnabled
            }
        }

        private fun readConfig(prefs: Preferences): DspConfig =
            DspConfig.sanitized(
                DspConfig(
                    enabled = prefs[enabledKey] ?: true,
                    preampDb = prefs[preampDbKey] ?: 0.0,
                    limiterEnabled = prefs[limiterKey] ?: true,
                    eq =
                        EqSettings(
                            enabled = prefs[eqEnabledKey] ?: false,
                            mode = enumOr(prefs[eqModeKey], EqMode.GRAPHIC),
                            bands =
                                prefs[eqBandsKey]?.let(EqBandsCodec::decode)
                                    ?: EqSettings.graphicBands(10),
                        ),
                    bassGainDb = prefs[bassDbKey] ?: 0.0,
                    trebleGainDb = prefs[trebleDbKey] ?: 0.0,
                    stereoWidthPercent = prefs[stereoWidthKey] ?: 100,
                    reverb =
                        ReverbSettings(
                            enabled = prefs[reverbEnabledKey] ?: false,
                            roomSize = prefs[reverbRoomKey] ?: 0.5,
                            damping = prefs[reverbDampingKey] ?: 0.5,
                            wet = prefs[reverbWetKey] ?: 0.33,
                        ),
                    resampler =
                        ResamplerSettings(
                            targetRateHz = prefs[resamplerRateKey]?.takeIf { it > 0 },
                            quality = enumOr(prefs[resamplerQualityKey], ResamplerQuality.SINC),
                        ),
                    ditherMode = enumOr(prefs[ditherKey], DitherMode.TPDF),
                    dvcEnabled = prefs[dvcEnabledKey] ?: false,
                    dvcVolume = prefs[dvcVolumeKey] ?: 1.0,
                    crossfadeSeconds = prefs[crossfadeSecondsKey] ?: 0,
                    mixPreset = enumOr(prefs[mixPresetKey], MixPreset.FADE),
                    useSystemEffects = prefs[useSystemEffectsKey] ?: false,
                    bitPerfectEnabled = prefs[bitPerfectKey] ?: false,
                ),
            )

        private inline fun <reified T : Enum<T>> enumOr(
            raw: String?,
            fallback: T,
        ): T =
            raw?.let {
                try {
                    enumValueOf<T>(it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } ?: fallback
    }
