package com.dropsync.feature.player

import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropLandingResult
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.WorkSongDrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestriert die Musik in Trainingspausen (Musik-Workout-Plan Phase 4).
 *
 * Der Coordinator beobachtet die eine [TimerEngine] (kein Feature-Import,
 * Kopplung nur ueber die Domainschnittstelle) zusammen mit der
 * Nutzereinstellung [RestMusicSettingsRepository]:
 * - [RestMusicBehavior.NORMAL]: nie eingreifen — Queue/Shuffle laeuft weiter.
 * - [RestMusicBehavior.REST_PLAYLIST]: bei Pausenbeginn auf die
 *   "Rest/Pause"-Playlist umschalten, am Pausenende einen "Work"-Titel starten.
 * - [RestMusicBehavior.DROP_LANDING]: zusaetzlich einen Work-Titel so
 *   vorziehen, dass sein Drop das Pausenende trifft ([DropLandingPlanner]).
 *
 * Die Zeitrechnung nutzt die monotone Startzeit der [TimerSession]
 * ([Clock]); realistisch sind ca. +/-100-200 ms (ADR-0012). Ein manueller
 * Eingriff (pausierte Wiedergabe) bricht die geplante Landung ab.
 */
@Singleton
class RestMusicCoordinator
    @Inject
    constructor(
        private val timerEngine: TimerEngine,
        private val restMusicSettings: RestMusicSettingsRepository,
        private val playbackRepository: PlaybackRepository,
        private val browseRepository: LibraryBrowseRepository,
        private val markerRepository: MarkerRepository,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        private var started = false

        /** ID der Rest-Sitzung, die wir gerade steuern; null = untaetig. */
        private var activeSessionId: String? = null

        /** True, sobald wir die Queue tatsaechlich uebernommen haben. */
        private var controlling = false

        /** True, sobald der Work-Titel per Drop-Landung gestartet wurde. */
        private var landed = false

        private var landingJob: Job? = null

        /** Startet die Beobachtung genau einmal (App-Start). */
        fun start() {
            if (started) return
            started = true
            scope.launch {
                combine(timerEngine.state, restMusicSettings.behavior) { state, behavior ->
                    state to behavior
                }.collect { (state, behavior) -> onState(state, behavior) }
            }
        }

        private suspend fun onState(
            state: TimerState,
            behavior: RestMusicBehavior,
        ) {
            if (behavior == RestMusicBehavior.NORMAL) {
                abort()
                return
            }
            val session = state.session
            val restRunningSession =
                session?.takeIf {
                    it.mode == TimerMode.REST && state.status == TimerStatus.RUNNING
                }
            when {
                restRunningSession != null && restRunningSession.id != activeSessionId -> {
                    activeSessionId = restRunningSession.id
                    controlling = false
                    landed = false
                    onRestBegin(restRunningSession, behavior)
                }

                restRunningSession == null && activeSessionId != null -> {
                    val completed = state.status == TimerStatus.COMPLETED
                    val wasControlling = controlling
                    val hadLanded = landed
                    landingJob?.cancel()
                    landingJob = null
                    activeSessionId = null
                    controlling = false
                    landed = false
                    // Natuerliches Pausenende ohne bereits erfolgte Landung:
                    // Work-Titel starten. Bei Abbruch/Pause nie erzwingen.
                    if (completed && wasControlling && !hadLanded) startWorkTitle()
                }
            }
        }

        private suspend fun onRestBegin(
            session: TimerSession,
            behavior: RestMusicBehavior,
        ) {
            val restSongs = songsForLabel(PlaylistLabel.REST)
            if (restSongs.isEmpty()) {
                // Fallback: keine "Rest/Pause"-Playlist -> NORMAL-Verhalten.
                // activeSessionId bleibt gesetzt, damit wir nicht wiederholt
                // erneut versuchen; wir uebernehmen die Queue aber nicht.
                return
            }
            playbackRepository.setQueue(restSongs, startIndex = 0, playWhenReady = true)
            controlling = true
            if (behavior != RestMusicBehavior.DROP_LANDING) return

            val startedAt = session.startedElapsedRealtimeMs ?: return
            val remaining =
                (startedAt + session.durationMs - clock.elapsedRealtimeMs())
                    .coerceAtLeast(0)
            val (candidates, songsById) = workCandidates()
            val plan =
                (DropLandingPlanner.plan(remaining, candidates) as? DropLandingResult.Scheduled)
                    ?.plan ?: return
            // Ohne brauchbaren Work-Drop faellt DROP_LANDING auf
            // REST_PLAYLIST zurueck: Work-Titel dann erst am Pausenende.
            val workSong = songsById[plan.songId] ?: return
            val sessionId = session.id
            landingJob =
                scope.launch {
                    delay(plan.startAfterDelayMs)
                    // Automatik nur, wenn die Sitzung noch aktiv ist und der
                    // Nutzer nicht manuell pausiert hat (Nutzer hat Vorrang).
                    if (activeSessionId != sessionId) return@launch
                    val snapshot = playbackRepository.snapshotNow().getOrNull()
                    if (snapshot != null && !snapshot.isPlaying) {
                        // Manueller Eingriff (Pause/Medientaste): Automatik
                        // ganz abgeben, damit auch am Pausenende nichts
                        // erzwungen wird (Hardware-/Touch-Control, F4).
                        controlling = false
                        return@launch
                    }
                    playbackRepository.crossfadeTo(workSong, plan.startAtPositionMs)
                    landed = true
                }
        }

        private suspend fun startWorkTitle() {
            val workSongs = songsForLabel(PlaylistLabel.WORK)
            if (workSongs.isNotEmpty()) {
                playbackRepository.setQueue(workSongs, startIndex = 0, playWhenReady = true)
            }
        }

        /**
         * Work-Titel mit aktivem Drop als Landungs-Kandidaten. Der Drop ist
         * der frueheste aktive Marker (aufsteigend nach Position); die
         * Songdaten bleiben fuer den spaeteren Crossfade erhalten.
         */
        private suspend fun workCandidates(): Pair<List<WorkSongDrop>, Map<Long, Song>> {
            val songs = songsForLabel(PlaylistLabel.WORK)
            val songsById = songs.associateBy { it.mediaStoreId }
            val candidates =
                songs.mapNotNull { song ->
                    val marker =
                        markerRepository
                            .getEnabledMarkersForSong(song.mediaStoreId)
                            .getOrNull()
                            ?.firstOrNull() ?: return@mapNotNull null
                    WorkSongDrop(
                        songId = song.mediaStoreId,
                        dropPositionMs = marker.positionMs,
                        durationMs = song.durationMs,
                        markerId = marker.id,
                    )
                }
            return candidates to songsById
        }

        private suspend fun songsForLabel(label: PlaylistLabel): List<Song> {
            val playlists = browseRepository.playlistsByLabel(label).first()
            return playlists
                .flatMap { browseRepository.songsOfPlaylist(it.id).first() }
                .distinctBy { it.mediaStoreId }
        }

        private fun abort() {
            landingJob?.cancel()
            landingJob = null
            activeSessionId = null
            controlling = false
            landed = false
        }
    }
