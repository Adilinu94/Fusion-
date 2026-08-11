# Shadow-vs-Live Diff Harness — Lösungsplan (Design-Dokument)

**Datum:** 2026-08-10
**Status:** Entwurf / zur Freigabe
**Zweck:** Automatisierte Vergleichs-/Diff-Logik zwischen Shadow- und Live-Zählung
schließen (Design-Dokument Abschnitt 11b, offener Punkt aus
`docs/STATUS_FORTSCHRITT.md` B.3). Macht die fünf Freigabe-Szenarien aus
Abschnitt 11b objektiv prüfbar, statt auf Logcat-Zeilen zu vertrauen.

**Referenzen:**
- `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` → Abschnitt 11b (Shadow-DoD), Phase 4 Schritt 6
- `docs/STATUS_FORTSCHRITT.md` → B.3 (offener Punkt)
- `docs/archive/flowrep-import/docs/design/DIRECTIONAL_GP_SHADOW_ROLLOUT_2026-07-27.md` → Vorbild für Shadow-Gates
- FlowRep-Referenz (ausserhalb der Fusion): `app/lib/domain/metrics/shadow_report.dart` (FR-B12, JSONL), `app/lib/data/repositories/csv_session_recorder.dart`, `tools/golden_csv_harness.py` (Auswertungs-Harness)

---

## 1. Ausgangslage und Problem

### 1.1 Was Abschnitt 11b fordert

Die neue Zähl-Pipeline (`ExerciseEnginePipeline`/`PeakDetector`/`PhaseValidator`/
`TemplateMatcher`) läuft seit Phase 4 im Shadow-Modus mit. Vor der Freigabe als
live zählende Methode (`_useNewPipeline`-Äquivalent = true) verlangt das Design
fünf Freigabe-Szenarien auf echter Hardware, in denen **Rep-Diff = 0 gegenüber
der Legacy-Engine** über mehrere unabhängige Sessions gezeigt wird.

### 1.2 Der offene Punkt (B.3)

> Es gibt aktuell keine automatisierte Vergleichs-/Diff-Logik zwischen Shadow-
> und Live-Zählung — nur die CSV-Aufnahme (`csv_session_recorder.dart`) existiert,
> keine Auswertung darauf. Muss vor oder während der Szenario-Läufe gebaut werden,
> sonst lassen sich die Kriterien oben nicht objektiv prüfen.

### 1.3 Ist-Zustand in der Fusion

- `TrainViewModel` führt die Shadow-Pipeline neben der Live-Zählung.
- Der einzige Vergleich ist `logShadowDiff(reason)` → `Log.d("FlowRepShadow",
  "diff(...): shadow=$shadowRepCount live=$liveRepCount")` (TrainViewModel.kt:562).
- **Logcat-only**: keine strukturierte Aufzeichnung, keine Persistenz, keine
  Auswertung. Nach dem Verbinden/Neustart sind die Daten weg.

### 1.4 Wichtige Erkenntnis aus der Ist-Analyse

Abschnitt 11b spricht vom Rep-Diff gegenüber der **Legacy-Engine**. In der
Fusion existiert die Legacy-Engine **nicht mehr** — es gibt nur noch die neue
Pipeline, die bereits live zählt (Live-Engine beim `startCountedSet`) und
gleichzeitig als Shadow läuft. Der reale, aussagekräftige Diff ist daher der gegenüber den **vom Nutzer
bestätigten Reps** (`liveRepCount += reps` in `logSet`). Diese bestätigten Reps
sind die beste verfügbare Ground-Truth — exakt das Analogon zu `correctedReps`
in FlowRep, das die dortige Harness bereits als Wahrheit nutzt.

**Wichtige Präzisierung:** Eine bestätigte Rep-Zahl ist nur dann unabhängige
Wahrheit, wenn der Nutzer den von der App vorbefüllten Wert **aktiv editiert**
hat. Lässt er den vorbefüllten Wert unverändert stehen, ist er nicht
unabhängig — er ist nur die Zahl, die die App selbst berechnet hat. Für die
Freigabe-Szenarien (Abschnitt 9) ist daher eine aktive Nutzer-Korrektur
Pflicht; unbestätigte `confirmedReps` dienen nur der Diagnose.

