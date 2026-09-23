package com.dropsync.core.designsystem.chart

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C9 (P-8, UI-Handbuch 19.4): Die Canvas-Waveform hat einen
 * Slider-Fallback — TalkBack kann den Fortschritt setzen, ohne dass eine
 * Zeigergeste noetig ist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RunningWaveformSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `waveform bietet den slider-fallback fuer talkback`() {
        val seeks = mutableListOf<Float>()
        compose.setContent {
            RunningWaveform(
                buckets = listOf(0.1f to 0.4f, 0.2f to 0.6f, 0.3f to 0.8f),
                progressFraction = { 0.25f },
                onSeek = { seeks += it },
                onScrubPreview = {},
                contentDescription = "Waveform des Titels",
                modifier = Modifier.size(width = 320.dp, height = 80.dp).testTag("waveform"),
            )
        }

        compose
            .onNodeWithTag("waveform")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        compose
            .onNodeWithTag("waveform")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }

        assertEquals(listOf(0.5f), seeks)
    }
}
