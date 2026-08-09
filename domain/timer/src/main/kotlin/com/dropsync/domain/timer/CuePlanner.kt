package com.dropsync.domain.timer

/**
 * Plant die fixe Triggerliste (Bauplan 5.3): 180, 120, 60, 30, 10..1
 * und 0 Sekunden. Nur Grenzwerte kleiner als die Startdauer werden
 * geplant; 0 (Abschluss) immer.
 */
object CuePlanner {
    private val SPOKEN_MAJORS_MS: List<Long> = listOf(180_000, 120_000, 60_000, 30_000)
    private val FINAL_COUNTDOWN_MS: List<Long> = (10 downTo 1).map { it * 1_000L }
    private val PREP_COUNTDOWN_MS: List<Long> = listOf(3_000, 2_000, 1_000)

    fun plan(
        mode: TimerMode,
        durationMs: Long,
    ): List<PlannedCue> {
        require(durationMs > 0) { "Dauer muss positiv sein: $durationMs" }
        val cues = mutableListOf<PlannedCue>()

        for (threshold in SPOKEN_MAJORS_MS) {
            if (threshold < durationMs) {
                // 180/120/60/30 s werden in allen Modi gesprochen.
                cues += PlannedCue(threshold, speak = true, haptic = false, tone = false)
            }
        }
        for (threshold in FINAL_COUNTDOWN_MS) {
            if (threshold < durationMs) {
                // DropSync: 10..1 s nur Haptik + visuelle Anzeige, damit
                // die Musik nicht zehnmal fuer Sprache geduckt wird.
                val spoken = mode != TimerMode.DROPSYNC
                cues += PlannedCue(threshold, speak = spoken, haptic = true, tone = false)
            }
        }
        // 0 s: NORMAL/REST sprechen den Abschluss, DropSync nicht;
        // Haptik und Abschlusston gibt es in allen Modi.
        cues +=
            PlannedCue(
                thresholdMs = 0,
                speak = mode != TimerMode.DROPSYNC,
                haptic = true,
                tone = true,
            )
        return cues.sortedByDescending { it.thresholdMs }
    }

    /**
     * Cues der Get-Ready-Vorbereitungsphase (Musik-Workout-Plan B9):
     * 3-2-1 als Haptik + Ton (keine Sprache, kein Ducking). Nur
     * Grenzwerte bis zur Vorlaufdauer werden geplant.
     */
    fun planPrep(prepMs: Long): List<PlannedCue> {
        if (prepMs <= 0) return emptyList()
        return PREP_COUNTDOWN_MS
            .filter { it <= prepMs }
            .map { PlannedCue(it, speak = false, haptic = true, tone = true) }
            .sortedByDescending { it.thresholdMs }
    }
}
