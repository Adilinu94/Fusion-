package com.dropsync.feature.workout

import androidx.lifecycle.viewModelScope
import com.dropsync.core.testing.FakeCalibrationProfileRepository
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeSensorProvider
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.calibration.CalibrationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Umbauplan Phase 1.4: CalibrationViewModel.confirmAndSave() muss den
 * kalibrierten Threshold theta DIREKT persistieren (kein verlustbehafteter
 * SPK/NPK-Umweg). Nach dem Speichern gilt:
 *   profile.detectionThreshold > 0
 *   profile.noiseFloor >= 0
 *   profile.expectedDurationMs > 0
 *   profile.engineVersion == V2_RELIABLE
 *   profile.signalKind == SIGNED_GYRO_PROJECTION
 *
 * Der synthetische Wizard-Durchlauf (REST -> SINGLE_REP -> KNOWN_SET ->
 * SLOW_SET -> REVIEW) ist in CalibrationControllerWizardTest (:domain:sensor)
 * als Domain-Test verifiziert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sensorProvider = FakeSensorProvider()
        calibrationProfileRepository = FakeCalibrationProfileRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        CalibrationViewModel(
            sensorProvider = sensorProvider,
            calibrationProfileRepository = calibrationProfileRepository,
            clock = FakeClock(),
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    /**
     * Wie in TrainViewModelTest: ohne Cancel haengt runTest im Cleanup, weil
     * der 250-ms-Tick-Poll (liveRestGate) endlos Tasks produziert.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(
        block: suspend (CalibrationViewModel) -> Unit,
    ) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * Emittet Samples in Chunks mit Scheduler-Durchlauf dazwischen (der
     * FakeSensorProvider puffert nur 64 Samples; laengere Wizard-Streams
     * wuerden sonst ueberlaufen).
     */
    private fun kotlinx.coroutines.test.TestScope.emitStream(samples: List<SensorSample>) {
        samples.chunked(32).forEach { chunk ->
            chunk.forEach { sensorProvider.emit(it) }
            testScheduler.runCurrent()
        }
    }

    private fun restSample(t: Long) = SensorSample(t, 0.0, 0.0, 9.81, 0.0, 0.0, 0.0)

    private fun rep(
        startMs: Long,
        amplitude: Double = 200.0,
        up: Int = 20,
        down: Int = 20,
    ): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        for (i in 1..up) {
            out.add(SensorSample(startMs + i * 20L, 0.0, 0.0, 9.81, 0.0, 0.0, amplitude * i / up))
        }
        for (i in 1..down) {
            out.add(SensorSample(startMs + (up + i) * 20L, 0.0, 0.0, 9.81, 0.0, 0.0, amplitude - amplitude * i / down))
        }
        return out
    }

    private fun reps(
        n: Int,
        amplitude: Double = 200.0,
        pauseSamples: Int = 60,
        up: Int = 20,
        down: Int = 20,
    ): List<SensorSample> {
        val out = mutableListOf<SensorSample>()
        var t = 0L
        repeat(n) {
            out += rep(t, amplitude, up, down)
            t += (up + down + pauseSamples) * 20L
        }
        return out
    }

    private fun kotlinx.coroutines.test.TestScope.driveWizard(vm: CalibrationViewModel) {
        vm.start(exerciseId = 1L, deviceId = "AA:BB:CC:DD:EE:FF")
        dispatcher.scheduler.runCurrent()

        // P1-Fix #15: finishStage() rechnet den Sweep auf dem Default-Dispatcher
        // statt auf dem UI-Thread. Der Stufenwechsel ist damit asynchron - der
        // Test muss den Scheduler nach jedem "Weiter" laufen lassen.
        fun advance() {
            vm.finishStage()
            dispatcher.scheduler.runCurrent()
        }

        // REST: 3 s Stille (150 Samples @ 50 Hz).
        emitStream((0 until 150).map { restSample(it * 20L) })
        advance()
        assertNull(vm.error.value)

        // SINGLE_REP: genau ein deutlicher Rep.
        emitStream(rep(0))
        advance()
        assertNull(vm.error.value)

        // KNOWN_SET: 5 Reps.
        emitStream(reps(5))
        advance()
        assertNull(vm.error.value)

        // SLOW_SET: 3 langsame Reps.
        emitStream(reps(3, up = 60, down = 60, pauseSamples = 120))
        advance()
        assertNull(vm.error.value)

        assertEquals(
            "Review-Stufe muss nach dem Durchlauf erreicht sein",
            com.dropsync.domain.sensor.calibration.CalibrationController.Stage.REVIEW,
            vm.stage.value,
        )
    }

    @Test
    fun `confirmAndSave speichert den kalibrierten Threshold direkt`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                driveWizard(vm)

                vm.confirmAndSave()
                dispatcher.scheduler.runCurrent()

                assertTrue(vm.saved.value)
                assertEquals(1, calibrationProfileRepository.saved.size)
                val profile = calibrationProfileRepository.saved.single()

                // Umbauplan Phase 1.4: theta direkt persistiert.
                assertTrue(
                    "detectionThreshold muss der kalibrierte positive Threshold sein",
                    profile.detectionThreshold > 0.0,
                )
                assertTrue(
                    "noiseFloor muss nichtnegativ sein",
                    profile.noiseFloor >= 0.0,
                )
                assertTrue(
                    "expectedDurationMs muss positiv sein",
                    profile.expectedDurationMs > 0.0,
                )
                assertEquals(
                    "nur GP-Kalibrierung ist freigegeben",
                    com.dropsync.domain.sensor.RepSignalKind.SIGNED_GYRO_PROJECTION,
                    profile.signalKind,
                )
                assertEquals(
                    "neue Profile tragen die zuverlaessige Engine-Version",
                    com.dropsync.domain.sensor.RepEngineVersion.V2_RELIABLE,
                    profile.engineVersion,
                )
            }
        }

    /**
     * B3 (RC-20): Die drei Profil-Schwellen werden beim Speichern explizit
     * gesetzt (Stufe 1: Transport, nicht gelernt) — der Codec schreibt sie
     * seit Schema v6 in den Blob und die Live-Pipeline liest sie von dort.
     */
    @Test
    fun `confirmAndSave persistiert die v6-Schwellen`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                driveWizard(vm)

                vm.confirmAndSave()
                dispatcher.scheduler.runCurrent()

                val profile = calibrationProfileRepository.saved.single()
                assertEquals(0.7, profile.templateThreshold, 1e-9)
                assertEquals(0.55, profile.minQualityScore, 1e-9)
                assertEquals(8, profile.dtwBand)
            }
        }

    @Test
    fun `confirmAndSave schlaegt ohne Review-Durchlauf fehl`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.start(exerciseId = 1L, deviceId = "AA:BB:CC:DD:EE:FF")
                dispatcher.scheduler.runCurrent()

                // Ohne Wizard-Durchlauf liefert finalize() null.
                vm.confirmAndSave()
                dispatcher.scheduler.runCurrent()

                assertEquals(false, vm.saved.value)
                assertNotNull(vm.error.value)
                assertEquals(0, calibrationProfileRepository.saved.size)
            }
        }

    // --- RC-8: Wizard 2.0 (Live-Schaetzung, Wiederholen, Review) -----------

    @Test
    fun `advanceEnabled folgt der Stufen-Regel`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.start(exerciseId = 1L, deviceId = "AA:BB:CC:DD:EE:FF")
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Ruhe ohne bestandenes Gate darf nicht weitergehen",
                    false,
                    vm.advanceEnabled.value,
                )

                emitStream((0 until 150).map { restSample(it * 20L) })
                testScheduler.advanceTimeBy(300)
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Nach 3 s Ruhe muss das Gate bereit sein",
                    true,
                    vm.advanceEnabled.value,
                )

                vm.finishStage()
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Leere Rep-Stufe darf nicht weitergehen",
                    false,
                    vm.advanceEnabled.value,
                )

                emitStream(rep(0))
                testScheduler.advanceTimeBy(300)
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Mit sichtbarer Bewegung ist die Stufe erfuellt",
                    true,
                    vm.advanceEnabled.value,
                )
            }
        }

    @Test
    fun `repeatStage leert Puffer und Schaetzung und bleibt in der Stufe`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                driveWizard(vm)

                // Aus dem Review zurueck in den 5er-Satz (ohne Ruhe/A zu wiederholen).
                vm.redoStage(CalibrationController.Stage.KNOWN_SET)
                dispatcher.scheduler.runCurrent()
                assertEquals(CalibrationController.Stage.KNOWN_SET, vm.stage.value)
                assertEquals(0, vm.bufferedSamples.value)
                assertNull("Das alte Review darf nicht stehen bleiben", vm.review.value)

                emitStream(reps(5))
                testScheduler.advanceTimeBy(300)
                dispatcher.scheduler.runCurrent()
                assertEquals(5, vm.liveRepEstimate.value)

                // Stufe wiederholen: Puffer und Schaetzung sind weg, Stufe bleibt.
                vm.repeatStage()
                dispatcher.scheduler.runCurrent()
                assertEquals(CalibrationController.Stage.KNOWN_SET, vm.stage.value)
                assertEquals(0, vm.bufferedSamples.value)
                assertEquals(0, vm.liveRepEstimate.value)

                // Und danach laeuft der Wizard normal zu Ende.
                emitStream(reps(5))
                vm.finishStage()
                dispatcher.scheduler.runCurrent()
                emitStream(reps(3, up = 60, down = 60, pauseSamples = 120))
                vm.finishStage()
                dispatcher.scheduler.runCurrent()
                assertEquals(CalibrationController.Stage.REVIEW, vm.stage.value)
                assertNotNull("Nach dem Wiederholen muss wieder ein Review entstehen", vm.review.value)
            }
        }

    @Test
    fun `review fakten und ziel stehen nach dem durchlauf bereit`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                driveWizard(vm)

                val review = vm.review.value
                assertNotNull("Review-Fakten muessen im Review vorliegen", review)
                assertEquals(5, review!!.detectedRepsKnownSet)
                assertEquals(5, review.expectedRepsKnownSet)
                assertEquals(3, review.detectedRepsSlowSet)
                assertEquals(3, review.expectedRepsSlowSet)
                assertNotNull("Die Qualitaet muss weiterhin angezeigt werden", vm.qualityScore.value)
                assertEquals(
                    "Im Review muss das Speichern moeglich sein",
                    true,
                    vm.advanceEnabled.value,
                )

                // Ziel-Zahlen der Stufen fuer "Reps erkannt: n von Ziel".
                vm.redoStage(CalibrationController.Stage.SLOW_SET)
                dispatcher.scheduler.runCurrent()
                assertEquals(3, vm.stageTarget.value)
                vm.redoStage(CalibrationController.Stage.KNOWN_SET)
                dispatcher.scheduler.runCurrent()
                assertEquals(5, vm.stageTarget.value)
                vm.repeatStage()
                dispatcher.scheduler.runCurrent()
                assertEquals(5, vm.stageTarget.value)
            }
        }
}
