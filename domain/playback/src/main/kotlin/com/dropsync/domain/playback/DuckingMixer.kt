package com.dropsync.domain.playback

import kotlin.math.pow

/**
 * Kombiniert die beiden Ducking-Quellen ohne Doppel-Ducking
 * (Design Phase 7): `effectiveDuckDb = min(restDuckDb, cueDuckDb)`.
 * Der staerkere Wert gewinnt, die Quellen addieren sich nie.
 *
 * Pur und JVM-testbar; die Rampe (Attack 20-50 ms, Release 150-300 ms)
 * uebernimmt [DuckingRamp], die Audio-Pipeline ruft beides im
 * Audiothread bzw. Ticker.
 */
object DuckingMixer {
    /**
     * Effektiver Ducking-Gain 0..1 aus beiden dB-Quellen.
     * [cueDuckDb] ist das klassische Cue-Ducking (als dB; 0 = aus),
     * [restDuckDb] das Pausenmusik-Ducking (default -8, 0 = aus).
     */
    fun effectiveGain(
        restDuckDb: Double,
        cueDuckDb: Double,
    ): Double {
        val effectiveDb = minOf(restDuckDb, cueDuckDb)
        return dbToLinear(effectiveDb)
    }

    /** dB -> linearer Gain; 0 dB = 1.0. */
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
}

/**
 * Expliziter Ducking-Zustand (Offtrack Phase 10, Abschnitt 13.2):
 * Rest- und TTS-/Cue-Ducking werden an einer Stelle als dB gebuendelt,
 * statt als verstreute Multiplikatoren `gain *= ...` an verschiedenen
 * unkontrollierten Orten. `minOf` = das staerkste Ducking gewinnt.
 */
data class DuckingState(
    val restDuckDb: Double = 0.0,
    val cueDuckDb: Double = 0.0,
) {
    /** Kombinierter Ducking-Gain 0..1 (nie additiv). */
    val combinedGain: Double
        get() = DuckingMixer.effectiveGain(restDuckDb, cueDuckDb)
}

/**
 * ReplayGain-/R128-Metadaten (Offtrack Phase 10, Abschnitt 13.3).
 * Bewusst nur Analyse-Information; die Anwendung erfolgt erst nach
 * expliziter Nutzer-Aktivierung, nie als v1-Standard.
 */
data class LoudnessInfo(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val truePeakDb: Float? = null,
    val integratedLufs: Float? = null,
)

/**
 * Rampe fuer Ducking-Uebergaenge (Design Phase 7.1): Attack schnell
 * (20-50 ms), Release langsamer (150-300 ms), damit Ducking nicht
 * knackst. [next] liefert den Gain nach [elapsedMs] seit dem Start
 * Richtung [target]; beide Richtungen nutzen unterschiedliche Zeiten.
 */
class DuckingRamp(
    val attackMs: Long = 40L,
    val releaseMs: Long = 200L,
) {
    /**
     * Gain 0..1 nach [elapsedMs] auf dem Weg von [from] nach [target].
     * Der Uebergang ist linear im dB-Raum (gleichmaessiges Absenken).
     */
    fun next(
        from: Double,
        target: Double,
        elapsedMs: Long,
    ): Double {
        val duration = if (target < from) attackMs else releaseMs
        if (duration <= 0 || elapsedMs <= 0) return target
        val t = (elapsedMs.toDouble() / duration).coerceIn(0.0, 1.0)
        return from + (target - from) * t
    }
}
