package com.dropsync.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Nutzeroption "Bei BT-Verbindung automatisch fortsetzen": Default aus,
 * gesetzter Wert round-tript (DataStore-Muster AccentColorStoreTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PlaybackSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = PlaybackSettingsStore(context)

    @Before
    fun reset() =
        runTest {
            store.setResumeOnBluetoothConnect(false)
        }

    @Test
    fun `default ist aus`() =
        runTest {
            assertEquals(false, store.resumeOnBluetoothConnect.first())
        }

    @Test
    fun `gesetzter Wert round-tript`() =
        runTest {
            store.setResumeOnBluetoothConnect(true)
            assertEquals(true, store.resumeOnBluetoothConnect.first())
        }
}
