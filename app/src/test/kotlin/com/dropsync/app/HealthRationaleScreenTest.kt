package com.dropsync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bericht 2.4: die Health-Connect-Begruendungsseite zeigt ihre vier Text-
 * Bloecke (Zweck, Umfang, Offline, Widerruf). Die Activity ist
 * @AndroidEntryPoint; Robolectric startet sie ueber die Compose-Activity-
 * Rule, Hilt liefert die Theme-Stores (echte DataStore-Implementierungen
 * auf dem Robolectric-Kontext).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HealthRationaleScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<HealthRationaleActivity>()

    private fun text(res: Int): String = compose.activity.getString(res)

    @Test
    fun `begruendung zeigt Zweck Umfang Offline und Widerruf`() {
        compose
            .onNodeWithText(text(R.string.health_rationale_title))
            .assertIsDisplayed()
        compose
            .onNodeWithText(text(R.string.health_rationale_purpose))
            .assertIsDisplayed()
        compose
            .onNodeWithText(text(R.string.health_rationale_scope))
            .assertIsDisplayed()
        compose
            .onNodeWithText(text(R.string.health_rationale_offline))
            .assertIsDisplayed()
        compose
            .onNodeWithText(text(R.string.health_rationale_revoke))
            .assertIsDisplayed()
    }
}
