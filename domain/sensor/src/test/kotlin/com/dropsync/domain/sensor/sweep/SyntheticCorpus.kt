package com.dropsync.domain.sensor.sweep

import java.nio.file.Files
import java.nio.file.Path

/**
 * Synthetischer Mini-Corpus fuer die Harness-Tests (Sweep, Live-vs-Replay,
 * CI-Regressionsgate).
 *
 * Deterministisch, dasselbe Signalmodell wie
 * `ExerciseEnginePipelineIsolationTest`: 0 -> 60 -> -60 -> 0 ueber 60 Samples
 * (1200 ms bei 20 ms), davor/danach/dazwischen 60 ruhige Samples. Die
 * Baseline MUSS die eingebettete Rep-Zahl exakt zaehlen, sonst wuerde jedes
 * Werkzeug auf einem Korpus messen, das schon am Mechanismus scheitert.
 *
 * Die `set`-Zeilen tragen die Live-Seite (RC-16): `liveCountedReps` und die
 * Diagnosefelder, die `CorpusLoader` liest. `liveCounts = null` schreibt
 * einen Altkorpus ohne set-Zeilen (Format vor P2-17/P2-18).
 */
internal object SyntheticCorpus {
    /** Ein Rep-Zyklus: 0 -> 60 -> -60 -> 0, 60 Samples bei 50 Hz. */
    fun repCycle(): List<Double> =
        (1..15).map { 60.0 * it / 15.0 } +
            (1..30).map { 60.0 - 120.0 * it / 30.0 } +
            (1..15).map { -60.0 + 60.0 * it / 15.0 }

    /**
     * Halbe Rep: 0 -> 60 -> 0, nie negativ. Wird als Phasen-Ablehnung
     * klassifiziert, wenn sie ueber einen zweiten Peak finalisiert wird
     * (siehe `ExerciseEnginePipelineIsolationTest`).
     */
    fun halfRepCycle(): List<Double> = (1..15).map { 60.0 * it / 15.0 } + (1..15).map { 60.0 - 60.0 * it / 15.0 }

    /** Gyro-Strom fuer [reps] Wiederholungen auf gx bei 50 Hz (20 ms). */
    fun gyroStream(reps: Int): List<Double> =
        buildList {
            addAll(List(60) { 0.0 })
            repeat(reps) {
                addAll(repCycle())
                addAll(List(60) { 0.0 })
            }
        }

    /**
     * Accel-Strom: Ruhe (~1 g auf y) mit einer klaren Magnituden-Spitze je
     * Rep (~0.16 g Abweichung) IN den Rep-Fenstern — nur so findet die
     * Offline-Derivation eine trennscharfe Schwelle.
     */
    fun accelStream(gyro: List<Double>): List<Triple<Double, Double, Double>> =
        gyro.map { gx ->
            if (gx > 5.0) Triple(0.15, -0.98, 0.60) else Triple(0.02, -0.98, 0.11)
        }

    /** `set_window`-Zeile mit dem Profil aus dem Isolationstest. */
    fun setWindowLine(
        setIndex: Int,
        n: Int,
        tsFirst: Long,
        templateThreshold: Double = CorpusWindow.DEFAULT_TEMPLATE_THRESHOLD,
        minQualityScore: Double = CorpusWindow.DEFAULT_MIN_QUALITY_SCORE,
        dtwBand: Int = CorpusWindow.DEFAULT_DTW_BAND,
    ): String =
        """{"t":"set_window","setIndex":$setIndex,"exerciseId":7,"rateHz":50.0,"n":$n,""" +
            """"tsFirst":$tsFirst,"tsLast":${tsFirst + (n - 1) * 20L},""" +
            """"axis":[1.0,0.0,0.0],"bias":[0.0,0.0,0.0],"theta":32.5,""" +
            """"prominence":1.0,"durationMs":2000.0,"accelTheta":0.0,""" +
            """"templateThreshold":$templateThreshold,"minQualityScore":$minQualityScore,""" +
            """"dtwBand":$dtwBand,"revision":1}"""

    /** `set`-Zeile (Live-Seite) mit Diagnosefeldern wie der Recorder sie schreibt. */
    fun setLine(
        countedReps: Int,
        gaps: Int = 0,
        zuptUpdates: Int = 0,
        zuptAborted: Int = 0,
        framesProcessed: Int = 0,
        framesRejected: Int = 0,
        rateHz: Double = 50.0,
        rejections: String = "",
    ): String =
        """{"t":"set","exerciseId":7,"weightMilliKg":10000000,""" +
            """"confirmedReps":$countedReps,"confirmedRepsEdited":true,""" +
            """"liveCountedReps":$countedReps,"shadowReps":$countedReps,"delta":0,""" +
            """"rejections":{$rejections},"framesProcessed":$framesProcessed,""" +
            """"framesRejected":$framesRejected,"gaps":$gaps,"zuptUpdates":$zuptUpdates,""" +
            """"zuptAborted":$zuptAborted,"rateHz":$rateHz}"""

    /** `sample`-Zeile. */
    fun sampleLine(
        setIndex: Int,
        ts: Long,
        gx: Double,
        accel: Triple<Double, Double, Double>,
    ): String {
        val (ax, ay, az) = accel
        return """{"t":"sample","setIndex":$setIndex,"ts":$ts,"ax":$ax,"ay":$ay,"az":$az,"gx":$gx,"gy":0.0,"gz":0.0}"""
    }

    /**
     * Schreibt `mini.jsonl` + Manifest. [sets] = Rep-Zahl je Satz;
     * [liveCounts] = `liveCountedReps` je Satz (`null` -> keine set-Zeilen,
     * Altformat). Die Wahrheit im Manifest ist [sets] — der Korpus ist so
     * gebaut, dass die Baseline sie exakt zaehlt.
     */
    fun writeMiniCorpus(
        dir: Path,
        sets: List<Int> = listOf(2, 3),
        liveCounts: List<Int>? = sets,
    ) {
        val lines = mutableListOf<String>()
        lines += """{"t":"session_start","sessionId":"mini"}"""
        var globalIndex = 0L
        sets.forEachIndexed { setIndex, reps ->
            liveCounts?.let { lines += setLine(countedReps = it[setIndex]) }
            val gyro = gyroStream(reps)
            val accel = accelStream(gyro)
            lines += setWindowLine(setIndex, gyro.size, setIndex * 1_000L)
            gyro.forEachIndexed { i, gx ->
                val ts = setIndex * 1_000L + globalIndex + i * 20L
                lines += sampleLine(setIndex, ts, gx, accel[i])
            }
            globalIndex += gyro.size * 20L
        }
        lines += """{"t":"session_end","sessionId":"mini"}"""
        Files.writeString(dir.resolve("mini.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            dir.resolve("mini.jsonl.meta.json"),
            """
            {
              "recording": "mini.jsonl",
              "exercise_id": "synthetic_curl",
              "scenario": "calibrated",
              "known_active_reps": [${sets.joinToString(",")}],
              "device": "synthetic",
              "samples_recorded": true
            }
            """.trimIndent(),
        )
    }
}
