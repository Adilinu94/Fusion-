package com.dropsync.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.SongMarker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 2): Verhalten des Marker-Sheets (P2-21) — Vorschlag
 * bestaetigen, Feinjustierung mit "Zurueck auf Original" nur bei
 * Abweichung, Zielwahl und Loeschen. Geprueft wird der Sheet-Inhalt ohne
 * das ModalBottomSheet-Fenster; die Chrome friert D1 nicht ein, die Regeln
 * sind hier.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
class MarkerSheetBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `vorschlag zeigt bestaetigen und die feinjustierung`() {
        var confirmed = 0
        var adjusted = 0L
        showSheet(
            marker = marker(),
            isSuggestion = true,
            onConfirm = { confirmed++ },
            onAdjust = { adjusted = it },
        )

        compose.onNodeWithText("Suggestion from analysis").assertIsDisplayed()
        compose.onNodeWithText("Fine-tune position").assertIsDisplayed()
        compose.onNodeWithText("+100 ms").performClick()
        assertEquals(100L, adjusted)
        // "Back to original" gibt es nur bei Abweichung (MarkerEditState).
        compose.onNodeWithText("Back to original").assertDoesNotExist()

        compose.onNodeWithText("Confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `geaenderte position zeigt den weg zurueck aufs original`() {
        var reverted = 0
        showSheet(
            marker = marker(),
            editState = MarkerEditState(originalPositionMs = 42_000L, editedPositionMs = 42_100L),
            onRevert = { reverted++ },
        )

        compose.onNodeWithText("Back to original").assertIsDisplayed().performClick()
        assertEquals(1, reverted)
    }

    @Test
    fun `bestaetigter marker bietet zielwahl und loeschen`() {
        var target = 0
        var deleted = 0
        showSheet(
            marker = marker(source = MarkerSource.MANUAL),
            onChooseTarget = { target++ },
            onDelete = { deleted++ },
        )

        compose.onNodeWithText("Set manually").assertIsDisplayed()
        // Ein bestaetigter Marker hat kein "Confirm".
        compose.onNodeWithText("Confirm").assertDoesNotExist()

        compose.onNodeWithText("Choose as DropSync target").performClick()
        assertEquals(1, target)

        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `gesetztes ziel zeigt den chip und das entfernen`() {
        showSheet(marker = marker(), isTarget = true)

        compose.onNodeWithText("Active target").assertIsDisplayed()
        compose.onNodeWithText("Remove target").assertIsDisplayed()
    }

    @Test
    fun `alle marker eines songs stehen als textliste bereit`() {
        var listened: SongMarker? = null
        showSheet(
            marker = marker(id = 1L),
            allMarkers = listOf(marker(id = 1L, positionMs = 42_000L), marker(id = 2L, positionMs = 61_000L)),
            onListenMarker = { listened = it },
        )

        compose.onNodeWithText("All markers (2)").assertIsDisplayed()
        // Jede Zeile springt mit Vorlauf zum Marker (C9).
        compose.onNodeWithText("01:01.000").performClick()
        assertEquals(61_000L, listened?.positionMs)
    }

    private fun showSheet(
        marker: SongMarker,
        isSuggestion: Boolean = false,
        isTarget: Boolean = false,
        editState: MarkerEditState = MarkerEditState.of(marker.positionMs),
        onConfirm: () -> Unit = {},
        onAdjust: (Long) -> Unit = {},
        onRevert: () -> Unit = {},
        onChooseTarget: () -> Unit = {},
        onClearTarget: () -> Unit = {},
        onDelete: () -> Unit = {},
        allMarkers: List<SongMarker> = emptyList(),
        onListenMarker: (SongMarker) -> Unit = {},
    ) {
        compose.setContent {
            FlowRepTheme {
                // Der Inhalt ist im echten Sheet scrollbar gerahmt; hier
                // bekommt er eine scrollbare Spalte, damit alle Aktionen
                // auch auf kleinen Testbildschirmen komponiert werden.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MarkerSheetContent(
                        marker = marker,
                        isSuggestion = isSuggestion,
                        isTarget = isTarget,
                        editState = editState,
                        onListen = {},
                        onAdjust = onAdjust,
                        onRevert = onRevert,
                        onChooseTarget = onChooseTarget,
                        onClearTarget = onClearTarget,
                        onConfirm = onConfirm,
                        onRenameRequest = {},
                        onDelete = onDelete,
                        allMarkers = allMarkers,
                        onListenMarker = onListenMarker,
                    )
                }
            }
        }
    }

    private fun marker(
        id: Long = 1L,
        positionMs: Long = 42_000L,
        source: MarkerSource = MarkerSource.AUTO_DETECTED,
    ) = SongMarker(
        id = id,
        label = "Drop",
        positionMs = positionMs,
        source = source,
        isEnabled = true,
        linkedSongId = 7L,
    )
}
