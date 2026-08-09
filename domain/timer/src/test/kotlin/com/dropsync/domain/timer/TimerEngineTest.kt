package com.dropsync.domain.timer

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zeichnet Ausgaben auf, um genau-einmal-Semantik zu pruefen. */
private class RecordingCueOutput : CueOutput {
    val spoken = mutableListOf<Pair<String, Int>>()
    val haptics = mutableListOf<String>()
    val tones = mutableListOf<String>()
    val stops = mutableListOf<String>()

    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) {
        spoken += cueSessionId to secondsRemaining
    }

    override fun haptic(cueSessionId: String) {
        haptics += cueSessionId
    }

    override fun tone(cueSessionId: String) {
        tones += cueSessionId
    }

    override fun stopAll(cueSessionId: String) {
        stops += cueSessionId
    }
}

class TimerEngineTest {
    private val clock = FakeClock(initialElapsedRealtimeMs = 100_000, initialEpochMillis = 1_000)
    private val cues = RecordingCueOutput()
    private val engine = TimerEngine(clock, cues) { "session-1" }

    @Test
    fun `normaler timer ueberlebt systemzeitaenderung unbeschadet`() {
        // Abnahme Schritt 7: verzogene Wanduhr beeinflusst den Timer nicht.
        engine.start(TimerMode.NORMAL, durationMs = 60_000)
        clock.setEpochMillis(9_999_999_999) // Systemzeit springt massiv.
        clock.advanceBy(20_000)
        engine.evaluate()

        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
        assertEquals(40_000, engine.state.value.remainingMs)
    }

    @Test
    fun `gehaeufte ticks liefern jeden grenzwert genau einmal`() {
        engine.start(TimerMode.REST, durationMs = 60_000)
        clock.advanceBy(30_000) // Rest 30 s -> Grenzwert 30 faellig.
        engine.evaluate()
        engine.evaluate()
        engine.evaluate()

        assertEquals(listOf("session-1" to 30), cues.spoken)
    }

    @Test
    fun `verspaeteter tick nach doze spricht nur den juengsten grenzwert`() {
        engine.start(TimerMode.NORMAL, durationMs = 60_000)
        // Geraet schlaeft durch: naechster Tick erst bei Rest 2 s.
        clock.advanceBy(58_000)
        engine.evaluate()

        // Nur der juengste Grenzwert (2 s) wird gesprochen; 30/10..3 sind
        // entwertet — keine Ansageflut nach Doze.
        assertEquals(listOf("session-1" to 2), cues.spoken)

        clock.advanceBy(2_000)
        engine.evaluate()
        assertEquals(TimerStatus.COMPLETED, engine.state.value.status)
        // Abschluss: genau ein Ton, Haptik fuer 2s- und 0s-Grenzwert.
        assertEquals(1, cues.tones.size)
    }

    @Test
    fun `abschluss passiert genau einmal auch bei weiteren ticks`() {
        engine.start(TimerMode.NORMAL, durationMs = 10_000)
        clock.advanceBy(10_000)
        engine.evaluate()
        engine.evaluate()

        assertEquals(TimerStatus.COMPLETED, engine.state.value.status)
        assertEquals(1, cues.tones.size)
    }

    @Test
    fun `zweiter start waehrend aktiver session liefert TimerConflict`() {
        engine.start(TimerMode.NORMAL, durationMs = 60_000)
        val second = engine.start(TimerMode.REST, durationMs = 30_000)

        assertTrue(second is AppResult.Failure)
        assertEquals(AppError.TimerConflict, (second as AppResult.Failure).error)
    }

    @Test
    fun `pause friert restzeit ein und resume laeuft monoton weiter`() {
        engine.start(TimerMode.NORMAL, durationMs = 60_000)
        clock.advanceBy(20_000)
        engine.evaluate()
        assertTrue(engine.pause())

        clock.advanceBy(500_000) // Pausenzeit zaehlt nicht.
        assertEquals(40_000, engine.state.value.remainingMs)

        assertTrue(engine.resume())
        clock.advanceBy(10_000)
        engine.evaluate()
        assertEquals(30_000, engine.state.value.remainingMs)
    }

    @Test
    fun `nach cancel loest kein alter callback mehr etwas aus`() {
        // Abnahme Schritt 7: nach cancel keine Haptik, Ansage oder
        // Lautstaerkeaenderung durch veraltete Trigger.
        val result = engine.startDropSync(requestedDurationMs = 30_000, markerPositionMs = 120_000)
        val session = (result as AppResult.Success).value
        assertTrue(engine.markRunning(session.id))
        assertTrue(engine.cancel(CancelReason.USER))
        assertEquals(listOf(session.id), cues.stops)

        engine.onThresholdReached(session.id, 10_000)
        engine.onThresholdReached(session.id, 0)
        engine.evaluate()

        assertTrue(cues.spoken.isEmpty())
        assertTrue(cues.haptics.isEmpty())
        assertTrue(cues.tones.isEmpty())
        assertEquals(TimerStatus.CANCELLED, engine.state.value.status)
    }

