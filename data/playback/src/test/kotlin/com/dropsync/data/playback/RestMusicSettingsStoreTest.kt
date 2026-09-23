package com.dropsync.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.domain.playback.RestMusicSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pausen-Musik-Verhalten (Phase 3) und Drop-Auto-Schalter (MP-13):
 * Defaults (NORMAL, Drop-Auto an) und Roundtrips.
 *
 * Kein Seed-Test des unbekannten Enum-Strings: Ein zweiter
 * `preferencesDataStore`-Delegate auf derselben Datei kollidiert mit der
 * Store-Instanz ("multiple DataStores active for the same file").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RestMusicSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = RestMusicSettingsStore(context)

    @Before
    fun reset() =
        runTest {
            store.setBehavior(RestMusicBehavior.NORMAL)
            store.setDropAutoEnabled(RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED)
        }

    @Test
    fun `defaults sind NORMAL und Drop-Auto an`() =
        runTest {
            assertEquals(RestMusicBehavior.NORMAL, store.behavior.first())
            assertEquals(true, store.dropAutoEnabled.first())
        }

    @Test
    fun `Verhalten round-tript`() =
        runTest {
            store.setBehavior(RestMusicBehavior.DROP_LANDING)
            assertEquals(RestMusicBehavior.DROP_LANDING, store.behavior.first())
        }

    @Test
    fun `Drop-Auto-Schalter round-tript`() =
        runTest {
            store.setDropAutoEnabled(false)
            assertEquals(false, store.dropAutoEnabled.first())
        }
}
