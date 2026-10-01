package com.dropsync.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation Musik-Workout-Plan Phase 3 + Design Phase 6: die
 * Drop-Landung timt einen Work-Titel so, dass sein Drop das Pausenende
 * trifft — INTRO (Drop vor Go), DIRECT_TO_DROP (Drop hinter Go),
 * Latenz-Abzug, Crossfade-Vorlauf, Markerwahl Entscheidung 37.
 */
class DropLandingPlannerTest {
    private fun drop(
        songId: Long = 1L,
        dropMs: Long,
        durationMs: Long = 300_000L,
        markerId: Long = songId * 10,
    ) = WorkSongDrop(songId = songId, dropPositionMs = dropMs, durationMs = durationMs, markerId = markerId)

    // --- Build-up-Vorlauf (leadInMs) ----------------------------------------

    @Test
    fun `Direktsprung mit Vorlauf steigt vor dem Drop ein und der Drop faellt trotzdem aufs Go`() {
        val rest = 60_000L
        val latency = 120L
        val plan =
            (
                DropLandingPlanner.plan(
                    remainingRestMs = rest,
                    candidates = listOf(drop(dropMs = 90_000L)),
                    latencyMs = latency,
                    leadInMs = 6_000L,
                ) as DropLandingResult.Scheduled
            ).plan
        assertEquals(DropLandingPlan.Kind.DIRECT_TO_DROP, plan.kind)
        assertEquals(6_000L, plan.leadInMs)
        assertEquals(84_000L, plan.startAtPositionMs)
        assertEquals(rest - 6_000L - latency, plan.startAfterDelayMs)
        // Drop-Zeitpunkt: Start + (Drop - Startposition) = Go - Latenz (die Latenz ist abgezogen)
        assertEquals(rest - latency, plan.startAfterDelayMs + (90_000L - plan.startAtPositionMs))
    }

    @Test
    fun `Vorlauf ohne Wirkung bei 0 - Direktsprung wie bisher`() {
        val plan = (DropLandingPlanner.plan(20_000L, listOf(drop(dropMs = 60_000L))) as DropLandingResult.Scheduled).plan
        assertEquals(0L, plan.leadInMs)
        assertEquals(60_000L, plan.startAtPositionMs)
        assertEquals(20_000L, plan.startAfterDelayMs)
    }

    @Test
    fun `Vorlauf ist auf ein Viertel der Restzeit begrenzt`() {
        val plan =
            (
                DropLandingPlanner.plan(
                    remainingRestMs = 60_000L,
                    candidates = listOf(drop(dropMs = 120_000L)),
                    leadInMs = 40_000L,
                ) as DropLandingResult.Scheduled
            ).plan
        assertEquals(15_000L, plan.leadInMs)
        assertEquals(105_000L, plan.startAtPositionMs)
        assertEquals(45_000L, plan.startAfterDelayMs)
    }

    @Test
    fun `Vorlauf geht nie vor Position 0`() {
        val plan =
            (
                DropLandingPlanner.plan(
                    remainingRestMs = 60_000L,
                    candidates = listOf(drop(dropMs = 61_000L)),
                    leadInMs = 6_000L,
                ) as DropLandingResult.Scheduled
            ).plan
        assertEquals(6_000L, plan.leadInMs)
        val early =
            (
                DropLandingPlanner.plan(
                    remainingRestMs = 60_000L,
                    candidates = listOf(drop(dropMs = 61_000L)),
                    leadInMs = 80_000L,
                ) as DropLandingResult.Scheduled
            ).plan
        assertTrue("Startposition >= 0", early.startAtPositionMs >= 0L)
        assertTrue("Vorlauf <= Drop", early.leadInMs <= 61_000L)
    }

    @Test
    fun `Intro-Landung ignoriert den Vorlauf`() {
        val plan =
            (
                DropLandingPlanner.plan(
                    remainingRestMs = 60_000L,
                    candidates = listOf(drop(dropMs = 20_000L)),
                    leadInMs = 6_000L,
                ) as DropLandingResult.Scheduled
            ).plan
        assertEquals(DropLandingPlan.Kind.INTRO, plan.kind)
        assertEquals(0L, plan.leadInMs)
    }

