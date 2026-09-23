package com.dropsync.feature.workout

import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.TimingConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-10: "Ziel in mm:ss" in der Train-Konsole. Die Landung am Pausenende
 * folgt der tickenden Timer-Restzeit; der manuelle DropRest der
 * Marker-Projektion des Monitors. Kein Plan -> keine Zielzeit.
 */
class DropTargetRemainingTest {
    private fun planned(
        mode: DropSyncMode,
        remainingMs: Long,
    ) = DropSyncState.Planned(
        songTitle = "Track",
        markerLabel = "Drop 2",
        targetElapsedRealtimeMs = 10_000L,
        remainingMs = remainingMs,
        confidence = TimingConfidence.EXACT,
        mode = mode,
    )

    @Test
    fun `landung am pausenende folgt der timer-restzeit`() {
        val state = planned(DropSyncMode.LANDING_AT_REST_END, remainingMs = 7_000L)
        assertEquals(42_000L, dropTargetRemainingMs(state, restRemainingMs = 42_000L))
    }

    @Test
    fun `manueller droprest folgt der marker-projektion`() {
        val state = planned(DropSyncMode.UNTIL_MARKER, remainingMs = 7_000L)
        assertEquals(7_000L, dropTargetRemainingMs(state, restRemainingMs = 42_000L))
    }

    @Test
    fun `armierter plan nutzt den modus des eingefrorenen plans`() {
        val untilMarker =
            DropSyncState.Armed(
                planned(DropSyncMode.UNTIL_MARKER, 5_000L),
                token = 1L,
                audioPrepared = true,
            )
        assertEquals(5_000L, dropTargetRemainingMs(untilMarker, restRemainingMs = 42_000L))
        val atRestEnd =
            DropSyncState.Armed(
                planned(DropSyncMode.LANDING_AT_REST_END, 5_000L),
                token = 2L,
                audioPrepared = false,
            )
        assertEquals(42_000L, dropTargetRemainingMs(atRestEnd, restRemainingMs = 42_000L))
    }

    @Test
    fun `ohne plan gibt es keine zielzeit`() {
        assertNull(dropTargetRemainingMs(DropSyncState.Off, restRemainingMs = 42_000L))
        assertNull(dropTargetRemainingMs(DropSyncState.Cancelled, restRemainingMs = 42_000L))
        assertNull(
            dropTargetRemainingMs(
                DropSyncState.BestEffort(BestEffortReason.WATCHDOG),
                restRemainingMs = 42_000L,
            ),
        )
    }

    @Test
    fun `plan abbrechen nur bei einer landung am pausenende`() {
        assertTrue(dropSyncCanCancelPlan(planned(DropSyncMode.LANDING_AT_REST_END, 5_000L)))
        assertFalse(dropSyncCanCancelPlan(planned(DropSyncMode.UNTIL_MARKER, 5_000L)))
        assertFalse(dropSyncCanCancelPlan(DropSyncState.Off))
        assertFalse(dropSyncCanCancelPlan(DropSyncState.BestEffort(BestEffortReason.WATCHDOG)))
        assertFalse(dropSyncCanCancelPlan(DropSyncState.Cancelled))
    }
}
