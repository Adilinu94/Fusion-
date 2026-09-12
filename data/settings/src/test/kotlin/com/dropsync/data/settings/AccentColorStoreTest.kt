package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.AccentColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B-UI-1 (`data/settings` hatte null Tests): DataStore-Roundtrip der
 * Akzentfarbe plus Fallback auf die Markenfarbe [AccentColor.LIME].
 * Rohzugriff wie in ThemeSettingsStoreTest (gleicher DataStore-Name).
 */
private val Context.seedAccentStore by preferencesDataStore(name = "accent_settings")

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AccentColorStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AccentColorStore(context)

    @Before
    fun reset() =
        runTest {
            store.setAccentColor(AccentColor.LIME)
        }

    @Test
    fun `default ist LIME wenn nichts gespeichert`() =
        runTest {
            assertEquals(AccentColor.LIME, store.accentColor.first())
        }

    @Test
    fun `gesetzte Farbe round-tript`() =
        runTest {
            store.setAccentColor(AccentColor.BLUE)
            assertEquals(AccentColor.BLUE, store.accentColor.first())
        }

    @Test
    fun `unbekannter gespeicherter Wert faellt auf LIME zurueck`() =
        runTest {
            context.seedAccentStore.edit { prefs ->
                prefs[stringPreferencesKey("accent_color")] = "NEON_PINK"
            }
            assertEquals(AccentColor.LIME, store.accentColor.first())
        }
}