    @Test
    fun `dropsync trigger mit fremder session id wird ignoriert`() {
        val result = engine.startDropSync(requestedDurationMs = 30_000, markerPositionMs = 120_000)
        val session = (result as AppResult.Success).value
        engine.markRunning(session.id)

        engine.onThresholdReached("andere-session", 10_000)

        assertTrue(cues.haptics.isEmpty())
    }

    @Test
    fun `dropsync validiert die dauergrenzen aus 5 2`() {
        // 5_000 <= requestedDurationMs <= marker.positionMs
        assertTrue(
            engine.startDropSync(4_999, markerPositionMs = 120_000) is AppResult.Failure,
        )
        assertTrue(
            engine.startDropSync(130_000, markerPositionMs = 120_000) is AppResult.Failure,
        )
        assertTrue(
            engine.startDropSync(30_000, markerPositionMs = 120_000) is AppResult.Success,
        )
    }

    @Test
    fun `dropsync marker trigger schliesst genau einmal ab`() {
        val result = engine.startDropSync(requestedDurationMs = 30_000, markerPositionMs = 120_000)
        val session = (result as AppResult.Success).value
        engine.markRunning(session.id)

        engine.onThresholdReached(session.id, 0)
        engine.onThresholdReached(session.id, 0)

        assertEquals(TimerStatus.COMPLETED, engine.state.value.status)
        assertEquals(1, cues.tones.size)
        // DropSync-Abschluss ist Haptik + Ton, keine Sprache.
        assertTrue(cues.spoken.isEmpty())
    }

    @Test
    fun `endzustand verlangt reset vor neustart`() {
        engine.start(TimerMode.NORMAL, durationMs = 10_000)
        clock.advanceBy(10_000)
        engine.evaluate()
        assertEquals(TimerStatus.COMPLETED, engine.state.value.status)

        val restart = engine.start(TimerMode.NORMAL, durationMs = 10_000)
        assertTrue(restart is AppResult.Failure)

        assertTrue(engine.reset())
        assertEquals(TimerStatus.IDLE, engine.state.value.status)
        assertTrue(engine.start(TimerMode.NORMAL, durationMs = 10_000) is AppResult.Success)
    }

    @Test
    fun `reset ist aus aktiven zustaenden verboten`() {
        engine.start(TimerMode.NORMAL, durationMs = 10_000)
        assertFalse(engine.reset())
        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
    }

    @Test
    fun `get-ready-vorlauf startet in preparing und zaehlt herunter`() {
        engine.start(TimerMode.REST, durationMs = 60_000, prepMs = 3_000)
        assertEquals(TimerStatus.PREPARING, engine.state.value.status)
        assertEquals(3_000, engine.state.value.remainingMs)

        clock.advanceBy(1_000)
        engine.evaluate()
        assertEquals(TimerStatus.PREPARING, engine.state.value.status)
        assertEquals(2_000, engine.state.value.remainingMs)
    }

    @Test
    fun `get-ready-vorlauf wechselt nach ablauf auf running mit voller dauer`() {
        engine.start(TimerMode.REST, durationMs = 60_000, prepMs = 3_000)
        clock.advanceBy(3_000)
        engine.evaluate()

        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
        assertEquals(60_000, engine.state.value.remainingMs)
        // 3-2-1 gab Haptik + Ton (kein Sprechen der Vorbereitung).
        assertTrue(cues.haptics.isNotEmpty())
        assertTrue(cues.tones.isNotEmpty())
        assertTrue(cues.spoken.isEmpty())
    }

    @Test
    fun `laufcues feuern trotz gleicher vorlauf-grenzwerte`() {
        // 3/2/1 s existieren in Vorlauf und Lauf: nach dem Uebergang
        // duerfen die Laufcues bei 3/2/1 s erneut feuern (Merker geleert).
        engine.start(TimerMode.REST, durationMs = 8_000, prepMs = 3_000)
        clock.advanceBy(3_000)
        engine.evaluate() // Vorlauf -> RUNNING (Rest 8 s)
        val prepHaptics = cues.haptics.size

        clock.advanceBy(6_000) // Rest 2 s -> Laufgrenzwert 2 s
        engine.evaluate()
        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
        assertTrue(cues.haptics.size > prepHaptics)
    }
}
