package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.accelMagnitude
import com.dropsync.domain.sensor.calibration.CalibrationController
import com.dropsync.domain.sensor.calibration.CalibrationReview
import com.dropsync.domain.sensor.calibration.GuidedCalibrationResult
import com.dropsync.domain.sensor.calibration.RestGateSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Wizard state for Guided Calibration 2.0 (Fusion Phase 4 step 3). Drives a
 * [CalibrationController] with live samples from the connected FlowRep chip.
 * The user performs: rest -> 1 rep -> 5 reps -> 3 slow reps -> review.
 * On success the learned profile is persisted per exercise+device.
 *
 * RC-8 (Wizard 2.0): der Wizard zeigt waehrend der Stufen das Live-Signal und
 * eine Rep-Schaetzung, laesst jede Stufe wiederholen (auch aus dem Review
 * heraus) und erklaert im Review, was die gelernte Qualitaet fuer die
 * spaetere Zaehlung bedeutet.
 */
@HiltViewModel
class CalibrationViewModel
    @Inject
    constructor(
        private val sensorProvider: SensorProvider,
        private val calibrationProfileRepository: CalibrationProfileRepository,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val controller = CalibrationController(sampleRateHz = 50.0)

        private val _stage = MutableStateFlow(CalibrationController.Stage.REST)
        val stage: StateFlow<CalibrationController.Stage> = _stage.asStateFlow()

        private val _restGate = MutableStateFlow<RestGateSnapshot?>(null)
        val restGate: StateFlow<RestGateSnapshot?> = _restGate.asStateFlow()

        private val _bufferedSamples = MutableStateFlow(0)
        val bufferedSamples: StateFlow<Int> = _bufferedSamples.asStateFlow()

        /**
         * RC-8: Live-Schaetzung der Reps im aktuellen Stufen-Puffer
         * ("Reps erkannt: n"). null in Stufen ohne Zaehlung.
         */
        private val _liveRepEstimate = MutableStateFlow<Int?>(null)
        val liveRepEstimate: StateFlow<Int?> = _liveRepEstimate.asStateFlow()

        /**
         * RC-8: Soll-Zahl der aktuellen Stufe (1 / 5 / 3) fuer "n / Ziel".
         * null in Ruhe und Review.
         */
        private val _stageTarget = MutableStateFlow<Int?>(null)
        val stageTarget: StateFlow<Int?> = _stageTarget.asStateFlow()

        /** P3-Fix #27: typisierter Fehler; Text entsteht erst im Composable. */
        private val errorState = MutableStateFlow<CalibrationUiError?>(null)
        internal val error: StateFlow<CalibrationUiError?> = errorState.asStateFlow()

        private val _saved = MutableStateFlow(false)
        val saved: StateFlow<Boolean> = _saved.asStateFlow()

        /** Learned profile quality 0..1 (shown in the review stage). */
        private val _qualityScore = MutableStateFlow<Double?>(null)
        val qualityScore: StateFlow<Double?> = _qualityScore.asStateFlow()

        /**
         * RC-8: nachrechenbare Fakten der Kalibrierung fuer die
         * Review-Erklaerung. null, solange kein Ergebnis berechnet wurde
         * (z. B. misslungener 5er-Satz — dann zeigt die UI den Ausweg).
         */
        private val _review = MutableStateFlow<CalibrationReview?>(null)
        val review: StateFlow<CalibrationReview?> = _review.asStateFlow()

        val connectionState: StateFlow<SensorConnectionState> = sensorProvider.connectionState

        /** True, solange eine Stufenauswertung laeuft (UI sperrt "Weiter"). */
        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        /**
         * RC-8: "Weiter" nur bei erfuellter Stufe. Ruhe braucht ein bestandenes
         * Rest-Gate; die Rep-Stufen brauchen mindestens eine sichtbare
         * Bewegung (die Live-Schaetzung ist bewusst tolerant — ueber die
         * endgueltige Zaehlung entscheidet die Auswertung bzw. die Korrektur
         * im Review). Laeuft eine Auswertung, ist der Knopf gesperrt.
         */
        val advanceEnabled: StateFlow<Boolean> =
            combine(_stage, _restGate, _liveRepEstimate, _busy) { stage, gate, estimate, busy ->
                if (busy) {
                    false
                } else {
                    when (stage) {
                        CalibrationController.Stage.REST -> gate?.ready == true

                        CalibrationController.Stage.SINGLE_REP,
                        CalibrationController.Stage.KNOWN_SET,
                        CalibrationController.Stage.SLOW_SET,
                        -> (estimate ?: 0) >= 1

                        CalibrationController.Stage.REVIEW -> true

                        CalibrationController.Stage.DONE,
                        CalibrationController.Stage.FAILED,
                        -> false
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        // --- RC-8: Live-Waveform -------------------------------------------

        /**
         * Rolling window of the last [WAVEFORM_WINDOW] acceleration magnitudes
         * (in g) for the wizard's live signal. Leer, solange nichts laeuft.
         * Gleiche Ringpuffer-/Drossel-Mechanik wie im Train-Tab (RC-11):
         * gefuellt mit jedem Sample, veroeffentlicht im Frame-Takt.
         */
        private val _waveform = MutableStateFlow(FloatArray(0))
        val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

        private val waveformRing = FloatArray(WAVEFORM_WINDOW)
        private var waveformHead = 0
        private var waveformFill = 0
        private var lastWaveformPublishMs: Long? = null

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
            resetUiForNewRun()

            collectJob?.cancel()
            collectJob =
                viewModelScope.launch {
                    sensorProvider.samples.collect { sample ->
                        controller.onSample(sample)
                        pushWaveformSample(sample.accelMagnitude.toFloat())
                        _bufferedSamples.value = controller.bufferedSampleCount
                    }
                }
            // Poll the live rest-gate and rep estimate at ~4 Hz for the wizard UI.
            tickJob?.cancel()
            tickJob =
                viewModelScope.launch {
                    while (isActive) {
                        _restGate.value = controller.liveRestGate
                        _liveRepEstimate.value = controller.liveRepEstimate
                        _stageTarget.value = stageTargetOf(controller.stage)
                        delay(250)
                    }
                }
        }

        /** RC-8: setzt alle UI-Zustaende eines frischen Durchlaufs zurueck. */
        private fun resetUiForNewRun() {
            _stage.value = controller.stage
            _restGate.value = null
            _bufferedSamples.value = 0
            _liveRepEstimate.value = controller.liveRepEstimate
            _stageTarget.value = stageTargetOf(controller.stage)
            _qualityScore.value = null
            _review.value = null
            errorState.value = null
            _saved.value = false
            _waveform.value = FloatArray(0)
            waveformHead = 0
            waveformFill = 0
            lastWaveformPublishMs = null
        }

        private fun stageTargetOf(stage: CalibrationController.Stage): Int? =
            when (stage) {
                CalibrationController.Stage.SINGLE_REP -> 1
                CalibrationController.Stage.KNOWN_SET -> controller.knownSetCount
                CalibrationController.Stage.SLOW_SET -> controller.slowSetCount
                else -> null
            }

        /**
         * RC-8: "Stufe wiederholen" — leert den Puffer der aktuellen Stufe.
         * Aus dem Review heraus (misslungener 5er- oder Langsam-Satz) springt
         * [redoStage] auf die passende Sammel-Stufe zurueck.
         */
        fun repeatStage() {
            if (_busy.value) return
            controller.repeatStage()
            refreshAfterBufferReset()
        }

        /**
         * RC-8: wiederholt eine bestimmte Sammel-Stufe (aus dem Review:
         * KNOWN_SET oder SLOW_SET). Fruehere Ergebnisse bleiben erhalten.
         */
        fun redoStage(stage: CalibrationController.Stage) {
            if (_busy.value) return
            controller.redoFrom(stage)
            refreshAfterBufferReset()
        }

        private fun refreshAfterBufferReset() {
            errorState.value = null
            _qualityScore.value = null
            _review.value = null
            _stage.value = controller.stage
            _bufferedSamples.value = controller.bufferedSampleCount
            _liveRepEstimate.value = controller.liveRepEstimate
            _stageTarget.value = stageTargetOf(controller.stage)
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
                _liveRepEstimate.value = controller.liveRepEstimate
                _stageTarget.value = stageTargetOf(controller.stage)
                // Preview the learned quality once the review stage is reached.
                if (controller.stage == CalibrationController.Stage.REVIEW) {
                    val result = withContext(dispatchers.default) { controller.finalize() }
                    _qualityScore.value = result?.qualityScore
                    _review.value = result?.review
                }
                _busy.value = false
            }
        }

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
                        // B3 (RC-20): Stufe 1 transportiert die drei Schwellen
                        // nur - die Kalibrierung liefert sie (noch) nicht, also
                        // gelten die bisherigen Code-Defaults. Sie stehen hier
                        // explizit, damit die Entscheidung sichtbar ist.
                        templateThreshold = 0.7,
                        minQualityScore = 0.55,
                        dtwBand = 8,
                    )
                when (calibrationProfileRepository.save(profile)) {
                    is AppResult.Success -> _saved.value = true
                    is AppResult.Failure -> errorState.value = CalibrationUiError.SaveFailed
                }
                _busy.value = false
            }
        }

        /**
         * RC-8: uebernimmt eine Magnitude in den Ringpuffer und veroeffentlicht
         * den Snapshot in Trackreihenfolge (aeltestes Sample zuerst). Die
         * Drosselung auf ~30 Hz verhindert 50 Compose-Recompositions pro
         * Sekunde (gleiche Begruendung wie im Train-Tab, RC-11).
         */
        private fun pushWaveformSample(magnitude: Float) {
            waveformRing[waveformHead] = magnitude
            waveformHead = (waveformHead + 1) % WAVEFORM_WINDOW
            if (waveformFill < WAVEFORM_WINDOW) waveformFill++
            val now = clock.elapsedRealtimeMs()
            val last = lastWaveformPublishMs
            if (last != null && now - last < WAVEFORM_PUBLISH_INTERVAL_MS) return
            lastWaveformPublishMs = now
            val snapshot = FloatArray(waveformFill)
            val start = if (waveformFill < WAVEFORM_WINDOW) 0 else waveformHead
            for (i in 0 until waveformFill) {
                snapshot[i] = waveformRing[(start + i) % WAVEFORM_WINDOW]
            }
            _waveform.value = snapshot
        }

        override fun onCleared() {
            collectJob?.cancel()
            tickJob?.cancel()
        }

        private companion object {
            /** Samples shown in the live waveform (~4 s at 50 Hz). */
            const val WAVEFORM_WINDOW = 200

            /** RC-11: Mindestabstand zweier Waveform-Veroeffentlichungen (~30 Hz). */
            const val WAVEFORM_PUBLISH_INTERVAL_MS = 33L
        }
    }
