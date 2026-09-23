package com.dropsync.feature.player

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeDropRestRequestBus
import com.dropsync.core.testing.FakeDropSyncPlanStore
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.DropTargetRepository
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.ImportedTrack
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryScanResult
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.DropLandingEvent
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import com.dropsync.domain.playback.RestDuckingGate
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.playback.RouteProfileRepository
import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncPlanKind
import com.dropsync.domain.timer.DropSyncPlanMarker
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.OverrideReason
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation P1-6/P1-7/P1-9/P1-14: Der DropSyncCoordinator plant die
 * Landung, armiert sie auf der Audio-Uhr (PlayerMessage-Port), wertet
 * Nutzer-Eingriffe als Override, rechnet Restzeit-Aenderungen ein und
 * uebernimmt den DropRest-Monitor app-weit. Timer ist die echte
 * [TimerEngine] (deterministisch ueber [FakeClock]); Repositories sind
 * Fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DropSyncCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock()
    private val engine = TimerEngine(clock, NoOpCueOutput()) { "session-1" }
    private val settings = CoordinatorRestMusicSettings()
    private val playback = CoordinatorPlaybackRepository()
    private val browse = CoordinatorBrowseRepository()
    private val markers = CoordinatorMarkerRepository()
    private val restDucking = CoordinatorRestDucking()
    private val bus = FakeDropRestRequestBus()
    private val routeProfiles = CoordinatorRouteProfiles()
    private val audioEngine = CoordinatorAudioEngine()
    private val library = CoordinatorLibraryRepository()
    private val dropTargets = CoordinatorDropTargetRepository()
    private val planStore = FakeDropSyncPlanStore()

    private fun coordinator() =
        DropSyncCoordinator(
            timerEngine = engine,
            restMusicSettings = settings,
            playbackRepository = playback,
            restDucking = restDucking,
            dropRestRequests = bus,
            planner = planner(),
            dropRestMonitor = dropRestMonitor(),
            planStore = planStore,
            clock = clock,
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    private fun planner() =
        DropSyncPlanner(
            browseRepository = browse,
            libraryRepository = library,
            markerRepository = markers,
            routeProfiles = routeProfiles,
            audioEngine = audioEngine,
            dropTargetRepository = dropTargets,
        )

    private fun dropRestMonitor() =
        DropRestSessionMonitor(
            timerEngine = engine,
            playbackRepository = playback,
            libraryRepository = library,
            clock = clock,
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    private fun restSetup(dropPositionMs: Long = 20_000L) {
        browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
        browse.songsByPlaylist[1L] = listOf(song(10L))
        browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
        browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
        markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = dropPositionMs, songId = 20L))
        library.songsById[20L] = song(20L, durationMs = 200_000L)
        library.songsById[10L] = song(10L)
    }

    @Test
    fun `Pausenbeginn setzt die Rest-Queue ohne Landung`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertTrue(playback.armCalls.isEmpty())
            assertTrue(playback.playSongAtCalls.isEmpty())
        }

    @Test
    fun `Drop-Landung wird auf der Audio-Uhr armiert`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            // Drop bei 20 s, Restzeit 30 s -> INTRO: Work-Titel von vorn nach
            // 10 s (30 - 20), Startposition 0, kein Crossfade (Default 0).
            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)
            assertTrue(playback.playSongAtCalls.isEmpty())
        }

    @Test
    fun `Pausenbeginn laedt marker und work-titel in je einer abfrage`() =
        runTest(dispatcher) {
            // D5/A7: zwei Work-Titel — die Planung laedt die Marker in EINER
            // Batch-Abfrage und die Titel in EINER Label-Abfrage statt je
            // Titel/Playlist eine eigene Query (N+1).
            restSetup()
            browse.songsByPlaylist[2L] =
                listOf(song(20L, durationMs = 200_000L), song(21L, durationMs = 180_000L))
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 20_000L, songId = 20L))
            markers.markersBySong[21L] = listOf(marker(id = 6L, positionMs = 15_000L, songId = 21L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(20L, 21L)), markers.batchCalls)
            assertEquals(1, browse.labelOnceCalls.count { it == PlaylistLabel.WORK })
            // Die Landung steht trotzdem: 20 s Drop bei 30 s Rest.
            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `bevorzugtes ziel ersetzt die naechste-marker-wahl des titels`() =
        runTest(dispatcher) {
            // P2-21: Zwei Marker (5 s und 20 s); ohne Ziel gewinnt der
            // naechste zur Restzeit (20 s). Mit Ziel landet der Plan auf dem
            // gewaehlten 5-s-Marker (Delay 25 s = 30 s Rest - 5 s Drop).
            restSetup()
            markers.markersBySong[20L] =
                listOf(
                    marker(id = 5L, positionMs = 20_000L, songId = 20L),
                    marker(id = 6L, positionMs = 5_000L, songId = 20L),
                )
            dropTargets.targetsFlow.value = mapOf(20L to 6L)
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(ArmCall(20L, 0L, 25_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `ziel ohne passenden marker faellt auf die automatik zurueck`() =
        runTest(dispatcher) {
            restSetup()
            markers.markersBySong[20L] =
                listOf(
                    marker(id = 5L, positionMs = 20_000L, songId = 20L),
                    marker(id = 6L, positionMs = 5_000L, songId = 20L),
                )
            // Ziel zeigt auf einen geloeschten Marker: kein Aufraeumen noetig,
            // die Automatik greift wieder.
            dropTargets.targetsFlow.value = mapOf(20L to 999L)
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `Landed-Event setzt den Landed-Zustand`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(coordinator.state.value is DropSyncState.Armed)

            playback.emitLanding(DropLandingEvent.Landed(songId = 20L, deltaMs = 12L))
            dispatcher.scheduler.advanceUntilIdle()

            val state = coordinator.state.value
            assertTrue(state is DropSyncState.Landed)
            assertEquals(12L, (state as DropSyncState.Landed).deltaMs)
        }

    @Test
    fun `Watchdog-Miss landet per Fallback und meldet Best Effort`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            playback.emitLanding(
                DropLandingEvent.Missed(DropLandingEvent.Reason.WATCHDOG),
            )
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(20L to 0L), playback.playSongAtCalls)
            assertEquals(
                DropSyncState.BestEffort(BestEffortReason.WATCHDOG),
                coordinator.state.value,
            )
        }

    @Test
    fun `Fehlgeschlagene Armierung faellt auf die Deadline-Schleife zurueck`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            playback.armResult = AppResult.failure(AppError.Unknown("kein Service"))

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(20L to 0L), playback.playSongAtCalls)
            assertEquals(
                DropSyncState.BestEffort(BestEffortReason.UNKNOWN_LATENCY),
                coordinator.state.value,
            )
        }

    @Test
    fun `Nutzer-Pause waehrend des Plans ergibt Overridden`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            // Erst laufende Wiedergabe der Rest-Queue melden, dann Pause.
            playback.stateFlow.value =
                PlaybackState(
                    isPlaying = true,
                    currentSongId = 10L,
                    positionMs = 1_000L,
                    queueSongIds = listOf(10L),
                )
            dispatcher.scheduler.runCurrent()
            playback.stateFlow.value =
                PlaybackState(
                    isPlaying = false,
                    currentSongId = 10L,
                    positionMs = 1_200L,
                    queueSongIds = listOf(10L),
                )
            dispatcher.scheduler.runCurrent()

            assertEquals(
                DropSyncState.Overridden(com.dropsync.domain.timer.OverrideReason.PAUSED),
                coordinator.state.value,
            )
            assertTrue("Armierung muss abgebrochen sein", playback.cancelLandingCalls > 0)
        }

    @Test
    fun `NORMAL greift nicht in die Wiedergabe ein`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(playback.setQueueCalls.isEmpty())
            assertTrue(playback.armCalls.isEmpty())
            assertTrue(playback.playSongAtCalls.isEmpty())
        }

    @Test
    fun `Pausenende startet einen Work-Titel ohne Landung`() =
        runTest(dispatcher) {
            restSetup()
            browse.songsByPlaylist[2L] = listOf(song(20L))
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            // Pause laeuft ab: monotone Uhr vorstellen und Timer neu bewerten.
            clock.advanceBy(30_000L)
            engine.evaluate()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf(listOf(10L) to true, listOf(20L) to true),
                playback.setQueueCalls,
            )
        }

    @Test
    fun `Rest-Ducking wird bei Pausenbeginn aktiviert und am Ende zurueckgenommen`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "Rest-Ducking muss bei Pausenbeginn aktiv sein",
                restDucking.activations.lastOrNull() == true,
            )

            clock.advanceBy(30_000L)
            engine.evaluate()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "Rest-Ducking muss am Pausenende zurueckgenommen sein",
                restDucking.activations.lastOrNull() == false,
            )
        }

    @Test
    fun `Latenz aus dem Route-Profil verschiebt die Armierung`() =
        runTest(dispatcher) {
            // Phase 6: WorkStart = Go - Marker - Latenz. R = 30 s,
            // D = 12 s, L = 200 ms -> Start nach 17.8 s statt 18 s.
            restSetup(dropPositionMs = 12_000L)
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            routeProfiles.latencyMs = 200L

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(ArmCall(20L, 0L, 17_800L, 0L)), playback.armCalls)
        }

    @Test
    fun `DIRECT_TO_DROP armierrt den Sprung zur Drop-Position`() =
        runTest(dispatcher) {
            restSetup(dropPositionMs = 60_000L)
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 20_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(ArmCall(20L, 60_000L, 20_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `Crossfade aus der DSP-Konfiguration wird verdrahtet`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            audioEngine.config.value = DspConfig(crossfadeSeconds = 3)

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            // Planner: 30 - 20 - 3 = 7 s Startverzoegerung, 3 s Fade.
            assertEquals(listOf(ArmCall(20L, 0L, 7_000L, 3_000L)), playback.armCalls)
        }

    @Test
    fun `mehrere Marker - der naechste gewinnt`() =
        runTest(dispatcher) {
            // MP-14: zwei aktive Marker (20 s und 25 s); der Planner waehlt
            // den mit dem kleinsten Abstand zur Restzeit (25 s -> 5 s).
            restSetup()
            markers.markersBySong[20L] =
                listOf(
                    marker(id = 5L, positionMs = 20_000L, songId = 20L),
                    marker(id = 6L, positionMs = 25_000L, songId = 20L),
                )
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(ArmCall(20L, 0L, 5_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `plus 15 Sekunden verschiebt die Landung`() =
        runTest(dispatcher) {
            // MP-15: +15 s verlaengert die Pause; die Landung wird neu
            // armiert und sitzt wieder auf dem Pausenende.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)

            engine.addTime(15_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, playback.armCalls.size)
            assertEquals(ArmCall(20L, 0L, 25_000L, 0L), playback.armCalls.last())
            // D5/A7: die Neuplanung laedt die Work-Kandidaten erneut — in
            // EINER Batch-Abfrage je Planung.
            assertEquals(2, markers.batchCalls.size)
        }

    @Test
    fun `Pause und Resume behaelt den Titel`() =
        runTest(dispatcher) {
            // MP-15: beim Resume wird die Queue NICHT neu gesetzt.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            engine.pause()
            dispatcher.scheduler.advanceUntilIdle()
            engine.resume()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, playback.setQueueCalls.size)
            assertEquals(2, playback.armCalls.size)
        }

    @Test
    fun `Drop-Auto fordert die Landung auch bei NORMAL an`() =
        runTest(dispatcher) {
            // MP-1/Entscheidung 19.09.2026: Der Rest behaelt die Dauer, nur
            // die Musik landet am Pausenende. Die Bus-Anforderung wirkt fuer
            // genau die naechste Pause, auch wenn das Verhalten NORMAL ist.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            dispatcher.scheduler.runCurrent() // Bus-Collector ist aktiv
            bus.request()
            dispatcher.scheduler.runCurrent()
            // C15 (PR-4): Drop-Auto erst ab einer Minute Pause.
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertEquals(listOf(ArmCall(20L, 0L, 70_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `Drop-Auto unter einer Minute wird sichtbar abgelehnt`() =
        runTest(dispatcher) {
            // C15 (PR-4): Unter 60 s ist Drop-Auto nicht moeglich — die
            // Anforderung wird als REST_TOO_SHORT sichtbar (C1-Zeile).
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            bus.request()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.REST_TOO_SHORT),
                coordinator.state.value,
            )
            assertTrue(playback.setQueueCalls.isEmpty())
            assertTrue(playback.armCalls.isEmpty())
        }

    @Test
    fun `vorgemerkte Drop-Auto-Anforderung greift nicht bei kurzer Pause`() =
        runTest(dispatcher) {
            // C15 (PR-4): Auch die vor dem Pausenstart gestellte Anforderung
            // gilt nicht fuer Pausen unter einer Minute; die Rest-Playlist
            // laeuft trotzdem.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()
            bus.request()
            dispatcher.scheduler.runCurrent()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertTrue(playback.armCalls.isEmpty())
            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.REST_TOO_SHORT),
                coordinator.state.value,
            )
        }

    @Test
    fun `Drop-Auto ohne Work-Drop faellt auf die Rest-Playlist zurueck`() =
        runTest(dispatcher) {
            // ADR-0012-Fallback: DROP_LANDING ohne brauchbaren Work-Drop
            // verhaelt sich wie REST_PLAYLIST — Rest-Queue ja, Landung nein.
            restSetup()
            markers.markersBySong.remove(20L)
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            dispatcher.scheduler.runCurrent()
            bus.request()
            dispatcher.scheduler.runCurrent()
            // C15 (PR-4): Drop-Auto erst ab einer Minute Pause.
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertTrue(playback.armCalls.isEmpty())
            assertTrue(playback.playSongAtCalls.isEmpty())
        }

    @Test
    fun `Drop-Auto ohne Rest-Playlist greift nicht ein`() =
        runTest(dispatcher) {
            // Entscheidung 7: ohne Pausen-Playlist laeuft alles normal weiter.
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 20_000L, songId = 20L))
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            dispatcher.scheduler.runCurrent()
            bus.request()
            dispatcher.scheduler.runCurrent()
            // C15 (PR-4): Drop-Auto erst ab einer Minute Pause.
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(playback.setQueueCalls.isEmpty())
            assertTrue(playback.armCalls.isEmpty())
        }

    @Test
    fun `Drop-Auto waehrend laufender Pause plant sofort`() =
        runTest(dispatcher) {
            // Der Schalter kann waehrend der Pause umgelegt werden: die
            // Anforderung kommt dann, wenn die Sitzung bereits RUNNING ist.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            dispatcher.scheduler.runCurrent()
            // C15 (PR-4): Drop-Auto erst ab einer Minute Pause.
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue("ohne Anforderung darf nichts passieren", playback.setQueueCalls.isEmpty())

            bus.request()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertEquals(listOf(ArmCall(20L, 0L, 70_000L, 0L)), playback.armCalls)
        }

    @Test
    fun `DropRest-Sitzung wird app-weit ueberwacht`() =
        runTest(dispatcher) {
            // P1-8: Der Monitor laeuft im Koordinator, nicht im Screen:
            // er projiziert die Restzeit und bricht bei Wiedergabe-Stopp ab.
            restSetup()
            library.songsById[10L] = song(10L)
            playback.snapshotSongId = 10L
            playback.snapshotPositionMs = 1_000L

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()

            val session =
                (
                    engine.startDropSync(requestedDurationMs = 20_000L, markerPositionMs = 21_000L)
                        as AppResult.Success
                ).value
            engine.markRunning(session.id)
            dispatcher.scheduler.advanceTimeBy(600L)

            assertEquals(20_000L, engine.state.value.remainingMs)

            playback.playing = false
            dispatcher.scheduler.advanceTimeBy(600L)

            assertEquals(TimerStatus.CANCELLED, engine.state.value.status)
            assertEquals(DropSyncState.Cancelled, coordinator.state.value)
        }

    @Test
    fun `Pause und Resume des DropRest projiziert weiter`() =
        runTest(dispatcher) {
            // P-1: Der Monitor-Loop prueft nur auf RUNNING und beendet sich in
            // der Pause; ohne Neustart beim Resume bliebe die Restzeit auf dem
            // Stand von vor der Pause stehen.
            restSetup()
            library.songsById[10L] = song(10L)
            playback.snapshotSongId = 10L
            playback.snapshotPositionMs = 1_000L

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()

            val session =
                (
                    engine.startDropSync(requestedDurationMs = 20_000L, markerPositionMs = 21_000L)
                        as AppResult.Success
                ).value
            engine.markRunning(session.id)
            dispatcher.scheduler.advanceTimeBy(600L)
            assertEquals(20_000L, engine.state.value.remainingMs)

            engine.pause()
            dispatcher.scheduler.runCurrent()

            // Der Player steht waehrend der Pause; beim Resume laeuft er
            // weiter und die Projektion kommt aus der neuen Position.
            playback.snapshotPositionMs = 2_000L
            engine.resume()
            dispatcher.scheduler.advanceTimeBy(600L)

            assertEquals(19_000L, engine.state.value.remainingMs)

            // Aufraeumen: der Monitor-Loop laeuft sonst endlos weiter und der
            // Test-Scheduler kaeme nie zur Ruhe.
            engine.cancel(com.dropsync.domain.timer.CancelReason.USER)
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `Plan abbrechen nimmt die Landung zurueck und laesst die Pause laufen`() =
        runTest(dispatcher) {
            // P1-10: "Plan abbrechen" in der Konsole beendet nur die
            // Plansteuerung (Queue/Ducking/Armierung), nicht die Pause.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(coordinator.state.value is DropSyncState.Armed)
            val cancelCallsBefore = playback.cancelLandingCalls

            coordinator.cancelPlan()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(DropSyncState.Cancelled, coordinator.state.value)
            assertTrue(
                "Armierung muss zurueckgenommen sein",
                playback.cancelLandingCalls > cancelCallsBefore,
            )
            assertEquals("Die Pause laeuft weiter", TimerStatus.RUNNING, engine.state.value.status)
            // Kein Work-Titel wurde gestartet (Queue bleibt die Rest-Playlist).
            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertTrue(playback.playSongAtCalls.isEmpty())
        }

    @Test
    fun `Plan abbrechen ohne aktiven Plan ist ein No-op`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.advanceUntilIdle()

            coordinator.cancelPlan()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(DropSyncState.Off, coordinator.state.value)
            assertTrue(playback.cancelLandingCalls == 0)
        }

    @Test
    fun `rekonstruierter REST plant neu ohne Queue-Reset`() =
        runTest(dispatcher) {
            // C13: Der Marker ueberlebt den Kill; die Queue hat der Player
            // selbst wiederhergestellt — der Koordinator darf sie nicht per
            // setQueue(0) reissen.
            restSetup()
            planStore.save(DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, "session-1"))
            playback.persistedState =
                PersistedPlayerState(
                    queueSongIds = listOf(10L),
                    currentSongId = 10L,
                    positionMs = 5_000L,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                )

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(playback.setQueueCalls.isEmpty())
            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)
            assertTrue(coordinator.state.value is DropSyncState.Armed)
        }

    @Test
    fun `rekonstruierter REST ohne Work-Drop zeigt PLAN_LOST`() =
        runTest(dispatcher) {
            restSetup()
            markers.markersBySong.remove(20L)
            planStore.save(DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, "session-1"))

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.PLAN_LOST),
                coordinator.state.value,
            )
            assertNull(planStore.current())
        }

    @Test
    fun `frische REST-Sitzung ohne Marker zeigt kein PLAN_LOST`() =
        runTest(dispatcher) {
            restSetup()
            markers.markersBySong.remove(20L)
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            coordinator.onRecoveryFinished()

            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.NO_WORK_DROP),
                coordinator.state.value,
            )
        }

    @Test
    fun `manueller DropRest nach Kill meldet PLAN_LOST und laesst sich quittieren`() =
        runTest(dispatcher) {
            planStore.save(DropSyncPlanMarker(DropSyncPlanKind.MANUAL_DROP_REST, "session-9"))

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.advanceUntilIdle()
            coordinator.onRecoveryFinished()

            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.PLAN_LOST),
                coordinator.state.value,
            )
            assertNull(planStore.current())

            coordinator.acknowledgePlanLost()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(DropSyncState.Off, coordinator.state.value)
        }

    @Test
    fun `manueller DropRest setzt den Marker und raeumt ihn am Sitzungsende auf`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.advanceUntilIdle()

            engine.startDropSync(requestedDurationMs = 30_000L, markerPositionMs = 60_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(DropSyncPlanKind.MANUAL_DROP_REST, planStore.current()?.kind)

            engine.cancel(CancelReason.PLAYBACK_INTERRUPTED)
            engine.reset()
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(planStore.current())
        }

    @Test
    fun `setQueue-Fehlschlag meldet PLAYBACK_ERROR statt still zu bleiben`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST
            playback.setQueueResult = AppResult.Failure(AppError.MediaUnavailable(mediaStoreId = 10L))

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR),
                coordinator.state.value,
            )
        }

    @Test
    fun `skip innerhalb der Rest-Queue waehrend Armed ergibt Overridden mit SKIPPED`() =
        runTest(dispatcher) {
            restSetup()
            browse.songsByPlaylist[1L] = listOf(song(10L), song(11L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            playback.stateFlow.value =
                PlaybackState(isPlaying = true, queueSongIds = listOf(10L, 11L))

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            playback.stateFlow.value =
                PlaybackState(isPlaying = true, currentSongId = 10L, queueSongIds = listOf(10L, 11L))
            dispatcher.scheduler.advanceUntilIdle()
            playback.stateFlow.value =
                PlaybackState(isPlaying = true, currentSongId = 11L, queueSongIds = listOf(10L, 11L))
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                DropSyncState.Overridden(OverrideReason.SKIPPED),
                coordinator.state.value,
            )
        }

    @Test
    fun `Undo nach Skip armierrt denselben Plan neu`() =
        runTest(dispatcher) {
            restSetup()
            browse.songsByPlaylist[1L] = listOf(song(10L), song(11L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            playback.stateFlow.value =
                PlaybackState(isPlaying = true, queueSongIds = listOf(10L, 11L))

            val coordinator = coordinator()
            coordinator.start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            playback.stateFlow.value =
                PlaybackState(isPlaying = true, currentSongId = 10L, queueSongIds = listOf(10L, 11L))
            dispatcher.scheduler.advanceUntilIdle()
            playback.stateFlow.value =
                PlaybackState(isPlaying = true, currentSongId = 11L, queueSongIds = listOf(10L, 11L))
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(coordinator.replanAfterOverride())
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(coordinator.state.value is DropSyncState.Armed)
        }

    @Test
    fun `Undo ohne laufende Sitzung ist ein No-op`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(!coordinator.replanAfterOverride())
        }

    @Test
    fun `C16 kette terminiert zwei uebergaenge`() =
        runTest(dispatcher) {
            // C16 (5.16): Die Kette fuellt die Pause aus der laufenden
            // Wiedergabe-Liste (5.17) und landet auf dem Drop; der
            // Zwischenwechsel laeuft deadline-basiert, die Landung ist
            // armiert.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            library.songsById[1L] = song(1L, durationMs = 30_000L)
            library.songsById[2L] = song(2L, durationMs = 300_000L)
            library.songsById[3L] = song(3L, durationMs = 300_000L)
            markers.markersBySong[2L] = listOf(marker(id = 12L, positionMs = 20_000L, songId = 2L))
            markers.markersBySong[3L] = listOf(marker(id = 13L, positionMs = 30_000L, songId = 3L))
            playback.snapshotSongId = 1L
            playback.snapshotQueue = listOf(queueItem(1L), queueItem(2L), queueItem(3L))
            playback.snapshotIndex = 0

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.runCurrent()

            // Kette: Song 1 (40 s) -> Song 2 (20 s, Wechsel auf seinem
            // Drop) -> Landung Song 3 bei 60 s.
            val armed = coordinator.state.value as DropSyncState.Armed
            assertEquals(listOf("Song 1", "Song 2", "Song 3"), armed.plan.chain)
            assertEquals(listOf(ArmCall(3L, 0L, 60_000L, 0L)), playback.armCalls)

            // Erster Zwischenwechsel nach 40 s.
            dispatcher.scheduler.advanceTimeBy(40_000L)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(2L to 0L), playback.playSongAtCalls)
        }

    @Test
    fun `C16 replan baut die kette neu`() =
        runTest(dispatcher) {
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            library.songsById[1L] = song(1L, durationMs = 30_000L)
            library.songsById[2L] = song(2L, durationMs = 300_000L)
            library.songsById[3L] = song(3L, durationMs = 300_000L)
            markers.markersBySong[2L] = listOf(marker(id = 12L, positionMs = 20_000L, songId = 2L))
            markers.markersBySong[3L] = listOf(marker(id = 13L, positionMs = 30_000L, songId = 3L))
            playback.snapshotSongId = 1L
            playback.snapshotQueue = listOf(queueItem(1L), queueItem(2L), queueItem(3L))
            playback.snapshotIndex = 0

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()
            engine.start(TimerMode.REST, durationMs = 90_000L)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, playback.armCalls.size)

            // +15 s: die Landung rueckt nach, die Kette wird neu gebaut.
            engine.addTime(15_000L)
            dispatcher.scheduler.runCurrent()

            assertEquals(2, playback.armCalls.size)
            assertEquals(ArmCall(3L, 0L, 75_000L, 0L), playback.armCalls.last())
            val armed = coordinator.state.value as DropSyncState.Armed
            assertEquals(listOf("Song 1", "Song 2", "Song 3"), armed.plan.chain)
        }

    @Test
    fun `C16 ohne queue bleibt die einzellandung`() =
        runTest(dispatcher) {
            // Ohne Wiedergabe-Liste (leere Queue) greift der bestehende
            // Einzel-Landungs-Pfad aus der Work-Playlist.
            restSetup()
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            val coordinator = coordinator()
            coordinator.start()
            dispatcher.scheduler.runCurrent()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(ArmCall(20L, 0L, 10_000L, 0L)), playback.armCalls)
            val armed = coordinator.state.value as DropSyncState.Armed
            assertTrue(armed.plan.chain.isEmpty())
        }

    private fun playlist(id: Long) = Playlist(id = id, name = "P$id", trackCount = 1)

    /** C16: Warteschlangeneintrag fuer die Kettenquelle. */
    private fun queueItem(songId: Long) =
        QueueItem(
            mediaId = songId.toString(),
            songId = songId,
            title = "Song $songId",
            artist = null,
        )

    private fun song(
        id: Long,
        durationMs: Long = 180_000L,
    ) = Song(
        mediaStoreId = id,
        contentUri = "content://media/$id",
        displayName = "song$id.mp3",
        relativePath = "Music/",
        durationMs = durationMs,
        sizeBytes = 1_000L,
        dateModifiedSeconds = 0L,
        title = "Song $id",
        artist = "Artist $id",
        album = null,
        isAvailable = true,
    )

    private fun marker(
        id: Long,
        positionMs: Long,
        songId: Long,
    ) = SongMarker(
        id = id,
        label = "Drop",
        positionMs = positionMs,
        source = MarkerSource.MANUAL,
        isEnabled = true,
        linkedSongId = songId,
    )
}

