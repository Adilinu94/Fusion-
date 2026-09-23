package com.dropsync.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.domain.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 2): Verhalten der Player-Bedienung — Next bleibt auch bei
 * scharfem Plan bedienbar (C2/5.10: Doppelbestaetigung statt harter Sperre;
 * die alte Idee "Details statt Next" ist damit vom Tisch) und das
 * DropSync-Badge nennt Zustand, Countdown und Kette (C2/C16) und ist der
 * Details-Einstieg.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NowPlayingBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `next bleibt bedienbar und ruft den skip-pfad`() {
        var next = 0
        showRow(hasNext = true, onNext = { next++ })

        compose
            .onNodeWithContentDescription("Next song")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, next)
    }

    @Test
    fun `next ist ohne nachfolgenden titel gesperrt`() {
        showRow(hasNext = false)

        compose.onNodeWithContentDescription("Next song").assertIsNotEnabled()
    }

    @Test
    fun `badge nennt den plan mit countdown und oeffnet die details`() {
        var opened = 0
        showBadge(DropSyncBadge.READY, countdownMs = 87_000L, onClick = { opened++ })

        compose.onNodeWithText("DROP READY · 1:27").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `badge nennt die ueberleitungskette`() {
        showBadge(
            DropSyncBadge.READY,
            countdownMs = 87_000L,
            chain = listOf("Midnight Drive", "Neon Alley", "Drop"),
        )

        compose
            .onNodeWithText("Midnight Drive${CHAIN_ARROW}Neon Alley${CHAIN_ARROW}Drop · 1:27")
            .assertIsDisplayed()
    }

    @Test
    fun `uebernommener plan steht als drop aus`() {
        showBadge(DropSyncBadge.OVERRIDDEN)

        compose.onNodeWithText("DROP OFF").assertIsDisplayed()
    }

    @Test
    fun `verlorener plan steht als drop lost`() {
        showBadge(DropSyncBadge.FAILED)

        compose.onNodeWithText("DROP LOST").assertIsDisplayed()
    }

    private fun showRow(
        hasNext: Boolean,
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            FlowRepTheme {
                PowerampModeRow(
                    repeatMode = RepeatMode.OFF,
                    shuffleEnabled = false,
                    hasPrevious = true,
                    hasNext = hasNext,
                    palette = testPalette(),
                    onCycleRepeat = {},
                    onToggleShuffle = {},
                    onPrevious = {},
                    onNext = onNext,
                )
            }
        }
    }

    private fun showBadge(
        badge: DropSyncBadge,
        countdownMs: Long? = null,
        chain: List<String> = emptyList(),
        onClick: () -> Unit = {},
    ) {
        compose.setContent {
            FlowRepTheme {
                DropSyncBadgeChip(
                    badge = badge,
                    countdownMs = countdownMs,
                    chain = chain,
                    onClick = onClick,
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
