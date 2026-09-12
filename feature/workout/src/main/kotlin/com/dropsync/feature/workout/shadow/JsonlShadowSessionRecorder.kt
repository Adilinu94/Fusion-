package com.dropsync.feature.workout.shadow

import android.content.Context
import com.dropsync.domain.sensor.CalibrationProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes [ShadowDiffEvent]s and raw sample windows as JSONL under
 * `/Android/data/<pkg>/files/recordings/<session>.jsonl`
 * (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 6; Umbauplan 2026-09-04 Phase 0).
 *
 * The session lifecycle is started/ended by TrainViewModel; events written
 * in between are appended to the session file. Missing startSession or
 * double start simply appends/starts a fresh writer (last one wins).
 */
@Singleton
class JsonlShadowSessionRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ShadowSessionRecorder {
        private var writer: BufferedWriter? = null
        private var sessionId: String? = null

        /**
         * 0-basierter Index des naechsten set-Events innerhalb der Session.
         * Stellt die Zuordnung zu `manifest.known_active_reps[i]` her. Wird in
         * [recordSet] inkrementiert, [recordSamples] liest den Index des
         * gerade geschriebenen Satzes (also `nextSetIndex - 1`) — deshalb die
         * Aufrufreihenfolge im Vertrag.
         */
        private var nextSetIndex = 0

        override fun startSession(sessionId: String) {
            endSession()
            this.sessionId = sessionId
            nextSetIndex = 0
            val dir = File(context.getExternalFilesDir(null), "recordings")
            dir.mkdirs()
            val file = File(dir, "$sessionId.jsonl")
            val w = FileWriter(file, true).buffered()
            writer = w
            w.write("{\"t\":\"session_start\",\"sessionId\":\"$sessionId\"}\n")
            w.flush()
        }

        override fun recordSet(event: ShadowDiffEvent) {
            val w = writer ?: return
            w.write(event.toJsonLine() + "\n")
            w.flush()
            nextSetIndex++
        }

        override fun recordSamples(window: SampleWindow) {
            val w = writer ?: return
            // Ohne vorangegangenes recordSet gibt es keinen Satz, zu dem die
            // Samples gehoeren. Lieber nichts schreiben als ein Fenster, das
            // auf einen nicht existierenden Satz zeigt.
            val setIndex = nextSetIndex - 1
            if (setIndex < 0) return
            val samples = window.samples
            if (samples.isEmpty()) return

            val tsFirst = samples.first().timestampMs
            val tsLast = samples.last().timestampMs
            w.write(
                "{\"t\":\"set_window\",\"setIndex\":$setIndex," +
                    "\"exerciseId\":${window.exerciseId}," +
                    "\"rateHz\":${window.measuredSampleRateHz}," +
                    "\"n\":${samples.size},\"tsFirst\":$tsFirst,\"tsLast\":$tsLast" +
                    profileJson(window.profile) + "}\n",
            )
            for (s in samples) {
                w.write(
                    "{\"t\":\"sample\",\"setIndex\":$setIndex,\"ts\":${s.timestampMs}," +
                        "\"ax\":${s.ax},\"ay\":${s.ay},\"az\":${s.az}," +
                        "\"gx\":${s.gx},\"gy\":${s.gy},\"gz\":${s.gz}}\n",
                )
            }
            // Ein Flush am Fensterende, nicht je Sample: bei ~600 Samples je
            // Satz waeren das 600 Flushes. Ein Absturz mitten im Satz
            // verliert dann hoechstens dieses Fenster, nicht die Session.
            w.flush()
        }

        override fun endSession() {
            val sid = sessionId ?: return
            val w = writer ?: return
            w.write("{\"t\":\"session_end\",\"sessionId\":\"$sid\"}\n")
            w.flush()
            w.close()
            writer = null
            sessionId = null
            nextSetIndex = 0
        }

        /**
         * Profilfelder des Satzfensters (Nachtrag Phase 0.7).
         *
         * Nur die Werte, die das Offline-Replay (Phase 6.2) braucht — nicht
         * das ganze Profil. `rotationAxis` und `gyroBias` bestimmen, welches
         * Signal die Pipeline sieht und sind nicht sweepbar; die drei
         * Schwellenwerte sind die Startpunkte, gegen die der Sweep variiert.
         *
         * Bewusst NICHT enthalten: `repTemplate`. Es sind 64 Werte je Satz,
         * und der Sweep variiert `templateThreshold`, nicht das Template
         * selbst. Wer das Template braucht, hat ein anderes Problem als einen
         * Parameter-Sweep.
         *
         * Leerer String ohne Profil: das Fenster bleibt gueltiges JSON, und
         * ein fehlendes `axis` sagt dem Leser klar "ohne Profil gezaehlt".
         */
        private fun profileJson(profile: CalibrationProfile?): String {
            if (profile == null) return ""
            val axis = profile.rotationAxis.joinToString(",")
            val bias = profile.gyroBias.joinToString(",")
            return ",\"axis\":[$axis],\"bias\":[$bias]," +
                "\"theta\":${profile.detectionThreshold}," +
                "\"prominence\":${profile.expectedProminence}," +
                "\"durationMs\":${profile.expectedDurationMs}," +
                "\"accelTheta\":${profile.accelThreshold}," +
                "\"revision\":${profile.revision}"
        }
    }
