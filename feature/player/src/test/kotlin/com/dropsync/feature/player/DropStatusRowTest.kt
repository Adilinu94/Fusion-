package com.dropsync.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 1): Die DropSync-Statuszeile im Player — die Sprache der
 * Train-Konsole als Zeile (MP-7), inklusive TalkBack-Satz (C5) und der
 * quittierbaren "Plan verloren"-Zeile (C1).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DropStatusRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `bereit-zeile nennt titel marker und ziel und spricht einen satz`() {
        show(
            DropStatusLine.Ready(
                songTitle = "Neon Alley",
                markerLabel = "Drop",
                remainingMs = 48_000L,
                mode = DropSyncMode.LANDING_AT_REST_END,
            ),
        )

        compose.onNodeWithText("DROP READY").assertIsDisplayed()
        compose.onNodeWithText("Neon Alley", substring = true).assertIsDisplayed()
        compose.onNodeWithText("target in 0:48", substring = true).assertIsDisplayed()
        // C5: TalkBack hoert den ganzen Satz, nicht nur den Punkt.
        compose
            .onNodeWithContentDescription("DropSync ready", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `beste leistung nennt den zustand und den grund im satz`() {
        show(DropStatusLine.BestEffort(BestEffortReason.UNKNOWN_LATENCY))

        compose.onNodeWithText("BEST EFFORT").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("DropSync best effort only", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `uebernommener plan wird sichtbar zurueckgenommen`() {
        show(DropStatusLine.Overridden)

        compose.onNodeWithText("MANUALLY OVERRIDDEN").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("DropSync plan overridden manually", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `plan verloren nennt den grund und laesst sich quittieren`() {
        var dismissed = 0
        show(DropStatusLine.Failed(DropSyncFailureReason.PLAN_LOST), onDismiss = { dismissed++ })

        compose.onNodeWithText("DROPSYNC · PLAN LOST").assertIsDisplayed()
        compose
            .onNodeWithText("The app was restarted; the plan could not be restored.")
            .assertIsDisplayed()

        compose.onNodeWithText("OK").performClick()
        assertEquals(1, dismissed)
    }

    private fun show(
        status: DropStatusLine,
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            FlowRepTheme {
                DropSyncStatusRow(
                    status = status,
                    palette = testPalette(),
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Composable
    private fun testPalette(): NowPlayingPalette {
        val scheme = MaterialTheme.colorScheme
        return NowPlayingPalette(
            background = scheme.background,
            accent = scheme.primary,
            content = scheme.onBackground,
            contentMuted = scheme.onSurfaceVariant,
            onAccent = scheme.onPrimary,
            inactive = scheme.onBackground.copy(alpha = 0.38f),
            disabled = scheme.onBackground.copy(alpha = 0.20f),
            waveUpcoming = scheme.primary.copy(alpha = 0.45f),
            waveReflection = scheme.primary.copy(alpha = 0.16f),
        )
    }
}
