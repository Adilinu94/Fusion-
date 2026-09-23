package com.dropsync.domain.sensor.calibration

/**
 * Kantenzaehler der Kalibrierung (Referenz `zaehle_edge` aus
 * calibration_controller.dart) als reine Top-Level-Funktion: Puffer +
 * Parameter rein, Rep-Markierungen ([RepMark]) raus.
 *
 * Aus [CalibrationController] herausgezogen, damit auch die Live-Schaetzung
 * ([CalibrationLiveEstimator]) und Tests dieselbe Zaehlfunktion nutzen —
 * die Kalibrierung darf nur auf dem Pfad messen, auf dem die Live-Pipeline
 * spaeter entscheidet.
 *
 * @param fallingRatio Anteil der Schwelle (ueber der Baseline), unter dem
 *   ein Rep als beendet gilt (Referenz 0.5).
 * @param prominenz Mindesthoehe der Exkursion (Peak minus vorheriges
 *   Minimum); 0.0 = aus.
 */
internal fun zaehleEdge(
    signal: DoubleArray,
    hz: Double,
    theta: Double,
    refractoryS: Double,
    baseline: Double,
    fallingRatio: Double = 0.5,
    prominenz: Double = 0.0,
    fallingDebounce: Int = 4,
): List<RepMark> {
    val reps = mutableListOf<RepMark>()
    var above = false
    var excPeak = Double.NEGATIVE_INFINITY
    var excIdx = -1
    var preMin = Double.POSITIVE_INFINITY
    var lastEnd = Int.MIN_VALUE / 2
    var unterFalling = 0
    val refrSamples = (refractoryS * hz).toInt()
    val falling = baseline + (theta - baseline) * fallingRatio
    for (i in signal.indices) {
        val v = signal[i]
        if (!above) {
            if (v < preMin) preMin = v
            // Refraktaerzeit: ein Durchgang innerhalb der Sperre zaehlt nicht.
            if (v > theta && i - lastEnd >= refrSamples) {
                above = true
                excPeak = v
                excIdx = i
                unterFalling = 0
            }
        } else {
            if (v >= excPeak) {
                excPeak = v
                excIdx = i
            }
            if (v < falling) {
                unterFalling++
            } else {
                unterFalling = 0
            }
            if (unterFalling >= fallingDebounce) {
                above = false
                unterFalling = 0
                val prominent = prominenz <= 0.0 || (excPeak - preMin) >= prominenz
                if (prominent) {
                    reps.add(RepMark(excIdx, excPeak))
                    lastEnd = i
                }
                preMin = v
            }
        }
    }
    return reps
}
