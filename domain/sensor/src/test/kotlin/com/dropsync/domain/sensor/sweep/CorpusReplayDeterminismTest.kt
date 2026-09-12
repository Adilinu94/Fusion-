package com.dropsync.domain.sensor.sweep

import com.dropsync.domain.sensor.ExerciseEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Determinismus-Beweis des Corpus-Replays (Umbauplan 2026-09-04 Phase 6.4):
 * "derselbe Trace, zweimal gespielt, gibt bitgleiche Counts (keine
 * Zeitabhaengigkeit im Replay)".
 *
 * Der Test-Trace enthaelt bewusst ECHTE Luecken (400 ms > LARGE_GAP_MS =
 * 250 ms mitten im Satz und 800 ms zwischen Saetzen): das Replay muss die
 * Timestamps aus der JSONL unveraendert mitspielen — geglaettete Timestamps
 * wuerden laut Plan "eine Pipeline messen, die live nicht gibt".
 *
 * Geprueft wird nicht nur der Zaehlerstand, sondern das vollstaendige
 * Rep-Ereignis-Tupel (Zeitstempel, Qualitaet, Korrelation, Prominenz,
 * Dauer): nur so ist ausgeschlossen, dass zwei Laeufe zufaellig denselben
 * Count liefern und sich in den Scores unterscheiden — die Abstands-
 * Metrik des Sweeps wuerde sonst auf nicht-deterministische Werte bauen.
 */
class CorpusReplayDeterminismTest {
    private fun repCycle(): List<Double> =
        (1..15).map { 60.0 * it / 15.0 } +
            (1..30).map { 60.0 - 120.0 * it / 30.0 } +
            (1..15).map { -60.0 + 60.0 * it / 15.0 }

    /**
     * Timestamps mit echten Luecken: 400 ms mitten im ersten Rep (ueber
     * LARGE_GAP_MS, loest onLargeGap aus) und 800 ms zwischen den Reps.
     * Der Trace bleibt chronologisch, wird aber NICHT veraenglicht —
     * exakt das, was Paketverlust auf dem Geraet hinterlaesst.
     */
    private fun gappedTrace(): List<Pair<Long, Double>> {
        val values =
            buildList {
                repeat(80) { add(0.0) }
                addAll(repCycle()) // Rep 1 (Samples 80..139)
                repeat(60) { add(0.0) }
                addAll(repCycle()) // Rep 2 (Samples 200..259)
                repeat(60) { add(0.0) }
            }
        val midRepGapAfter = 80 + 10 // mitten im ersten Rep
        val betweenRepsAt = 200 // erster Sample des zweiten Reps
        var extra = 0L
        return values.mapIndexed { i, v ->
            if (i == midRepGapAfter + 1) extra += 380L // 400 ms statt 20 ms
            if (i == betweenRepsAt) extra += 780L // 800 ms statt 20 ms
            (i * 20L + extra) to v
        }
    }

    @Test
    fun `derselbe trace zweimal gespielt gibt bitgleiche counts und events`() {
        val samples =
            gappedTrace().map { (ts, gx) ->
                CorpusSample(
                    timestampMs = ts,
                    ax = 0.02,
                    ay = -0.98,
                    az = 0.11,
                    gx = gx,
                    gy = 0.0,
                    gz = 0.0,
                )
            }
        val window =
            CorpusWindow(
                setIndex = 0,
                exerciseId = 7,
                rateHz = 50.0,
                axis = listOf(1.0, 0.0, 0.0),
                bias = listOf(0.0, 0.0, 0.0),
                theta = 32.5,
                prominence = 1.0,
                durationMs = 2_000.0,
                accelTheta = 0.0,
                revision = 1,
                samples = samples,
            )
        val config =
            ExerciseEngineConfig(
                rotationAxis = listOf(1.0, 0.0, 0.0),
                gyroBias = listOf(0.0, 0.0, 0.0),
                detectionThreshold = 32.5,
                expectedDurationMs = 2_000.0,
                expectedProminence = 1.0,
            )

        val harness = CorpusSweepHarness(Path.of("."), Path.of("unused.csv"))
        val first = harness.replay(window, config)
        val second = harness.replay(window, config)

        // Der Mid-Rep-Gap zerstoert Rep 1 korrekt (onLargeGap verwirft den
        // Pending-Rep) — deshalb steht hier KEINE Rep-Zahl: der Test beweist
        // Zeit-Unabhaengigkeit, nicht eine bestimmte Zahl. Zwei identische
        // Laeufe muessen alles identisch liefern.
        assertEquals(first.countedReps, second.countedReps)
        assertEquals("Events muessen bitgleich sein", first.repEvents, second.repEvents)
        assertEquals(
            "beide Luecken muessen in beiden Laeufen feuern",
            first.largeGapCount,
            second.largeGapCount,
        )
        assertEquals("Gap-Pfad muss durchlaufen werden", 2, first.largeGapCount)
    }

