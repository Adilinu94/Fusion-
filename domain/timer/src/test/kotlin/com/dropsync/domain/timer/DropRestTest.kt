package com.dropsync.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vorbedingungen und effektive Dauer fuer Drop-Rest (Bauplan 11.2/11.3). */
class DropRestGateTest {
    private val markers =
        listOf(
            MarkerPoint(markerId = 1, positionMs = 60_000),
            MarkerPoint(markerId = 2, positionMs = 120_000),
        )

    private fun playing(positionMs: Long) = PlaybackSample(songId = 42, positionMs = positionMs, isPlaying = true)

    @Test
    fun `effektive dauer ist markerposition minus playerposition`() {
        // 11.3: Die UI zeigt genau diese Dauer, kein editierbarer Wert.
        val result = DropRestGate.evaluate(playing(positionMs = 45_000), markers)
        val eligible = result as DropRestEligibility.Eligible
        assertEquals(1L, eligible.markerId)
        assertEquals(60_000L, eligible.markerPositionMs)
        assertEquals(15_000L, eligible.effectiveDurationMs)
    }

    @Test
    fun `zu naher marker wird uebersprungen zugunsten des naechsten`() {
        // 3 s bis Marker 1 (< 5 s Untergrenze) -> Marker 2 qualifiziert.
        val result = DropRestGate.evaluate(playing(positionMs = 57_000), markers)
        val eligible = result as DropRestEligibility.Eligible
        assertEquals(2L, eligible.markerId)
        assertEquals(63_000L, eligible.effectiveDurationMs)
    }

    @Test
    fun `ohne aktuellen song ist der modus gesperrt`() {
        val result =
            DropRestGate.evaluate(
                PlaybackSample(songId = null, positionMs = 0, isPlaying = true),
                markers,
            )
        assertEquals(
            DropRestBlockReason.NO_CURRENT_SONG,
            (result as DropRestEligibility.Ineligible).reason,
        )
    }

    @Test
    fun `ohne laufende wiedergabe ist der modus gesperrt`() {
        val result =
            DropRestGate.evaluate(
                PlaybackSample(songId = 42, positionMs = 10_000, isPlaying = false),
                markers,
            )
        assertEquals(
            DropRestBlockReason.PLAYBACK_NOT_RUNNING,
            (result as DropRestEligibility.Ineligible).reason,
        )
    }

    @Test
    fun `ohne zukuenftigen marker ist der button deaktiviert`() {
        // Abnahme Schritt 11: Button deaktiviert und erklaert warum.
        val result = DropRestGate.evaluate(playing(positionMs = 130_000), markers)
        assertEquals(
            DropRestBlockReason.NO_FUTURE_MARKER,
            (result as DropRestEligibility.Ineligible).reason,
        )
    }

    @Test
    fun `alle zukuenftigen marker unter mindestdauer sperren den start`() {
        val result =
            DropRestGate.evaluate(
                playing(positionMs = 118_000),
                listOf(MarkerPoint(markerId = 2, positionMs = 120_000)),
            )
        assertEquals(
            DropRestBlockReason.MARKER_TOO_CLOSE,
            (result as DropRestEligibility.Ineligible).reason,
        )
    }
}

/** Abbruchregeln waehrend Drop-Rest (Bauplan 11.4). */
class DropRestMonitorTest {
    private fun sample(
        songId: Long? = 42,
        positionMs: Long,
        isPlaying: Boolean = true,
        hasError: Boolean = false,
    ) = PlaybackSample(songId, positionMs, isPlaying, hasError)

    @Test
    fun `normale progression liefert keinen abbruch`() {
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(positionMs = 11_000),
                elapsedMsBetweenSamples = 1_000,
            )
        assertNull(result)
    }

    @Test
    fun `songwechsel beendet nur den timer`() {
        // Abnahme Schritt 11: Songwechsel bricht den Timer ab, die
        // Workout-Session bleibt unveraendert (kein Datenpfad hier).
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(songId = 43, positionMs = 0),
                elapsedMsBetweenSamples = 1_000,
            )
        assertEquals(DropRestInterruption.SONG_CHANGED, result)
    }

    @Test
    fun `pause beendet den modus`() {
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(positionMs = 10_400, isPlaying = false),
                elapsedMsBetweenSamples = 1_000,
            )
        assertEquals(DropRestInterruption.PAUSED, result)
    }

    @Test
    fun `seek ausserhalb der toleranz beendet den modus`() {
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(positionMs = 40_000),
                elapsedMsBetweenSamples = 1_000,
            )
        assertEquals(DropRestInterruption.SEEK, result)
    }

    @Test
    fun `playerfehler hat vorrang vor allen anderen ursachen`() {
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(songId = null, positionMs = 0, isPlaying = false, hasError = true),
                elapsedMsBetweenSamples = 1_000,
            )
        assertEquals(DropRestInterruption.PLAYER_ERROR, result)
    }

    @Test
    fun `kleine positionsabweichung innerhalb toleranz ist kein seek`() {
        val result =
            DropRestMonitor.detect(
                startedSongId = 42,
                previous = sample(positionMs = 10_000),
                current = sample(positionMs = 12_400),
                elapsedMsBetweenSamples = 1_000,
            )
        assertNull(result)
    }
}
