package com.dropsync.data.timer

import android.content.Context
import android.os.Vibrator
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Haptikadapter (Schritt 8.5) und Abschluss-Signalton: Beide Ausgaben
 * laufen unter Robolectric ohne Fehler; der Adapter erreicht den echten
 * System-Vibrator und der Signalton startet auf dem Schatten-Generator.
 */
class HapticsAdapterTest : RobolectricTestCase() {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `tick und completion loesen eine Vibration aus`() {
        val adapter = HapticsAdapter(context)
        val vibrator =
            context
                .getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?: context.getSystemService(Vibrator::class.java)
        assertNotNull(vibrator)

        adapter.tick()
        assertTrue("tick muss den Vibrator erreichen", shadowOf(vibrator).isVibrating)

        adapter.completion()
        assertTrue("completion muss den Vibrator erreichen", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `Abschluss-Signalton startet ohne Fehler`() {
        CompletionTonePlayer().play()
    }
}
