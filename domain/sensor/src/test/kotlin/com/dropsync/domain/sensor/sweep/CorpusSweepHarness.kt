package com.dropsync.domain.sensor.sweep

import com.dropsync.domain.sensor.ExerciseEngineConfig
import com.dropsync.domain.sensor.ExerciseEnginePipeline
import com.dropsync.domain.sensor.RepEvent
import com.dropsync.domain.sensor.calibration.CalibrationController
import com.dropsync.domain.sensor.calibration.median
import com.dropsync.domain.sensor.calibration.std
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline-Replay des Golden Corpus gegen variierte Pipeline-Parameter
 * (Umbauplan 2026-09-04 Phase 6.2 — "das Sweep-Werkzeug").
 *
 * Liest die JSONL-Sample-Fenster aus Phase 0 (siehe [CorpusLoader]), baut je
 * Parametersatz eine frische [ExerciseEnginePipeline], spielt die Samples
 * durch und vergleicht den Count mit `manifest.known_active_reps`.
 *
 * Kein Test im Sinne von "gruen/rot", sondern ein Messwerkzeug: das Ergebnis
 * ist eine CSV mit (Parametersatz, Szenario, Satz, erwartet, gezaehlt,
 * delta, Abstands-Metriken). Als `@Test`-Einstieg markiert (in
 * [CorpusSweepHarnessTest]), damit es ohne eigene Toolchain laeuft; per
 * Property-Flag nur auf Anforderung — der Lauf gegen den ECHTEN Corpus
 * gehoert zu Phase 1 (Geraetezeit), nicht in die CI.
 *
 * Determinismus (Plan 6.4): das Replay nimmt die `ProcessedFrame`-Timestamps
 * aus der JSONL und spielt echte Luecken mit — nicht geglaettet. Die Pipeline
 * reagiert auf Luecken selbst (`LARGE_GAP_MS`); geglaettete Timestamps wuerden
 * eine Pipeline messen, die live nie gelaufen ist.
 *
 * Accel-Konstanten (Plan-Tabelle): `ACCEL_PEAK_FRACTION`/`ACCEL_NOISE_MARGIN`/
 * `ACCEL_PEAK_WINDOW_S` wirken zur KALIBRIERUNGSZEIT — der Corpus traegt nur
 * ihr Ergebnis (`accelTheta`). Der Sweep re-deriviert die Schwelle deshalb in
 * zwei Stufen: Gyro-Pass (Voting aus) liefert Rep-Positionen als Marks, dann
 * leitet [deriveAccelThreshold] mit den gesweepten Konstanten die Schwelle
 * ab (dieselbe Mathematik wie `CalibrationController.calibrateAccelThreshold`,
 * Ruhesigma aus der Stille zwischen den Reps statt aus dem Kalibrierungs-
 * Rest-Fenster). Gelingt keine Trennung, bleibt das Voting aus — exakt das
 * live-Verhalten ("lieber kein zweiter Kanal als ein falsch parametrisierter").
 */