**Konsequenz für das Design:** Der Diff ist `shadow ↔ bestätigte Reps`. Der
Schatten-Vergleich `shadow ↔ liveEngine` wird zusätzlich aufgezeichnet, ist aber
nur diagnostisch (eine Pipeline im Selbstvergleich ist kein Freigabe-Kriterium).

---

## 2. Referenz: Was FlowRep bereits gelöst hat

Bevor ein eigenes Format erfunden wird, wird das validierte FlowRep-Muster
übernommen und portiert:

### 2.1 `ShadowReportLine` (FR-B12) — einheitliches Shadow-Report-Format

```json
{"ts":"...ISO...","source":"new_pipeline","liveReps":12,"shadowReps":12,
 "delta":0,"liveSignal":1.2,"shadowSignal":1.2,"note":"..."}
```

- Pro Zeile ein Diff-Zustand, `delta = shadowReps - liveReps`.
- `source` unterscheidet Shadow-Quellen (magnitude, new_pipeline, ml_suggest) —
  in der Fusion heute nur `new_pipeline`, später erweiterbar.
- In-Memory-Ring (Capacity 200) + `exportJsonl()`.
- **Lehre:** das Format ist schlank, maschinenlesbar und mit dem Diff pro Zeile
  direkt auswertbar. Wird als JSONL-Schema übernommen und um Satz-/Szenario-
  Kontext erweitert.

### 2.2 `CsvSessionRecorder` — unabhängiger Aufnahme-Listener

- Zweiter, unabhängiger Listener am selben `samples`-Stream; eigene
  `SignalProcessor`-Instanz; ruft nie die Engine auf → kann das Zählverhalten
  nicht beeinflussen.
- Schreibt in app-specific external Storage (`/Android/data/<pkg>/files/
  recordings/`), keine Runtime-Berechtigung nötig, per USB/Dateimanager erreichbar.
- Spalten: `timestamp_ms,accel_x_g,accel_y_g,accel_z_g,gyro_x_dps,gyro_y_dps,
  gyro_z_dps,dyn_magnitude,workout_state`.

### 2.3 `golden_csv_harness.py` — Auswertungs-Harness (das zentrale Muster)

Zwei unabhängige Datenquellen:

1. **Session-Exports** (`flowrep_export_*.json`): `countedReps` (was die App
   live zählte) vs. `correctedReps` (was der Nutzer bestätigte). Direkteste
   App-vs-Manuell-Quelle.
2. **Roh-CSV + Mini-Manifest** (`<name>.csv.meta.json`): `known_active_reps`
   pro `active`-Fenster + Szenario-Tag (`normal`/`wiggle`/`slow`/
   `placement_variant`), `gp_profile` optional. Wird durch die Referenz-Engine
   offline wiedergegeben.

Zentrale Abnahmekriterien (eine Stelle, nicht verstreut):
- App-vs-Manuell: Exact-Match ≥ 85 %, MAE ≤ 0.5
- Replay (combined + g_p): Toleranz = 0 Reps pro Fenster

Report mit PASS/FAIL pro Kategorie + Gesamt-PASS/FAIL und Exit-Code (1 bei FAIL).
Smoke-Test-Modus mit synthetischen Fixtures (ersetzt keine echte Validierung).

**Lehren, die in den Plan eingehen:**
- Die Auswertung gehört **ausserhalb der App** (Python), nicht in einen
  Android-Test: sie läuft gegen kuratierte Aufnahmen und ist CI-fähig.
- **Kritische Einsicht aus flowrep-clone:** `tools/golden_csv_corpus/` ist dort
  bis heute **leer** — Infrastruktur war da, aber nie wurden echte Aufnahmen
  kuratiert. Das Design muss deshalb den **Kurations-Workflow** als Erstklass-
  Bürger einplanen (siehe Abschnitt 8), sonst wird die Harness wieder tote Daten
  produzieren.

---

## 3. Design-Entscheidungen (freigegeben)

