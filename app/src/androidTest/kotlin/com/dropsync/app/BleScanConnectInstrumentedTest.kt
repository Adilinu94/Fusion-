package com.dropsync.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.data.sensor.BleSensorProvider
import com.dropsync.domain.sensor.SensorConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierter BLE-Test (Testinfra-Plan Schritt 4, T3):
 * Scan -> Connect -> Service-Discovery -> MTU-Verhandlung -> STREAMING.
 *
 * Benoetigt ein echtes M5StickC-Plus2 (kein Emulator-BLE). Per
 * Standard mit @Ignore, damit CI nicht kippt; auf dem Testgeraet den
 * @Ignore entfernen oder via Runner-Argument explizit mitlaufen lassen.
 */
@RunWith(AndroidJUnit4::class)
class BleScanConnectInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun provider(): BleSensorProvider =
        BleSensorProvider(
            context,
            object : DispatcherProvider {
                override val io = kotlinx.coroutines.Dispatchers.IO
                override val default = kotlinx.coroutines.Dispatchers.Default
                override val main = kotlinx.coroutines.Dispatchers.Main
            },
        )

    @Test
    @Ignore("Benoetigt echtes M5StickC-Plus2 - manueller Test")
    fun scan_connect_mtu_streaming_mit_echtem_sensor() {
        assumeTrue(
            "BLUETOOTH_CONNECT fehlt",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val provider = provider()
        runBlocking {
            val result = provider.connect(deviceId = null)
            assertTrue("Verbindung fehlgeschlagen: $result", result is AppResult.Success)
            val state =
                withTimeout(60_000) {
                    provider.connectionState.first { it == SensorConnectionState.STREAMING }
                }
            assertEquals(SensorConnectionState.STREAMING, state)
        }
        runBlocking { provider.disconnect() }
    }

    @Test
    @Ignore("Benoetigt echtes M5StickC-Plus2 - manueller Test")
    fun sensor_liefert_daten_nach_verbindung() {
        assumeTrue(
            "BLUETOOTH_CONNECT fehlt",
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val provider = provider()
        runBlocking {
            val result = provider.connect(deviceId = null)
            assertTrue("Verbindung fehlgeschlagen: $result", result is AppResult.Success)
            val firstSample = withTimeout(60_000) { provider.samples.first() }
            assertTrue(
                "Sensor muss Daten liefern",
                firstSample.gx != 0.0 || firstSample.ax != 0.0,
            )
        }
        runBlocking { provider.disconnect() }
    }
}