class CorpusSweepHarness(
    private val corpusDir: Path,
    private val outputCsv: Path,
) {
    /**
     * Ein Parametersatz. `null` heisst "Profil-/Code-Default" — nur die
     * gesetzten Werte weichen vom aufgenommenen Zustand ab, damit die
     * CSV-Zeile "baseline" wirklich das Live-Verhalten repliziert.
     */
    data class ParameterSet(
        val name: String,
        val templateThreshold: Double? = null,
        val minQualityScore: Double? = null,
        val dtwBand: Int? = null,
        val accelVoteWindowMs: Long? = null,
        val accelEnabled: Boolean = false,
        val accelPeakFraction: Double? = null,
        val accelNoiseMargin: Double? = null,
        val accelPeakWindowS: Double? = null,
    ) {
        /** CSV-Spalte: name=value-Paare, "|"-getrennt. */
        fun describe(): String =
            buildList {
                templateThreshold?.let { add("templateThreshold=$it") }
                minQualityScore?.let { add("minQualityScore=$it") }
                dtwBand?.let { add("dtwBand=$it") }
                accelVoteWindowMs?.let { add("accelVoteWindowMs=$it") }
                if (accelEnabled) add("accelEnabled=true")
                accelPeakFraction?.let { add("accelPeakFraction=$it") }
                accelNoiseMargin?.let { add("accelNoiseMargin=$it") }
                accelPeakWindowS?.let { add("accelPeakWindowS=$it") }
            }.joinToString("|").ifEmpty { "baseline" }
    }

    /** Eine Ergebniszeile (auch als CSV-Zeile exportierbar). */
    data class SweepRow(
        val parameterSet: String,
        val scenario: String,
        val window: String,
        val setIndex: Int,
        val expected: Int?,
        val counted: Int?,
        val delta: Int?,
        val accelTheta: Double,
        val minQualityMargin: Double?,
        val minCorrelationMargin: Double?,
        val note: String,
    ) {
        fun toCsv(): String =
            listOf(
                parameterSet,
                scenario,
                window,
                setIndex.toString(),
                expected?.toString() ?: "",
                counted?.toString() ?: "",
                delta?.toString() ?: "",
                "%.6f".format(accelTheta),
                minQualityMargin?.let { "%.6f".format(it) } ?: "",
                minCorrelationMargin?.let { "%.6f".format(it) } ?: "",
                note,
            ).joinToString(";")
    }

    data class SweepReport(
        val rows: List<SweepRow>,
        val loaderIssues: List<String>,
        val csvPath: Path,
    )

    /** Ergebnis eines einzelnen Replays (auch Determinismus-Beweis). */
    data class ReplayOutcome(
        val countedReps: Int,
        val repEvents: List<RepEvent>,
        /** Wie oft der Lueckenpfad (LARGE_GAP_MS) feuerte — Gap-Diagnose. */
        val largeGapCount: Int,
    )

    /**
     * Faehrt den Sweep gegen [parameterSets] und schreibt die CSV.
     * Fenster ohne Profil (Phase 0.7) werden pro Parametersatz als Zeile mit
     * leerem `counted` und Hinweis protokolliert — sie sind kein Messergebnis.
     */
    fun sweep(parameterSets: List<ParameterSet>): SweepReport {
        val (sessions, issues) = CorpusLoader(corpusDir).load()
        val rows = mutableListOf<SweepRow>()
        for (session in sessions) {
            for (window in session.windows) {
                for (params in parameterSets) {
                    rows += evaluate(session, window, params)
                }
            }
        }
        Files.createDirectories(outputCsv.toAbsolutePath().parent)
        BufferedWriter(Files.newBufferedWriter(outputCsv)).use { w ->
            w.write(CSV_HEADER)
            w.write("\n")
            for (row in rows) {
                w.write(row.toCsv())
                w.write("\n")
            }
        }
        return SweepReport(rows = rows, loaderIssues = issues, csvPath = outputCsv)
    }

    private fun evaluate(
        session: CorpusSession,
        window: CorpusWindow,
        params: ParameterSet,
    ): SweepRow {
        val expected = session.manifest.expectedReps(window.setIndex)
        if (!window.hasProfile) {
            return SweepRow(
                parameterSet = params.describe(),
                scenario = session.manifest.scenario,
                window = session.name,
                setIndex = window.setIndex,
                expected = expected,
                counted = null,
                delta = null,
                accelTheta = 0.0,
                minQualityMargin = null,
                minCorrelationMargin = null,
                note = "kein Profil im set_window (Phase 0.7) — fuer Sweeps unbrauchbar",
            )
        }

        // Stufe 1: Gyro-Pass mit den Nicht-Accel-Parametern. Die Marks der
        // Accel-Derivation sind Rep-Positionen: wie die kalibrierten
        // RepMark.sampleIndex-Werte (Maximum der Exkursion, NICHT das
        // Rep-Ende) werden sie aus den Event-Zeitpunkten minus der halben
        // Rep-Dauer geschaetzt — die Bewegungsumkehr liegt in der Rep-Mitte,
        // nicht am Rep-Ende, wo das Event feuert.
        val gyroConfig = configFor(window, params, accelThreshold = 0.0, accelEnabled = false)
        val gyroOutcome = replay(window, gyroConfig)

        var derivedTheta = 0.0
        if (params.accelEnabled) {
            derivedTheta =
                deriveAccelThreshold(
                    samples = window.samples,
                    markTimestampsMs =
                        gyroOutcome.repEvents.map { event ->
                            event.timestampMs - (event.durationMs ?: 0L) / 2L
                        },
                    sampleRateHz = window.rateHz,
                    peakFraction = params.accelPeakFraction ?: CalibrationController.ACCEL_PEAK_FRACTION,
                    noiseMargin = params.accelNoiseMargin ?: CalibrationController.ACCEL_NOISE_MARGIN,
                    peakWindowS = params.accelPeakWindowS ?: CalibrationController.ACCEL_PEAK_WINDOW_S,
                )
        }

        val note =
            if (params.accelEnabled && derivedTheta <= 0.0) {
                "Accel-Derivation ohne Trennschaerfe -> Voting bleibt aus (wie live)"
            } else {
                ""
            }

        // Stufe 2: eigentlicher Lauf. Eine abgeleitete Schwelle von 0 mit
        // aktivem Voting waere unzulaessig (ExerciseEngineConfig-require) —
        // genau der Fall ist oben bereits als "Voting bleibt aus"
        // dokumentiert und faellt hier auf den Gyro-Lauf zurueck.
        val finalEnabled = params.accelEnabled && derivedTheta > 0.0
        val config = configFor(window, params, accelThreshold = derivedTheta, accelEnabled = finalEnabled)
        val outcome = replay(window, config)
        val delta = expected?.let { outcome.countedReps - it }

        // Abstands-Metriken (Plan 6.2): ein Parametersatz, der knapp passt,
        // ist schlechter als einer, der komfortabel passt. Minimum ueber alle
        // gezaehlten Reps; ohne Reps bleibt die Spalte leer (keine Aussage).
        val minQualityMargin =
            outcome.repEvents
                .mapNotNull { it.qualityScore }
                .minOfOrNull { it - config.minQualityScore }
        val minCorrelationMargin =
            outcome.repEvents
                .mapNotNull { it.correlation }
                .minOfOrNull { it - config.templateThreshold }

        return SweepRow(
            parameterSet = params.describe(),
            scenario = session.manifest.scenario,
            window = session.name,
            setIndex = window.setIndex,
            expected = expected,
            counted = outcome.countedReps,
            delta = delta,
            accelTheta = derivedTheta,
            minQualityMargin = minQualityMargin,
            minCorrelationMargin = minCorrelationMargin,
            note = note,
        )
    }

    /** Baut die Pipeline-Konfiguration aus Fensterprofil + Parameter-Set. */
    private fun configFor(
        window: CorpusWindow,
        params: ParameterSet,
        accelThreshold: Double,
        accelEnabled: Boolean,
    ): ExerciseEngineConfig =
        ExerciseEngineConfig(
            sampleRateHz = window.rateHz,
            rotationAxis = window.axis!!,
            gyroBias = window.bias!!,
            templateThreshold = params.templateThreshold ?: DEFAULT_TEMPLATE_THRESHOLD,
            minQualityScore = params.minQualityScore ?: DEFAULT_MIN_QUALITY_SCORE,
            detectionThreshold = window.theta,
            expectedProminence = window.prominence,
            expectedDurationMs = window.durationMs,
            accelEnabled = accelEnabled,
            accelThreshold = accelThreshold,
            accelVoteWindowMs = params.accelVoteWindowMs ?: DEFAULT_VOTE_WINDOW_MS,
            dtwBand = params.dtwBand ?: DEFAULT_DTW_BAND,
        )

    /**
     * Spielt die Samples mit ihren ECHTEN Timestamps durch eine frische
     * Pipeline. Rep-Events kommen synchron aus `processSample` (nicht aus
     * dem SharedFlow, dessen Puffer 16 Events ueberschreiten koennte).
     */
    fun replay(
        window: CorpusWindow,
        config: ExerciseEngineConfig,
    ): ReplayOutcome {
        val engine = ExerciseEnginePipeline(config)
        val events = mutableListOf<RepEvent>()
        for (s in window.samples) {
            val result = engine.processSample(s.timestampMs, s.gx, s.gy, s.gz, s.ax, s.ay, s.az)
            if (result.repResult.repCounted) {
                events +=
                    RepEvent(
                        repNumber = result.repResult.repNumber,
                        qualityScore = result.repResult.qualityScore ?: 0.0,
                        correlation = result.repResult.correlation,
                        prominence = result.repResult.prominence ?: 0.0,
                        durationSamples = result.repResult.durationSamples ?: 0,
                        durationMs = result.repResult.durationMs ?: 0,
                        timestampMs = result.frame?.timestampMs ?: s.timestampMs,
                    )
            }
        }
        return ReplayOutcome(
            countedReps = engine.repCount.value,
            repEvents = events,
            largeGapCount = engine.largeGapCount,
        )
    }

    companion object {
        const val CSV_HEADER =
            "parameter_set;scenario;window;set_index;expected;counted;delta;" +
                "accel_theta;min_quality_margin;min_correlation_margin;note"

        // Code-Defaults (NICHT die Profil-Fensterwerte): ein ParameterSet
        // ohne Override repliziert das Live-Verhalten der Codebasis.
        const val DEFAULT_TEMPLATE_THRESHOLD = 0.7
        const val DEFAULT_MIN_QUALITY_SCORE = 0.55
        const val DEFAULT_VOTE_WINDOW_MS = 800L
        const val DEFAULT_DTW_BAND = 8

        /**
         * Sweep-Raster aus der Plan-Tabelle (Phase 6.2), je Parameter
         * einzeln gegen die Baseline variiert. Das kartesische Produkt
         * aller Dimensionen waere 3^6 x Fenster — fuer den ersten Lauf
         * gegen den echten Corpus ist je-Parameter-variationen die
         * richtige Groessenordnung; wer das Produkt will, baut die
         * ParameterSets selbst.
         */
        fun defaultParameterSets(): List<ParameterSet> =
            listOf(
                ParameterSet("baseline"),
                ParameterSet("templateThreshold=0.55", templateThreshold = 0.55),
                ParameterSet("templateThreshold=0.85", templateThreshold = 0.85),
                ParameterSet("minQualityScore=0.45", minQualityScore = 0.45),
                ParameterSet("minQualityScore=0.70", minQualityScore = 0.70),
                ParameterSet("dtwBand=4", dtwBand = 4),
                ParameterSet("dtwBand=16", dtwBand = 16),
                ParameterSet("accelVoteWindowMs=400", accelVoteWindowMs = 400),
                ParameterSet("accelVoteWindowMs=1200", accelVoteWindowMs = 1200),
                ParameterSet(
                    "accel fraction=0.20",
                    accelEnabled = true,
                    accelPeakFraction = 0.20,
                ),
                ParameterSet(
                    "accel fraction=0.60",
                    accelEnabled = true,
                    accelPeakFraction = 0.60,
                ),
                ParameterSet(
                    "accel noiseMargin=2.0",
                    accelEnabled = true,
                    accelNoiseMargin = 2.0,
                ),
                ParameterSet(
                    "accel noiseMargin=8.0",
                    accelEnabled = true,
                    accelNoiseMargin = 8.0,
                ),
                ParameterSet(
                    "accel windowS=0.2",
                    accelEnabled = true,
                    accelPeakWindowS = 0.2,
                ),
                ParameterSet(
                    "accel windowS=0.8",
                    accelEnabled = true,
                    accelPeakWindowS = 0.8,
                ),
            )
    }
}