| # | Entscheidung | Begründung |
|---|---|---|
| D1 | Auswertung als **Python-Harness** im Fusion-Repo (`tools/`) | Bewährtes Muster (golden_csv_harness.py), deckt genau die 5 Szenarien ab, kein Android-Build nötig, CI-fähig. |
| D2 | Recorder zeichnet **immer** Zähler/Events auf, **optional** rohe Sensor-Samples (Schalter) | Rohdaten erlauben später Offline-Replays und Pipeline-Verbesserungen ohne neue Hardware-Läufe; Volumen bleibt bei alltäglicher Nutzung klein. |
| D3 | Ground-Truth = **bestätigte Reps aus `logSet`**, aber nur wenn aktiv editiert | Das Analogon zu `correctedReps`; nur die tatsächliche Nutzer-Korrektur ist unabhängige Wahrheit, nicht der vorbefüllte App-Wert. |
| D4 | Shadow-Diff-Zeilen im **JSONL**-Format nach FR-B12-Vorbild | Schlank, maschinenlesbar, pro Zeile auswertbar, erweiterbar um neue `source`-Knoten. |

---

## 4. Zielarchitektur

```
                       ┌──────────────────────────────────────────┐
   SensorProvider       │              TrainViewModel             │
   .samples ─────────► │  ┌──────────┐   ┌─────────────────────┐  │
                       │  │ Live-    │   │ Shadow-             │  │
                       │  │ Engine   │   │ Engine              │  │
                       │  └────┬─────┘   └─────────┬───────────┘  │
                       │       │ liveRepCount      │ shadowRepCount│
                       │       ▼                   ▼              │
                       │  ┌──────────────────────────────────┐    │
                       │  │   ShadowSessionRecorder          │    │
                       │  │  (unabhängiger Listener)         │    │
                       │  │  · RepEvent-Listener            │    │
                       │  │  · optionaler Sample-Listener    │    │
                       │  │  · Satzgrenzen aus logSet        │    │
                       │  └──────────────────┬───────────────┘    │
                       └─────────────────────┼────────────────────┘
                                             │  JSONL (eine Zeile/Event)
                                             ▼
                        /Android/data/<pkg>/files/recordings/<session>.jsonl
                                             │
                       (USB/Dateimanager kopiert nach) │
                                             ▼
                        Fusion/tools/golden_shadow_corpus/<name>.jsonl
                             + <name>.session.json  (Manifest, Wahrheit)
                                             │
                                             ▼
                        tools/shadow_harness.py ──► Report: PASS/FAIL je
                        (Python, portiert aus                    Szenario,
                         golden_csv_harness.py)                  Exit-Code
```

### 4.1 Datenfluss

1. **Aufzeichnung (App):** Während einer aktiven Trainings-Session mit
   verbundenem Chip sammelt `ShadowSessionRecorder` (a) je bestätigtem Rep einen
   Diff-Zustand und (b) je `RepEvent` der Shadow-/Live-Engine die Qualitäts-
   Metadaten; optional zusätzlich rohe `SensorSample`s.
2. **Satzgrenzen:** `logSet` signalisiert Satz-Ende mit der bestätigten
   Rep-Zahl. Der Recorder schreibt pro Satz eine Abschlusszeile (Satz-ID,
   live-bestimmt, bestätigt, shadow).
3. **Kuration:** Der Entwickler kopiert die JSONL auf den Rechner und legt ein
   Manifest `<name>.session.json` daneben (Szenario-Tag, Übung, erwartete
   Reps — sofern abweichend vom bestätigten Wert, Notizen).
4. **Auswertung:** `shadow_harness.py` liest alle JSONL+Manifest-Paare, rechnet
   pro Szenario Exact-Match/MAE/Bias, vergleicht gegen die Abnahmekriterien und
   gibt einen konsolidierten Report mit Exit-Code aus.
5. **Optional (Replay):** Liegen Roh-Samples in der JSONL vor, spielt die
   Harness dieselben Samples zusätzlich durch einen Kotlin-Nachbau der
   Pipeline-Logik ab (im ersten Schritt: durch die identische Signalkette, in
   Python portiert) und vergleicht die Rep-Zeitstempel pro `active`-Fenster.

---

## 5. Datei-/Modulplan

### 5.1 Kotlin (App)

