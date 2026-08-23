package com.dropsync.domain.sensor

import com.dropsync.core.common.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle eines live gezaehlten Sets (Umbauplan Phase 6): Countdown,
 * Engine-Erzeugung, Sample-Puffer und Rep-Events laufen hier gebuendelt
 * statt lose im ViewModel.
 *
 * Regeln:
 * - [start] friert Uebung, Geraet und Profilrevision ein; ein spaeterer
 *   UI-Wechsel haengt ein laufendes Set nie still auf eine andere Uebung um.
 * - [stop] beendet das Set, laesst Engine und Puffer aber fuer
 *   [finishAndTakeTrace] stehen (der Lernpfad braucht den Mitschnitt).
 * - [abort] raeumt atomar auf (Countdown, Engine, Puffer, Zaehler) und ist
 *   idempotent; sie laeuft ueber alle Abbruchpfade (Disconnect, schlechtes
 *   Signal, Uebungswechsel, onCleared).
 * - Kein Sample eines alten Sets gelangt in ein neues: Puffer und Engine
 *   werden nur in [start]/[finishAndTakeTrace]/[abort] angefasst.
 *
 * Rein JVM: keine Android-Typen, testbar mit kotlinx-coroutines-test.
 */
class ActiveSetController(
    private val scope: CoroutineScope,
    private val samples: Flow<SensorSample>,
    private val connectionState: Flow<SensorConnectionState>,
    private val health: Flow<SensorHealth>,
    private val clock: Clock,
    private val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
) {
    private val _phase = MutableStateFlow(ActiveSetPhase.IDLE)
    val phase: StateFlow<ActiveSetPhase> = _phase.asStateFlow()

    private val _countdownRemaining = MutableStateFlow(0)
    val countdownRemaining: StateFlow<Int> = _countdownRemaining.asStateFlow()

    private val _countedReps = MutableStateFlow(0)
    val countedReps: StateFlow<Int> = _countedReps.asStateFlow()

    private val _currentSignalQuality = MutableStateFlow(SignalQuality.UNRELIABLE)
    val currentSignalQuality: StateFlow<SignalQuality> = _currentSignalQuality.asStateFlow()

    private var engine: ExerciseEnginePipeline? = null
    private var exerciseId: Long = -1L
    private var deviceId: String? = null
    private var profileRevision: Int = 0
    private var engineVersion: RepEngineVersion = RepEngineVersion.V2_RELIABLE
    private val bufferedSamples = mutableListOf<SensorSample>()
    private val bufferedRepEvents = mutableListOf<RepEvent>()
    private var startedAtMs: Long = 0L

    private var countdownJob: Job? = null
    private var sampleJob: Job? = null
    private var eventJob: Job? = null
    private var healthJob: Job? = null
    private var connectionJob: Job? = null

    /**
     * Startet ein gezaehltes Set fuer [profile]. Liefert false, wenn bereits
     * ein Set laeuft (Guard: nur aus IDLE startbar).
     */
    fun start(
        exerciseId: Long,
        deviceId: String,
        profile: CalibrationProfile,
    ): Boolean {
        if (_phase.value != ActiveSetPhase.IDLE) return false
        // Reste eines vorherigen Sets (stop ohne finish) gehoeren nicht in
        // das neue Set.
        cancelJobs()
        engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = profile.rotationAxis,
                    gyroBias = profile.gyroBias,
                    expectedProminence = profile.expectedProminence,
                    expectedDurationMs = profile.expectedDurationMs,
                    detectionThreshold = profile.detectionThreshold,
                    hasValidCalibration = true,
                    // Rollout (Umbauplan Punkte 4/8): accelEnabled und
                    // orientationTrackingEnabled bleiben false, bis die
                    // Freigabe-Szenarien (Gate 11b) gruen sind.
                ),
            ).also { it.setTemplate(profile.repTemplate) }

        this.exerciseId = exerciseId
        this.deviceId = deviceId
        this.profileRevision = profile.revision
        this.engineVersion = profile.engineVersion
        startedAtMs = clock.elapsedRealtimeMs()
        _countedReps.value = 0
        bufferedSamples.clear()
        bufferedRepEvents.clear()
        _phase.value = ActiveSetPhase.COUNTDOWN
        _countdownRemaining.value = countdownSeconds

        val activeEngine = engine
        eventJob = scope.launch { activeEngine?.repEvents?.collect { bufferedRepEvents.add(it) } }
        sampleJob = scope.launch { samples.collect { onSample(it) } }
        healthJob = scope.launch { health.collect { onHealth(it) } }
        connectionJob =
            scope.launch {
                connectionState.collect { state ->
                    if (state != SensorConnectionState.STREAMING &&
                        _phase.value != ActiveSetPhase.IDLE
                    ) {
                        abort(SetAbortReason.DISCONNECT)
                    }
                }
            }
        countdownJob =
            scope.launch {
                var remaining = countdownSeconds
                while (remaining > 0 && isActive) {
                    _countdownRemaining.value = remaining
                    delay(1_000)
                    remaining--
                }
                _countdownRemaining.value = 0
                // Guard: nach abort/stop ist die Phase IDLE und darf nicht
                // von einem verspaeteten Countdown-Ende ueberschrieben werden.
                if (_phase.value == ActiveSetPhase.COUNTDOWN) {
                    _phase.value = ActiveSetPhase.COUNTING
                }
            }
        return true
    }

    private fun onSample(sample: SensorSample) {
        if (_phase.value != ActiveSetPhase.COUNTING) return
        val engine = engine ?: return
        bufferedSamples.add(sample)
        engine.processSample(
            sample.timestampMs,
            sample.gx,
            sample.gy,
            sample.gz,
            sample.ax,
            sample.ay,
            sample.az,
        )
        _countedReps.value = engine.repCount.value
    }

    /**
     * Umbauplan Phase 3: ein unzuverlaessiger Stream (oder ein nicht
     * streamendes Geraet) bricht ein laufendes Set ab. Manuelle Eingabe
     * bleibt jederzeit moeglich.
     */
    private fun onHealth(health: SensorHealth) {
        _currentSignalQuality.value = health.quality
        if (health.quality == SignalQuality.UNRELIABLE && _phase.value != ActiveSetPhase.IDLE) {
            abort(SetAbortReason.DISCONNECT)
        }
    }

    /**
     * Beendet das Set und liefert den gezaehlten Stand. Engine und Puffer
     * bleiben absichtlich stehen, bis [finishAndTakeTrace] oder [abort]
     * aufgeraeumt haben.
     */
    fun stop(): Int {
        countdownJob?.cancel()
        countdownJob = null
        val counted = _countedReps.value
        _phase.value = ActiveSetPhase.IDLE
        _countdownRemaining.value = 0
        return counted
    }

    /**
     * Bricht das aktive Set atomar ab (Umbauplan Phase 6 / P0-Fix):
     * Countdown, Engine, Zaehlstand, Puffer und Phase werden GEMEINSAM
     * zurueckgesetzt. Idempotent; darf aus jedem Zustand aufgerufen werden.
     */
    fun abort(reason: SetAbortReason) {
        cancelJobs()
        engine = null
        _phase.value = ActiveSetPhase.IDLE
        _countdownRemaining.value = 0
        _countedReps.value = 0
        bufferedSamples.clear()
        bufferedRepEvents.clear()
        exerciseId = -1L
        deviceId = null
        profileRevision = 0
        startedAtMs = 0L
    }

    /**
     * Friert den Set-Mitschnitt ein (unveraenderliche Kopien) und raeumt den
     * Controller auf. Liefert null, wenn kein Set lief (nichts zu lernen).
     */
    fun finishAndTakeTrace(): SetTrace? {
        if (engine == null) {
            _phase.value = ActiveSetPhase.IDLE
            return null
        }
        val trace =
            SetTrace(
                exerciseId = exerciseId,
                deviceId = deviceId.orEmpty(),
                engineVersion = engineVersion,
                profileRevision = profileRevision,
                samples = bufferedSamples.toList(),
                repEvents = bufferedRepEvents.toList(),
                predictedReps = _countedReps.value,
                signalQuality = _currentSignalQuality.value,
                startedAtMs = startedAtMs,
                durationMs = clock.elapsedRealtimeMs() - startedAtMs,
            )
        abort(SetAbortReason.CLEARED)
        return trace
    }

    /** Fuer onCleared(): raeumt Jobs und Zustand vollstaendig ab. */
    fun close() {
        abort(SetAbortReason.CLEARED)
    }

    private fun cancelJobs() {
        countdownJob?.cancel()
        countdownJob = null
        sampleJob?.cancel()
        sampleJob = null
        eventJob?.cancel()
        eventJob = null
        healthJob?.cancel()
        healthJob = null
        connectionJob?.cancel()
        connectionJob = null
    }

    companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 3
    }
}
