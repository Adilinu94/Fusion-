#!/usr/bin/env python3
"""
tools/shadow_harness.py

Shadow-Diff-Harness (SHADOW_DIFF_HARNESS_PLAN.md Schritt 4, Umbauplan
Punkt 7b): liest JSONL-Aufnahmen plus Manifeste aus einem Corpus-Verzeichnis
und produziert einen PASS/FAIL-Report pro Szenario (Design-Dokument
Abschnitt 11b).

Wahrheits-Prioritaet (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 7):
  1. manifest.known_active_reps[i] (unabhaengige Wahrheit)
  2. confirmedReps, wenn confirmedRepsEdited=true
  3. sonst: kein Urteil fuer diesen Satz (pass=None)

Usage:
    python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus
    python3 tools/shadow_harness.py --smoke-test          # synthetische Fixtures
"""

import json
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

try:
    import numpy as np
except ImportError:
    np = None  # Referenz-Korpora brauchen numpy; App-Korpora nicht.

# Abnahmekriterien (zentral, SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 10)
DELTA_TOLERANCE_REPS = 0          # exakte Uebereinstimmung
MIN_SESSIONS_PER_SCENARIO = 3     # mehrere unabhaengige Sessions
EXACT_MATCH_RATE_MIN = 1.0        # bei Toleranz 0
MAE_MAX = 0.0                     # bei Toleranz 0

# Externe Referenz-Korpora (RecoFit/MM-Fit, Umbauplan Punkt 9): anderes
# Geraet, andere Sensorposition, einfache Referenz-Zaehlung statt der
# App-DSP-Pipeline. Hier wird die Harness-MECHANIK validiert, nicht die
# Zaehlgenauigkeit - daher nur: Daten wurden geladen, Fenster gefunden,
# Referenz-Zaehlung > 0 fuer Fenster mit Wahrheit > 0 (KEIN Exact-Match).
REFERENCE_SCENARIO_PREFIXES = ("recofit_reference", "mmfit_reference")


def parse_jsonl(path: Path) -> list:
    lines = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            lines.append(json.loads(line))
    return lines


