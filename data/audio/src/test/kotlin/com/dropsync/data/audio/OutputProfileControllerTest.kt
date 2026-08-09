package com.dropsync.data.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.OutputDeviceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Automatischer Profilwechsel je Ausgabegeraet (ADR-0008, Phase 5). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OutputProfileControllerTest {
    @Test
    fun `geraetewechsel legt profil an und wendet gespeichertes profil wieder an`() =
        runBlocking {
            val settingsStore = DspSettingsStore(ApplicationProvider.getApplicationContext())
            val profileStore = DeviceProfileStore(ApplicationProvider.getApplicationContext())
            val devices =
                MutableStateFlow(
                    OutputDeviceSnapshot(OutputDeviceKind.SPEAKER, "Lautsprecher", null),
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val controller =
                OutputProfileController(
                    deviceSnapshots = devices,
                    settingsStore = settingsStore,
                    profileStore = profileStore,
                    scope = scope,
                )
            controller.start()
            // Ohne Adresse dient der Produktname als Profil-Schluessel.
            awaitUntil { controller.activeProfileKey.value == "SPEAKER:lautsprecher" }
            assertNotNull(profileStore.read("SPEAKER:lautsprecher"))

            // Einstellung am Lautsprecher aendern: Save-Through ins Profil.
            settingsStore.save(DspConfig(preampDb = 5.0))
            awaitUntil {
                profileStore.read("SPEAKER:lautsprecher")?.preampDb == 5.0
            }

            // BT-Kopfhoerer mit eigenem Profil vorbereiten und umstecken.
            profileStore.write("BLUETOOTH_A2DP:aa_bb", DspConfig(preampDb = -4.0))
            devices.value =
                OutputDeviceSnapshot(OutputDeviceKind.BLUETOOTH_A2DP, "Kopfhoerer", "AA:BB")
            awaitUntil { controller.activeProfileKey.value == "BLUETOOTH_A2DP:aa_bb" }
            awaitUntil { settingsStore.config.first().preampDb == -4.0 }
            assertEquals(-4.0, settingsStore.config.first().preampDb, 1e-9)
            scope.cancel()
        }

    /** Echtzeit-Polling, weil DataStore auf echtem IO emittiert. */
    private suspend fun awaitUntil(
        timeoutMs: Long = 5_000,
        condition: suspend () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Bedingung nicht innerhalb $timeoutMs ms erfuellt")
            }
            delay(20)
        }
    }
}