| Datei | Inhalt |
|---|---|
| `feature/workout/.../shadow/ShadowSessionRecorder.kt` | Neuer, unabhängiger Listener; Puffer + JSONL-Serialisierung; start/stop pro Session; optionaler Sample-Listener. |
| `feature/workout/.../shadow/ShadowReportLine.kt` | Datenklasse nach FR-B12-Vorbild (ts, source, liveReps, shadowReps, delta, liveSignal?, shadowSignal?, note?, setIndex?). |
| `feature/workout/.../shadow/ShadowSessionManifest.kt` | Serde für das Manifest (Szenario-Tag, Übung, erwartete Reps, Notizen) — dient der Konsistenz zwischen App und Harness. |
| `feature/workout/TrainViewModel.kt` | Anbindung: Recorder in `init` erzeugen, an `samples`- und `repEvents`-Flows hängen, in `logSet` Satzgrenze setzen, bei `finishExercise`/`disconnect` stoppen und JSONL schreiben. |
| `feature/workout/.../di/` | Hilt: `ShadowSessionRecorder` verdrahten (Speicherverzeichnis-Injektion für Tests). |

### 5.2 Python (Auswertung)

| Datei | Inhalt |
|---|---|
| `tools/shadow_harness.py` | Port von `golden_csv_harness.py` auf das JSONL-Schema; Kategorien je Szenario-Tag; Abnahmekriterien zentral; Smoke-Test-Modus. |
| `tools/golden_shadow_corpus/README.md` | Kurations-Anleitung (analog `golden_csv_corpus/README.md`), Manifest-Schema, Ablauf. |
| `tools/golden_shadow_corpus/` | Ablage kuratierter Aufnahmen + Manifeste (Start leer). |
| `tools/shadow_harness_test_fixtures.py` (oder im Skript) | Synthetische Fixtures für den Smoke-Test. |

---

## 6. JSONL-Schema (Recorder-Ausgabe)

Eine Datei pro Session, Append-only, UTF-8, `\n`-getrennt. Jede Zeile ist ein
JSON-Objekt mit einer `t`-Zeile vom Typ. Zwei Kategorien:

### 6.1 Diff-/Zustandszeilen (immer)

```json
{"t":"rep","ts":1723000000000,"source":"new_pipeline",
 "liveReps":12,"shadowReps":12,"delta":0,
 "setIndex":1,"qualityScore":0.83,"correlation":0.91,
 "prominence":142.0,"durationSamples":54}
```

- `ts`: Epoch-ms des Ereignisses (Gerätezeit).
- `source`: heute immer `new_pipeline`; später erweiterbar.
- `liveReps`/`shadowReps`: Zählerstände im Moment des Ereignisses.
- `delta`: `shadowReps - liveReps`.
- `setIndex`: Satznummer innerhalb der Session (Start bei 1).
- Qualitätsfelder aus `RepEvent` (ExerciseEnginePipeline.kt:32) — nur wenn
  das Ereignis von einer Engine kam.

### 6.2 Abschlusszeilen pro Satz (immer)

```json
{"t":"set","ts":1723000060000,"setIndex":1,
 "confirmedReps":12,"confirmedRepsEdited":true,
 "liveCountedReps":12,"shadowReps":12,"delta":0,
 "exerciseId":7,"weightMilliKg":80500000}
```

- `confirmedReps`: was der Nutzer im `logSet` abgelegt hat.
- `confirmedRepsEdited`: `true`, wenn der Nutzer den vorbefüllten Wert aktiv
  geändert hat (**Ground-Truth**, D3); `false`, wenn der App-Wert unverändert
  übernommen wurde (dann nicht unabhängig, nur Diagnose).
- `liveCountedReps`: was die Live-Engine zählte (diagnostisch, D4).
- `shadowReps`: Shadow-Stand bei Satzende.
- `delta`: `shadowReps - confirmedReps` (das Freigabe-Kriterium).
- Felder werden so geschrieben, dass die Harness pro Satz genau **einen**
  `set`-Datensatz mit beiden Zählerständen vorfindet.

### 6.3 Sample-Zeilen (optional, Schalter)

```json
{"t":"sample","ts":1723000000050,"ax":0.1,"ay":0.9,"az":9.8,
 "gx":12.3,"gy":-4.5,"gz":1.2,"workoutState":"counting"}
```

