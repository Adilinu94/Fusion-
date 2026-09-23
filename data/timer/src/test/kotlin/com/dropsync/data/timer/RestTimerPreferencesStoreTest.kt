package com.dropsync.data.timer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ApplicationProvider
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Rohzugriff auf denselben DataStore-Namen wie [RestTimerPreferencesStore]
 * (Muster AccentColorStoreTest) — fuer den Korrupt-/Fallback-Fall.
 */
private val Context.seedRestTimerPrefs by preferencesDataStore(name = "rest_timer_prefs")

/**
 * Persistenz der Resttimer-Einstellungen (B8/B9): Defaults 60/90/120/180 s,
 * Bereinigung der Preset-Liste, Clamping des Vorlaufs und der Fallback bei
 * unbekanntem gespeichertem String.
 */
class RestTimerPreferencesStoreTest : RobolectricTestCase() {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = RestTimerPreferencesStore(context)

    @Before
    fun reset() =
        runTest {
            store.setRestPresetsSeconds(RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS)
            store.setGetReady(false, RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS)
        }

    @Test
    fun `defaults sind 60 90 120 180 und Vorlauf aus`() =
        runTest {
            assertEquals(listOf(60, 90, 120, 180), store.restPresetsSeconds.first())
            assertEquals(false, store.getReadyEnabled.first())
            assertEquals(3, store.getReadySeconds.first())
        }

    @Test
    fun `presets werden gefiltert entdoppelt und sortiert gespeichert`() =
        runTest {
            store.setRestPresetsSeconds(listOf(90, 60, 60, -5, 0, 120))
            assertEquals(listOf(60, 90, 120), store.restPresetsSeconds.first())
        }

    @Test
    fun `get ready wird auf 1 bis 10 sekunden begrenzt`() =
        runTest {
            store.setGetReady(true, 99)
            assertEquals(true, store.getReadyEnabled.first())
            assertEquals(10, store.getReadySeconds.first())

            store.setGetReady(false, 0)
            assertEquals(false, store.getReadyEnabled.first())
            assertEquals(1, store.getReadySeconds.first())
        }

    @Test
    fun `kaputter gespeicherter String faellt auf Default-Presets zurueck`() =
        runTest {
            context.seedRestTimerPrefs.edit { prefs ->
                prefs[stringPreferencesKey("rest_presets_seconds")] = "abc,,0,-3"
            }
            assertEquals(listOf(60, 90, 120, 180), store.restPresetsSeconds.first())
        }
}
