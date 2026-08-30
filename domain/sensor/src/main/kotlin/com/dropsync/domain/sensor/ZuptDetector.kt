package com.dropsync.domain.sensor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ZUPT-Segmentierung (Zero-velocity UPdaTe) — P2-Fix #24.
 *
 * Hintergrund: die Pipeline kennt bisher nur EINEN Ruhezustand, naemlich den
 * einmalig bei der Kalibrierung gemessenen. Der Gyro-Bias des MPU6886 driftet
 * aber mit der Temperatur (typisch 2-5 deg/s, ueber eine Trainingseinheit
 * durchaus wandernd). Ein falscher Bias verschiebt die projizierte Spur
 * [ProcessedFrame.rawGp] als konstanten Offset — und genau gegen diesen Offset
 * arbeitet der Schwellwert-Detektor: die kalibrierte Schwelle theta sitzt
 * ploetzlich zu hoch oder zu tief, ohne dass sich an der Bewegung etwas
 * geaendert haette.
 *
 * ZUPT loest das ohne Nutzerinteraktion: zwischen zwei Wiederholungen (und in
 * der Satzpause) steht der Sensor faktisch still. In diesen Fenstern MUSS die
 * Drehrate null sein — was gemessen wird, ist per Definition Bias. Das ist der
 * Standardansatz aus der Inertialnavigation (Foxlin 2005, "Pedestrian tracking
 * with shoe-mounted inertial sensors"), dort gegen Positionsdrift eingesetzt,
 * hier gegen Schwellwert-Drift.
 *
 * Zwei Verwendungen:
 * 1. [biasEstimate] liefert den im Ruhefenster gemessenen Bias; die Pipeline
 *    zieht ihn nach.
 * 2. [isStationary] segmentiert den Satz. Waehrend echter Ruhe kann kein
 *    Peak laufen — ein Pending-Rep, der in eine Ruhephase hineinragt, ist ein
 *    Artefakt.
 *
 * Bewusst KEINE Korrektur der Achse: die Rotationsachse ist im Ruhezustand
 * nicht beobachtbar (es gibt keine Rotation). Dafuer ist der
 * [OrientationTracker] zustaendig.
 *
 * Rein JVM, zustandsbehaftet, nicht threadsicher.
 */
class ZuptDetector(
    /** Gyro-Magnitude, unter der ein Sample als ruhend gilt (deg/s). */
    private val gyroThreshold: Double = DEFAULT_GYRO_THRESHOLD,
    /** Abweichung der Accel-Magnitude von 1 g, unter der Ruhe gilt (g). */
    private val accelThreshold: Double = DEFAULT_ACCEL_THRESHOLD,
    /** Mindestdauer, ab der ein ruhiges Fenster als ZUPT zaehlt. */
    private val minStationaryMs: Long = DEFAULT_MIN_STATIONARY_MS,
) {
    /** Aktueller Segmentzustand des Stroms. */
    enum class Segment {
        /** Noch keine Entscheidung (zu wenige Samples). */
        UNKNOWN,

        /** Sensor bewegt sich. */
        MOTION,

        /** Sensor steht still (ZUPT-Fenster bestaetigt). */
        STATIONARY,
    }

    /** Ergebnis eines Samples. */
    data class Result(
        val segment: Segment,
        /**
         * True genau in dem Sample, in dem ein Ruhefenster erstmals als
         * bestaetigt gilt. Der passende Moment fuer eine Bias-Uebernahme.
         */
        val zuptConfirmed: Boolean,
        /**
         * True genau in dem Sample, in dem die Bewegung nach Ruhe wieder
         * einsetzt (Segmentgrenze).
         */
        val motionStarted: Boolean,
    )

    var segment: Segment = Segment.UNKNOWN
        private set

    /** Anzahl bestaetigter Ruhefenster im laufenden Strom (Diagnose). */
    var stationaryWindows = 0
        private set

    private var quietSinceMs: Long? = null
    private var confirmed = false

    // Laufende Summen der Drehraten im aktuellen Ruhefenster: der Mittelwert
    // ueber ein Ruhefenster IST der Bias.
    private var sumGx = 0.0
    private var sumGy = 0.0
    private var sumGz = 0.0
    private var quietSamples = 0

    /** True, solange ein bestaetigtes Ruhefenster laeuft. */
    val isStationary: Boolean
        get() = segment == Segment.STATIONARY

    /**
     * Im aktuellen (bestaetigten) Ruhefenster gemessener Gyro-Bias, oder null
     * wenn kein bestaetigtes Fenster mit genug Samples vorliegt.
     *
     * Die Werte sind ROH (nicht bias-korrigiert): der Aufrufer speist sie
     * direkt als neuen Bias ein.
     */
    val biasEstimate: DoubleArray?
        get() {
            if (!confirmed || quietSamples < MIN_BIAS_SAMPLES) return null
            return doubleArrayOf(
                sumGx / quietSamples,
                sumGy / quietSamples,
                sumGz / quietSamples,
            )
        }

    /**
     * Nimmt ein ROHES Sample auf (Gyro in deg/s, Accel in g).
     *
     * Wichtig: hier gehen die UNKORRIGIERTEN Gyro-Werte hinein. Mit bereits
     * bias-korrigierten Werten wuerde der Detektor immer denselben Bias
     * bestaetigen, den er schon anwendet — die Drift bliebe unsichtbar.
     */
    fun onSample(
        timestampMs: Long,
        gx: Double,
        gy: Double,
        gz: Double,
        ax: Double,
        ay: Double,
        az: Double,
    ): Result {
        val gyroMag = sqrt(gx * gx + gy * gy + gz * gz)
        val accelDev = abs(sqrt(ax * ax + ay * ay + az * az) - 1.0)
        val quiet = gyroMag < gyroThreshold && accelDev < accelThreshold

        if (!quiet) {
            val wasStationary = segment == Segment.STATIONARY
            resetWindow()
            segment = Segment.MOTION
            return Result(segment, zuptConfirmed = false, motionStarted = wasStationary)
        }

        val since = quietSinceMs
        if (since == null) {
            quietSinceMs = timestampMs
            accumulate(gx, gy, gz)
            // Noch nicht lang genug still: der Zustand bleibt, was er war.
            return Result(segment, zuptConfirmed = false, motionStarted = false)
        }

        accumulate(gx, gy, gz)
        if (!confirmed && timestampMs - since >= minStationaryMs) {
            confirmed = true
            segment = Segment.STATIONARY
            stationaryWindows++
            return Result(segment, zuptConfirmed = true, motionStarted = false)
        }
        return Result(segment, zuptConfirmed = false, motionStarted = false)
    }

    /** Verwirft den Zustand (neues Set, Reconnect, Uebungswechsel). */
    fun reset() {
        segment = Segment.UNKNOWN
        stationaryWindows = 0
        resetWindow()
    }

    private fun resetWindow() {
        quietSinceMs = null
        confirmed = false
        sumGx = 0.0
        sumGy = 0.0
        sumGz = 0.0
        quietSamples = 0
    }

    private fun accumulate(
        gx: Double,
        gy: Double,
        gz: Double,
    ) {
        sumGx += gx
        sumGy += gy
        sumGz += gz
        quietSamples++
    }

    companion object {
        /**
         * Ruhe-Gate der Kalibrierung nutzt 15 deg/s als Mittelwert-Grenze
         * (CalibrationThresholds.REST_GYRO_MEAN_MAX_DEG_PER_SEC). Fuer die
         * Erkennung EINZELNER ruhiger Samples ist das zu grosszuegig: hier
         * darf nichts als Ruhe durchgehen, in dem noch echte Bewegung steckt,
         * sonst wandert Bewegung in den Bias.
         */
        const val DEFAULT_GYRO_THRESHOLD = 8.0

        /** Passend zum Accel-Rauschen des MPU6886 im Ruhezustand. */
        const val DEFAULT_ACCEL_THRESHOLD = 0.06

        /**
         * 400 ms sind kuerzer als jede Satzpause, aber laenger als der
         * Umkehrpunkt einer Wiederholung. Der Umkehrpunkt hat zwar eine
         * Drehrate nahe null, die Accel-Abweichung ist dort aber wegen der
         * Richtungsumkehr deutlich groesser als das Ruherauschen — und er
         * dauert nie 400 ms.
         */
        const val DEFAULT_MIN_STATIONARY_MS = 400L

        /** Unter so wenigen Samples ist der Bias-Mittelwert nicht belastbar. */
        const val MIN_BIAS_SAMPLES = 15
    }
}
