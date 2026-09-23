package com.dropsync.domain.sensor.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mechanik-Test des Sweep-Werkzeugs (Umbauplan 2026-09-04 Phase 6.4).
 *
 * Laeuft gegen einen eingebetteten synthetischen Mini-Corpus (2 Saetze) und
 * prueft, was das Werkzeug leisten muss, bevor es echten Daten ausgesetzt
 * wird: Corpus laden, Fenster mit Profil erkennen, je Parametersatz eine
 * frische Pipeline bauen, CSV mit Kopf + Zeilen je (Parametersatz x Satz)
 * schreiben, Wahrheit aus dem Manifest zuordnen.
 *
 * Der Mini-Corpus ist bewusst deterministisch (dasselbe Signalmodell wie
 * `ExerciseEnginePipelineIsolationTest`): die Baseline muss die eingebettete
 * Rep-Zahl exakt zaehlen, sonst wuerde der Sweep ein Werkzeug messen, das
 * schon am Mechanismus-Test scheitert. Echte Sweep-ERGEBNISSE brauchen die
 * Phase-1-Aufnahmen — dieser Test beweist die Mechanik, nicht die Parameter.
 *
 * Der Einstieg gegen einen ECHTEN Corpus ist der Property-gate unten
 * (`sweep.corpus.dir`): als @Test markiert, damit er ohne eigene Toolchain
 * laeuft (Plan 6.2), aber ohne Flag ein No-Op, damit die CI ihn nie anfasst.
 */
class CorpusSweepHarnessTest {
    // Der Mini-Corpus liegt in SyntheticCorpus (geteilt mit den
    // Live-vs-Replay-Tests und dem CI-Regressionsgate).

    @Test
    fun `harness laeuft gegen den mini-corpus und erzeugt die csv`() {
        val tmp = Files.createTempDirectory("sweep_mini")
        SyntheticCorpus.writeMiniCorpus(tmp)
        val csv = tmp.resolve("sweep.csv")

        val report =
            CorpusSweepHarness(tmp, csv).sweep(
                listOf(
                    CorpusSweepHarness.ParameterSet("baseline"),
                    CorpusSweepHarness.ParameterSet("templateThreshold=0.85", templateThreshold = 0.85),
                ),
            )

        assertTrue("CSV muss existieren", Files.exists(csv))
        val csvText = Files.readString(csv)
        assertTrue(
            "CSV beginnt mit dem Header",
            csvText.startsWith(CorpusSweepHarness.CSV_HEADER),
        )
        assertEquals(
            "Zeilen je Parametersatz x Satz",
            4,
            csvText.lines().count { it.isNotBlank() } - 1,
        )
        assertEquals("keine Loader-Issues", emptyList<String>(), report.loaderIssues)
    }

    @Test
    fun `baseline zaehlt die eingebettete rep-zahl exakt`() {
        val tmp = Files.createTempDirectory("sweep_exact")
        SyntheticCorpus.writeMiniCorpus(tmp)

        val report =
            CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).sweep(
                listOf(CorpusSweepHarness.ParameterSet("baseline")),
            )

