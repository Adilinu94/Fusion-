package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Verifikation Musik-Workout-Plan A5: die Gewichtung bevorzugt Favoriten,
 * meidet zuletzt Gespielte und liefert eine vollstaendige, deterministische
 * Reihenfolge (fester [Random]).
 */
class SmartShuffleTest {
    private val now = 1_000_000_000L

    @Test
    fun `favorit wiegt schwerer als nicht-favorit bei sonst gleichen werten`() {
        val fav = candidate(1L, isFavorite = true)
        val plain = candidate(2L, isFavorite = false)

        assertTrue(SmartShuffle.weightOf(fav, now) > SmartShuffle.weightOf(plain, now))
    }

    @Test
    fun `gerade gespielt wiegt weniger als lange nicht gespielt`() {
        val justPlayed = candidate(1L, lastPlayedAtEpochMs = now - 1_000L)
        val longAgo = candidate(2L, lastPlayedAtEpochMs = now - SmartShuffle.RECENCY_WINDOW_MS * 2)

        assertTrue(SmartShuffle.weightOf(justPlayed, now) < SmartShuffle.weightOf(longAgo, now))
    }

    @Test
    fun `nie gespielt bekommt vollen aktualitaets-faktor`() {
        val never = candidate(1L, lastPlayedAtEpochMs = null, playCount = 0)
        // Ohne Favorit und ohne Wiedergaben ist das Gewicht exakt 1.0.
        assertEquals(1.0, SmartShuffle.weightOf(never, now), 1e-9)
    }

    @Test
    fun `order liefert jede id genau einmal`() {
        val candidates = (1L..20L).map { candidate(it) }

        val order = SmartShuffle.order(candidates, now, Random(42))

        assertEquals(candidates.map { it.songId }.toSet(), order.toSet())
        assertEquals(20, order.size)
    }

    @Test
    fun `order ist bei gleichem seed deterministisch`() {
        val candidates = (1L..10L).map { candidate(it, playCount = it.toInt()) }

        val a = SmartShuffle.order(candidates, now, Random(7))
        val b = SmartShuffle.order(candidates, now, Random(7))

        assertEquals(a, b)
    }

    @Test
    fun `leere eingabe ergibt leere reihenfolge`() {
        assertEquals(emptyList<Long>(), SmartShuffle.order(emptyList(), now))
    }

    private fun candidate(
        songId: Long,
        playCount: Int = 5,
        lastPlayedAtEpochMs: Long? = now - SmartShuffle.RECENCY_WINDOW_MS,
        isFavorite: Boolean = false,
    ) = ShuffleCandidate(
        songId = songId,
        playCount = playCount,
        lastPlayedAtEpochMs = lastPlayedAtEpochMs,
        isFavorite = isFavorite,
    )
}
