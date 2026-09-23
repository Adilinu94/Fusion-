package com.dropsync.feature.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 1): Verhalten der Rest-Konsole — die Zustaende, die im Alltag
 * zaehlen: normale Pause, DropSync-Kette (C16) und der Schalter unter der
 * Mindestpause (C15). Die Screenshots (D1) frieren das Aussehen ein, diese
 * Tests die Regeln.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class RestConsoleBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `normale pause zeigt plus 15 sekunden und den dropsync-schalter`() {
        showConsole(
            remainingMs = 90_000L,
            mode = TimerMode.REST,
            dropSyncState = DropSyncState.Off,
        )

        compose.onNodeWithText("REST").assertIsDisplayed()
        compose.onNodeWithText("+15 SECONDS").assertIsDisplayed()
        compose.onNodeWithText("DropSync for this rest").assertIsDisplayed()
        compose.onNodeWithText("END REST").assertIsDisplayed()
    }

    @Test
    fun `unter einer minute ist der dropsync-schalter blockiert`() {
        showConsole(
            remainingMs = 30_000L,
            mode = TimerMode.REST,
            dropSyncState = DropSyncState.Off,
        )

        compose.onNodeWithText("DropSync for this rest").assertIsDisplayed()
        compose.onNodeWithText("Only possible from 1 minute rest").assertIsDisplayed()
    }

    @Test
    fun `dropsync-kette steht in der kopfzeile und plus 15 sekunden fehlt`() {
        showConsole(
            remainingMs = 45_000L,
            mode = TimerMode.DROPSYNC,
            dropSyncState =
                DropSyncState.Planned(
                    songTitle = "Neon Alley",
                    markerLabel = "Drop",
                    targetElapsedRealtimeMs = 45_000L,
                    remainingMs = 45_000L,
                    confidence = TimingConfidence.EXACT,
                    mode = DropSyncMode.LANDING_AT_REST_END,
                    chain = listOf("Midnight Drive", "Neon Alley", "Drop"),
                ),
        )

        compose.onNodeWithText("DROPSYNC").assertIsDisplayed()
        compose
            .onNodeWithText("Midnight Drive${CHAIN_ARROW}Neon Alley${CHAIN_ARROW}Drop", substring = true)
            .assertIsDisplayed()
        // MP-6: Bei einem DropSync-Rest ist +15 s wirkungslos — der Knopf
        // wird nicht angeboten statt still nichts zu tun.
        compose.onNodeWithText("+15 SECONDS").assertDoesNotExist()
        compose.onNodeWithText("CANCEL PLAN").assertIsDisplayed()
    }

    @Test
    fun `go-overlay erscheint nach der landung und schliesst per tap`() {
        // A5 (T-6): Das GO-Overlay gehoert der Konsole (kein Touch-Leak) und
        // schliesst per Tap — vor dem 4-s-Selbstschluss, deshalb misst der
        // Test die verstrichene Testzeit mit.
        showConsole(
            remainingMs = 0L,
            mode = TimerMode.DROPSYNC,
            dropSyncState = DropSyncState.Landed(atElapsedRealtimeMs = 1_000L, deltaMs = 12L),
        )

        compose.onNodeWithText("GO").assertIsDisplayed()
        compose.onNodeWithText("Drop landed").assertIsDisplayed()

        val vorDemTap = compose.mainClock.currentTime
        compose.onNodeWithText("GO").performClick()
        compose.onNodeWithText("GO").assertDoesNotExist()

        val gedauert = compose.mainClock.currentTime - vorDemTap
        assertTrue(
            "Der Tap muss vor dem Selbstschluss (4 s) schliessen, war ${gedauert}ms",
            gedauert < 4_000L,
        )
    }

    @Test
    fun `go-overlay traegt die klick-aktion selbst`() {
        // A5: Der Tap gehoert dem Overlay — ein direkt gerendertes Overlay
        // ruft onDismiss (die Knoepfe darunter bekommen nichts ab).
        var dismissed = 0
        compose.setContent {
            FlowRepTheme {
                Box {
                    GoOverlay(onDismiss = { dismissed++ })
                }
            }
        }

        compose.onNodeWithText("GO").performClick()
        assertEquals(1, dismissed)
    }

    private fun showConsole(
        remainingMs: Long,
        mode: TimerMode,
        dropSyncState: DropSyncState,
    ) {
        compose.setContent {
            FlowRepTheme {
                RestConsole(
                    remainingMs = remainingMs,
                    mode = mode,
                    timerStatus = TimerStatus.RUNNING,
                    dropSyncState = dropSyncState,
                    restDuckDb = -12.0,
                    dropAutoChecked = false,
                    onAddTime = {},
                    onSkip = {},
                    onCancelPlan = {},
                    onFinish = {},
                    onOpenTimer = {},
                    onSetRestDuckDb = {},
                    onSetDropAuto = {},
                )
            }
        }
    }
}
