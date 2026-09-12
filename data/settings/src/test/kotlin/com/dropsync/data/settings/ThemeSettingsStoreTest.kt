package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B-UI-1, letzte offene Zeile (`data/settings` hatte null Tests):
 * DataStore-Roundtrip plus der dokumentierte Fallback (unbekannte oder
 * fehlende Werte fallen auf [ThemeMode.SYSTEM] zurueck).
 *
 * Der Rohzugriff nutzt denselben DataStore-Namen wie der Store; der
 * Delegate cached pro Name genau eine Instanz, daher sieht der Store
 * den gesetzten Rohwert ohne zweite Datei.
 */
private val Context.seedThemeStore by preferencesDataStore(name = "theme_settings")

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ThemeSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ThemeSettingsStore(context)

    @Before
    fun reset() =
        runTest {
            store.setThemeMode(ThemeMode.SYSTEM)
        }

    @Test
    fun `default ist SYSTEM wenn nichts gespeichert`() =
        runTest {
            assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
        }

    @Test
    fun `gesetzter Modus round-tript`() =
        runTest {
            store.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, store.themeMode.first())
        }

    @Test
    fun `ueberschreiben ersetzt den alten Wert`() =
        runTest {
            store.setThemeMode(ThemeMode.DARK)
            store.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, store.themeMode.first())
        }

    @Test
    fun `unbekannter gespeicherter Wert faellt auf SYSTEM zurueck`() =
        runTest {
            context.seedThemeStore.edit { prefs ->
                prefs[stringPreferencesKey("theme_mode")] = "ULTRA_DARK"
            }
            assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
        }
}
