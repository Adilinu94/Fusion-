package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.calibration.CalibrationController
import com.dropsync.domain.sensor.calibration.GuidedCalibrationResult
import com.dropsync.domain.sensor.calibration.RestGateSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Wizard state for Guided Calibration 2.0 (Fusion Phase 4 step 3). Drives a
 * [CalibrationController] with live samples from the connected FlowRep chip.
 * The user performs: rest -> 1 rep -> 5 reps -> 3 slow reps -> review.
 * On success the learned profile is persisted per exercise+device.
 */
@HiltViewModel
class CalibrationViewModel
    @Inject
    constructor(
        private val sensorProvider: SensorProvider,
        private val calibrationProfileRepository: CalibrationProfileRepository,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val controller = CalibrationController(sampleRateHz = 50.0)

        private val _stage = MutableStateFlow(CalibrationController.Stage.REST)
        val stage: StateFlow<CalibrationController.Stage> = _stage.asStateFlow()

        private val _restGate = MutableStateFlow<RestGateSnapshot?>(null)
        val restGate: StateFlow<RestGateSnapshot?> = _restGate.asStateFlow()

        private val _bufferedSamples = MutableStateFlow(0)
        val bufferedSamples: StateFlow<Int> = _bufferedSamples.asStateFlow()

        /** P3-Fix #27: typisierter Fehler; Text entsteht erst im Composable. */
        private val errorState = MutableStateFlow<CalibrationUiError?>(null)
        internal val error: StateFlow<CalibrationUiError?> = errorState.asStateFlow()

        private val _saved = MutableStateFlow(false)
        val saved: StateFlow<Boolean> = _saved.asStateFlow()

        /** Learned profile quality 0..1 (shown in the review stage). */
        private val _qualityScore = MutableStateFlow<Double?>(null)
        val qualityScore: StateFlow<Double?> = _qualityScore.asStateFlow()

        val connectionState: StateFlow<SensorConnectionState> = sensorProvider.connectionState

        private var collectJob: Job? = null
        private var tickJob: Job? = null
        private var exerciseId: Long = 0
        private var deviceId: String = ""

        /** Starts the wizard for [exerciseId]; subscribes to the sample stream. */
        fun start(
            exerciseId: Long,
            deviceId: String,
        ) {
            this.exerciseId = exerciseId
            this.deviceId = deviceId
            controller.start()
            _stage.value = controller.stage
            errorState.value = null
            _saved.value = false

            collectJob?.cancel()
            collectJob =
                viewModelScope.launch {
                    sensorProvider.samples.collect { sample ->
                        controller.onSample(sample)
                        _bufferedSamples.value = controller.bufferedSampleCount
                    }
                }
            // Poll the live rest-gate at ~4 Hz for the wizard UI.
            tickJob?.cancel()
            tickJob =
                viewModelScope.launch {
                    while (isActive) {
                        _restGate.value = controller.liveRestGate
                        delay(250)
                    }
                }
        }

        /**
         * "Weiter" — ends the current stage; exposes a typed failure on failure.
         *
         * P1-Fix: `finishStage()` fuehrt in Stufe B/C den Brute-Force-Sweep aus
         * (20 Thresholds x 2 Prominenzen x 5 Refraktaerfaktoren, jeweils mit
         * drei vollstaendigen Zaehl-Laeufen ueber das Signal — plus einmal ueber
         * das 3x gestreckte). Das sind bei einem 15-s-Set mehrere
         * Hunderttausend Sample-Operationen. Vorher lief das ueber
         * `viewModelScope` auf `Dispatchers.Main.immediate`, also auf dem
         * UI-Thread. Jetzt auf dem CPU-Dispatcher; die UI-Zustaende werden
         * danach wieder auf dem Main-Thread gesetzt.
         */
        fun finishStage() {
            if (_busy.value) return
            _busy.value = true
            viewModelScope.launch {
                val failure = withContext(dispatchers.default) { controller.finishStage() }
                errorState.value = failure?.let(CalibrationUiError::GateFailure)
                _stage.value = controller.stage
                // Preview the learned quality once the review stage is reached.
                if (controller.stage == CalibrationController.Stage.REVIEW) {
                    _qualityScore.value = withContext(dispatchers.default) { controller.finalize()?.qualityScore }
                }
                _busy.value = false
            }
        }

        /** True, solange eine Stufenauswertung laeuft (UI sperrt "Weiter"). */
        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        /** Persists the learned profile once the review stage is confirmed. */
        fun confirmAndSave() {
            if (_busy.value) return
            _busy.value = true
            viewModelScope.launch {
                val result: GuidedCalibrationResult? = withContext(dispatchers.default) { controller.finalize() }
                if (result == null) {
                    errorState.value = CalibrationUiError.Incomplete
                    _busy.value = false
                    return@launch
                }
                // Umbauplan Phase 1.4: theta wird DIREKT persistiert - keine
                // verlustbehaftete SPK/NPK-Rekonstruktion mehr.
                // Umbauplan Phase 7.4: eine neue Kalibrierung startet als
                // Revision 1 ACTIVE (ersetzt die vorherige aktive Revision).
                val profile =
                    CalibrationProfile(
                        exerciseId = exerciseId,
                        deviceId = deviceId,
                        rotationAxis = result.rotationAxis,
                        gyroBias = result.gyroBias,
                        repTemplate = result.repTemplate,
                        expectedProminence = result.expectedProminence,
                        qualityScore = result.qualityScore,
                        detectionThreshold = result.theta,
                        noiseFloor = result.noiseFloor,
                        expectedDurationMs = result.expectedDurationMs,
                        revision = 1,
                        parentRevision = null,
                        status = ProfileStatus.ACTIVE,
                        validatedSetCount = 0,
                        // P2-Fix #22: aus dem KNOWN_SET gemessene Accel-Schwelle.
                        // 0.0 = keine trennscharfe Schwelle gefunden, dann laeuft
                        // die Live-Pipeline ohne Accel-Voting.
                        accelThreshold = result.accelThreshold,
                    )
                when (calibrationProfileRepository.save(profile)) {
                    is AppResult.Success -> _saved.value = true
                    is AppResult.Failure -> errorState.value = CalibrationUiError.SaveFailed
                }
                _busy.value = false
            }
        }

        override fun onCleared() {
            collectJob?.cancel()
            tickJob?.cancel()
        }
    }
