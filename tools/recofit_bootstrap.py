#!/usr/bin/env python3
"""
tools/recofit_bootstrap.py

Umbauplan Punkt 9a/9c: konvertiert den Microsoft RecoFit-Datensatz
(github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors) in unser
JSONL-Schema (kompatibel zu tools/shadow_harness.py).

Reales RecoFit-Format (per Inspektion verifiziert):
  * exercise_data.50.0000_multionly.mat / _singleonly.mat
      subject_data: object-Array (nParticipants x 1) bzw.
      (nParticipants x nActivities). Jede Zelle ist ein MATLAB-Struct-Array
      (Shape (1, nVisits)); jedes Element ist eine Visit mit den Feldern:
        - data[0,0].accelDataMatrix  (N x 4: t[s], ax, ay, az in g)
        - data[0,0].gyroDataMatrix   (N x 4: t[s], gx, gy, gz in dps)
        - activityStartMatrix        (A x 7 object; je Zeile:
                                       name | start_s | end_s | notes |
                                       reps | ... | ...)
      Die activityStartMatrix enthaelt die echte Rep-Wahrheit (Spalte 5,
      Spalte 0: Uebungsname) - damit traegt das Manifest known_active_reps
      direkt aus dem Datensatz (D3-Regel des Harness-Plans).

WICHTIG: Der volle Datensatz ist ~3 GB. Das Skript laedt die .mat mit
variable_names-Filter (nur subject_data) und konvertiert standardmaessig
eine Stichprobe (--max-visits), nicht alles. Jede Visit hat ~42 min
(~128k Samples bei 50 Hz) -> die JSONL-Dateien sind bewusst NICHT im Repo,
das Ziel liegt ausserhalb von tools/golden_shadow_corpus.

Usage:
    python3 tools/recofit_bootstrap.py \
        --input-dir data/recofit/mat/ \
        --output-dir ../recofit_corpus/ \
        [--max-visits 10]

Benoetigt: scipy, numpy (pip install scipy numpy)
"""

import argparse
import ast
import json
import sys
from pathlib import Path

try:
    import numpy as np
    import scipy.io as sio
except ImportError:
    print("Benoetigt: scipy, numpy. pip install scipy numpy", file=sys.stderr)
    sys.exit(1)

# RecoFit zeichnet mit 50 Hz auf (load_exercise_data.m: "at 50Hz").
SAMPLE_RATE_HZ = 50

# loadmat filter: nur die grossen Variablen laden.
LOAD_VARS = ("subject_data",)

NON_EXERCISE = "non-exercise"


def _parse_matlab_scalar(cell):
    """Loest MATLAB-Skalare wie '[[-15.207]]' oder '[[20]]' zu Python-Werten."""
    s = str(cell).strip()
    try:
        value = ast.literal_eval(s)
    except (ValueError, SyntaxError):
        return None
    # Geschachtelte Listen [[x]] bzw. [x] bis zum Skalar abwickeln.
    while isinstance(value, (list, tuple)) and len(value) > 0:
        value = value[0]
    return value


def _parse_activity_name(cell):
    """Loest '[''Jumping Jacks'']' zu 'Jumping Jacks'."""
    s = str(cell).strip()
    try:
        value = ast.literal_eval(s)
    except (ValueError, SyntaxError):
        return s
    while isinstance(value, (list, tuple)) and len(value) > 0:
        value = value[0]
    return str(value)