    @Test
    fun `replay ueber den loader ist identisch mit dem direkten replay`() {
        // Der Weg ueber die JSONL (schreiben, laden, replay) muss dasselbe
        // liefern wie das direkte In-Memory-Replay — sonst glaettet oder
        // veraendert eine Schicht des Ladens die Daten.
        val tmp = Files.createTempDirectory("determinism")
        val trace = gappedTrace()
        val lines =
            listOf(
                """{"t":"set_window","setIndex":0,"exerciseId":7,"rateHz":50.0,"n":${trace.size},""" +
                    """"tsFirst":${trace.first().first},"tsLast":${trace.last().first},""" +
                    """"axis":[1.0,0.0,0.0],"bias":[0.0,0.0,0.0],"theta":32.5,""" +
                    """"prominence":1.0,"durationMs":2000.0,"accelTheta":0.0,"revision":1}""",
            ) +
                trace.map { (ts, gx) ->
                    """{"t":"sample","setIndex":0,"ts":$ts,"ax":0.02,"ay":-0.98,"az":0.11,"gx":$gx,"gy":0.0,"gz":0.0}"""
                }
        Files.writeString(tmp.resolve("det.jsonl"), lines.joinToString("\n") + "\n")
        Files.writeString(
            tmp.resolve("det.jsonl.meta.json"),
            """{"recording":"det.jsonl","exercise_id":"e","scenario":"s","known_active_reps":[2],"device":"d"}""",
        )

        val (sessions, issues) = CorpusLoader(tmp).load()
        assertEquals(emptyList<String>(), issues)
        val loadedWindow = sessions.single().windows.single()
        val harness = CorpusSweepHarness(tmp, tmp.resolve("out.csv"))

        val directSamples =
            trace.map { (ts, gx) ->
                CorpusSample(timestampMs = ts, ax = 0.02, ay = -0.98, az = 0.11, gx = gx, gy = 0.0, gz = 0.0)
            }
        val directWindow =
            CorpusWindow(
                setIndex = 0,
                exerciseId = 7,
                rateHz = 50.0,
                axis = listOf(1.0, 0.0, 0.0),
                bias = listOf(0.0, 0.0, 0.0),
                theta = 32.5,
                prominence = 1.0,
                durationMs = 2_000.0,
                accelTheta = 0.0,
                revision = 1,
                samples = directSamples,
            )
        val config =
            ExerciseEngineConfig(
                rotationAxis = listOf(1.0, 0.0, 0.0),
                gyroBias = listOf(0.0, 0.0, 0.0),
                detectionThreshold = 32.5,
                expectedDurationMs = 2_000.0,
                expectedProminence = 1.0,
            )
        val loadedOutcome = harness.replay(loadedWindow, config)
        val directOutcome = harness.replay(directWindow, config)

        assertEquals(loadedOutcome.countedReps, directOutcome.countedReps)
        assertEquals("Events muessen identisch sein", loadedOutcome.repEvents, directOutcome.repEvents)
        assertEquals(
            "Timestamps duerfen durch das Laden nicht veraendert werden",
            trace.map { it.first },
            loadedWindow.samples.map { it.timestampMs },
        )
    }
}
