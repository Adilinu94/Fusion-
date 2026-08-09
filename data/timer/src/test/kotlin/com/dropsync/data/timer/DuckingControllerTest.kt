package com.dropsync.data.timer

import com.dropsync.domain.playback.PlayerVolumeGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private class FakeVolumeGate(
    var volume: Float = 0.8f,
) : PlayerVolumeGate {
    override suspend fun currentVolume(): Float = volume

    override suspend fun setVolume(volume: Float) {
        this.volume = volume
    }
}

class DuckingControllerTest {
    private val gate = FakeVolumeGate(volume = 0.8f)
    private val ducking = DuckingController(gate)

    @Test
    fun `ducking 50 prozent halbiert nur die eigene playerlautstaerke`() =
        runTest {
            assertTrue(ducking.beginCue("s1", duckingPercent = 50))
            assertEquals(0.4f, gate.volume, 0.0001f)

            ducking.endCue("s1")
            assertEquals(0.8f, gate.volume, 0.0001f)
        }

    @Test
    fun `ducking 100 prozent bedeutet stille waehrend der ausgabe`() =
        runTest {
            assertTrue(ducking.beginCue("s1", duckingPercent = 100))
            assertEquals(0f, gate.volume, 0.0001f)
            ducking.endCue("s1")
            assertEquals(0.8f, gate.volume, 0.0001f)
        }

    @Test
    fun `unbekannte duckingwerte werden abgelehnt`() =
        runTest {
            // Nur 0, 50, 100 sind erlaubt (Schritt 8.4).
            assertFalse(ducking.beginCue("s1", duckingPercent = 30))
            assertEquals(0.8f, gate.volume, 0.0001f)
        }

    @Test
    fun `nutzeraenderung waehrend der ansage wird neuer basiswert`() =
        runTest {
            ducking.beginCue("s1", duckingPercent = 50)
            // Person dreht waehrend der Ansage auf 0.6.
            ducking.onUserVolumeChanged("s1", newVolume = 0.6f)
            ducking.endCue("s1")
            assertEquals(0.6f, gate.volume, 0.0001f)
        }

    @Test
    fun `veraltete antwort einer alten session veraendert nichts`() =
        runTest {
            // Abnahme Schritt 8: alte TTS-Antwort darf die Lautstaerke
            // einer neuen Session nicht veraendern.
            ducking.beginCue("alt", duckingPercent = 50)
            ducking.endCue("alt")

            ducking.beginCue("neu", duckingPercent = 100)
            assertEquals(0f, gate.volume, 0.0001f)

            ducking.endCue("alt") // veralteter Callback
            assertEquals(0f, gate.volume, 0.0001f)

            ducking.endCue("neu")
            assertEquals(0.8f, gate.volume, 0.0001f)
        }

    @Test
    fun `abort stellt den basiswert sofort wieder her`() =
        runTest {
            ducking.beginCue("s1", duckingPercent = 100)
            ducking.abort("s1")
            assertEquals(0.8f, gate.volume, 0.0001f)
        }

    @Test
    fun `fremde session kann laufendes ducking nicht kapern`() =
        runTest {
            assertTrue(ducking.beginCue("s1", duckingPercent = 50))
            assertFalse(ducking.beginCue("s2", duckingPercent = 100))
            assertEquals(0.4f, gate.volume, 0.0001f)
        }
}

class SpeechTextFormatterTest {
    @Test
    fun `deutsch formatiert sekunden und minuten`() {
        val formatter = SpeechTextFormatter(Locale.GERMAN)
        assertEquals("30 Sekunden", formatter.format(30))
        assertEquals("1 Minute", formatter.format(60))
        assertEquals("3 Minuten", formatter.format(180))
        assertEquals("Fertig", formatter.format(0))
    }

    @Test
    fun `englisch formatiert sekunden und minuten`() {
        val formatter = SpeechTextFormatter(Locale.ENGLISH)
        assertEquals("30 seconds", formatter.format(30))
        assertEquals("1 minute", formatter.format(60))
        assertEquals("2 minutes", formatter.format(120))
        assertEquals("Done", formatter.format(0))
    }
}

class TtsSpeakerCompanionTest {
    @Test
    fun `utterance id enthaelt die session id vor dem separator`() {
        assertEquals("session-1", TtsSpeaker.sessionIdOf("session-1|123456"))
        assertEquals("ohne-suffix", TtsSpeaker.sessionIdOf("ohne-suffix"))
    }
}
