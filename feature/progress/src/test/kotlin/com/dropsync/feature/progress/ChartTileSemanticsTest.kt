package com.dropsync.feature.progress

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A5-Semantik-Test (Ausbauplan): Die unsichtbare Tap-Ebene ueber dem
 * Wochen-Chart besteht aus acht Buttons — jeder nennt Woche und Volumen,
 * statt achtmal nur „Button" zu melden.
 */
@RunWith(AndroidJUnit4::class)
class ChartTileSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun state() =
        ProgressUiState(
            hasAnySets = true,
            trainingDaysThisWeek = 2,
            weeklyGoal = 3,
            streakDays = 0,
            streakAtRisk = false,
            firstWeekDayWithoutSets = false,
            weekVolumeKg = 800.0,
            weekBars =
                List(8) { index ->
                    ProgressWeekBar(
                        weekStartEpochMs = index.toLong(),
                        volumeKg = (index + 1) * 100.0,
                        trainingDays = 2,
                        isCurrentWeek = index == 7,
                    )
                },
        )

    @Test
    fun `jeder Balken meldet Woche und Volumen`() {
        compose.setContent { ChartTile(progress = state()) }
        // Die Tap-Ebene sind die einzigen Klickziele der Kachel: achtmal
        // Klick plus achtmal Wochen- und Volumen-Ansage.
        assertEquals(8, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        assertEquals(
            8,
            compose.onAllNodes(hasContentDescription("Week", substring = true)).fetchSemanticsNodes().size,
        )
        assertEquals(
            8,
            compose.onAllNodes(hasContentDescription("volume", substring = true)).fetchSemanticsNodes().size,
        )
    }
}
