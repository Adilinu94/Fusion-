package com.dropsync.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C16 (5.16-5.22): Die Kettenplanung fuellt die Restzeit mit der aktuellen
 * Wiedergabe-Liste, verwirft Fragmente, plant hoechstens zwei Songs nach
 * dem laufenden und landet deterministisch auf einem Drop.
 */
class DropChainPlannerTest {
    private fun candidate(
        songId: Long,
        durationMs: Long = 300_000L,
        positionMs: Long = 0L,
        dropMs: Long? = null,
        markerId: Long? = null,
    ) = ChainCandidate(
        songId = songId,
        durationMs = durationMs,
        positionMs = positionMs,
        dropPositionMs = dropMs,
        markerId = markerId ?: dropMs?.let { songId * 10 },
    )

    private fun planned(result: DropChainResult): DropChain {
        assertTrue("Kette erwartet, war $result", result is DropChainResult.Planned)
        return (result as DropChainResult.Planned).chain
    }

    @Test
    fun `fuellt die restzeit mit der queue`() {
        // R = 240 s; laufender Titel 60 s, Queue [B(100 s Drop), C(60 s
        // Drop)]: Landung ist der letzte Titel mit Drop (C, Intro 60 s),
        // B wechselt auf seinem Drop, die Restluecke deckt der laufende
        // Titel ab.
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 240_000L,
                currentSong = candidate(songId = 1L, durationMs = 60_000L),
                queue = listOf(candidate(songId = 2L, dropMs = 100_000L), candidate(songId = 3L, dropMs = 60_000L)),
            )

        val chain = planned(result)
        assertEquals(listOf(1L, 2L, 3L), chain.segments.map { it.songId })
        assertEquals(240_000L, chain.segments.sumOf { it.playForMs })
        assertEquals(180_000L, chain.landing?.startsAfterMs)
        assertEquals(60_000L, chain.landing?.playForMs)
        assertEquals(80_000L, chain.segments[0].playForMs)
        assertEquals(80_000L, chain.segments[1].startsAfterMs)
        assertEquals(100_000L, chain.segments[1].playForMs)
    }

    @Test
    fun `kein segment kuerzer als minSegmentMs`() {
        // R = 110 s, laufender Titel 70 s, Landung nach 80 s; der Queue-
        // Titel bekaeme nur 10 s (< 50 s) -> er wird verworfen und der
        // laufende Titel deckt den Rest mit ab.
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 110_000L,
                currentSong = candidate(songId = 1L, durationMs = 70_000L),
                queue = listOf(candidate(songId = 2L), candidate(songId = 3L, dropMs = 30_000L)),
                minSegmentMs = 50_000L,
            )

        val chain = planned(result)
        assertEquals(listOf(1L, 3L), chain.segments.map { it.songId })
        assertEquals(80_000L, chain.segments[0].playForMs)
        assertTrue(
            chain.segments
                .filter { it.kind == ChainSegmentKind.FILLER }
                .all { it.playForMs >= 50_000L },
        )
    }

    @Test
    fun `hoechstens zwei geplante songs`() {
        // Queue [F1, L(30 s), F3(10 s)]: das Fenster reicht bis L; F1 ist
        // Fueller, L die Landung -> genau zwei geplante Queue-Songs.
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 200_000L,
                currentSong = null,
                queue =
                    listOf(
                        candidate(songId = 1L),
                        candidate(songId = 2L, dropMs = 30_000L),
                        candidate(songId = 3L, dropMs = 10_000L),
                    ),
            )

        val chain = planned(result)
        assertEquals(2, chain.plannedSongs)
        assertEquals(listOf(1L, 2L), chain.segments.map { it.songId })
        assertEquals(ChainSegmentKind.LANDING, chain.segments.last().kind)
    }

    @Test
    fun `letztes segment landet auf dem drop`() {
        // Die Landung startet so, dass Drop + Latenz + Crossfade genau am
        // Pausenende enden (bestehende Landungsmathematik).
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 240_000L,
                currentSong = candidate(songId = 1L),
                queue = listOf(candidate(songId = 2L, dropMs = 100_000L)),
                latencyMs = 200L,
                crossfadeMs = 3_000L,
            )

        val landing = planned(result).landing ?: error("Landung fehlt")
        assertEquals(0L, landing.startAtPositionMs)
        assertEquals(136_800L, landing.startsAfterMs)
        // Drop-Zeitpunkt: Start + Drop muss am Pausenende minus Latenz
        // und Crossfade liegen (der hoerbare Drop trifft das Ende).
        assertEquals(
            240_000L - 200L - 3_000L,
            landing.startsAfterMs + 100_000L,
        )
    }

    @Test
    fun `laufender titel bleibt als landung`() {
        // 5.18: Drop des laufenden Titels am/ab dem Pausenende -> kein
        // Wechsel, Direktsprung beim Go.
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 240_000L,
                currentSong = candidate(songId = 1L, dropMs = 300_000L),
                queue = listOf(candidate(songId = 2L, dropMs = 100_000L)),
            )

        val chain = planned(result)
        assertEquals(1, chain.segments.size)
        val landing = chain.segments.single()
        assertEquals(1L, landing.songId)
        assertEquals(ChainSegmentKind.LANDING, landing.kind)
        assertEquals(300_000L, landing.startAtPositionMs)
        assertEquals(0L, landing.startsAfterMs)
    }

    @Test
    fun `fueller-wechsel liegt auf dem drop`() {
        // 5.20: F1 hat einen Drop bei 50 s im erlaubten Fenster -> der
        // Wechsel zur Landung liegt auf diesem Drop, nicht am Segmentende
        // (F1 ist 300 s lang).
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 240_000L,
                currentSong = candidate(songId = 1L, durationMs = 150_000L),
                queue =
                    listOf(
                        candidate(songId = 2L, durationMs = 300_000L, dropMs = 50_000L),
                        candidate(songId = 3L, dropMs = 40_000L),
                    ),
            )

        val chain = planned(result)
        val filler = chain.segments[1]
        assertEquals(2L, filler.songId)
        assertEquals(150_000L, filler.startsAfterMs)
        assertEquals(50_000L, filler.playForMs)
        assertEquals(200_000L, chain.landing?.startsAfterMs)
    }

    @Test
    fun `kurze pausen ergeben keine kette`() {
        val result =
            DropChainPlanner.plan(
                remainingRestMs = DropLandingPlanner.MIN_DROP_AUTO_REST_MS - 1_000L,
                currentSong = candidate(songId = 1L),
                queue = listOf(candidate(songId = 2L, dropMs = 30_000L)),
            )

        assertEquals(DropChainResult.NotPossible(DropLandingReason.REST_TOO_SHORT), result)
    }

    @Test
    fun `ohne drop in der queue gibt es keine kette`() {
        val result =
            DropChainPlanner.plan(
                remainingRestMs = 240_000L,
                currentSong = candidate(songId = 1L),
                queue = listOf(candidate(songId = 2L), candidate(songId = 3L)),
            )

        assertEquals(DropChainResult.NotPossible(DropLandingReason.NO_WORK_SONG_WITH_DROP), result)
    }

    @Test
    fun `deterministisch bei gleichen eingaben`() {
        val current = candidate(songId = 1L)
        val queue =
            listOf(
                candidate(songId = 2L, dropMs = 50_000L),
                candidate(songId = 3L, dropMs = 90_000L),
            )

        val first = DropChainPlanner.plan(240_000L, current, queue)
        val second = DropChainPlanner.plan(240_000L, current, queue)

        assertEquals(first, second)
    }
}
