package com.dropsync.feature.workout.shadow

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.feature.workout.shadow.di.ShadowRecorderModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Umbauplan 2026-09-04 Phase 0.3: der Recorder ist die einzige Stelle, die
 * das JSONL-Format erzeugt, das `tools/shadow_harness.py` liest. Diese Tests
 * pruefen das Format und die setIndex-Zuordnung — ohne sie waere ein
 * aufgezeichneter Corpus erst nach dem Transfer auf den Rechner als
 * unbrauchbar erkennbar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class JsonlShadowSessionRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorder = JsonlShadowSessionRecorder(context, TestIoDispatchers)

    /** A4: echte Hintergrund-Dispatcher statt Test-Scheduler. */
    private object TestIoDispatchers : DispatcherProvider {
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val default: CoroutineDispatcher get() = Dispatchers.Default
        override val main: CoroutineDispatcher get() = Dispatchers.Main
    }

    private fun sessionFile(sessionId: String): File =
        File(File(context.getExternalFilesDir(null), "recordings"), "$sessionId.jsonl")

    private fun lines(sessionId: String): List<JSONObject> =
        sessionFile(sessionId)
            .readLines()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }

    private fun samples(
        n: Int,
        startMs: Long = 1_000L,
    ) = (0 until n).map { i ->
        SensorSample(
            timestampMs = startMs + i * 20L,
            ax = 0.01 * i,
            ay = -0.98,
            az = 0.11,
            gx = 1.5 * i,
            gy = -0.6,
            gz = 0.2,
        )
    }

    private fun event(confirmed: Int) =
        ShadowDiffEvent(
            exerciseId = 7L,
            weightMilliKg = 20_000_000L,
            confirmedReps = confirmed,
            confirmedRepsEdited = true,
            liveCountedReps = confirmed,
            shadowReps = confirmed,
        )

    private fun profile() =
        CalibrationProfile(
            exerciseId = 7L,
            deviceId = "AA:BB",
            rotationAxis = listOf(0.8, 0.0, 0.6),
            gyroBias = listOf(0.01, -0.02, 0.03),
            repTemplate = List(64) { 0.0 },
            expectedProminence = 42.5,
            detectionThreshold = 15.5,
            noiseFloor = 5.0,
            expectedDurationMs = 2_100.0,
            accelThreshold = 0.1625,
            revision = 3,
            templateThreshold = 0.83,
            minQualityScore = 0.61,
            dtwBand = 12,
        )

    /** Kurzform: Fenster mit Profil (Standardfall seit Phase 0.7). */
    private fun window(
        n: Int,
        startMs: Long = 1_000L,
        rateHz: Double = 50.0,
        exerciseId: Long = 7L,
        withProfile: Boolean = true,
    ) = SampleWindow(
        exerciseId = exerciseId,
        measuredSampleRateHz = rateHz,
        samples = samples(n, startMs),
        profile = if (withProfile) profile() else null,
    )

    @Test
    fun `Session mit zwei Saetzen schreibt set set_window und sample je Satz`() =
        runTest {
            val id = "test_two_sets"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(3, rateHz = 49.8))
            recorder.recordSet(event(10))
            recorder.recordSamples(window(2, startMs = 5_000L, rateHz = 50.2))
            recorder.endSession()

            val all = lines(id)
            assertEquals("session_start", all.first().getString("t"))
            assertEquals("session_end", all.last().getString("t"))

            val types = all.map { it.getString("t") }
            assertEquals(
                listOf(
                    "session_start",
                    "set",
                    "set_window",
                    "sample",
                    "sample",
                    "sample",
                    "set",
                    "set_window",
                    "sample",
                    "sample",
                    "session_end",
                ),
                types,
            )
        }

    @Test
    fun `setIndex verbindet Satz-Event und Sample-Fenster ohne Zutun des Aufrufers`() =
        runTest {
            val id = "test_setindex"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(2))
            recorder.recordSet(event(10))
            recorder.recordSamples(window(2, startMs = 9_000L))
            recorder.endSession()

            val windows = lines(id).filter { it.getString("t") == "set_window" }
            assertEquals(listOf(0, 1), windows.map { it.getInt("setIndex") })

            // Jede Sample-Zeile traegt den Index ihres Fensters. Ohne ihn kann der
            // Harness die Samples nicht manifest.known_active_reps[i] zuordnen.
            val samplesBySet = lines(id).filter { it.getString("t") == "sample" }.groupBy { it.getInt("setIndex") }
            assertEquals(setOf(0, 1), samplesBySet.keys)
            assertEquals(2, samplesBySet.getValue(0).size)
            assertEquals(2, samplesBySet.getValue(1).size)
        }

    @Test
    fun `set_window traegt n tsFirst tsLast und die gemessene Rate`() =
        runTest {
            val id = "test_window_fields"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(5, rateHz = 49.8, exerciseId = 42L))
            recorder.endSession()

            val window = lines(id).single { it.getString("t") == "set_window" }
            assertEquals(42L, window.getLong("exerciseId"))
            assertEquals(49.8, window.getDouble("rateHz"), 1e-9)
            assertEquals(5, window.getInt("n"))
            assertEquals(1_000L, window.getLong("tsFirst"))
            assertEquals(1_080L, window.getLong("tsLast"))
        }

    // --- Profilfelder (Nachtrag Phase 0.7) --------------------------------
    //
    // Ohne Achse und Bias projiziert ein Offline-Replay auf die Neutralachse
    // und misst damit eine Pipeline, die live nie gelaufen ist. Diese Werte
    // sind kalibriert, also aus keiner anderen Quelle rekonstruierbar - fehlen
    // sie in der Aufnahme, ist der Satz fuer Sweeps verloren.

    @Test
    fun `set_window traegt Achse Bias und die kalibrierten Schwellen`() =
        runTest {
            val id = "test_window_profile"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(5))
            recorder.endSession()

            val w = lines(id).single { it.getString("t") == "set_window" }
            val axis = w.getJSONArray("axis")
            assertEquals(3, axis.length())
            assertEquals(0.8, axis.getDouble(0), 1e-9)
            assertEquals(0.0, axis.getDouble(1), 1e-9)
            assertEquals(0.6, axis.getDouble(2), 1e-9)

            val bias = w.getJSONArray("bias")
            assertEquals(3, bias.length())
            assertEquals(0.01, bias.getDouble(0), 1e-9)
            assertEquals(-0.02, bias.getDouble(1), 1e-9)
            assertEquals(0.03, bias.getDouble(2), 1e-9)

            assertEquals(15.5, w.getDouble("theta"), 1e-9)
            assertEquals(42.5, w.getDouble("prominence"), 1e-9)
            assertEquals(2_100.0, w.getDouble("durationMs"), 1e-9)
            assertEquals(0.1625, w.getDouble("accelTheta"), 1e-9)
            // B3 (RC-20): die drei Profil-Schwellen gehoeren ins Fenster,
            // sonst reproduziert das Replay nicht die Live-Pipeline.
            assertEquals(0.83, w.getDouble("templateThreshold"), 1e-9)
            assertEquals(0.61, w.getDouble("minQualityScore"), 1e-9)
            assertEquals(12, w.getInt("dtwBand"))
            assertEquals(3, w.getInt("revision"))
        }

    @Test
    fun `set_window ohne Profil bleibt gueltiges JSON ohne Achse`() =
        runTest {
            // Ein Satz ohne Profil (Szenario no_template) ist fuer Sweeps
            // unbrauchbar - das fehlende axis-Feld sagt das dem Leser direkt,
            // statt eine Neutralachse zu erfinden.
            val id = "test_window_no_profile"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(3, withProfile = false))
            recorder.endSession()

            val w = lines(id).single { it.getString("t") == "set_window" }
            assertEquals(3, w.getInt("n"))
            assertTrue("ohne Profil darf kein axis-Feld erscheinen", !w.has("axis"))
            assertTrue(!w.has("theta"))
        }

    @Test
    fun `sample-Zeilen tragen die vom Harness gelesenen Felder ts gx gy gz plus ax ay az`() =
        runTest {
            val id = "test_sample_fields"
            recorder.startSession(id)
            recorder.recordSet(event(1))
            recorder.recordSamples(window(1, startMs = 2_500L))
            recorder.endSession()

            val sample = lines(id).single { it.getString("t") == "sample" }
            // shadow_harness.py:71-77 liest ts/gx/gy/gz — diese Namen sind Vertrag.
            assertEquals(2_500L, sample.getLong("ts"))
            assertEquals(0.0, sample.getDouble("gx"), 1e-9)
            assertEquals(-0.6, sample.getDouble("gy"), 1e-9)
            assertEquals(0.2, sample.getDouble("gz"), 1e-9)
            assertEquals(0.0, sample.getDouble("ax"), 1e-9)
            assertEquals(-0.98, sample.getDouble("ay"), 1e-9)
            assertEquals(0.11, sample.getDouble("az"), 1e-9)
        }

    @Test
    fun `Samples sind nach dem Fenster-Flush lesbar ohne endSession`() =
        runTest {
            // Ein App-Absturz nach dem Satz darf hoechstens das laufende Fenster
            // kosten, nicht die ganze Session: deshalb Flush am Fensterende.
            val id = "test_flush"
            recorder.startSession(id)
            recorder.recordSet(event(8))
            recorder.recordSamples(window(4))

            val types = lines(id).map { it.getString("t") }
            assertTrue("Fenster muss vor endSession auf der Platte liegen", types.contains("set_window"))
            assertEquals(4, types.count { it == "sample" })
        }

    @Test
    fun `recordSamples ohne vorangegangenes recordSet schreibt nichts`() =
        runTest {
            // Ein Fenster mit setIndex -1 wuerde auf einen Satz zeigen, den es
            // nicht gibt; der Harness haette dann Samples ohne Wahrheit.
            val id = "test_no_set"
            recorder.startSession(id)
            recorder.recordSamples(window(3))
            recorder.endSession()

            val types = lines(id).map { it.getString("t") }
            assertEquals(listOf("session_start", "session_end"), types)
        }

    @Test
    fun `leeres Sample-Fenster schreibt keine Zeile`() =
        runTest {
            val id = "test_empty"
            recorder.startSession(id)
            recorder.recordSet(event(5))
            recorder.recordSamples(window(0))
            recorder.endSession()

            val types = lines(id).map { it.getString("t") }
            assertEquals(listOf("session_start", "set", "session_end"), types)
        }

    @Test
    fun `neue Session setzt den setIndex-Zaehler zurueck`() =
        runTest {
            recorder.startSession("test_reset_a")
            recorder.recordSet(event(12))
            recorder.recordSamples(window(1))
            recorder.endSession()

            recorder.startSession("test_reset_b")
            recorder.recordSet(event(9))
            recorder.recordSamples(window(1))
            recorder.endSession()

            val window = lines("test_reset_b").single { it.getString("t") == "set_window" }
            assertEquals("zweite Session muss wieder bei 0 anfangen", 0, window.getInt("setIndex"))
        }

    @Test
    fun `Debug-Flag entscheidet ueber die Aufzeichnung`() =
        runTest {
            // RC-2/ADR-0023: Nur debuggable Builds schreiben Rohdaten; die
            // Release-App bekommt den NoOp-Recorder. Der Test prueft die reine
            // Entscheidung, nicht den Hilt-Graph.
            assertTrue(
                "Debug-Build muss aufzeichnen",
                ShadowRecorderModule.isRecordingEnabled(ApplicationInfo.FLAG_DEBUGGABLE),
            )
            assertTrue(
                "Release-Build darf nicht aufzeichnen",
                !ShadowRecorderModule.isRecordingEnabled(0),
            )
        }

    // --- RC-17: Ablehnungsmechanismen im JSONL ----------------------------

    @Test
    fun `set-Zeile traegt die Ablehnungsmechanismen`() =
        runTest {
            val id = "test_rejections"
            recorder.startSession(id)
            recorder.recordSet(
                event(12).copy(
                    rejectionCounts =
                        mapOf(
                            RepRejectionReason.QUALITY to 2,
                            RepRejectionReason.PHASE_VALIDATION to 1,
                        ),
                ),
            )
            recorder.recordSamples(window(1))
            recorder.endSession()

            val rejections =
                lines(id)
                    .single { it.getString("t") == "set" }
                    .getJSONObject("rejections")
            assertEquals(2, rejections.getInt("QUALITY"))
            assertEquals(1, rejections.getInt("PHASE_VALIDATION"))
        }

    @Test
    fun `set-Zeile ohne Ablehnungen traegt ein leeres Objekt`() =
        runTest {
            val id = "test_rejections_empty"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(1))
            recorder.endSession()

            val rejections =
                lines(id)
                    .single { it.getString("t") == "set" }
                    .getJSONObject("rejections")
            assertEquals(0, rejections.length())
        }

    // --- RC-16: Live-Diagnose im JSONL fuer den Live-vs-Replay-Vergleich --

    @Test
    fun `set-Zeile traegt die Live-Diagnose`() =
        runTest {
            val id = "test_diagnostics"
            recorder.startSession(id)
            recorder.recordSet(
                event(12).copy(
                    diagnostics =
                        SetDiagnostics(
                            countedReps = 12,
                            framesProcessed = 900,
                            framesRejected = 3,
                            largeGapCount = 1,
                            zuptBiasUpdates = 4,
                            zuptAbortedPending = 1,
                            measuredSampleRateHz = 49.75,
                        ),
                ),
            )
            recorder.recordSamples(window(1))
            recorder.endSession()

            val set = lines(id).single { it.getString("t") == "set" }
            assertEquals(900, set.getInt("framesProcessed"))
            assertEquals(3, set.getInt("framesRejected"))
            assertEquals(1, set.getInt("gaps"))
            assertEquals(4, set.getInt("zuptUpdates"))
            assertEquals(1, set.getInt("zuptAborted"))
            assertEquals(49.75, set.getDouble("rateHz"), 0.0001)
        }

    @Test
    fun `set-Zeile ohne Diagnose bleibt im Altformat`() =
        runTest {
            val id = "test_diagnostics_absent"
            recorder.startSession(id)
            recorder.recordSet(event(12))
            recorder.recordSamples(window(1))
            recorder.endSession()

            val set = lines(id).single { it.getString("t") == "set" }
            assertTrue(
                "Altaufrufer ohne Diagnose duerfen keine Diagnosefelder erzeugen",
                !set.has("framesProcessed") && !set.has("gaps") && !set.has("rateHz"),
            )
        }

    // --- A4/T-5/S-12: Dateiarbeit auf IO ----------------------------------

    @Test
    fun `alle Recorder-Aufrufe laufen ueber den io-dispatcher`() =
        runTest {
            var dispatches = 0
            val io =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatches++
                        block.run()
                    }
                }
            val provider =
                object : DispatcherProvider {
                    override val io: CoroutineDispatcher get() = io
                    override val default: CoroutineDispatcher get() = io
                    override val main: CoroutineDispatcher get() = io
                }
            val local = JsonlShadowSessionRecorder(context, provider)

            local.startSession("test_io_dispatcher")
            local.recordSet(event(1))
            local.recordSamples(window(2))
            local.endSession()

            assertTrue(
                "start/set/samples/end muessen per withContext(io) laufen, waren: $dispatches",
                dispatches >= 4,
            )
        }
}
