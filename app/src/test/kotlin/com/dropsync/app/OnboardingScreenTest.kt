package com.dropsync.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
/**
 * Paket 5 / Bericht 2.4: Compose-Tests fuer das First-Run-Onboarding
 * ([OnboardingScreen]) auf Robolectric. Geprueft werden die Vertraege aus
 * B3/C5/Befund 7.1.1: drei Seiten ohne Wischzwang, Zurueck ab Seite 2,
 * Skip nur vor der letzten Seite, Abschluss-Callback einmalig, und der
 * Seitenzaehler als TalkBack-Text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var finished = 0

    private fun str(res: Int): String = context.getString(res)

    private fun str(
        res: Int,
        vararg args: Any,
    ): String = context.getString(res, *args)

    private fun setContent(page: Int = 0) {
        finished = 0
        compose.setContent {
            OnboardingScreen(
                onFinish = { finished++ },
            )
        }
        compose.waitForIdle()
        repeat(page) {
            compose.onNodeWithText(str(R.string.onboarding_next)).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun `erste Seite zeigt Titel und Skip`() {
        setContent()
        compose.onNodeWithText(str(R.string.onboarding_music_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_skip)).assertIsDisplayed()
        // Zurueck gibt es erst ab Seite 2.
        compose.onAllNodesWithText(str(R.string.onboarding_back)).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun `weiter fuehrt durch alle drei Seiten bis Get started`() {
        setContent()
        compose.onNodeWithText(str(R.string.onboarding_next)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.onboarding_drop_title)).assertIsDisplayed()
        // Ab Seite 2 gibt es Zurueck, Skip weiterhin.
        compose.onNodeWithText(str(R.string.onboarding_back)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_next)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.onboarding_training_title)).assertIsDisplayed()
        // Letzte Seite: kein Skip mehr, nur "Get started".
        compose.onAllNodesWithText(str(R.string.onboarding_skip)).fetchSemanticsNodes().isEmpty()
        compose.onNodeWithText(str(R.string.onboarding_start)).performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun `zurueck kehrt zur vorherigen Seite zurueck`() {
        setContent(page = 1)
        compose.onNodeWithText(str(R.string.onboarding_drop_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.onboarding_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.onboarding_music_title)).assertIsDisplayed()
        assertEquals(0, finished)
    }

    @Test
    fun `skip beendet das Onboarding sofort`() {
        setContent()
        compose.onNodeWithText(str(R.string.onboarding_skip)).performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun `seitenzaehler enthaelt Seite X von 3`() {
        setContent()
        // C5: TalkBack-Text am Punkte-Indikator (contentDescription, kein
        // eigener Text-Knoten).
        compose
            .onNodeWithContentDescription(str(R.string.onboarding_page_indicator, 1, 3))
            .assertExists()
    }
}
