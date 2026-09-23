package com.dropsync.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D1 (I-7): Die Rest-Konsole ist der Hero des Train-Tabs — hier liegt die
 * DropSync-Sprache (Kopf, Zielzeile, Chips, Aktionen). Zwei Zustaende sind
 * eingefroren: die normale Pause (C15-Schalter sichtbar) und die geplante
 * Ueberleitungskette (C16: "A -> B -> Drop" in der Kopfzeile). Jede
 * unbeabsichtigte Verschiebung faellt im CI-Gate, nicht erst auf dem Geraet.
 *
 * Referenzen liegen unter `src/test/screenshots/` und werden per
 * `recordRoborazziDebug` erneuert (nur bei beabsichtigtem Redesign, danach
 * Review des Diffs).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class RestConsoleScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun restKonsoleNormal() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    RestConsole(
                        remainingMs = 90_000L,
                        mode = TimerMode.REST,
                        timerStatus = TimerStatus.RUNNING,
                        dropSyncState = DropSyncState.Off,
                        restDuckDb = -12.0,
                        dropAutoChecked = true,
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
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun restKonsoleKette() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    RestConsole(
                        remainingMs = 45_000L,
                        mode = TimerMode.DROPSYNC,
                        timerStatus = TimerStatus.RUNNING,
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
                        restDuckDb = -6.0,
                        dropAutoChecked = true,
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
        compose.onRoot().captureRoboImage()
    }
}
