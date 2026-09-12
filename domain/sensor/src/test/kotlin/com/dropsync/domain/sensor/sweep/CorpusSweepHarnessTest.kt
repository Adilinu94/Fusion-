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
    // Dasselbe deterministische Rep-Modell wie im Isolationstest: 0 -> 60
    // -> -60 -> 0 ueber 60 Samples, 60 ruhige Samples davor/danach/dazwischen.
    private fun repCycle(): List<Double> =
        (1..15).map { 60.0 * it / 15.0 } +
            (1..30).map { 60.0 - 120.0 * it / 30.0 } +
            (1..15).map { -60.0 + 60.0 * it / 15.0 }

    /** Gyro-Strom fuer [reps] Wiederholungen auf gx bei 50 Hz (20 ms). */
    private fun gyroStream(reps: Int): List<Double> =
        buildList {
            addAll(List(60) { 0.0 })
            repeat(reps) {
                addAll(repCycle())
                addAll(List(60) { 0.0 })
            }
        }

    /**
     * Accel-Strom: Ruhe (~1 g auf y, wie das reale Device auf dem Tisch) mit
     * einer klaren Magnituden-Spitze je Rep (~0.16 g Abweichung). Die Spitzen
     * liegen IN den Rep-Fenstern — nur so kann die Offline-Derivation
     * ueberhaupt eine trennscharfe Schwelle finden und der Voting-Pfad
     * geprueft werden. Die Staerke entspricht einem kraeftigen Zug, nicht
     * einem Sensor-Artefakt: knapp ueber der Rausch-Untergrenze gewinnt
     * niemand Vertrauen in das Werkzeug.
     */
    private fun accelStream(gyro: List<Double>): List<Triple<Double, Double, Double>> =
        gyro.map { gx ->
            if (gx > 5.0) Triple(0.15, -0.98, 0.60) else Triple(0.02, -0.98, 0.11)
        }

    private fun writeMiniCorpus(dir: Path) {
        // Satz 1: 2 Reps; Satz 2: 3 Reps. Profil wie im Isolationstest
        // (Achse [1,0,0], theta 32.5, Prominenz 1.0, Dauer 2000 ms) — das
        // zaehlt dort deterministisch exakt.
        val sets = listOf(2, 3)
        val lines = mutableListOf<String>()
        lines += """{"t":"session_start","sessionId":"mini"}"""
        var globalIndex = 0L
        sets.forEachIndexed { setIndex, reps ->
            val gyro = gyroStream(reps)
            val accel = accelStream(gyro)
            lines += setWindowLine(setIndex, gyro.size, setIndex * 1_000L)
            gyro.forEachIndexed { i, gx ->
                val (ax, ay, az) = accel[i]
                val ts = setIndex * 1_000L + globalIndex + i * 20L
                lines +=
                    """{"t":"sample","setIndex":$setIndex,"ts":$ts,"ax":$ax,"ay":$ay,"az":$az,"gx":$gx,"gy":0.0,"gz":0.0}"""
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
              "known_active_reps": [2, 3],
              "device": "synthetic",
              "samples_recorded": true
            }
            """.trimIndent(),
        )
    }

    private fun setWindowLine(
        setIndex: Int,
        n: Int,
        tsFirst: Long,
    ): String =
        """{"t":"set_window","setIndex":$setIndex,"exerciseId":7,"rateHz":50.0,"n":$n,""" +
            """"tsFirst":$tsFirst,"tsLast":${tsFirst + (n - 1) * 20L},""" +
            """"axis":[1.0,0.0,0.0],"bias":[0.0,0.0,0.0],"theta":32.5,""" +
            """"prominence":1.0,"durationMs":2000.0,"accelTheta":0.0,"revision":1}"""

    @Test
    fun `harness laeuft gegen den mini-corpus und erzeugt die csv`() {
        val tmp = Files.createTempDirectory("sweep_mini")
        writeMiniCorpus(tmp)
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
        writeMiniCorpus(tmp)

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
        writeMiniCorpus(tmp)

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
