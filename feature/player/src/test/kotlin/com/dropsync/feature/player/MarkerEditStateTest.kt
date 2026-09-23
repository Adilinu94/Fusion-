package com.dropsync.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-21 (UI-Handbuch 14.5): Feinjustierung behaelt die Originalposition;
 * jeder Schritt ist geklemmt, "Zurueck" stellt exakt wieder her.
 */
class MarkerEditStateTest {
    @Test
    fun `startzustand ist nicht schmutzig`() {
        val state = MarkerEditState.of(78_420L)

        assertFalse(state.isDirty)
        assertEquals(78_420L, state.editedPositionMs)
    }

    @Test
    fun `positiver und negativer schritt aendern die position`() {
        val state =
            MarkerEditState
                .of(78_420L)
                .adjustedBy(-100L, durationMs = 200_000L)
                .adjustedBy(+10L, durationMs = 200_000L)

        assertEquals(78_330L, state.editedPositionMs)
        assertTrue(state.isDirty)
    }

    @Test
    fun `klemmt an den trackgrenzen`() {
        val atStart = MarkerEditState.of(50L).adjustedBy(-100L, durationMs = 200_000L)
        val atEnd = MarkerEditState.of(199_950L).adjustedBy(+100L, durationMs = 200_000L)

        assertEquals(0L, atStart.editedPositionMs)
        assertEquals(200_000L, atEnd.editedPositionMs)
    }

    @Test
    fun `zurueck stellt die originalposition exakt wieder her`() {
        val state = MarkerEditState.of(78_420L).adjustedBy(+100L, durationMs = 200_000L)

        val reverted = state.reverted()

        assertFalse(reverted.isDirty)
        assertEquals(78_420L, reverted.editedPositionMs)
        assertEquals(78_420L, reverted.originalPositionMs)
    }

    @Test
    fun `snap behaelt die originalposition fuer den rueckweg`() {
        // B4: automatische Rastung beim Oeffnen; "Zurueck auf Original"
        // macht sie rueckgaengig.
        val snapped = MarkerEditState.of(1_010L).snappedTo(1_000L)

        assertTrue(snapped.isDirty)
        assertEquals(1_000L, snapped.editedPositionMs)
        assertEquals(1_010L, snapped.originalPositionMs)

        val reverted = snapped.reverted()
        assertFalse(reverted.isDirty)
        assertEquals(1_010L, reverted.editedPositionMs)
    }

    @Test
    fun `snap auf dieselbe position bleibt sauber`() {
        val snapped = MarkerEditState.of(1_000L).snappedTo(1_000L)

        assertFalse(snapped.isDirty)
    }
}
