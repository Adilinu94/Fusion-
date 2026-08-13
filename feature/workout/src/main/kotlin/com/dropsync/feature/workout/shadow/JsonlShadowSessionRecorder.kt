package com.dropsync.feature.workout.shadow

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes [ShadowDiffEvent]s as JSONL under
 * `/Android/data/<pkg>/files/recordings/<session>.jsonl`
 * (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 6, Plan Schritt 2/3).
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
        private var writer: FileWriter? = null
        private var sessionId: String? = null

        override fun startSession(sessionId: String) {
            endSession()
            this.sessionId = sessionId
            val dir = File(context.getExternalFilesDir(null), "recordings")
            dir.mkdirs()
            val file = File(dir, "$sessionId.jsonl")
            val w = FileWriter(file, true)
            writer = w
            w.write("{\"t\":\"session_start\",\"sessionId\":\"$sessionId\"}\n")
            w.flush()
        }

        override fun recordSet(event: ShadowDiffEvent) {
            val w = writer ?: return
            w.write(event.toJsonLine() + "\n")
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
        }
    }
