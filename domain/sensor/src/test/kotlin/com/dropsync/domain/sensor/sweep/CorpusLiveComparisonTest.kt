package com.dropsync.domain.sensor.sweep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * RC-16: Live-vs-Replay-Vergleich im Harness.
 *
 * Der Vergleich spielt die Rohsamples eines Fensters durch eine frische
 * Pipeline und stellt das Ergebnis der `liveCountedReps`-Zahl aus der
 * `set`-Zeile gegenueber. Diese Tests pruefen die Mechanik:
 * 1. ohne Pfad-Differenz stehen beide Spalten gleich und delta = 0;
 * 2. der Gegenbeweis — eine geaenderte Refraktaerzeit aendert den
 *    Replay-Count und NICHT den Live-Count (sonst misst der Vergleich
 *    nichts);
 * 3. die Zuordnung set-Zeile <-> Fenster laeuft ueber den setIndex, nicht
 *    ueber die Listenposition (ein Satz ohne Samples darf die folgenden
 *    Saetze nicht verrutschen).
 */
class CorpusLiveComparisonTest {
    private fun windowLines(
        setIndex: Int,
        reps: Int,
    ): List<String> {
        val gyro = SyntheticCorpus.gyroStream(reps)
        val accel = SyntheticCorpus.accelStream(gyro)
        val base = setIndex * 100_000L
        return listOf(SyntheticCorpus.setWindowLine(setIndex, gyro.size, base)) +
            gyro.mapIndexed { i, gx ->
                SyntheticCorpus.sampleLine(setIndex, base + i * 20L, gx, accel[i])
            }
    }

    @Test
    fun `live-vs-replay vergleicht beide spalten ohne abweichung`() {
        val tmp = Files.createTempDirectory("lvr_clean")
        SyntheticCorpus.writeMiniCorpus(tmp)

        val report = CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).compareLiveVsReplay()

        assertEquals(emptyList<String>(), report.loaderIssues)
        assertEquals(2, report.rows.size)
        assertEquals("Live-Count kommt aus der set-Zeile", listOf(2, 3), report.rows.map { it.liveCountedReps })
        assertEquals("Replay zaehlt dieselben Reps", listOf(2, 3), report.rows.map { it.replayCountedReps })
        assertEquals("keine Pfad-Differenz", listOf(0, 0), report.rows.map { it.delta })
        assertEquals("ohne Abweichung keine Diagnose-Notiz", listOf("", ""), report.rows.map { it.note })

        val csv = Files.readString(report.csvPath)
        assertTrue("CSV beginnt mit dem Vergleichs-Header", csv.startsWith(CorpusSweepHarness.LIVE_VS_REPLAY_HEADER))
        assertEquals("Header + eine Zeile je Satz", 3, csv.lines().count { it.isNotBlank() })
    }

    @Test
    fun `gegenbeweis geaenderte refraktaerzeit aendert nur den replay-count`() {
        // expectedDurationMs steuert die Refraktaerzeit (30 %) und den
        // Qualitaetsscore. Eine Verachtfachung liegt weit ueber dem
        // Rep-Abstand (2400 ms) und muss den Replay-Count senken — der
        // Live-Count aus dem JSONL bleibt davon unberuehrt. Genau das
        // beweist, dass der Vergleich die Replay-Seite wirklich rechnet.
        val tmp = Files.createTempDirectory("lvr_proof")
        SyntheticCorpus.writeMiniCorpus(tmp)
        val harness = CorpusSweepHarness(tmp, tmp.resolve("sweep.csv"))

        val baseline = harness.compareLiveVsReplay()
        val adjusted =
            harness.compareLiveVsReplay { config ->
                config.copy(expectedDurationMs = config.expectedDurationMs * 8)
            }

        assertEquals("Baseline ohne Differenz", listOf(0, 0), baseline.rows.map { it.delta })
        assertEquals(
            "Live-Counts bleiben identisch (Quelle ist das JSONL, nicht die Config)",
            baseline.rows.map { it.liveCountedReps },
            adjusted.rows.map { it.liveCountedReps },
        )
        assertTrue(
            "geaenderte Refraktaerzeit MUSS den Replay-Count aendern",
            adjusted.rows.any { it.delta != 0 },
        )
        assertTrue(
            "die Abweichung muss im Report diagnostiziert werden",
            adjusted.rows.any { it.note.isNotEmpty() },
        )
    }

    @Test
    fun `set-zeile ohne fenster verrutscht die zuordnung nicht`() {
        // Drei Saetze, aber nur fuer Index 0 und 2 gibt es Fenster (Index 1
        // wurde ohne Samples abgebrochen). Die Zuordnung muss ueber den
        // setIndex laufen: Fenster 2 gehoert zu set-Zeile 2 (live=4), nicht
        // zur dritten Zeile in der Liste (die waere live=3).
        val tmp = Files.createTempDirectory("lvr_gap")
        val lines =
            buildList {
                add("""{"t":"session_start","sessionId":"gap"}""")
                add(SyntheticCorpus.setLine(countedReps = 2))
                addAll(windowLines(setIndex = 0, reps = 2))
                add(SyntheticCorpus.setLine(countedReps = 3))
                add(SyntheticCorpus.setLine(countedReps = 4))
                addAll(windowLines(setIndex = 2, reps = 4))
                add("""{"t":"session_end","sessionId":"gap"}""")
            }
        Files.writeString(tmp.resolve("gap.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            tmp.resolve("gap.jsonl.meta.json"),
            """{"recording":"gap.jsonl","exercise_id":"e","scenario":"s","known_active_reps":[2,0,4],"device":"d"}""",
        )

        val report = CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).compareLiveVsReplay()

        val byIndex = report.rows.associateBy { it.setIndex }
        assertEquals(2, byIndex.getValue(0).liveCountedReps)
        assertEquals(4, byIndex.getValue(2).liveCountedReps)
        assertEquals("Replay von Satz 2 zaehlt die vier Reps", 4, byIndex.getValue(2).replayCountedReps)
    }

    @Test
    fun `fenster ohne profil wird als nicht messbar gemeldet`() {
        val tmp = Files.createTempDirectory("lvr_noprofile")
        val lines =
            listOf(
                """{"t":"session_start","sessionId":"nop"}""",
                SyntheticCorpus.setLine(countedReps = 1),
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

        val report = CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).compareLiveVsReplay()

        val row = report.rows.single()
        assertEquals(null, row.replayCountedReps)
        assertEquals(1, row.liveCountedReps)
        assertTrue(row.note.contains("kein Profil"))
    }

    /** Der Vergleich ist per Property auch gegen einen echten Corpus fahrbar. */
    @Test
    fun `echter corpus vergleich nur auf anforderung`() {
        val corpusDir = System.getProperty("sweep.corpus.dir") ?: return
        val out = System.getProperty("sweep.out.csv")?.let { Path.of(it) }
        val harness = CorpusSweepHarness(Path.of(corpusDir), out ?: Path.of("sweep_result.csv"))
        val report = if (out != null) harness.compareLiveVsReplay(out) else harness.compareLiveVsReplay()
        println("Live-vs-Replay: ${report.rows.size} Zeilen -> ${report.csvPath}")
        report.rows.filter { it.delta != 0 }.forEach { println("DELTA: $it") }
        report.loaderIssues.forEach { println("ISSUE: $it") }
    }
}
