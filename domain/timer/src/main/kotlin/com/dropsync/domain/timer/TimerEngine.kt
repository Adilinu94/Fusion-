package com.dropsync.domain.timer

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Gemeinsamer Timerkern fuer NORMAL, REST und DROPSYNC
 * (Bauplan Schritt 7): eine Zustandsmaschine, deterministisch testbar,
 * ohne Android-Abhaengigkeit.
 *
 * - NORMAL/REST rechnen ausschliesslich mit der monotonen Uhr
 *   (elapsedRealtime); `evaluate()` ist idempotent und darf von jedem
 *   UI-Tick aufgerufen werden — `delay()` ist nie die Abschlussquelle.
 * - DROPSYNC erhaelt seine Ereignisse ausschliesslich von aussen
 *   (PlayerMessage -> [onThresholdReached]); der sichtbare Countdown ist
 *   eine Projektion der Playerposition ([projectRemaining]).
 * - `deliveredCueIds` garantiert genau eine Ausgabe pro Grenzwert;
 *   veraltete Callbacks nach cancel/reset werden ueber Sitzungs-ID und
 *   RUNNING-Pruefung entwertet (Schritt 7.5, Abnahme 3).
 */
class TimerEngine(
    private val clock: Clock,
    private val cueOutput: CueOutput,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableState = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = mutableState.asStateFlow()

    private val deliveredCueIds = mutableSetOf<String>()

    /** Monotones Fristende (nur NORMAL/REST). */
    private var endElapsedRealtimeMs: Long? = null

    /** Monotones Ende der optionalen Get-Ready-Vorbereitungsphase (B9). */
    private var prepEndElapsedRealtimeMs: Long? = null

    /** Cues der Vorbereitungsphase (3-2-1); getrennt von den Laufcues. */
    private var prepCues: List<PlannedCue> = emptyList()

    /** Eingefrorene Restzeit waehrend PAUSED. */
    private var pausedRemainingMs: Long? = null

    /**
     * Startet einen NORMAL- oder REST-Timer. Ohne Vorbereitungszeit
     * ([prepMs] <= 0) geht er sofort auf RUNNING; mit [prepMs] > 0 laeuft
     * zuerst eine Get-Ready-Phase (PREPARING, 3-2-1 mit Haptik/Ton, B9),
     * die `evaluate()` monoton herunterzaehlt und danach auf RUNNING
     * schaltet.
     */
    fun start(
        mode: TimerMode,
        durationMs: Long,
        prepMs: Long = 0,
    ): AppResult<TimerSession> {
        require(mode != TimerMode.DROPSYNC) { "DropSync startet ueber startDropSync" }
        return beginSession(mode, durationMs, markerPositionMs = null, prepMs = prepMs) { session ->
            if (prepMs > 0) {
                prepEndElapsedRealtimeMs = clock.elapsedRealtimeMs() + prepMs
                mutableState.value =
                    TimerState(TimerStatus.PREPARING, session, remainingMs = prepMs)
            } else {
                endElapsedRealtimeMs = clock.elapsedRealtimeMs() + durationMs
                transitionTo(TimerStatus.RUNNING)
                mutableState.value =
                    TimerState(TimerStatus.RUNNING, session, remainingMs = durationMs)
            }
        }
    }

    /**
     * Startet einen DROPSYNC-Timer; bleibt in PREPARING, bis der
     * Koordinator nach `player.isPlaying == true` [markRunning] ruft
     * (Bauplan 5.2). Vorbedingung: 5_000 <= Dauer <= Markerposition.
     */
    fun startDropSync(
        requestedDurationMs: Long,
        markerPositionMs: Long,
    ): AppResult<TimerSession> {
        if (requestedDurationMs < MIN_DROPSYNC_DURATION_MS || requestedDurationMs > markerPositionMs) {
            return AppResult.failure(
                AppError.Unknown("Ungueltige DropSync-Dauer: $requestedDurationMs"),
            )
        }
        return beginSession(TimerMode.DROPSYNC, requestedDurationMs, markerPositionMs) { session ->
            mutableState.value =
                TimerState(TimerStatus.PREPARING, session, remainingMs = requestedDurationMs)
        }
    }

    /** PREPARING -> RUNNING; nur fuer die aktive Sitzung. */
    fun markRunning(sessionId: String): Boolean {
        val current = mutableState.value
        if (current.session?.id != sessionId || current.status != TimerStatus.PREPARING) return false
        transitionTo(TimerStatus.RUNNING)
        mutableState.value = current.copy(status = TimerStatus.RUNNING)
        return true
    }

    /**
     * Idempotente Neubewertung fuer NORMAL/REST: liefert faellige Cues
     * genau einmal und schliesst bei Fristablauf genau einmal ab.
     * Verzoegerte oder gehaeufte UI-Ticks veraendern das Ergebnis nicht.
     */
    fun evaluate() {
        val current = mutableState.value
        val session = current.session ?: return
        if (session.mode == TimerMode.DROPSYNC) return
        when (current.status) {
            TimerStatus.PREPARING -> evaluatePreparing(current, session)
            TimerStatus.RUNNING -> evaluateRunning(current, session)
            else -> Unit
        }
    }

    /** RUNNING-Neubewertung (NORMAL/REST): faellige Cues + Abschluss. */
    private fun evaluateRunning(
        current: TimerState,
        session: TimerSession,
    ) {
        val end = endElapsedRealtimeMs ?: return
        val remaining = maxOf(0, end - clock.elapsedRealtimeMs())
        deliverDue(session, remaining, session.plannedCues)
        if (remaining <= 0) {
            transitionTo(TimerStatus.COMPLETED)
            mutableState.value = current.copy(status = TimerStatus.COMPLETED, remainingMs = 0)
        } else {
            mutableState.value = current.copy(remainingMs = remaining)
        }
    }

    /**
     * Get-Ready-Countdown (B9): zaehlt die Vorbereitungsphase monoton
     * herunter (3-2-1) und schaltet bei Ablauf auf RUNNING. Eine
     * DropSync-PREPARING (ohne [prepEndElapsedRealtimeMs]) bleibt
     * unberuehrt — sie wird ueber [markRunning] freigegeben.
     */
    private fun evaluatePreparing(
        current: TimerState,
        session: TimerSession,
    ) {
        val prepEnd = prepEndElapsedRealtimeMs ?: return
        val remaining = maxOf(0, prepEnd - clock.elapsedRealtimeMs())
        deliverDue(session, remaining, prepCues)
        if (remaining <= 0) {
            // Vorbereitung vorbei: Laufphase beginnt jetzt. Cue-Merker
            // leeren (Vorlaufcues 3/2/1 teilen Grenzwerte mit dem Lauf)
            // und die monotone Startzeit auf jetzt setzen.
            deliveredCueIds.clear()
            prepEndElapsedRealtimeMs = null
            val now = clock.elapsedRealtimeMs()
            endElapsedRealtimeMs = now + session.durationMs
            val running = session.copy(startedElapsedRealtimeMs = now)
            transitionTo(TimerStatus.RUNNING)
            mutableState.value =
                TimerState(TimerStatus.RUNNING, running, remainingMs = session.durationMs)
        } else {
            mutableState.value = current.copy(remainingMs = remaining)
        }
    }

    /**
     * Externer Trigger aus der Media3-Zeitlinie (nur DROPSYNC, 5.2/7.4).
     * Prueft Sitzungs-ID und RUNNING, bevor irgendetwas ausgegeben wird
     * (Schritt 7.5); `thresholdMs == 0` schliesst genau einmal ab.
     */
    fun onThresholdReached(
        sessionId: String,
        thresholdMs: Long,
    ) {
        val current = mutableState.value
        val session = current.session ?: return
        if (session.id != sessionId || current.status != TimerStatus.RUNNING) return

        val cue = session.plannedCues.firstOrNull { it.thresholdMs == thresholdMs } ?: return
        deliver(session, cue)
        if (thresholdMs == 0L) {
            transitionTo(TimerStatus.COMPLETED)
            mutableState.value = current.copy(status = TimerStatus.COMPLETED, remainingMs = 0)
        }
    }

    /** UI-Projektion der Playerposition (nur DROPSYNC, 5.2/5). */
    fun projectRemaining(
        sessionId: String,
        remainingMs: Long,
    ) {
        val current = mutableState.value
        if (current.session?.id != sessionId || current.status != TimerStatus.RUNNING) return
        mutableState.value = current.copy(remainingMs = maxOf(0, remainingMs))
    }

    fun pause(): Boolean {
        val current = mutableState.value
        if (current.status != TimerStatus.RUNNING) return false
        if (current.session?.mode != TimerMode.DROPSYNC) {
            val end = endElapsedRealtimeMs ?: return false
            pausedRemainingMs = maxOf(0, end - clock.elapsedRealtimeMs())
        }
        transitionTo(TimerStatus.PAUSED)
        mutableState.value =
            current.copy(
                status = TimerStatus.PAUSED,
                remainingMs = pausedRemainingMs ?: current.remainingMs,
            )
        return true
    }

    fun resume(): Boolean {
        val current = mutableState.value
        if (current.status != TimerStatus.PAUSED) return false
        if (current.session?.mode != TimerMode.DROPSYNC) {
            val remaining = pausedRemainingMs ?: return false
            endElapsedRealtimeMs = clock.elapsedRealtimeMs() + remaining
            pausedRemainingMs = null
        }
        transitionTo(TimerStatus.RUNNING)
        mutableState.value = current.copy(status = TimerStatus.RUNNING)
        return true
    }

    /**
     * Abbruch (Schritt 7.7): entwertet alle kuenftigen Trigger ueber den
     * Zustandswechsel, stoppt TTS und macht aktives Ducking rueckgaengig.
     */
    fun cancel(reason: CancelReason): Boolean {
        val current = mutableState.value
        if (!TimerTransitions.isAllowed(current.status, TimerStatus.CANCELLED)) return false
        current.session?.let { cueOutput.stopAll(it.id) }
        mutableState.value = current.copy(status = TimerStatus.CANCELLED, cancelReason = reason)
        return true
    }

    /** Player-Fehler, Seek, Queue-Wechsel, Songende vor Marker (5.2). */
    fun fail(): Boolean {
        val current = mutableState.value
        if (!TimerTransitions.isAllowed(current.status, TimerStatus.FAILED)) return false
        current.session?.let { cueOutput.stopAll(it.id) }
        mutableState.value = current.copy(status = TimerStatus.FAILED)
        return true
    }

    /** Endzustand -> IDLE (Schritt 7.2); loescht Sitzung und Cue-Merker. */
    fun reset(): Boolean {
        if (!TimerTransitions.isResettable(mutableState.value.status)) return false
        deliveredCueIds.clear()
        endElapsedRealtimeMs = null
        prepEndElapsedRealtimeMs = null
        prepCues = emptyList()
        pausedRemainingMs = null
        mutableState.value = TimerState()
        return true
    }

    private fun beginSession(
        mode: TimerMode,
        durationMs: Long,
        markerPositionMs: Long?,
        prepMs: Long = 0,
        onPrepared: (TimerSession) -> Unit,
    ): AppResult<TimerSession> {
        when (mutableState.value.status) {
            TimerStatus.RUNNING, TimerStatus.PREPARING, TimerStatus.PAUSED -> {
                // Nur eine aktive Instanz (Bauplan 5.2/4).
                return AppResult.failure(AppError.TimerConflict)
            }

            TimerStatus.COMPLETED, TimerStatus.CANCELLED, TimerStatus.FAILED -> {
                return AppResult.failure(
                    AppError.Unknown("Endzustand verlangt reset vor Neustart (Schritt 7.2)"),
                )
            }

            TimerStatus.IDLE -> {
                Unit
            }
        }
        if (durationMs <= 0) {
            return AppResult.failure(AppError.Unknown("Dauer muss positiv sein: $durationMs"))
        }
        deliveredCueIds.clear()
        pausedRemainingMs = null
        endElapsedRealtimeMs = null
        prepEndElapsedRealtimeMs = null
        prepCues = if (prepMs > 0) CuePlanner.planPrep(prepMs) else emptyList()
        val session =
            TimerSession(
                id = idGenerator(),
                mode = mode,
                durationMs = durationMs,
                startedElapsedRealtimeMs =
                    if (mode == TimerMode.DROPSYNC) null else clock.elapsedRealtimeMs(),
                markerPositionMs = markerPositionMs,
                plannedCues = CuePlanner.plan(mode, durationMs),
            )
        transitionTo(TimerStatus.PREPARING)
        mutableState.value = TimerState(TimerStatus.PREPARING, session, durationMs)
        onPrepared(session)
        return AppResult.success(session)
    }

    /**
     * Liefert alle ueberschrittenen Grenzwerte genau einmal. Werden durch
     * einen verspaeteten Tick mehrere Grenzwerte gleichzeitig faellig,
     * wird nur der juengste (kleinste) ausgegeben; aeltere werden ohne
     * Ausgabe entwertet, damit nach Doze keine Ansageflut entsteht.
     */
    private fun deliverDue(
        session: TimerSession,
        remainingMs: Long,
        cues: List<PlannedCue>,
    ) {
        val due =
            cues.filter {
                remainingMs <= it.thresholdMs && it.cueId(session.id) !in deliveredCueIds
            }
        if (due.isEmpty()) return
        val newest = due.minBy { it.thresholdMs }
        for (cue in due) {
            if (cue == newest) {
                deliver(session, cue)
            } else {
                deliveredCueIds += cue.cueId(session.id)
            }
        }
    }

    private fun deliver(
        session: TimerSession,
        cue: PlannedCue,
    ) {
        val cueId = cue.cueId(session.id)
        if (!deliveredCueIds.add(cueId)) return
        if (cue.speak) cueOutput.speak(session.id, (cue.thresholdMs / 1000).toInt())
        if (cue.haptic) cueOutput.haptic(session.id)
        if (cue.tone) cueOutput.tone(session.id)
    }

    private fun transitionTo(target: TimerStatus) {
        val from = mutableState.value.status
        check(TimerTransitions.isAllowed(from, target)) {
            "Verbotener Uebergang: $from -> $target"
        }
    }

    /**
     * Kill-Fallback (Testinfrastruktur-Umbauplan Schritt 2, 5b): gibt den
     * persistierbaren Zustand eines laufenden NORMAL/REST-Timers zurueck.
     *
     * Liefert `null`, wenn kein rehydrierbarer Timer laeuft — also bei IDLE,
     * einem Endzustand (COMPLETED/CANCELLED/FAILED), PREPARING oder DROPSYNC.
     * DROPSYNC ist bewusst ausgenommen: seine Ereignisquelle (Player-Session)
     * existiert nach einem Kill nicht mehr und muss neu aufgebaut werden.
     */
    fun snapshot(): TimerSnapshot? {
        val current = mutableState.value
        val session = current.session ?: return null
        if (session.mode == TimerMode.DROPSYNC) return null
        return when (current.status) {
            TimerStatus.RUNNING ->
                TimerSnapshot(
                    session = session,
                    status = TimerStatus.RUNNING,
                    endElapsedRealtimeMs = endElapsedRealtimeMs,
                    pausedRemainingMs = null,
                )

            TimerStatus.PAUSED ->
                TimerSnapshot(
                    session = session,
                    status = TimerStatus.PAUSED,
                    endElapsedRealtimeMs = null,
                    pausedRemainingMs = pausedRemainingMs,
                )

            else -> null
        }
    }

    /**
     * Rehydriert einen NORMAL/REST-Timer aus einem persistierten [TimerSnapshot]
     * (Kill-Fallback 5b). Stellt die monotone Frist gegenueber der jetzigen
     * monotonen Uhr wieder her, ohne dass ein Uhrzeit-Wechsel verfaelscht.
     *
     * Verhalten nach Status:
     * - RUNNING: Frist = jetzt + verbliebene Restzeit; bereits abgelaufene
     *   Timer werden sofort auf COMPLETED gesetzt (evaluate() schliesst ab).
     * - PAUSED: bleibt PAUSED mit der eingefrorenen Restzeit.
     *
     * Liefert `false` (ohne Zustandsaenderung), wenn das Snapshot ungueltig
     * ist (DROPSYNC, negative Restzeit) oder bereits eine aktive Sitzung
     * laeuft. Der Aufrufer hat zuvor ueber [MonotonicStateStore] einen
     * Reboot ausgeschlossen; bei Ruecksprung der monotonen Uhr ist das
     * Snapshot zu verwerfen statt [restore] zu rufen.
     */
    fun restore(snapshot: TimerSnapshot): Boolean {
        val session = snapshot.session
        if (session.mode == TimerMode.DROPSYNC) return false
        if (mutableState.value.status != TimerStatus.IDLE) return false

        return when (snapshot.status) {
            TimerStatus.RUNNING -> {
                val end = snapshot.endElapsedRealtimeMs ?: return false
                val now = clock.elapsedRealtimeMs()
                val remaining = end - now
                // Ablauf waehrend des Kill ist erlaubt: remaining <= 0 wird
                // restauriert und das erste evaluate() schliesst ab (COMPLETED).
                deliveredCueIds.clear()
                pausedRemainingMs = null
                prepEndElapsedRealtimeMs = null
                prepCues = emptyList()
                endElapsedRealtimeMs = end
                mutableState.value =
                    TimerState(TimerStatus.RUNNING, session, remainingMs = maxOf(0, remaining))
                true
            }

            TimerStatus.PAUSED -> {
                val paused = snapshot.pausedRemainingMs ?: return false
                if (paused < 0) return false
                deliveredCueIds.clear()
                pausedRemainingMs = paused
                endElapsedRealtimeMs = null
                prepEndElapsedRealtimeMs = null
                prepCues = emptyList()
                mutableState.value =
                    TimerState(TimerStatus.PAUSED, session, remainingMs = paused)
                true
            }

            else -> false
        }
    }

    companion object {
        /** Untere Grenze der DropSync-Dauer (Bauplan 5.2/3). */
        const val MIN_DROPSYNC_DURATION_MS: Long = 5_000
    }
}
