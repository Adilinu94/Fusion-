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

    /**
     * P2-Fix #19: Ergebnis der Autokorrelations-Plausibilitaetspruefung des
     * letzten abgeschlossenen Sets. null, solange kein Set abgeschlossen ist.
     * Die UI kann daraus einen Hinweis ableiten ("Zaehlung wirkt
     * unplausibel — Kalibrierung pruefen?"), ohne dass eine Ground Truth
     * vorliegen muss.
     */
    private val _lastPlausibility = MutableStateFlow<RepCountPlausibility.Result?>(null)
    val lastPlausibility: StateFlow<RepCountPlausibility.Result?> = _lastPlausibility.asStateFlow()

    private var engine: ExerciseEnginePipeline? = null
    private var exerciseId: Long = -1L
    private var deviceId: String? = null
    private var profileRevision: Int = 0
    private var engineVersion: RepEngineVersion = RepEngineVersion.V2_RELIABLE
    private val bufferedSamples = mutableListOf<SensorSample>()
    private val bufferedRepEvents = mutableListOf<RepEvent>()
    private var startedAtMs: Long = 0L

    /** Letzter Abbruchgrund fuer Diagnose und Tests (P4-Fix #30). */
    var lastAbortReason: SetAbortReason? = null
        private set

    private var countdownJob: Job? = null
    private var sampleJob: Job? = null
    private var eventJob: Job? = null
    private var healthJob: Job? = null
    private var connectionJob: Job? = null

    /**
     * P0-Fix (Start-Race): `health` und `connectionState` sind StateFlows und
     * liefern beim Abonnieren SOFORT ihren aktuellen Wert. Stammt dieser noch
     * aus einer Phase vor STREAMING — oder lief `updateHealth()` seit dem
     * Wechsel nicht —, brach der Controller das gerade gestartete Set
     * unmittelbar wieder ab. Bis der Stream sich einmal als brauchbar gezeigt
     * hat, gilt deshalb eine Anlaufzeit: erst danach loesen UNRELIABLE bzw.
     * "nicht STREAMING" einen Abbruch aus.
     */
    private var streamProvenGood = false

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
                    // P2-Fix #22: das Accel-Voting laeuft, sobald die
                    // Kalibrierung eine trennscharfe Schwelle gemessen hat.
                    // Ohne kalibrierten Wert (Altprofile aus Schema v4)
                    // bleibt der Kanal aus - lieber kein zweiter Kanal als
                    // ein falsch parametrisierter.
                    accelEnabled = profile.accelVotingAvailable,
                    accelThreshold = profile.accelThreshold,
                    // Rollout (Umbauplan Punkt 8): orientationTrackingEnabled
                    // bleibt false, bis die Freigabe-Szenarien (Gate 11b)
                    // gruen sind.
                ),
            ).also { it.setTemplate(profile.repTemplate) }

        this.exerciseId = exerciseId
        this.deviceId = deviceId
        this.profileRevision = profile.revision
        this.engineVersion = profile.engineVersion
        startedAtMs = clock.elapsedRealtimeMs()
        _countedReps.value = 0
        lastAbortReason = null
        streamProvenGood = false
        // P2-Fix #19: die Zweitmeinung des Vorgaenger-Sets darf nicht in ein
        // neues Set hineinragen.
        _lastPlausibility.value = null
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
                    if (state == SensorConnectionState.STREAMING) {
                        streamProvenGood = true
                    } else if (streamProvenGood && _phase.value != ActiveSetPhase.IDLE) {
                        // Erst abbrechen, wenn der Stream vorher wirklich lief
                        // (P0-Fix Start-Race, siehe [streamProvenGood]).
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
     *
     * P0-Fix: der erste, beim Abonnieren nachgelieferte Wert darf das Set
     * nicht toeten. Erst wenn der Stream sich einmal als brauchbar gezeigt
     * hat ([streamProvenGood]), wirkt UNRELIABLE als Abbruchgrund.
     */
    private fun onHealth(health: SensorHealth) {
        _currentSignalQuality.value = health.quality
        if (health.quality != SignalQuality.UNRELIABLE) {
            streamProvenGood = true
            return
        }
        if (streamProvenGood && _phase.value != ActiveSetPhase.IDLE) {
            abort(SetAbortReason.DISCONNECT)
        }
    }

    /**
     * Beendet das Set und liefert den gezaehlten Stand. Engine und Puffer
     * bleiben absichtlich stehen, bis [finishAndTakeTrace] oder [abort]
     * aufgeraeumt haben.
     *
     * P0-Fix: hier laufen ALLE Jobs aus. Frueher wurde nur der Countdown
     * gecancelt — Sample-, Health- und Connection-Collector liefen weiter,
     * bis der Nutzer loggte. Stoppte er ohne zu loggen, blieben drei
     * Collector unbegrenzt am Flow haengen. Der Zaehlstand ist trotzdem
     * sicher: `onSample` verwirft ausserhalb von COUNTING ohnehin alles.
     *
     * P2-Fix #19: beim Stoppen laeuft die Autokorrelations-Pruefung ueber das
     * mitgeschnittene Signal, solange die Engine noch steht.
     */
    fun stop(): Int {
        cancelJobs()
        _lastPlausibility.value = engine?.checkPlausibility()
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
        lastAbortReason = reason
        engine = null
        streamProvenGood = false
        _phase.value = ActiveSetPhase.IDLE
        _countdownRemaining.value = 0
        _countedReps.value = 0
        // Umbauplan 2026-09-04 Phase 7: die Zweitmeinung gehoert zum
        // Zaehlstand. Bleibt sie beim Abbruch stehen, waehrend `_countedReps`
        // auf 0 faellt, zeigt die UI eine Aussage ueber einen Satz an, den es
        // nicht mehr gibt. Erst seit die Zweitmeinung sichtbar ist, hat dieser
        // Zustand eine Wirkung nach draussen — deshalb hier und nicht in
        // Phase 6.
        _lastPlausibility.value = null
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
        val activeEngine = engine
        // P2-Fix #19/#21: Zweitmeinung und gemessene Rate gehoeren in den
        // Mitschnitt. `stop()` hat die Pruefung eventuell schon gerechnet -
        // dann den vorhandenen Wert wiederverwenden statt erneut ueber das
        // Signal zu laufen.
        val plausibility = _lastPlausibility.value ?: activeEngine?.checkPlausibility()
        val measuredRateHz = activeEngine?.estimatedSampleRateHz ?: SampleRateEstimator.NOMINAL_RATE_HZ
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
                plausibility = plausibility,
                measuredSampleRateHz = measuredRateHz,
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
