package com.dropsync.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisGateTest {
    /** Merkt sich Ein-/Austritte der Prioritaetsabsenkung. */
    private class RecordingPriority : ThreadPriority {
        val events = mutableListOf<String>()

        override suspend fun <T> runAtBackground(block: suspend () -> T): T {
            events += "low"
            try {
                return block()
            } finally {
                events += "restore"
            }
        }
    }

    @Test
    fun `Hintergrundlaeufe laufen nacheinander, nicht parallel`() =
        runTest {
            val gate = AnalysisGate(parallelism = 1, priority = RecordingPriority())
            var active = 0
            var peak = 0

            val jobs =
                (1..6).map {
                    async {
                        gate.background {
                            active++
                            peak = maxOf(peak, active)
                            delay(10)
                            active--
                        }
                    }
                }
            jobs.awaitAll()

            assertEquals("nie mehr als ein Lauf zugleich", 1, peak)
        }

    @Test
    fun `die Parallelitaet ist einstellbar`() =
        runTest {
            val gate = AnalysisGate(parallelism = 2, priority = RecordingPriority())
            var active = 0
            var peak = 0

            (1..8)
                .map {
                    async {
                        gate.background {
                            active++
                            peak = maxOf(peak, active)
                            delay(10)
                            active--
                        }
                    }
                }.awaitAll()

            assertEquals(2, peak)
        }

    @Test
    fun `die Prioritaet wird abgesenkt und danach wiederhergestellt`() =
        runTest {
            val priority = RecordingPriority()
            val gate = AnalysisGate(priority = priority)

            val result = gate.background { 42 }

            assertEquals(42, result)
            assertEquals(listOf("low", "restore"), priority.events)
        }

    @Test
    fun `die Prioritaet wird auch nach einem Fehler wiederhergestellt und der Platz wird frei`() =
        runTest {
            val priority = RecordingPriority()
            val gate = AnalysisGate(parallelism = 1, priority = priority)

            try {
                gate.background { error("Decoder kaputt") }
                fail("Fehler erwartet")
            } catch (expected: IllegalStateException) {
                assertEquals("Decoder kaputt", expected.message)
            }
            val next = gate.background { "weiter" }

            assertEquals("weiter", next)
            assertEquals(listOf("low", "restore", "low", "restore"), priority.events)
        }

    @Test
    fun `ein abgebrochener Lauf gibt Platz und Prioritaet frei`() =
        runTest {
            val priority = RecordingPriority()
            val gate = AnalysisGate(parallelism = 1, priority = priority)

            val running = launch { gate.background { delay(1_000) } }
            advanceUntilIdle()
            running.cancel(CancellationException("Titel gewechselt"))
            advanceUntilIdle()
            val next = gate.background { "frei" }

            assertEquals("frei", next)
            assertTrue(
                "Absenkung und Wiederherstellung sind ausgeglichen",
                priority.events.count { it == "low" } == priority.events.count { it == "restore" },
            )
        }
}
