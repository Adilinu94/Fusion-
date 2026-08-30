package com.dropsync.domain.sensor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Autokorrelations-Plausibilitaetspruefung fuer die Wiederholungszahl
 * (P2-Recherche: Frequenzbereichs-Verfahren als zweite, UNABHAENGIGE
 * Schaetzung neben der Peak-Detektion).
 *
 * Gedanke: eine Serie von Wiederholungen ist ein quasi-periodisches Signal.
 * Die dominante Periode laesst sich ohne jede Schwellwert-Entscheidung aus
 * der Autokorrelation ablesen — also voellig unabhaengig davon, ob die
 * kalibrierte Schwelle theta noch passt. Set-Dauer geteilt durch Periode
 * ergibt eine Erwartung fuer die Rep-Zahl. Weicht der Peak-Zaehler stark
 * davon ab, ist das ein belastbarer Verdacht, OHNE dass eine Ground Truth
 * vorliegen muss.
 *
 * Bewusst NICHT als Zaehler eingesetzt: die Autokorrelation kann die Zahl
 * nicht exakt bestimmen (Randeffekte, Tempowechsel innerhalb des Sets), sie
 * taugt aber sehr gut als Konsistenzpruefung. Deshalb liefert
 * [RepCountPlausibility] eine Einschaetzung, keine Korrektur.
 *
 * Reine Mathematik, keine Zustaende — JVM-testbar.
 */
object RepCountPlausibility {
    /** Ergebnis der Plausibilitaetspruefung. */
    data class Result(
        /** Dominante Periode in Sekunden; null wenn keine gefunden. */
        val periodSeconds: Double?,
        /** Aus der Periode geschaetzte Rep-Zahl; null ohne Periode. */
        val estimatedReps: Int?,
        /** Normalisierte Stärke der Periodizitaet 0..1. */
        val periodicityStrength: Double,
        /** Einschaetzung des uebergebenen Zaehlerstands. */
        val verdict: Verdict,
    )

    /** Bewertung des Peak-Zaehlers gegen die Autokorrelations-Schaetzung. */
    enum class Verdict {
        /** Zaehler und Periodenschaetzung stimmen im Rahmen ueberein. */
        CONSISTENT,

        /** Abweichung von einer Wiederholung — im Rahmen der Randeffekte. */
        BORDERLINE,

        /** Deutliche Abweichung: der Zaehlerstand ist verdaechtig. */
        SUSPICIOUS,

        /** Keine verwertbare Periodizitaet (zu kurz, zu unregelmaessig). */
        INCONCLUSIVE,
    }

    /**
     * Prueft [countedReps] gegen die Autokorrelation von [signal].
     *
     * [signal] ist die projizierte, gefilterte Gyro-Spur eines Sets
     * (dieselbe Groesse, die der PeakDetector sieht), [sampleRateHz] die
     * tatsaechliche Abtastrate (siehe [SampleRateEstimator]).
     */
    fun check(
        signal: DoubleArray,
        sampleRateHz: Double,
        countedReps: Int,
    ): Result {
        if (signal.size < MIN_SAMPLES || sampleRateHz <= 0.0) {
            return Result(null, null, 0.0, Verdict.INCONCLUSIVE)
        }

        val minLag = (sampleRateHz * MIN_REP_SECONDS).toInt().coerceAtLeast(2)
        val maxLag = min((sampleRateHz * MAX_REP_SECONDS).toInt(), signal.size / 2)
        if (maxLag <= minLag) return Result(null, null, 0.0, Verdict.INCONCLUSIVE)

        val centered = centered(signal)
        val zeroLag = dot(centered, centered, 0)
        if (zeroLag <= 1e-12) return Result(null, null, 0.0, Verdict.INCONCLUSIVE)

        var bestLag = -1
        var bestValue = 0.0
        for (lag in minLag..maxLag) {
            val value = dot(centered, centered, lag) / zeroLag
            if (value > bestValue) {
                bestValue = value
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestValue < MIN_PERIODICITY) {
            return Result(null, null, max(0.0, bestValue), Verdict.INCONCLUSIVE)
        }

        val periodSeconds = bestLag / sampleRateHz
        val durationSeconds = signal.size / sampleRateHz
        // Aufrunden statt abrunden: die letzte Wiederholung ist am Set-Ende
        // meist nicht vollstaendig im Fenster.
        val estimated = Math.round(durationSeconds / periodSeconds).toInt().coerceAtLeast(1)

        val diff = abs(countedReps - estimated)
        val verdict =
            when {
                diff == 0 -> Verdict.CONSISTENT
                diff == 1 -> Verdict.BORDERLINE
                else -> Verdict.SUSPICIOUS
            }
        return Result(periodSeconds, estimated, bestValue, verdict)
    }

    /** Signal um seinen Mittelwert zentriert (Autokorrelation braucht das). */
    private fun centered(signal: DoubleArray): DoubleArray {
        var sum = 0.0
        for (v in signal) sum += v
        val mean = sum / signal.size
        val out = DoubleArray(signal.size)
        for (i in signal.indices) out[i] = signal[i] - mean
        return out
    }

    /** Unnormierte Autokorrelation bei Verschiebung [lag]. */
    private fun dot(
        a: DoubleArray,
        b: DoubleArray,
        lag: Int,
    ): Double {
        var sum = 0.0
        var i = 0
        val n = a.size - lag
        while (i < n) {
            sum += a[i] * b[i + lag]
            i++
        }
        return sum
    }

    /** Kuerzestes Set, fuer das die Pruefung sinnvoll ist (~3 s). */
    private const val MIN_SAMPLES = 150

    /** Schnellste plausible Wiederholung. */
    private const val MIN_REP_SECONDS = 0.6

    /** Langsamste plausible Wiederholung (betonte Exzentrik). */
    private const val MAX_REP_SECONDS = 8.0

    /**
     * Mindest-Autokorrelation, ab der von Periodizitaet gesprochen wird.
     * Darunter ist das Signal zu unregelmaessig fuer eine Aussage.
     */
    private const val MIN_PERIODICITY = 0.25
}
