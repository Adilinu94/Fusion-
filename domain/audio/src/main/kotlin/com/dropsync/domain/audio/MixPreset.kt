package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Benannte Uebergangs-Presets des Crossfade (Mix-Uebergaenge-Plan
 * Phase 2). Reine Volume-Kurven fuer die beiden Spieler des
 * Dual-Player-Crossfade (ADR-0007); die DSP-Kette bleibt unberuehrt.
 *
 * Jedes Preset (ausser [SLAM]) leitet seinen Fade-Out als
 * Equal-Power-Komplement `sqrt(1 - fadeIn(t)^2)` ab, damit die
 * Invariante `fadeIn^2 + fadeOut^2 = 1` aus [CrossfadeCurves] fuer
 * jede Kurvenform gilt; [FADE] reproduziert damit exakt das bisherige
 * Sinus/Cosinus-Paar. [SLAM] ist bewusst die Ausnahme (harter Schnitt
 * statt Blende); den Klickschutz uebernimmt der Controller ueber eine
 * Mikro-Rampe pro Schritt.
 */
enum class MixPreset {
    /** Bestandsverhalten: Sinus-Viertelperiode, sanft und neutral. */
    FADE,

    /** Spaeter Einsatz, steiles Ende — der neue Titel "steigt auf". */
    RISE,

    /** Linearer Amplitudenanstieg: gleichmaessig, unauffaellig. */
    BLEND,

    /** Wellenfoermiges, atmendes Anschwellen (sinusmodulierter Fortschritt). */
    WAVE,

    /** Frueher Einsatz, langes gemeinsames Ausklingen beider Titel. */
    MELT,

    /** Harter Schnitt in der Mitte des Uebergangs (DJ-Cut). */
    SLAM,

    ;

    /** Lautstaerke des startenden Titels bei Fortschritt [progress] 0..1. */
    fun fadeInGain(progress: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        return when (this) {
            FADE -> {
                CrossfadeCurves.fadeInGain(t)
            }

            RISE -> {
                t * t
            }

            BLEND -> {
                t
            }

            WAVE -> {
                CrossfadeCurves.fadeInGain(
                    (t - WAVE_DEPTH * sin(2.0 * PI * t)).coerceIn(0.0, 1.0),
                )
            }

            MELT -> {
                sqrt(t)
            }

            SLAM -> {
                if (t < 0.5) 0.0 else 1.0
            }
        }
    }

    /** Lautstaerke des endenden Titels bei Fortschritt [progress] 0..1. */
    fun fadeOutGain(progress: Double): Double {
        if (this == SLAM) return if (progress.coerceIn(0.0, 1.0) < 0.5) 1.0 else 0.0
        val gain = fadeInGain(progress)
        return sqrt((1.0 - gain * gain).coerceAtLeast(0.0))
    }

    companion object {
        /**
         * Modulationstiefe von [WAVE]; unter 1/(2*PI), damit der
         * modulierte Fortschritt (und damit die Kurve) monoton bleibt.
         */
        private const val WAVE_DEPTH: Double = 0.10
    }
}
