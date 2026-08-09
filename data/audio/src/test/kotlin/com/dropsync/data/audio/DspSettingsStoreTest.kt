package com.dropsync.data.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.audio.DspConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Persistenz und Bereichspruefung der DSP-Konfiguration (Phase 1). */
@RunWith(AndroidJUnit4::class)
class DspSettingsStoreTest {
    @Test
    fun `save sanitisiert und config liefert die werte zurueck`() =
        runTest {
            val store = DspSettingsStore(ApplicationProvider.getApplicationContext())

            store.save(DspConfig(enabled = false, preampDb = 25.0, limiterEnabled = false))

            val loaded = store.config.first()
            assertEquals(false, loaded.enabled)
            // 25 dB liegt ausserhalb des Bereichs und wird auf +12 begrenzt.
            assertEquals(12.0, loaded.preampDb, 0.0)
            assertEquals(false, loaded.limiterEnabled)
        }
}
