package com.dropsync.domain.sensor.sweep

import com.dropsync.domain.sensor.ExerciseEngineConfig
import com.dropsync.domain.sensor.ExerciseEnginePipeline
import com.dropsync.domain.sensor.RepEvent
import com.dropsync.domain.sensor.RepRejectionReason
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
        /** B1 (RC-18): einseitige Qualitaetstoleranzen. */
        val fatigueTolerance: Double? = null,
        val suspiciousTolerance: Double? = null,
        /** B6 (RC-21): Admission-Margin des Template-Pools. */
        val templateAdmissionMargin: Double? = null,
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
                fatigueTolerance?.let { add("fatigueTolerance=$it") }
                suspiciousTolerance?.let { add("suspiciousTolerance=$it") }
                templateAdmissionMargin?.let { add("templateAdmissionMargin=$it") }
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

    /**
     * Eine Vergleichszeile des Live-vs-Replay-Reports (RC-16). `delta` ist
     * `replay - live`; 0 heisst "kein Pfad-Unterschied sichtbar". Die
     * Diagnosespalten stehen beidseitig daneben, damit eine Abweichung
     * eingegrenzt werden kann, statt nur zu existieren.
     */
    data class LiveVsReplayRow(
        val scenario: String,
        val window: String,
        val setIndex: Int,
        val liveCountedReps: Int?,
        val replayCountedReps: Int?,
        val delta: Int?,
        val liveLargeGaps: Int?,
        val replayLargeGaps: Int?,
        val liveZuptAborted: Int?,
        val replayZuptAborted: Int,
        val liveZuptUpdates: Int?,
        val replayZuptUpdates: Int,
        val liveRejections: Map<RepRejectionReason, Int>,
        val replayRejections: Map<RepRejectionReason, Int>,
        val liveRateHz: Double?,
        val replayEstimatedRateHz: Double?,
        val note: String,
    ) {
        fun toCsv(): String =
            listOf(
                scenario,
                window,
                setIndex.toString(),
                liveCountedReps?.toString() ?: "",
                replayCountedReps?.toString() ?: "",
                delta?.toString() ?: "",
                liveLargeGaps?.toString() ?: "",
                replayLargeGaps?.toString() ?: "",
                liveZuptAborted?.toString() ?: "",
                replayZuptAborted.toString(),
                liveZuptUpdates?.toString() ?: "",
                replayZuptUpdates.toString(),
                describeRejections(liveRejections),
                describeRejections(replayRejections),
                liveRateHz?.let { "%.3f".format(it) } ?: "",
                replayEstimatedRateHz?.let { "%.3f".format(it) } ?: "",
                note,
            ).joinToString(";")
    }

    data class LiveVsReplayReport(
        val rows: List<LiveVsReplayRow>,
        val loaderIssues: List<String>,
        val csvPath: Path,
    )

    /** Ergebnis eines einzelnen Replays (auch Determinismus-Beweis). */
    data class ReplayOutcome(
        val countedReps: Int,
        val repEvents: List<RepEvent>,
        /** Wie oft der Lueckenpfad (LARGE_GAP_MS) feuerte — Gap-Diagnose. */
        val largeGapCount: Int,
        /** RC-16: restliche Diagnose des Replay-Laufs (Live-Vergleich). */
        val framesProcessed: Int = 0,
        val framesRejected: Int = 0,
        val zuptBiasUpdates: Int = 0,
        val zuptAbortedPending: Int = 0,
        val rejectionCounts: Map<RepRejectionReason, Int> = emptyMap(),
        val estimatedSampleRateHz: Double = 0.0,
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
        writeCsv(outputCsv, CSV_HEADER, rows.map { it.toCsv() })
        return SweepReport(rows = rows, loaderIssues = issues, csvPath = outputCsv)
    }

    /**
     * RC-16: Live-Zaehlung vs. Replay derselben Rohsamples.
     *
     * Spielt jedes Fenster mit der Live-Config (Profil aus dem JSONL,
     * [liveConfig]) durch eine frische Pipeline und stellt das Ergebnis der
     * `liveCountedReps`-Zahl der `set`-Zeile gegenueber. Abweichungen sind
     * eine eigene Klasse (Pfad-Differenz), kein Zaehlfehler: der
     * [LiveVsReplayRow.note] traegt die Diagnose-Unterschiede (Gaps, ZUPT,
     * Filterframes, Ablehnungen), damit die Ursache eingegrenzt werden kann.
     *
     * [configAdjust] ist der Experimentier-Hook (Gegenbeweis: eine geaenderte
     * Refraktaerzeit MUSS den Replay-Count aendern und den Live-Count nicht).
     *
     * Der Report ist ein Messwerkzeug, kein Gate — das CI-Gate auf dem
     * goldenen Korpus ist [CorpusRegressionGateTest].
     */
    fun compareLiveVsReplay(
        output: Path = defaultComparisonCsv(),
        configAdjust: (ExerciseEngineConfig) -> ExerciseEngineConfig = { it },
    ): LiveVsReplayReport {
        val (sessions, issues) = CorpusLoader(corpusDir).load()
        val rows = mutableListOf<LiveVsReplayRow>()
        for (session in sessions) {
            for (window in session.windows) {
                val live = window.live
                if (!window.hasProfile) {
                    rows +=
                        LiveVsReplayRow(
                            scenario = session.manifest.scenario,
                            window = session.name,
                            setIndex = window.setIndex,
                            liveCountedReps = live?.countedReps,
                            replayCountedReps = null,
                            delta = null,
                            liveLargeGaps = live?.largeGapCount,
                            replayLargeGaps = null,
                            liveZuptAborted = live?.zuptAbortedPending,
                            replayZuptAborted = 0,
                            liveZuptUpdates = live?.zuptBiasUpdates,
                            replayZuptUpdates = 0,
                            liveRejections = live?.rejectionCounts.orEmpty(),
                            replayRejections = emptyMap(),
                            liveRateHz = window.rateHz,
                            replayEstimatedRateHz = null,
                            note = "kein Profil im set_window — Replay nicht moeglich",
                        )
                    continue
                }
                val outcome = replay(window, configAdjust(liveConfig(window)))
                rows +=
                    LiveVsReplayRow(
                        scenario = session.manifest.scenario,
                        window = session.name,
                        setIndex = window.setIndex,
                        liveCountedReps = live?.countedReps,
                        replayCountedReps = outcome.countedReps,
                        delta = live?.countedReps?.let { outcome.countedReps - it },
                        liveLargeGaps = live?.largeGapCount,
                        replayLargeGaps = outcome.largeGapCount,
                        liveZuptAborted = live?.zuptAbortedPending,
                        replayZuptAborted = outcome.zuptAbortedPending,
                        liveZuptUpdates = live?.zuptBiasUpdates,
                        replayZuptUpdates = outcome.zuptBiasUpdates,
                        liveRejections = live?.rejectionCounts.orEmpty(),
                        replayRejections = outcome.rejectionCounts,
                        liveRateHz = window.rateHz,
                        replayEstimatedRateHz = outcome.estimatedSampleRateHz,
                        note = comparisonNote(live, outcome),
                    )
            }
        }
        writeCsv(output, LIVE_VS_REPLAY_HEADER, rows.map { it.toCsv() })
        return LiveVsReplayReport(rows = rows, loaderIssues = issues, csvPath = output)
    }

    /**
     * Die Config, mit der der Satz LIVE gezaehlt wurde, soweit das Fenster
     * sie traegt: Achse, Bias, theta, Prominenz, Dauer, gemessene Rate,
     * `accelTheta` (profilgesteuertes Voting, ADR-0017) und seit B3 (RC-20)
     * auch `templateThreshold`/`minQualityScore`/`dtwBand`. ZUPT bleibt beim
     * Default (an) — live laeuft er ebenfalls.
     *
     * Altaufnahmen ohne die B3-Felder lesen die Code-Defaults (siehe
     * [CorpusWindow]) — genau der Zustand, in dem sie damals aufgenommen
     * wurden. `internal` fuer den B3-Test (liest die Fensterwerte).
     */
    internal fun liveConfig(window: CorpusWindow): ExerciseEngineConfig =
        ExerciseEngineConfig(
            sampleRateHz = window.rateHz,
            rotationAxis = window.axis!!,
            gyroBias = window.bias!!,
            templateThreshold = window.templateThreshold,
            minQualityScore = window.minQualityScore,
            // B1 (RC-18): die Toleranzen stehen (noch) nicht im Profil/JSONL;
            // live galten die Code-Defaults, der Sweep variiert sie.
            fatigueTolerance = DEFAULT_FATIGUE_TOLERANCE,
            suspiciousTolerance = DEFAULT_SUSPICIOUS_TOLERANCE,
            detectionThreshold = window.theta,
            expectedProminence = window.prominence,
            expectedDurationMs = window.durationMs,
            accelEnabled = window.accelTheta > 0.0,
            accelThreshold = window.accelTheta,
            accelVoteWindowMs = DEFAULT_VOTE_WINDOW_MS,
            dtwBand = window.dtwBand,
        )

    /**
     * Grenzt eine Live-vs-Replay-Abweichung ein: sammelt die beobachtbaren
     * Unterschiede (Gaps, Ablehnungen, ZUPT, Filterframes). Bleibt die Liste
     * leer, ist der Mechanismus mit den vorhandenen Zaehlern nicht sichtbar —
     * das steht dann auch so im Report (Sample-Rate/Filterzustand pruefen).
     */
    private fun comparisonNote(
        live: CorpusLiveRecord?,
        replay: ReplayOutcome,
    ): String {
        if (live == null) return "kein set-Event — kein Live-Vergleich moeglich"
        val liveCount = live.countedReps ?: return "set-Event ohne liveCountedReps"
        if (liveCount == replay.countedReps) return ""
        val causes =
            buildList {
                if (live.largeGapCount != null && live.largeGapCount != replay.largeGapCount) {
                    add("Gaps live=${live.largeGapCount} replay=${replay.largeGapCount}")
                }
                if (live.rejectionCounts != replay.rejectionCounts) {
                    add(
                        "Ablehnungen live=${describeRejections(live.rejectionCounts)} " +
                            "replay=${describeRejections(replay.rejectionCounts)}",
                    )
                }
                if (live.zuptAbortedPending != null && live.zuptAbortedPending != replay.zuptAbortedPending) {
                    add("ZUPT-Abbrueche live=${live.zuptAbortedPending} replay=${replay.zuptAbortedPending}")
                }
                if (live.zuptBiasUpdates != null && live.zuptBiasUpdates != replay.zuptBiasUpdates) {
                    add("ZUPT-Bias-Updates live=${live.zuptBiasUpdates} replay=${replay.zuptBiasUpdates}")
                }
                if (live.framesProcessed != null && live.framesProcessed != replay.framesProcessed) {
                    add("Filterframes live=${live.framesProcessed} replay=${replay.framesProcessed}")
                }
            }
        return causes.joinToString("; ").ifEmpty {
            "Mechanismus unklar — Sample-Rate/Filterzustand pruefen"
        }
    }

    /** CSV schreiben (Header + Zeilen), Verzeichnisse anlegen. */
    private fun writeCsv(
        path: Path,
        header: String,
        lines: List<String>,
    ) {
        Files.createDirectories(path.toAbsolutePath().parent)
        BufferedWriter(Files.newBufferedWriter(path)).use { w ->
            w.write(header)
            w.write("\n")
            for (line in lines) {
                w.write(line)
                w.write("\n")
            }
        }
    }

    /** Standard-Ausgabe des Vergleichs: neben der Sweep-CSV. */
    private fun defaultComparisonCsv(): Path =
        outputCsv.resolveSibling(
            outputCsv.fileName.toString().removeSuffix(".csv") + "_live_vs_replay.csv",
        )

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
                            event.timestampMs - event.durationMs / 2L
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

    /**
     * Baut die Pipeline-Konfiguration aus Fensterprofil + Parameter-Set.
     * `internal` fuer den B3-Test: ein ParameterSet ohne Override muss die
     * Fensterwerte replizieren (baseline).
     */
    internal fun configFor(
        window: CorpusWindow,
        params: ParameterSet,
        accelThreshold: Double,
        accelEnabled: Boolean,
    ): ExerciseEngineConfig =
        ExerciseEngineConfig(
            sampleRateHz = window.rateHz,
            rotationAxis = window.axis!!,
            gyroBias = window.bias!!,
            templateThreshold = params.templateThreshold ?: window.templateThreshold,
            minQualityScore = params.minQualityScore ?: window.minQualityScore,
            // B1 (RC-18): Toleranzen sind (noch) nicht im Profil/JSONL —
            // Baseline sind die Code-Defaults, der Sweep variiert sie.
            fatigueTolerance = params.fatigueTolerance ?: DEFAULT_FATIGUE_TOLERANCE,
            suspiciousTolerance = params.suspiciousTolerance ?: DEFAULT_SUSPICIOUS_TOLERANCE,
            // B6 (RC-21): Admission-Margin des Template-Pools.
            templateAdmissionMargin = params.templateAdmissionMargin ?: DEFAULT_TEMPLATE_ADMISSION_MARGIN,
            detectionThreshold = window.theta,
            expectedProminence = window.prominence,
            expectedDurationMs = window.durationMs,
            accelEnabled = accelEnabled,
            accelThreshold = accelThreshold,
            accelVoteWindowMs = params.accelVoteWindowMs ?: DEFAULT_VOTE_WINDOW_MS,
            dtwBand = params.dtwBand ?: window.dtwBand,
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
            framesProcessed = engine.framesProcessed,
            framesRejected = engine.framesRejected,
            zuptBiasUpdates = engine.zuptBiasUpdates,
            zuptAbortedPending = engine.zuptAbortedPending,
            rejectionCounts = engine.rejectionCountsSnapshot,
            estimatedSampleRateHz = engine.estimatedSampleRateHz,
        )
    }

    companion object {
        const val CSV_HEADER =
            "parameter_set;scenario;window;set_index;expected;counted;delta;" +
                "accel_theta;min_quality_margin;min_correlation_margin;note"

        /** RC-16: Spalten des Live-vs-Replay-Reports. */
        const val LIVE_VS_REPLAY_HEADER =
            "scenario;window;set_index;live_counted;replay_counted;delta;" +
                "live_gaps;replay_gaps;live_zupt_aborted;replay_zupt_aborted;" +
                "live_zupt_updates;replay_zupt_updates;live_rejections;replay_rejections;" +
                "live_rate_hz;replay_estimated_rate_hz;note"

        // Ein ParameterSet ohne Override repliziert das Live-Verhalten des
        // aufgenommenen Fensters: seit B3 (RC-20) traegt das Fenster auch
        // templateThreshold/minQualityScore/dtwBand (Altaufnahmen: Defaults).
        const val DEFAULT_VOTE_WINDOW_MS = 800L

        /** B1 (RC-18): Code-Defaults der einseitigen Toleranzen. */
        const val DEFAULT_FATIGUE_TOLERANCE = 1.45
        const val DEFAULT_SUSPICIOUS_TOLERANCE = 0.80

        /** B6 (RC-21): Code-Default der Admission-Margin. */
        const val DEFAULT_TEMPLATE_ADMISSION_MARGIN = 0.05

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
 * Kompakte, deterministische Darstellung der Ablehnungszaehler fuer CSV und
 * Notizen ("QUALITY=2|TEMPLATE_MATCH=1"; leer -> "keine").
 */
private fun describeRejections(counts: Map<RepRejectionReason, Int>): String =
    counts.entries
        .filter { it.value > 0 }
        .sortedBy { it.key.ordinal }
        .joinToString("|") { "${it.key.name}=${it.value}" }
        .ifEmpty { "keine" }

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
