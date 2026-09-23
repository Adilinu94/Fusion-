package com.dropsync.feature.workout.shadow

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.SetDiagnostics

/**
 * Shadow-vs-confirmed diff recording (`docs/design/SHADOW_DIFF_HARNESS_PLAN.md`,
 * D2/D3/D4; ADR-0014).
 *
 * D2: every logged set is recorded — counters via [recordSet], raw sensor
 * samples via [recordSamples] (Umbauplan 2026-09-04 Phase 0).
 * D3 (Ground-Truth-Regel): [ShadowDiffEvent.confirmedRepsEdited] decides
 * whether [ShadowDiffEvent.confirmedReps] may count as independent truth in
 * the eventual harness evaluation — an unedited pre-filled value never does.
 *
 * Persisting as JSONL under `/Android/data/<pkg>/files/recordings/` (Plan
 * Abschnitt 6) is [JsonlShadowSessionRecorder]; [NoOpShadowSessionRecorder]
 * is the binding for contexts without file I/O.
 */
interface ShadowSessionRecorder {
    /**
     * A4/T-5/S-12: alle Methoden sind suspend — Implementierungen schreiben
     * auf IO, der Aufrufer darf Main nicht blockieren. Die Reihenfolge
     * (start -> set -> samples -> ... -> end) bleibt Vertrag.
     */
    suspend fun startSession(sessionId: String)

    suspend fun recordSet(event: ShadowDiffEvent)

    /**
     * Schreibt die Rohsamples eines abgeschlossenen Sets plus die Satzgrenze
     * (Umbauplan 2026-09-04 Phase 0). Format wie von
     * `tools/shadow_harness.py` gelesen: `t="sample"` mit `ts`/`gx`/`gy`/`gz`,
     * zusaetzlich `ax`/`ay`/`az`.
     *
     * MUSS **nach** [recordSet] desselben Satzes aufgerufen werden: die
     * Implementierung leitet den `setIndex` aus dem Zaehler der set-Events
     * ab, damit Satz-Event und Sample-Fenster ohne Absprache mit dem
     * Aufrufer denselben Index tragen. Ohne diesen Index kann der Harness
     * Samples nicht dem Satz zuordnen, dessen Wahrheit im Manifest steht —
     * und ein Corpus ohne Zuordnung ist wieder nur ein Protokoll.
     */
    suspend fun recordSamples(window: SampleWindow)

    suspend fun endSession()
}

/**
 * Ein Satzfenster in Sample-Zeit (Umbauplan 2026-09-04 Phase 0.2).
 *
 * Traegt bewusst **keinen** `setIndex`: den vergibt der Recorder aus dem
 * Zaehler der set-Events (siehe [ShadowSessionRecorder.recordSamples]). Ein
 * vom Aufrufer gesetzter Index waere eine zweite Wahrheit, die mit dem
 * set-Event auseinanderlaufen kann.
 */
data class SampleWindow(
    val exerciseId: Long,
    val measuredSampleRateHz: Double,
    val samples: List<SensorSample>,
    /**
     * Nachtrag Phase 0.7: das Profil, mit dem dieser Satz gezaehlt wurde.
     *
     * Ohne diese Werte ist ein Offline-Replay (Phase 6.2) sinnlos:
     * `rotationAxis` und `gyroBias` bestimmen, WELCHES Signal die Pipeline
     * ueberhaupt sieht, und sie sind pro Uebung und Geraet kalibriert — also
     * nicht sweepbar und aus keiner anderen Quelle rekonstruierbar. Ein
     * Replay ohne sie projiziert auf die Neutralachse und messe damit eine
     * Pipeline, die live nie gelaufen ist.
     *
     * null, wenn der Satz ohne Profil gezaehlt wurde. Dann ist er fuer
     * Sweeps unbrauchbar, und das steht dann auch so im Corpus.
     */
    val profile: CalibrationProfile?,
)

