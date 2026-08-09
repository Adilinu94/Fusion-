package com.dropsync.domain.timer

// Timerkern-Grundtypen (Bauplan Schritt 7.1-7.3).
// Reine JVM-Domaene: kein Android, keine Player-, TTS- oder Vibrator-API.

/** Die drei Timerarten; alle teilen dieselbe Zustandsmaschine (Schritt 7). */
enum class TimerMode { NORMAL, REST, DROPSYNC }

/** Die einzigen erlaubten Zustaende (Schritt 7.1). */
enum class TimerStatus { IDLE, PREPARING, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED }

/** Abbruchgruende; DEVICE_REBOOT_OR_UNKNOWN_CLOCK gemaess Schritt 7.9. */
enum class CancelReason { USER, DEVICE_REBOOT_OR_UNKNOWN_CLOCK, PLAYBACK_INTERRUPTED }

/**
 * Eine Timersitzung (Schritt 7.3): zufaellige ID, Modus, Startdauer,
 * monotone Startzeit bzw. Markerbezug. Gelieferte Cue-IDs haelt die
 * Engine, damit Pause/Recomposition/Neustart nie doppelt ansagen.
 */
data class TimerSession(
    val id: String,
    val mode: TimerMode,
    val durationMs: Long,
    /** Monotoner Start (NORMAL/REST); bei DROPSYNC null. */
    val startedElapsedRealtimeMs: Long?,
    /** Markerbezug (nur DROPSYNC): Zielposition in der Playertimeline. */
    val markerPositionMs: Long?,
    val plannedCues: List<PlannedCue>,
)

/**
 * Ein geplanter Grenzwert mit Ausgabeprofil (Bauplan 5.3, Abschnitt 2):
 * NORMAL/REST sprechen alle Grenzwerte; DROPSYNC spricht nur
 * 180/120/60/30 s, 10..1 s sind Haptik + visuelle Anzeige, 0 s ist
 * Haptik + Abschlusston.
 */
data class PlannedCue(
    val thresholdMs: Long,
    val speak: Boolean,
    val haptic: Boolean,
    val tone: Boolean,
) {
    /** Stabile Kennung `timerId:thresholdMs` (Bauplan 5.3). */
    fun cueId(sessionId: String): String = "$sessionId:$thresholdMs"
}

/** Beobachtbarer Zustand des Timerkerns fuer die UI. */
data class TimerState(
    val status: TimerStatus = TimerStatus.IDLE,
    val session: TimerSession? = null,
    val remainingMs: Long = 0,
    val cancelReason: CancelReason? = null,
)
