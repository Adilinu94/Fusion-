# Golden Shadow Corpus

Hier landen die aufgenommenen JSONL-Sessions + Manifeste fuer den
Shadow-Diff-Harness (`tools/shadow_harness.py`, Design-Dokument
Abschnitt 11b, SHADOW_DIFF_HARNESS_PLAN.md Schritt 5).

## Aufbau

Pro Session zwei Dateien:

- `<session>.jsonl`          - Aufnahme aus `/Android/data/com.dropsync/files/recordings/`
- `<session>.jsonl.meta.json` - Manifest mit Szenario-Tag und Wahrheit

## Manifest-Schema

```json
{
  "recording": "2026-08-12_bizeps_curl_normal.jsonl",
  "exercise_id": "bicep_curl",
  "scenario": "calibrated",
  "known_active_reps": [12, 10, 8],
  "device": "M5StickC-Plus2",
  "notes": "Template aktiv, normales Tempo",
  "samples_recorded": false
}
```

`known_active_reps` ist die unabhaengige Wahrheit (ein Wert pro Satz, in
chronologischer Reihenfolge). Fehlt die Liste fuer einen Satz, wertet der
Harness ihn nur dann, wenn `confirmedRepsEdited=true` (D3-Regel).

## Abnahmekriterien (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 10)

- Exakte Uebereinstimmung shadow == truth (Toleranz 0 Reps)
- Mindestens 3 unabhaengige Sessions pro Szenario
- Szenarien aus Design-Dokument Abschnitt 11b:
  1. `calibrated`  - Template aktiv, normales Tempo
  2. `uncalibrated` - kein Profil, Neutralachse
  3. `wiggle`       - Alltagsbewegung, keine Reps (False-Positive-Test)
  4. `slow`         - langsame Reps (> 3 s Zykluszeit)
  5. `placement`    - Sensor verrutscht (Position geaendert)

## Auswertung

```bash
python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus
python3 tools/shadow_harness.py --smoke-test   # Harness-Mechanik ohne echte Daten
```

## Externe Referenz-Korpora

- `recofit/` - `python3 tools/recofit_bootstrap.py` (Handgelenk, anderes Geraet)
- `mmfit/`   - `python3 tools/mmfit_bootstrap.py` (Multi-Device, RGB-D-Referenz)

Ein gruener Lauf dort validiert die Harness-Mechanik, nicht die
Hardware-Freigabe.