- Identisch zum FlowRep-CSV, aber als JSONL-Zeile (ein Schema für alles).
- Nur aktiv, wenn der Nutzer den Recorder-Modus "mit Rohdaten" eingeschaltet
  hat (Standard aus).
- `workoutState`: aktueller Satzphase-Wert (`idle`/`countdown`/`counting`),
  analog zum `workout_state` in FlowRep für Offline-Fensterung.

---

## 7. Manifest-Schema (Wahrheit, neben der JSONL)

`<name>.jsonl.meta.json` — analog zu `<name>.csv.meta.json` in FlowRep:

```json
{
  "recording": "2026-08-10_bizeps_curl_normal.jsonl",
  "exercise_id": "bicep_curl",
  "scenario": "normal",
  "known_active_reps": [12, 10],
  "device": "M5StickC-Plus2",
  "notes": "ruhiges Tempo, Griff wie gewohnt, Template aktiv",
  "samples_recorded": false
}
```

`scenario` ist eines der 5 Szenarien aus Abschnitt 11b (Schlüssel siehe
Abschnitt 9 dieses Plans — die Szenario-Tabelle unten). `known_active_reps` pro Satz in chronologischer Reihenfolge —
die unabhängige, von Hand gezählte Kontrollgröße (Kurations-Schritt 2).

**Wahrheits-Priorität (pro Satz):**
1. `known_active_reps` — wenn vorhanden, wertet die Harness ausschließlich
   dagegen (unabhängig, manuell gezählt).
2. `confirmedReps` aus der JSONL — **nur wenn `confirmedRepsEdited = true`**
   (der Nutzer hat den vorbefüllten App-Wert aktiv editiert). Ein unveränderter
   vorbefüllter Wert ist **keine** unabhängige Wahrheit, denn er ist nur die
   Zahl, die die App selbst berechnet hat.
3. Ist keine dieser Wahrheiten vorhanden, wird der Satz als „keine Wahrheit"
   markiert und zählt nicht in den Report.

---

## 8. Kurations-Workflow (erste Klasse — Lehre aus flowrep-clone)

Damit die Harness nicht wieder tote Daten produziert, wird der Ablauf als
expliziter, dokumentierter Prozess im README und in der CI verankert:

1. **Aufnahme:** Trainings-Session mit verbundenem Chip + aktivem Recorder
   (Schalter "Rohdaten" nur bei kurzen Szenario-Läufen). Die App zeigt am
   Session-Ende an, dass eine JSONL geschrieben wurde.
2. **Sofortige Notiz:** Direkt nach der Session (nicht Tage später) die echte,
   von Hand gezählte Wiederholungszahl pro Satz notieren — siehe
   `golden_csv_corpus/README.md`-Grundsatz.
3. **Transfer:** JSONL aus `/Android/data/<pkg>/files/recordings/` per USB/
   Dateimanager nach `tools/golden_shadow_corpus/` kopieren.
4. **Manifest:** `<name>.jsonl.meta.json` daneben anlegen (Szenario-Tag,
   `known_active_reps`, Notizen).
5. **Lauf:** `python3 tools/shadow_harness.py --corpus-dir
   tools/golden_shadow_corpus` → Report.
6. **Freigabe-Protokoll:** Ergebnisse (Report-Ausgabe) an den Freigabe-Eintrag
   in `docs/STATUS_FORTSCHRITT.md` anhängen, sobald das Gate 11b geprüft wird.

CI-Integration (später, sobald der erste Corpus existiert): Ein GitHub-Action-
Schritt führt `shadow_harness.py` gegen den corpus aus; bei `--fail-on-fail`
bricht die CI. So bleibt die Harness nicht unbeobachtet.

---

## 9. Zuordnung zu den 5 Freigabe-Szenarien (Abschnitt 11b)

| Szenario (11b) | Manifest-`scenario` | Erwartung (Abnahmekriterium) |
|---|---|---|
| 1. 5 Übungen ohne Kalibrierungsprofil (`noTemplate=true`) | `no_template` | pro Satz `delta = 0` |
| 2. 5 Übungen nach echter Kalibrierung mit aktivem Template | `calibrated` | pro Satz `delta = 0` |
| 3. Langsame Wiederholungen (exzentrisch vollständig) | `slow` | pro Satz `delta = 0` |
| 4. Sensor-Reconnect mitten in der Session | `reconnect` | `delta = 0` ab dem Reconnect-Zeitpunkt; davor dokumentierte Abweichungen erlaubt |
| 5. Übungswechsel mit verschiedenen Profilen in derselben Session | `exercise_switch` | `delta = 0` je Satz, kein Template-Übersprechen |

