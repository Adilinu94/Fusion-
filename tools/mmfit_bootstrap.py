#!/usr/bin/env python3
"""
tools/mmfit_bootstrap.py

Umbauplan Punkt 9b: konvertiert das MM-Fit-Dataset (Strathclyde, 2020;
Multi-Device-IMU + RGB-D-Referenz) in unser JSONL-Schema.

MM-Fit hat CSV/JSON-Dateien pro Session mit IMU-Daten von mehreren
Sensoren gleichzeitig (wrist, ankle, hip, arm, ...). Dieses Skript ist
generisch: es akzeptiert

  * CSV mit Spalten wie ax,ay,az,gx,gy,gz (+ optional ts, device, label)
  * JSON/JSONL mit gleichnamigen Schluesseln pro Zeile/Objekt

und schreibt pro Quelldatei eine JSONL im Schema von shadow_harness.py.
Die Rep-Wahrheit (known_active_reps) traegt MM-Fit nicht direkt; das
Manifest laesst sie leer (Harness stuft Saetze dann als "no truth" ein),
die RGB-D-Referenz muss ausserhalb dieses Skripts annotiert werden.

Usage:
    python3 tools/mmfit_bootstrap.py --input-dir data/mmfit/ \
        --output-dir tools/golden_shadow_corpus/mmfit/ \
        [--label-col label]
"""

import argparse
import csv
import json
import sys
from pathlib import Path

SENSOR_COLS = ("ax", "ay", "az", "gx", "gy", "gz")
TS_STEP_MS = 20  # 50 Hz


def _parse_csv(path: Path) -> list:
    with open(path, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            raise ValueError(f"{path.name}: keine CSV-Spalten")
        rows = []
        for row in reader:
            rows.append({k: row.get(k) for k in SENSOR_COLS})
        return rows


def _parse_json(path: Path) -> list:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read().strip()
    if text.startswith("["):
        data = json.loads(text)
    else:  # JSONL
        data = [json.loads(line) for line in text.splitlines() if line.strip()]
    rows = []
    for obj in data:
        rows.append({k: obj.get(k) for k in SENSOR_COLS})
    return rows


def convert_file(path: Path, output_dir: Path, label_col: str) -> None:
    try:
        if path.suffix.lower() == ".csv":
            rows = _parse_csv(path)
        elif path.suffix.lower() in (".json", ".jsonl"):
            rows = _parse_json(path)
        else:
            print(f"WARN: ueberspringe {path.name} (nicht .csv/.json/.jsonl)", file=sys.stderr)
            return
    except Exception as exc:
        print(f"WARN: {path.name} fehlgeschlagen: {exc}", file=sys.stderr)
        return

    events = []
    usable = 0
    for i, row in enumerate(rows):
        try:
            vals = [float(row.get(k)) for k in SENSOR_COLS]
        except (TypeError, ValueError):
            continue
        ax, ay, az, gx, gy, gz = vals
        events.append({
            "t": "sample",
            "ts": i * TS_STEP_MS,
            "ax": ax, "ay": ay, "az": az,
            "gx": gx, "gy": gy, "gz": gz,
            "workoutState": "idle",
        })
        usable += 1

    if usable == 0:
        print(f"WARN: {path.name} enthaelt keine verwertbaren Zeilen", file=sys.stderr)
        return

    session_id = path.stem
    output_path = output_dir / f"{session_id}.jsonl"
    with open(output_path, "w", encoding="utf-8") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")

    manifest = {
        "recording": f"{session_id}.jsonl",
        "exercise_id": "unknown_mmfit",
        "scenario": "mmfit_reference",
        "known_active_reps": [],
        "device": f"MM-Fit ({session_id})",
        "notes": "Konvertiert aus MM-Fit, keine Satz-Wahrheit im Manifest "
                 "(RGB-D-Referenz separat annotieren).",
        "samples_recorded": True,
    }
    with open(output_dir / f"{session_id}.jsonl.meta.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)

    print(f"  {session_id}: {usable} Samples")


def main() -> int:
    parser = argparse.ArgumentParser(description="MM-Fit -> JSONL")
    parser.add_argument("--input-dir", required=True, help="MM-Fit CSV/JSON Verzeichnis")
    parser.add_argument("--output-dir", required=True, help="Zielverzeichnis fuer JSONL")
    parser.add_argument("--label-col", default=None,
                        help="optionaler Label-Spaltenname (wird derzeit nur geparst, nicht ausgewertet)")
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    files = sorted(
        [p for p in input_dir.iterdir() if p.suffix.lower() in (".csv", ".json", ".jsonl")]
    )
    if not files:
        print(f"Keine .csv/.json/.jsonl-Dateien in {input_dir}", file=sys.stderr)
        return 1

    for path in files:
        print(f"Konvertiere: {path.name}")
        convert_file(path, output_dir, args.label_col)

    n_out = len(list(output_dir.glob("*.jsonl")))
    print(f"\nFertig. {n_out} JSONL-Dateien in {output_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
