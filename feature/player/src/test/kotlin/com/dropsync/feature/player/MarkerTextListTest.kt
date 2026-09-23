package com.dropsync.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.SongMarker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C9 (P-8): Die Marker-Textliste ist die alternative Ansicht zur
 * Canvas-Waveform — alle Marker sind als Text da und jede Zeile springt
 * mit Vorlauf zum Marker.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkerTextListTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `liste enthaelt alle marker und springt beim tippen`() {
        val clicked = mutableListOf<Long>()
        compose.setContent {
            MarkerTextList(
                markers =
                    listOf(
                        marker(id = 1L, label = "Drop A", positionMs = 42_000L),
                        marker(id = 2L, label = "Drop B", positionMs = 78_500L),
                    ),
                onListenMarker = { clicked += it.id },
            )
        }

        compose.onNodeWithText("Drop A").assertIsDisplayed()
        compose.onNodeWithText("Drop B").assertIsDisplayed()

        compose.onNodeWithText("Drop B").performClick()
        assertEquals(listOf(2L), clicked)
    }

    private fun marker(
        id: Long,
        label: String,
        positionMs: Long,
    ): SongMarker =
        SongMarker(
            id = id,
            label = label,
            positionMs = positionMs,
            source = MarkerSource.AUTO_DETECTED,
            isEnabled = true,
            linkedSongId = 7L,
        )
}
