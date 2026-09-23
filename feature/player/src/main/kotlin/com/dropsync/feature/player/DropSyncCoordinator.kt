package com.dropsync.feature.player

import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.playback.DropLandingEvent
import com.dropsync.domain.playback.PlaybackGeneration
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RestDuckingGate
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.ChainSegmentKind
import com.dropsync.domain.timer.DropLandingPlan
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropRestRequestBus
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncPlanKind
import com.dropsync.domain.timer.DropSyncPlanMarker
import com.dropsync.domain.timer.DropSyncPlanStore
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.DropSyncStateSource
import com.dropsync.domain.timer.OverrideReason
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Der eine DropSync-Koordinator (Verbesserungsplan A.3): Planung,
 * Ausfuehrung und **Zustand** an einer Stelle statt drei halber Wege.
 *
 * Aufgaben:
 * - Rest-Queue setzen und Pausenmusik ducken (Musik-Workout-Plan Phase 4/7).
 * - Landung planen ([DropLandingPlanner]) und auf der Audio-Uhr armieren
 *   ([PlaybackRepository.armLanding], MP-3); ein Watchdog bleibt Fallback.
 * - Nutzer-Vorrang: Pause, Skip, Seek und fremde Queue-Aenderungen beenden
 *   den Plan sichtbar ([DropSyncState.Overridden], MP-5).
 * - Restzeit-Aenderungen (`+15 s`, Pause/Resume) rechnen in den Plan ein
 *   (MP-15); beim Resume bleibt der laufende Titel stehen.
 * - Der Zustand ist app-weit sichtbar ([state]) und speist UI und Diagnose.
 *
 * Die Zeitrechnung nutzt die monotone Uhr ([Clock]); `delay()` ist nur
 * Transport, nie Wahrheit. Ein Nutzer-Eingriff bricht die Landung ab.
 */