def parse_manifest(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _count_gyro_peaks(samples, start_s, end_s) -> int:
    """
    Einfache Referenz-Peak-Zaehlung fuer externe Korpora (RecoFit/MM-Fit):
    Gyro-Magnitude |omega| innerhalb eines Activity-Fensters, lokale Maxima
    ueber `median + 1.5 * mad` zaehlen. Kein Anspruch auf DSP-Paritaet mit
    der App-Pipeline - validiert die Harness-Mechanik, nicht die
    Hardware-Freigabe (Umbauplan Punkt 9).
    """
    if np is None:
        return -1  # Signal: numpy fehlt, Szenario nicht auswertbar.
    windowed = [s for s in samples if start_s <= s.get("ts", 0) / 1000.0 < end_s]
    if len(windowed) < 10:
        return 0
    mags = [
        (s.get("gx", 0.0) ** 2 + s.get("gy", 0.0) ** 2 + s.get("gz", 0.0) ** 2) ** 0.5
        for s in windowed
    ]
    arr = np.asarray(mags, dtype=float)
    median = float(np.median(arr))
    mad = float(np.median(np.abs(arr - median)))
    threshold = median + 1.5 * mad
    if threshold <= 0:
        return 0
    peaks = 0
    prev_above = False
    for m in arr:
        above = m > threshold
        if above and not prev_above:
            peaks += 1
        prev_above = above
    return peaks


def evaluate_session(jsonl_path: Path, manifest: dict) -> dict:
    events = parse_jsonl(jsonl_path)
    scenario = manifest.get("scenario", "unknown")
    known_active_reps = manifest.get("known_active_reps", [])

    set_events = [e for e in events if e.get("t") == "set"]

    # Referenz-Pfad fuer externe Korpora: keine set-Events, sondern
    # activity_windows mit Rep-Wahrheit. Die Referenz-Zaehlung (Gyro-Peaks)
    # steht stellvertretend fuer den Shadow-Zaehler der App.
    activity_windows = manifest.get("activity_windows")
    if not set_events and activity_windows:
        sample_events = [e for e in events if e.get("t") == "sample"]
        results = []
        for i, window in enumerate(activity_windows):
            truth = int(window.get("reps", 0))
            counted = _count_gyro_peaks(
                sample_events,
                float(window.get("startS", 0.0)),
                float(window.get("endS", 0.0)),
            )
            if counted < 0:
                # numpy fehlt -> nicht auswertbar, kein Urteil.
                results.append({
                    "set_index": i,
                    "delta": None,
                    "truth": truth,
                    "truth_source": "activity_windows (RecoFit/MM-Fit Referenz)",
                    "pass": None,
                })
                continue
            results.append({
                "set_index": i,
                "delta": counted - truth,
                "truth": truth,
                "counted": counted,
                "truth_source": "activity_windows (RecoFit/MM-Fit Referenz)",
                # Mechanik-Check: Zaehlung lief (>= 0) und Fenster mit
                # Wahrheit > 0 fanden Peaks. Kein Exact-Match - die naive
                # Peak-Zaehlung ist NICHT die App-Pipeline.
                "pass": counted >= 0 and (truth <= 0 or counted > 0),
            })
        return {
            "scenario": scenario,
            "session": jsonl_path.stem,
            "exercise_id": manifest.get("exercise_id", "unknown"),
            "results": results,
            "n_sets": len(activity_windows),
            "n_passed": sum(1 for r in results if r["pass"] is True),
            "n_failed": sum(1 for r in results if r["pass"] is False),
            "n_no_truth": sum(1 for r in results if r["pass"] is None),
        }

    results = []
    # Reconnect-Szenario (Plan Abschnitt 9): Saetze VOR dem Reconnect duerfen
    # dokumentierte Abweichungen haben; erst ab dem Reconnect gilt delta=0.
    reconnect_before_set = manifest.get("reconnect_before_set", -1)
    for i, set_event in enumerate(set_events):
        confirmed = set_event.get("confirmedReps", 0)
        shadow = set_event.get("shadowReps", 0)
        edited = set_event.get("confirmedRepsEdited", False)

        # Wahrheits-Prioritaet (Abschnitt 7)
        if i < len(known_active_reps):
            truth = known_active_reps[i]
            truth_source = "known_active_reps"
        elif edited:
            truth = confirmed
            truth_source = "confirmedReps (edited)"
        else:
            results.append({
                "set_index": i,
                "delta": shadow - confirmed,
                "truth": None,
                "truth_source": "none",
                "pass": None,
            })
            continue

        delta = shadow - truth
        if scenario == "reconnect" and reconnect_before_set >= 0 and i < reconnect_before_set:
            # Vor dem Reconnect: Abweichung dokumentiert erlaubt.
            results.append({
                "set_index": i,
                "delta": delta,
                "truth": truth,
                "truth_source": truth_source,
                "pass": True,
                "note": "vor Reconnect, Abweichung erlaubt",
            })
            continue

        results.append({
            "set_index": i,
            "delta": delta,
            "truth": truth,
            "truth_source": truth_source,
            "pass": abs(delta) <= DELTA_TOLERANCE_REPS,
        })

    return {
        "scenario": scenario,
        "session": jsonl_path.stem,
        "exercise_id": manifest.get("exercise_id", "unknown"),
        "results": results,
        "n_sets": len(set_events),
        "n_passed": sum(1 for r in results if r["pass"] is True),
        "n_failed": sum(1 for r in results if r["pass"] is False),
        "n_no_truth": sum(1 for r in results if r["pass"] is None),
    }


def run_report(corpus_path: Path) -> bool:
    sessions = defaultdict(list)

    for meta_path in sorted(corpus_path.glob("*.jsonl.meta.json")):
        # "<name>.jsonl.meta.json" -> "<name>.jsonl"
        stem = meta_path.name
        if stem.endswith(".jsonl.meta.json"):
            stem = stem[: -len(".jsonl.meta.json")] + ".jsonl"
        jsonl_path = corpus_path / stem
        if not jsonl_path.exists():
            print(f"WARN: keine JSONL zu {meta_path.name}", file=sys.stderr)
            continue

        manifest = parse_manifest(meta_path)
        result = evaluate_session(jsonl_path, manifest)
        sessions[result["scenario"]].append(result)

    if not sessions:
        print("FEHLER: keine Sessions im Corpus gefunden", file=sys.stderr)
        return False

    all_pass = True
    print("=" * 60)
    print("SHADOW-DIFF-HARNESS REPORT")
    print("=" * 60)

    for scenario, scenario_sessions in sorted(sessions.items()):
        print(f"\n--- Szenario: {scenario} ---")
        print(f"  Sessions: {len(scenario_sessions)}")
        is_reference = scenario.startswith(REFERENCE_SCENARIO_PREFIXES)

        all_deltas = []
        total_sets = 0
        total_passed = 0
        total_failed = 0

        for sess in scenario_sessions:
            print(f"  {sess['session']} (exercise={sess['exercise_id']}): "
                  f"{sess['n_passed']}/{sess['n_sets']} passed, "
                  f"{sess['n_failed']} failed, {sess['n_no_truth']} no truth")
            for r in sess["results"]:
                if r["pass"] is not None:
                    all_deltas.append(r["delta"])
            total_sets += sess["n_sets"]
            total_passed += sess["n_passed"]
            total_failed += sess["n_failed"]

        if total_sets > 0:
            mae = sum(abs(d) for d in all_deltas if d is not None) / max(1, len([d for d in all_deltas if d is not None]))
            exact_match = total_passed / total_sets if total_sets > 0 else 0
            bias = sum(d for d in all_deltas if d is not None) / max(1, len([d for d in all_deltas if d is not None]))

            if is_reference:
                print(f"  Fenster: {total_sets}, Zaehlung gelaufen: {total_passed + total_failed}, "
                      f"davon Peaks gefunden: {total_passed}")
                print(f"  Bias (Referenz-Zaehlung vs. Wahrheit): {bias:+.2f} (nur informativ)")
                print(f"  Sessions: {len(scenario_sessions)} (min: 1)")
            else:
                print(f"  Exact-Match: {exact_match:.1%} (benoetigt: {EXACT_MATCH_RATE_MIN:.0%})")
                print(f"  MAE: {mae:.2f} (max: {MAE_MAX})")
                print(f"  Bias: {bias:+.2f}")
                print(f"  Sessions: {len(scenario_sessions)} (min: {MIN_SESSIONS_PER_SCENARIO})")

            if is_reference:
                # Referenz-Korpora (Umbauplan Punkt 9): die Harness-Mechanik
                # wird validiert - Daten geladen, Fenster gefunden, Zaehlung
                # gelaufen. Exact-Match waere eine falsche Abnahme fuer ein
                # fremdes Geraet mit fremder Sensorposition.
                scenario_pass = (
                    len(scenario_sessions) >= 1
                    and total_failed == 0
                    and any(r["truth"] is not None and r["pass"] is not None
                            for s in scenario_sessions for r in s["results"])
                )
                print("  (Referenz-Korpus: Mechanik-Check, kein Exact-Match-Kriterium)")
            else:
                scenario_pass = (
                    len(scenario_sessions) >= MIN_SESSIONS_PER_SCENARIO
                    and exact_match >= EXACT_MATCH_RATE_MIN
                    and mae <= MAE_MAX
                )
            all_pass = all_pass and scenario_pass
            print(f"  => {'PASS' if scenario_pass else 'FAIL'}")
        else:
            all_pass = False
            print("  => FAIL (keine Saetze im Szenario)")

    print("\n" + "=" * 60)
    print(f"GESAMT: {'PASS' if all_pass else 'FAIL'}")
    print("=" * 60)
    return all_pass


def _make_set_event(confirmed, shadow, edited):
    return {
        "t": "set",
        "exerciseId": 1,
        "weightMilliKg": 20000000,
        "confirmedReps": confirmed,
        "confirmedRepsEdited": edited,
        "liveCountedReps": confirmed,
        "shadowReps": shadow,
        "delta": shadow - confirmed,
    }


def smoke_test() -> bool:
    """Erzeugt synthetische Fixtures und prueft die Harness-Mechanik."""
    with tempfile.TemporaryDirectory() as tmp:
        corpus = Path(tmp)

        # Szenario "calibrated": 3 Sessions, alle exakt -> PASS.
        for n in range(3):
            session = f"smoke_calibrated_{n}"
            with open(corpus / f"{session}.jsonl", "w", encoding="utf-8") as f:
                f.write(json.dumps({"t": "session_start", "sessionId": session}) + "\n")
                f.write(json.dumps(_make_set_event(12, 12, True)) + "\n")
                f.write(json.dumps(_make_set_event(10, 10, True)) + "\n")
                f.write(json.dumps({"t": "session_end", "sessionId": session}) + "\n")
            with open(corpus / f"{session}.jsonl.meta.json", "w", encoding="utf-8") as f:
                json.dump({
                    "recording": f"{session}.jsonl",
                    "exercise_id": "bicep_curl",
                    "scenario": "calibrated",
                    "known_active_reps": [12, 10],
                    "device": "smoke",
                }, f, indent=2)

        print(f"SMOKE TEST: synthetische Fixtures in {corpus}")
        ok = run_report(corpus)

        # no_truth-Pfad separat pruefen: ein Satz ohne Wahrheit
        # (known_active_reps leer, confirmedRepsEdited=false) muss
        # pass=None ergeben und darf nicht als Urteil zaehlen.
        session = "smoke_no_truth"
        jsonl = corpus / f"{session}.jsonl"
        meta = corpus / f"{session}.jsonl.meta.json"
        with open(jsonl, "w", encoding="utf-8") as f:
            f.write(json.dumps({"t": "session_start", "sessionId": session}) + "\n")
            f.write(json.dumps(_make_set_event(8, 9, False)) + "\n")
            f.write(json.dumps({"t": "session_end", "sessionId": session}) + "\n")
        with open(meta, "w", encoding="utf-8") as f:
            json.dump({
                "recording": f"{session}.jsonl",
                "exercise_id": "bicep_curl",
                "scenario": "no_truth_check",
                "known_active_reps": [],
                "device": "smoke",
            }, f, indent=2)
        result = evaluate_session(jsonl, parse_manifest(meta))
        assert len(result["results"]) == 1, result
        assert result["results"][0]["pass"] is None, result
        assert result["n_no_truth"] == 1, result
        print("SMOKE TEST: no_truth-Pfad liefert pass=None wie erwartet")
        return ok


def main() -> int:
    corpus_dir = None

    for arg in sys.argv[1:]:
        if arg == "--smoke-test":
            return 0 if smoke_test() else 1
        if arg.startswith("--corpus-dir="):
            corpus_dir = arg.split("=", 1)[1]
        elif arg == "--corpus-dir":
            idx = sys.argv.index(arg)
            if idx + 1 < len(sys.argv):
                corpus_dir = sys.argv[idx + 1]

    if not corpus_dir:
        print("Usage: shadow_harness.py --corpus-dir <path> [--smoke-test]", file=sys.stderr)
        return 1

    corpus_path = Path(corpus_dir)
    if not corpus_path.exists():
        print(f"Corpus nicht gefunden: {corpus_path}", file=sys.stderr)
        return 1

    return 0 if run_report(corpus_path) else 1


if __name__ == "__main__":
    sys.exit(main())
