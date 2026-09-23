package com.dropsync.data.timer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * TTS-Adapter (Bauplan Schritt 8.1-8.3): Verfuegbarkeits-Gate,
 * Fehlschlag nie fatal, Attribute und Utterance-ID an die eigene
 * Cue-Session gebunden — gegen den Robolectric-TextToSpeech-Schatten.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TtsSpeakerTest {
    private val finished = mutableListOf<String>()
    private val speaker =
        TtsSpeaker(androidx.test.core.app.ApplicationProvider.getApplicationContext()) { finished += it }

    private fun initShadow(
        locale: java.util.Locale,
        status: Int = android.speech.tts.TextToSpeech.SUCCESS,
    ): ShadowTextToSpeech {
        ShadowTextToSpeech.addLanguageAvailability(locale)
        speaker.initialize(locale)
        val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val shadow = Shadows.shadowOf(tts)
        shadow.onInitListener.onInit(status)
        return shadow
    }

    // --- Verfuegbarkeits-Gate ---------------------------------------------

    @Test
    fun `vor der Initialisierung ist kein TTS verfuegbar`() {
        assertFalse(speaker.isAvailable())
        assertFalse(speaker.speak("s1", "Text"))
    }

    @Test
    fun `erfolgreiche Initialisierung schaltet die Ansage frei`() {
        val shadow = initShadow(java.util.Locale.GERMAN)

        assertTrue(speaker.isAvailable())
        assertTrue(speaker.speak("s1", "45 Sekunden"))
        assertEquals("45 Sekunden", shadow.lastSpokenText)
    }

    @Test
    fun `fehlgeschlagene Initialisierung laesst den Timer weiter laufen`() {
        initShadow(java.util.Locale.GERMAN, status = android.speech.tts.TextToSpeech.ERROR)

        assertFalse(speaker.isAvailable())
        assertFalse(speaker.speak("s1", "Text"))
    }

    // --- Utterance-Bindung an die Cue-Session -----------------------------

    @Test
    fun `Callbacks reichen nur die eigene Cue-Session weiter`() {
        val shadow = initShadow(java.util.Locale.GERMAN)
        assertTrue(speaker.speak("session-a", "Text"))

        shadow.utteranceProgressListener.onDone("session-a|42")
        assertEquals(listOf("session-a"), finished)

        // Fehler (beide Ueberladungen) beenden die Session ebenfalls.
        shadow.utteranceProgressListener.onError("session-a|43")
        @Suppress("DEPRECATION")
        shadow.utteranceProgressListener.onError("session-a|44")
        assertEquals(listOf("session-a", "session-a", "session-a"), finished)
    }

    // sessionIdOf selbst ist in TtsSpeakerCompanionTest abgedeckt.

    // --- Stoppen und Abschalten -------------------------------------------

    @Test
    fun `stop beendet laufende Ansagen sofort`() {
        val shadow = initShadow(java.util.Locale.GERMAN)
        speaker.stop()
        assertTrue(shadow.isStopped)
    }

    @Test
    fun `shutdown schaltet die Ansage ab und macht neue Ansagen unmoeglich`() {
        initShadow(java.util.Locale.GERMAN)
        speaker.shutdown()

        assertFalse(speaker.isAvailable())
        assertFalse(speaker.speak("s1", "Text"))
    }

    @Test
    fun `ein zweites initialize ohne shutdown erzeugt keine zweite Engine`() {
        speaker.initialize(java.util.Locale.GERMAN)
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        speaker.initialize(java.util.Locale.GERMAN)
        assertEquals(first, ShadowTextToSpeech.getLastTextToSpeechInstance())
    }
}
