#!/usr/bin/env python3
"""
tools/recofit_bootstrap.py

Umbauplan Punkt 9a: laedt den Microsoft RecoFit-Datensatz
(github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors) und
konvertiert die MATLAB-.mat-Dateien in unser JSONL-Schema (kompatibel zu
tools/shadow_harness.py).

RecoFit: 114 Teilnehmer, 146 Sessions, Rep-Zaehlung +/- 1 Rep in 93%,
Uebungs-Erkennung 96-99% (Morris et al. 2014).

Caveat: anderes Geraet, andere Sensorposition (Handgelenk statt M5Stick),
andere Uebungen. Ein gruener Lauf validiert die Harness-Mechanik, NICHT
die Hardware-Freigabe (siehe Umbauplan Punkt 9c).

Usage:
    python3 tools/recofit_bootstrap.py --input-dir data/recofit/mat/ \
        --output-dir tools/golden_shadow_corpus/recofit/

Benoetigt: scipy, numpy (pip install scipy numpy)
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import numpy as np
    import scipy.io as sio
except ImportError:
    print("Benoetigt: scipy, numpy. pip install scipy numpy", file=sys.stderr)
    sys.exit(1)

SAMPLE_RATE_HZ = 50
TS_STEP_MS = 1000 // SAMPLE_RATE_HZ  # 20 ms bei 50 Hz

# RecoFit-Feldnamen variieren zwischen Sessions; hier die ueblichen Kandidaten.
ACCEL_KEYS = ("dvs_ap", "dvs_av", "dvs_am", "accel", "acc")
GYRO_KEYS = ("dvs_gp", "dvs_gv", "dvs_gm", "gyro", "gyr")
LABEL_KEYS = ("lbls", "labels", "rep_labels", "activity")


def _first_existing(data: dict, keys, default=None):
    for key in keys:
        if key in data and data[key] is not None:
            return data[key]
    return default


def _to_xyz(arr):
    """Normalisiert ein Sensor-Array auf (N, 3)."""
    arr = np.asarray(arr, dtype=float)
    if arr.ndim == 1:
        arr = arr.reshape(-1, 3)
    if arr.shape[1] != 3:
        raise ValueError(f"erwartet (N,3), bekam {arr.shape}")
    return arr


def convert_mat_to_jsonl(mat_path: Path, output_dir: Path) -> None:
    data = sio.loadmat(mat_path)

    accel = _first_existing(data, ACCEL_KEYS)
    gyro = _first_existing(data, GYRO_KEYS)
    labels = _first_existing(data, LABEL_KEYS)

    if accel is None or gyro is None:
        print(f"WARN: {mat_path.name} hat kein accel/gyro "
              f"(gefunden: {[k for k in data if not k.startswith('__')][:10]})", file=sys.stderr)
        return

    accel = _to_xyz(accel)
    gyro = _to_xyz(gyro)
    n = min(len(accel), len(gyro))
    label_vec = None
    if labels is not None:
        label_vec = np.asarray(labels, dtype=float).ravel()
        if len(label_vec) != n:
            print(f"WARN: {mat_path.name}: Label-Laenge {len(label_vec)} != {n}, Labels ignoriert",
                  file=sys.stderr)
            label_vec = None

    session_id = mat_path.stem

    events = []
    for i in range(n):
        ax, ay, az = accel[i]
        gx, gy, gz = gyro[i]
        label = 0 if label_vec is None else int(label_vec[i])
        events.append({
            "t": "sample",
            "ts": i * TS_STEP_MS,
            "ax": float(ax), "ay": float(ay), "az": float(az),
            "gx": float(gx), "gy": float(gy), "gz": float(gz),
            "workoutState": "counting" if label > 0 else "idle",
        })

    output_path = output_dir / f"{session_id}.jsonl"
    with open(output_path, "w", encoding="utf-8") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")

    # Manifest fuer shadow_harness.py. RecoFit-Labels markieren Samples als
    # aktiv/ruhend; die Wahrheit "Reps pro Satz" laesst sich daraus nicht
    # zuverlaessig ableiten, deshalb known_active_reps nur als Platzhalter
    # (der Harness stuft solche Saetze als "no truth" ein, sofern das
    # Manifest keine Liste traegt).
    known_reps = int(np.sum(label_vec > 0)) if label_vec is not None else 0
    manifest = {
        "recording": f"{session_id}.jsonl",
        "exercise_id": "unknown_recofit",
        "scenario": "recofit_reference",
        "known_active_reps": [],
        "device": "RecoFit (wrist)",
        "notes": (f"Konvertiert aus RecoFit-Dataset, Handgelenk-Position. "
                  f"{known_reps} als aktiv markierte Samples (keine Satz-Wahrheit)."),
        "samples_recorded": True,
    }
    with open(output_dir / f"{session_id}.jsonl.meta.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"  {session_id}: {n} Samples, {known_reps} aktiv markierte Samples")


def main() -> int:
    parser = argparse.ArgumentParser(description="RecoFit-Dataset -> JSONL")
    parser.add_argument("--input-dir", required=True, help="RecoFit .mat Verzeichnis")
    parser.add_argument("--output-dir", required=True, help="Zielverzeichnis fuer JSONL")
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    mat_files = sorted(input_dir.glob("*.mat"))
    if not mat_files:
        print(f"Keine .mat-Dateien in {input_dir}", file=sys.stderr)
        return 1

    for mat_path in mat_files:
        print(f"Konvertiere: {mat_path.name}")
        convert_mat_to_jsonl(mat_path, output_dir)

    n_out = len(list(output_dir.glob("*.jsonl")))
    print(f"\nFertig. {n_out} JSONL-Dateien in {output_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
