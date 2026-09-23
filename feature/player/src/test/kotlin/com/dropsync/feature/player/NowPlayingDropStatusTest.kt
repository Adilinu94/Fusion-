package com.dropsync.feature.player

import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.OverrideReason
import com.dropsync.domain.timer.TimingConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-21 (MP-7): Statuszeile unter der Now-Playing-Waveform. Der Countdown
 * kommt aus dem Deadline des Plans und der monotonen Uhr, damit die Zeile
 * ohne Rest-Timer tickt; stumme Zustaende liefern null.
 */
class NowPlayingDropStatusTest {
    private fun planned(
        targetElapsedRealtimeMs: Long = 100_000L,
        songTitle: String = "Track",
        markerLabel: String = "Drop 2",
    ) = DropSyncState.Planned(
        songTitle = songTitle,
        markerLabel = markerLabel,
        targetElapsedRealtimeMs = targetElapsedRealtimeMs,
        remainingMs = 30_000L,
        confidence = TimingConfidence.EXACT,
        mode = DropSyncMode.LANDING_AT_REST_END,
    )

    @Test
    fun `geplanter plan zeigt track marker und countdown`() {
        val line = dropStatusLine(planned(), nowElapsedRealtimeMs = 70_000L)

        assertTrue(line is DropStatusLine.Ready)
        val ready = line as DropStatusLine.Ready
        assertEquals("Track", ready.songTitle)
        assertEquals("Drop 2", ready.markerLabel)
        assertEquals(30_000L, ready.remainingMs)
    }

    @Test
    fun `armierter plan nutzt den eingefrorenen plan`() {
        val state = DropSyncState.Armed(planned(), token = 7L, audioPrepared = true)

        val ready = dropStatusLine(state, nowElapsedRealtimeMs = 95_000L) as DropStatusLine.Ready
        assertEquals(5_000L, ready.remainingMs)
    }

    @Test
    fun `C16 kette erreicht die statuszeile`() {
        // 5.22: Die Kette steht in Konsole und Mini-Player (beide lesen
        // dieselbe Statuszeile).
        val state =
            DropSyncState.Planned(
                songTitle = "Drop",
                markerLabel = "Drop 3",
                targetElapsedRealtimeMs = 100_000L,
                remainingMs = 60_000L,
                confidence = TimingConfidence.EXACT,
                mode = DropSyncMode.LANDING_AT_REST_END,
                chain = listOf("A", "B", "Drop"),
            )

        val ready = dropStatusLine(state, nowElapsedRealtimeMs = 40_000L) as DropStatusLine.Ready
        assertEquals(listOf("A", "B", "Drop"), ready.chain)
        assertEquals(60_000L, ready.remainingMs)
    }

    @Test
    fun `verstrichene zielzeit klemmt auf null`() {
        val ready = dropStatusLine(planned(), nowElapsedRealtimeMs = 120_000L) as DropStatusLine.Ready
        assertEquals(0L, ready.remainingMs)
    }

    @Test
    fun `best effort nennt den grund`() {
        val line =
            dropStatusLine(
                DropSyncState.BestEffort(BestEffortReason.ROUTE_CHANGED),
                nowElapsedRealtimeMs = 0L,
            )
        assertEquals(DropStatusLine.BestEffort(BestEffortReason.ROUTE_CHANGED), line)
    }

    @Test
    fun `uebernommener plan bleibt sichtbar`() {
        val line =
            dropStatusLine(
                DropSyncState.Overridden(OverrideReason.SEEK),
                nowElapsedRealtimeMs = 0L,
            )
        assertEquals(DropStatusLine.Overridden, line)
    }

    @Test
    fun `stumme zustaende liefern keine zeile`() {
        assertNull(dropStatusLine(DropSyncState.Off, nowElapsedRealtimeMs = 0L))
        assertNull(dropStatusLine(DropSyncState.Cancelled, nowElapsedRealtimeMs = 0L))
        assertNull(dropStatusLine(DropSyncState.Landed(1L, 0L), nowElapsedRealtimeMs = 0L))
    }

    @Test
    fun `fehlschlag nennt den grund statt stumm zu bleiben`() {
        val line =
            dropStatusLine(
                DropSyncState.Failed(DropSyncFailureReason.NO_WORK_DROP),
                nowElapsedRealtimeMs = 0L,
            )
        assertEquals(DropStatusLine.Failed(DropSyncFailureReason.NO_WORK_DROP), line)
    }

    @Test
    fun `plan verloren liefert eine quittierbare zeile`() {
        val line =
            dropStatusLine(
                DropSyncState.Failed(DropSyncFailureReason.PLAN_LOST),
                nowElapsedRealtimeMs = 0L,
            )
        assertEquals(DropStatusLine.Failed(DropSyncFailureReason.PLAN_LOST), line)
    }
}