private data class ArmCall(
    val songId: Long,
    val startPositionMs: Long,
    val delayMs: Long,
    val fadeMs: Long,
)

private class CoordinatorRestMusicSettings : RestMusicSettingsRepository {
    val behaviorState = MutableStateFlow(RestMusicBehavior.NORMAL)
    val dropAutoState = MutableStateFlow(RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED)

    override val behavior: Flow<RestMusicBehavior> = behaviorState

    override val dropAutoEnabled: Flow<Boolean> = dropAutoState

    override suspend fun setBehavior(behavior: RestMusicBehavior) {
        behaviorState.value = behavior
    }

    override suspend fun setDropAutoEnabled(enabled: Boolean) {
        dropAutoState.value = enabled
    }
}

private class CoordinatorPlaybackRepository : PlaybackRepository {
    val setQueueCalls = mutableListOf<Pair<List<Long>, Boolean>>()
    val playSongAtCalls = mutableListOf<Pair<Long, Long>>()
    val armCalls = mutableListOf<ArmCall>()
    var armResult: AppResult<Unit> = AppResult.success(Unit)
    var setQueueResult: AppResult<Unit> = AppResult.success(Unit)
    var persistedState: PersistedPlayerState? = null
    var cancelLandingCalls = 0
    var playing = true
    var snapshotSongId: Long? = null
    var snapshotPositionMs = 0L