@Singleton
class DropSyncCoordinator
    @Inject
    constructor(
        private val timerEngine: TimerEngine,
        private val restMusicSettings: RestMusicSettingsRepository,
        private val playbackRepository: PlaybackRepository,
        private val restDucking: RestDuckingGate,
        private val dropRestRequests: DropRestRequestBus,
        private val planner: DropSyncPlanner,
        private val dropRestMonitor: DropRestSessionMonitor,
        private val planStore: DropSyncPlanStore,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) : DropSyncStateSource {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        private var started = false

        private val mutableState = MutableStateFlow<DropSyncState>(DropSyncState.Off)

        /** App-weiter DropSync-Zustand fuer UI und Diagnose (Design 4.5). */
        override val state: StateFlow<DropSyncState> = mutableState.asStateFlow()

        /** ID der Rest-Sitzung, die wir gerade steuern; null = untaetig. */
        private var activeSessionId: String? = null

        /** True, sobald wir die Queue tatsaechlich uebernommen haben. */
        private var controlling = false

        /** True, sobald der Work-Titel per Drop-Landung gestartet wurde. */
        private var landed = false

        /** Fallback-Landung, wenn keine Armierung moeglich war. */
        private var landingJob: Job? = null

        /**
         * C16: geplante Zwischenwechsel der Ueberleitungskette (deadline-
         * basiert, grob) — die letzte Landung bleibt die Armierung.
         */
        private var chainJobs: List<Job> = emptyList()

        /** C16: Song-IDs der aktiven Kette; geplante Wechsel sind kein Override. */
        private var chainSongIds: Set<Long> = emptySet()

        /**
         * C16 (5.17): Wiedergabe-Liste VOR dem Wechsel auf die
         * Pausen-Playlist — die Kandidatenquelle der Kette. Die
         * Pausen-Playlist bleibt das Bett, bis der erste Kettenwechsel
         * greift.
         */
        private var chainSource: ChainSource? = null

        /** C16: eingefrorene Wiedergabe-Liste (Queue + Position). */
        private data class ChainSource(
            val queue: List<QueueItem>,
            val currentIndex: Int,
            val positionMs: Long,
            val currentSongId: Long?,
        )

        /**
         * Generation-Token (Design Phase 6): Skip, Satzwechsel und
         * Route-Wechsel erhoehen die Generation; laufende Landungen
         * vergleichen und verwerfen veraltete Events.
         */
        private var generation = PlaybackGeneration.INITIAL

        /**
         * Drop-Auto (Entscheidung 19.09.2026, MP-1): eine per Bus
         * angeforderte Landung fuer die naechste Pause. Gilt fuer genau eine
         * Pause und wirkt auch dann, wenn das globale Verhalten NORMAL ist.
         */
        private var pendingDropAuto = false

        /** Armierungs-Token fuer [DropSyncState.Armed]. */
        private var armedToken = 0L

        /** Aktiver Plan und Ziel-Titel fuer den Watchdog-Fallback. */
        private var activePlan: DropLandingPlan? = null
        private var activeWorkSong: Song? = null
        private var targetElapsedRealtimeMs = 0L

        /** Queue der laufenden Rest-Playlist; Basis der Override-Erkennung. */
        private var restQueueSongIds: List<Long> = emptyList()

        /** Letzte Wiedergabeprobe waehrend des Plans (Seek-Erkennung). */
        private var lastSample: PositionSample? = null

        /** True, sobald die Wiedergabe im Plan einmal lief (Pause-Erkennung). */
        private var sawPlaying = false

        /** Restzeit und Zeitpunkt der letzten Planung (MP-15). */
        private var plannedRemainingMs = -1L
        private var plannedAtElapsedMs = 0L

        /** Nach einer Pause muss der Plan beim Resume neu armiert werden. */
        private var needsReplanOnResume = false

        /**
         * C13: Marker aus dem letzten Lauf (beim Start geladen); null,
         * sobald er fuer seine Sitzung konsumiert wurde.
         */
        private var recoveredMarker: DropSyncPlanMarker? = null

        /** C13: Sitzung, die aus dem Kill rekonstruiert wurde (fuer PLAN_LOST). */
        private var recoveredSessionId: String? = null

        /** C13: laufende manuelle DropRest-Sitzung (Marker-Wache). */
        private var manualMarkerSessionId: String? = null

        /** Startet die Beobachtung genau einmal (App-Start). */
        fun start() {
            if (started) return
            started = true
            scope.launch {
                // C13: Erst den Marker laden, dann beobachten. Die StateFlow
                // liefert danach sofort den aktuellen (ggf. rehydrierten)
                // Timerzustand — kein Rennen mit dem Kill-Fallback.
                recoveredMarker = planStore.load()
                subscribe()
            }
        }

        private fun subscribe() {
            scope.launch {
                combine(timerEngine.state, restMusicSettings.behavior) { state, behavior ->
                    state to behavior
                }.collect { (state, behavior) -> onState(state, behavior) }
            }
            scope.launch {
                dropRestRequests.requests.collect { onDropAutoRequested() }
            }
            scope.launch {
                playbackRepository.landingEvents.collect { onLandingEvent(it) }
            }
            scope.launch {
                playbackRepository.state.collect { onPlaybackState(it) }
            }
        }

        /**
         * C13: beendet die Koordinator-Jobs (Tests, App-Teardown). Der
         * Koordinator ist ein Singleton; ohne close() leben seine
         * Beobachter bis zum Prozessende.
         */
        fun close() {
            scope.cancel()
        }

        /**
         * C13: Wird vom App-Start nach dem Kill-Fallback gerufen. Wurde die
         * Sitzung aus dem Marker nicht rekonstruiert, wird sie einmalig als
         * "Plan verloren" gemeldet (Entscheidungen 5.8/5.9); ein manueller
         * DropRest wird bewusst nicht wiederhergestellt, aber gemeldet.
         */
        suspend fun onRecoveryFinished() {
            val marker = recoveredMarker ?: return
            val session = timerEngine.state.value.session
            val restored =
                session != null &&
                    session.mode == TimerMode.REST &&
                    session.id == marker.sessionId
            if (restored) return
            mutableState.value = planLost()
        }

        /**
         * P1-10: "Plan abbrechen" aus der Train-Konsole. Nimmt Queue und
         * Ducking zurueck und beendet die Plansteuerung sichtbar
         * ([DropSyncState.Cancelled]); die Pause selbst laeuft weiter.
         */
        override fun cancelPlan() {
            scope.launch {
                if (activeSessionId == null) return@launch
                endSession(timerEngine.state.value, forceCancel = true)
            }
        }

        /** C13: quittiert die einmalige "Plan verloren"-Meldung. */
        override fun acknowledgePlanLost() {
            scope.launch {
                if (mutableState.value == DropSyncState.Failed(DropSyncFailureReason.PLAN_LOST)) {
                    mutableState.value = DropSyncState.Off
                }
            }
        }

        /**
         * C2 (5.10): Undo nach einem Skip — derselbe Plan wird neu armiiert.
         * No-op ohne offenen Skip-Override, ohne laufende REST-Sitzung oder
         * bei zu kurzer Restzeit.
         */
        override fun replanAfterOverride(): Boolean {
            val state = timerEngine.state.value
            val session = state.session ?: return false
            if (session.mode != TimerMode.REST || session.id != activeSessionId) return false
            if (mutableState.value !is DropSyncState.Overridden) return false
            if (state.status != TimerStatus.RUNNING) return false
            if (state.remainingMs < DropLandingPlanner.MIN_REST_MS) return false
            scope.launch { planLanding(session) }
            return true
        }

        /**
         * Drop-Auto (MP-1): Das Trainingslog fordert eine Drop-Landung fuer
         * die kommende Pause an. Laeuft die Pause bereits, wird sofort fuer
         * diese Sitzung geplant; sonst greift die Anforderung beim naechsten
         * Pausenbeginn.
         */
        private suspend fun onDropAutoRequested() {
            val state = timerEngine.state.value
            val session = state.session
            val restRunning =
                session != null &&
                    session.mode == TimerMode.REST &&
                    state.status == TimerStatus.RUNNING
            if (!restRunning) {
                pendingDropAuto = true
                return
            }
            // C15 (PR-4): Unter einer Minute ist Drop-Auto nicht moeglich;
            // sichtbar als Grund statt als stille Nicht-Landung.
            if (state.remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS) {
                mutableState.value = DropSyncState.Failed(DropSyncFailureReason.REST_TOO_SHORT)
                return
            }
            if (activeSessionId != session.id) {
                beginSession(session, RestMusicBehavior.DROP_LANDING)
            } else if (!controlling) {
                if (!ensureRestQueue()) return
                planLanding(session)
            } else {
                planLanding(session)
            }
        }

        private suspend fun onState(
            state: TimerState,
            behavior: RestMusicBehavior,
        ) {
            // Drop-Auto (MP-1): eine angeforderte Landung wirkt fuer genau die
            // naechste Pause, auch wenn das globale Verhalten NORMAL ist.
            val session = state.session
            val restSession = session?.takeIf { it.mode == TimerMode.REST }
            val dropSyncSession = session?.takeIf { it.mode == TimerMode.DROPSYNC }
            // C13: Ein geladener Marker zaehlt nur fuer seine Sitzung.
            val recoveredAuto =
                restSession?.let { rest ->
                    recoveredMarker?.takeIf {
                        it.kind == DropSyncPlanKind.AUTO_LANDING && it.sessionId == rest.id
                    }
                }
            if (dropSyncSession == null && manualMarkerSessionId != null) {
                // Manueller DropRest beendet: Marker verfaellt mit der Sitzung.
                manualMarkerSessionId = null
                planStore.clear()
            }
            val effectiveBehavior =
                if (pendingDropAuto || recoveredAuto != null) {
                    RestMusicBehavior.DROP_LANDING
                } else {
                    behavior
                }
            val isActive = restSession != null && restSession.id == activeSessionId
            val running = isActive && state.status == TimerStatus.RUNNING
            val paused = isActive && state.status == TimerStatus.PAUSED
            val ended =
                restSession == null ||
                    state.status == TimerStatus.COMPLETED ||
                    state.status == TimerStatus.CANCELLED ||
                    state.status == TimerStatus.FAILED
            when {
                // Manueller DropRest (P1-8): eigener Pfad, app-weit ueberwacht.
                dropSyncSession != null -> {
                    onDropSyncState(state, dropSyncSession)
                }

                effectiveBehavior == RestMusicBehavior.NORMAL && !pendingDropAuto -> {
                    // Kein Eingriff gewuenscht: einen LAUFENDEN Plan sichtbar
                    // beenden (z. B. Verhalten mitten in der Pause umgestellt).
                    if (activeSessionId != null && (controlling || activePlan != null)) {
                        endSession(state, forceCancel = true)
                    }
                }

                restSession != null && restSession.id != activeSessionId -> {
                    if (recoveredAuto != null) recoveredMarker = null
                    beginSession(
                        session = restSession,
                        behavior = effectiveBehavior,
                        recovered = recoveredAuto != null,
                        paused = state.status == TimerStatus.PAUSED,
                    )
                }

                running -> {
                    onRunning(state)
                }

                paused -> {
                    onPaused()
                }

                ended && activeSessionId != null -> {
                    endSession(state, forceCancel = false)
                }
            }
        }

        /**
         * Monitor des manuellen DropRest (11.4, P1-8): projiziert die
         * Restzeit aus der Playerposition, liefert Cues und beendet nur den
         * Timer bei Songwechsel, Seek, Pause oder Playerfehler. Der Monitor
         * selbst lebt app-weit in [DropRestSessionMonitor].
         */
        private fun onDropSyncState(
            state: TimerState,
            session: TimerSession,
        ) {
            // C13: Der manuelle DropRest wird nicht wiederhergestellt; der
            // Marker macht den Verlust beim naechsten Start sichtbar (5.9).
            if (manualMarkerSessionId != session.id) {
                manualMarkerSessionId = session.id
                scope.launch {
                    planStore.save(DropSyncPlanMarker(DropSyncPlanKind.MANUAL_DROP_REST, session.id))
                }
            }
            dropRestMonitor.onTimerState(state, session) { mutableState.value = it }
        }

        private suspend fun beginSession(
            session: TimerSession,
            behavior: RestMusicBehavior,
            recovered: Boolean = false,
            paused: Boolean = false,
        ) {
            val dropAutoPending = pendingDropAuto
            pendingDropAuto = false
            activeSessionId = session.id
            controlling = false
            landed = false
            activePlan = null
            activeWorkSong = null
            restQueueSongIds = emptyList()
            lastSample = null
            sawPlaying = false
            plannedRemainingMs = -1L
            needsReplanOnResume = false
            chainSource = null
            if (behavior == RestMusicBehavior.NORMAL) {
                mutableState.value = DropSyncState.Off
                return
            }
            if (recovered) {
                recoveredSessionId = session.id
                adoptPersistedQueue()
            } else {
                // C16: Kandidatenquelle der Kette ist die Wiedergabe-Liste
                // VOR dem Rest-Queue-Wechsel (5.17); die Pausen-Playlist
                // bleibt das Bett, bis der erste Kettenwechsel greift.
                chainSource = captureChainSource()
                if (!ensureRestQueue()) {
                    return
                }
            }
            if (behavior == RestMusicBehavior.REST_PLAYLIST) {
                // Queue uebernommen, keine Landung geplant.
                mutableState.value = DropSyncState.Off
                return
            }
            // C15 (PR-4): Auch eine vorgemerkte Drop-Auto-Anforderung gilt
            // nicht fuer Pausen unter einer Minute (die Queue laeuft weiter).
            if (dropAutoPending &&
                !recovered &&
                timerEngine.state.value.remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS
            ) {
                mutableState.value = DropSyncState.Failed(DropSyncFailureReason.REST_TOO_SHORT)
                return
            }
            if (paused) {
                // C13: Rekonstruierte Pause — die Landung wird beim Resume
                // geplant (die Restzeit steht still).
                needsReplanOnResume = true
                return
            }
            planLanding(session)
        }

        /**
         * Setzt die Rest-Queue und aktiviert das Ducking. Liefert false,
         * wenn keine "Rest/Pause"-Playlist existiert (dokumentierter
         * Fallback, Entscheidung 7: nichts tun) oder der Player sie nicht
         * annimmt (C1: sichtbarer PLAYBACK_ERROR statt stillem Fehlschlag).
         */
        private suspend fun ensureRestQueue(): Boolean {
            val restSongs = planner.playlistSongs(PlaylistLabel.REST)
            if (restSongs.isEmpty()) {
                // activeSessionId bleibt gesetzt, damit wir nicht wiederholt
                // erneut versuchen; die Queue uebernehmen wir aber nicht.
                mutableState.value = DropSyncState.Failed(DropSyncFailureReason.NO_REST_PLAYLIST)
                return false
            }
            // Satzwechsel: Generation erhoehen, damit laufende Landungen
            // veralteter Sitzungen nie mehr feuern (Design Phase 6).
            generation = generation.next()
            val result = playbackRepository.setQueue(restSongs, startIndex = 0, playWhenReady = true)
            if (result is AppResult.Failure) {
                mutableState.value = DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR)
                return false
            }
            restQueueSongIds = restSongs.map { it.mediaStoreId }
            controlling = true
            // Phase 7: Pausenmusik ducken (Rampe, dB aus DSP-Konfiguration).
            restDucking.setActive(true)
            return true
        }

        /**
         * C13: Uebernimmt die vom Player wiederhergestellte Queue, ohne sie
         * per `setQueue` zurueckzusetzen — der Kill-Fallback darf den
         * laufenden Titel nicht reissen. Fehlt eine persistierte Queue,
         * bleibt die Wiedergabe unangetastet; nur die Override-Erkennung
         * wird gefuellt.
         */
        private suspend fun adoptPersistedQueue() {
            val ids = playbackRepository.lastPersistedState()?.queueSongIds.orEmpty()
            restQueueSongIds =
                ids.ifEmpty { planner.playlistSongs(PlaylistLabel.REST).map { it.mediaStoreId } }
            controlling = true
            restDucking.setActive(true)
        }

        private suspend fun onRunning(state: TimerState) {
            // Nach einer Pause ist die Armierung entwertet: Resume plant neu,
            // ohne die Queue anzufassen (MP-15, "Resume behaelt den Titel").
            // C13: Auch eine rekonstruierte Pause plant erst beim Resume.
            if (needsReplanOnResume) {
                needsReplanOnResume = false
                val session = state.session ?: return
                planLanding(session)
                return
            }
            val current = mutableState.value
            if (current !is DropSyncState.Planned && current !is DropSyncState.Armed) return
            // MP-15: Restzeit-Aenderungen (`+15 s`) verschieben die Landung.
            // Erwartet wird der lineare Countdown seit der Planung; weicht
            // die Engine-Restzeit deutlich ab, wird neu geplant (Queue und
            // laufender Titel bleiben stehen).
            val expectedRemaining = plannedRemainingMs - (clock.elapsedRealtimeMs() - plannedAtElapsedMs)
            if (abs(state.remainingMs - expectedRemaining) > REPLAN_TOLERANCE_MS) {
                val session = state.session ?: return
                planLanding(session)
            }
        }

        private suspend fun onPaused() {
            if (activePlan == null) return
            // Nutzer pausiert: Landung entwerten, aber Queue und Titel
            // behalten — Resume plant neu (MP-15).
            cancelLanding()
            needsReplanOnResume = true
            val planned =
                (mutableState.value as? DropSyncState.Armed)?.plan
                    ?: (mutableState.value as? DropSyncState.Planned)
            if (planned != null) {
                mutableState.value = planned
            }
        }

        /**
         * C16: friert die laufende Wiedergabe-Liste als Kettenquelle ein
         * (5.17). Ohne laufende Wiedergabe gibt es keine Kette.
         */
        private suspend fun captureChainSource(): ChainSource? {
            val state = playbackRepository.snapshotNow().getOrNull() ?: return null
            if (state.queue.isEmpty()) return null
            return ChainSource(
                queue = state.queue,
                currentIndex = state.currentIndex,
                positionMs = state.positionMs,
                currentSongId = state.currentSongId,
            )
        }

        /**
         * Plant (oder plant neu) die Landung fuer [session]. Die Rest-Queue
         * bleibt unangetastet; nur Armierung und Zustand werden erneuert.
         *
         * C16: Zuerst wird die Ueberleitungskette aus der aktuellen
         * Wiedergabe-Liste geplant (5.17); ist keine Kette moeglich, greift
         * der bestehende Einzel-Landungs-Pfad (Work-Playlist).
         */
        private suspend fun planLanding(session: TimerSession) {
            cancelLanding()
            val remaining = timerEngine.state.value.remainingMs
            val chainOutcome = planChain(remaining)
            if (chainOutcome is DropSyncPlanner.ChainOutcome.Planned && chainOutcome.chain.landing != null) {
                planChainLanding(session, chainOutcome, remaining)
                return
            }
            planSingleLanding(session, remaining)
        }

        /**
         * C16: Kettenplanung aus der eingefrorenen Wiedergabe-Liste; laeuft
         * der eingefrorene Titel noch, zaehlt seine Live-Position, sonst
         * beginnt die Kette mit einem echten Wechsel (kein Phantom-Titel).
         */
        private suspend fun planChain(remaining: Long): DropSyncPlanner.ChainOutcome {
            val live = playbackRepository.snapshotNow().getOrNull()
            val source =
                chainSource ?: return DropSyncPlanner.ChainOutcome.NotPossible(DropSyncFailureReason.NO_WORK_DROP)
            val currentStillPlaying =
                live != null &&
                    source.currentSongId != null &&
                    live.currentSongId == source.currentSongId
            return planner.planChain(
                remainingMs = remaining,
                queue = source.queue,
                currentIndex = if (currentStillPlaying) source.currentIndex else -1,
                currentPositionMs = if (currentStillPlaying) live.positionMs else 0L,
            )
        }

        /** C16: Kette ausfuehren — Fueller terminiert, Landung armiert. */
        private suspend fun planChainLanding(
            session: TimerSession,
            outcome: DropSyncPlanner.ChainOutcome.Planned,
            remaining: Long,
        ) {
            val chain = outcome.chain
            val landing = chain.landing ?: return
            val workSong = outcome.songsById[landing.songId] ?: return
            val plan =
                DropLandingPlan(
                    songId = landing.songId,
                    markerId = landing.markerId ?: 0L,
                    startAtPositionMs = landing.startAtPositionMs,
                    startAfterDelayMs = landing.startsAfterMs,
                    kind =
                        if (landing.startAtPositionMs > 0L) {
                            DropLandingPlan.Kind.DIRECT_TO_DROP
                        } else {
                            DropLandingPlan.Kind.INTRO
                        },
                    crossfadeMs = outcome.crossfadeMs,
                )
            chainSongIds = chain.segments.map { it.songId }.toSet()
            chainJobs = scheduleChainFillers(session, outcome)
            armLanding(
                session = session,
                plan = plan,
                workSong = workSong,
                remainingMs = remaining,
                markerLabel = outcome.labelsByMarkerId[landing.markerId].orEmpty(),
                confidence = outcome.confidence,
                chainTitles =
                    chain.segments.mapNotNull { segment ->
                        outcome.songsById[segment.songId]?.let { it.title ?: it.displayName }
                    },
            )
        }

        /** Bestehender Pfad: genau EINE Landung aus der Work-Playlist. */
        private suspend fun planSingleLanding(
            session: TimerSession,
            remaining: Long,
        ) {
            val outcome = planner.plan(remaining)
            if (outcome is DropSyncPlanner.Outcome.NotPossible) {
                // C13: Eine rekonstruierte Sitzung, die nicht neu geplant
                // werden kann, ist sichtbar "verloren" (5.8).
                mutableState.value =
                    if (session.id == recoveredSessionId) {
                        planLost()
                    } else {
                        DropSyncState.Failed(outcome.reason)
                    }
                return
            }
            val plannedOutcome = outcome as DropSyncPlanner.Outcome.Planned
            armLanding(
                session = session,
                plan = plannedOutcome.plan,
                workSong = plannedOutcome.song,
                remainingMs = remaining,
                markerLabel = plannedOutcome.markerLabel,
                confidence = plannedOutcome.confidence,
            )
        }

        /**
         * C16: terminiert die Zwischenwechsel deadline-basiert (grob, ohne
         * Audio-Uhr — Praezision braucht nur die Landung). Segment 0 ist der
         * laufende Titel und wird nicht neu gestartet.
         */
        private fun scheduleChainFillers(
            session: TimerSession,
            outcome: DropSyncPlanner.ChainOutcome.Planned,
        ): List<Job> {
            val sessionId = session.id
            val sessionGeneration = generation
            return outcome.chain.segments
                .filter { it.kind == ChainSegmentKind.FILLER }
                .mapNotNull { segment ->
                    if (segment.songId == outcome.currentSongId && segment.startsAfterMs <= 0L) {
                        return@mapNotNull null
                    }
                    val song = outcome.songsById[segment.songId] ?: return@mapNotNull null
                    scope.launch {
                        delay(segment.startsAfterMs.coerceAtLeast(0L))
                        if (activeSessionId != sessionId || generation != sessionGeneration) return@launch
                        val current = mutableState.value
                        if (current !is DropSyncState.Planned && current !is DropSyncState.Armed) return@launch
                        playbackRepository.playSongAt(song, segment.startAtPositionMs)
                    }
                }
        }

        /**
         * Armiert die Landung und setzt den sichtbaren Zustand; ohne
         * MediaController uebernimmt die Deadline-Schleife.
         */
        private suspend fun armLanding(
            session: TimerSession,
            plan: DropLandingPlan,
            workSong: Song,
            remainingMs: Long,
            markerLabel: String,
            confidence: TimingConfidence,
            chainTitles: List<String> = emptyList(),
        ) {
            targetElapsedRealtimeMs = clock.elapsedRealtimeMs() + plan.startAfterDelayMs
            val planned =
                DropSyncState.Planned(
                    songTitle = workSong.title ?: workSong.displayName,
                    markerLabel = markerLabel,
                    targetElapsedRealtimeMs = targetElapsedRealtimeMs,
                    remainingMs = remainingMs,
                    confidence = confidence,
                    mode = DropSyncMode.LANDING_AT_REST_END,
                    chain = chainTitles,
                )
            plannedRemainingMs = remainingMs
            plannedAtElapsedMs = clock.elapsedRealtimeMs()
            activePlan = plan
            activeWorkSong = workSong
            mutableState.value = planned
            // C13: Der Plan ueberlebt den Prozess nicht — der Marker macht
            // ihn beim naechsten Start erkennbar (5.8/5.9).
            planStore.save(DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, session.id))
            val armed =
                playbackRepository.armLanding(
                    song = workSong,
                    startPositionMs = plan.startAtPositionMs,
                    delayMs = plan.startAfterDelayMs,
                    fadeMs = plan.crossfadeMs,
                )
            if (armed is AppResult.Success) {
                armedToken += 1
                mutableState.value = DropSyncState.Armed(planned, armedToken, audioPrepared = true)
            } else {
                // Kein MediaController/Service: Deadline-Schleife als
                // Fallback (Verhalten wie vor MP-3, sichtbar als Best Effort).
                armedToken += 1
                mutableState.value = DropSyncState.Armed(planned, armedToken, audioPrepared = false)
                startFallbackLanding(session, plan, workSong)
            }
        }

        /**
         * C13: beendet eine verlorene Sitzung sichtbar und raeumt den
         * Marker auf; die Meldung bleibt bis zur Quittierung stehen.
         */
        private suspend fun planLost(): DropSyncState {
            recoveredMarker = null
            recoveredSessionId = null
            planStore.clear()
            return DropSyncState.Failed(DropSyncFailureReason.PLAN_LOST)
        }

        /**
         * Fallback ohne Armierung: wartet deadline-basiert und landet mit
         * Driftkorrektur der Startposition (MP-3 Punkt 2).
         */
        private fun startFallbackLanding(
            session: TimerSession,
            plan: DropLandingPlan,
            workSong: Song,
        ) {
            val sessionId = session.id
            val sessionGeneration = generation
            landingJob =
                scope.launch {
                    delay(plan.startAfterDelayMs.coerceAtLeast(0))
                    if (activeSessionId != sessionId || generation != sessionGeneration) return@launch
                    val snapshot = playbackRepository.snapshotNow().getOrNull()
                    if (snapshot != null && !snapshot.isPlaying) {
                        mutableState.value = DropSyncState.Overridden(OverrideReason.PAUSED)
                        return@launch
                    }
                    val lateMs = (clock.elapsedRealtimeMs() - targetElapsedRealtimeMs).coerceAtLeast(0)
                    val startAt = correctedStartPosition(plan, lateMs)
                    val result = playbackRepository.playSongAt(workSong, startAt)
                    if (result is AppResult.Failure) {
                        mutableState.value = DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR)
                        return@launch
                    }
                    landed = true
                    restDucking.setActive(false)
                    mutableState.value =
                        DropSyncState.BestEffort(
                            if (lateMs > BEST_EFFORT_LATE_MS) {
                                BestEffortReason.LATE_ARMED
                            } else {
                                BestEffortReason.UNKNOWN_LATENCY
                            },
                        )
                }
        }

        /** Startposition um die bereits verstrichene Zeit nachziehen (MP-3). */
        private fun correctedStartPosition(
            plan: DropLandingPlan,
            lateMs: Long,
        ): Long =
            when (plan.kind) {
                DropLandingPlan.Kind.INTRO -> plan.startAtPositionMs + lateMs
                DropLandingPlan.Kind.DIRECT_TO_DROP -> plan.startAtPositionMs
            }

        private suspend fun onLandingEvent(event: DropLandingEvent) {
            when (event) {
                is DropLandingEvent.Landed -> {
                    if (activeSessionId == null) return
                    cancelFallback()
                    landed = true
                    restDucking.setActive(false)
                    mutableState.value =
                        DropSyncState.Landed(
                            atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                            deltaMs = event.deltaMs,
                        )
                }

                is DropLandingEvent.Missed -> {
                    when (event.reason) {
                        DropLandingEvent.Reason.OVERRIDDEN -> {
                            cancelFallback()
                            mutableState.value = DropSyncState.Overridden(OverrideReason.PAUSED)
                        }

                        DropLandingEvent.Reason.WATCHDOG -> {
                            // Die Audio-Uhr hat nicht gefeuert: jetzt mit
                            // Deadline-Wissen landen (Best Effort, sichtbar).
                            val plan = activePlan ?: return
                            val workSong = activeWorkSong ?: return
                            val lateMs =
                                (clock.elapsedRealtimeMs() - targetElapsedRealtimeMs).coerceAtLeast(0)
                            val startAt = correctedStartPosition(plan, lateMs)
                            val result = playbackRepository.playSongAt(workSong, startAt)
                            if (result is AppResult.Failure) {
                                mutableState.value = DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR)
                                return
                            }
                            landed = true
                            restDucking.setActive(false)
                            mutableState.value = DropSyncState.BestEffort(BestEffortReason.WATCHDOG)
                        }

                        DropLandingEvent.Reason.PLAYER_ERROR -> {
                            cancelFallback()
                            mutableState.value = DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR)
                        }
                    }
                }
            }
        }

        /**
         * Nutzer-Vorrang (MP-5): Pause, fremder Titel, Seek oder fremde
         * Queue-Aenderung waehrend des Plans beenden ihn sichtbar. Die
         * eigene Landung ist ausgenommen (Ziel-Titel bzw. Landed-Event).
         */
        private suspend fun onPlaybackState(state: PlaybackState) {
            // C2: Auch ein noch nicht armierter Plan (Planned) wird
            // beobachtet — ein Skip in dieser Phase war vorher unsichtbar.
            val current = mutableState.value
            if (current !is DropSyncState.Armed && current !is DropSyncState.Planned) {
                lastSample = null
                return
            }
            val plannedSongId = activeWorkSong?.mediaStoreId
            if (state.currentSongId != null && state.currentSongId == plannedSongId) {
                // Eigene Landung laeuft: nicht als Override werten.
                return
            }
            if (!state.isPlaying && sawPlaying) {
                override(OverrideReason.PAUSED)
                return
            }
            if (state.isPlaying) sawPlaying = true
            val songId = state.currentSongId
            // C16: Ein geplanter Kettenwechsel ist kein Override — Probe
            // nachziehen und die Rest-Queue-Pruefungen ueberspringen (der
            // Wechsel hat die Queue bereits uebernommen).
            if (songId != null && songId in chainSongIds) {
                lastSample = PositionSample(songId, state.positionMs, clock.elapsedRealtimeMs())
                return
            }
            val previous = lastSample
            // C2 (5.10): Ein Titelwechsel INNERHALB der Rest-Queue ist ein
            // Skip (Queue-Sheet, Bluetooth, Kopfhoerer); vorher blieb der
            // Plan dabei scharf und landete unbemerkt.
            if (songId != null && previous?.songId != null && songId != previous.songId) {
                override(OverrideReason.SKIPPED)
                return
            }
            if (songId != null && songId !in restQueueSongIds) {
                override(OverrideReason.SONG_CHANGED)
                return
            }
            if (restQueueSongIds.isNotEmpty() && state.queueSongIds != restQueueSongIds) {
                override(OverrideReason.QUEUE_CHANGED)
                return
            }
            val now = clock.elapsedRealtimeMs()
            if (isUnexpectedSeek(previous, songId, state, now)) {
                override(OverrideReason.SEEK)
                return
            }
            lastSample = PositionSample(songId, state.positionMs, now)
        }

        /**
         * MP-5: Ein Sprung ausserhalb der Toleranz ist ein Seek. Top-level im
         * Coordinator statt im [onPlaybackState]-Body, damit der Dispatcher
         * unter der Detekt-Schwelle bleibt (CyclomaticComplexMethod).
         */
        private fun isUnexpectedSeek(
            previous: PositionSample?,
            songId: Long?,
            state: PlaybackState,
            now: Long,
        ): Boolean {
            if (previous == null || !state.isPlaying || previous.songId != songId) return false
            val expected =
                previous.positionMs + ((now - previous.atElapsedMs) * state.playbackSpeed).toLong()
            return abs(state.positionMs - expected) > SEEK_TOLERANCE_MS
        }

        private suspend fun override(reason: OverrideReason) {
            cancelLanding()
            mutableState.value = DropSyncState.Overridden(reason)
        }

        private suspend fun cancelLanding() {
            landingJob?.cancel()
            landingJob = null
            // C16: geplante Kettenwechsel verfallen mit dem Plan.
            chainJobs.forEach { it.cancel() }
            chainJobs = emptyList()
            chainSongIds = emptySet()
            activePlan = null
            activeWorkSong = null
            runCatching { playbackRepository.cancelLanding() }
        }

        private fun cancelFallback() {
            landingJob?.cancel()
            landingJob = null
        }

        private suspend fun endSession(
            state: TimerState,
            forceCancel: Boolean,
        ) {
            val completed = state.status == TimerStatus.COMPLETED
            val wasControlling = controlling
            val hadLanded = landed
            cancelLanding()
            val previous = mutableState.value
            activeSessionId = null
            controlling = false
            landed = false
            restQueueSongIds = emptyList()
            lastSample = null
            sawPlaying = false
            needsReplanOnResume = false
            recoveredSessionId = null
            manualMarkerSessionId = null
            chainSource = null
            // Eine Anforderung, die keine Pause mehr erreicht hat,
            // verfaellt mit dieser Pause.
            pendingDropAuto = false
            // C13: Sitzung vorbei — der Kill-Marker verfaellt mit ihr.
            planStore.clear()
            // Pausenende/Abbruch: Rest-Ducking zuruecknehmen (Phase 7).
            restDucking.setActive(false)
            // Natuerliches Pausenende ohne bereits erfolgte Landung:
            // Work-Titel starten. Bei Abbruch/Pause nie erzwingen.
            if (completed && wasControlling && !hadLanded) startWorkTitle()
            mutableState.value =
                when {
                    forceCancel -> DropSyncState.Cancelled

                    previous is DropSyncState.Landed ||
                        previous is DropSyncState.BestEffort ||
                        previous is DropSyncState.Overridden -> previous

                    previous is DropSyncState.Off || previous is DropSyncState.Cancelled -> previous

                    else -> DropSyncState.Cancelled
                }
        }

        private suspend fun startWorkTitle() {
            val workSongs = planner.workSongs()
            if (workSongs.isNotEmpty()) {
                playbackRepository.setQueue(workSongs, startIndex = 0, playWhenReady = true)
            }
        }

        /** Position einer Wiedergabeprobe mit Songbezug (Seek-Erkennung). */
        private data class PositionSample(
            val songId: Long?,
            val positionMs: Long,
            val atElapsedMs: Long,
        )

        private companion object {
            /** Toleranz, ab der eine Restzeit-Abweichung neu plant (MP-15). */
            const val REPLAN_TOLERANCE_MS = 750L

            /** Ab hier gilt eine verspaetete Landung als LATE_ARMED. */
            const val BEST_EFFORT_LATE_MS = 120L

            /** Toleranz der Seek-Erkennung gegen die erwartete Position. */
            const val SEEK_TOLERANCE_MS = 1_500L
        }
    }
