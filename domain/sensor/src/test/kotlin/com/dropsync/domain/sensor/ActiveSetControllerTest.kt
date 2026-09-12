package com.dropsync.domain.sensor

import com.dropsync.core.testing.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Umbauplan Phase 6: der [ActiveSetController] buendelt Countdown, Engine,
 * Sample-Puffer und Abort atomar. Diese Tests schreiben die Regeln fest:
 * - Disconnect/UNRELIABLE bricht aus jedem Zustand ab
 * - kein Sample eines alten Sets gelangt in ein neues
 * - stop laesst Engine/Puffer fuer den Lernpfad stehen
 * - finishAndTakeTrace liefert unveraenderliche Kopien
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSetControllerTest {
    private val samplesFlow = MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
    private val connectionState = MutableStateFlow(SensorConnectionState.STREAMING)
    private val health = MutableStateFlow(SensorHealth(connectionState = SensorConnectionState.STREAMING))

    private fun profile(deviceId: String = "AA:BB") =
        CalibrationProfile(
            exerciseId = 1L,
            deviceId = deviceId,
            rotationAxis = listOf(1.0, 0.0, 0.0),
            gyroBias = listOf(0.0, 0.0, 0.0),
            repTemplate = List(64) { 0.0 },
            expectedProminence = 50.0,
            detectionThreshold = 15.0,
            noiseFloor = 5.0,
            expectedDurationMs = 1_000.0,
        )

    private fun TestScope.controller(clock: FakeClock = FakeClock()) =
        ActiveSetController(
            scope = backgroundScope,
            samples = samplesFlow,
            connectionState = connectionState,
            health = health,
            clock = clock,
            countdownSeconds = 3,
        )

    private fun sample(
        i: Int,
        gx: Double = 0.0,
    ) = SensorSample(
        timestampMs = i * 20L,
        ax = 0.0,
        ay = 0.0,
        az = 9.8,
        gx = gx,
        gy = 0.0,
        gz = 0.0,
    )

    /** Zwei vollstaendige Zwei-Phasen-Zyklen (Umbauplan Phase 4). */
    private fun twoRepSamples(): List<SensorSample> {
        val settle = List(60) { 0.0 }
        val rep =
            (1..15).map { 60.0 * it / 15.0 } +
                (1..30).map { 60.0 - 120.0 * it / 30.0 } +
                (1..15).map { -60.0 + 60.0 * it / 15.0 }
        return (settle + rep + List(60) { 0.0 } + rep + List(40) { 0.0 })
            .mapIndexed { i, gx -> sample(i, gx) }
    }

    @Test
    fun `countdown laeuft 3-2-1 und wechselt dann zu COUNTING`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            assertEquals(ActiveSetPhase.COUNTDOWN, c.phase.value)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, c.countdownRemaining.value)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(ActiveSetPhase.COUNTING, c.phase.value)
            assertEquals(0, c.countdownRemaining.value)
            c.close()
        }

    @Test
    fun `start liefert false wenn bereits ein Set laeuft`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            assertFalse(c.start(2L, "AA:BB", profile()))
            c.close()
        }

    @Test
    fun `abort waehrend Countdown endet in IDLE und zaehlt nie`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(1_000)
            runCurrent()
            c.abort(SetAbortReason.DISCONNECT)
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
            // Verspaetetes Countdown-Ende darf die Phase nicht ueberschreiben.
            advanceUntilIdle()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
        }

    @Test
    fun `abort waehrend COUNTING verwirft Engine Puffer und Zaehler`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            assertEquals(ActiveSetPhase.COUNTING, c.phase.value)

            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            assertEquals(2, c.countedReps.value)

            c.abort(SetAbortReason.EXERCISE_CHANGED)
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
            assertNull(c.finishAndTakeTrace())
        }

    @Test
    fun `kein Sample eines alten Sets gelangt in ein neues Set`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()

            // Erste Haelfte des ersten Reps, dann Abbruch.
            twoRepSamples().take(30).forEach { samplesFlow.tryEmit(it) }
            runCurrent()
            c.abort(SetAbortReason.DISCONNECT)

            // Neues Set: der alte Puffer darf nicht mitzaehlen.
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            assertEquals(2, c.countedReps.value)
            val trace = c.finishAndTakeTrace()
            assertNotNull(trace)
            assertEquals(2, trace?.predictedReps)
        }

    @Test
    fun `stop liefert Count und laesst Trace stehen bis finish`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            assertEquals(2, c.stop())
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            // Engine/Puffer stehen noch: der Lernpfad kann den Trace nehmen.
            val trace = c.finishAndTakeTrace()
            assertNotNull(trace)
            assertEquals(2, trace?.predictedReps)
            assertEquals(1L, trace?.exerciseId)
            assertEquals("AA:BB", trace?.deviceId)
        }

    @Test
    fun `finishAndTakeTrace liefert unveraenderliche Sample-Kopie`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            val trace = c.finishAndTakeTrace() ?: error("trace erwartet")
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
            // Trace bleibt stabil, auch wenn der Controller weiterlebt.
            assertTrue(trace.samples.isNotEmpty())
            assertEquals(trace.predictedReps, 2)
            assertEquals(trace.samples.size, trace.samples.size)
        }

    @Test
    fun `finishAndTakeTrace ohne laufendes Set liefert null`() =
        runTest {
            val c = controller()
            assertNull(c.finishAndTakeTrace())
        }

    @Test
    fun `UNRELIABLE-Health bricht ein laufendes Set ab`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            assertEquals(ActiveSetPhase.COUNTING, c.phase.value)
            health.value =
                SensorHealth(
                    connectionState = SensorConnectionState.STREAMING,
                    recentPacketLossRate = 0.3,
                )
            runCurrent()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
            assertNull(c.finishAndTakeTrace())
        }

    @Test
    fun `ConnectionState nicht STREAMING bricht ein laufendes Set ab`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ActiveSetPhase.COUNTDOWN, c.phase.value)
            connectionState.value = SensorConnectionState.DISCONNECTED
            runCurrent()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            advanceUntilIdle()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
        }

    @Test
    fun `abort ist idempotent und close raeumt auf`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            c.abort(SetAbortReason.DISCONNECT)
            c.abort(SetAbortReason.DISCONNECT)
            c.close()
            c.close()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
        }

    @Test
    fun `SignalQuality wird beim Set-Abschluss eingefroren`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            health.value =
                SensorHealth(
                    connectionState = SensorConnectionState.STREAMING,
                    recentPacketLossRate = 0.06,
                )
            runCurrent()
            val trace = c.finishAndTakeTrace()
            assertEquals(SignalQuality.DEGRADED, trace?.signalQuality)
        }

    // --- Zweitmeinung (Umbauplan 2026-09-04 Phase 7) ----------------------
    //
    // Seit die Zweitmeinung in der UI sichtbar ist, hat ihr Lebenszyklus eine
    // Wirkung nach draussen: bleibt sie beim Abbruch stehen, waehrend der
    // Zaehlstand auf 0 faellt, zeigt die App eine Aussage ueber einen Satz an,
    // den es nicht mehr gibt.

    @Test
    fun `stop setzt die Zweitmeinung und abort raeumt sie ab`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            assertEquals(2, c.stop())
            assertNotNull("stop muss die Pruefung rechnen", c.lastPlausibility.value)

            c.abort(SetAbortReason.EXERCISE_CHANGED)
            assertEquals(0, c.countedReps.value)
            assertNull(
                "Zweitmeinung darf einen abgebrochenen Satz nicht ueberleben",
                c.lastPlausibility.value,
            )
        }

    @Test
    fun `finishAndTakeTrace raeumt die Zweitmeinung mit ab`() =
        runTest {
            // finishAndTakeTrace ruft intern abort(CLEARED): der Trace traegt
            // die Zweitmeinung, der Controller darf sie danach nicht mehr
            // anzeigen - sonst haengt der Hinweis nach dem Loggen fest.
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            c.stop()
            val trace = c.finishAndTakeTrace()
            assertNotNull("Trace muss die Zweitmeinung tragen", trace?.plausibility)
            assertNull(c.lastPlausibility.value)
        }

    @Test
    fun `Zweitmeinung eines Vorgaenger-Sets ragt nicht in ein neues Set`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            twoRepSamples().chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            c.stop()
            assertNotNull(c.lastPlausibility.value)

            assertTrue(c.start(1L, "AA:BB", profile()))
            assertNull(c.lastPlausibility.value)
            c.close()
        }
}
