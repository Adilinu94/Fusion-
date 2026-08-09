package com.dropsync.domain.timer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deckt jeden erlaubten und verbotenen Uebergang ab (Abnahme Schritt 7). */
class TimerTransitionsTest {
    private val allowedPairs =
        setOf(
            TimerStatus.IDLE to TimerStatus.PREPARING,
            TimerStatus.PREPARING to TimerStatus.RUNNING,
            TimerStatus.PREPARING to TimerStatus.FAILED,
            TimerStatus.PREPARING to TimerStatus.CANCELLED,
            TimerStatus.RUNNING to TimerStatus.PAUSED,
            TimerStatus.RUNNING to TimerStatus.COMPLETED,
            TimerStatus.RUNNING to TimerStatus.CANCELLED,
            TimerStatus.RUNNING to TimerStatus.FAILED,
            TimerStatus.PAUSED to TimerStatus.RUNNING,
            TimerStatus.PAUSED to TimerStatus.CANCELLED,
        )

    @Test
    fun `jede kombination entspricht exakt der bauplan matrix`() {
        for (from in TimerStatus.entries) {
            for (to in TimerStatus.entries) {
                val expected = (from to to) in allowedPairs
                val actual = TimerTransitions.isAllowed(from, to)
                assertTrue(
                    "Uebergang $from -> $to: erwartet=$expected, war=$actual",
                    expected == actual,
                )
            }
        }
    }

    @Test
    fun `nur endzustaende sind resettable`() {
        assertTrue(TimerTransitions.isResettable(TimerStatus.COMPLETED))
        assertTrue(TimerTransitions.isResettable(TimerStatus.CANCELLED))
        assertTrue(TimerTransitions.isResettable(TimerStatus.FAILED))
        assertFalse(TimerTransitions.isResettable(TimerStatus.IDLE))
        assertFalse(TimerTransitions.isResettable(TimerStatus.PREPARING))
        assertFalse(TimerTransitions.isResettable(TimerStatus.RUNNING))
        assertFalse(TimerTransitions.isResettable(TimerStatus.PAUSED))
    }
}

class CuePlannerTest {
    @Test
    fun `nur grenzwerte unter der startdauer werden geplant`() {
        val cues = CuePlanner.plan(TimerMode.NORMAL, durationMs = 45_000)
        val thresholds = cues.map { it.thresholdMs }
        // 45 s: 180/120/60 fallen weg; 30, 10..1 und 0 bleiben.
        assertTrue(30_000L in thresholds)
        assertFalse(60_000L in thresholds)
        assertTrue(thresholds.containsAll((1..10).map { it * 1_000L }))
        assertTrue(0L in thresholds)
    }

    @Test
    fun `normal und rest sprechen alle grenzwerte`() {
        for (mode in listOf(TimerMode.NORMAL, TimerMode.REST)) {
            val cues = CuePlanner.plan(mode, durationMs = 200_000)
            assertTrue(cues.all { it.speak })
        }
    }

    @Test
    fun `dropsync spricht nur 180 120 60 30`() {
        // Verbindliche Entscheidung: DropSync spricht nur 180/120/60/30 s;
        // 10..1 s sind Haptik + visuelle Anzeige, 0 s Haptik + Ton.
        val cues = CuePlanner.plan(TimerMode.DROPSYNC, durationMs = 200_000)
        val spoken = cues.filter { it.speak }.map { it.thresholdMs }.toSet()
        assertTrue(spoken == setOf(180_000L, 120_000L, 60_000L, 30_000L))

        val finalCountdown = cues.filter { it.thresholdMs in 1_000L..10_000L }
        assertTrue(finalCountdown.all { it.haptic && !it.speak && !it.tone })

        val completion = cues.single { it.thresholdMs == 0L }
        assertTrue(completion.haptic && completion.tone && !completion.speak)
    }
}

class RebootGuardTest {
    @Test
    fun `fehlender wert verwirft den timer`() {
        assertTrue(RebootGuard.shouldDiscard(null, currentElapsedRealtimeMs = 50))
    }

    @Test
    fun `monotoner ruecksprung bedeutet reboot und verwirft`() {
        assertTrue(RebootGuard.shouldDiscard(1_000_000, currentElapsedRealtimeMs = 40_000))
    }

    @Test
    fun `inkonsistente persistenz verwirft`() {
        assertTrue(RebootGuard.shouldDiscard(-5, currentElapsedRealtimeMs = 40_000))
    }

    @Test
    fun `fortlaufende monotone uhr behaelt den timer`() {
        assertFalse(RebootGuard.shouldDiscard(40_000, currentElapsedRealtimeMs = 41_000))
    }
}