    @Test
    fun `Verspaetete Armierung spult hoechstens um den Vorlauf vor`() {
        val withLead =
            (
                DropLandingPlanner.plan(60_000L, listOf(drop(dropMs = 90_000L)), leadInMs = 6_000L)
                    as DropLandingResult.Scheduled
            ).plan
        assertEquals(84_000L + 2_000L, withLead.startPositionAfterLate(2_000L))
        assertEquals("nicht ueber den Drop hinaus", 90_000L, withLead.startPositionAfterLate(20_000L))
        assertEquals("nie zurueck", 84_000L, withLead.startPositionAfterLate(-5L))

        val noLead = (DropLandingPlanner.plan(20_000L, listOf(drop(dropMs = 60_000L))) as DropLandingResult.Scheduled).plan
        assertEquals("ohne Vorlauf wie bisher: Direktsprung", 60_000L, noLead.startPositionAfterLate(2_000L))

        val intro = (DropLandingPlanner.plan(60_000L, listOf(drop(dropMs = 20_000L))) as DropLandingResult.Scheduled).plan
        assertEquals("Intro spult um die Verspaetung vor", intro.startAtPositionMs + 2_000L, intro.startPositionAfterLate(2_000L))
    }

    @Test
    fun `Drop hinter dem Go springt direkt zum Drop`() {
        // R = 20 s, D = 60 s: Rest-Musik laeuft die volle Restzeit,
        // beim Go springt der Player direkt zum Drop (kein Vorspulen).
        val result = DropLandingPlanner.plan(20_000L, listOf(drop(dropMs = 60_000L)))

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(DropLandingPlan.Kind.DIRECT_TO_DROP, plan.kind)
        assertEquals(60_000L, plan.startAtPositionMs)
        assertEquals(20_000L, plan.startAfterDelayMs)
    }

    @Test
    fun `Drop vor dem Go startet mit Intro und Crossfade-Vorlauf`() {
        // R = 30 s, D = 12 s, Xfade 3 s: Work startet 15 s vor dem Go
        // (30 - 12 - 3), der Crossfade endet vor dem Drop.
        val result =
            DropLandingPlanner.plan(
                30_000L,
                listOf(drop(dropMs = 12_000L)),
                crossfadeMs = 3_000L,
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(DropLandingPlan.Kind.INTRO, plan.kind)
        assertEquals(0L, plan.startAtPositionMs)
        assertEquals(15_000L, plan.startAfterDelayMs)
        assertEquals(3_000L, plan.crossfadeMs)
    }

    @Test
    fun `Latenz wird vom Go abgezogen`() {
        // R = 30 s, D = 12 s, L = 200 ms: Work startet 17.8 s vor dem Go,
        // damit der hoerbare Drop das Pausenende trifft.
        val result =
            DropLandingPlanner.plan(
                30_000L,
                listOf(drop(dropMs = 12_000L)),
                latencyMs = 200L,
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(17_800L, plan.startAfterDelayMs)
    }

    @Test
    fun `Markerwahl nach Entscheidung 37 - kleinster Abstand zur Restzeit`() {
        // R = 10 s, Kandidaten: D=30 s (Abstand 20), D=12 s (Abstand 2).
        // Der Planner muss den D=12 s-Kandidaten waehlen, nicht den ersten.
        val result =
            DropLandingPlanner.plan(
                10_000L,
                listOf(
                    drop(songId = 5L, dropMs = 30_000L, markerId = 55L),
                    drop(songId = 6L, dropMs = 12_000L, markerId = 66L),
                ),
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(6L, plan.songId)
        assertEquals(66L, plan.markerId)
    }

    @Test
    fun `gleicher Abstand bevorzugt Drop vor dem Go`() {
        // R = 20 s: D=18 s (Abstand 2, vor Go) vs D=22 s (Abstand 2,
        // hinter Go). Beide gleich nah -> der vor dem Go gewinnt (INTRO
        // laeuft sauberer als ein Sprung).
        val result =
            DropLandingPlanner.plan(
                20_000L,
                listOf(
                    drop(songId = 7L, dropMs = 22_000L),
                    drop(songId = 8L, dropMs = 18_000L),
                ),
            )

        val plan = (result as DropLandingResult.Scheduled).plan
        assertEquals(8L, plan.songId)
        assertEquals(DropLandingPlan.Kind.INTRO, plan.kind)
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
        assertTrue(plan.kind == DropLandingPlan.Kind.DIRECT_TO_DROP)
    }
}
