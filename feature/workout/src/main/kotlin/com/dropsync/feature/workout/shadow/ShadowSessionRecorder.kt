package com.dropsync.feature.workout.shadow

/**
 * Shadow-vs-confirmed diff recording (`docs/design/SHADOW_DIFF_HARNESS_PLAN.md`,
 * D2/D3/D4; ADR-0014).
 *
 * D2: every logged set is recorded — counters/events always, raw sensor
 * samples are out of scope for this first slice (Plan Abschnitt 4.1 Punkt 5,
 * still deferred, see Testinfrastruktur-Plan).
 * D3 (Ground-Truth-Regel): [ShadowDiffEvent.confirmedRepsEdited] decides
 * whether [ShadowDiffEvent.confirmedReps] may count as independent truth in
 * the eventual harness evaluation — an unedited pre-filled value never does.
 *
 * This interface only captures the event. Persisting it as JSONL under
 * `/Android/data/<pkg>/files/recordings/` (Plan Abschnitt 6) is Schritt 2/3
 * of the plan and needs a real device/Robolectric run to verify the
 * Android-side file I/O — not attempted in this slice, see
 * [NoOpShadowSessionRecorder].
 */
interface ShadowSessionRecorder {
    fun recordSet(event: ShadowDiffEvent)
}

/**
 * One `"t":"set"` line from `SHADOW_DIFF_HARNESS_PLAN.md` Abschnitt 6.
 * [delta] is `shadowReps - confirmedReps`, the release-gate criterion
 * (D3/D4) — but only meaningful when [confirmedRepsEdited] is true.
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
 * Placeholder binding until the real JSONL-writing recorder (Plan Schritt
 * 2/3) lands with verified file I/O. Keeps the call site in [TrainViewModel]
 * (com.dropsync.feature.workout.TrainViewModel) exercised by tests now,
 * without committing to unverified Android storage APIs in this slice.
 */
class NoOpShadowSessionRecorder : ShadowSessionRecorder {
    override fun recordSet(event: ShadowDiffEvent) = Unit
}