/**
 * One `"t":"set"` line from `SHADOW_DIFF_HARNESS_PLAN.md` Abschnitt 6.
 * [delta] is `predictedReps - confirmedReps`, the release-gate criterion
 * (D3/D4) — but only meaningful when [confirmedRepsEdited] is true.
 *
 * Hinweis zum Feld [shadowReps]: es traegt seit dem Entfernen der zweiten,
 * parallel mitlaufenden Engine denselben Wert wie [liveCountedReps]. Grund:
 * die "Shadow"-Engine war seit Umbauplan Punkt 1 mit identischer Achse,
 * identischem Bias und identischem Threshold konfiguriert wie die
 * Live-Engine — sie rechnete also dasselbe Ergebnis, nur ueber einen
 * anderen Zeitraum (durchgehend statt nur waehrend eines gezaehlten Sets).
 * Die Differenz war damit kein Erkenntnisgewinn, sondern ein Artefakt
 * unterschiedlicher Set-Grenzen. Das Feld bleibt im JSONL-Format, damit
 * bereits aufgezeichnete Sessions weiter lesbar sind.
 */
data class ShadowDiffEvent(
    val exerciseId: Long,
    val weightMilliKg: Long,
    val confirmedReps: Int,
    val confirmedRepsEdited: Boolean,
    val liveCountedReps: Int,
    val shadowReps: Int,
    /**
     * RC-17: Ablehnungsmechanismen des Sets (Enum-Name -> Anzahl). Damit
     * zerfallen "N Abweichungen" im Corpus in ihre Ursachen. Leer bei
     * Altaufrufern ohne Diagnose.
     */
    val rejectionCounts: Map<RepRejectionReason, Int> = emptyMap(),
    /**
     * RC-16: restliche Live-Diagnose des Sets (Rate, Gaps, ZUPT,
     * Filterframes). Der Offline-Harness stellt sie der Replay-Seite
     * gegenueber, um Pfad-Differenzen einzugrenzen. null bei Altaufrufern.
     */
    val diagnostics: SetDiagnostics? = null,
) {
    val delta: Int get() = shadowReps - confirmedReps

    /**
     * Manual JSONL encoding: fixed, small field set, not worth pulling
     * kotlinx-serialization into `:feature:workout` for one line type
     * (it is declared in the version catalog but used nowhere yet).
     *
     * RC-16: die Diagnosefelder kommen flach hinter `rejections` —
     * dieselben Namen, die `CorpusLoader`/`tools/shadow_harness.py`
     * lesen. Ohne Diagnose bleiben sie weg (Altformat bleibt gueltig).
     */
    fun toJsonLine(): String {
        val rejections =
            rejectionCounts.entries
                .filter { it.value > 0 }
                .sortedBy { it.key.ordinal }
                .joinToString(",") { "\"${it.key.name}\":${it.value}" }
        val diagnosticsJson =
            diagnostics?.let {
                ",\"framesProcessed\":${it.framesProcessed}" +
                    ",\"framesRejected\":${it.framesRejected}" +
                    ",\"gaps\":${it.largeGapCount}" +
                    ",\"zuptUpdates\":${it.zuptBiasUpdates}" +
                    ",\"zuptAborted\":${it.zuptAbortedPending}" +
                    ",\"rateHz\":${it.measuredSampleRateHz}"
            } ?: ""
        return "{\"t\":\"set\",\"exerciseId\":$exerciseId,\"weightMilliKg\":$weightMilliKg," +
            "\"confirmedReps\":$confirmedReps,\"confirmedRepsEdited\":$confirmedRepsEdited," +
            "\"liveCountedReps\":$liveCountedReps,\"shadowReps\":$shadowReps,\"delta\":$delta," +
            "\"rejections\":{$rejections}$diagnosticsJson}"
    }
}

/**
 * Placeholder binding for contexts without file I/O (Tests, Previews).
 * Keeps the call site in [TrainViewModel]
 * (com.dropsync.feature.workout.TrainViewModel) exercised without touching
 * Android storage APIs.
 */
class NoOpShadowSessionRecorder : ShadowSessionRecorder {
    override suspend fun startSession(sessionId: String) = Unit

    override suspend fun recordSet(event: ShadowDiffEvent) = Unit

    override suspend fun recordSamples(window: SampleWindow) = Unit

    override suspend fun endSession() = Unit
}
