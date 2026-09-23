package com.dropsync.domain.sensor.sweep

import com.dropsync.domain.sensor.RepRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * CI-Regressionsgate auf dem goldenen Korpus (RC-16, Fix 3).
 *
 * Der Korpus ist synthetisch und deterministisch (SyntheticCorpus) — dieselbe
 * Rolle wie BarSpeeds `FieldDataRegressionTest`: jede Pipeline-Aenderung muss
 * die hier fixierten Counts und Ablehnungs-Mechanismen sichtbar brechen,
 * bevor sie echte Aufnahmen erreicht. Der echte Gate-Korpus (Adis
 * Kampagnen-Aufnahmen) kommt spaeter dazu; die Mechanik ist dieselbe.
 *
 * Fixiert wird:
 * - der Replay-Count je Satz gegen die eingebettete Wahrheit,
 * - delta = 0 im Live-vs-Replay-Vergleich (Live-Zeilen tragen dieselben
 *   Counts),
 * - die Ablehnungs-/ZUPT-Diagnose des Artefakt-Satzes (halbe Rep am Ende).
 */
class CorpusRegressionGateTest {
    private fun windowLines(
        setIndex: Int,
        gyro: List<Double>,
    ): List<String> {
        val accel = SyntheticCorpus.accelStream(gyro)
        val base = setIndex * 100_000L
        return listOf(SyntheticCorpus.setWindowLine(setIndex, gyro.size, base)) +
            gyro.mapIndexed { i, gx ->
                SyntheticCorpus.sampleLine(setIndex, base + i * 20L, gx, accel[i])
            }
    }

    /**
     * Baut den goldenen Korpus: drei Saetze mit [2, 3, 2] echten Reps.
     * Satz 2 endet mit einer halben Rep (0 -> 60 -> 0): live ein Artefakt,
     * das nie gezaehlt wird — aber genau die Klasse, die der Bericht als
     * eigenen Topf zeigen muss (ZUPT-Verwurf).
     *
     * Die Live-Zeilen tragen dieselben Diagnosewerte, die der Replay
     * berechnet (ZUPT-Bias-Updates je Ruhephase, ein Verwurf in Satz 2) —
     * so fixiert das Gate beide Seiten als konsistent.
     */
    private fun writeGoldenCorpus(dir: java.nio.file.Path) {
        val halfRepTail =
            SyntheticCorpus.gyroStream(2) +
                List(60) { 0.0 } +
                SyntheticCorpus.halfRepCycle() +
                List(40) { 0.0 }
        val lines =
            buildList {
                add("""{"t":"session_start","sessionId":"golden"}""")
                add(SyntheticCorpus.setLine(countedReps = 2, zuptUpdates = 3))
                addAll(windowLines(setIndex = 0, gyro = SyntheticCorpus.gyroStream(2)))
                add(SyntheticCorpus.setLine(countedReps = 3, zuptUpdates = 4))
                addAll(windowLines(setIndex = 1, gyro = SyntheticCorpus.gyroStream(3)))
                add(SyntheticCorpus.setLine(countedReps = 2, zuptAborted = 1, zuptUpdates = 4))
                addAll(windowLines(setIndex = 2, gyro = halfRepTail))
                add("""{"t":"session_end","sessionId":"golden"}""")
            }
        Files.writeString(dir.resolve("golden.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            dir.resolve("golden.jsonl.meta.json"),
            """{"recording":"golden.jsonl","exercise_id":"golden_curl",""" +
                """"scenario":"calibrated","known_active_reps":[2,3,2],"device":"synthetic"}""",
        )
    }

    @Test
    fun `goldkorpus - counts und ablehnungs-diagnose bleiben stabil`() {
        val tmp = Files.createTempDirectory("golden_gate")
        writeGoldenCorpus(tmp)

        val report = CorpusSweepHarness(tmp, tmp.resolve("sweep.csv")).compareLiveVsReplay()

        assertEquals(emptyList<String>(), report.loaderIssues)
        val rows = report.rows
        assertEquals(
            "Replay-Counts muessen exakt die eingebettete Wahrheit treffen",
            listOf(2, 3, 2),
            rows.map { it.replayCountedReps },
        )
        assertEquals(
            "Live und Replay duerfen sich auf dem Goldkorpus nicht unterscheiden",
            listOf(0, 0, 0),
            rows.map { it.delta },
        )
        assertTrue(
            "keine grossen Luecken im Goldkorpus",
            rows.all { it.replayLargeGaps == 0 },
        )

        // Satz 2 (halbe Rep am Ende): Der Ruhe-Eintritt verwirft den offenen
        // Pending — der Report muss das als eigenen Topf zeigen (RC-16/RC-17).
        val artifact = rows.single { it.setIndex == 2 }
        assertEquals(
            "die halbe Rep am Satzende ist ein echter ZUPT-Verwurf",
            1,
            artifact.replayZuptAborted,
        )
        assertEquals(
            "die halbe Rep erreicht decide() nicht — kein Klassifizierungs-Eintrag",
            emptyMap<RepRejectionReason, Int>(),
            artifact.replayRejections,
        )
        assertEquals(
            "saubere Saetze haben nichts zu verwerfen (Bias-Updates trotzdem)",
            listOf(0, 0),
            rows.filter { it.setIndex != 2 }.map { it.replayZuptAborted },
        )
        assertEquals(
            "Live- und Replay-Diagnose erzaehlen dieselbe Geschichte",
            rows.map { it.liveZuptAborted },
            rows.map { it.replayZuptAborted },
        )
    }
}
