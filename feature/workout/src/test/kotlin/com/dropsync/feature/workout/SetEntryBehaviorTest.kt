package com.dropsync.feature.workout

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.RestMode
import com.dropsync.domain.sensor.ActiveSetPhase
import com.dropsync.domain.sensor.SignalQuality
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 2): Verhalten der Satz-Eingabe — genau eine Primaeraktion im
 * SET_ENTRY-Modus (A.4) und der Undo-Pfad der Satz-Snackbar (A1/T-1:
 * "Satz gespeichert" traegt "Rueckgaengig"). Die Screenshots (D1) frieren
 * das Aussehen ein, diese Tests die Regeln.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp")
class SetEntryBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `set-eingabe nennt die uebung und genau eine primaeraktion`() {
        var logged = 0
        showSetEntry(onLogSet = { logged++ })

        compose.onNodeWithText("ENTER SET").assertIsDisplayed()
        compose.onNodeWithText("Bankdruecken").assertIsDisplayed()
        compose.onNodeWithText("SET DONE").assertIsDisplayed().performClick()
        assertEquals(1, logged)
    }

    @Test
    fun `satz fertig ist ohne eingabe gesperrt`() {
        showSetEntry(canLog = false)

        compose.onNodeWithText("SET DONE").assertIsNotEnabled()
    }

    @Test
    fun `satz gespeichert bietet rueckgaengig an`() {
        val events = MutableSharedFlow<SetLogEvent>(extraBufferCapacity = 1)
        var undone = 0
        showEventHost(events, onUndoSetLog = { undone++ })

        events.tryEmit(SetLogEvent.Logged(setId = 7L, exerciseId = 3L))
        awaitText("Set saved")
        compose.onNodeWithText("Undo").performClick()
        // Der Klick resumed showSnackbar und ruft erst danach den Undo-Pfad.
        repeat(5) { compose.mainClock.advanceTimeByFrame() }

        assertEquals(1, undone)
    }

    @Test
    fun `zurueckgenommener satz wird gemeldet`() {
        val events = MutableSharedFlow<SetLogEvent>(extraBufferCapacity = 1)
        showEventHost(events)

        events.tryEmit(SetLogEvent.Undone(exerciseId = 3L))
        awaitText("Set removed")

        compose.onNodeWithText("Set removed").assertIsDisplayed()
    }

    @Test
    fun `idle-konsole nennt den platzhalter und bietet keine primaeraktion`() {
        // A.4: IDLE hat keine Uebung — die Konsole fuehrt mit dem Platzhalter
        // und bietet bewusst KEINE Aktion an.
        compose.setContent {
            FlowRepTheme {
                EmptyConsoleHero()
            }
        }

        compose.onNodeWithText("Pick an exercise").assertIsDisplayed()
        compose.onNodeWithText("SET DONE").assertDoesNotExist()
    }

    private fun showSetEntry(
        canLog: Boolean = true,
        onLogSet: () -> Unit = {},
    ) {
        compose.setContent {
            FlowRepTheme {
                SetEntryHero(
                    exerciseName = "Bankdruecken",
                    weightKg = "80",
                    lastWeightKg = 75.0,
                    reps = "8",
                    repsSource = RepsSource.Manual,
                    setPhase = ActiveSetPhase.IDLE,
                    countdownSeconds = 0,
                    liveCountedReps = 0,
                    streaming = false,
                    signalQuality = SignalQuality.GOOD,
                    hasCalibration = true,
                    waveform = FloatArray(0),
                    lastPeakMs = 0L,
                    plausibilityHint = null,
                    countedZero = false,
                    maxVolumeKg = null,
                    restSeconds = 90,
                    restMode = RestMode.NORMAL,
                    canLog = canLog,
                    onWeightChange = {},
                    onIncrement = {},
                    onDecrement = {},
                    onRepsChange = {},
                    onStartSet = {},
                    onStopSet = {},
                    onLogSet = onLogSet,
                    onOpenRestPref = {},
                )
            }
        }
    }

    private fun showEventHost(
        events: MutableSharedFlow<SetLogEvent>,
        onUndoSetLog: () -> Unit = {},
    ) {
        // Die Snackbar (Short) darf nicht weglaufen, bevor der Klick kommt:
        // die Uhr laeuft nur, wenn der Test sie schiebt.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FlowRepTheme {
                val host = remember { SnackbarHostState() }
                // Scaffold nur als Snackbar-Traeger; der Content-Padding-
                // Parameter bleibt bewusst ungenutzt (Lint Suppress).
                @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
                Scaffold(snackbarHost = { SnackbarHost(host) }) {
                    TrainEventSnackbars(
                        snackbarHostState = host,
                        setLogEvents = events,
                        onUndoSetLog = onUndoSetLog,
                        learningEvent = emptyFlow(),
                        errorEvent = emptyFlow(),
                        setReport = emptyFlow(),
                    )
                }
            }
        }
    }

    /** Schiebt Frames, bis der Text im Baum steht (max. ~2 s Testzeit). */
    private fun awaitText(text: String) {
        repeat(120) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeByFrame()
        }
        throw AssertionError("Knoten '$text' erschien nicht")
    }
}
