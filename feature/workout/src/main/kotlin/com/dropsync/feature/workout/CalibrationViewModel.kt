package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
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
    ) : ViewModel() {
        private val controller = CalibrationController(sampleRateHz = 50.0)

        private val _stage = MutableStateFlow(CalibrationController.Stage.REST)
        val stage: StateFlow<CalibrationController.Stage> = _stage.asStateFlow()

        private val _restGate = MutableStateFlow<RestGateSnapshot?>(null)
        val restGate: StateFlow<RestGateSnapshot?> = _restGate.asStateFlow()

        private val _bufferedSamples = MutableStateFlow(0)
        val bufferedSamples: StateFlow<Int> = _bufferedSamples.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

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
            _error.value = null
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

        /** "Weiter" — ends the current stage; shows the German gate message on failure. */
        fun finishStage() {
            val failure = controller.finishStage()
            _error.value = failure
            _stage.value = controller.stage
            // Preview the learned quality once the review stage is reached.
            if (controller.stage == CalibrationController.Stage.REVIEW) {
                _qualityScore.value = controller.finalize()?.qualityScore
            }
        }

        /** Persists the learned profile once the review stage is confirmed. */
        fun confirmAndSave() {
            val result: GuidedCalibrationResult =
                controller.finalize() ?: run {
                    _error.value = "Kalibrierung unvollstaendig — bitte neu starten."
                    return
                }
            viewModelScope.launch {
                val profile =
                    CalibrationProfile(
                        exerciseId = exerciseId,
                        deviceId = deviceId,
                        rotationAxis = result.rotationAxis,
                        gyroBias = result.gyroBias,
                        repTemplate = result.repTemplate,
                        signalPeakLevel = result.theta + result.expectedProminence,
                        noisePeakLevel = result.theta * 0.5,
                        expectedProminence = result.expectedProminence,
                        expectedDurationSamples = result.expectedDurationSamples,
                        qualityScore = result.qualityScore,
                    )
                when (calibrationProfileRepository.save(profile)) {
                    is AppResult.Success -> _saved.value = true
                    is AppResult.Failure -> _error.value = "Speichern fehlgeschlagen."
                }
            }
        }

        override fun onCleared() {
            collectJob?.cancel()
            tickJob?.cancel()
        }
    }
