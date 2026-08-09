package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-Power-Kurven fuer den Crossfade (Plan Phase 4, ADR-0007).
 *
 * Beide Spieler laufen waehrend der Ueberblendung gleichzeitig; damit
 * die Summenleistung konstant bleibt, gilt fuer jeden Fortschritt t:
 * fadeIn(t)^2 + fadeOut(t)^2 = 1 (Sinus-/Cosinus-Viertelperiode).
 */
object CrossfadeCurves {
    /** Obergrenze der Ueberblenddauer (Plan Phase 4: 0-12 s, 0 = aus). */
    const val MAX_SECONDS: Int = 12

    /** Lautstaerke des startenden Titels bei Fortschritt [progress] 0..1. */
    fun fadeInGain(progress: Double): Double = sin(progress.coerceIn(0.0, 1.0) * PI / 2.0)

    /** Lautstaerke des endenden Titels bei Fortschritt [progress] 0..1. */
    fun fadeOutGain(progress: Double): Double = cos(progress.coerceIn(0.0, 1.0) * PI / 2.0)
}
