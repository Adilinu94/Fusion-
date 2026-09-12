package com.dropsync.feature.timer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B2-Interaktionstest des Stellrads (Robolectric): Spaltenlabels sind
 * lokalisiert, Stunden-Spalte ist aktiv (kein Placeholder mehr).
 * Test-Locale ist Englisch (HRS/MIN/SEC).
 */
@RunWith(AndroidJUnit4::class)
class TimerWheelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Spaltenlabels sind vorhanden`() {
        compose.setContent { TimerWheel(seconds = 90, onSecondsChange = {}) }
        compose.onNodeWithText("HRS").assertExists()
        compose.onNodeWithText("MIN").assertExists()
        compose.onNodeWithText("SEC").assertExists()
    }

    @Test
    fun `Minuten Plus erhöht um 60 Sekunden`() {
        // 90 s: MIN naechste „02" ist eindeutig (HRS 00/00/01, SEK 15/30/45).
        var seconds = 90
        compose.setContent { TimerWheel(seconds = seconds, onSecondsChange = { seconds = it }) }
        compose.onNodeWithText("02").performClick()
        assertEquals(150, seconds)
    }

    @Test
    fun `Stunden Plus erhöht um eine Stunde`() {
        // 3600 s: HRS naechste „02" ist eindeutig (MIN 00/00/01, SEK 45/00/15).
        var seconds = 3_600
        compose.setContent { TimerWheel(seconds = seconds, onSecondsChange = { seconds = it }) }
        compose.onNodeWithText("02").performClick()
        assertEquals(7_200, seconds)
    }
}
