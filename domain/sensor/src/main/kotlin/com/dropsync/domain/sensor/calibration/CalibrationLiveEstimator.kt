package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.SensorSample
import kotlin.math.max
import kotlin.math.sqrt

/**
 * RC-8: Live-Schaetzung fuer die Wizard-Anzeige ("Reps erkannt: n").
 *
 * Bewusst ausgelagert aus [CalibrationController]: das ist Fuehrungs-Logik
 * fuer die UI, keine Kalibrier-Entscheidung. Sie laeuft auf denselben
 * Puffern und derselben Zaehlfunktion wie die Stufen-Auswertung, aber mit
 * den zu diesem Zeitpunkt besten Parametern — endgueltig entscheidet erst
 * der Abschluss der Stufe (und im Review die Korrektur des Nutzers).
 *
 * Alle Funktionen sind rein (Puffer + Parameter rein, Zahl raus) und damit
 * unabhaengig vom Controller-Zustand testbar.
 */
internal object CalibrationLiveEstimator {
    /**
     * Zusammenhaengende Bewegungs-Bursts im Puffer (Stufe A). Ein Burst ist
     * eine Folge von Samples ueber der Bewegungs-Schwelle mit mindestens
     * [CalibrationThresholds.MIN_ACTIVITY_SAMPLES] Samples — dieselbe
     * Untergrenze, die auch die Achsenanalyse fuer ein gueltiges
     * Bewegungsfenster verlangt.
     */
    fun activityBursts(
        buf: List<SensorSample>,
        bias: DoubleArray,
        sigmaGyro: Double,
    ): Int {
        val threshold = max(CalibrationThresholds.MIN_ACTIVITY_GYRO_DEG_PER_SEC, 4.0 * sigmaGyro)
        var bursts = 0
        var run = 0
        for (s in buf) {
            val dx = s.gx - bias[0]
            val dy = s.gy - bias[1]
            val dz = s.gz - bias[2]
            val mag = sqrt(dx * dx + dy * dy + dz * dz)
            if (mag > threshold) {
                run++
            } else {
                if (run >= CalibrationThresholds.MIN_ACTIVITY_SAMPLES) bursts++
                run = 0
            }
        }
        if (run >= CalibrationThresholds.MIN_ACTIVITY_SAMPLES) bursts++
        return bursts
    }

    /**
     * Vorlaeufige Rep-Zahl fuer die Stufen B/C. Nutzt die GP-Projektion (die
     * einzige Live-Signalform, Umbauplan Phase 1.3) und die Zaehlfunktion der
     * Auswertung — mit der gelernten Config in Stufe C, mit einem robusten
     * Startwert in Stufe B.
     *
     * Der Startwert (p10 als Baseline, Schwelle bei 35 % der Spanne bis p99)
     * ist bewusst NICHT der Rand-Mittelwert des Sweeps: ein Satz, der sofort
     * mit der ersten Wiederholung beginnt, hat am Pufferrand keine Ruhe —
     * p10/p99 bleiben davon weitgehend unberuehrt.
     */
    fun provisionalRepCount(
        buf: List<SensorSample>,
        achse: DoubleArray,
        bias: DoubleArray,
        sampleRateHz: Double,
        sweepCfg: CalibrationController.SweepCfg?,
        baselineChosen: Double?,
        useLearnedCfg: Boolean,
        provisionalRefractoryS: Double,
    ): Int {
        if (buf.isEmpty()) return 0
        val gp = gyroProjection(buf, achse, bias)
        if (useLearnedCfg && sweepCfg != null && baselineChosen != null) {
            return zaehleEdge(
                signal = gp,
                hz = sampleRateHz,
                theta = sweepCfg.theta,
                refractoryS = sweepCfg.refractoryS,
                baseline = baselineChosen,
                prominenz = sweepCfg.prominenz,
            ).size
        }
        val values = gp.toList()
        val robustBaseline = percentile(values, 10.0)
        val span = percentile(values, 99.0) - robustBaseline
        if (span <= 0.0) return 0
        return zaehleEdge(
            signal = gp,
            hz = sampleRateHz,
            theta = robustBaseline + 0.35 * span,
            refractoryS = provisionalRefractoryS,
            baseline = robustBaseline,
        ).size
    }

    /** GP-Projektion der Gyro-Samples auf die kalibrierte Achse. */
    fun gyroProjection(
        buf: List<SensorSample>,
        achse: DoubleArray,
        bias: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(buf.size)
        for (i in buf.indices) {
            val s = buf[i]
            out[i] =
                (s.gx - bias[0]) * achse[0] +
                (s.gy - bias[1]) * achse[1] +
                (s.gz - bias[2]) * achse[2]
        }
        return out
    }
}
