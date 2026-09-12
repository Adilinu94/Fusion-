package com.dropsync.feature.settings

import app.cash.turbine.test
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.ThemeMode
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeRestTimerPreferencesRepository
import com.dropsync.core.testing.FakeWorkoutRepository
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.audio.CrossfadeCurves
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.MixPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Zustandslogik des `SettingsViewModel` (Verbesserungsplan B-UI-1). Das
 * Modul hatte 1.327 Produktivzeilen und keinen einzigen Test.
 *
 * Geprueft wird, was hier wirklich Logik ist: die Mix-Setter rechnen
 * (Einschalten setzt eine Default-Dauer, Dauer wird geklemmt, Ducking wird
 * geklemmt) und lesen dazu die aktuelle Konfiguration. Die reinen
 * Weiterleitungen (`setThemeMode` und Geschwister) sind mit je einer Zeile
 * geprueft, weil ein vertauschtes Repository sonst niemandem auffaellt.
 *
 * `importFrom`/`exportTo` bleiben aussen vor: sie lesen und schreiben ueber
 * den `ContentResolver` echte SAF-Dokumente. Das ist ein eigenes Paket, kein
 * Nebenprodukt dieses Tests.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val restMusic = FakeRestMusicSettingsRepository()
    private val theme = FakeThemeSettingsRepository()
    private val accent = FakeAccentColorRepository()
    private val libraryViews = FakeLibraryViewPreferencesRepository()
    private val goals = RecordingWorkoutGoalRepository()
    private val heart = FakeHeartRateSourceForSettings()

    @Before
    fun setUpMainDispatcher() {
        // viewModelScope haengt am Main-Dispatcher; ohne setMain wirft jeder
        // launch-Aufruf "Module with the Main dispatcher had failed".
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(audio: FakeAudioEngineRepository = FakeAudioEngineRepository()) =
        SettingsViewModel(
            context = RuntimeEnvironment.getApplication(),
            markerRepository = FakeMarkerRepository(),
            libraryRepository = FakeLibraryRepository(),
            restMusicSettings = restMusic,
            themeSettings = theme,
            accentColorSettings = accent,
            restTimerPreferences = FakeRestTimerPreferencesRepository(),
            libraryViewPreferences = libraryViews,
            audioEngine = audio,
            flatSetRepository = FakeFlatSetRepository(),
            workoutRepository = FakeWorkoutRepository(),
            workoutGoalRepository = goals,
            heartRateSource = heart,
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    @Test
    fun `mix einschalten setzt die standarddauer`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository(DspConfig(crossfadeSeconds = 0))
            val model = viewModel(audio)

            model.setMixEnabled(true)
            advanceUntilIdle()

            assertEquals(
                SettingsViewModel.DEFAULT_MIX_SECONDS,
                audio.current.crossfadeSeconds,
            )
        }

    @Test
    fun `mix ausschalten setzt die dauer auf null`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository(DspConfig(crossfadeSeconds = 8))
            val model = viewModel(audio)

            model.setMixEnabled(false)
            advanceUntilIdle()

            assertEquals(0, audio.current.crossfadeSeconds)
        }

    @Test
    fun `mix dauer wird auf das erlaubte fenster geklemmt`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.setMixSeconds(999)
            advanceUntilIdle()
            assertEquals(CrossfadeCurves.MAX_SECONDS, audio.current.crossfadeSeconds)

            // 0 ist nur ueber den Schalter erreichbar, nicht ueber den Regler:
            // sonst koennte der Regler die Funktion still abschalten.
            model.setMixSeconds(0)
            advanceUntilIdle()
            assertEquals(1, audio.current.crossfadeSeconds)
        }

    @Test
    fun `mix preset aendert nur das preset`() =
        runTest(dispatcher) {
            val audio =
                FakeAudioEngineRepository(
                    DspConfig(crossfadeSeconds = 5, mixPreset = MixPreset.FADE),
                )
            val model = viewModel(audio)

            model.setMixPreset(MixPreset.SLAM)
            advanceUntilIdle()

            assertEquals(MixPreset.SLAM, audio.current.mixPreset)
            assertEquals("Dauer darf sich nicht mitaendern", 5, audio.current.crossfadeSeconds)
        }

    @Test
    fun `rest ducking wird auf den erlaubten bereich geklemmt`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.setRestDuckDb(-99.0)
            advanceUntilIdle()
            assertEquals(DspConfig.REST_DUCK_MIN_DB, audio.current.restDuckDb, 0.0)

            model.setRestDuckDb(42.0)
            advanceUntilIdle()
            assertEquals(DspConfig.REST_DUCK_MAX_DB, audio.current.restDuckDb, 0.0)
        }

    @Test
    fun `jeder setter schreibt in sein eigenes repository`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setRestMusicBehavior(RestMusicBehavior.REST_PLAYLIST)
            model.setThemeMode(ThemeMode.DARK)
            model.setAccentColor(AccentColor.BLUE)
            model.setSmartShuffleEnabled(true)
            model.setWeeklyTrainingGoal(5)
            advanceUntilIdle()

            assertEquals(RestMusicBehavior.REST_PLAYLIST, restMusic.lastWritten)
            assertEquals(ThemeMode.DARK, theme.lastWritten)
            assertEquals(AccentColor.BLUE, accent.lastWritten)
            assertEquals(true, libraryViews.lastShuffleWritten)
            assertEquals(5, goals.lastWritten)
        }

    @Test
    fun `herzfrequenz-sync-ausschalten erreicht die quelle`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setHeartRateSyncEnabled(false)
            advanceUntilIdle()

            assertEquals(false, heart.lastSyncWritten)
            model.heartRateSyncEnabled.test {
                awaitItem() // Startwert true, dann der Fake-Wert.
                assertEquals(false, awaitItem())
            }
        }

    @Test
    fun `herzfrequenz-verfuegbarkeit wird durchgereicht`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.heartRateAvailability.test {
                awaitItem() // Startwert NOT_AVAILABLE, dann der Fake-Wert.
                assertEquals(
                    com.dropsync.domain.health.HeartRateAvailability.READY,
                    awaitItem(),
                )
            }
        }
}
