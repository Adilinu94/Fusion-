package com.dropsync.data.audio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.audio.BiquadType
import com.dropsync.domain.audio.BuiltInEqPresets
import com.dropsync.domain.audio.EqBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EQ-Preset-Seed und -CRUD gegen eine echte In-Memory-Room-DB
 * (Plan Phase 2, Punkt 8): idempotenter Seed, Nutzerpresets, Schutz der
 * eingebauten Presets, Uebernahme in die DSP-Konfiguration.
 */
@RunWith(AndroidJUnit4::class)
class EqPresetRepositoryTest {
    private lateinit var db: DropSyncDatabase
    private lateinit var store: DspSettingsStore
    private lateinit var seeder: EqPresetSeeder
    private lateinit var repository: AudioEngineRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                .build()
        store = DspSettingsStore(context)
        seeder = EqPresetSeeder(db.eqPresetDao())
        repository =
            AudioEngineRepositoryImpl(
                settingsStore = store,
                pipeline = AudioPipeline(store, OutputDeviceMonitor(context)),
                eqPresetDao = db.eqPresetDao(),
                transactionRunner = RoomTransactionRunner(db),
                dispatchers = TestDispatcherProvider(),
                profileController =
                    OutputProfileController(
                        deviceSnapshots = OutputDeviceMonitor(context).device,
                        settingsStore = store,
                        profileStore = DeviceProfileStore(context),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    ),
                bitPerfectGateway = BitPerfectGateway(context),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `seed spielt alle eingebauten presets mit baendern ein`() =
        runTest {
            seeder.seed()

            val presets = repository.eqPresets.first()
            assertEquals(BuiltInEqPresets.presets.size, presets.size)
            assertTrue(presets.all { it.isBuiltIn })
            // Jedes eingebaute Preset traegt die zehn Grafik-Baender.
            assertTrue(presets.all { it.bands.size == 10 })
            val rock = presets.first { it.name == "Rock" }
            assertEquals(31.5, rock.bands.first().frequencyHz, 0.001)
            assertEquals(BiquadType.PEAK, rock.bands.first().type)
        }

    @Test
    fun `seed ist idempotent`() =
        runTest {
            seeder.seed()
            seeder.seed()

            val presets = repository.eqPresets.first()
            assertEquals(BuiltInEqPresets.presets.size, presets.size)
            // Kein doppeltes Einspielen von Baendern.
            assertTrue(presets.all { it.bands.size == 10 })
        }

    @Test
    fun `nutzerpreset speichern lesen und loeschen`() =
        runTest {
            val bands =
                listOf(
                    EqBand(frequencyHz = 100.0, gainDb = 3.0, q = 1.0, type = BiquadType.PEAK),
                    EqBand(frequencyHz = 1_000.0, gainDb = -2.0, q = 1.0, type = BiquadType.PEAK),
                )

            val saved = repository.saveEqPreset("Mein Preset", bands)
            assertTrue(saved is AppResult.Success)
            val id = (saved as AppResult.Success).value

            val loaded = repository.eqPresets.first().firstOrNull { it.name == "Mein Preset" }
            assertNotNull(loaded)
            assertEquals(false, loaded!!.isBuiltIn)
            assertEquals(2, loaded.bands.size)
            assertEquals(3.0, loaded.bands.first().gainDb, 0.001)

            val deleted = repository.deleteEqPreset(id)
            assertTrue(deleted is AppResult.Success)
            assertTrue(repository.eqPresets.first().none { it.name == "Mein Preset" })
        }

    @Test
    fun `nutzerpreset ueberschreibt gleichnamige baender`() =
        runTest {
            repository.saveEqPreset(
                "X",
                listOf(EqBand(frequencyHz = 100.0, gainDb = 1.0)),
            )
            repository.saveEqPreset(
                "X",
                listOf(
                    EqBand(frequencyHz = 200.0, gainDb = 4.0),
                    EqBand(frequencyHz = 400.0, gainDb = 5.0),
                ),
            )

            val presets = repository.eqPresets.first().filter { it.name == "X" }
            assertEquals(1, presets.size)
            assertEquals(2, presets.first().bands.size)
            assertEquals(
                200.0,
                presets
                    .first()
                    .bands
                    .first()
                    .frequencyHz,
                0.001,
            )
        }

    @Test
    fun `eingebautes preset ist gesperrt und unloeschbar`() =
        runTest {
            seeder.seed()
            val rock = repository.eqPresets.first().first { it.name == "Rock" }

            val overwrite = repository.saveEqPreset("Rock", listOf(EqBand(frequencyHz = 100.0)))
            assertTrue(overwrite is AppResult.Failure)

            val delete = repository.deleteEqPreset(rock.id)
            assertTrue(delete is AppResult.Failure)

            // Unveraendert: weiterhin die zehn Original-Baender.
            assertEquals(
                10,
                repository.eqPresets
                    .first()
                    .first { it.name == "Rock" }
                    .bands.size,
            )
        }

    @Test
    fun `preset anwenden aktiviert eq und uebernimmt baender`() =
        runTest {
            seeder.seed()
            val bass = repository.eqPresets.first().first { it.name == "Bass Boost" }

            val result = repository.applyEqPreset(bass.id)
            assertTrue(result is AppResult.Success)

            val config = store.config.first()
            assertTrue(config.eq.enabled)
            assertEquals(bass.bands.size, config.eq.bands.size)
            assertEquals(
                bass.bands.first().gainDb,
                config.eq.bands
                    .first()
                    .gainDb,
                0.001,
            )
        }
}
