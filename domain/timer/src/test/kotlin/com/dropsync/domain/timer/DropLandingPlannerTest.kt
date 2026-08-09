package com.dropsync.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation Musik-Workout-Plan Phase 3: die Drop-Landung timt einen
 * Work-Titel so, dass sein Drop das Pausenende trifft — beide Faelle
 * (Drop spaeter/frueher als Restzeit), Fallbacks und Titelwahl.
 */
class DropLandingPlannerTest {
    private fun drop(
        songId: Long = 1L,
        dropMs: Long,
        durationMs: Long = 300_000L,
        markerId: Long = songId * 10,
    ) = WorkSongDrop(songId = songId, dropPositionMs = dropMs, durationMs = durationMs, markerId = markerId)

    @Test
    fun `Drop spaeter als Restzeit spult vor und startet sofort`() {
        // R = 20 s, D = 60 s: sofort starten, auf 40 s vorspulen.
        val result = DropLandingPlanner.plan(20_000L, listOf(drop(dropMs = 60_000L)))

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(40_000L, plan.startAtPositionMs)
        assertEquals(0L, plan.startAfterDelayMs)
    }

    @Test
    fun `Drop frueher als Restzeit verzoegert den Start`() {
        // R = 30 s, D = 12 s: erst 18 s Pausenmusik, dann Titel von vorn.
        val result = DropLandingPlanner.plan(30_000L, listOf(drop(dropMs = 12_000L)))

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(0L, plan.startAtPositionMs)
        assertEquals(18_000L, plan.startAfterDelayMs)
    }

    @Test
    fun `zu kurze Restzeit ist nicht moeglich`() {
        val result = DropLandingPlanner.plan(3_000L, listOf(drop(dropMs = 60_000L)))

        assertEquals(
            DropLandingReason.REST_TOO_SHORT,
            (result as DropLandingResult.NotPossible).reason,
        )
    }

    @Test
    fun `ohne brauchbaren Drop ist nicht moeglich`() {
        val result = DropLandingPlanner.plan(20_000L, emptyList())

        assertEquals(
            DropLandingReason.NO_WORK_SONG_WITH_DROP,
            (result as DropLandingResult.NotPossible).reason,
        )
    }

    @Test
    fun `Drop ausserhalb der Songdauer wird uebersprungen`() {
        // Erster Kandidat hat einen Drop hinter dem Songende und wird
        // verworfen; der zweite (brauchbare) wird gewaehlt.
        val result =
            DropLandingPlanner.plan(
                10_000L,
                listOf(
                    drop(songId = 1L, dropMs = 400_000L, durationMs = 300_000L),
                    drop(songId = 2L, dropMs = 50_000L, durationMs = 300_000L),
                ),
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(2L, plan.songId)
        assertEquals(40_000L, plan.startAtPositionMs)
    }

    @Test
    fun `erster brauchbarer Kandidat wird gewaehlt`() {
        val result =
            DropLandingPlanner.plan(
                10_000L,
                listOf(
                    drop(songId = 5L, dropMs = 30_000L, markerId = 55L),
                    drop(songId = 6L, dropMs = 40_000L, markerId = 66L),
                ),
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(5L, plan.songId)
        assertEquals(55L, plan.markerId)
        assertTrue(plan.startAtPositionMs == 20_000L)
    }
}
