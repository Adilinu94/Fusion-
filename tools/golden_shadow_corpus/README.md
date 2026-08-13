# Golden Shadow Corpus

Hier landen die aufgenommenen JSONL-Sessions + Manifeste fuer den
Shadow-Diff-Harness (`tools/shadow_harness.py`, Design-Dokument
Abschnitt 11b, SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 8).

## Aufbau

Pro Session zwei Dateien:

- `<session>.jsonl`            - Aufnahme aus `/Android/data/com.dropsync/files/recordings/`
- `<session>.jsonl.meta.json`  - Manifest mit Szenario-Tag und Wahrheit

## Manifest-Schema

```json
{
  "recording": "2026-08-12_bizeps_curl_calibrated.jsonl",
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

Fuer Szenario `reconnect`: `reconnect_before_set` gibt an, vor welchem Satz
(0-basiert) der Reconnect passiert ist. Saetze davor duerfen dokumentierte
Abweichungen haben, erst ab diesem Satz gilt `delta = 0`.

## Abnahmekriterien (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 10)

- Exakte Uebereinstimmung shadow == truth (Toleranz 0 Reps)
- Mindestens 3 unabhaengige Sessions pro Szenario
- Exact-Match-Rate 100%, MAE 0

## Die 5 Freigabe-Szenarien (Abschnitt 11b / Plan Abschnitt 9)

| # | Szenario (11b) | Manifest-`scenario` | Erwartung |
|---|---|---|---|
| 1 | 5 Uebungen ohne Kalibrierungsprofil (`noTemplate=true`) | `no_template` | pro Satz `delta = 0` |
| 2 | 5 Uebungen nach echter Kalibrierung mit aktivem Template | `calibrated` | pro Satz `delta = 0` |
| 3 | Langsame Wiederholungen (exzentrisch vollstaendig) | `slow` | pro Satz `delta = 0` |
| 4 | Sensor-Reconnect mitten in der Session | `reconnect` | `delta = 0` ab Reconnect; davor dokumentierte Abweichungen erlaubt |
| 5 | Uebungswechsel mit verschiedenen Profilen in derselben Session | `exercise_switch` | `delta = 0` je Satz, kein Template-Uebersprechen |

## Aufnahme-Anleitung (Schritt fuer Schritt)

### Vorbereitung

1. **Debug-Build installieren** (der JSONL-Recorder ist im normalen Build
   aktiv, kein spezieller Schalter noetig).
2. **Chip verbinden:** Train-Tab -> Sensor verbinden. Warten bis der
   Zustand STREAMING zeigt (nicht nur CONNECTED).
3. **Kalibrierung:** Fuer Szenario 2, 3, 5 vorher die Kalibrierung in der
   App durchfuehren (Profil anlegen). Fuer Szenario 1 bewusst KEIN Profil
   verwenden.
4. **Rohdaten-Schalter:** nur bei kurzen Szenario-Laeufen aktivieren
   (Plan Abschnitt 8.1, der Recorder schreibt aktuell keine Rohdaten,
   sondern nur die Satz-Events).

### Aufnahme einer Session

1. Train-Tab oeffnen (startet eine neue Recording-Session automatisch).
2. Uebung auswaehlen und die Saetze normal trainieren. Pro Satz:
   Gewicht + Reps wie ueblich bestaetigen.
3. **Direkt nach jedem Satz** die von Hand gezaehlte Rep-Zahl notieren
   (nicht Tage spaeter - Lehre aus dem leeren golden_csv_corpus).
4. Session beenden: Trainingsende / Uebung abschliessen. Die App schreibt
   `<session>.jsonl` nach `/Android/data/com.dropsync/files/recordings/`.

### Transfer + Manifest

1. JSONL per USB oder Dateimanager (z. B. `adb pull
   /sdcard/Android/data/com.dropsync/files/recordings/`) nach
   `tools/golden_shadow_corpus/` kopieren.
2. Manifest daneben anlegen: `<name>.jsonl.meta.json` mit
   `scenario` aus der Tabelle oben und `known_active_reps` (deine
   Handnotizen, chronologisch, ein Wert pro Satz).
3. Fuer Szenario 4 (Reconnect): `reconnect_before_set` im Manifest auf den
   Satz-Index setzen, AB dem der Reconnect stattgefunden hat; in `notes`
   den Reconnect-Zeitpunkt festhalten (z. B. "Reconnect vor Satz 3").
   Saetze davor duerfen dokumentierte Abweichungen haben.

### Auswertung

```bash
python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus
python3 tools/shadow_harness.py --smoke-test   # Harness-Mechanik ohne echte Daten
```

Report-Output in `docs/STATUS_FORTSCHRITT.md` (Freigabe-Protokoll)
anhaengen, sobald das Gate 11b geprueft wird.

## Externe Referenz-Korpora

- `recofit/` - `python3 tools/recofit_bootstrap.py` (Handgelenk, anderes Geraet)
- `mmfit/`   - `python3 tools/mmfit_bootstrap.py` (Multi-Device, RGB-D-Referenz)

Ein gruener Lauf dort validiert die Harness-Mechanik, nicht die
Hardware-Freigabe.
