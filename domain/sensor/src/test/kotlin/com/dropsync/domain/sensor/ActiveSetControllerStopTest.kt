package com.dropsync.domain.sensor

import com.dropsync.core.testing.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5-Regressionsgruppe: [ActiveSetController.stop] und
 * [ActiveSetController.finishAndTakeTrace] muessen den Sample-Collector
 * wirklich abwarten (Join), statt sich auf kooperatives `cancel()` zu
 * verlassen. Eigenes File, weil [ActiveSetControllerTest] sonst die
 * detekt-LargeClass-Grenze reisst.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSetControllerStopTest {
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
            // RC-1: Test-Dispatcher statt Dispatchers.Default, damit die
            // virtuelle Zeit des runTest-Schedulers greift.
            workerDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
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

    // --- B5: stop/finish warten den Sample-Worker wirklich ab -------------

    /**
     * B5-Regression: `cancel()` allein ist kooperativ. Haengt der Collector
     * in einem nicht abbrechbaren Abschnitt (hier [NonCancellable]), darf
     * [ActiveSetController.stop] erst nach dessen Ende lesen. Ohne den Join
     * waere der Stop sofort fertig — dieser Test faellt dann.
     */
    @Test
    fun `stop wartet den sample-collector ab bevor er liest`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var emitted = 0
            val samples =
                flow {
                    // Erst nach dem Countdown emittieren, sonst verwirft
                    // onSample den ersten Sample (Phase noch COUNTDOWN).
                    delay(4_000)
                    emit(sample(0, gx = 60.0))
                    emitted++
                    withContext(NonCancellable) { gate.await() }
                    emit(sample(1, gx = 60.0))
                    emitted++
                }
            val c =
                ActiveSetController(
                    scope = backgroundScope,
                    samples = samples,
                    connectionState = connectionState,
                    health = health,
                    clock = FakeClock(),
                    countdownSeconds = 3,
                    workerDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
                )
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(4_100)
            runCurrent()
            assertEquals("erster Sample muss verarbeitet sein", 1, emitted)

            var counted: Int? = null
            val stopJob = launch { counted = c.stop() }
            runCurrent()
            assertFalse(
                "stop darf erst nach dem Collector-Ende zurueckkommen",
                stopJob.isCompleted,
            )
            assertNull("stop darf noch nichts gelesen haben", counted)

            gate.complete(Unit)
            runCurrent()
            stopJob.join()
            assertEquals(0, counted ?: -1)
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(
                "der zweite Sample darf nach dem Stop nicht mehr im Mitschnitt landen",
                1,
                c.finishAndTakeTrace()?.samples?.size,
            )
        }

    /**
     * B5-Regression: [ActiveSetController.finishAndTakeTrace] friert den
     * Puffer erst ein, wenn der Collector beendet ist — sonst koennte er
     * waehrend `toList()` noch schreiben oder nach `clear()` nachtragen.
     */
    @Test
    fun `finishAndTakeTrace wartet den sample-collector ab`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val samples =
                flow {
                    // Erst nach dem Countdown emittieren, sonst verwirft
                    // onSample den Sample (Phase noch COUNTDOWN).
                    delay(4_000)
                    emit(sample(0, gx = 60.0))
                    withContext(NonCancellable) { gate.await() }
                    emit(sample(1, gx = 60.0))
                }
            val c =
                ActiveSetController(
                    scope = backgroundScope,
                    samples = samples,
                    connectionState = connectionState,
                    health = health,
                    clock = FakeClock(),
                    countdownSeconds = 3,
                    workerDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
                )
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(4_100)
            runCurrent()

            var trace: SetTrace? = null
            val finishJob = launch { trace = c.finishAndTakeTrace() }
            runCurrent()
            assertFalse(
                "finish darf nicht vor dem Collector-Ende liefern",
                finishJob.isCompleted,
            )
            assertNull("ohne Join wuerde hier schon ein Trace stehen", trace)

            gate.complete(Unit)
            runCurrent()
            finishJob.join()
            assertNotNull(trace)
            assertEquals(
                "nur der Sample vor dem Collector-Ende gehoert in den Mitschnitt",
                1,
                trace?.samples?.size,
            )
        }

    /**
     * B5: die Autokorrelations-Pruefung ist CPU-Arbeit und darf nicht auf
     * dem Aufrufer-Thread (hier: Main) laufen. Der Zaehler-Dispatcher sieht
     * jeden Block, den [ActiveSetController.stop] per `withContext` abgibt.
     */
    @Test
    fun `stop rechnet die plausibilitaet ueber den worker dispatcher`() =
        runTest {
            var dispatchedBlocks = 0
            val worker =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatchedBlocks++
                        block.run()
                    }
                }
            val c =
                ActiveSetController(
                    scope = backgroundScope,
                    samples = flow { emit(sample(0, gx = 60.0)) },
                    connectionState = connectionState,
                    health = health,
                    clock = FakeClock(),
                    countdownSeconds = 3,
                    workerDispatcher = worker,
                )
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            val before = dispatchedBlocks

            c.stop()
            assertTrue(
                "die Plausibilitaet muss per withContext(workerDispatcher) laufen",
                dispatchedBlocks > before,
            )
        }

    /**
     * B5: Stop und Disconnect-Abbruch koennen sich ueberlappen (der Stop
     * wartet auf den Worker, der Disconnect laeuft synchron dazwischen).
     * Beide Pfade muessen denselben, konsistenten Endzustand hinterlassen.
     */
    @Test
    fun `abort waehrend stop laesst den zustand konsistent`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val samples =
                flow {
                    delay(4_000)
                    emit(sample(0, gx = 60.0))
                    withContext(NonCancellable) { gate.await() }
                }
            val c =
                ActiveSetController(
                    scope = backgroundScope,
                    samples = samples,
                    connectionState = connectionState,
                    health = health,
                    clock = FakeClock(),
                    countdownSeconds = 3,
                    workerDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
                )
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(4_100)
            runCurrent()

            var counted: Int? = null
            val stopJob = launch { counted = c.stop() }
            runCurrent()
            assertFalse(stopJob.isCompleted)

            c.abort(SetAbortReason.DISCONNECT)

            gate.complete(Unit)
            runCurrent()
            stopJob.join()
            assertEquals(ActiveSetPhase.IDLE, c.phase.value)
            assertEquals(0, c.countedReps.value)
            assertNull("ein abgebrochenes Set hat keinen Report", c.lastDiagnostics.value)
            assertEquals("abort gewinnt: der Zaehlstand ist weg", 0, counted ?: -1)
        }

    /**
     * B5: ohne vorherigen [ActiveSetController.stop] muss der Mitschnitt
     * dieselbe Sample-Menge tragen — der Join ersetzt keine Samples.
     */
    @Test
    fun `finishAndTakeTrace ohne stop liefert dieselbe sample-menge`() =
        runTest {
            val c = controller()
            assertTrue(c.start(1L, "AA:BB", profile()))
            advanceTimeBy(3_100)
            runCurrent()
            val samples = twoRepSamples()
            samples.chunked(32).forEach { chunk ->
                chunk.forEach { samplesFlow.tryEmit(it) }
                runCurrent()
            }
            val trace = c.finishAndTakeTrace()
            assertEquals(samples.size, trace?.samples?.size)
            assertEquals(2, trace?.predictedReps)
        }
}
