package com.dropsync.data.timer

import com.dropsync.domain.playback.PlayerVolumeGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Komponierte Cue-Ausgabe (Bauplan Schritt 8) gegen Fake-Ports:
 * Settings-Gates, Textformatierung, Ducking-Ruecknahme und
 * Session-Bindung — ohne Android-Systemdienste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCueOutputTest {
    private val speaker = FakeSpeaker()
    private val haptics = FakeHaptics()
    private val beeps = FakeBeeps()
    private val gate = FakeGate(volume = 1.0f)
    private val ducking = DuckingController(gate)
    private val output =
        AndroidCueOutput(
            tts = speaker,
            haptics = haptics,
            beepPlayer = beeps,
            ducking = ducking,
            formatter = SpeechTextFormatter(Locale.GERMAN),
            // Unconfined: Cue-Launches laufen synchron im Test-Thread.
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )

    // --- speak --------------------------------------------------------------

    @Test
    fun `speak duckt die Lautstaerke und reicht den formatierten Text durch`() {
        output.speak("s1", 45)

        assertEquals(listOf("s1" to "45 Sekunden"), speaker.spoken)
        // Basis 1.0, CueSettings.duckingPercent = 50 => 0.5.
        assertEquals(0.5f, gate.volume)
    }

    @Test
    fun `misslungene Ansage entduckt sofort wieder`() {
        speaker.succeeds = false

        output.speak("s1", 30)

        assertEquals(listOf("s1" to "30 Sekunden"), speaker.spoken)
        assertEquals(1.0f, gate.volume)
    }

    @Test
    fun `deaktiviertes TTS bleibt still und duckt nicht`() {
        output.settings = output.settings.copy(ttsEnabled = false)

        output.speak("s1", 45)

        assertTrue(speaker.spoken.isEmpty())
        assertEquals(1.0f, gate.volume)
    }

    @Test
    fun `unverfuegbares TTS wird uebersprungen`() {
        speaker.available = false

        output.speak("s1", 45)

        assertTrue(speaker.spoken.isEmpty())
        assertEquals(1.0f, gate.volume)
    }

    @Test
    fun `duckingPercent 100 drueckt die Lautstaerke auf null`() {
        output.settings = output.settings.copy(duckingPercent = 100)

        output.speak("s1", 45)

        assertEquals(0.0f, gate.volume)
    }

    @Test
    fun `ungueltiges duckingPercent duckt nicht, die Ansage laeuft trotzdem`() {
        // 0/50/100 sind gueltig; 30 lehnt der DuckingController ab.
        output.settings = output.settings.copy(duckingPercent = 30)

        output.speak("s1", 45)

        assertEquals(listOf("s1" to "45 Sekunden"), speaker.spoken)
        assertEquals(1.0f, gate.volume)
    }

    // --- haptic / Beeps -----------------------------------------------------

    @Test
    fun `haptic tickt nur bei eingeschalteter Haptik`() {
        output.haptic("s1")
        assertEquals(1, haptics.ticks)

        output.settings = output.settings.copy(hapticsEnabled = false)
        output.haptic("s2")
        assertEquals(1, haptics.ticks)
    }

    @Test
    fun `countdownBeep spielt nur bei eingeschaltetem Ton`() {
        output.countdownBeep("s1")
        assertEquals(1, beeps.shortBeeps)

        output.settings = output.settings.copy(completionToneEnabled = false)
        output.countdownBeep("s2")
        assertEquals(1, beeps.shortBeeps)
    }

    @Test
    fun `tone kombiniert Abschluss-Haptik und Go-Beep unabhaengig voneinander`() {
        output.tone("s1")
        assertEquals(1, haptics.completions)
        assertEquals(1, beeps.goBeeps)

        output.settings = output.settings.copy(hapticsEnabled = false)
        output.tone("s2")
        assertEquals(1, haptics.completions)
        assertEquals(2, beeps.goBeeps)

        output.settings =
            output.settings.copy(hapticsEnabled = true, completionToneEnabled = false)
        output.tone("s3")
        assertEquals(2, haptics.completions)
        assertEquals(2, beeps.goBeeps)
    }

    // --- stopAll ------------------------------------------------------------

    @Test
    fun `stopAll stoppt TTS und stellt die Lautstaerke wieder her`() {
        output.speak("s1", 45)
        assertEquals(0.5f, gate.volume)

        output.stopAll("s1")

        assertTrue(speaker.stopCalls >= 1)
        assertEquals(1.0f, gate.volume)
    }

    // --- Fakes ---------------------------------------------------------------

    private class FakeSpeaker : CueSpeaker {
        var available = true
        var succeeds = true
        var stopCalls = 0
        val spoken = mutableListOf<Pair<String, String>>()

        override fun isAvailable(): Boolean = available

        override fun speak(
            cueSessionId: String,
            text: String,
        ): Boolean {
            spoken += cueSessionId to text
            return succeeds
        }

        override fun stop() {
            stopCalls++
        }
    }

    private class FakeHaptics : CueHaptics {
        var ticks = 0
        var completions = 0

        override fun tick() {
            ticks++
        }

        override fun completion() {
            completions++
        }
    }

    private class FakeBeeps : CueBeeps {
        var shortBeeps = 0
        var goBeeps = 0

        override fun shortBeep() {
            shortBeeps++
        }

        override fun goBeep() {
            goBeeps++
        }
    }

    private class FakeGate(
        var volume: Float,
    ) : PlayerVolumeGate {
        override suspend fun currentVolume(): Float = volume

        override suspend fun setVolume(volume: Float) {
            this.volume = volume
        }
    }
}