def convert_mat(mat_path: Path, output_dir: Path, max_visits: int) -> int:
    print(f"Lade {mat_path.name} (kann Minuten dauern)...", file=sys.stderr)
    data = sio.loadmat(str(mat_path), variable_names=LOAD_VARS)

    subject_data = data.get("subject_data")
    if subject_data is None or subject_data.dtype != np.dtype("O"):
        print(f"FEHLER: {mat_path.name} hat kein subject_data-Zellfeld", file=sys.stderr)
        return 0

    n_rows, n_cols = subject_data.shape
    n_written = 0

    for row in range(n_rows):
        if n_written >= max_visits:
            break
        for col in range(n_cols):
            if n_written >= max_visits:
                break
            cell = subject_data[row, col]
            if cell is None:
                continue
            if not hasattr(cell, "size") or cell.size == 0:
                continue
            # Struct-Array: jedes Element ist eine Visit/Aufnahme.
            if cell.ndim == 0:
                records = [cell]
            else:
                records = list(cell.ravel())
            for recording in records:
                if n_written >= max_visits:
                    break
                try:
                    accel = np.asarray(
                        recording["data"][0, 0]["accelDataMatrix"], dtype=float
                    )
                    gyro = np.asarray(
                        recording["data"][0, 0]["gyroDataMatrix"], dtype=float
                    )
                except (KeyError, IndexError, ValueError, TypeError):
                    continue

                if accel.ndim != 2 or accel.shape[1] < 4 or gyro.ndim != 2 or gyro.shape[1] < 4:
                    continue
                n = min(len(accel), len(gyro))
                if n == 0:
                    continue

                # activityStartMatrix: Spalte 0 = Name, Spalte 1 = Start [s],
                # Spalte 2 = Ende [s], Spalte 4 = Reps.
                known_reps = []
                activity_names = []
                activity_windows = []
                try:
                    asm = recording["activityStartMatrix"]
                    asm_rows = np.asarray(asm, dtype=object)
                    for a_row in asm_rows:
                        if a_row is None or len(np.asarray(a_row, dtype=object).ravel()) < 5:
                            continue
                        flat = np.asarray(a_row, dtype=object).ravel()
                        name = _parse_activity_name(flat[0]).strip()
                        if name.lower() == NON_EXERCISE or name.startswith("Tap "):
                            continue
                        reps = _parse_matlab_scalar(flat[4])
                        start_s = _parse_matlab_scalar(flat[1])
                        end_s = _parse_matlab_scalar(flat[2])
                        if reps is None or start_s is None or end_s is None:
                            continue
                        try:
                            reps = int(reps)
                            start_s = float(start_s)
                            end_s = float(end_s)
                        except (ValueError, TypeError):
                            continue
                        if reps > 0 and end_s > start_s:
                            known_reps.append(reps)
                            activity_names.append(name)
                            activity_windows.append({
                                "name": name,
                                "startS": start_s,
                                "endS": end_s,
                                "reps": reps,
                            })
                except (KeyError, IndexError, ValueError, TypeError):
                    pass

                subject_idx = _parse_matlab_scalar(recording["subjectIndex"]) or (row + 1)
                session_id = f"recofit_p{int(subject_idx):03d}_v{int(n_written):03d}"

                # Fenster in Sample-Indizes (t[s] bei 50 Hz -> Index = round(t*50)).
                window_idxs = [
                    (round(w["startS"] * SAMPLE_RATE_HZ), round(w["endS"] * SAMPLE_RATE_HZ))
                    for w in activity_windows
                ]

                events = []
                for i in range(n):
                    state = "idle"
                    for (ws, we) in window_idxs:
                        if ws <= i < we:
                            state = "counting"
                            break
                    events.append({
                        "t": "sample",
                        "ts": i * (1000 // SAMPLE_RATE_HZ),
                        "ax": float(accel[i, 1]),
                        "ay": float(accel[i, 2]),
                        "az": float(accel[i, 3]),
                        "gx": float(gyro[i, 1]),
                        "gy": float(gyro[i, 2]),
                        "gz": float(gyro[i, 3]),
                        "workoutState": state,
                    })

                with open(output_dir / f"{session_id}.jsonl", "w", encoding="utf-8") as f:
                    for e in events:
                        f.write(json.dumps(e) + "\n")

                exercise_id = activity_names[0] if activity_names else "unknown_recofit"
                manifest = {
                    "recording": f"{session_id}.jsonl",
                    "exercise_id": exercise_id,
                    "scenario": "recofit_reference",
                    "known_active_reps": known_reps,
                    "device": "RecoFit (wrist, 50Hz)",
                    "notes": (f"Konvertiert aus {mat_path.name}, Participant "
                              f"{int(subject_idx)}. known_active_reps aus "
                              f"activityStartMatrix (Spalte 5). "
                              f"Aktivitaeten: {activity_names[:10]}"),
                    "samples_recorded": True,
                    "activity_windows": activity_windows,
                }
                with open(output_dir / f"{session_id}.jsonl.meta.json", "w", encoding="utf-8") as f:
                    json.dump(manifest, f, indent=2)

                n_written += 1
                print(f"  {session_id}: {n} Samples, {len(known_reps)} Saetze "
                      f"(reps: {known_reps[:8]}{'...' if len(known_reps) > 8 else ''})",
                      file=sys.stderr)

    return n_written


def main() -> int:
    parser = argparse.ArgumentParser(description="RecoFit-Dataset -> JSONL")
    parser.add_argument("--input-dir", required=True, help="RecoFit .mat Verzeichnis")
    parser.add_argument("--output-dir", required=True, help="Zielverzeichnis fuer JSONL")
    parser.add_argument("--max-visits", type=int, default=10,
                        help="maximale Anzahl konvertierter Visits (Default 10)")
    parser.add_argument("--single-only", action="store_true",
                        help="nur exercise_data.50.0000_singleonly.mat konvertieren")
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    mat_files = sorted(input_dir.glob("*.mat"))
    if not mat_files:
        print(f"Keine .mat-Dateien in {input_dir}", file=sys.stderr)
        return 1

    if args.single_only:
        mat_files = [p for p in mat_files if "singleonly" in p.name]
    else:
        mat_files = [p for p in mat_files if "multionly" in p.name]

    if not mat_files:
        print("Keine passenden .mat-Dateien (multionly/singleonly) gefunden", file=sys.stderr)
        return 1

    total = 0
    for mat_path in mat_files:
        total += convert_mat(mat_path, output_dir, args.max_visits - total)
        if total >= args.max_visits:
            break

    n_out = len(list(output_dir.glob("*.jsonl")))
    print(f"\nFertig. {n_out} JSONL-Dateien in {output_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
