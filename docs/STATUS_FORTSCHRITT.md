# STATUS & FORTSCHRITT – FlowRep x DropSync Fusion

**Zweck:** Lebendiges Koordinationsdokument, da mehrere Claude-Instanzen
parallel an der Fusion arbeiten koennen. Wird bei jeder Session als
Erstes gelesen (vor Phase 0), und nach jedem Arbeitsschritt aktualisiert.
Massgeblich fuer Produktentscheidungen ist
`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (siehe dessen
eigene Geltungsordnung) — dieses Dokument haelt nur den Fortschritt fest.

Bis 2026-08-09 gab es keine Koordinationsdatei fuer die Fusion in diesem
Repo. Die entsprechende Datei fuer den fruehereren FlowRep-Umbauplan
liegt archiviert unter `flowrep`-Repo,
`docs/archive/umbauplan/STATUS_FORTSCHRITT.md` — thematisch nicht mehr
aktuell fuer die Fusion, aber als Beispiel fuer den Dokumentationsstil
frueherer Sessions brauchbar.

**Regeln für jede Session:**

1. Vor Beginn einer Aufgabe: passende Zeile auf `[~] in Arbeit (Session: <Kennung>, <Datum>)` setzen.
2. Nach Abschluss: `[x] erledigt (Session: <Kennung>, <Datum>) – <1-Zeilen-Ergebnis>`.
3. Nie eine fremde `[~]`-Zeile ohne Rücksprache überschreiben oder als erledigt markieren.
4. Session-Kennung: `Claude-<8-stelliger-Zufalls-Hex>`, erzeugbar z. B. via `openssl rand -hex 4`.

Legende: `[ ]` offen · `[~]` in Arbeit · `[x]` erledigt · `[!]` blockiert / braucht Entscheidung von Adi

---

## A. Vorarbeiten am Design-Dokument (vor Phase 0)

- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Widerspruch zwischen Abschnitt 9 (alter Phasenplan, Phasen 0-10) und Abschnitt 12 (überarbeitete Reihenfolge nach Super-KI-Review, Phasen 0-12) aufgelöst: Abschnitt 9 komplett auf die Abschnitt-12-Struktur umgeschrieben, Auto-Drop-Erkennung von alter Phase 5 in neue Phase 12 verschoben.
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Verwaisten alten Dokumentschluss (doppelte Abschnitte 11–13 aus der Zeit vor dem Super-KI-Review, u. a. mit "Phase 0 starten, dann 1 bis 10" statt 1 bis 12) entfernt.
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Kopf-Block (Tabelle "Verbundene Dokumente" + Geltungsordnung) fehlte in dieser Repo-Kopie des Design-Dokuments, aus der flowrep-Kopie nachgetragen. Beide Kopien danach byte-identisch (verifiziert per `diff`).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Phase 4: neue Zähl-Pipeline (ExerciseEngine/PeakDetector/PhaseValidator/TemplateMatcher) als zusätzlicher Shadow-Port ergänzt (Adi-Entscheidung), Befund-C-Fix als Vorbedingung referenziert, neuer Abschnitt 11b mit Shadow-DoD-Checkliste (5 Freigabe-Szenarien, modelliert nach `DIRECTIONAL_GP_SHADOW_ROLLOUT_2026-07-27.md`).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Phase 2: expliziter Löschauftrag für vorhandenen Routinen-Code ergänzt (`RoutineScreens`/`RoutineViewModels`/`RoutineExpander`/`ProgressAnalysis`/`ProgressScreens`/`ProgressViewModels`, Adi-Entscheidung).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – ADR-0013 geschrieben: hebt die "3-Tab / keine neuen Fitness-Features"-Grundsätze aus `FLOWREP_DESIGN_PLAN.md` formal für die Fusion auf, analog zu ADR-0010.
- [x] Phase 0 begonnen und laengst abgeschlossen, siehe Abschnitt C — diese Zeile war bis 2026-08-12 nicht aktualisiert worden, obwohl Phase 0-5 bereits fertig waren (Regel 2 dieser Datei wurde von den ausfuehrenden Sessions nicht befolgt).

---

## A1. Kollision entdeckt und aufgelöst (2026-08-09)

- [x] **Wichtig für alle künftigen Sessions (Session: Claude-936c6f89, 2026-08-09):** Parallel zu dieser Doku-Arbeit hat eine andere Session bereits mit echter Umsetzung begonnen, Branch `fusion/foundation` (Autor "Claude (Entwurf, ungeprüft)"): FlowRep-Referenzmaterial nach `docs/archive/flowrep-import/` gespiegelt + CI-Workflow (`.github/workflows/ci.yml`) eingerichtet (inkl. Fix für `gradlew`-Ausführungsrecht) — **beides bereits in `main`**, unproblematisch. **Zusätzlich** enthält `fusion/foundation` einen dritten, noch nicht gemergten Commit (`9692b6f`), der Abschnitt 9 (Phasenplan) fast komplett löscht (nur noch Verweis auf Abschnitt 12, keine Einzelschritte mehr) — das ist eine **andere Lösung für dasselbe Problem**, das diese Session per vollständiger Umsortierung gelöst hat (siehe A oben). Adi hat sich für die ausführliche Version entschieden (Einzelschritte pro Phase bleiben erhalten). **Für die nächste Session, die `fusion/foundation` weiterführt: Commit `9692b6f` NICHT nach `main` mergen** — würde die jetzt in `main` stehende ausführliche Abschnitt-9-Fassung wieder überschreiben. Die anderen `fusion/foundation`-Commits (Archiv-Spiegel, CI) sind davon nicht betroffen und bereits sicher in `main`.
- [x] Branch `docs/fix-fusion-design-doc` (ein dritter, unabhängiger Versuch, nur den doppelten Dokumentschluss zu entfernen) ist damit ebenfalls überholt — Inhalt ist jetzt vollständig in `main` enthalten, Branch kann gelöscht werden.
- [!] **Grundursache:** Bis zu diesem Eintrag gab es keine gemeinsame Koordinationsdatei in diesem Repo, wodurch zwei Sessions unabhängig am selben Problem gearbeitet haben, ohne es zu wissen. Ab jetzt: vor Beginn jeder Aufgabe diese Datei lesen und `git fetch --prune` gegen alle Branches, nicht nur `main`.

---

## B. Offene Punkte aus dem Plan-Review (Stand 2026-08-12)

- [x] Datenbank-Verschlüsselung: FlowRep verschlüsselt lokal (`sqlite3mc`), die fusionierte App bewusst nicht (Adi-Entscheidung, 2026-08-09) — kein weiterer Handlungsbedarf, hier nur zur Nachvollziehbarkeit protokolliert.
- [~] Instrumentierte Tests (`androidTest`): `TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md` löst das als vollständige Testpyramide statt nur `androidTest`-Grundgerüst (Robolectric wo möglich, `androidTest` nur wo zwingend). Erster Pilot gelandet (`TimerServiceForegroundTest`, `:data:timer`, Commit `0973c2c`). Für `:feature:workout` (die eigentlich sicherheitsrelevante Stelle, siehe Fund 1) noch nicht ausgerollt.
- [~] Automatisierte Vergleichs-/Diff-Logik Shadow-vs-Live: `SHADOW_DIFF_HARNESS_PLAN.md` geschrieben und mit D3-Ground-Truth-Regel geschärft (Commit `0973c2c`), aber noch nicht implementiert — Recorder/DI/Kotlin-Tests aus Plan-Schritt 1-3 stehen aus.
- [!] **Fund 1 (Review, 2026-08-12):** `ExerciseEnginePipeline` zählt seit Phase 4 live (`liveEngine` in `TrainViewModel.startCountedSet()`), entgegen Abschnitt 11b. Per ADR-0014 formal als bewusste Abweichung dokumentiert (Session: Claude-814d9738, 2026-08-12) — kein offener Entscheidungsbedarf mehr, aber die 5 Freigabe-Szenarien aus Abschnitt 11b stehen weiterhin aus.

---

## C. Phase 0-5 Umsetzung (nachgetragen 2026-08-12, Session: Claude-814d9738)

Diese Phasen liefen zwischen 2026-08-09 und 2026-08-11, ohne dass diese
Datei laufend aktualisiert wurde (Regel 2 wurde nicht befolgt — der
Rückstand ist genau das Risiko, vor dem Abschnitt A1 warnt). Nachfolgend
aus `git log` rekonstruiert; die ausführenden Sessions trugen keine
`Claude-<hex>`-Kennung im Autor-Feld, daher Commit-Hash statt
Session-Kennung als Referenz.

- [x] erledigt (`ae3d2eb`, 2026-08-09) – Phase 0: Foundation. DropSync als Basis, FlowRep-Archiv unter `docs/archive/flowrep-import/`, 4-Tab-Navigation, Build grün.
- [x] erledigt (`f33dfeb`, 2026-08-09) – Phase 1: Designsystem finalisiert (FlowRepTheme, Lime-Dominanz, Theme-Snapshot-Test).
- [x] erledigt (`78fb770`, `c003781`, 2026-08-10) – Phase 2: Flaches Satz-Log (`flat_sets`, Train-Tab, PR-Volumen), Routinen-/Progress-Code vollständig entfernt (`RoutineScreens`/`RoutineViewModels`/`RoutineExpander`/`ProgressAnalysis`/`ProgressScreens`/`ProgressViewModels` — stichprobenartig verifiziert: sauber, nur noch ein erklärender Kommentar als Spur), `FlatSetRepositoryImpl`-Tests, Neue-Übung-Dialog, RestPref-Default 90s.
- [x] erledigt (`309b039`, `276b309`, 2026-08-10) – Phase 3: Pausen-Timer Foreground-Service + Train-Bindung, `POST_NOTIFICATIONS`-Runtime-Request.
- [x] erledigt (`70bd3f8` bis `d8a0a12`, 2026-08-10) – Phase 4: Sensor-Stack (BLE-Provider, Guided Calibration Wizard, Live-Waveform), Shadow-Pipeline-Port (`ExerciseEnginePipeline`/`PeakDetector`/`PhaseValidator`/`TemplateMatcher`/`QualityScorer`, Befund-C-Fix korrekt referenziert und portiert), Live-Rep-Counting + Lern-Loop verdrahtet. **Siehe Fund 1 / ADR-0014**: die Shadow-Pipeline wurde dabei zusätzlich als `liveEngine` eingesetzt, entgegen der ursprünglichen Shadow-only-Absicht dieser Phase.
- [x] erledigt (`f8b0868`, `52595c0`, 2026-08-10) – Phase 5: Marker-Drag (`moveMarker`-Port + Waveform-Geste), Auto-Analyse nach Import (neue Songs starten Waveform-Analyse direkt nach dem Scan).
- [ ] Phase 6-12 noch nicht begonnen (Audio-POC, weitere Schritte laut Design-Dokument Abschnitt 9/12).

---

## D. Design-Dokumente Shadow-Diff-Harness + Testinfrastruktur (2026-08-10/11)

- [x] erledigt (`aab0330`, 2026-08-10) – `SHADOW_DIFF_HARNESS_PLAN.md` geschrieben: schließt offenen Punkt B (alt) zur Diff-Logik. Wiederverwendet FlowRep-Muster (ShadowReportLine, unabhängiger CSV-Recorder), Kurationsworkflow als Erstklass-Bürger (Lehre aus FlowReps leerem `golden_csv_corpus`).
- [x] erledigt (`078809a`, 2026-08-10) – `TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md` geschrieben: korrigiert Abschnitt 11's teils falsche Testschicht-Einordnung, definiert Testpyramide (Layer 1-4), deckt Abschnitt-9/12-Kollision im Hauptdesign auf.
- [x] erledigt (`0973c2c`, 2026-08-11) – Beide Pläne geschärft: D3-Ground-Truth-Regel (nur aktiv editierte `confirmedReps` zählen), RecoFit-Corpus als Baseline vor erstem Hardware-Lauf, BLE-MTU-Praxiswissen (Samsung-Delay, Status-133-Retry, serielle GATT-Queue), CI-Branch-Fix (`main`→`master`, CI lief zuvor nie — verifiziert per GitHub-API, `total_count: 0` vor dem Fix), erster Robolectric-Test (`TimerServiceForegroundTest`).
- [!] RecoFit-Caveat aus dem Review (2026-08-12) noch nicht in `SHADOW_DIFF_HARNESS_PLAN.md` ergänzt: ein grüner RecoFit-Lauf prüft nur die Pipeline-Mechanik, ist kein Ersatz für die 5-Szenarien-Hardwarefreigabe. Sollte vor dem RecoFit-Bootstrap (Plan-Schritt 5.5) noch als Zeile ergänzt werden.

---

## E. Heutige Session (2026-08-12, Session: Claude-814d9738)

- [x] erledigt – ADR-0014 geschrieben: dokumentiert Fund 1 formal als akzeptierte Abweichung von Abschnitt 11b (Adi-Entscheidung).
- [x] erledigt – `confirmedRepsEdited` in `TrainViewModel` implementiert (Flag in `setReps()`, nicht per Wertevergleich; zurückgesetzt in `stopCountedSet()`/`logSet()`/`finishExercise()`), 2 neue Tests in `TrainViewModelTest.kt`.
- [x] erledigt – `ExerciseEnginePipelineIsolationTest.kt` (`:domain:sensor`): beweist Instanz-Unabhängigkeit von live-/shadow-artig konfigurierten Pipelines, Sample-Sequenz per Python-Simulation der SignalChain/PeakDetector/RepCounter-Logik numerisch verifiziert (nicht nur geschrieben, sondern durchgerechnet).
- [x] erledigt – Diese Datei synchronisiert (Abschnitte C, D, E nachgetragen).
- [!] **Nicht in diesem Commit** (siehe Fence-Report des Reviews): `ShadowEngineIsolationTest` auf ViewModel-Ebene (`:feature:workout`, braucht Fake-Umbau), `feature/train`-ADR (Split-Kandidat, aber verfrüht), Testinfra-Plan Schritt 2 (MTU-Negotiator/TimerService-Kill-Recovery/Audio-Timestamp), Shadow-Diff-Harness Schritt 1-3 (Recorder+DI+Tests), RecoFit-Corpus-Bootstrap. Alle fünf sind eigene, größere Arbeitspakete.
- [!] **Wichtig:** keine dieser Änderungen wurde mit einem echten `./gradlew test`-Lauf verifiziert (kein Gradle/Maven-Netzwerkzugriff in der ausführenden Sandbox) — nur Code-Audit + für die Testdaten eine unabhängige Python-Simulation der Zählalgorithmen. Vor dem nächsten Schritt: `./gradlew test` lokal laufen lassen und diesen Punkt hier auf `[x]` setzen.

**Nächster Schritt:** `./gradlew test` lokal verifizieren, danach `ShadowEngineIsolationTest` (ViewModel-Ebene) oder Shadow-Diff-Harness-Plan Schritt 1 (Recorder), je nach Priorität.

---

## F. Heutige Session, Fortsetzung (2026-08-12, Session: c7f2a9e1)

- [x] erledigt (`9216db2`) – Shadow-Diff-Harness-Plan Schritt 1: `ShadowSessionRecorder`/`ShadowDiffEvent` (`feature/workout/shadow/`), `NoOpShadowSessionRecorder` als Platzhalter-Binding. `logSet()` zeichnet jetzt ein Event auf (vor Lern-Loop/Reset, damit `confirmedRepsEdited` den tatsächlich bestätigten Zustand abbildet). 1 neuer Test; zweiten geplanten Test (unedited-Vorbefüllung) wieder verworfen — mit den aktuellen Fakes nicht sauber treibbar, gleiche Lücke wie beim ViewModel-Level-Isolationstest.
- [x] erledigt – RecoFit-Caveat in `SHADOW_DIFF_HARNESS_PLAN.md` (Abschnitt D, offener Punkt) ergänzt.
- [!] `feature/train`-ADR bewusst nicht umgesetzt: Einschätzung aus Abschnitt E ("verfrüht") übernommen.
- [!] Weiterhin offen: `ShadowEngineIsolationTest` (ViewModel-Ebene, braucht Fake-Umbau), MTU-Negotiator-Extraktion (Testinfra Schritt 2), echte JSONL-Persistenz für den Recorder (Schritt 2/3), RecoFit-Corpus-Bootstrap selbst (nur das Caveat ist erledigt).
- [!] **Nicht mit echtem `./gradlew test` verifiziert** (kein Gradle-Netzwerkzugriff in dieser Sandbox) — nur Code-Audit + Klammer-/Referenz-Abgleich per Hand. Vor dem nächsten Schritt lokal gegenprüfen.

**Nächster Schritt:** `./gradlew test` lokal verifizieren (deckt jetzt auch diese Runde ab), danach echte JSONL-Persistenz für den Recorder oder der ViewModel-Level-Isolationstest.
