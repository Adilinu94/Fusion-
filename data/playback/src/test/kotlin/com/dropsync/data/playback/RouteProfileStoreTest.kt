package com.dropsync.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.data.audio.OutputDeviceMonitor
import com.dropsync.domain.audio.OutputDeviceKind
import com.dropsync.domain.playback.AudioRouteProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Latenzprofile je Audio-Route (Design Phase 6, Abschnitt 10): Tabellenwert
 * ohne Messung (Robolectric hat keine Ausgabegeraete -> SPEAKER-Fallback),
 * Upsert eines kalibrierten Profils, STALE-Rueckfall nach Geraetewechsel.
 *
 * Der DataStore wird zwischen Tests derselben Klasse geteilt; `@Before`
 * markiert deshalb STALE, damit der Tabellenwert-Zustand garantiert ist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RouteProfileStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val monitor = OutputDeviceMonitor(context)
    private val store = RouteProfileStore(context, monitor)

    @Before
    fun reset() =
        runTest {
            store.markStale()
        }

    @Test
    fun `ohne Messung liefert die Tabelle das Speaker-Profil`() =
        runTest {
            assertEquals(OutputDeviceKind.SPEAKER, monitor.device.value.kind)
            val profile = store.currentProfile.first()
            assertNotNull(profile)
            assertEquals(RouteProfileStore.LATENCY_SPEAKER_MS, profile!!.estimatedLatencyMs)
            assertEquals(AudioRouteProfile.Confidence.ESTIMATED, profile.confidence)
        }

    @Test
    fun `currentLatencyMs entspricht dem Tabellenwert`() =
        runTest {
            assertEquals(RouteProfileStore.LATENCY_SPEAKER_MS, store.currentLatencyMs())
        }

    @Test
    fun `upsert ersetzt den Tabellenwert und markStale faellt zurueck`() =
        runTest {
            val key = store.currentProfile.first()!!.routeKey
            store.upsert(
                AudioRouteProfile(
                    routeKey = key,
                    sampleRate = 48_000,
                    channels = 2,
                    estimatedLatencyMs = 25,
                    p50ErrorMs = 4,
                    p95ErrorMs = 9,
                    calibratedAt = System.currentTimeMillis() - 1_000,
                    confidence = AudioRouteProfile.Confidence.CALIBRATED,
                ),
            )

            val calibrated = store.currentProfile.first()!!
            assertEquals(25L, calibrated.estimatedLatencyMs)
            assertEquals(AudioRouteProfile.Confidence.CALIBRATED, calibrated.confidence)
            assertEquals(25L, store.currentLatencyMs())

            store.markStale()

            val stale = store.currentProfile.first()!!
            assertEquals(RouteProfileStore.LATENCY_SPEAKER_MS, stale.estimatedLatencyMs)
            assertEquals(AudioRouteProfile.Confidence.ESTIMATED, stale.confidence)
            assertEquals(RouteProfileStore.LATENCY_SPEAKER_MS, store.currentLatencyMs())
        }
}