**Gate (aus 11b):** Erst wenn alle fünf über mehrere unabhängige Sessions
`delta = 0` zeigen (oder jede Abweichung dokumentiert und einzeln von Adi
freigegeben ist), darf die neue Pipeline live zählen. Bis dahin bleibt sie
reine Beobachtung.

---

## 10. Abnahmekriterien (zentral, in der Harness)

```python
# tools/shadow_harness.py (zentral, nicht verstreut)
DELTA_TOLERANCE_REPS = 0          # Freigabe-Gate 11b: exakte Uebereinstimmung
MIN_SESSIONS_PER_SCENARIO = 3     # "mehrere unabhaengige Sessions" (11b)
EXACT_MATCH_RATE_MIN = 1.0        # bei Toleranz 0 == alle Saetze delta=0
MAE_MAX = 0.0                     # bei Toleranz 0
```

Pro Szenario zählen nur Sätze mit vorhandener Wahrheit. Der Report gibt je
Szenario aus: Anzahl Sessions, Anzahl Sätze, davon `delta=0`, Exact-Match-Rate,
MAE, Bias. PASS erfordert: mindestens `MIN_SESSIONS_PER_SCENARIO` Sessions UND
alle Sätze `delta=0`.

**Zusätzliche Diagnose-Spalten (aus der Validierungs-Literatur, z. B.
Oberhofer et al. 2021, Sports 9(9):118 — Smartwatch-Rep-Counting-Validierung):**

- **RMSE** zwischen Wahrheit und gezählten Reps. Bei Toleranz 0 ist RMSE=0
  äquivalent zum Exact-Match, aber der Wert dokumentiert die Fehlergröße
  einzelner Abweichungen und bleibt nützlich, falls das Gate später gelockert
  wird.
- **Aufschlüsselung pro `exercise_id` UND pro `scenario`.** Die Literatur
  zeigt stark übungsabhängige Zählgenauigkeit (z. B. Bench Press deutlich
  schlechter als Squat/Deadlift). Nur nach Szenario zu gruppieren würde
  systematische Fehler pro Übung verstecken.

---

## 11. Teststrategie

- **Kotlin (Recorder):** Unit-Tests mit `TestDispatcherProvider` und injizier-
  barem Speicherverzeichnis:
  - Diff-Zeilen werden bei `repEvents`-Emission geschrieben.
  - Satzabschlusszeile korrekt bei `logSet` (confirmed = bestätigt).
  - Optionaler Sample-Listener schreibt nur bei aktiviertem Schalter.
  - JSONL-Ausgabe ist valides, zeilenweise parsebares JSON.
  - Recorder beeinflusst Zählung nicht (kein Aufruf in die Engine).
- **Python (Harness):** Smoke-Test-Modus mit synthetischen Fixtures (wie
  `golden_csv_harness.py --smoke-test`): normal/slow/reconnect/wiggle-artige
  Diffs, inkl. eines bewusst fehlerhaften Satzes, um den FAIL-Pfad zu prüfen.
- **Integration:** einmalige Manuell-Prüfung: mit Fake-Sensor-Samples in den
  Unit-Tests eine JSONL erzeugen → in corpus legen → Harness liefert Report.

---

## 12. Umsetzungsreihenfolge (falls freigegeben)

