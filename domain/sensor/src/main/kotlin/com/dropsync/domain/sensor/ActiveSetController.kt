package com.dropsync.domain.sensor

import com.dropsync.core.common.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * RC-1: CPU-lastige Verarbeitung (Filter, ZUPT, Peak-Erkennung, bei
     * Treffern DTW/Phasenpruefung/Qualitaet) laeuft auf diesem Dispatcher,
     * nicht auf dem Main-Thread. Der UI-Zustand bleibt ueber
     * `MutableStateFlow` thread-sicher; das Set-Logging (z. B. Robolectric)
     * kann denselben Test-Dispatcher uebergeben.
     */
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    /**
     * RC-7/RC-17: Diagnose-Snapshot des zuletzt GESTOPPTEN Sets. Gefuellt in
     * [stop], geleert in [start]/[abort]. Die Train-UI zeigt daraus den
     * Satz-Report, das Diagnose-Panel den letzten Stand.
     */
    private val _lastDiagnostics = MutableStateFlow<SetDiagnostics?>(null)
    val lastDiagnostics: StateFlow<SetDiagnostics?> = _lastDiagnostics.asStateFlow()

    private var engine: ExerciseEnginePipeline? = null

    /**
     * C14 (T-14): Live-Rep-Events des laufenden Sets fuer die UI (Peak-Blitz,
     * Rep-Hero). Kein Replay (extraBufferCapacity), damit ein spaet
     * abonnierender Screen keine alten Blitze nachholt.
     */
    private val _repEvents = MutableSharedFlow<RepEvent>(extraBufferCapacity = 16)
    val repEvents: SharedFlow<RepEvent> = _repEvents.asSharedFlow()

    private var exerciseId: Long = -1L
    private var deviceId: String? = null
    private var profileRevision: Int = 0
    private var engineVersion: RepEngineVersion = RepEngineVersion.V2_RELIABLE
    private val bufferedSamples = mutableListOf<SensorSample>()
    private val bufferedRepEvents = mutableListOf<RepEvent>()

    /**
     * Befund 5.5: `cancel()` ist kooperativ — ein abgebrochener Collector
     * kann noch in `add()` stehen, waehrend [abort]/[start] leeren. Ein
     * einziges Lock fuer beide Puffer (keine Lock-Ordnung noetig) macht
     * Pruefen+Anhaengen atomar und `clear()`/`toList()` konfliktfrei.
     * Kurze kritische Abschnitte, nie suspend — kein Deadlock-Risiko.
     */
    private val bufferLock = Any()
    private var startedAtMs: Long = 0L

    /**
     * B2 (RC-19): die am Ende des letzten Sets gemessene Rep-Periode (ms)
     * samt Zuordnung. Sie seedet beim naechsten Set DESSELBEN Geraets die
     * Qualitaets-Erwartung ([ExerciseEngineConfig.qualityDurationMs]);
     * Refraktaerzeit und Pending-Deckel bleiben am Mittelwert (5.14).
     * Gesetzt in [stop], verworfen in [abort].
     */
    private var lastMeasuredPeriodMs: Double? = null
    private var lastMeasuredPeriodExerciseId: Long = -1L
    private var lastMeasuredPeriodDeviceId: String? = null

    /**
     * B2 (RC-19): Periode (ms), mit der der LAUFENDE Satz seine
     * Qualitaets-Erwartung gestartet hat; null = Profilwert. Diagnose/Test —
     * die Zaehlung selbst haengt nicht daran.
     */
    var appliedQualityPeriodMs: Double? = null
        private set

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
        // B2 (RC-19): die gemessene Periode nur fuer dasselbe Geraet/UEbung
        // uebernehmen - eine Periode aus einem anderen Aufbau waere eine
        // falsche Erwartung.
        val periodSeed =
            lastMeasuredPeriodMs?.takeIf {
                exerciseId == lastMeasuredPeriodExerciseId && deviceId == lastMeasuredPeriodDeviceId
            }
        appliedQualityPeriodMs = periodSeed
        engine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = profile.rotationAxis,
                    gyroBias = profile.gyroBias,
                    expectedProminence = profile.expectedProminence,
                    expectedDurationMs = profile.expectedDurationMs,
                    // B2 (RC-19): nur die Qualitaets-Erwartung seeden;
                    // Refraktaerzeit/Pending bleiben am Profil-/Mittelwert.
                    qualityDurationMs = periodSeed,
                    detectionThreshold = profile.detectionThreshold,
                    hasValidCalibration = true,
                    // B3 (RC-20): die drei Schwellen kommen aus dem Profil,
                    // nicht mehr aus den Code-Defaults; Altprofile wurden beim
                    // Laden mit genau diesen Defaults hochgezogen.
                    templateThreshold = profile.templateThreshold,
                    minQualityScore = profile.minQualityScore,
                    dtwBand = profile.dtwBand,
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
        // RC-7: gleiches gilt fuer den Diagnose-Snapshot des Vorgaengers.
        _lastDiagnostics.value = null
        // Befund 5.5: unter dem Puffer-Lock — ein noch auslaufender
        // Collector des Vorgaenger-Sets kann nicht zwischen Pruefung und
        // Leeren schreiben.
        synchronized(bufferLock) {
            bufferedSamples.clear()
            bufferedRepEvents.clear()
        }
        _phase.value = ActiveSetPhase.COUNTDOWN
        _countdownRemaining.value = countdownSeconds

        val activeEngine = engine
        eventJob =
            scope.launch {
                activeEngine?.repEvents?.collect { event ->
                    synchronized(bufferLock) { bufferedRepEvents.add(event) }
                    // C14 (T-14): die UI speist ihren Peak-Blitz aus dem
                    // echten Rep-Event statt aus einer Flanken-Heuristik.
                    _repEvents.tryEmit(event)
                }
            }
        // RC-1: die Zaehlpipeline laeuft off-main; der Collector selbst
        // startet im uebergebenen Scope, verarbeitet aber auf dem Worker.
        sampleJob =
            scope.launch(workerDispatcher) {
                samples.collect { onSample(it) }
            }
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

    /**
     * Befund 5.5: Pruefung (Phase, Engine) und Anhaengen laufen atomar
     * unter dem Puffer-Lock. Ein paralleler [abort] kann dazwischen weder
     * leeren noch die Engine entfernen — kein Streusample im geleerten
     * Puffer, keine `ConcurrentModificationException`. Die CPU-Arbeit der
     * Engine bleibt bewusst AUSSERHALB des Locks.
     */
    private fun onSample(sample: SensorSample) {
        val activeEngine =
            synchronized(bufferLock) {
                if (_phase.value != ActiveSetPhase.COUNTING) return
                val current = engine ?: return
                bufferedSamples.add(sample)
                current
            }
        activeEngine.processSample(
            sample.timestampMs,
            sample.gx,
            sample.gy,
            sample.gz,
            sample.ax,
            sample.ay,
            sample.az,
        )
        _countedReps.value = activeEngine.repCount.value
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
     *
     * B5/RC-1: suspend. Die Phase faellt VOR dem Join auf IDLE (es wird kein
     * Sample mehr angenommen), danach wartet [cancelJobsAndJoin] den
     * Sample-Worker wirklich ab — `cancel()` allein ist kooperativ und liesse
     * ihn waehrend der Leseoperationen weiter in Puffer und Engine schreiben.
     * Die Plausibilitaet rechnet auf dem [workerDispatcher], nicht auf dem
     * Aufrufer-Thread.
     */
    suspend fun stop(): Int {
        _phase.value = ActiveSetPhase.IDLE
        _countdownRemaining.value = 0
        cancelJobsAndJoin()
        val activeEngine = engine
        val plausibility =
            if (activeEngine == null) {
                null
            } else {
                withContext(workerDispatcher) { activeEngine.checkPlausibility() }
            }
        _lastPlausibility.value = plausibility
        // B2 (RC-19): die Periode fuer den naechsten Satz merken (nur
        // Qualitaets-Erwartung, Entscheidung 5.14).
        lastMeasuredPeriodMs =
            plausibility
                ?.periodSeconds
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.times(1_000.0)
        lastMeasuredPeriodExerciseId = exerciseId
        lastMeasuredPeriodDeviceId = deviceId
        // RC-7/RC-17: den Diagnose-Snapshot beim Stoppen einfrieren - danach
        // kann die UI den Satz-Report zeigen, auch wenn noch nicht geloggt ist.
        _lastDiagnostics.value = diagnosticsSnapshot()
        return _countedReps.value
    }

    /**
     * RC-7/RC-17: Momentaufnahme aller Diagnose-Zaehler des stehenden
     * Motors. null, wenn kein Set laeuft/stand.
     */
    private fun diagnosticsSnapshot(): SetDiagnostics? {
        val activeEngine = engine ?: return null
        return SetDiagnostics(
            countedReps = _countedReps.value,
            framesProcessed = activeEngine.framesProcessed,
            framesRejected = activeEngine.framesRejected,
            largeGapCount = activeEngine.largeGapCount,
            zuptBiasUpdates = activeEngine.zuptBiasUpdates,
            zuptAbortedPending = activeEngine.zuptAbortedPending,
            measuredSampleRateHz = activeEngine.estimatedSampleRateHz,
            signalQuality = _currentSignalQuality.value,
            rejectionCounts = activeEngine.rejectionCountsSnapshot,
            plausibility = _lastPlausibility.value,
        )
    }

    /**
     * Bricht das aktive Set atomar ab (Umbauplan Phase 6 / P0-Fix):
     * Countdown, Engine, Zaehlstand, Puffer und Phase werden GEMEINSAM
     * zurueckgesetzt. Idempotent; darf aus jedem Zustand aufgerufen werden.
     *
     * B5: bewusst synchron (ohne Join) — sie laeuft auch aus `onCleared()`,
     * wo der Scope bereits gecancelt ist und ein suspend/join wirkungslos
     * waere. `runBlocking` auf Main ist tabu; die Collector sterben durch
     * `cancel()` und fassen den dann geleerten Zustand nicht mehr an.
     *
     * Befund 5.5: `cancel()` allein schliesst das Race nicht — ein gerade in
     * [onSample] stehender Worker kann noch schreiben, waehrend hier
     * geleert wird. Deshalb schliesst [abort] zuerst Phase (IDLE) und Engine
     * (null): spaete Samples scheitern an der atomaren Pruefung in
     * [onSample]. Das Leeren selbst laeuft unter [bufferLock], ein Join ist
     * weiterhin nicht noetig und die onCleared-Tauglichkeit bleibt.
     */
    fun abort(reason: SetAbortReason) {
        _phase.value = ActiveSetPhase.IDLE
        engine = null
        cancelJobs()
        lastAbortReason = reason
        streamProvenGood = false
        _countdownRemaining.value = 0
        _countedReps.value = 0
        // Umbauplan 2026-09-04 Phase 7: die Zweitmeinung gehoert zum
        // Zaehlstand. Bleibt sie beim Abbruch stehen, waehrend `_countedReps`
        // auf 0 faellt, zeigt die UI eine Aussage ueber einen Satz an, den es
        // nicht mehr gibt. Erst seit die Zweitmeinung sichtbar ist, hat dieser
        // Zustand eine Wirkung nach draussen — deshalb hier und nicht in
        // Phase 6.
        _lastPlausibility.value = null
        // RC-7: der Diagnose-Snapshot gehoert ebenfalls zum Zaehlstand.
        _lastDiagnostics.value = null
        // B2 (RC-19): [lastMeasuredPeriodMs] bleibt bewusst stehen — sie
        // gehoert zum letzten per [stop] ABGESCHLOSSENEN Satz, nicht zu
        // diesem Abbruch (finishAndTakeTrace ruft hier CLEARED). Nur die
        // Zuordnung des laufenden Satzes wird geleert.
        appliedQualityPeriodMs = null
        synchronized(bufferLock) {
            bufferedSamples.clear()
            bufferedRepEvents.clear()
        }
        exerciseId = -1L
        deviceId = null
        profileRevision = 0
        startedAtMs = 0L
    }

    /**
     * Friert den Set-Mitschnitt ein (unveraenderliche Kopien) und raeumt den
     * Controller auf. Liefert null, wenn kein Set lief (nichts zu lernen).
     *
     * B5/RC-1: suspend. Erst die Phase schliessen, dann den Sample-Worker per
     * Join beenden, dann den Puffer kopieren — sonst koennte der Worker
     * waehrend `toList()` noch Samples anhaengen (Teilkopie) oder nach dem
     * `clear()` in [abort] noch eines nachtragen.
     */
    suspend fun finishAndTakeTrace(): SetTrace? {
        val activeEngine =
            engine ?: run {
                _phase.value = ActiveSetPhase.IDLE
                return null
            }
        _phase.value = ActiveSetPhase.IDLE
        _countdownRemaining.value = 0
        cancelJobsAndJoin()
        // Ein paralleler abort/start hat den Motor inzwischen ersetzt: Puffer
        // und Zaehler gehoeren dann nicht mehr zu diesem Mitschnitt.
        if (engine !== activeEngine) return null
        // P2-Fix #19/#21: Zweitmeinung und gemessene Rate gehoeren in den
        // Mitschnitt. `stop()` hat die Pruefung eventuell schon gerechnet -
        // dann den vorhandenen Wert wiederverwenden statt erneut ueber das
        // Signal zu laufen.
        val plausibility =
            _lastPlausibility.value ?: withContext(workerDispatcher) { activeEngine.checkPlausibility() }
        val measuredRateHz = activeEngine.estimatedSampleRateHz ?: SampleRateEstimator.NOMINAL_RATE_HZ
        // RC-7: den beim Stop eingefrorenen Snapshot bevorzugen; nur wenn
        // direkt (ohne stop) abgeschlossen wird, jetzt einen ziehen.
        val diagnostics = _lastDiagnostics.value ?: diagnosticsSnapshot()
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
                diagnostics = diagnostics,
            )
        abort(SetAbortReason.CLEARED)
        return trace
    }

    /** Fuer onCleared(): raeumt Jobs und Zustand vollstaendig ab. */
    fun close() {
        abort(SetAbortReason.CLEARED)
    }

    /** Bricht alle Collector ab, ohne auf sie zu warten (start/abort). */
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

    /**
     * B5: beendet alle Collector und WARTET auf ihren Abschluss. Der
     * Sample-Collector wird zuerst abgewartet — er schreibt als Einziger in
     * Puffer und Engine. Nur so sind die Leseoperationen von [stop] und
     * [finishAndTakeTrace] frei von gleichzeitigen Schreibern.
     */
    private suspend fun cancelJobsAndJoin() {
        val jobs = listOf(sampleJob, eventJob, healthJob, connectionJob, countdownJob)
        countdownJob = null
        sampleJob = null
        eventJob = null
        healthJob = null
        connectionJob = null
        jobs.forEach { it?.cancel() }
        jobs.forEach { it?.join() }
    }

    companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 3
    }
}
