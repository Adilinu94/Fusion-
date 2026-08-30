package com.dropsync.domain.sensor.calibration

/**
 * Grund, aus dem eine Kalibrierungsstufe abgelehnt wurde — P3-Fix #27.
 *
 * Vorher gab [CalibrationController.finishStage] fertig formulierte DEUTSCHE
 * Saetze zurueck (inklusive `"%.1f".format(...)`-Zahlen). Das verstoesst gegen
 * die Schichtung: `:domain:sensor` ist ein reines JVM-Modul ohne Ressourcen
 * und ohne Locale — ein Sprachwechsel der App haette diese Meldungen deutsch
 * gelassen, und die Zahlenformatierung ignorierte das Gebietsschema (im
 * Englischen "1.5", im Deutschen "1,5").
 *
 * Jetzt liefert der Controller die MESSWERTE mit dem Grund; die UI-Schicht
 * setzt daraus den lokalisierten Text zusammen.
 */
sealed interface CalibrationFailure {
    /**
     * Ruhephase war kuerzer als gefordert.
     *
     * [measuredSeconds] ist die tatsaechlich erreichte Dauer,
     * [requiredSeconds] die Mindestdauer.
     */
    data class RestTooShort(
        val measuredSeconds: Double,
        val requiredSeconds: Double,
    ) : CalibrationFailure

    /**
     * Ruhe-Gate nicht bestanden: der Arm war nicht ruhig genug.
     *
     * Die Felder sind null, wenn das jeweilige Kriterium bestanden wurde —
     * so kann die UI genau die verletzten Kriterien nennen, statt alle
     * aufzuzaehlen.
     */
    data class RestGateFailed(
        /** Mittlere Gyro-Magnitude in deg/s, falls sie das Gate riss. */
        val gyroMagMean: Double?,
        /** Accel-Rauschen (sigma) in g, falls es das Gate riss. */
        val accelSigma: Double?,
    ) : CalibrationFailure

    /** In der Einzelwiederholung war keine auswertbare Bewegung. */
    data object NoMotionWindow : CalibrationFailure
}