/**
 * Re-Derivation der Accel-Schwelle aus Corpus-Samples (Plan 6.2, analog zu
 * `CalibrationController.calibrateAccelThreshold`, dessen Zustand —
 * KNOWN_SET-Marks, Rest-Fenster — im Corpus nur als Samples existiert).
 *
 * Marks = Rep-Positionen des Gyro-Passes; Ruhesigma = Streuung der
 * EMA-gefilterten Abweichung in der Stille ZWISCHEN den Rep-Fenstern.
 */
internal fun deriveAccelThreshold(
    samples: List<CorpusSample>,
    markTimestampsMs: List<Long>,
    sampleRateHz: Double,
    peakFraction: Double,
    noiseMargin: Double,
    peakWindowS: Double,
): Double {
    if (markTimestampsMs.size < CalibrationController.MIN_ACCEL_CALIBRATION_PEAKS) return 0.0
    if (samples.isEmpty()) return 0.0

    // Dieselbe Glaettung wie live: EMA der |Magnitude - 1 g|.
    val raw = DoubleArray(samples.size) { abs(samples[it].accelMagnitude - 1.0) }
    val deviation = ema(raw)

    // Mark-Timestamps -> Sample-Indizes (Samples sind chronologisch).
    val markIndices = markTimestampsMs.map { ts -> nearestIndex(samples, ts) }
    val half = max(1, (sampleRateHz * peakWindowS).toInt())

    val peaks =
        markIndices.mapNotNull { idx ->
            val start = max(0, idx - half)
            val end = min(deviation.size, idx + half + 1)
            if (end > start) deviation.copyOfRange(start, end).max() else null
        }
    if (peaks.size < CalibrationController.MIN_ACCEL_CALIBRATION_PEAKS) return 0.0

    // Ruhesigma: live kommt es aus dem echten Rest-Fenster der Kalibrierung
    // (stilles Geraet). Offline ist das Analogon die stillste Sekunde des
    // Fensters — Minimum der Std ueber gleitende 1-s-Fenster. "Alles
    // ausserhalb der Mark-Fenster" waere falsch: die EMA-Auslaufenden nach
    // den Pulsen wuerden das Sigma aufblaehen und jede Schwelle unter die
    // Rauschdecke druecken (gemessen: 0.0 statt ~0.05).
    val windowSize = max(2, sampleRateHz.toInt())
    val noiseSigma =
        if (deviation.size <= windowSize) {
            std(deviation.toList())
        } else {
            (0..deviation.size - windowSize)
                .minOf { from -> std(deviation.copyOfRange(from, from + windowSize).toList()) }
        }

    val candidate = peakFraction * median(peaks)
    val noiseCeiling =
        noiseMargin * max(noiseSigma, CalibrationController.MIN_ACCEL_NOISE_SIGMA)
    if (candidate <= noiseCeiling) return 0.0
    return candidate
}

private fun ema(values: DoubleArray): DoubleArray {
    if (values.isEmpty()) return values
    val out = DoubleArray(values.size)
    out[0] = values[0]
    for (i in 1 until values.size) {
        out[i] =
            CalibrationController.ACCEL_EMA_ALPHA * values[i] +
            (1 - CalibrationController.ACCEL_EMA_ALPHA) * out[i - 1]
    }
    return out
}

private fun nearestIndex(
    samples: List<CorpusSample>,
    timestampMs: Long,
): Int {
    var best = 0
    var bestDistance = abs(samples[0].timestampMs - timestampMs)
    for (i in 1 until samples.size) {
        val distance = abs(samples[i].timestampMs - timestampMs)
        if (distance < bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}