        val counted = report.rows.mapNotNull { it.counted }
        assertEquals("Satz 1 und Satz 2: 2 bzw. 3 Reps", listOf(2, 3), counted)
        assertEquals("Delta 0 gegenueber der Manifest-Wahrheit", listOf(0, 0), report.rows.mapNotNull { it.delta })
    }

    @Test
    fun `accel-parametersatz deriviert eine trennscharfe schwelle und zaehlt weiter korrekt`() {
        val tmp = Files.createTempDirectory("sweep_accel")
        SyntheticCorpus.writeMiniCorpus(tmp)

        val report =
            CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).sweep(
                listOf(
                    CorpusSweepHarness.ParameterSet(
                        "accel on",
                        accelEnabled = true,
                        accelPeakFraction = 0.35,
                        accelNoiseMargin = 4.0,
                        accelPeakWindowS = 0.4,
                    ),
                ),
            )

        // Satz 2 (3 Reps): die synthetischen Accel-Spitzen (~0.16 g) muessen
        // von der Ruhe (~0.01) getrennt werden. Abgeleitete Schwelle > 0,
        // Voting an, Ergebnis unveraendert (die Peaks liegen innerhalb der
        // Rep-Fenster).
        val strong = report.rows.single { it.setIndex == 1 }
        assertTrue(
            "abgeleitete Schwelle muss ueber dem Rauschen liegen: ${strong.accelTheta}",
            strong.accelTheta > 0.02,
        )
        assertEquals(3, strong.counted)
        assertEquals(0, strong.delta)

        // Satz 1 (2 Reps): weniger als MIN_ACCEL_CALIBRATION_PEAKS Marks —
        // keine belastbare Trennung, Voting bleibt aus (wie live), der
        // Gyro-Lauf zaehlt trotzdem.
        val weak = report.rows.single { it.setIndex == 0 }
        assertEquals(0.0, weak.accelTheta, 0.0)
        assertTrue(weak.note.contains("Voting bleibt aus"))
        assertEquals(2, weak.counted)
        assertEquals(0, weak.delta)
    }

    @Test
    fun `fenster ohne profil wird protokolliert statt gemessen`() {
        val tmp = Files.createTempDirectory("sweep_noprofile")
        // set_window OHNE axis/bias (Phase 0.7: ohne Profil gezaehlt).
        val lines =
            listOf(
                """{"t":"session_start","sessionId":"nop"}""",
                """{"t":"set_window","setIndex":0,"exerciseId":7,"rateHz":50.0,"n":3,"tsFirst":0,"tsLast":40}""",
                """{"t":"sample","setIndex":0,"ts":0,"ax":0.0,"ay":-0.98,"az":0.11,"gx":1.0,"gy":0.0,"gz":0.0}""",
                """{"t":"sample","setIndex":0,"ts":20,"ax":0.0,"ay":-0.98,"az":0.11,"gx":1.0,"gy":0.0,"gz":0.0}""",
                """{"t":"sample","setIndex":0,"ts":40,"ax":0.0,"ay":-0.98,"az":0.11,"gx":1.0,"gy":0.0,"gz":0.0}""",
                """{"t":"session_end","sessionId":"nop"}""",
            )
        Files.writeString(tmp.resolve("nop.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            tmp.resolve("nop.jsonl.meta.json"),
            """{"recording":"nop.jsonl","exercise_id":"e","scenario":"x","known_active_reps":[1],"device":"d"}""",
        )

        val report =
            CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).sweep(
                listOf(CorpusSweepHarness.ParameterSet("baseline")),
            )

        val row = report.rows.single()
        assertEquals(null, row.counted)
        assertTrue(row.note.contains("kein Profil"))
    }

    /**
     * B3 (RC-20): Die drei Profil-Schwellen gehoeren ins Fenster — sonst
     * misst der Replay eine Pipeline, die live nie gelaufen ist. Das
     * Live-vs-Replay-Band liest sie direkt, der Sweep nimmt sie als
     * Baseline und variiert nur die gesetzten Overrides.
     */
    @Test
    fun `liveConfig und baseline-config replizieren die fensterschwellen`() {
        val harness = CorpusSweepHarness(Path.of("unused"), Path.of("unused.csv"))
        val window =
            CorpusWindow(
                setIndex = 0,
                exerciseId = 7L,
                rateHz = 50.0,
                axis = listOf(1.0, 0.0, 0.0),
                bias = listOf(0.0, 0.0, 0.0),
                theta = 32.5,
                prominence = 1.0,
                durationMs = 2_000.0,
                accelTheta = 0.0,
                templateThreshold = 0.83,
                minQualityScore = 0.61,
                dtwBand = 12,
                revision = 1,
                samples = emptyList(),
            )

        val live = harness.liveConfig(window)
        assertEquals(0.83, live.templateThreshold, 1e-9)
        assertEquals(0.61, live.minQualityScore, 1e-9)
        assertEquals(12, live.dtwBand)

        val baseline =
            harness.configFor(
                window,
                CorpusSweepHarness.ParameterSet("baseline"),
                accelThreshold = 0.0,
                accelEnabled = false,
            )
        assertEquals("baseline muss das Fenster replizieren", live.templateThreshold, baseline.templateThreshold, 1e-9)
        assertEquals(live.minQualityScore, baseline.minQualityScore, 1e-9)
        assertEquals(live.dtwBand, baseline.dtwBand)

        val override =
            harness.configFor(
                window,
                CorpusSweepHarness.ParameterSet("dtwBand=4", dtwBand = 4),
                accelThreshold = 0.0,
                accelEnabled = false,
            )
        assertEquals("Override schlaegt das Fenster", 4, override.dtwBand)
        assertEquals("nicht gesetzte Werte bleiben am Fenster", 0.83, override.templateThreshold, 1e-9)
    }

    /**
     * B3 (RC-20): v6-Fenster tragen die Schwellen, Altaufnahmen (ohne die
     * Felder) lesen exakt die bisherigen Code-Defaults — sonst wuerde eine
     * alte Aufnahme gegen eine andere Baseline gemessen als damals live.
     */
    @Test
    fun `loader liest v6-schwellen und alte fenster bekommen die defaults`() {
        val tmp = Files.createTempDirectory("sweep_v6_fields")
        val lines =
            listOf(
                """{"t":"session_start","sessionId":"v6"}""",
                """{"t":"set_window","setIndex":0,"exerciseId":7,"rateHz":50.0,"n":1,"tsFirst":0,"tsLast":0,""" +
                    """"axis":[1.0,0.0,0.0],"bias":[0.0,0.0,0.0],"theta":32.5,"prominence":1.0,""" +
                    """"durationMs":2000.0,"accelTheta":0.0,"templateThreshold":0.83,""" +
                    """"minQualityScore":0.61,"dtwBand":12,"revision":1}""",
                """{"t":"sample","setIndex":0,"ts":0,"ax":0.0,"ay":-0.98,"az":0.11,"gx":1.0,"gy":0.0,"gz":0.0}""",
                """{"t":"set_window","setIndex":1,"exerciseId":7,"rateHz":50.0,"n":1,"tsFirst":20,"tsLast":20,""" +
                    """"axis":[1.0,0.0,0.0],"bias":[0.0,0.0,0.0],"theta":32.5,"prominence":1.0,""" +
                    """"durationMs":2000.0,"accelTheta":0.0,"revision":1}""",
                """{"t":"sample","setIndex":1,"ts":20,"ax":0.0,"ay":-0.98,"az":0.11,"gx":1.0,"gy":0.0,"gz":0.0}""",
                """{"t":"session_end","sessionId":"v6"}""",
            )
        Files.writeString(tmp.resolve("v6.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            tmp.resolve("v6.jsonl.meta.json"),
            """{"recording":"v6.jsonl","exercise_id":"e","scenario":"x","known_active_reps":[1,1],"device":"d"}""",
        )

        val (sessions, issues) = CorpusLoader(tmp).load()
        assertEquals(emptyList<String>(), issues)
        val windows = sessions.single().windows

        val modern = windows.first { it.setIndex == 0 }
        assertEquals(0.83, modern.templateThreshold, 1e-9)
        assertEquals(0.61, modern.minQualityScore, 1e-9)
        assertEquals(12, modern.dtwBand)

        val legacy = windows.first { it.setIndex == 1 }
        assertEquals(CorpusWindow.DEFAULT_TEMPLATE_THRESHOLD, legacy.templateThreshold, 1e-9)
        assertEquals(CorpusWindow.DEFAULT_MIN_QUALITY_SCORE, legacy.minQualityScore, 1e-9)
        assertEquals(CorpusWindow.DEFAULT_DTW_BAND, legacy.dtwBand)
    }

    /**
     * Einstieg gegen den ECHTEN Corpus (Phase 1): nur auf Anforderung per
     * `-Dsweep.corpus.dir=...` (Plan 6.2: "per @Ignore/Property-Flag nur auf
     * Anforderung"). Ohne Flag No-Op — die CI darf den Sweep gegen echte
     * Aufnahmen nie automatisch anstossen.
     */
    @Test
    fun `echter corpus sweep nur auf anforderung`() {
        val corpusDir = System.getProperty("sweep.corpus.dir") ?: return
        val out = System.getProperty("sweep.out.csv") ?: "sweep_result.csv"
        val report =
            CorpusSweepHarness(
                Path.of(corpusDir),
                Path.of(out),
            ).sweep(CorpusSweepHarness.defaultParameterSets())
        println("Sweep: ${report.rows.size} Zeilen -> ${report.csvPath}")
        report.loaderIssues.forEach { println("ISSUE: $it") }
    }
}
