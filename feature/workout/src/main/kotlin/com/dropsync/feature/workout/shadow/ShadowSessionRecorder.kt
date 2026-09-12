package com.dropsync.feature.workout.shadow

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.SensorSample

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
    fun startSession(sessionId: String)

    fun recordSet(event: ShadowDiffEvent)

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
    fun recordSamples(window: SampleWindow)

    fun endSession()
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
) {
    val delta: Int get() = shadowReps - confirmedReps

    /**
     * Manual JSONL encoding: fixed, small field set, not worth pulling
     * kotlinx-serialization into `:feature:workout` for one line type
     * (it is declared in the version catalog but used nowhere yet).
     */
    fun toJsonLine(): String =
        "{\"t\":\"set\",\"exerciseId\":$exerciseId,\"weightMilliKg\":$weightMilliKg," +
            "\"confirmedReps\":$confirmedReps,\"confirmedRepsEdited\":$confirmedRepsEdited," +
            "\"liveCountedReps\":$liveCountedReps,\"shadowReps\":$shadowReps,\"delta\":$delta}"
}

/**
 * Placeholder binding for contexts without file I/O (Tests, Previews).
 * Keeps the call site in [TrainViewModel]
 * (com.dropsync.feature.workout.TrainViewModel) exercised without touching
 * Android storage APIs.
 */
class NoOpShadowSessionRecorder : ShadowSessionRecorder {
    override fun startSession(sessionId: String) = Unit

    override fun recordSet(event: ShadowDiffEvent) = Unit

    override fun recordSamples(window: SampleWindow) = Unit

    override fun endSession() = Unit
}
