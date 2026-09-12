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
 * B3: Das First-Run-Flag ist default `false` und bleibt nach dem Setzen
 * `true` — das Onboarding erscheint genau einmal. Der Store hat bewusst keine
 * Reset-API (Produktivcode), daher laeuft die Default-Pruefung per
 * Methodenordnung zuerst.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OnboardingStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = OnboardingStore(context)

    @Test
    fun `a default ist ungesehen`() =
        runTest {
            assertFalse(store.onboardingSeen.first())
        }

    @Test
    fun `b markieren setzt gesehen dauerhaft`() =
        runTest {
            store.markOnboardingSeen()
            assertTrue(store.onboardingSeen.first())
            assertTrue(OnboardingStore(context).onboardingSeen.first())
        }
}
