package com.dropsync.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.data.audio.OutputDeviceMonitor
import com.dropsync.data.audio.OutputDeviceSnapshot
import com.dropsync.domain.audio.OutputDeviceKind
import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.RouteProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.routeProfileDataStore by preferencesDataStore(name = "route_profiles")

/**
 * Latenzprofil-Store je Audio-Route (Design Phase 6, Abschnitt 10).
 *
 * Ohne Loopback-Messung entsteht das Profil aus Latenz-Tabellen je
 * Geraeteklasse und wird bei lokaler Messung verfeinert
 * ([AudioRouteProfile.Confidence.CALIBRATED]). Beim Geraetewechsel wird
 * das Profil als STALE markiert; der Coordinator rechnet dann
 * BEST_EFFORT weiter (kein ms-Versprechen in der UI).
 */
@Singleton
class RouteProfileStore
    @Inject
    constructor(
        private val context: Context,
        private val deviceMonitor: OutputDeviceMonitor,
    ) : RouteProfileRepository {
        private val profileStore = context.routeProfileDataStore

        override val currentProfile: Flow<AudioRouteProfile?> =
            combine(deviceMonitor.device, profileStore.data) { device, prefs ->
                val key = routeKey(device) ?: return@combine null
                storedProfile(key, prefs)
                    ?.takeIf { !isStale(key, it.calibratedAt, prefs) }
                    ?: tableProfile(device, key)?.copy(confidence = AudioRouteProfile.Confidence.ESTIMATED)
            }

        override suspend fun currentLatencyMs(): Long? {
            val device = deviceMonitor.device.value
            val key = routeKey(device) ?: return null
            val prefs = profileStore.data.first()
            val stored = storedProfile(key, prefs)
            if (stored != null && !isStale(key, stored.calibratedAt, prefs)) {
                return stored.estimatedLatencyMs
            }
            return tableProfile(device, key)?.estimatedLatencyMs
        }

        override suspend fun markStale() {
            val device = deviceMonitor.device.value
            val key = routeKey(device) ?: return
            profileStore.edit { prefs ->
                prefs[staleKey(key)] = System.currentTimeMillis()
            }
        }

        override suspend fun upsert(profile: AudioRouteProfile) {
            val key = profile.routeKey
            profileStore.edit { prefs ->
                prefs[latencyKey(key)] = profile.estimatedLatencyMs
                prefs[p50Key(key)] = profile.p50ErrorMs ?: -1L
                prefs[p95Key(key)] = profile.p95ErrorMs ?: -1L
                prefs[calibratedAtKey(key)] = profile.calibratedAt ?: System.currentTimeMillis()
                prefs[confidenceKey(key)] = profile.confidence.name
                prefs.remove(staleKey(key))
            }
        }

        /** Gespeichertes Profil; null wenn nie gemessen. */
        private fun storedProfile(
            key: String,
            prefs: androidx.datastore.preferences.core.Preferences,
        ): AudioRouteProfile? {
            val latency = prefs[latencyKey(key)] ?: return null
            val p50 = prefs[p50Key(key)]?.takeIf { it >= 0 }
            val p95 = prefs[p95Key(key)]?.takeIf { it >= 0 }
            val calibratedAt = prefs[calibratedAtKey(key)]
            val confidence =
                prefs[confidenceKey(key)]
                    ?.let { runCatching { AudioRouteProfile.Confidence.valueOf(it) }.getOrNull() }
                    ?: AudioRouteProfile.Confidence.CALIBRATED
            return AudioRouteProfile(
                routeKey = key,
                sampleRate = 48_000,
                channels = 2,
                estimatedLatencyMs = latency,
                p50ErrorMs = p50,
                p95ErrorMs = p95,
                calibratedAt = calibratedAt,
                confidence = confidence,
            )
        }

        private fun isStale(
            key: String,
            calibratedAt: Long?,
            prefs: androidx.datastore.preferences.core.Preferences,
        ): Boolean {
            val staleAt = prefs[staleKey(key)] ?: return false
            return calibratedAt == null || staleAt > calibratedAt
        }

        /** Tabellenwerte je Geraeteklasse (Design Abschnitt 10). */
        private fun tableProfile(
            device: OutputDeviceSnapshot,
            key: String,
        ): AudioRouteProfile? =
            when (device.kind) {
                OutputDeviceKind.SPEAKER -> {
                    AudioRouteProfile(key, 48_000, 2, LATENCY_SPEAKER_MS)
                }

                OutputDeviceKind.WIRED -> {
                    AudioRouteProfile(key, 48_000, 2, LATENCY_WIRED_MS)
                }

                OutputDeviceKind.BLUETOOTH_A2DP -> {
                    // Codec-spezifische Latenz (Poweramp-Muster
                    // PaBluetoothCodecConfig): LDAC/AAC liegen deutlich
                    // ueber/unter SBC; pauschal SBC waere falsche
                    // Praezision. Unbekannter Codec = SBC (A2DP-Fallback).
                    val latency =
                        when (device.bluetoothCodec) {
                            "AAC" -> LATENCY_BT_AAC_MS
                            "LDAC" -> LATENCY_BT_LDAC_MS
                            else -> LATENCY_BT_SBC_MS
                        }
                    AudioRouteProfile(key, 48_000, 2, latency)
                }

                OutputDeviceKind.USB -> {
                    AudioRouteProfile(key, 48_000, 2, LATENCY_USB_MS)
                }

                OutputDeviceKind.OTHER -> {
                    null
                }
            }

        private fun routeKey(device: OutputDeviceSnapshot): String? {
            if (device.kind == OutputDeviceKind.OTHER) return null
            val suffix = device.address?.takeIf { it.isNotBlank() } ?: device.kind.name
            // Codec als Teil des Profilschluessels (Phase 8-Ergaenzung):
            // derselbe Kopfhoerer darf fuer SBC und LDAC getrennte
            // Messwerte halten; die Tabelle greift nur ohne Messung.
            val codec = device.bluetoothCodec?.takeIf { it.isNotBlank() }
            return buildString {
                append(device.kind.name)
                append(':')
                append(suffix)
                if (codec != null) {
                    append('#')
                    append(codec)
                }
            }
        }

        companion object {
            /** Groessenordnungen je Route (Design Abschnitt 10, konservativ). */
            const val LATENCY_SPEAKER_MS: Long = 40L
            const val LATENCY_WIRED_MS: Long = 25L
            const val LATENCY_BT_SBC_MS: Long = 120L
            const val LATENCY_BT_AAC_MS: Long = 80L
            const val LATENCY_BT_LDAC_MS: Long = 150L
            const val LATENCY_USB_MS: Long = 30L

            private fun latencyKey(key: String) = longPreferencesKey("latency_$key")

            private fun p50Key(key: String) = longPreferencesKey("p50_$key")

            private fun p95Key(key: String) = longPreferencesKey("p95_$key")

            private fun calibratedAtKey(key: String) = longPreferencesKey("calibrated_at_$key")

            private fun confidenceKey(key: String) = stringPreferencesKey("confidence_$key")

            private fun staleKey(key: String) = longPreferencesKey("stale_$key")
        }
    }