    /** C16: Wiedergabe-Liste der Live-Momentaufnahme (Kettenquelle). */
    var snapshotQueue: List<QueueItem> = emptyList()
    var snapshotIndex: Int = -1

    val stateFlow = MutableStateFlow(PlaybackState(isPlaying = true))
    private val landingFlow = MutableSharedFlow<DropLandingEvent>(extraBufferCapacity = 8)

    override val state: Flow<PlaybackState> = stateFlow

    override val landingEvents: Flow<DropLandingEvent> = landingFlow

    fun emitLanding(event: DropLandingEvent) {
        landingFlow.tryEmit(event)
    }

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        setQueueCalls += songs.map { it.mediaStoreId } to playWhenReady
        return setQueueResult
    }

    override suspend fun playSongAt(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> {
        playSongAtCalls += song.mediaStoreId to startPositionMs
        return AppResult.success(Unit)
    }

    override suspend fun armLanding(
        song: Song,
        startPositionMs: Long,
        delayMs: Long,
        fadeMs: Long,
    ): AppResult<Unit> {
        armCalls += ArmCall(song.mediaStoreId, startPositionMs, delayMs, fadeMs)
        return armResult
    }

    override suspend fun cancelLanding(): AppResult<Unit> {
        cancelLandingCalls += 1
        return AppResult.success(Unit)
    }

    override suspend fun setScrubbingMode(enabled: Boolean): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun snapshotNow(): AppResult<PlaybackState> =
        AppResult.success(
            PlaybackState(
                isPlaying = playing,
                currentSongId = snapshotSongId,
                positionMs = snapshotPositionMs,
                queue = snapshotQueue,
                currentIndex = snapshotIndex,
            ),
        )

    override suspend fun play(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun pause(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToNext(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToPrevious(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun playNext(song: Song): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setPlaybackSpeed(speed: Float): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun lastPersistedState(): PersistedPlayerState? = persistedState
}

private class CoordinatorBrowseRepository : LibraryBrowseRepository {
    val playlistsByLabelMap = mutableMapOf<PlaylistLabel, List<Playlist>>()
    val songsByPlaylist = mutableMapOf<Long, List<Song>>()

    /** D5/A7: kombinierte Abfrage (Liste der Labels je Aufruf). */
    val labelOnceCalls = mutableListOf<PlaylistLabel>()

    override fun playlistsByLabel(label: PlaylistLabel): Flow<List<Playlist>> =
        flowOf(playlistsByLabelMap[label].orEmpty())

    override fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> = flowOf(songsByPlaylist[playlistId].orEmpty())

    override suspend fun songsForLabelOnce(label: PlaylistLabel): AppResult<List<Song>> {
        labelOnceCalls += label
        val songs =
            playlistsByLabelMap[label]
                .orEmpty()
                .flatMap { playlist -> songsByPlaylist[playlist.id].orEmpty() }
                .distinctBy { it.mediaStoreId }
        return AppResult.success(songs)
    }

    override val albums: Flow<List<Album>> = emptyFlow()
    override val artists: Flow<List<Artist>> = emptyFlow()
    override val genres: Flow<List<Genre>> = emptyFlow()
    override val folders: Flow<List<LibraryFolder>> = emptyFlow()
    override val playStats: Flow<List<com.dropsync.domain.library.SongPlayStat>> = emptyFlow()
    override val favorites: Flow<List<Song>> = emptyFlow()
    override val playlists: Flow<List<Playlist>> = emptyFlow()

    override fun songsByAlbum(album: String): Flow<List<Song>> = emptyFlow()

    override fun songsByArtist(artist: String): Flow<List<Song>> = emptyFlow()

    override fun songsByGenre(genre: String): Flow<List<Song>> = emptyFlow()

    override fun songsByFolder(relativePath: String): Flow<List<Song>> = emptyFlow()

    override fun recentlyAdded(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun recentlyPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun mostPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun isFavorite(songId: Long): Flow<Boolean> = flowOf(false)

    override suspend fun recordPlayback(songId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun shuffleCandidates(songIds: List<Long>): AppResult<List<ShuffleCandidate>> =
        AppResult.success(emptyList())

    override suspend fun setFavorite(
        songId: Long,
        favorite: Boolean,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun search(query: String): AppResult<List<Song>> = AppResult.success(emptyList())

    override suspend fun setPlaylistLabel(
        playlistId: Long,
        label: PlaylistLabel?,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun createPlaylist(name: String): AppResult<Long> = AppResult.success(0L)

    override suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
    ): AppResult<Int> = AppResult.success(songIds.size)

    override suspend fun removeFromPlaylist(
        playlistId: Long,
        position: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveInPlaylist(
        playlistId: Long,
        fromPosition: Int,
        toPosition: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun importM3uPlaylist(
        name: String,
        m3uText: String,
    ): AppResult<PlaylistImportResult> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))
}

private class CoordinatorMarkerRepository : MarkerRepository {
    val markersBySong = mutableMapOf<Long, List<SongMarker>>()

    /** D5/A7: Batch-Aufrufe der Planung (Liste der Song-IDs je Aufruf). */
    val batchCalls = mutableListOf<List<Long>>()

    override val unmatchedMarkers: Flow<List<SongMarker>> = emptyFlow()
    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> = emptyFlow()

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>> =
        AppResult.success(markersBySong[songId].orEmpty())

    override suspend fun getEnabledMarkersForSongs(songIds: List<Long>): AppResult<Map<Long, List<SongMarker>>> {
        batchCalls += songIds
        return AppResult.success(
            songIds.mapNotNull { id -> markersBySong[id]?.let { id to it } }.toMap(),
        )
    }

    override fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarker>> =
        flowOf(markersBySong[songId].orEmpty())

    override suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override val songsWithEnabledMarkers: Flow<Set<Long>> = emptyFlow()

    override suspend fun setMarkerEnabled(
        markerId: Long,
        enabled: Boolean,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun restoreMarker(marker: SongMarker): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun renameMarker(
        markerId: Long,
        newLabel: String,
    ): AppResult<Unit> = AppResult.success(Unit)
}

private class CoordinatorDropTargetRepository : DropTargetRepository {
    val targetsFlow = MutableStateFlow<Map<Long, Long>>(emptyMap())

    override fun observeTargetMarkerId(songId: Long): Flow<Long?> = targetsFlow.map { it[songId] }

    override val targets: Flow<Map<Long, Long>> = targetsFlow

    override suspend fun setTarget(
        songId: Long,
        markerId: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun clearTarget(songId: Long): AppResult<Unit> = AppResult.success(Unit)
}

private class CoordinatorRouteProfiles : RouteProfileRepository {
    var latencyMs: Long? = null

    override val currentProfile: Flow<AudioRouteProfile?> = emptyFlow()

    override suspend fun currentLatencyMs(): Long? = latencyMs

    override suspend fun markStale() = Unit

    override suspend fun upsert(profile: AudioRouteProfile) = Unit
}

private class CoordinatorRestDucking : RestDuckingGate {
    val activations = mutableListOf<Boolean>()

    override suspend fun setActive(active: Boolean) {
        activations += active
    }
}

private class CoordinatorAudioEngine : AudioEngineRepository {
    val config = MutableStateFlow(DspConfig())

    override val dspConfig: Flow<DspConfig> = config

    override val audioInfo: Flow<AudioInfo?> = flowOf(null)

    override suspend fun updateDspConfig(config: DspConfig) {
        this.config.value = config
    }

    override val eqPresets: Flow<List<EqPreset>> = flowOf(emptyList())

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> = AppResult.success(0L)

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override val activeOutputProfileKey: Flow<String?> = flowOf(null)

    override val bitPerfectSupport: Flow<BitPerfectSupport> = flowOf(BitPerfectSupport.UNAVAILABLE)
}

private class CoordinatorLibraryRepository : LibraryRepository {
    val songsById = mutableMapOf<Long, Song>()

    override val songs: Flow<List<Song>> = flowOf(emptyList())

    override val availableSongs: Flow<List<Song>> = flowOf(emptyList())

    override suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult> =
        AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun getSong(mediaStoreId: Long): AppResult<Song> =
        songsById[mediaStoreId]?.let { AppResult.success(it) }
            ?: AppResult.failure(AppError.Unknown("unbekannt"))

    override suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int> = AppResult.success(0)

    override fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>> = emptyFlow()

    override suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult> =
        AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override val scannedFiles: Flow<List<ScannedFile>> = emptyFlow()
}
