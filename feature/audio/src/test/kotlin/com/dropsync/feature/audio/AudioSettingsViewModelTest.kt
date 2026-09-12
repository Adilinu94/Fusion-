package com.dropsync.feature.audio

import app.cash.turbine.test
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Zustandslogik des `AudioSettingsViewModel` (Verbesserungsplan B-UI-1,
 * P2-15 zweite Haelfte). Das Modul hatte bis hierher keinen einzigen Test.
 *
 * Geprueft wird, was hier wirklich Logik ist:
 * - `update` liest die persistierte Konfiguration und reiht Schreibvorgaenge
 *   aneinander (Regressionstest fuer die `.value`-Falle: zwei schnelle
 *   Aenderungen duerfen sich nicht still uebereinanderschreiben),
 * - `setGraphicBandCount` baut neutrale ISO-Baender und laesst den Rest der
 *   Konfiguration unangetastet,
 * - `setBandGain` aendert genau ein Band und ignoriert ungueltige Indizes
 *   ohne Absturz,
 * - Preset-Aufrufe werden unverfaelscht durchgereicht.
 *
 * Bewusst ohne Robolectric (Abweichung von der Settings-Pattern-Vorlage):
 * dieses ViewModel injiziert keinen Context, ruft kein android.util.Log und
 * greift auf keine Ressourcen — `Dispatchers.setMain` genuegt, wie es
 * `TrainViewModelTest` im Schwestermodul vorfuehrt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

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

    private fun viewModel(repository: FakeAudioEngineRepository) = AudioSettingsViewModel(repository)

    @Test
    fun `graphic band count baut neutrale iso-baender und haelt den rest`() =
        runTest(dispatcher) {
            val audio =
                FakeAudioEngineRepository(
                    DspConfig(preampDb = 3.5, eq = EqSettings(enabled = true)),
                )
            val model = viewModel(audio)

            model.setGraphicBandCount(31)
            advanceUntilIdle()

            val bands = audio.current.eq.bands
            assertEquals(31, bands.size)
            assertEquals(
                "alle Baender muessen neutral starten",
                true,
                bands.all { it.gainDb == 0.0 },
            )
            assertEquals(EqSettings.GRAPHIC_31, bands.map { it.frequencyHz })
            assertEquals("Gueteklasse 31 Bander", 4.32, bands.first().q, 0.0)
            assertEquals("EQ-Modus darf sich nicht mitaendern", true, audio.current.eq.enabled)
            assertEquals("Preamp darf sich nicht mitaendern", 3.5, audio.current.preampDb, 0.0)
            assertEquals(1, audio.writes.size)
        }

    @Test
    fun `band gain aendert genau ein band`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.setBandGain(2, 6.5)
            advanceUntilIdle()

            val bands = audio.current.eq.bands
            assertEquals(6.5, bands[2].gainDb, 0.0)
            assertEquals("Nachbarbaender bleiben unberuehrt", 0.0, bands[1].gainDb, 0.0)
            assertEquals(0.0, bands[3].gainDb, 0.0)
            assertEquals("Frequenzen bleiben unberuehrt", EqSettings.GRAPHIC_10, bands.map { it.frequencyHz })
            assertEquals(1, audio.writes.size)
        }

    /**
     * Gegenbeweis zur `.value`-Falle: `update` muss den Repository-Stand
     * lesen, nicht den StateFlow-Wert. Ohne Abonnenten liefert
     * `dspConfig.value` dauernd den Initialwert — die zweite Aenderung
     * wuerde die erste still loeschen. (Vor dem Fix rot, danach gruen.)
     */
    @Test
    fun `zwei updates hintereinander akkumulieren statt ueberschreiben`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.setBandGain(0, 5.0)
            advanceUntilIdle()
            model.setBandGain(1, 3.0)
            advanceUntilIdle()

            assertEquals(
                "erste Aenderung muss erhalten bleiben",
                5.0,
                audio.current.eq.bands[0]
                    .gainDb,
                0.0,
            )
            assertEquals(
                3.0,
                audio.current.eq.bands[1]
                    .gainDb,
                0.0,
            )
            assertEquals(2, audio.writes.size)
        }

    @Test
    fun `band gain mit ungueltigem index aendert kein band und stuerzt nicht ab`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.setBandGain(99, 5.0)
            advanceUntilIdle()

            assertEquals(
                "kein Band darf sich geaendert haben",
                true,
                audio.current.eq.bands
                    .all { it.gainDb == 0.0 },
            )
        }

    @Test
    fun `preset-aufrufe werden unverfaelscht durchgereicht`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)
            val bands = listOf(EqBand(frequencyHz = 1_000.0, gainDb = 2.0))

            model.applyPreset(5)
            model.savePreset("Test-Preset", bands)
            model.deletePreset(7)
            advanceUntilIdle()

            assertEquals(listOf(5L), audio.appliedPresetIds)
            assertEquals(listOf("Test-Preset" to bands), audio.savedPresets)
            assertEquals(listOf(7L), audio.deletedPresetIds)
            assertEquals("Presets duerfen die DSP-Konfiguration nicht anfassen", 0, audio.writes.size)
        }

    @Test
    fun `dspConfig spiegelt repository-aenderungen an abonnenten`() =
        runTest(dispatcher) {
            val audio = FakeAudioEngineRepository()
            val model = viewModel(audio)

            model.dspConfig.test {
                assertEquals("Initialwert beim Abonnieren", DspConfig(), awaitItem())

                model.setBandGain(0, 4.0)
                advanceUntilIdle()

                val written = awaitItem()
                assertEquals(4.0, written.eq.bands[0].gainDb, 0.0)
                assertEquals("Abonnent sieht den persistierten Stand", audio.current, written)
            }
        }
}