| Schritt | Inhalt | Dateien |
|---|---|---|
| 1 | `ShadowReportLine` + `ShadowSessionRecorder` (Diff-Zeilen + Satzgrenze), ohne Rohdaten | Kotlin, `feature/workout` |
| 2 | `logSet`-Anbindung + Hilt-Verdrahtung + Tests | Kotlin |
| 3 | Optionaler Sample-Listener + Schalter + Tests | Kotlin |
| 4 | `tools/shadow_harness.py` + Manifest-Serde + Smoke-Fixtures | Python |
| 5 | `tools/golden_shadow_corpus/README.md` + Kurations-Workflow | Markdown |
| 5.5 | **Öffentlichen IMU-Datensatz als Corpus-Baseline:** Microsoft RecoFit-Datensatz (`microsoft/Exercise-Recognition-from-Wearable-Sensors`, 200+ Teilnehmer, Acc+Gyro, rep-gelabelt, Matlab-`.mat`) per Python-Script (`scipy.io.loadmat`) in unser JSONL-Schema konvertieren und als ersten Corpus-Inhalt ablegen. Damit ist die Harness vor dem ersten Hardware-Lauf gegen echte Kurven validierbar — struktureller Schutz gegen die „leerer Corpus"-Falle aus flowrep-clone. Optional ergänzend: IEEE DataPort „Gym Gesture Classification Using IMU" (wrist-worn, CSV, TinyML-nah am M5StickC-Formfaktor). | Python, `tools/` |
| 6 | Erste echte Aufnahme (Szenario 1, eine Übung) als Pilot der Kette | Hardware-Lauf + Kuration |
| 7 | CI-Schritt (sobald Corpus existiert) | `.github/workflows/ci.yml` |

Pilot zuerst (Schritt 6): genau eine Übung, ein Szenario — die komplette Kette
vom Chip bis zum Report einmal durchlaufen, bevor alle fünf Szenarien gefahren
werden.

---

## 13. Risiken und offene Punkte

| Risiko | Antwort |
|---|---|
| Recorder puffert zu viele Rohdaten | Rohdaten nur optional, Standard aus; Ringpuffer mit fester Größe; Rohdaten-Modus nur für kurze Szenario-Läufe. |
| `ts` aus der App vs. `timestampMs` aus dem BLE-Paket | Klären: im Recorder durchgehend Gerätezeit verwenden; BLE-Zeitstempel nur in `rep`-Metadaten. |
| `delta=0` pro Satz ist streng — kleine, dokumentierte Abweichungen blockieren das Gate | Gate-Text aus 11b erlaubt "jede Abweichung dokumentiert und einzeln von Adi freigegeben" — die Harness gibt sie maschinenlesbar aus, Freigabe bleibt Entscheidung. |
| Corpus wird wieder leer | Kurations-Workflow + CI-Anbindung als Pflicht in diesem Plan; Pilot in Schritt 6 als erster Beweis. |
| Pipeline-Verbesserungen (z. B. Befund-C) ändern Zählverhalten | Dank Rohdaten später offline reproduzierbar ohne neue Hardware-Läufe (D2). |
| Python-Harness driftet vom Kotlin-Verhalten ab | Signalkette bleibt in der Fusion der Single-Source-of-Truth; Harness-Replays sind als Kontrolle markiert, nicht als Freigabe-Kriterium. |
| Wo landet die Freigabe-Dokumentation? | In `docs/STATUS_FORTSCHRITT.md` (Abschnitt B) wird ein Eintrag je Szenario mit Report-Anhang geführt. |

---

## 14. Bewusst nicht in diesem Plan

- Die fünf Hardware-Szenarien selbst zu fahren (eigener Schritt nach Freigabe
  der Harness — die Harness macht sie nur objektiv auswertbar).
- Ein ML-basiertes Offline-Replay (TFLite/Classifier) — späteres Feature.
- Ein UI-Screen für die Shadow-Diffs (Diagnose-Overlay) — separates Thema,
  vorerst reicht die JSONL.
- Automatisches Hochladen von Aufnahmen — bleibt lokal (Datenschutz-Konstante).

---

## 15. Ergebnis-Nutzen

Mit diesem Plan ist der offene Punkt B.3 aus `docs/STATUS_FORTSCHRITT.md`
geschlossen: Die Shadow-vs-Live-Diffs werden **strukturiert aufgezeichnet**,
**kuratiert** und **objektiv ausgewertet**. Die fünf Freigabe-Szenarien aus
Abschnitt 11b bekommen ein reproduzierbares, CI-fähiges Kriterium. Und dank der
optionalen Rohdaten-Aufnahme (D2) ist jede spätere Pipeline-Verbesserung gegen
echte Bewegungsdaten offline testbar — genau der Mehrwert, den FlowRep mit
seinem (nie befüllten) `golden_csv_corpus` eigentlich liefern sollte.
