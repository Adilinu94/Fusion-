package com.dropsync.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config

/**
 * P2-17/RC-7: Der Diagnose-Schalter ist default `false` (kein Technik-UI im
 * Alltag) und bleibt nach dem Setzen `true` — auch ueber eine neue
 * Store-Instanz, also wirklich persistiert. Wie beim Onboarding laeuft die
 * Default-Pruefung per Methodenordnung zuerst (kein Reset im Produktivcode).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DebugSettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = DebugSettingsStore(context)

    @Test
    fun `a default ist aus`() =
        runTest {
            assertFalse(store.diagnosticsEnabled.first())
        }

    @Test
    fun `b einschalten bleibt dauerhaft`() =
        runTest {
            store.setDiagnosticsEnabled(true)
            assertTrue(store.diagnosticsEnabled.first())
            assertTrue(DebugSettingsStore(context).diagnosticsEnabled.first())
        }
}
