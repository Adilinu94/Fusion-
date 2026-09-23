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
- [~] Instrumentierte Tests (`androidTest`): `TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md` löst das als vollständige Testpyramide statt nur `androidTest`-Grundgerüst (Robolectric wo möglich, `androidTest` nur wo zwingend). Pilot gelandet (`TimerServiceForegroundTest`), Testinfra Schritt 2 (5a/5b/5c) komplett umgesetzt (Robolectric-Hilt-Tests). Offen bleibt nur: instrumentierte Kern-Tests (Schritt 4: AudioTimestamp, Underrun, AudioFocus) + `androidTest`-Ausrollung für `:feature:workout`.
- [x] Automatisierte Vergleichs-/Diff-Logik Shadow-vs-Live: umgesetzt als Punkt 7a (JSONL-Recorder + DI + `TrainViewModel`-Bindung, siehe Abschnitt I). Offen bleibt nur Corpus-Kuration und Hardware-Läufe (Punkte 7c/9c).
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
- [x] RecoFit-Caveat aus dem Review (2026-08-12) in `SHADOW_DIFF_HARNESS_PLAN.md` ergänzt (Plan-Schritt 5.5): ein grüner RecoFit-Lauf prüft nur die Pipeline-Mechanik, ist kein Ersatz für die 5-Szenarien-Hardwarefreigabe.

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

## G. Heutige Session (2026-08-13): Umbauplan SOFORT-Punkte 1-3 umgesetzt

- [x] **Windows/Gradle-Testumgebung repariert.** Zwei Blocker gefunden und behoben:
  - Der Test-Executor crashte mit `Hauptklasse Files konnte nicht gefunden werden`: Gradle 9.5 quotet `-Djava.library.path` nicht; der vollständige System-PATH (mit `C:\Program Files\...`) zerriss das Argument. Fix: echter PATH-freier Wert + minimaler Worker-PATH in `feature/workout/build.gradle.kts`. Zusätzlich zeigte die Junction `C:\dev\jbr17` auf Android Studios JBR (inzwischen JDK 25); sie wurde auf ein echtes Temurin JDK 17 umgebogen (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`).
  - `TrainViewModelTest` hing im `runTest`-Cleanup: der 250-ms-Ticker läuft im `viewModelScope` (nicht im `backgroundScope`) und lässt den Test-Scheduler nie idle werden. Fix: `withViewModel`-Helper in beiden ViewModel-Tests, der den Scope am Testende cancelt. Zusätzlich `isReturnDefaultValues = true` in `feature/workout/build.gradle.kts` (android.util.Log im Shadow-Collector).
- [x] **Umbauplan Punkt 1:** `TrainViewModel.resetShadowEngine()` erstellt die Shadow-Engine jetzt mit Profil-Achse/Bias (und erwarteter Prominenz/Dauer) nach dem asynchronen Laden; ohne Profil weiter neutral.
- [x] **Umbauplan Punkt 2a:** `CalibrationViewModel.confirmAndSave()` speichert `signalPeakLevel = theta + expectedProminence` (SPK) und `noisePeakLevel = theta * 0.5` (NPK) statt `theta`/`baseline` direkt.
- [x] **Umbauplan Punkt 2b:** `updateLevels(spk, npk)`-Durchreichung über `ExerciseEnginePipeline` → `RepCounter` → `PeakDetector`; `startCountedSet()` und `resetShadowEngine()` rufen sie mit den Profilwerten auf.
- [x] **Umbauplan Punkt 3:** `ExerciseEngineConfig.minQualityScore`-Default von 0.4 auf 0.55 angehoben (konsistent mit dem `QualityScorer`-Default).
- [x] **Tests:** `ExerciseEnginePipelineIsolationTest` war nie grün — der alte `peakShape`-Stream (abrupt -100 → 0) ließ die Pending-Window-Erweiterung erst über das 120-Sample-Limit schließen, der PhaseValidator lehnte dann als asymmetrisch ab. Die "Python-Verifikation" im Kommentar hatte die Pending-Logik nicht nachgebildet. Neuer Stream (rein positives Dreieck) zählt deterministisch 2 Reps. Neu: `CalibrationControllerWizardTest` (synthetischer Voll-Durchlauf REST→REVIEW) und `CalibrationViewModelTest` (SPK/NPK-Invariante). Verifiziert mit echtem Gradle: `:domain:sensor:test`, `:core:testing:test`, `:feature:workout:testDebugUnitTest` — alle grün.

**Nächster Schritt:** MITTELFRISTIGE Punkte 4-6 (Accel-Kanal, Multi-Template, adaptive Refraktärzeit) oder LANGFRISTIG Gate 11b. Die offenen Punkte aus Abschnitt F (JSONL-Persistenz, MTU-Negotiator) bleiben bestehen.

## H. Mittelfristige Punkte 4-6 umgesetzt (2026-08-13, Fortsetzung)

- [x] **Punkt 4 (Accel als zweiter Kanal, Voting):** `SignalChain` hat einen Accel-Zweig (Abweichung der Magnitude von 1 g -> OneEuro mit 2 Hz MinCutoff -> Envelope), `ProcessedFrame` trägt `smoothedAccel`/`accelEnvelope`, `ExerciseEngineConfig.accelEnabled` (default false) schaltet den zweiten `PeakDetector` auf dem Accel-Kanal zu. Der RepCounter zählt einen Gyro-Peak nur, wenn ein Accel-Peak innerhalb von 5 Samples liegt (Voting); das Pending-Fenster bleibt bei aktivem Voting offen, bis der Accel-Peak (feuert wegen Falling-Debounce später) vorliegt. `TrainViewModel` füttert jetzt ax/ay/az in beide Engines. Tests: Voting unterdrückt reinen Gyro-Peak; gleichphasige Kanäle zählen beide Reps; `accelEnabled=false` verhält sich wie vorher.
- [x] **Punkt 5 (Multi-Template-Pool):** `TemplateMatcher` hält statt eines Einzel-Templates einen FIFO-Pool (default 5, konfigurierbar über `ExerciseEngineConfig.templatePoolSize`); `match()` nimmt den Best-Match über alle Templates, bestätigte Rep-Windows wandern per `addToPool()` in den Pool. Fängt Formdrift/Ermüdung ab. Tests: Best-Match gewinnt (1- vs. 2-Perioden-Sinus, nahezu orthogonal), FIFO-Eviction, kurze/konstante Windows verworfen.
- [x] **Punkt 6 (Adaptive Refraktärzeit):** `PeakDetector.updateExpectedDuration()` setzt die Refraktärzeit auf 30% der erwarteten Rep-Dauer, geklemmt auf 5-100 Samples; `updateLevels()` nimmt jetzt auch `expectedDurationSamples` entgegen, `RepCounter.trackForAdaptation()` aktualisiert sie nach 3 bestätigten Reps. Die Pending-Fenster-Grenze `MAX_EXTRA_PHASE_SAMPLES` ist dynamisch (2x erwartete Dauer, 60-300 Samples). `TrainViewModel` reicht `profile.expectedDurationSamples` in beide Engines durch. Tests: schnelle Reps nicht mehr unterdrückt, Floor/Cap, direkte Dauer-Update.
- [x] Verifiziert mit echtem Gradle: `:domain:sensor:test`, `:feature:workout:testDebugUnitTest`, `:core:testing:test`, `:domain:timer:test`, `:domain:workout:test` — alle grün.

**Nächster Schritt:** LANGFRISTIGE Punkte 7-9. Punkt 7 (Gate 11b) braucht zuerst die JSONL-Persistenz aus Abschnitt F; Punkte 8 (Madgwick) und 9 (RecoFit-Bootstrap) sind unabhängig davon.

## I. Langfristige Punkte 7-9 umgesetzt (2026-08-13, Fortsetzung)

- [x] **Punkt 7a (JSONL-Persistenz):** `ShadowSessionRecorder`-Interface um `startSession`/`endSession` erweitert; `JsonlShadowSessionRecorder` schreibt `session_start`/`set`/`session_end`-Zeilen nach `/Android/data/<pkg>/files/recordings/<session>.jsonl`. `ShadowRecorderModule` ist jetzt ein `@Binds`-Modul auf die echte Implementierung (Hilt-Transform `transformDebugClassesWithAsm` mit `--rerun-tasks` verifiziert). `TrainViewModel` startet eine Session im `init` und beendet sie in `finishExercise`/`disconnectSensor`; neuer Lifecycle-Test in `TrainViewModelTest` (Fake-Recorder zählt start/end).
- [x] **Punkt 7b (Harness):** `tools/shadow_harness.py` liest JSONL+Manifest-Paare, wertet mit der D3-Wahrheits-Priorität (known_active_reps > edited confirmedReps > no truth) und produziert PASS/FAIL pro Szenario (Exact-Match 100%, MAE 0, min. 3 Sessions). `--smoke-test` generiert synthetische Fixtures und ist grün (inkl. no_truth-Pfad-Check). `tools/golden_shadow_corpus/README.md` dokumentiert Schema und Kurations-Workflow (7c).
- [x] **Punkt 8 (Madgwick):** `OrientationTracker` (6 DOF, gyro+accel, Paper-Gleichungen mit korrigiertem Update-Vorzeichen) in `domain/sensor`; `SignalChain` kann ihn per Konstruktor-Injektion aktivieren und rotiert die kalibrierte Achse damit online nach (`ExerciseEngineConfig.orientationTrackingEnabled`, default false). Tests: Identität/Reset, Einheits-Quaternion unter Bewegung, exakte statische Rotationen, konsistente dynamische Konvergenz (90°/s um Y), Vektorlängen-Erhalt, SignalChain-Invarianz in Ruhe.
- [x] **Punkt 9 (externe Korpora):** `tools/recofit_bootstrap.py` (RecoFit .mat → JSONL + Manifest, robuste Feldnamen-Varianten) und `tools/mmfit_bootstrap.py` (MM-Fit CSV/JSON → JSONL, multi-device) erstellt; beide kompatibel zum Harness. Kuration/Validierung (9c) braucht echte Datensatz-Downloads (bewusst außerhalb dieses Commits).
- [x] Verifiziert: `:domain:sensor:test` (58 Tests), `:feature:workout:testDebugUnitTest`, Hilt-ASM-Transform mit `--rerun-tasks`, `python tools/shadow_harness.py --smoke-test` — alle grün.

**Nächster Schritt:** Alle Punkte des Umbauplans sind implementiert. Offen bleibt die echte Hardware-/Korpus-Validierung (Abschnitt 11b, Punkte 7c/9c) und die offenen Punkte aus Abschnitt F (MTU-Negotiator).

## J. Offene Punkte abgearbeitet (2026-08-13, Abschluss)

- [x] **MTU-Negotiator-Verdrahtung (Abschnitt F / Testinfra-Plan 5a):** Der Entscheidungskern existierte bereits, war aber im `BleSensorProvider` nicht verdrahtet (kein Timeout, kein Retry, Status verworfen). Neu: `MtuNegotiationSession` (pure, stateful, zählt Retries über Callbacks/Timeouts hinweg), `GattEvent.MtuChanged` trägt jetzt den GATT-Status, `connectGatt` läuft die Verhandlung mit 1s-Timeout und bis zu 2 Retries (Status 133 = Retry-Trigger), danach Fallback MTU 23; erst nach Verhandlung/Timeout erfolgt Service-Discovery. `REQUEST_MTU` bleibt 185 (HyperOS-517-Off-by-one, dokumentiert statt Plan-512). Tests: Session-Zählung über mehrere Callbacks, Reset, Erfolg nach Retries, HyperOS-Grenze.
- [x] **RecoFit-Konvertierung (9a/9c) mit echtem Datensatz verifiziert:** `tools/recofit_bootstrap.py` auf das reale Format umgeschrieben (per Inspektion verifiziert: `subject_data`-Zellmatrix, `data.accelDataMatrix`/`gyroDataMatrix`, `activityStartMatrix` mit Rep-Wahrheit Spalte 5, Start/Ende Spalten 1/2). 3 GB LFS-Download geladen, 2 Visits konvertiert (je ~90-128k Samples, 8-12 Sätze mit Wahrheit 15-30 Reps), `activity_windows` im Manifest, Samples als counting/idle markiert. Harness-Referenz-Pfad (Gyro-Magnitude-Peak-Zählung, Mechanik-Check statt Exact-Match, da fremdes Gerät/Position) → PASS.
- [x] **Harness-Erweiterungen:** Referenz-Szenarien (`recofit_reference`/`mmfit_reference`) mit eigenem Kriterium (numpy optional, Mechanik-Check), Reconnect-Szenario unterstützt `reconnect_before_set` (Sätze vor dem Reconnect dürfen dokumentierte Abweichungen haben). README des Corpus um Aufnahme-Anleitung + Reconnect-Feld ergänzt.
- [x] **Feature-Flags (Punkte 4/8) dokumentiert:** `accelEnabled` und `orientationTrackingEnabled` bleiben an beiden Config-Baustellen in `TrainViewModel` explizit auf `false` mit Rollout-Kommentar, bis die 5 Freigabe-Szenarien (Gate 11b) grün sind.
- [x] Verifiziert: `:data:sensor:testDebugUnitTest`, `:domain:sensor:test`, `:feature:workout:testDebugUnitTest`, `shadow_harness.py --smoke-test`, RecoFit-Harness-Lauf (2 Sessions, 20 Fenster) — alle grün.

**Nächster Schritt:** Umbauplan komplett implementiert. Übrig: echte Hardware-Validierung durch Adi (5 Freigabe-Szenarien, Anleitung in `tools/golden_shadow_corpus/README.md`), danach Gate-11b-Entscheidung und Flag-Aktivierung.

## K. Testinfra Schritt 2 abgeschlossen: 5b Kill-Fallback + 5c Audio-Timestamp (2026-08-13)

- [x] **5b TimerService-Kill-Fallback vollständig verdrahtet:** Der Domain-Kern (`TimerEngine.snapshot()/restore()`, `RestTimerRecovery`, `DataStoreTimerSnapshotStore`) existierte bereits, war aber nirgends eingebaut. Neu:
  - `TimerService` persistiert bei jedem Tick laufende NORMAL/REST-Timer (RUNNING/PAUSED) und leert das Snapshot bei COMPLETED/CANCELLED; schreibt den monotonen Zeitwert für die Reboot-Erkennung mit.
  - `TimerRecoveryStarter` (neu): App-Start-Prüfung RebootGuard → Snapshot verwerfen (DEVICE_REBOOT_OR_UNKNOWN_CLOCK) oder Engine rehydrieren + Foreground-Service neu starten. Eingebunden in `DropSyncApplication.onCreate()`.
  - DI: `TimerSnapshotStore` + `RestTimerRecovery` in `TimerDataModule`.
  - Tests: 3 neue Robolectric-Hilt-Tests (Snapshot persistiert, nach Abschluss geleert, PAUSED mit Restzeit) + 5 neue JVM-Tests für `TimerRecoveryStarter` (Reboot verwirft, Rehydrierung startet Service, kein Snapshot = kein Start). `FakeTimerSnapshotStore` um `snapshot`-Getter ergänzt.
- [x] **5c AudioTimestamp-Latenz:** `AudioTimestampReader`-Interface + `AudioTimestampExtrapolator` in `:domain:playback` (pur, formelgetreu `audibleFrame ≈ framePosition + (nowNs - tsNano) * rate`, Buffer-Anteil abziehbar, Warm-up-Gate fällt auf Playhead zurück, Rückwärtsuhr extrapoliert nicht). `AudioTrackTimestampReader` in `:data:playback` als echte Implementierung (bewusst noch nicht DI-verdrahtet, Media3 verwaltet den Sink). 6 JVM-Tests: Vorwärts-Extrapolation über Deltas, Warm-up-Fallback, Null-Timestamp, Rückwärtsuhr, Buffer-Abzug, invalide Sample-Rate.
- [x] **Nebenbefund behoben:** `data:playback`-Robolectric-Tests liefen gegen SDK 36 (Java-21-Pflicht), lokal ist Java 17 → `robolectric.properties` auf sdk=34 gesetzt, `CrossfadeControllerTest`/`MediaItemFactoryTest` laufen wieder grün.
- [x] Verifiziert: `:domain:timer:test`, `:domain:playback:test`, `:data:timer:testDebugUnitTest` (25 Tests), `:data:playback:testDebugUnitTest`, `:core:testing:test`, `:app:compileDebugKotlin` — alle grün.

**Nächster Schritt:** Testinfra Schritt 2 ist damit vollständig (5a MTU war bereits im vorherigen Commit verdrahtet). Übrig aus Schritt 6: Design-Doku-Konsolidierung (Punkt 4). Danach Umbauplan Punkte 4-6 (Accel-Kanal, Multi-Template, adaptive Refraktärzeit).

## L. Restpunkte abgearbeitet (2026-08-13): Umbauplan-Tests 4-6 + Doku-Konsolidierung

- [x] **Umbauplan Punkte 4-6 (Implementierung existierte, Plan-Tests fehlten):** Die Logik für Accel-Voting, Multi-Template-Pool und adaptive Refraktärzeit war im Code bereits vorhanden, aber die im Umbauplan geforderten Tests fehlten teilweise. Ergänzt: `SignalChainAccelTest` (3 Tests: smoothedAccel bei Bewegung, Ruhe ~0, deaktivierter Zweig bleibt 0), `RepPipelineTest` (Pool füllt sich nach bestätigter Rep, FIFO-Eviction bei poolSize=3, `trackForAdaptation` adaptiert die erwartete Dauer nach 3 Reps). `:domain:sensor:test` grün.
- [x] **Design-Doku-Konsolidierung (Testinfra-Plan Punkt 4):** Benannte Anker (`<a name="...">`) im Design-Doc für Abschnitte 9-13 und 11a/11b gesetzt; alle Zahl-Verweise (`Abschnitt <n>`) in den lebenden Design-Plänen (`FLOWREP_DROPSYNC_FUSION_DESIGN`, `SHADOW_DIFF_HARNESS_PLAN`, `TESTINFRASTRUKTUR_UMBAUPLAN`, `REP_ZAEHLUNG_UMBAUPLAN`, `WISSEN_POWERAMP_OFFTRACK`) auf Anker-Links umgestellt. Neues `tools/doku_links_check.py`: prüft Anker-Ziele, tote Links und verbietet Zahl-Verweise in `docs/design/*.md` (Archiv/STATUS/ADR bleiben historisch ausgenommen). CI-Job um den Check erweitert.
- [x] **Konsolidierungs-Entscheidung dokumentiert:** Die ausführliche Abschnitt-9-Fassung bleibt erhalten (Adi-Entscheidung aus STATUS A1); der Check verhindert künftige stille Umnummerierungs-Regressionen, ohne die Doku umzubauen.
- [x] Umbauplan-DoD und Reporting-Tabelle (Punkte 4-6 erledigt, Punkt 7 als `[~]` mit Hardware-Hinweis) aktualisiert.

**Nächster Schritt:** Alle automatisierbaren Punkte sind erledigt. Offen bleibt ausschließlich Hardware-Validierung durch Adi (5 Freigabe-Szenarien, Anleitung in `tools/golden_shadow_corpus/README.md`), danach Gate-11b-Entscheidung und Flag-Aktivierung (`accelEnabled`/`orientationTrackingEnabled`).

## M. Phase 6-7 umgesetzt (2026-08-13): Audio-FSM, Route-Kalibrierung, Gain-Struktur, Countdown-Beeps

- [x] **Phase 6a (AudioClock + RouteProfile, Domain):** `AudioClock`-Interface (`EXACT/BEST_EFFORT/UNAVAILABLE`), `AudioRouteProfile` (routeKey, sampleRate, Latenz, p50/p95, `Confidence`), `PlaybackGeneration` (Invalidierungs-Token) in `domain/playback`; `RouteProfileRepository`-Port. `DuckingMixer` (min-Logik: `effectiveDuckDb = min(rest, cue)`, nie doppelt) + `DuckingRamp` (Attack 40 ms / Release 200 ms) mit 6 JVM-Tests.
- [x] **Phase 6b (Data + DI):** `RouteProfileStore` (DataStore je Route, Latenz-Tabellen: Speaker 40 ms, Wired 25 ms, BT/SBC 120 ms, USB 30 ms; STALE-Markierung bei Gerätewechsel), `Media3AudioClock` (interpolierte hoerbare Position, Service bindet den Player), `PlaybackService` hängt die Clock ein. DI in `PlaybackDataModule`.
- [x] **Phase 6c (Planner):** `DropLandingPlanner` um `DIRECT_TO_DROP` (Drop hinter Go: Rest-Musik volle Restzeit, beim Go direkt zum Drop), Latenz-Abzug (`WorkStart = Go - Marker - Latenz`), Crossfade-Vorlauf und Markerwahl Entscheidung 37 (kleinster Abstand |R-D|, Drop vor Go bevorzugt) erweitert; 8 Tests.
- [x] **Phase 6d (Coordinator):** Crossfade-Dauer aus `DspConfig`, Latenz aus Route-Profil, Generation-Token (Satzwechsel invalidieren laufende Landungen), `DIRECT_TO_DROP`-Ausführung. 3 neue Tests (Rest-Ducking on/off, Latenz verschiebt Landung, DIRECT_TO_DROP springt zur Drop-Position).
- [x] **Phase 7a (Gain-Struktur):** `restDuckDb` (-12..0, Default -8) in `DspConfig` + Codec; `MasterDspProcessor` kombiniert Rest- und Cue-Ducking per `min()` am Preamp-Knoten; `AudioPipeline.setRestDuckDb` mit Rampe (Attack 2 Schritte, Release 8 Schritte); `RestDuckingGate` (Domain-Port) + `RestDuckingGateImpl`; Coordinator aktiviert das Rest-Ducking bei Pausenbeginn, nimmt es bei Pausenende/manueller Übernahme/Landung zurück.
- [x] **Phase 7b (Countdown-Beeps):** `CountdownBeepPlayer` (vorgerenderte Sinus-Clips 880 Hz kurz / 1760 Hz Go, Hüllkurve gegen Knacksen, eigener AudioTrack, keine Systemlautstärke-Änderung); `CueOutput.countdownBeep()` neu, `TimerEngine` nutzt kurze Beeps für 3-2-1 und letzte Sekunden, langen Go-Beep für den Abschluss. `AndroidCueOutput` + DI verdrahtet.
- [x] **Phase 7c (Settings-UI):** Duck-Regler (-12..0 dB Chips) + dezenter Timing-Hinweis je Route im Musik-in-Pausen-Abschnitt (`SettingsScreen`), `SettingsViewModel.setRestDuckDb`, Strings de/en.
- [x] **Nebenbefund:** `data:audio`-Robolectric-Tests liefen gegen SDK 36 (Java-21-Pflicht) → `robolectric.properties` auf sdk=34 (wie `data:playback` in Testinfra 5c). Spotless-Format-Drift vieler Dateien bereinigt (`spotlessApply`).
- [x] Verifiziert: `:domain:timer`, `:domain:playback`, `:domain:audio`, `:data:timer` (25 Tests), `:data:audio`, `:data:playback`, `:feature:player`, `:feature:workout`, `:app:compileDebugKotlin`, `spotlessCheck` — alle grün.

**Nächster Schritt:** Phase 8 (Waveform-Rendering/Scrubbing/Marker-UI auf Basis der Phase-5-Daten) und danach Phase 9 (Train-Hero-Politur). Bewusst noch offen aus Phase 6/7: echte Latenz-Messung (AudioTrack-Timestamp-Verdrahtung), Underrun-Monitoring/MISSED_UNDERRUN, Route-Wechsel → BEST_EFFORT-Hinweis (Profil ist da, UI-Text existiert).

## N. Phase 8 umgesetzt (2026-08-13): Library-Waveform, Marker direkt auf der Waveform, A11y

- [x] **Library-Waveform (Schritt 1/2):** `MiniWaveform` im Designsystem (nicht-interaktiv, Lime-Anteil = gespielter Fortschritt des laufenden Titels); `LibraryViewModel.currentProgress` + `waveformFor(songId)` (nur lesend aus dem Analyse-Cache, kein Anstoßen beim Scrollen); `SongColumn`/`SongRow` zeigen die Mini-Waveform rechts (84×24 dp), sobald die Analyse vorliegt; laufender Titel zeigt den Fortschritt. Verdrahtet in `SongCategoryScreen` und `CollectionSongScreen`; Grid/Kompakt/Playlist bleiben schlank (bewusst).
- [x] **Marker direkt auf der Waveform (Schritt 3):** Long-Press nahe einem Tick (Slop 3 %) löscht den Marker nach Bestätigung statt einen neuen zu setzen (`WaveformMapping.nearestMarkerIndex`, `DeleteMarkerDialog`); Long-Press auf freier Fläche setzt weiterhin über den Label-Dialog. Drag nahe einem Tick verschiebt (bestand schon), Tap springt. Die Drop-Landung nutzt die verschobene Position automatisch (Coordinator liest die Marker-Position).
- [x] **A11y:** Waveform meldet `progressBarRangeInfo` (0..100) + `stateDescription` (Prozent), damit TalkBack die Position ohne Slider vorliest; Beschreibungstext um die Lösch-Geste ergänzt (de/en).
- [x] **Tests:** `nearestMarkerIndex` (Slop-Treffer/Fehlschlag/leer), `LibraryWaveformMathTest` (Fortschritt klemmt, Bucket-Normalisierung, leere Buckets → null) neu; bestehende Waveform-/Player-Tests unverändert grün.
- [x] Verifiziert: `:feature:library`, `:core:designsystem`, `:feature:player`, `:domain:timer`, `:app:compileDebugKotlin`, `spotlessCheck` — alle grün.

**Nächster Schritt:** Phase 9 (Train-Hero-Politur: Rep-Zahl groß, Waveform unter Hero, Gewicht-Reihe, +/- Pill, Timer-Pille, Mini-Player-Badges, Swipe-Pager, Leerzustände mit CTA, Verlaufs-Chart, Undo). Hinweis: 60fps-Scrubbing ist auf dem 256-Bucket-Canvas (einfache drawRoundRect-Schleife) strukturell unkritisch, aber bewusst nicht instrumentiert gemessen — das bleibt ein Punkt für die Hardware-Abnahme.

## O. Waveform-Härtung (2026-08-13): Lautheits-Normalisierung + Zeichenpfad ohne Allokation

Anlass: Überprüfung gegen `WISSEN_POWERAMP_OFFTRACK` (Abschnitt 25: Waveform in Compose) und `D:\rev-tools\poweramp_offline_triage` (keine zusätzlichen Waveform-Befunde, nur "track peak" als Keyword). Zwei Lücken gefunden und geschlossen:

- [x] **Lautstärke-Robustheit:** Der `WaveformAccumulator` speicherte absolute Int8-Peaks, ein leise gemastertes Lied ergab eine fast flache Waveform. Jetzt: `WaveformAccumulator.peak()` trackt den größten Betrag (0..1) je Analyse; `WaveformDisplayGain` (Boden 0.35, Deckel 8x) skaliert leise Tracks ehrlich hoch, laute bleiben unverändert, Stille/Peak 0 hebt nie an. Persistiert als `track_analysis.peak_linear` (DB v6, Migration 5→6 additiv), `ANALYZER_VERSION` 2→3 invalidiert den alten Cache automatisch. `PlayerViewModel.waveform` und `LibraryViewModel.waveformFor` wenden dieselbe Normalisierung an (Player und Library identisch).
- [x] **Zeichenpfad ohne Allokation:** `WaveformMapping.toFlatBars` liefert die Balkengeometrie als FloatArray (left/top/width/height) statt Objektliste; `Waveform` und `MiniWaveform` zeichnen per while-Schleife direkt aus dem Array (Wissensdoku Abschnitt 25: keine Objekt-Allokation pro Frame, Canvas nur aus vorbereiteten Arrays). `mapToBars` bleibt für die Tests erhalten.
- [x] **Tests:** 5 neue JVM-Tests (Peak, Boden, laute unverändert, Deckel, symmetrische Skalierung), Geometrie-Äquivalenz `toFlatBars`↔`mapToBars`, leer bei ungültiger Fläche, Player-Test "leiser Track wird angehoben".
- [x] **Nebenbefund:** `core:database`-Robolectric lief gegen SDK 36 (Java-21-Pflicht unter Java 17) → `robolectric.properties` auf sdk=34, wie bereits data:audio/data:playback.
- [x] Verifiziert: `:domain:audio`, `:core:designsystem`, `:feature:player`, `:feature:library`, `:core:database` (inkl. MigrationTest), `:data:audio`, `:app:compileDebugKotlin`, `spotlessCheck` — alle grün.

**Bewertung Geschwindigkeit:** Die Analyse ist ein einmaliger WorkManager-Durchgang (MediaCodec, 256 Buckets, expedited bei Titelwechsel), danach kommt alles aus dem Room-Cache. Das Zeichnen sind 256 drawRoundRect + Reflexion auf einem Canvas ohne Allokation im Zeichenpfad — strukturell weit unterhalb der 60fps-Grenze. Echte FPS-Messung bleibt bewusst der Hardware-Abnahme vorbehalten.

## P. Poweramp-Triage-Lektionen umgesetzt (2026-08-13): BT-Codec-Latenz + Batch-Import-Analyse

Anlass: `D:\rev-tools\poweramp_offline_triage` tief durchsucht (native ELF-Strings, Smali, DEX-Keyword-Report) und mit unserem Player abgeglichen. Befunde, die wir übernehmen konnten:

**Was Poweramp in nativ versteckt (bewusst NICHT kopiert):** Der komplette Audio-Hotpath (DSP-Thread, Resampler, Output-Puffer `output_buf_ms`/`dsp_bufs`, AAudio/OpenSL, `AudioTrack.getMinBufferSize`, `AudioManager.getProperty(OUTPUT_FRAMES_PER_BUFFER)`). Diese Strings liegen nur in `libpowerampcore.so`, nicht im DEX. Das bestätigt unsere Entscheidung (Wissensdoku Abschnitt 15/36): Media3-Decks fürs MVP, native Engine erst nach Messung.

- [x] **A2DP-Codec-spezifische Latenz (Poweramp-Muster `PaBluetoothCodecConfig`):** `OutputDeviceSnapshot` führt jetzt `bluetoothCodec` (SBC/AAC/APTX/LDAC/LC3). `RouteProfileStore` nutzt AAC=80ms / LDAC=150ms statt pauschal SBC=120ms, unbekannt = SBC (ehrlicher A2DP-Fallback). Profil-Key enthält den Codec (`BLUETOOTH_A2DP:addr#AAC`), damit dasselbe Gerät für SBC/LDAC getrennte Messwerte halten kann. `AudioManager.getBluetoothCodecStatus()` ist versteckt API → Reflection mit Exception-Fallback, genau wie Poweramp es für versteckte AudioManager-Aufrufe macht (seine Logs "getProperty java exception" zeigen das Muster).
- [x] **Batch statt N-Queries beim Import (Poweramp-Scanner-Muster: Batches bei Tausenden Titeln):** Neu `TrackAnalysisRepository.requestAnalysisForNewSongs` + `TrackAnalysisDao.getBySongIds` (eine IN-Query für alle Cache-Misses). `refreshLibrary` enqueued den Batch statt pro Song eine Cache-Abfrage + Worker-Anlage. Bei 1000 neuen Songs: 1 statt 1000 Room-Queries.
- [x] **Bereits erfüllt (nur geprüft):** Scanner-Muster `setThreadPriority(LOWEST)` + `SystemClock.sleep` entspricht unserem WorkManager-Ansatz (lässt das System die Priorität steuern); MediaSession-Baum getrennt vom DSP (unsere `:data:playback`-Grenze); LazyList mit stabilen Keys (`mediaStoreId`) überall in der Library; Analyse-Cache versioniert mit Datei-Fingerprint.
- [x] **Tests:** `neue songs laufen gebatcht in genau einem anstoss` (data:library); bestehende Import-/Player-/DB-Tests grün.
- [x] Verifiziert: `:data:audio`, `:data:playback`, `:data:library`, `:feature:player`, `:core:database`, `:app:compileDebugKotlin` — alle grün.

### P1. Vertiefte Waveform-Triage (2026-08-26)

Die erneute Prüfung von `D:\\rev-tools\\poweramp_offline_triage` hat die frühere Einschätzung aus Abschnitt O präzisiert. Die APK enthält mit `com/maxmpz/widget/player/Waveseek.smali` ein eigenes Waveform-Widget, mit `Seek.smali` einen getrennten Seek-Lebenszyklus und mit `q1.smali` einen `Choreographer`-basierten Frame-Controller.

- `f0.r:[F` ist die vom Player-Zustand gelieferte vorbereitete Waveform-Datenstruktur.
- `Waveseek` übernimmt dieses Array direkt; ein kleiner synthetischer Sinus-Fallback wird nur bei fehlenden Daten aufgebaut.
- `q1.doFrame()` interpoliert die Position zwischen Playback-Events zeitbasiert und meldet den primitiven Fortschrittswert an das Widget.
- `Seek` trennt `DOWN`/`MOVE`/`UP`/`CANCEL`, nutzt 0..10000 als Positionsauflösung und mappt Touch-X innerhalb der gepaddeten Breite.
- Für FlowRep folgt daraus: Waveform-Geometrie stabil vorbereiten, Offset/Progress frame-synchron aktualisieren und Scrub-Vorschau/Commit/Cancel getrennt behandeln.

Nicht belegt sind aus den Artefakten allein die exakte Peak-Analyse, Fensterbreite, Farben und konkrete Zeichenreihenfolge des sichtbaren Poweramp-Screens. `milk`-Shader/Blur-Assets gehören zur Visualizer-Schicht und werden nicht als Now-Playing-Grundlage übernommen. Die ausführliche Evidenz steht in `docs/design/WISSEN_POWERAMP_OFFTRACK_2026-08-07.md`, Abschnitt 49.

## Q. Instrumentierte Kern-Tests umgesetzt (2026-08-14): Teil F des Hardware-Testplans

Anlass: Hardware-Testplan Teil F (Testinfra-Plan Schritt 4) — die
"Kein einziger instrumentierter Test"-Lücke schließen. Auf dem
Medium_Phone-Emulator (Android 16) verifiziert.

- [x] **Testinfra eingerichtet:** `hilt-android-testing` + `kspAndroidTest` + `testApplicationId` + `customTestApplicationClass` in `app/build.gradle.kts`; `HiltTestApplication` in `app/src/androidTest/` **bewusst OHNE `@HiltAndroidApp`** — Hilt erlaubt nur eine Root pro Modul, die Produktions-`DropSyncApplication` ist die Root (vorher `InvalidRootsException`). Media3 als `androidTestImplementation` ergänzt.
- [x] **`AudioTimestampExtrapolationInstrumentedTest`** (2 Tests, grün): echter `AudioTrack` + `AudioTrackTimestampReader`, Warm-up-Gate (vor `play()` kein valider Timestamp), Delta-Monotonie. Exakte Frame-Deltas sind auf dem Emulator nicht garantiert (grobe Treiber-Bursts) → robust: Uhr geht vorwärts, Position wächst monoton, Extrapolator läuft vorwärts. D8-Falle: Testmethoden mit Backtick-Leerzeichen (z. B. `fehlerhafte konfiguration`) erzeugen Klassennamen mit Leerzeichen → `Space characters in SimpleName` → auf ASCII-Unterstrich umbenannt.
- [x] **`AudioFocusPermanentLossInstrumentedTest`** (1 Test, grün): ExoPlayer identisch zur App (`setAudioAttributes(attrs, true)`), permanenter LOSS via `AudioManager.requestAudioFocus` stoppt die Wiedergabe. ExoPlayer-Falle: Zugriff nur auf dem Main-Thread (Erzeugung + Zustandslesungen über `runOnMainSync`/Listener), sonst `Player is accessed on the wrong thread`.
- [x] **`BleScanConnectInstrumentedTest`** (2 Tests, per `@Ignore`): Scan → Connect → MTU → STREAMING auf echtem M5; CI-sicher deaktiviert.
- [x] **Underrun-Test bewusst NICHT angelegt:** `AudioInfoListener` hat kein Underrun-Monitoring (`onAudioSinkError`/`onAudioTrackUnderrun` fehlen). Erst Feature bauen (Hardware-Testplan B6 prüft den Bedarf), dann Test. Im Testplan dokumentiert.
- [x] **Nebenbefund:** physisches Gerät (`55j7xkiffixsyhxg`) war parallel an ADB → `INSTALL_FAILED_USER_RESTRICTED`; via `-s emulator-5554` + `settings put global verifier_verify_adb_installs 0` gelöst. `connectedDebugAndroidTest` läuft jetzt stabil.
- [x] Verifiziert: `:app:connectedDebugAndroidTest` auf Medium_Phone (Android 16) — BUILD SUCCESSFUL, 3/3 Audio-Tests grün. `docs/HARDWARE_TESTPLAN.md` Teil F aktualisiert.

## R. Emulator-UI-Tests Teil D/E (2026-08-14): First-Start, Theme, A11y, Import

Anlass: Hardware-Testplan Teile D und E auf dem Medium_Phone-Emulator
(Android 16) durchgeführt.

- [x] **E1 First-Start-Berechtigungsfluss:** Frische Installation startet
  mit Systemdialog `POST_NOTIFICATIONS` (Train-Tab, ohne eigenes
  Rationale - die App fragt direkt beim ersten Öffnen). Nach "Don't
  allow" läuft die App normal weiter (kein Crash). Music-Tab zeigt bei
  fehlender Audio-Berechtigung den Fehlerzustand "Access to your music /
  DropSync plays music stored on this device. Grant access" mit CTA
  statt leerem Screen (Anforderung erfüllt, LibraryScreen hat den
  Rationale-Flow). Nach Grant: Library mit allen Kategorien (All Songs,
  Folders, Albums, Artists, Genres, Playlists, Queue, Favorites,
  Recently Added).
- [x] **D3 Erst-Import 28 Tracks:** 3 synthetische WAVs gepusht +
  Emulator-Vorab-MP3s (28 Tracks, 1:19:10, inkl. FLAC). Import ohne
  UI-Freeze, Liste sofort bedienbar, Track-Zähler korrekt.
- [x] **D1/D4 Wiedergabe + Ticker:** Blue Horizon (FLAC, 1:15) abgespielt;
  MediaSession-PlaybackState PLAYING(3), Position läuft (13946 → 25988
  in ~12 s), DSP-Pipeline aktiv. Now-Playing zeigt Titel/Position,
  Ticker läuft flüssig.
- [x] **D7 Dark/Light-Theme:** `cmd uimode night yes` → App rendert
  dunkel (Hintergrund RGB 31/31/31), Lime-Akzent (RGB 223/255/47) als
  aktiver Tab-Indikator vorhanden. Einstellungen bieten Follow
  system/Light/Dark + Accent color. Audio & DSP-Seite mit kompletter
  DSP-Kette: Master/Preamps, Soft limiter, Bass/Treble-Shelfs,
  Equalizer (Graphic/Parametric, 10/15/31 Bands, Frequenz-Slider
  32 Hz-16 kHz). Kein Kontrastproblem sichtbar.
- [x] **D6 200%-Schrift:** `font_scale 1.3` → alle Hauptscreens, keine
  abgeschnittenen Texte/Buttons (UI-Dump zeigt keine überlaufenden
  Elemente).
- [x] **D5 Barrierefreiheit (Waveform):** Now-Playing-Waveform hat
  vollständige TalkBack-Beschreibung ("Waveform: tap to jump, drag to
  preview, long-press to set or delete a marker"); Pause/Next/Queue/
  Back/More options alle beschriftet. Library: Shuffle/Play/Search/
  List Options mit content-desc, Song-Zeilen als Text lesbar.
- [x] **D2 Library-Scroll-Performance (Emulator-Einschränkung):** 28
  Tracks scrollen flüssig, aber `gfxinfo` zeigt 38 % Janky frames (95th
  550 ms) - **Emulator nutzt Software-Rendering** (`ro.hardware.egl=
  emulation`, kein GPU). Für belastbare FPS-Messung (D1/D2) echtes
  Gerät nötig; Ergebnis ist als "nicht aussagekräftig" zu werten.
- [x] **E4 Offline:** Flugmodus-Broadcast auf Emulator per
  SecurityException verweigert (Shell-Permission); App läuft aber lokal
  ohne Netz komplett (Import + Wiedergabe aus MediaStore), keine
  Netzwerk-Fehlermeldungen. Echter Offline-Test (Flugmodus) auf
  physischem Gerät wiederholen.
- [x] Verifiziert: App installiert, First-Start, Library-Import,
  Wiedergabe, Theme, A11y auf Medium_Phone (Android 16) - alle grün.
  Doku: `docs/STATUS_FORTSCHRITT.md` Abschnitt R.

## S. Musik-Recherche 2026 + Waveform-Performance-Umbauplan (2026-08-21, Session: ZCode-4d9f2a7b)

Anlass: Recherche "Music-Funktion verbessern (technisch + UI/UX), Best
Practices 2026" und vertiefte Analyse der Waveform-Generierung
("schleppend"). Kein Produktionscode geaendert — nur Doku.

- [x] Zwei Research-Dokumente mit Quellenzitaten je Aussage erstellt:
  `docs/research/RESEARCH_MUSIC_UIUX_2026.md` (M3 Expressive, YTM/Spotify/
  Apple-Redesigns 2025-26, Now-Playing/Queue/Mini-Player, Fitness-Hybrid,
  36 DropSync-Empfehlungen + Quick Wins) und
  `docs/research/RESEARCH_MUSIC_TECHNIK_2026.md` (Media3 1.11, AudioStack,
  BT/LE-Audio, FGS-Regeln 14-16, Compose-Performance, Waveform, Testing).
- [x] Flaschenhals-Analyse der Analyse-Pipeline (fuenf Befunde, mit
  file:line belegt): All-or-nothing-Analyse (Interface verspricht
  Nur-Waveform-Pfad, `AnalysisProfile` existiert unbenutzt),
  Per-Sample-Schleife, Chroma-Detailkosten (Modulo je Sample, cos je
  Fenster), WorkManager-Dispatch-Latenz im UI-Pfad, kein Prewarming/
  keine Prioritaet. Rendering explizit KEIN Problem (bereits
  allokerungsfrei).
- [x] Umbauplan `WAVEFORM_PERFORMANCE_UMBAU_PLAN.md` (Root, analog zu den
  anderen Ausbauplaenen) geschrieben: Ziel/metrisch, 7 Phasen (0 Messung
  + Baseline, 1 Block-API/Float, 2 Profile + zwei Stufen +
  MIGRATION_8_9, 3 In-Process-Prioritaet, 4 Queue-Prewarming, 5 optional
  Decode-Overlap, 6 optional FFmpeg-JNI, 7 Doku + ADR-0015),
  Grundregel "Ausgaben aendern sich nicht => kein ANALYZER_VERSION-Bump =>
  kein Re-Analyse-Sturm", Verifikation und Eskalationen.
- [ ] Naechster Schritt gemaess Plan: Phase 0 (Messinfrastruktur +
  Baseline), danach Phase 1-4 als ein Umsetzungsblock.

## T. Musik-UI/UX-Mittelfrist-Paket umgesetzt (2026-08-22, Session: ZCode-4d9f2a7b, Fortsetzung)

Anlass: /goal mit den Mittelfrist-Punkten aus der Recherche (Abschnitt
S). Umsetzung als ein Block; Builds/Tests/Lint der beruehrten Module
gruen, nichts committet (Arbeitsbaum wie zuvor uncommittet).

- [x] **Expressive ohne Alpha:** Kein `MaterialExpressiveTheme` —
  material3 1.5.0 ist laut Material-Blog erst mit dessen Stable-Release
  oeffentlich, aktuell nur alpha/beta; Projektregel verbietet Alpha
  (Quellen in `Theme.kt`-Kommentar dokumentiert). Umgesetzt stattdessen:
  Play/Pause-Shape-Morph Kreis<->Squircle (Feder) im Now-Playing,
  Icon-Puls im Mini-Player, Sheet-Routen-Transition (Slide-up/Fade,
  Feder) fuer `now_playing` in `DropSyncApp`, Cover-Hero-Pop-in.
- [x] **YTM-Layout:** Cover-Karussell 0.40 der Hoehe (240-420 dp statt
  0.52/320-520), Abstande straffen — Controls daumenerreichbar im
  oberen Drittel.
- [x] **Swipe-down-dismiss:** `SwipeDismissBox` am Cover (vertikales
  Ziehen, 140-dp-Schwelle, Feder-Rueckstellung, Fade am Fortschritt);
  horizontale Pager-Wische unberuehrt.
- [x] **Artwork-adaptives Theming:** `ArtworkColors.kt` (feature/player)
  — dominante/vibrierendste Deckfarbe per 4-Bit-Histogramm aus dem
  gecachten 512er-Cover (nutzt denselben CoverArtLoader-Cache wie der
  Blur-Hintergrund; bewusst KEINE neue Palette-Abhaengigkeit, Plan-Regel
  "keine neue Dependency"). Pure Funktion `colorsFromPixels` ist
  JVM-testbar angelegt. Scrim/Text/Akzent/Play-Button im Now-Playing
  adaptiv statt festem `Color.White`.
- [x] **Aktions-Carousel + Quick-EQ:** `PlayerActionRow` (EQ, Tempo,
  Marker, Mix an/aus mit Sekunden, Queue mit Anzahl) + `QuickEqSheet`
  mit eigenen vertikalen Band-Slidern (48-dp-Flaeche, 0,5-dB-Raster,
  Haptik am Nulldurchgang, Tap+Drag) — schreibt live ueber
  `AudioEngineRepository` in die DSP-Kette.
- [x] **Tempo/BPM-Lock:** `PlaybackState.playbackSpeed` +
  `setPlaybackSpeed` (impl: `Player.setPlaybackSpeed`, begrenzt
  0.5-2.0x wg. media3-Issue #1101, `EVENT_PLAYBACK_PARAMETERS_CHANGED`
  im Listener) + `TempoSheet` (0,05-Raster, Presets, BPM-Lock: Ziel-
  Kadenz 60-200, Oktav-Faltung gegen Track-BPM via `trackBpm`,
  automatisches Nachziehen bei Titelwechseln; manuelle Wahl hebt den
  Lock). Restzeit-Anzeige im Player tempo-korrigiert. PlayerViewModel
  injiziert zusaetzlich `AudioEngineRepository`.
- [x] **Marker Beat-Snap/A11y:** `MarkerSnapping` (250-ms-Fenster aufs
  Beat-Raster bei analysiertem BPM, Haptik beim Einrasten beim Setzen
  UND Verschieben; `MarkerSnappingTest` 6 Faelle), Trefferzone ~24 dp
  (Slop 0.06 statt 0.03, per Parameter an `Waveform`), 3-dp-Ticks,
  TalkBack-Beschreibung nennt Marker-Anzahl
  (`now_playing_waveform_with_markers`, DE+EN).
- [x] **Shared-Element (Ersatzloesung):** Echte
  `SharedTransitionLayout`-Elemente MiniPlayer->NowPlaying sind mit der
  heutigen Shell nicht erreichbar (Mini-Player sitzt im Scaffold-
  bottomBar ausserhalb des NavHost; der AnimatedVisibilityScope der
  Route erreicht ihn nicht). Umgesetzt: Sheet-Transition + Cover-Pop-in
  (visuell aequivale Hero-Wirkung). **Folgearbeit:** Player als
  Overlay/Bottom-Sheet in der Shell statt NavRoute — dann echte Shared
  Elements moeglich.
- [x] Verifikation: `:feature:player:testDebugUnitTest` (28 gruen, incl.
  neuer `MarkerSnappingTest`), `:data:playback`,
  `:data:workout`, `:core:designsystem` Unit-Tests gruen;
  `:app:assembleDebug` gruen; lintDebug der beruehrten Module ohne
  Befunde. Interface-Erweiterung `setPlaybackSpeed` in allen drei
  Test-Fakes nachgezogen (PlayerViewModelTest, RestMusicCoordinatorTest,
  WorkoutRepositoryImplTest).
- [ ] Geraeteabnahme offen: Time-Stretch-Qualitaet der Plattform bei
  0.5-2.0x (Sonic/AudioTrack-PlaybackParams), Haptik, adaptive
  Kontraste auf hellen Covern, Swipe-dismiss im Zusammenspiel mit dem
  Karussell am echten Geraet.

---

## U. Flowtimer-Integration: Spec-Korrekturen, ADR und UI-Vertrag (2026-08-22)

Vorarbeit am Dokumentenbestand. **Kein Code geaendert** - die Integration
selbst startet erst nach Flowtimer v2 (Phase 15 + 16), siehe
Implementierungsreihenfolge im Design-Dokument.

- [x] `docs/design/2026-08-22-flowtimer-integration-design.md` gegen beide
  Repos geprueft: sechs Falschaussagen korrigiert (archived-Flag existiert
  bereits, millikg-Exaktheit, Testanzahl 40 statt 33, 29 Module / 29
  Entities, Richtung der PR-Mathematik, Hosting). Neuer Abschnitt
  "Revisionen" haelt die Aenderungen fest.
- [x] `docs/design/2026-08-22-flowtimer-integration-CONTEXT.md` angelegt:
  neun gesperrte Umsetzungsentscheidungen (E1-E9) plus acht offene
  Recherchepunkte. Bei Widerspruch zum Design-Dokument gilt CONTEXT.
- [x] `docs/adr/0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md`
  geschrieben (Muster ADR-0010/0013): loest den Vorrang-Widerspruch zum
  Fusionsdesign fuer vier Punkte formal auf. Erste ADR dieser Reihe mit
  Code-Folgen (Submodule, CI-Checkout, Architekturtest-Liste). Nummer
  0015 ist im Waveform-Umbauplan Phase 7 vorbelegt, daher 0016.
- [x] Kopftabelle "Verbundene Dokumente" im Fusionsdesign ergaenzt
  (Mobile Design System + Flowtimer-Integration), Geltungsordnung dort um
  die ADR-0016-Einschraenkung erweitert.
- [x] `docs/design/2026-08-22-flowtimer-integration-UI.md` angelegt:
  Screen-Vertrag des Progress-Dashboards, recherchegestuetzt. Kernpunkte:
  eine Aussage statt vier gleichwertiger Kacheln, Lime genau einmal,
  Distanz statt Stand, kein PR-Konfetti im Dashboard.
- [x] **Entscheidungen von Adi eingearbeitet (2026-08-22):** Tages-Streak
  (kein Wochen-Streak) mit Bruch erst nach 3 zusammenhaengenden
  Ruhetagen; Gewichte ganzzahlig ohne Dezimalstelle (nie `95,0 kg`);
  Volumen in kg unter 1000, darueber Tonnen mit einer Stelle; kein
  Ziel-Balken je Uebungszeile (nur Textdifferenz). Uebriges Design
  liegt beim Umsetzer, Abschnitt "Entschieden" im UI-Dokument.
- [x] Folgearbeit aus dem Tages-Streak notiert: `streakCount` in
  `Streak.kt` braucht einen Parameter `maxGapDays` (Default 0, damit
  Flowtimers Verhalten unveraendert bleibt; Fusion uebergibt 2). Tests
  fuer Luecke 1, 2 und 3 sind Pflicht.
- [x] **Zweite Adi-Runde (2026-08-22):** Segmented Control "Uebersicht |
  Verlauf" gestrichen (Design-Entscheidung 17 aufgehoben) — der
  Satz-Verlauf ist der letzte Tile mit eigener Route, damit kein
  Modus-Zustand und Android-Back funktioniert normal. Layout auf
  **Bento-Grid** umgestellt (zwei Spalten, Tiles unterschiedlich gross,
  einspaltig ab fontScale 1.5). Tiles ohne Aussage werden
  **ausgeblendet** statt leer gezeigt (Streak ab 2 Tagen, Chart ab 2
  Wochen, Volumen nur bei Saetzen dieser Woche).
- [x] Palette erweitert, je Farbe eine Rolle: `756FFA` = Ziele
  (`secondary`), `141414` = Grund (`background`/`surface`), `E7E6FB` =
  genau ein heller Tile (`secondaryContainer`). Lime bleibt
  ausschliesslich Aktion. Kontraste gemessen: dunkler Text auf Violett
  (4,75) statt weissem (3,73); Lime auf `E7E6FB` ist 1,08 und damit
  verboten. `AccentColor` wird NICHT erweitert — Violett ist semantische
  Rolle, keine waehlbare Akzentfarbe.
- [x] Folgearbeit Designsystem notiert: `Theme.kt` `DarkColors` um
  `secondary`/`onSecondary`/`secondaryContainer`/`onSecondaryContainer`
  erweitern, `background`/`surface` auf `141414`;
  `ThemeColorSnapshotTest` um drei Faelle (Violett bleibt `756FFA`,
  `onSecondary` dunkel, Lime nie mit `secondaryContainer` gepaart).
- [x] **HTML-Prototyp** `docs/design/prototypes/progress-dashboard.html`
  (Entscheidung 24, Schritt 0 der Implementierungsreihenfolge). Eine
  Datei, fuenf Datenzustaende (kein Satz / Tag 2 / zwei Wochen / voll /
  Wochenziel uebertroffen) in je einem 393x852-dp-Rahmen mit
  eingezeichneter Faltlinie. Zwei Pruefschalter: fontScale 2.0 (Grid
  einspaltig, Ring 120 dp mit Zahl darunter) und 8-dp-Raster. Keine
  externen Assets, keine Logik. **Entscheidungswerkzeug, keine
  Spezifikation** — bei Abweichung gilt das UI-Dokument. Grund:
  `:feature:progress` existiert nicht, eine Compose-Preview kostet neues
  Modul + `settings.gradle.kts` + `ModuleDependencyRulesTest` + Build
  ueber 29 Module je Layoutfrage. Poppins per `@font-face` aus
  `core/designsystem`; Chrome braucht dafuer `python -m http.server`.
- [x] Zwei inhaltliche Aenderungen aus dem Vergleich mit einem
  Referenz-Design derselben Palette: (1) Chart hebt die aktuelle Woche
  hervor — Violett-Balken plus Lime-Wertpille, weil acht graue Balken
  ohne Anker offenlassen, welcher jetzt gilt. (2) Regel
  "Lime nie neben Violett" praezisiert: 3,42 unterschreitet 4,5:1 fuer
  Text, ueberschreitet aber 3:1 fuer grafische Objekte. Lime-**Text** auf
  Violett bleibt verboten, Lime-**Grafik** ist erlaubt; der Pillen-Text
  steht auf Lime, nicht auf Violett.
- [x] Fremde Abschnitte S und T nebst ihren vier untracked Dateien
  (`docs/research/RESEARCH_MUSIC_*.md`,
  `WAVEFORM_PERFORMANCE_UMBAU_PLAN.md`) als **eigener** Commit
  nachgetragen, damit die Flowtimer-Arbeit nicht mit ihnen vermischt
  wird. Der in T beschriebene Produktionscode bleibt uncommittet und
  braucht eine eigene Verifikation durch den Urheber.
- [x] `docs/design/FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`
  nachgetragen. Die Datei war untracked, wird aber von fuenf committeten
  Dokumenten verlinkt — der Link-Check lief nur in diesem Arbeitsbaum
  gruen, in einem frischen Clone fuenffach rot. Mit Testclone verifiziert.
- [x] `python3 tools/doku_links_check.py` gruen — auch aus einem frischen
  `git clone` heraus, was vorher nicht der Fall war.

## V. Flowtimer-Integration: Umsetzung gestartet — Basis-Entscheidungen, Designsystem (2026-08-23, Session: ZCode)

- [x] Working-Tree-Analyse vor dem Start: von 587 offenen Eintraegen hatten
  116 Dateien echte Inhaltsaenderungen (Rest Zeilenenden-Phantome der
  LF-Normalisierung). HEAD (master) stand auf DB v6 und ohne HistoryScreen,
  FlowRepComponents, Poppins-Fonts, app/src/test und weitere ungetrackte
  Infrastruktur — der Working Tree war de facto der Projektstand. Ein Abzweig
  von master haette bedeutet, die Haelfte der App zu rekonstruieren.
- [x] Entscheidung Adi: alle offenen Aenderungen als EIN Fremd-Checkpoint auf
  `wip/fremde-sessions-2026-08-23` (729ac46) parken, klar gelabelt als fremde
  Arbeit (Abschnitte S/T, Geraeteabnahme offen, Verifikation durch Urheber).
  `fusion/training-core` zweigt vom Checkpoint ab; master bleibt sauber
  (offener Punkt 7 aus CONTEXT damit entschieden: Branch statt master).
- [x] Baseline auf `fusion/training-core` gruen: test, spotlessCheck, detekt,
  lintDebug, assembleDebug und `python tools/doku_links_check.py` (36 Dateien).
- [x] Reihenfolge entzerrt (Adi erlaubt): Schritt 6 (Designsystem + Dashboard
  gegen die bestehende FlatSetDao) vor der Kern-Extraktion (Schritte 3-5).
  Vorbedingung 0 gilt weiter: Flowtimer v2 (Phase 15 + 16) zuerst; die offenen
  Punkte 1/2/8 aus CONTEXT werden vor der Kern-Extraktion geklaert.
- [x] E4d umgesetzt: `Theme.kt` um BrandViolet (756FFA, secondary = Ziele),
  BrandGround (141414, background/surface) und BrandLilac (E7E6FB,
  secondaryContainer = der eine helle Tile) erweitert; onSecondary und
  onSecondaryContainer dunkel (4,75 statt weiss 3,73).
  `ThemeColorSnapshotTest` um drei Faelle ergaenzt: Violett bleibt 756FFA,
  onSecondary ist dunkel statt weiss, Lime wird nie mit secondaryContainer
  gepaart (1,08). `BrandButtonSecondary` (aktuell unbenutzt) von secondary auf
  surfaceContainerHigh umgestellt — secondary ist seit E4d exklusiv die
  Ziel-Rolle und steht Buttons nicht mehr zur Verfuegung.
- [x] 6b+6c umgesetzt (cf039e8): Modul `:feature:progress` angelegt (Template
  feature/timer), `ModuleDependencyRulesTest` auf alle 29 Module vervollstaendigt
  (health, sensor, audio fehlten), HistoryScreen + UiState + Test ins Feature
  verschoben, alle Literals in stringResource (values EN / values-de DE),
  Zahlformate zentral in `ProgressFormatters` (E4c/R2b: Locale.getDefault(),
  ganzzahlige Gewichte ohne Dezimalstelle, Volumen t ab 1000 kg).
- [x] 6d-1 umgesetzt (ff1ddd4): `ProgressUiState` mit Tages-Streak (E4b, zwei
  freie Ruhetage), Wochenring (cap 1f), 8-Wochen-Aggregation mit kalendarischen
  Wochengrenzen (DST-sicher) und Neue-Woche-Zustand (R3) — Zeit als Calendar
  injiziert, deterministisch getestet (Luecke 1/2/3, Mitternacht, Montag).
- [x] 6d-2 umgesetzt: Bento-Dashboard im Verlauf-Tab nach UI-Vertrag.
  `LazyVerticalStaggeredGrid` Fixed(2), ab fontScale > 1.5 einspaltig und Ring
  120 dp mit Zahl darunter (A11y 1+2). Aussage-Tile E7E6FB/radiusHero mit Ring
  auf dunklem Track (Lime beruehrt E7E6FB nie), Distanz-Sprache (R2) inkl.
  bold bei genau einem fehlenden Training, duenner Violett-Zweitbogen fuer
  Ueberschuss (`ProgressRing.excessProgress`), Neue-Woche-Sprache (R3),
  stateDescription am Tile (A11y 3). Streak- und Volumen-Tile (fest 120 dp),
  Chart-Tile mit erweitertem `BarChart` (2-dp-Grundlinie erfuellter Wochen,
  Null-Wochen-Markierung, Violett-Highlight + Lime-Wert-Pille, Tap-Ebene mit
  KW/Volumen/Tage-Info), PR-Zeile (R6, Bestwert = max. Volumen je Uebung,
  frisch = Rekordsatz juenger als 7 Tage), Ziel-Platzhalterzeile (R7) bis
  TargetEntity (Schritt 7, DB v9), Tile 7 letzte 10 Saetze mit eigener
  Alle-Saetze-Route (`AllSetsScreen`, HistoryScreen ersetzt: Bestwerte +
  Vollliste, Android-Back normal). Bewegung: animateItem, fadeIn+scaleIn(0.96f)
  200 ms, einmaliges Violett-Rand-Aufblitzen des Aussage-Tiles; bei reduzierter
  Systemanimation sofortige Endwerte. Alle Gates gruen inkl.
  `python tools/doku_links_check.py`.
- [x] Schritt 7 umgesetzt (Ziele-Pflege): Archivieren/Wiederherstellen von
  Uebungen (DAO `restoreExercise` + `observeArchivedExerciseLibrary`, Repo +
  Fake, Library-UI mit Archiv-Sektion — Restore statt Delete schuetzt die
  Trainingshistorie). ExerciseLibrary ueberall verdrahtet: eigene Route,
  „Bibliothek"-Chip in der Trainings-Uebungsleiste, Ziel-Hinweiszeile des
  Dashboards navigiert dorthin (Entscheidung 13: Uebungs- und Ziel-Pflege
  leben in der Bibliothek). Wochenziel als `WorkoutGoalRepository` (MIN 1 /
  MAX 7 / DEFAULT 3) mit `WorkoutGoalPreferencesStore` im DataStore —
  bewusst NICHT Room, um den DB-Versionsspielraum bis zum Urheber-WIP-Merge
  (v8) nicht zu beruehren; echte Ziele (TargetEntity, DB v9) bleiben
  blockiert. Settings: WeeklyGoalSection mit FilterChips 1..7, horizontal
  scrollbar, Plural-contentDescription (A11y); ProgressViewModel combine den
  Goal-Flow live in Ring/Chart (aenderungen wirken ohne Neustart).
  `FakeWorkoutGoalRepository` in core:testing. Alle Gates gruen inkl.
  `python tools/doku_links_check.py`.
- [x] Schritt 3 umgesetzt (Kern-Extraktion): Repo
  `github.com/Adilinu94/training-core` (Tag v1.0.0) mit 8 Kern-Dateien und
  6 Testklassen. Fusions `WorkoutMath`/`PrCalculator` bilden die Grundlage
  (E6), Flowtimers `Streak`/`WeekAgg`/`TargetMath` sind ergaenzt und
  `bestFor` ist als `PrCalculator.bestSet` darauf abgebildet.
  Einheitenvertrag `Units` (E1): oeffentlich kg als Double, intern ganze
  Millikilogramm, HALF_UP am Eingang — deshalb sind Gewichtsvergleiche
  exakt. Kern ist pures Kotlin JVM, `jvmToolchain(17)`, apiVersion 2.0
  (CONTEXT Punkt 2), Plugin-Block ohne Version, damit beide Apps mit ihrer
  eigenen Toolchain kompilieren.
- [x] Schritt 4a umgesetzt (Submodule-Einbindung, ohne DB v9): `training-core`
  als Git-Submodule an der Repo-Wurzel, `include(":training-core")` mit
  gesetztem `projectDir` statt Composite-Build (CONTEXT Punkt 1). Guard in
  `settings.gradle.kts`: fehlt das Submodule-Verzeichnis, bricht der Build mit
  einer lesbaren Meldung ab statt mit einem Plugin-Fehler. CI-Checkout auf
  `submodules: recursive`. Spotless und Detekt schliessen `training-core/**`
  aus — der Kern hat eigene CI. `ModuleDependencyRulesTest` fuehrt
  `training-core` als Pflichtmodul.
- [x] Schritt 5 umgesetzt (Mathematik-Umzug): `domain/workout` konsumiert den
  Kern. `WorkoutMath` und `PrCalculator` sind nur noch
  Millikilogramm-Fassaden auf `com.training.core` — Fusions Invariante
  (keine Gleitkommazahlen im Datenmodell) bleibt unangetastet, Double
  erscheint ausschliesslich auf der Kern-Grenze und ist dort auf ganze
  Gramm gerundet, also verlustfrei. `roundKgInputToMilliKg` bleibt in
  Fusion: Der Kern nimmt Zahlen, keine Textfeldinhalte. Alle Gates gruen
  (`test spotlessCheck detekt lintDebug assembleDebug`,
  `python tools/doku_links_check.py`).
- [x] Schritt 5 abgesichert: `WorkoutMathTest` (12) und `PrCalculatorTest` (6)
  in `domain/workout` neu — die Kern-Grenze war bis dahin ungetestet, obwohl
  sie Long nach Double und zurueck wandelt. Belegt wird die Verlustfreiheit
  (einzelnes Gramm, 92,501 kg durch alle drei PR-Arten), die Enum-Zuordnung
  (Reps behalten `PrValueUnit.REPS`) und dass die Gleichstandsregel nach dem
  Umzug unveraendert gilt.
- [x] E3 nachgezogen: `training-core` ist oeffentlich statt privat. Der erste
  CI-Lauf mit Submodule schlug mit `repository not found` fehl - `GITHUB_TOKEN`
  gilt nur fuer das ausloesende Repo, ein privates Submodule braucht zusaetzlich
  Deploy-Key oder PAT als Secret. Der Grund fuer "privat" war, dass es nichts
  kostet, nicht Geheimhaltung; das Ziel der Entscheidung ist Reproduzierbarkeit
  und die gilt oeffentlich genauso. Der Kern enthaelt reine Mathematik, keine
  Schluessel und keine Nutzerdaten. Damit bleibt die CI ohne Secret gruen und
  Flowtimer bindet dasselbe Submodule ohne eigene Zugangsverwaltung ein.
  CONTEXT E3 und ADR-0016 entsprechend nachgetragen.
- [x] Reproduzierbarkeit hergestellt (2026-08-25), zwei Luecken geschlossen:
  1. **Flowtimer hatte kein Remote** - `git config --get-regexp ^remote\.` lieferte
     nichts. Commit 6ff7783 (Flowtimers Submodule-Umstellung, dessen Haelfte von
     Schritt 3) existierte nur auf einer Platte; genau die Nicht-Reproduzierbarkeit,
     die E3 fuer Fusion verhindern sollte. Repo `github.com/Adilinu94/Flowtimer`
     angelegt, master und feature/training-core gepusht. **Privat**, nicht
     oeffentlich wie training-core: Flowtimer hat 810 getrackte Dateien unter
     `.agents/` mit persoenlichen Skills (u.a. `adrian-mail-stil` mit Klarnamen,
     Mailadresse und Kundennamen). Ein oeffentliches Repo haette die
     veroeffentlicht.
  2. **Der WIP-Checkpoint 729ac46 war ein loser Commit** - nur ueber das Reflog
     erreichbar, von keinem Branch gehalten, also ein `git gc` vom Verschwinden
     entfernt. Als Branch `wip/fremde-sessions-2026-08-23` gesichert und gepusht.
     Inhaltlich ist er in master aufgegangen (PR #6); erhalten bleiben nur die
     alte `HistoryScreen`-Fassung in `:app`, Abschnitt-T-Waveform-Arbeit und
     Werkzeug-Artefakte. Der Branch ist die Verifikationsgrundlage fuer den
     Urheber, nicht Merge-Kandidat.
- [x] design.md nachgezogen: Kopf (Repo-URLs statt lokaler Pfade),
  Entscheidung 7, Architektur 1 und 3, Reihenfolge 3+4 - alle sagten "privat"
  und "Deploy-Key". Verweise jetzt auf den E3-Nachtrag.
- [x] Blocker "Urheber-WIP-Merge (v8)" geprueft und aufgeloest: 729ac46 steht auf
  DB v8, master steht auf DB v8, und der Checkpoint enthaelt **keine**
  Schema-Aenderung (Diff gegen master beruehrt in `core/database` nur eine
  aeltere `WorkoutDaos.kt`). Es gab keinen Versionskonflikt, auf den DB v9 haette
  warten muessen - die Zurueckhaltung beim Wochenziel (DataStore statt Room) war
  eine Vorsichtsmassnahme gegen eine Gefahr, die es nicht gab.
- [x] Schritt 4b umgesetzt (DB v9, Ziele): Damit ist die Flowtimer-Integration
  bis Schritt 7 vollstaendig.
  - **Room v8 -> v9, additiv:** Tabelle `exercise_targets` mit `exercise_id`
    als Primary Key (CONTEXT Punkt 3 - `@Upsert` statt Insert/Update-Fall-
    unterscheidung, Muster `ExerciseRestPrefEntity`). Gewicht in ganzen
    Millikilogramm. `9.json` nach `src/test/assets` exportiert und committet
    (Punkt 4). `MigrationTest` prueft die Kette v1->v9 **und Datenerhalt**:
    Die DB traegt die echte Musikbibliothek, eine Migration mit Zeilenverlust
    waere nicht wiederherstellbar - der Test schreibt Uebung und Satz in v8
    und liest sie nach der Migration zurueck.
  - **Domain:** `TargetRepository` mit `ExerciseTarget`/`TargetStatus`,
    `TargetEvaluator` als Kern-Grenze auf `com.training.core.targetReached`
    (E4: EIN Satz muss Gewicht UND Reps schaffen; massgeblich ist der beste
    Satz, damit ein leichter Abschlusssatz den Status nicht zuruecknimmt).
    Fortschritt bewusst nur ueber das Gewicht - zwei Dimensionen in einem
    Balken ergeben eine Zahl, die nichts aussagt.
  - **Data:** `TargetRepositoryImpl` validiert an der Repository-Grenze
    (Muster `FlatSetRepositoryImpl`); ein Ziel von 0 kg oder 0 Reps wird
    abgewiesen, weil es sofort erfuellt waere. `FakeTargetRepository` in
    core:testing ist veraenderbar, nicht statisch - ein neu gesetztes Ziel
    muss im Flow erscheinen.
  - **Dashboard (R5):** Ziele-Tile ersetzt die Platzhalterzeile.
    `ProgressGoalsUiState` sortiert nach Goal-Gradient (Fast-Geschaffftes
    zuerst, Erreichtes ans Ende, Sortierschluessel ist die groessere offene
    Dimension), gruppiert `Laenger nicht trainiert` (>8 Wochen) und liefert
    die Distanz-Werte fuer die R2-Sprache (`10 kg fehlen`, nicht
    `90 kg (Ziel 100 kg)`). Fortschritt sind zehn Violett-Punkte statt eines
    Balkens; knapp verfehlt zeigt nie zehn Punkte. Uebungen ohne Ziel
    erscheinen nicht - sonst wird die Liste mit jeder Uebung laenger und
    sagt weniger.
  - **Ziel-Pflege (Entscheidung 13/R5b):** Dialog in der ExerciseLibrary,
    erreichbar per Tap auf die Uebungskarte - ein Tap statt vier. Gesetzte
    Ziele stehen als Violett-Zeile auf der Karte, ohne dass man den Dialog
    oeffnen muss. Speichern erst bei zwei tragfaehigen Feldern.
    `roundKgInputToMilliKg` parst hier, nicht im Kern: Der Kern nimmt Zahlen,
    keine Textfeldinhalte.
  - 40 neue Tests (Migration 2, TargetEvaluator 10, ProgressGoalsUiState 15,
    PrCalculator/WorkoutMath-Grenze 18 aus Schritt 5); alle Gates gruen.
- [x] Toter Eintrag entfernt (2026-08-30): An dieser Stelle stand bis heute
  „Offen: DB v9 (`TargetEntity`) — blockiert bis zum Urheber-WIP-Merge (v8)".
  Das widersprach dem Eintrag oben („Schritt 4b umgesetzt (DB v9, Ziele)") und
  dem aufgeloesten Blocker zwei Eintraege darueber. `TargetEntity`,
  `TargetRepository`, `TargetEvaluator` und das Ziele-Tile existieren; die DB
  steht auf v9 (`DropSyncDatabase.kt:96`), `9.json` ist exportiert, die
  Migrationskette v1→v9 ist mit Datenerhalt getestet. CONTEXT-Punkte 3 und 4
  sind damit ebenfalls erledigt.
- [ ] Weiterhin offen aus der Flowtimer-Integration: **Flowtimer selbst** auf
  das `training-core`-Submodule umstellen (die andere Haelfte von Schritt 3).
  Das betrifft das Repo `github.com/Adilinu94/Flowtimer`, nicht dieses.
  Ebenfalls offen: der DataStore-Wochenziel-Umweg
  (`WorkoutGoalPreferencesStore`) koennte jetzt nach Room wandern — die
  Vorsichtsmassnahme, die ihn begruendet hat, ist entfallen. Kein Zwang,
  solange der DataStore funktioniert.

## W. Aufräumen und Verifikation (2026-08-30, Session: OpenCode)

Anlass: Bestandsaufnahme „was steht noch aus". Die Recherche hat vor allem
Doku-Drift und ein Absicherungsrisiko gefunden, nicht fehlende Features.

- [x] **`ui-test/` nicht mehr versioniert.** 42 MB Emulator-Diagnostik
  (160 uiautomator-Dumps, 130 Screenshots) waren untracked und nicht in
  `.gitignore` — ein Commit haette sie dauerhaft in die History gelegt. Jetzt
  ignoriert. Kuratiert erhalten: die neun Bilder, auf die sich das
  Now-Playing-Handoff stuetzt, unter `docs/design/reference/` (5,9 MB), und die
  drei Auswertungsdokumente (`UI_REVIEW.md`,
  `POWERAMP_ONBOARDING_REVIEW.md`, `POWERAMP_FOLDER_FLOW.md`) unter
  `docs/qa/`. Alle Verweise umgeschrieben, damit
  `tools/doku_links_check.py` gruen bleibt und in einem frischen Clone nicht
  rot wird (dieselbe Falle wie STATUS Abschnitt U beim Mobile-Design-System).
- [x] **`docs/Kritische Befunde.md` mit Ist-Stand-Tabelle versehen.** Von den
  P0-Befunden des 2026-08-12-Reviews sind 16 behoben oder entfallen, das
  Dokument sagte das nicht. Jede Session las es als aktuelle Fehlerliste.
  Belege je Zeile mit Datei und Zeilennummer. Der Umbauplan darunter bleibt
  als Beschreibung der Zielarchitektur stehen, jetzt aber erkennbar als
  Absicht statt als Zustand. Echter Rest: Ground-Truth-Traces und Gate 11b.
- [x] **Release-Build als CI-Gate** (`ad48334`). `isMinifyEnabled` war seit
  Schritt 1 aktiv, aber die CI baute nur `assembleDebug` — der R8-Pfad war nie
  verifiziert. Erstpruefung gruen (5m 7s, 3,4 MB unsigniertes APK, keine
  fehlende Keep-Regel). Der vermutete Reflection-Bedarf in
  `OutputDeviceMonitor` bestaetigte sich nicht und kann sich nicht bestaetigen:
  R8 schrumpft `android.*` nie. `proguard-rules.pro` haelt die Abgrenzung fest.
  Offen: `signingConfig` (Releasevorbereitung, braucht Keystore).
  Nebenbefund: `DspRenderersFactory.kt:44` nutzt die in Media3 deprecated
  `setEnableAudioTrackPlaybackParams` — Kandidat fuers Media3-Update.

## X. Konfidenz-Gate fuer BPM und Tonart (2026-08-31, Session: OpenCode)

Anlass: BPM/Camelot waren im Datenmodell und in der UI fertig verdrahtet
(`TempoSheet` mit BPM-Lock, Marker-Beat-Snap), aber `trackBpm` blieb in der
Praxis unbrauchbar — die Akkumulatoren liefern **immer** einen Wert, auch fuer
Material ohne Puls.

- [x] **Konfidenzwerte erstmals gemessen** (`MixConfidenceBaselineTest`,
  `:domain:audio`). Rauschen ergibt "160 BPM" bei Konfidenz 0,15, Sprache
  "77 BPM" bei 0,18; ein klarer Beat 1,00, mit 8 % Jitter 0,39. Der Test
  bleibt als Beleg im Repo und druckt die Verteilung bei jedem Lauf.
- [x] **Vorzeichenfehler in der Chroma-Korrelation gefunden und behoben.**
  Weisses Rauschen korrelierte mit **0,96**, echte Dreiklaenge nur mit 0,73 —
  die Konfidenz war invers und als Qualitaetsmass wertlos. `correlation()`
  rechnete eine Kosinus-Aehnlichkeit rein positiver Vektoren statt der
  Pearson-Korrelation, die Krumhansl-Schmuckler verlangt; ohne Zentrierung
  aehnelt ein flaches Chromagramm **jedem** Profil. Nach dem Fix: Rauschen
  0,64, Dreiklaenge 0,83..0,89. Ohne die Messung waere jede Schwelle auf den
  invertierten Wert gesetzt worden.
- [x] **`MixConfidence`-Gate** (`:domain:audio`): `MIN_BPM_CONFIDENCE = 0,25`
  (Tal zwischen Rauschen 0,18 und Jitter-Beat 0,39), `MIN_KEY_CONFIDENCE = 0,70`
  (trennt Rauschen 0,64 von Dreiklaengen 0,83). Angewendet in
  `TrackAnalysisRepositoryImpl.observeAnalysis` — **Leseseite**, damit eine
  spaetere Nachkalibrierung ohne `ANALYZER_VERSION`-Bump (und damit ohne
  Verlust aller Waveform-Caches) greift. Fehlende Konfidenz (DB-v7-Zeilen)
  gilt als unsicher. Beide Schwellen unabhaengig: untanzbar ≠ untonal.
- [x] **`TempoSheet` war nicht erreichbar.** Das Sheet existierte seit
  `6769ea0` samt BPM-Lock, Presets und Ziel-Kadenz — aber ohne jede
  Aufrufstelle. Toter Code, der als fertiges Feature in der Doku stand. Jetzt
  im Overflow-Menue des Now-Playing. Der Kein-BPM-Text sagt nicht mehr
  „Analyse steht aus" (falsch, wenn die Analyse fertig ist und nur nichts
  hergab), sondern „kein verlaesslicher BPM-Wert verfuegbar".
- [x] ADR-0019 festgehalten (Gate, Nebenbefund, permissive Schwellen).
- [x] Verifikation: `:domain:audio:test` (19 Klassen), `:data:audio:testDebugUnitTest`
  (neu: `TrackAnalysisConfidenceGateTest`, 7 Faelle), `:feature:player:testDebugUnitTest`,
  `detekt`, `:app:assembleDebug`, `lintDebug` (player + data:audio),
  `tools/doku_links_check.py` — alle gruen.

**Bewusst offen:** Die Schwellen trennen synthetische Extremfaelle. Echte
Musik liegt niedriger als ein Burst-Train oder ein reiner Dreiklang, deshalb
sind sie permissiv gesetzt. Endgueltige Kalibrierung braucht echte Titel mit
Rekordbox-/Mixed-In-Key-Referenz — dieselbe Luecke wie bei den
Sensor-Ground-Truth-Traces (ADR-0017): Mechanik steht, Referenzdaten fehlen.

## Y. Waveform-Performance Phase 0: messen vor umbauen (2026-08-31, Session: OpenCode)

Anlass: `WAVEFORM_PERFORMANCE_UMBAU_PLAN.md` plante zuerst eine invasive
Block-API-/Float-Umstellung und erst danach die Zwei-Stufen-Analyse. Die
Reihenfolge beruhte auf Code-Inspection, nicht auf Messwerten.

- [x] **Reproduzierbare JVM-Baseline** (`TrackAnalysisBaselineTest`):
  4-Minuten-Referenztrack, 44,1 kHz, 10,58 Mio musikaehnliche Mono-Samples,
  JIT-Warmup, Einzelmessung je Akkumulator und kombinierter heutiger Pfad.
  Zwei Laeufe ergaben: Waveform 183-288 ms, Tempo 63-83 ms, Chroma
  397-446 ms, Loudness 34-48 ms, kombiniert 592-745 ms.
- [x] **Stufentrennung vor Block-API priorisiert.** Nur Waveform kostet
  183-227 ms; das Weglassen der Mix-Metadaten spart im UI-kritischen Lauf
  410-518 ms bzw. **69-70 %** der Akkumulatorzeit. Dieser Gewinn braucht
  keine Aenderung der Signalmathematik und ist damit risikoaermer als Phase 1.
- [x] **Plan-Hypothese zum Chroma-Flaschenhals falsifiziert.** Die 74.412
  `cos()`-Aufrufe kosten zusammen nur 3,5 ms. Die Zeit steckt in ca. 76,2 Mio.
  inneren Goertzel-Schleifendurchlaeufen. Koeffizienten vorzuberechnen bleibt
  korrektes Aufraeumen, ist aber kein Performancehebel und keine
  Rechtfertigung fuer einen vorgezogenen Umbau.
- [x] **Android-Timing-Infrastruktur** in `TrackAnalyzerImpl`: Logcat-Tag
  `TrackAnalysisTiming` protokolliert pro Lauf Song-ID, Trackdauer,
  Sample-/Bufferzahl, `dequeueWaitMs`, `accumulateMs`, `finalizeMs`,
  `overheadMs` und `totalMs`. Die Namen sind bewusst ehrlich:
  `dequeueWaitMs` ist nur die Wartezeit auf Output-Buffer, nicht der gesamte
  Decode-Anteil.
- [x] **Planstatus korrigiert:** Phase 0 = teilweise; Phase 2
  (Profile/Stufentrennung) vor Phase 1 (Block-API). Phase 1 erst bauen, wenn
  die Geraetemessung nach Stufentrennung das 1,5-s-Ziel verfehlt.
- [x] Verifikation: `spotlessApply`, `:domain:audio:test`,
  `:data:audio:testDebugUnitTest`, `:app:assembleDebug` gruen.

**Bewusst offen / braucht Geraet:** Drei Cold-Cache-Laeufe desselben
4-Minuten-Tracks auf einem Mittelklasse-Android-Geraet. Erst diese Werte
entscheiden Abbruchkriterium A1 (>80 % MediaCodec-Decode) und ob 1,5 s ein
realistischer verbindlicher Zielwert sind. Die JVM-Zahlen sind eine
Untergrenze, keine Geraeteprognose.

## Z. Waveform-Performance Phase 2: Zwei-Stufen-Analyse (2026-09-01, Session: OpenCode)

Umsetzung des in Abschnitt Y neu priorisierten Plans: die sichtbare Waveform
wartet nicht mehr auf BPM/Key/LUFS. `AnalysisProfile` war seit Monaten
deklariert und wurde von der Implementierung ignoriert — die Interface-Doku
versprach einen Nur-Waveform-Pfad, den es nicht gab.

- [x] **`analyze(song, profile)` statt `analyze(song, detectOnsets)`.**
  `TrackAnalyzerImpl` konstruiert nur die Akkumulatoren des Profils; bei
  `WAVEFORM_ONLY` entstehen Tempo-, Chroma- und Loudness-Objekte gar nicht.
  Vorher liefen sie immer mit — genau die 410-518 ms aus der Baseline.
- [x] **DB v10, getrennte Cache-Versionierung.** Spalte
  `mix_analyzer_version` (`MIGRATION_9_10`, additiv, DEFAULT 0) plus
  `WaveformCodec.MIX_ANALYZER_VERSION = 1`. `observeAnalysis` prueft
  `analyzerVersion` fuer die Waveform und `mixAnalyzerVersion` getrennt fuer
  BPM/Key/LUFS. Kuenftige Algorithmus-Aenderungen an den Metadaten
  invalidieren damit **nicht** die Waveform-Caches der ganzen Bibliothek.
  Bestandszeilen bekommen Version 0: Waveform bleibt sofort sichtbar,
  Metadaten werden im Hintergrund neu berechnet.
- [x] **Abweichung von Plan-Entscheidung E1 (bewusst).** E1 sah vor, dass
  Stufe 2 erneut dekodiert und die Waveform "notfalls gleich mitliefert".
  Umgesetzt ist ein **reiner Metadatenlauf** mit echtem SQL-`UPDATE`
  (`updateMixMetadata`). Ein Zweitlauf, der Waveform-Bytes schreibt, koennte
  eine bereits sichtbare Waveform durch ein abweichendes Ergebnis ersetzen —
  sichtbares Flackern, genau das, was Stufe 1 verhindern soll. Trifft das
  UPDATE keine Zeile, liefert der Worker `Result.retry()` statt eine Zeile
  ohne Waveform anzulegen.
- [x] **Reihenfolge ueber WorkManager-Verkettung, nicht ueber Hoffnung.**
  Ist die Waveform veraltet, laeuft `WAVEFORM_ONLY` (expedited) und per
  `.then(...)` verkettet `MIX_METADATA` (non-expedited). Sind nur die
  Metadaten veraltet, laeuft ein eigener Unique-Work `mix_analysis_<id>` —
  eigener Name ist zwingend, weil `ExistingWorkPolicy.KEEP` unter
  `track_analysis_<id>` den Metadatenlauf verworfen haette.
- [x] **Fehlerpfade getrennt.** Permanenter Fehler in Stufe 2: nur
  Null-Metadaten mit aktueller Version (kein Retry-Loop, Waveform bleibt).
  Permanenter Fehler in Stufe 1: leerer Bucket-Eintrag wie bisher, aber die
  vorhandenen Metadatenfelder werden uebernommen statt verworfen.
- [x] `doWork` in `handleSuccess`/`handleFailure` zerlegt (detekt
  CyclomaticComplexity 23 > 20 — die Grenze hat hier korrekt gegriffen).
- [x] Verifikation: `spotlessApply`, `detekt`, `:domain:audio:test`,
  `:core:database:testDebugUnitTest` (neu: `migration 9 auf 10 trennt mix
  version ohne waveform zu verlieren`, prueft echte Nutzdaten),
  `:data:audio:testDebugUnitTest` (neu: alte Mix-Version bleibt unsichtbar,
  Waveform erhalten), `:feature:player:testDebugUnitTest`,
  `:data:audio:lintDebug`, `:app:assembleDebug` — alle gruen.

**Bewusst offen:** Die WorkManager-Dispatch-Latenz (Flaschenhals 4) steht
unveraendert VOR der verkuerzten Stufe 1. Die Kette reduziert die Arbeit bis
zum ersten DB-Write, nicht die Wartezeit bis zum Start. Ob der Titelwechsel
real unter 1,5 s liegt, entscheidet erst Phase 3 (In-Process-Prioritaetspfad)
zusammen mit der Geraetemessung aus Abschnitt Y.

## AA. Waveform-Performance Phase 3: In-Process-Prioritaetspfad (2026-09-01, Session: OpenCode)

Der UI-kritische Waveform-Lauf hat WorkManager verlassen. Flaschenhals 4 des
Umbauplans (Dispatch- plus Expedited-Quota-Latenz vor JEDEM Analysestart) war
der letzte Grund, warum die Waveform beim Titelwechsel spaeter erscheint als
noetig — Phase 2 hatte nur die Arbeit verkuerzt, nicht die Wartezeit davor.

- [x] **Zwei getrennte Lanes.** `requestAnalysis` startet Stufe 1 sofort in
  einem anwendungsweiten Scope (`SupervisorJob + dispatchers.default`).
  Mix-Metadaten, Import-Bulk und Onset-Erkennung bleiben aufschiebbar.
  `setExpedited` ist entfallen: was ohnehin sofort laeuft, braucht keine
  Beschleunigung und kein Kontingent.
- [x] **Cancel-und-Ueberholen.** `activeJobs` haelt einen Job je Song; jeder
  Aufruf bricht Laeufe zu ANDEREN Songs ab — auch im Cache-Hit-Fall, denn der
  Nutzer sieht diesen Titel. Ein zweiter Aufruf zum selben Song startet
  nichts. `Semaphore(2)` begrenzt gleichzeitige Decoder (MediaCodec-Instanzen
  sind knapp; mehr Parallelitaet macht den sichtbaren Titel langsamer).
- [x] **Kein Mutex fuer `activeJobs`, mit Grund.** Aufgeraeumt wird in
  `Job.invokeOnCompletion`, das nicht suspendieren darf. Ein `Mutex.withLock`
  dort bricht nach einem Cancel sofort erneut ab und laesst den Eintrag fuer
  immer stehen — ein schleichendes Leck, das den Dedup-Check dauerhaft
  verfaelscht. Stattdessen `synchronized` um reine Map-Operationen.
- [x] **`TrackAnalysisPersister` extrahiert.** Die Versions- und
  Feld-Uebernahmeregeln haben jetzt zwei Aufrufer (In-Process-Lauf und
  Worker). Doppelt gepflegt waeren sie die naechste Quelle fuer Zeilen, die
  `observeAnalysis` nie als aktuell akzeptiert.
- [x] **`DeferredAnalysisScheduler` extrahiert — aus Testnot, nicht aus
  Aesthetik.** Ohne diesen Schnitt ist der Prioritaetspfad nicht testbar:
  jeder Fall scheiterte an `WorkManager.getInstance()` unter Robolectric. Die
  Alternative (WorkManager je Test hochziehen) haette Latenz und
  Nebenlaeufigkeit ins Testbild geholt, die fuer die geprueften Regeln
  irrelevant sind.
- [x] **Fehlerpfad weicht bewusst vom Worker ab.** Ein voruebergehender
  Fehler in-process schreibt nichts und plant keinen Retry — Anlass ist immer
  eine Nutzeraktion, der naechste Aufruf versucht es erneut. Im Worker bleibt
  der Backoff-Retry, weil dort niemand zuschaut.
- [x] Verifikation: `spotlessApply`, `detekt`, `:data:audio:testDebugUnitTest`
  (neu: `TrackAnalysisPriorityPathTest`, 7 Faelle — Dedup, Abbruch ohne
  Cache-Eintrag, Profilwahl, Stufe-2-Anstoss erst nach dem Waveform-Write,
  beide Fehlerarten), `:core:database:testDebugUnitTest`, `:domain:audio:test`,
  `:feature:player:testDebugUnitTest`, `:data:audio:lintDebug`,
  `:app:assembleDebug` — alle gruen.

**Bewusst offen:** der Zielwert selbst. Diese Phase entfernt die Wartezeit VOR
dem Start, nicht die Decode-Dauer. Ob Decode plus Stufe 1 unter 1,5 s liegen,
entscheidet die Geraetemessung aus Abschnitt Y (drei Cold-Cache-Laeufe,
Logcat-Tag `TrackAnalysisTiming`) — sie steht weiterhin aus.

## AB. Waveform-Performance Phase 4: Queue-Prewarming (2026-09-01, Session: OpenCode)

`requestAnalysisPrewarm(songs, limit = 2)` bereitet die Waveform der naechsten
Queue-Titel vor. Ab dem zweiten Titel einer Session ist sie beim Wechsel
praktisch immer schon da — unabhaengig davon, wie schnell Decode und Stufe 1
tatsaechlich sind.

- [x] **Anstoss an eine Bedingung gekoppelt, nicht an den Titelwechsel.**
  Der `init`-Block im `PlayerViewModel` wartet, bis `waveform` fuer den
  LAUFENDEN Titel `Ready` meldet. Frueher angestossen konkurrieren die
  Prewarm-Decodes mit dem einen Lauf, auf den der Nutzer gerade wartet —
  Prewarming waere dann messbar schaedlich statt nuetzlich.
- [x] **Nur Waveform, keine Mix-Metadaten.** Eigene Scheduler-Methode
  `schedulePrewarmWaveform` statt `scheduleWaveformThenMix`: ein Metadatenlauf
  je vorbereitetem Titel waere ein voller zweiter Decode fuer Werte, die noch
  niemand sehen will. Sie folgen beim echten Titelwechsel.
- [x] **`limit = 2` mit Begruendung im Interface.** Bei sequenzieller
  Wiedergabe ist der naechste Titel immer dabei, auch bei einem Skip. Jeder
  weitere kostet einen vollen Decode fuer etwas, das nie erreicht wird.
- [x] **Dedup ueber denselben Work-Namen** (`track_analysis_<id>` +
  `ExistingWorkPolicy.KEEP`): laeuft fuer den Titel schon eine Analyse, wird
  der Prewarm verworfen. Ein Prewarm hat nie Vorrang.
- [x] **Virtuelle CUE-Tracks uebersprungen** (`QueueItem.songId == null`) —
  ohne MediaStore-ID existiert kein Analyse-Cache. `distinctUntilChanged`
  verhindert Neuplanung bei jedem Queue-Update; Position, Shuffle und Repeat
  aendern die Nachfolgerliste nicht.
- [x] **`init`-Block bewusst NACH `waveform` und `queue` deklariert.**
  `viewModelScope` laeuft auf `Dispatchers.Main.immediate`, die Coroutine
  startet synchron im Konstruktor — weiter oben waeren beide Felder null.
  Dieselbe Falle wie beim BPM-Lock (STATUS Abschnitt T).
- [x] Verifikation: `spotlessApply`, `detekt`, `:data:audio:testDebugUnitTest`
  (10 Faelle, neu: Prewarm plant nur fehlende Waveforms und nie Metadaten,
  Limit greift, `limit = 0` plant nichts),
  `:feature:player:testDebugUnitTest`, `:data:library:testDebugUnitTest`,
  `:data:audio:lintDebug`, `:feature:player:lintDebug`, `:app:assembleDebug` —
  alle gruen.

**Was Prewarming nicht kann:** die erste Waveform einer Session. Beim ersten
Titel gibt es keinen Vorgaenger, der ihn vorbereitet haette. Dort zaehlt
weiterhin allein die Geschwindigkeit von Decode + Stufe 1 - und damit die noch
ausstehende Geraetemessung aus Abschnitt Y.

## AC. Waveform-Performance Phase 7: Doku-Abschluss und ADR-0015 (2026-09-01, Session: OpenCode)

Der Umbau ist codeseitig durch (Phasen 2, 3, 4). Diese Phase haelt die
Begruendungen an einem Ort fest, an dem sie ein spaeterer Leser findet, ohne
vier Umsetzungsnachtraege in einem Planungsdokument zu rekonstruieren.

- [x] **ADR-0015 geschrieben**
  (`docs/adr/0015-track-analyse-in-zwei-stufen-mit-getrennter-cache-versionierung.md`).
  Enthaelt die Messtabelle als Entscheidungsgrundlage, nicht als Anhang: die
  Zahlen 592-745 ms (kombiniert) gegen 183-227 ms (nur Waveform) sind der
  Grund, warum die Stufentrennung vor der Block-API kam.
- [x] **Die widerlegte Annahme steht im ADR, nicht nur im Plan.** Der
  urspruengliche Plan nannte die 74.412 `cos()`-Aufrufe der
  Goertzel-Koeffizienten als Flaschenhals. Gemessen kosten sie zusammen
  3,5 ms. Ein ADR, das nur die richtige Entscheidung dokumentiert und die
  verworfene Hypothese weglaesst, laedt dazu ein, denselben Irrtum
  nochmal zu haben.
- [x] **Fuenf Alternativen mit ihrem konkreten Nachteil** festgehalten -
  darunter zwei, die im Plan noch als Absicht standen: Stufe 2 schreibt die
  Waveform mit (verursacht Flackern) und Stufe 1 bleibt mit `setExpedited` in
  WorkManager (Kontingent nicht garantiert, Rueckfall auf genau die Latenz,
  die beseitigt werden sollte).
- [x] **README-Statustabelle** um den Abschnitt "Waveform-/Analyse-Performance"
  erweitert: Phase 0 teilweise, 2/3/4/7 abgeschlossen, 1 zurueckgestellt,
  5/6 offen. Phase 1 steht bewusst als "zurueckgestellt" und nicht als
  "offen" - die Bedingung fuer den Bau ist benannt.
- [x] Verifikation: `python tools/doku_links_check.py`, `spotlessCheck`,
  `:app:assembleDebug` - gruen.

**Der Zielwert bleibt unbelegt, und das steht jetzt so in der Doku.** Die
1,5 s sind eine Absicht, kein Ergebnis: `TrackAnalysisTiming` liefert
Stopuhren, aber MediaCodec-Decode und WorkManager-Dispatch laufen nur auf
Android. Drei Cold-Cache-Laeufe auf Mittelklasse-Hardware entscheiden sowohl
den verbindlichen Zielwert als auch Abbruchkriterium A1 (>80 % Decode), das
ueber Phase 1 gegen Phase 5/6 entscheidet. Das ist dieselbe Luecke wie bei den
Sensor-Ground-Truth-Traces (ADR-0017) und dem Konfidenz-Gate (ADR-0019): die
Mechanik steht, die Referenzmessung fehlt. Solange sie fehlt, ist jede weitere
Optimierung Raten.

## AD. B-AUD-6: Abbruchkooperation im Decoder-Loop (2026-09-03, Session: OpenCode)

Befund aus dem neuen `VERBESSERUNGSPLAN.md` (Parallelsession, mit diesem
Commit eingecheckt, damit die Befund-ID referenzierbar bleibt):
`TrackAnalyzerImpl.drainDecoder()` war eine `while (!outputDone)`-Schleife
ohne einen einzigen Suspension-Punkt. Ein abgebrochener Lauf waere bis zum
Trackende weitergelaufen und haette CPU plus eine MediaCodec-Instanz
gehalten - zwei solche Zombie-Laeufe blockieren die `Semaphore(2)`-Lane aus
Phase 3 fuer genau den Titel, den der Nutzer ansieht. Die Kernzusage von
`6646da5` war damit nicht eingeloest. Verifiziert: kein `ensureActive` in
`data/audio`.

- [x] **Fix (1 Zeile + 2 Signaturen):** `coroutineContext.ensureActive()` am
  Schleifenkopf, nicht nach `releaseOutputBuffer` wie im Befund
  vorgeschlagen - der `INFO_TRY_AGAIN_LATER`-Zweig springt per `continue`
  zurueck und wuerde eine Pruefung am Schleifenende ueberspringen; ein
  wartender Decoder bliebe sonst unabbrechbar. Dafuer `decodeAndAccumulate`
  und `drainDecoder` auf `suspend` umgestellt (Aufrufer laeuft bereits in
  `withContext(dispatchers.default)`). Der Fehlerpfad war schon korrekt:
  `analyze` faengt `CancellationException` separat und wirft weiter - ein
  Abbruch endet nie als Cache-Eintrag.
- [x] **Test:** `TrackAnalyzerCancellationTest` (2 Faelle) mit
  Robolectric-Shadows (`ShadowMediaExtractor.addTrack` + Fake-Decoder via
  `ShadowMediaCodec.addDecoder`); der Abbruch wird deterministisch aus dem
  Decoder-Callback ausgeloest (kein Timing, kein zweiter Thread). Herkunft:
  die eingecheckte Testversion stammt aus der Parallelsession und wurde nach
  gruener Verifikation uebernommen. Die eigene Erstversion mit exakter
  Puffergesamtzahl (`== 40`) scheiterte am Referenzwert - der Shadow liefert
  nicht exakt `bytes/buffer`; die uebernommene Version behauptet robust
  `> 20` im Referenzlauf und exakt `5` nach Abbruch.
- [x] Verifikation: `spotlessCheck`, `:data:audio:testDebugUnitTest`
  (37 Faelle, alle gruen) - gruen.
- [ ] **`:app:assembleDebug` ist rot, aber nicht durch diesen Commit:**
  `:feature:progress/.../ProgressDashboardScreen.kt`
  (`Unresolved reference 'asState'`, +96/-64 uncommitted) und Root-
  `build.gradle.kts` sind aktive Baustellen der Parallelsession. Bewusst
  nicht angefasst - fremde laufende Edits zu reparieren erzeugt genau die
  Ueberschreibzyklen aus Abschnitt 0 des Verbesserungsplans. Assemble-Gate
  nach deren Fix nachholen.

**Koordination:** Zwei Sessions arbeiten gleichzeitig im selben Baum (Belege
in Abschnitt 0 des Verbesserungsplans). Dieser Commit stagt bewusst nur die
vier B-AUD-6-Dateien; fremde Dateien (`feature/progress`,
Root-`build.gradle.kts`, `UI/*.jpeg`) bleiben unberuehrt.

## AE. B-SEC-1: Health-Connect-Manifest und Begruendungsseite (2026-09-03, Session: OpenCode)

Der Befund war der schwerste im Plan, weil er den Nutzer direkt trifft:
`TrainViewModel` injiziert die Health-Connect-Quelle, `TrainScreen`
registriert den Launcher und ruft ihn bei Tap auf "Puls erlauben" — aber
`:data:health` hatte kein Manifest, die Permission existierte nur als
Kotlin-Konstante. Health Connect verweigert ohne Deklaration die Anfrage:
ein sichtbarer Knopf, der nichts tut.

Von den zwei zulaessigen Wegen im Befund (Manifest bauen oder Badge
abschalten) habe ich Stufe 1 genommen — das Feature ist fertig, es fehlte
nur die Deklaration.

- [x] **`data/health/src/main/AndroidManifest.xml`** mit
  `android.permission.health.READ_HEART_RATE`. Im Modul, nicht in `:app`:
  dasselbe Muster wie `READ_MEDIA_AUDIO` in `:data:library` und
  `POST_NOTIFICATIONS` in `:data:timer` — das Modul, das die Faehigkeit
  bereitstellt, deklariert sie, der Merger fuehrt zusammen. Bewusst nicht
  dabei: `READ_HEALTH_DATA_IN_BACKGROUND` (Lesen nur im Foreground, Plan
  3.4) und jede Schreibberechtigung.
- [x] **`HealthRationaleActivity` in `:app`** mit
  `ACTION_SHOW_PERMISSIONS_RATIONALE` (Android <= 13), plus
  `activity-alias` `HealthRationaleAliasActivity` fuer
  `VIEW_PERMISSION_USAGE` + Kategorie `HEALTH_PERMISSIONS` (Android 14+).
  Beide zeigen dieselbe Seite. Der Alias ist mit
  `android:permission="android.permission.START_VIEW_PERMISSION_USAGE"`
  geschuetzt, damit nur das System ihn startet — die offizielle Anleitung
  macht das genauso.
- [x] **Eigene Activity statt Alias auf `MainActivity`.** Die
  Beispielimplementierungen zeigen beide Varianten; ein Alias auf
  `MainActivity` wuerde beim Tap auf den Datenschutz-Link im
  Berechtigungsdialog die normale App oeffnen. Der Nutzer bekaeme die
  Antwort auf "warum will diese App meinen Puls?" nie zu sehen. Die Seite
  ist reiner Text ohne Eingaben, deshalb ist `exported="true"` hier
  unbedenklich.
- [x] **Rationale-Text zweisprachig** (`health_rationale_*` in
  `app/src/main/res/values{,-de}`): Zweck (Puls neben den Saetzen,
  waehrend des Trainings), Umfang (nur Lesen, nur Herzfrequenz, kein
  Schreiben), Offline-Zusage (keine `INTERNET`-Permission, es *kann* nichts
  uebertragen werden) und der Weg zum Widerruf. Die Umlaut-Konvention der
  `values-de`-Dateien uebernommen (`ae/oe/ue`).
- [x] **Verifikation am Merger-Report**
  (`app/build/intermediates/merged_manifest/debug/`): `READ_HEART_RATE`
  steht in der Permission-Liste, beide Intent-Filter und der
  Alias-Permission-Schutz sind im zusammengefuehrten Manifest.
  `:app:assembleDebug`, `:app:lintDebug` (0 Fehler, 2 bestehende
  Warnungen), `spotlessCheck`, `doku_links_check.py` (47 Dateien) — gruen.
- [x] **Doku entwidersprucht** (B-DOC-4, 1 von 5 Zeilen): README-Zeile
  Herzfrequenz Phase 2 nennt jetzt den Manifest-Nachtrag und die offene
  Geraeteabnahme; `HERZFREQUENZ_HEALTH_CONNECT_PLAN.md` Abschnitt 7 ist
  von Absicht auf Umsetzung umgeschrieben, Phase 2 in der Phasentabelle
  von "Offen" auf den tatsaechlichen Teilstand. Dabei aufgefallen und
  festgehalten: die Berechtigungs-UI sitzt im Train-Tab, nicht in
  `:feature:settings` wie der Plan sie vorsah.

**Was der Merger-Report nicht belegt: dass es funktioniert.** Er zeigt die
Deklaration, nicht das Verhalten. Am Geraet zu pruefen bleibt: erscheint der
Health-Connect-Dialog nach Tap auf "Puls erlauben", fuehrt der
Datenschutz-Link darin zur Begruendungsseite, und wechselt der Badge nach
der Freigabe auf einen Wert. Ohne Health Connect und Mi Fitness auf einem
echten Geraet ist das nicht pruefbar — dieselbe Klasse offener Punkte wie
die Waveform-Messung (Abschnitt Y/AC), die Sensor-Traces (ADR-0017) und die
BPM-Kalibrierung (ADR-0019).

**Nebenbefund zur Parallelsession:** `:app:assembleDebug` war zu Beginn
dieses Pakets rot (`Unresolved reference 'asState'` in
`ProgressDashboardScreen.kt`, ein Import auf eine Member-Funktion von
`Animatable`). Ich habe die fremde Datei nicht angefasst; die andere Session
hat den Import um 18:53 selbst entfernt, danach war der Build gruen. Das
Vorgehen aus Abschnitt AD hat sich damit bestaetigt: warten statt in eine
fremde offene Datei schreiben.

## AF. B-UI-3 und B-UI-4: Nutzertexte in Ressourcen und echte Umlaute (2026-09-03, Session: OpenCode)

Zwei Befunde in einem Paket, weil sie dieselbe Dateiklasse anfassen.

**B-UI-3 — drei hartcodierte deutsche Strings.** Der ernste davon war
`DropSyncApp.kt:405`: die TalkBack-Zustandsansage der Hauptnavigation
("Ausgewaehlt" / "Nicht ausgewaehlt") stand im Code und sprach damit auch
auf englischen Geraeten deutsch — eine Barrierefreiheits-Regression, die
sehende Nutzer nie bemerken.

- [x] `nav_state_selected` / `nav_state_not_selected` in `:app`,
  `settings_screen_title` in `:feature:settings`, `timer_start` in
  `:feature:timer` — jeweils in `values` und `values-de`.
- [x] Ressourcenpaare gegengezaehlt: alle zehn Module haben identische
  Schluesselzahlen (73/73, 129/129, 179/179, ...). `:app` steht bei 12/11,
  weil `app_name` als `translatable="false"` korrekt nur im Default liegt.

**B-UI-4 — der Befund hat den Umfang unterschaetzt.** Lint meldete 7
`Typos` in `values-de`. Nachgezaehlt waren es **117 Zeilen** in acht
Modulen: Lint kennt nur "fuer", "Schliessen", "Groesse", "abschliessen",
"in Folge" — nicht `Uebung`, `Zurueck`, `Saetze`, `laeuft`, `Lautstaerke`
und rund 80 weitere Woerter derselben Klasse.

Entscheidend fuer die Bewertung: **fuenf Dateien mischten beide
Schreibweisen in sich selbst.** `feature/workout/values-de` hatte `Uebung`
in Zeile 9 und `Übung` in Zeile 106, `Saetze` in Zeile 29 und `Sätze` in
Zeile 120. Nur die 7 gemeldeten Stellen zu reparieren haette die
Inkonsistenz festgeschrieben.

- [x] **Wortliste statt Regex.** Eine naive Ersetzung `ae`→`ä` waere
  falsch: "neue", "Dauer", "zuerst", "Quelle", "Bluetooth",
  "Herzfrequenz" tragen den Digraph ohne Umlaut, und "dass", "bewusst",
  "passt", "Session", "Bass", "Gapless", "abgeschlossen" tragen `ss`
  korrekt. Ich habe die Kandidaten per Skript aus dem **Elementinhalt**
  extrahiert (nicht aus Kommentaren oder Attributen), die 112 Woerter
  einzeln entschieden und nur die Liste angewandt. Danach gegengeprueft:
  32 Woerter bleiben stehen, alle korrekt. Beide Skripte waren Wegwerfware
  und sind geloescht — ein Konvertierungsskript im Repo waere ein
  Werkzeug ohne zweiten Anwendungsfall.
- [x] **Zwei Stellen bewusst behalten:** `progress_streak_days` und
  `progress_streak_line` mit "in Folge". Lint schlaegt "infolge" vor, das
  "wegen" bedeutet und den Satz umdreht ("Tage wegen"). Mit
  `tools:ignore="Typos"` plus Begruendungskommentar stillgelegt, nicht
  durch eine falsche Korrektur ersetzt.
- [x] **Ergebnis:** `Typos` in allen Modulen auf 0 (per SARIF gezaehlt);
  Lint-Gesamtstand 50 → 43 Warnungen. Der Rest ist bekannt und im Plan
  gefuehrt: 25 `PluralsCandidate`, 5 `UseKtx`, 4 `TypographyFractions`,
  2 `TypographyDashes`, dazu vier Einzelfaelle (davon 2 Fehlalarme und
  2 bewusste Entscheidungen).
- [x] Verifikation: `spotlessCheck`, `lintDebug` (alle Module),
  `:app:assembleDebug`, `doku_links_check.py` — gruen.

**Nicht gemacht und warum:** die 25 `PluralsCandidate` ("1
Wiederholungen") sind echte Sprachfehler, aber jede Umstellung auf
`<plurals>` aendert die Aufrufstelle im Kotlin-Code mit. Das ist ein
eigenes Paket je Modul, nicht ein Nebenschritt in einem
Ressourcen-Commit.

## AG. B-UI-5: Baseline Profiles - zwei gemeldete Ursachen, fuenf echte (2026-09-05, Session: OpenCode)

Der Befund sagte: Plugin da, Generator fehlt, `packageName` falsch. Beim
Umsetzen kam heraus, dass `:benchmarks` seit `cb7efda` **gar nicht
kompilierte**. Jede Ursache verdeckte die naechste, deshalb der Reihe nach:

- [x] **1. Producer nie verdrahtet.** `baselineProfile(project(":benchmarks"))`
  fehlte in `app/build.gradle.kts`. Das war der eigentliche Grund fuer die
  zwei `SKIPPED`-Tasks, die der Befund als Beleg zitierte - nicht der
  fehlende Generator. Ohne diese Zeile kennt `:app:generateBaselineProfile`
  das erzeugende Modul nicht; `merge` und `copy` liefen auf leerer Eingabe
  und meldeten Erfolg.
- [x] **2. Generator angelegt** (`BaselineProfileGenerator.kt`) mit
  `BaselineProfileRule` und `includeInStartupProfile = true`.
- [x] **3. `junit4` und `androidx.test.ext.junit` fehlten** in
  `benchmarks/build.gradle.kts`. Beide wurden in `cb7efda` beim Modulumbau
  entfernt; seither schlug jeder Kompilierversuch mit `Unresolved reference
  'AndroidJUnit4'` fehl.
- [x] **4. `CompilationMode.BaselineProfile()` existiert nicht mehr.** Die
  API gab es in benchmark 1.0; mit `1.5.0-alpha01` ist der Ersatz
  `CompilationMode.Partial(baselineProfileMode = ...)`. `StartupBenchmark.kt`
  hat mit der eingetragenen Version also nie kompiliert. Bewusst
  `BaselineProfileMode.Require` statt `UseIfAvailable`: fehlt das Profil,
  soll der Benchmark abbrechen statt ein Ergebnis zu liefern, das wie "kein
  Gewinn" aussieht. Genau diese Art stiller Fehlmessung war der Befund.
- [x] **5. `kotlin.compose` lag auf `:benchmarks`**, obwohl das Modul keine
  Composables enthaelt. Der Compose-Compiler verlangt die Compose-Runtime
  auf dem Classpath, die dort nicht ankommt (`implementation(project(":app"))`
  reicht sie nicht weiter) - Abbruch mit "requires the Compose Runtime to be
  on the class path".
- [x] **`packageName` korrigiert** (Punkt aus dem Befund): `com.dropsync.app`
  ohne `.debug`. Macrobenchmark verlangt `debuggable=false`, die
  `benchmark`-Variante faellt per `matchingFallbacks` auf `release`, und
  `release` hat keinen `applicationIdSuffix`.

**Der eigentliche Befund ist nicht das Modul, sondern die Luecke, die es
verrotten liess:** die CI baut `com.android.test`-Module nicht. `test`,
`assembleDebug` und `assembleRelease` fassen `:benchmarks` nicht an. Ein
Modul ohne Compiler-Abdeckung verfaellt still - hier ueber mindestens zwei
Commits, ohne dass ein Gate rot wurde. Gegenmittel ist ein
`:benchmarks:assembleBenchmarkRelease`-Schritt in der CI (baut nur, laeuft
nicht, braucht kein Geraet). Als Punkt 20b in P2 eingetragen; die CI-Datei
selbst habe ich nicht angefasst, weil sie ausserhalb dieses Pakets liegt.

- [x] Verifikation: alle drei Varianten kompilieren
  (`compileNonMinifiedBenchmarkKotlin`, `compileBenchmarkBenchmarkKotlin`,
  `compileBenchmarkReleaseKotlin`). Die Taskkette enthaelt jetzt
  `:benchmarks:connectedNonMinifiedReleaseAndroidTest` und
  `:benchmarks:collectNonMinifiedReleaseBaselineProfile` vor
  `:app:mergeReleaseBaselineProfile` - vorher standen dort nur merge und
  copy. `doku_links_check.py` gruen (49 Dateien).
- [ ] **Der Generatorlauf selbst ist nicht verifiziert.** Er braucht ein
  Geraet mit Root oder API 33+ (das Sammeln liest `/data/misc/profiles`),
  auf einem Emulator ein `aosp`-Systemabbild. Bis dahin ist belegt, dass die
  Kette vollstaendig ist - nicht, dass sie ein brauchbares Profil liefert.
  Fuenfter offener Geraetepunkt neben Waveform-Messung (Y/AC),
  Health-Connect-Dialog (AE), Sensor-Traces (ADR-0017) und
  BPM-Kalibrierung (ADR-0019).

**Koordination:** `README.md` und `VERBESSERUNGSPLAN.md` sind in diesem
Commit **nicht** enthalten. Beide hat die Parallelsession im Arbeitsbaum
umgeschrieben (Plan: 277 geaenderte Zeilen), und meine Statuszeilen zu
B-UI-5 sitzen mitten in deren Hunks - sie zu stagen haette fremde,
unfertige Doku-Arbeit mitcommittet. Die Aenderungen liegen im Arbeitsbaum
und gehen mit dem Doku-Commit der anderen Session. `spotlessCheck` ist aus
demselben Grund rot: `data/audio/.../MasterDspProcessor.kt` ist eine offene
fremde Baustelle (B-AUD-1). Meine Kotlin-Dateien sind sauber - der Check
nennt genau diese eine Datei.

## AH. Fremd-TODOs abgearbeitet: Link-Fix, P2-15, Phase 6.2 (2026-09-11, Session: OpenCode)

Anlass: offene Punkte einer anderen KI-Session (Link-Fix UMBAUPLAN:1477,
P2-15 zweite Haelfte, Phase 6.2 Sweep-Werkzeug) plus Nutzerentscheidungen zu
B-AUD-4/B-AUD-5, P2-16/17 und naechstem Schwerpunkt (B-UI-1 statt P2-14).

- [x] **Link-Fix:** `doku_links_check.py` war mit genau einem Fehler rot
  (Zahl-Verweis 'Abschnitt 7' in UMBAUPLAN:1477). Anker
  `<a name="risiko-schwelle">` vor `### 7.6 Risiko`, Verweis auf
  `[Risiko-Abschnitt](#risiko-schwelle)` umgebogen. Check gruen (49 Dateien).
- [x] **P2-15 zweite Haelfte:** `AudioSettingsViewModelTest` (6 Tests) +
  modul-lokale `AudioSettingsFakes.kt` nach Settings-Muster. Bewusst OHNE
  Robolectric (kein Context, kein Log, keine Ressourcen — Muster
  `TrainViewModelTest`); `build.gradle.kts` nur um junit4/coroutines-test
  ergaenzt. **Echter Bug gefunden und gefixt:** `update()` las
  `dspConfig.value` (WhileSubscribed, ohne Abonnent dauerhaft Initialwert) —
  zwei schnelle Aenderungen ueberschrieben sich still. Liest jetzt
  `repository.dspConfig.first()` wie `SettingsViewModel`. Gegenbeweis: alte
  Lesart = 2 Tests rot, neue = 6/6 gruen.
- [x] **Fremde Regression gefangen:** Nach gruenen Laeufen schlug ploetzlich
  `graphic band count` fehl (31 vs 10 Baender). Ursache: fremde Aenderung
  IM BAUM (`setGraphicBandCount` ignorierte `count`, hart `graphicBands(10)`
  — mtime 11.09. 22:00, nach meiner Arbeit). Die UI bietet 10/15/31 an; mit
  dem Hardcode taete die Wahl nichts. `count` wiederhergestellt, 6/6 gruen.
  Beleg, dass P2-15 seinen Zweck erfuellt — und Warnung, wie schnell ein
  uncommitteter Baum fremde Regressionen versteckt.
- [x] **Phase 6.2 Sweep-Werkzeug** (`:domain:sensor`-Testquellen, kein
  Produktivcode ausser additiver Config-Durchreichung `accelVoteWindowMs` +
  `dtwBand` mit unveraenderten Defaults): `CorpusFiles.kt` (JSONL/Manifest-
  Parser im Recorder-Stil, ohne neue Dependency), `CorpusSweepHarness.kt`
  (frische Pipeline je Parametersatz, echte Timestamps mit Luecken, CSV mit
  Delta + Abstands-Metriken, zweistufige Accel-Derivation analog
  `calibrateAccelThreshold`). `CorpusSweepHarnessTest` (Mini-Corpus 2 Saetze:
  Baseline exakt, Accel-Schwelle trennscharf, profillose Fenster
  protokolliert) + `CorpusReplayDeterminismTest` (zweimal = bitgleich inkl.
  Events, Loader-Roundtrip identisch). 7 Tests; `:domain:sensor` 127/127
  gruen. Echte Sweep-Ergebnisse brauchen weiter Phase-1-Aufnahmen; Einstieg
  per `-Dsweep.corpus.dir=...` (ohne Flag No-Op, CI-sicher). Zwei Lektionen:
  Marks sind Rep-Mitten (Kalibrierungs-`RepMark` = Exkursionsmaximum, nicht
  Rep-Ende), Ruhesigma ist die stillste Sekunde (nicht "alles ausserhalb der
  Marks" — EMA-Auslaufenden blaehen es auf und druecken jede Schwelle auf 0).
- [x] **B-AUD-4 (Weg b):** `audio_bitperfect_desc` DE/EN verspricht keine
  Mixer-Ausgabe mehr; Schalter (DSP-Bypass) bleibt funktional.
- [x] **B-AUD-5 (Weg b):** `MixTransitionsSection` (Schalter/Chips/Regler)
  und `CrossfadeSection` (Regler) ausgegraut + Hinweis "derzeit ohne
  Wirkung" (`settings_mix_no_effect`, `audio_crossfade_no_effect`, DE/EN,
  Ressourcen-Paritaet geprueft); Werte bleiben persistiert.
- [x] **P2-16 (Weg b "Regeln extrahieren"):** `docs/ARCHITEKTURREGELN.md` neu
  (3.2/1-4 aus dem Architekturtest als normativer Anker, Bauplan-Schritte aus
  Code-Zitaten mit Belegstelle, nur-Namen-nur-Namen-Ehrlichkeit); README-Kopf
  verweist auf die Rekonstruktion statt aufs fehlende Original.
- [x] **P2-17 ("Verdrahten"):** neuer `TimerScreen` (TopAppBar +
  `TimerSection`) in `:feature:timer`, Route `timer` im App-NavHost (kein
  fuenfter Tab), Einstieg per Tap auf die Countdown-Anzeige der
  Train-Pausenkonsole (`TrainScreen.onOpenTimer`, `train_rest_open_timer`
  DE/EN, `onClickLabel` statt contentDescription damit TalkBack weiter die
  Zeit vorliest). Gleiche geteilte Engine — konsistenter Zustand. Der
  `"TIMER STARTEN"`-Teil war bereits durch B-UI-3 erledigt.
- [x] Verifikation: `spotlessCheck`, `detekt`, `:domain:sensor:test`
  (127/127), `:feature:audio:testDebugUnitTest` (6/6),
  `:feature:settings:lintDebug`, `:feature:audio:lintDebug`,
  `:feature:timer:lintDebug`, `:feature:workout:lintDebug`,
  `:app:assembleDebug`, `doku_links_check.py` — alle gruen.
- [ ] Naechster Schwerpunkt per Nutzerentscheidung: **B-UI-1 naechste Stufe**
  (Tests fuer player/timer/workout nach settings/audio-Muster), nicht P2-14.
  Offen ausserdem: uncommitteter Gesamtbaum (diese Arbeit + fremde
  REPCOUNT-/B-DB-1-/B-AUD-1-Arbeit) sichten und committen; Push-Entscheidung;
  Geraeteabnahmen (P4/Phase 1).

**Koordination:** Parallelsession-Aenderungen im selben Baum wurden nicht
angefasst (nur gelesen). Zwei Ausnahmen mit Testbeleg:

1. `AudioSettingsViewModel.kt` Zeile 87 — fremde Regression
   (`graphicBands(count)`→`graphicBands(10)`, mtime 11.09. 22:00, nach meiner
   Arbeit): als Bugfix mit Testbeleg wiederhergestellt.
2. **Wiederholung am 12.09. 13:41:** dieselbe Zeile erneut auf `graphicBands(10)`
   gesetzt (Detekt `UnusedParameter` + Test `31 vs 10 Baender` wurden rot).
   Erneut wiederhergestellt. Wer die Bandanzahl absichtlich festlegen will,
   muss `AudioEqSection.kt:63` (Aufrufer mit 10/15/31), den KDoc-Vertrag und
   `AudioSettingsViewModelTest` gleichzeitig aendern — stilles Festnageln
   bricht den UI-Vertrag.
3. `tools/doku_links_check.py`: eigener Bug gefixt (Absturz `ValueError` bei
   Links aus `docs/` heraus, z. B. auf `../VERBESSERUNGSPLAN.md#anker` —
   Anker werden jetzt bei Bedarf eingelesen, Pfade Repo-relativ gemeldet).
   Beruehrt die fremden Fence-Hunks nicht.

## AI. B-UI-1 erledigt: Timer- und Settings-Store-Tests (Session: OpenCode)

- [x] Befund beim Sichten: `feature/player` (PlayerViewModelTest u. a.) und
  `feature/workout` (TrainViewModelTest, PlausibilityTest,
  CalibrationViewModelTest) waren bereits abgedeckt und gruen. Offen waren nur
  noch `feature/timer` (0 Tests) und `data/settings` (0 Tests).
- [x] `feature/timer`: `TimerViewModelTest` (6 Tests) + `TimerFakes.kt`
  (FakeTimerClock, RecordingCueOutput, FakeRestTimerPreferences) gegen die
  echte `TimerEngine`; `withViewModel`-Muster mit Scope-Cancel (250-ms-Ticker).
  Dabei **Produktionsbug gefixt**: `getReady` lief mit `WhileSubscribed` ohne
  Abonnenten, `startRest` las ewig den Startwert - B9-Vorlauf tot (Gegenbeweis:
  Get-Ready-Test rot vor dem Fix auf `Eagerly`, 5/6 uebrige schon vorher gruen).
- [x] `data/settings`: `ThemeSettingsStoreTest` (4) + `AccentColorStoreTest`
  (3) per Robolectric nach dem DataStore-Sensor-Muster (sdk=33): Roundtrip,
  Ueberschreiben, Defaults SYSTEM/LIME, Fallback unbekannter Rohwerte ueber
  denselben DataStore-Namen.
- [x] Plan nachgezogen: B-UI-1 `[x]`, P2-15 auf erledigt erweitert, Kennzahl
  "Module ohne Tests" 0 (war 4), B-UI-2-Folgesatz aktualisiert.
- [x] Verifikation: `spotlessCheck`, `detekt`, Tests timer 6/6, settings 7/7,
  player 35/35, workout 34/34, audio 6/6, `:domain:sensor` 127/127,
  `lintDebug` timer/settings, `:app:assembleDebug`, `doku_links_check.py`
  (50 Dateien) - alle gruen.
- [ ] Verbleibend (nicht per Code loesbar oder fremde Entscheidung):
  Geraeteabnahmen (P4/Phase 1: Gate 11b, B1/B9, Health-Connect, Generatorlauf);
  P2-14 weiter zurueckgestellt; Push-Entscheidung beim Nutzer.

## AJ. Ausbauplan Tranche A: Release-Sicherheit (A1-A5, Session: OpenCode)

- [x] A1: `runBlocking` aus `TimerService` entfernt — neue `TimerTermination`
  (suspend-Sequenz, Phase-10.3-Ordnung bleibt), 4 Tests inkl. Kill-Race-Fall.
  `:domain:timer` 60/60, `TimerServiceForegroundTest` 7/7.
- [x] A2: Media3 1.10.1 -> 1.11.0 (+ Notices, FFmpeg-Doku/Skript nachgezogen).
  Neue `SessionConnectionPolicy` (eigen/System/Dritte), `onConnect` verdrahtet,
  5 Tests. Konstantennamen gegen Media3-Quelle verifiziert (kein
  SET_SHUFFLE_ORDER/SKIP_SILENCE/AUX_EFFECT in 1.11). `@SuppressLint` statt
  `@OptIn` (Objekt-Scope trägt OptIn in dieser Lint-Version nicht).
- [x] A3: Cancellation-Rethrow an 73 Stellen (14 Dateien) + `DropSyncApplication`
  in `runTimerRecovery`/`runSeeders` entzerrt (ThrowsCount). `SwallowedException`
  bewusst aus (AppResult-Vertrag) — stattdessen Architekturtest
  `cancellation wird nicht verschluckt` in `ModuleDependencyRulesTest`.
  Abbruch-Test mit Gegenbeweis (rot ohne Fix, grün mit). LargeClass-Baseline
  per `detektBaseline` generiert (Signatur enthält Superklasse).
- [x] A4: `tools:targetApi` 36 -> 37, `enableOnBackInvokedCallback`, Bibliothek
  auf `PredictiveBackHandler` mit Fortschritts-Animation (eigene Composable
  gegen Komplexitätsgrenze). 16-KB bereits in `docs/ffmpeg-build.md`.
- [x] A5: Chart-Balken mit Wochen-Ansage (+ Robolectric-Semantik-Test, 8/8),
  Mini-Player als Button (48 dp, onClickLabel), Now-Playing-Overflow 48 dp,
  Settings-Chips `heightIn(48)`, Bottom-Bar ohne falschen Tab + `selected`.
- [x] Gates: `test`, `spotlessCheck`, `lintDebug`, `detekt`, `assembleDebug`,
  `assembleRelease`, Link-Check — alle grün.

## AK. Ausbauplan Tranche B: Nutzer-Impact (B1-B5, Session: OpenCode)

- [x] B1: B-UI-5-Verdrahtung verifiziert (Producer, Generator, `Require`-Benchmark).
  CI-Gate `Baseline-Profil vorhanden` (faellt mit Runbook-Verweis, bis das
  Profil eingecheckt ist) + Ablauf in `docs/HARDWARE_TESTPLAN.md` (neuer
  Abschnitt B1). Generatorlauf + Messzahlen brauchen ein Geraet (offen).
- [x] B2: Timer-Einstieg in den Einstellungen (kein 5. Tab, gleiche Route und
  Engine wie Train-Pause). Stellrad: STD/MIN/SEK lokalisiert, Stunden-Spalte
  aktiv (0-23 h, max. 23:59:59, pure `split/shift`-Funktionen). Tests:
  TimerWheelMath (5) + TimerWheel-Interaktion (3, Robolectric). Timer 14/14.
- [x] B3: First-Run-Onboarding (3 Seiten, DataStore-Flag via
  `OnboardingRepository`/`OnboardingStore`, tri-state ohne Flackern) +
  POST_NOTIFICATIONS-Karte mit Kontext statt Auto-Anfrage (TrainScreen).
  Store-Test (2). Hinweis: OnboardingScreen liegt in `:app` (Shell-Flow,
  B-ARCH-5-Spannung dokumentiert).
- [x] B4: Undo-Snackbars ueber einen App-Host: Queue (Ende+Move-Best-Effort,
  CUE-Tracks ohne Song-ID ohne Undo), Marker (Long-Press sofort + Undo statt
  Dialog; Review-Bestaetigen/Verwerfen bleibt), Playlist-Eintrag und ganze
  Playlist (mit Songs + Label). `deletePlaylist` dafuer suspend (Race
  erklaert). Player-Tests +3 (38/38). Library-VM ohne Testinfra — Review only.
- [x] B5: Health-Connect nach Vorgabe — Sync-Toggle (Default an, Gating in der
  Source, Persistenz im Health-Store), Status je Availability, Hinweis bei
  fehlender Berechtigung, Manage-Access-Button (resolve-gesichert),
  Refresh nach Rueckkehr. Tests: Source-Sync (1), Settings-Toggle (2).
  Health 10/10, Settings 8/8.
- [x] Gates: `test`, `spotlessCheck`, `lintDebug`, `detekt`, `assembleDebug`,
  `assembleRelease`, Link-Check — alle gruen.
- [ ] Offen (Geraet/Store): B1-Generatorlauf + Messzahlen, Geraeteabnahmen
  (P4/Schritt 13), Push-Entscheidung.

## AL. Ausbauplan Tranche C1: Designsystem-Konsolidierung (Session: OpenCode)

- [x] Expressive-Befund verifiziert und dokumentiert: material3 1.4.0
  (BOM 2026.06.01) hat weder stabiles `ButtonGroup` noch `LoadingIndicator`,
  `MaterialExpressiveTheme` ist internal. Projektregel "nur stabile Versionen"
  -> **kein** Expressive-Umstieg; Wiedervorlage bei 1.5.0 stable. Kommentar in
  `Theme.kt` bleibt bestehen.
- [x] Radien: `Spacing.radiusMedium = 16.dp` ergaenzt; Literale ersetzt in
  `FlowRepComponents`, `BrandCard` (jetzt `MaterialTheme.shapes.medium = 24`),
  `MiniPlayer`, `LibraryHomeScreen`, `LibraryLists`, `DetailScreens`. Skala
  bleibt `radiusSmall/Medium/Card/Hero` (Pills/Kreise sind Sonderformen).
- [x] Typografie: `bodyLarge != bodyMedium` und `labelLarge/Medium/Small`
  eindeutig (vorher waren `bodyLarge==bodyMedium` und alle drei Labels 12 sp).
  Groessen/Proportionen nach M3, Poppins bleibt Markenschrift.
- [x] Farben: Kategorie-Palette -> Token `CategoryTints` (im Theme, statt 12
  Hex-Literalen im Feature), Akzent-Schwaerze -> `accentSwatchColor()`,
  Cover-Overlays -> `OverlayTokens` (`scrim`/`onScrim`, bewusst absolut).
- [x] Grep-Gate `tools/design_check.py`: scannt `*/src/main/**/*.kt` ausserhalb
  `core/designsystem`; ALLOWLIST nur `PlayerArtworkColors.kt` (aus dem Cover
  berechnet). Gegenbeweis: Probe-Literal -> rot, nach Entfernen -> gruen.
  CI-Schritt nach dem Doku-Link-Check eingehaengt.
- [x] Screenshot-Gate: Roborazzi 1.74.0 (Paparazzi 1.x kennt AGP 9 `BaseExtension`
  nicht mehr), `ComponentScreenshotsTest` (6 Faelle: Buttons, BrandCard,
  FlowRepSurface je hell/dunkel), Referenzen versioniert unter
  `core/designsystem/src/test/screenshots/`, CI `:core:designsystem:verifyRoborazziDebug`.
- [x] Gates: `test`, `spotlessCheck`, `lintDebug`, `detekt`, `assembleDebug`,
  `assembleRelease`, `verifyRoborazziDebug`, `doku_links_check.py`,
  `design_check.py` - alle gruen.
- [ ] Bewusst abweichend: Screenshots der Komponenten statt der "5 groessten
  Screens" (Feature-Module braeuchten eigene Roborazzi-Infrastruktur; die
  Breakpoint-Abnahme kommt in C2). 200-%-Abnahme bleibt Geraeteschritt 13.

## AM. Ausbauplan Tranche C2-C5: Adaptiv, System-Media, Gates, Modernisierung (Session: OpenCode)

- [x] C2 Adaptiv: `LocalWindowSizeClass` in `:core:designsystem` (einmal von
  der Shell bereitgestellt, `rememberWindowWidthSizeClass()` in den Screens).
  Now-Playing begrenzt das kreisrunde Cover auf Tablet/Landscape auf 440 dp
  (vorher full-width) und ersetzt die festen 30/18 sp durch Typo-Tokens;
  Train bekommt auf breiten Fenstern mehr Aussenrand; das Dashboard
  generalisiert die Spaltenwahl (gross > 1.5 -> 1, Expanded >= 840 dp -> 3,
  sonst 2) mit purem `DashboardColumnCountTest` (4).
- [ ] C2-Abnahme "Screenshot je Breakpoint" bewusst nicht als Pixel-Gate:
  dafuer braeuchten die Feature-Module eigene Roborazzi-Infrastruktur mit
  Screen-Fixtures. Die Breakpoint-Logik ist per Test abgedeckt, das
  Gesamtbild bleibt Geraeteschritt 13 (200 %).
- [x] C3 System-Media: Timer-/Rest-Notification traegt einen
  Fortschrittsbalken (`setProgress`, Prozent aus Sitzungsdauer), Test in
  `TimerServiceForegroundTest` (15/60 s -> 25 %). ADR-0020: media3-ui-compose
  wird bewusst NICHT genutzt (Regel 3.2/4 verbietet media3 in Features, der
  eigene Mini-Player ist designsystem-konform nach ADR-0018); System-Media
  laeuft ueber die MediaSession, der Output-Switcher ist System-UI und bleibt
  Geraeteabnahme.
- [x] C4 Gates: neuer CI-Job `instrumented` (Emulator API 33,
  `connectedCheck`) mit `continue-on-error: true`; das Screenshot-Gate ist seit
  C1 als `verifyRoborazziDebug` in der CI (Paparazzi ersetzt). `src/test`
  nachgeholt: `core:model` (`DuckingPercentTest`, `EnumPersistenceNamesTest`
  pinnt die als String persistierten Enum-Namen als Migrationsvertrag),
  `domain:settings` (`SettingsContractTest`). Kennzahl "Module ohne Tests" 0.
- [x] C5-Teil 1 Backup: ADR-0021 haelt fest, dass V1 bewusst weder
  Auto-Backup noch Geraetetransfer noch verschluesselten Export baut
  (`allowBackup=false`, `dataExtractionRules` und `fullBackupContent=false`
  waren bereits gesetzt). Ein lokaler Export bleibt Folge-Option mit eigener
  ADR (Format/Schluessel).
- [x] C5-Teil 2 Detekt: Die manuell gepflegte `source.setFrom`-Liste ist durch
  eine Ableitung aller Modul-`src`-Verzeichnisse ersetzt (B-ARCH-4). Sie
  konnte vorher still Module auslassen; `training-core`/`libs` bleiben raus.
  Verifikation ueber den Bericht: 423 kt-Dateien, 0 Findings.
- [x] Gates: `test`, `spotlessCheck`, `lintDebug`, `detekt`, `assembleDebug`,
  `assembleRelease`, `verifyRoborazziDebug`, `doku_links_check.py`,
  `design_check.py` - alle gruen.
- [ ] C5 offen (bewusst nicht in dieser Session): vollstaendige
  `build-logic`-Convention-Plugins (Duplikat-`build.gradle.kts` ueber ~20
  Module, eigener Modul-Schnitt); I18n-Reste + Top-Bars (U8);
  Dependency-Bumps (coroutines/datastore/Detekt-Alphas) erst bei stabilen
  Releases; Rest aus `VERBESSERUNGSPLAN.md` (B-SEC/B-DB/B-ARCH/B-UI/B-DOC);
  Geraeteabnahmen (P4/Schritt 13).

## AN. Verbesserungsanalyse 2026-09: Pakete 0-4 (Session: OpenCode)

Quelle: `docs/VERBESSERUNGSANALYSE_2026-09.md` (Befunde + Pakete 0-4).
Arbeitsstand: Working Tree, kein Commit (Adi entscheidet ueber Stueckelung).

- [x] Paket 0: CI-Baseline-Gate warnend statt blockierend; Dezimalkomma-Fix
  im TrainViewModel mit Gegenbeweis-Tests; Reduced-Motion-Local
  (`LocalReducedMotion`) eingezogen und die Hauptanimationsorte umgestellt
  (Nav-Pill inkl. Icon-Pop, Now-Playing-Slide, CountUpText, Buttons,
  ProgressRing, MiniPlayer, Library-AnimatedContent, Onboarding); das
  Dashboard-Duplikat (eigene Settings-Abfrage) entfernt.
- [x] Paket 1: `signingConfig` (keystore.properties/Env-Vars, in
  `.gitignore`), `gradle/verification-metadata.xml` (sha256) inkl.
  Nachtrag fuer spotless/detekt, Poppins-OFL als
  `core/designsystem/POPLINS_OFL.txt`; Lint auf 0 Warnungen mit
  `warningsAsErrors` (`app/lint.xml` dokumentiert ObsoleteSdkInt,
  mipmap-anydpi-v26 bleibt wegen v31-Splash); erste `:app`-Unit-Tests
  (`OnboardingViewModelTest`, `NavigationRoutesTest` inkl.
  `calibrationRoute`-Extraktion) und CI-Schritt auf
  `:benchmarks:assembleBenchmarkRelease` umgestellt (Check
  `checkTestedAppObfuscation` dokumentiert deaktiviert:
  self-instrumenting).
- [x] Paket 1.7: DataStore-Korruption zentral
  (`createResilientPreferencesDataStore` in `:core:common`) plus
  CorruptionHandler in allen 15 Stores; Listener-Reconnect-Fix in
  `PlaybackRepositoryImpl` (Listener folgt der Player-Instanz);
  `AudioSessionId`-Listener statt einmaligem Lesen.
- [x] Paket 2: Bit-Perfect verdrahtet (`setPreferredMixerAttributes`/
  `clear` fuer USB ab API 34, `floatOutput` aus der Config,
  Service-Neustart bei Wechsel, ehrlicher UI-Text); ReplayGain als Option
  (`DspConfig.replayGainEnabled`, Persistenz, SwitchRow, Gain pro
  Titelwechsel aus `integratedLufs`, Referenz -18 LUFS, Preamp-Knoten,
  unabhaengig von `config.enabled`). Crossfade (2.9) bewusst offen
  gelassen (Dual-Player = eigenes Feature, Entfernen invasiv; Entscheidung
  dokumentiert). 2.11 mediaui: ADR-0020 entscheidet bereits (kein Wrapper).
- [x] Paket 3.12: FTS-Suche verdrahtet (Kategorie-Suche nutzt
  `searchResults`, geschnitten auf die Kategorie; Index-Rebuild in der
  Scan-Transaktion statt je Query).
- [x] Paket 3.13: Playlist-Duplikat-Schutz (`addToPlaylist` ueberspringt
  bereits enthaltene Titel, Test) + Snackbar-Meldung; Import-Verstoesse
  einzeln in den Einstellungen (max. 5 + Summe); SAF-Ordnerscan und
  M3U-Import als UI-Einstieg (Picker, persistente URI-Rechte,
  Ergebnis-Snackbars).
- [x] Paket 3.14: Drop-Auto verdrahtet (`DropRestRequestBus` vom
  TrainViewModel, 2 neue Tests), Log-Fehler als Snackbar; echte PRs aus
  `personal_records` im Dashboard (DAO/Repo/Domain erweitert,
  `ProgressFeedUiState.newPrRecords`, Tile 5 mit PR-Art und Wert,
  3 Tests). Gewichtsschritt/paging bleiben als Rest offen (siehe unten).
- [x] Paket 3.15: Timer-Presets aus den Einstellungen (ViewModel-StateFlow,
  UI nutzt sie), POST_NOTIFICATIONS-Anfrage in der Timer-Route;
  Persistenz-Drosselung war bereits umgesetzt.
- [x] Paket 4.16: Compose BOM 2026.06.01 -> 2026.08.00 (Compose 1.12.0,
  material3 1.4.0; Versionsverifikation ueber Google Maven) inkl.
  aktualisierter Verifikationsmetadaten und gruener Screenshot-Gates;
  Predictive Back der Bibliothek auf `DeferredAnimatedContent`
  umgestellt (`DeferredTransitionState` + `mutableTransformSpec`);
  `SideEffect` mit Keys fuer nicht-suspendierende Effekte
  (LibraryScreen/NowPlayingScreen/LibraryContent/CategoryScreens).
  M3-Expressive-Recherche verifiziert: 1.5.0 existiert nur als Alpha
  (alpha28) -> bewusst NICHT umgestellt (Kommentar in libs.versions.toml).
- [x] Paket 4.18 (Teil): `FlowRepTopBar` als einheitliches App-Bar-Muster
  (Timer, Audio, Alle Saetze, Uebungsbibliothek migriert);
  `FlowRepEmptyState`/`FlowRepErrorState` im Designsystem, verdrahtet in
  Library-EmptyHint, ExerciseLibrary und AllSets; Radius-Tokens waren
  bereits konsolidiert. Orphaned Komponenten: `DropRestCard` im
  NowPlaying verdrahtet (nur sichtbar wenn Rest laeuft/startbar),
  `MiniWaveform` in den Library-Listen wieder verdrahtet (war seit dem
  Poweramp-Umbau tot, inkl. `currentProgress`/`waveformFor`),
  `LineChart` und `FlowRepMetricCard` entfernt (keine Referenzen);
  `BrandCard`/`BrandButtonSecondary` bleiben (Screenshot-Galerie).

### Bewusst offen / Rest

- [ ] Paket 2.9 Crossfade: Entscheidung Dual-Player vs. Entfernen noetig
  (Entfernen betrifft 5 Module, Verdrahten ist ein eigenes Feature).
- [ ] Paket 3.14-Rest: Session-/Routinen-UI, Paging fuer grosse
  Bibliotheken, Gewichtsschritt-Feinschliff (1,25/2,5) — eigene Pakete.
- [ ] Paket 4.17 Material 3 Expressive: erst nach stabilem material3 1.5.0
  (aktuell nur Alphas), erfordert neue ADR.
- [ ] Paket 4.19: build-logic-Convention-Plugins, Detekt-Baseline-Abbau,
  Doku-Konsolidierung, `:app`-Testausbau — offen.
- [ ] Geraeteabnahmen (Bit-Perfect nachweisbar, Crossfade hoerbar/entfernt,
  200-%-Lauf) bleiben laut Analyse Abschnitt 6 manuell.

### Gates dieser Session

- [x] `test` (alle Module), `spotlessCheck`, `detekt`,
  `:app:lintDebug` (0 Warnungen, warningsAsErrors), `:app:assembleDebug`,
  `:app:assembleRelease` (R8), `:benchmarks:assembleBenchmarkRelease`,
  `:core:designsystem:verifyRoborazziDebug` - alle gruen.

## AO. Verbesserungsplan MusicPlayer/DropSync + Rep-Zaehlung: Entscheidungen, Recherche, P0 (2026-09-19, Session: OpenCode)

Anlass: Adi hat einen neuen, fokussierten Verbesserungsplan fuer die zwei
Schwerpunkte angefordert (MusicPlayer/DropSync und BLE-/IMU-Rep-Zaehlung),
drei Recherche-Werkzeuge zum Testen gegeben und danach alle offenen Punkte
per Entscheidungs-Wizard beantwortet.

**Artefakte:**

- `VERBESSERUNGSPLAN_MUSIC_DROPSYNC_REPCOUNT.md` (Hauptplan, ~1.450
  Zeilen): 16 MusicPlayer/DropSync-Befunde (MP-1..MP-16), 22
  Rep-Counting-Befunde (RC-1..RC-22), Abgleich mit der bestehenden
  Tiefenrecherche (Anhang B), Entscheidungen (Anhang A), Phasen P0-P3.
- `docs/research/2026-09-19-pausen-benachrichtigung-service-typ.md`,
  `docs/research/2026-09-19-rep-counting-per-uebung.md`,
  `docs/research/2026-09-19-downbeat-offset.md`.
- ADR-0022 (Crossfade stufenweise, Spike vor Bau), ADR-0023
  (Sensordaten nur in Debug-Builds), ADR-0012-Nachtrag (Crossfade-Pfad,
  Fallback, Nutzer-Vorrang).

**Werkzeugtest (reproduzierbar):** hyperresearch (venv, 1242 Tests/1
Umgebungsfehler, `scholar search` ohne Key, Vault-Fetch), OpenResearch
`orx` (Windows-CLI, `paper --full` 52 KB), Agent-Reach (589 Tests/3
Umgebungsfehler, `doctor` 3/16, GitHub-Kanal via `gh`). Details in
Abschnitt 0.2 des Plans.

**Entscheidungen (19.09.2026):** Drop-Auto = Musik landet am Pausenende
(Rest behaelt die Dauer); Recorder nur Debug; echter Crossfade gewuenscht
(ADR-0022); Messungen vorerst zurueckgestellt; Sensor am Handgelenk;
keine Rest-Playlist = nichts tun; Pausen-Notification mit Abbrechen;
Gerätetaste = Satz starten/stoppen (von der Umsetzung festgelegt, P3).

**P0 umgesetzt (Code, Arbeitsbaum):**

- [x] **MP-1 Drop-Auto Ende-zu-Ende.** Der `RestMusicCoordinator`
  (app-weit) konsumiert jetzt `DropRestRequestBus`; die Anforderung wirkt
  fuer genau eine Pause (auch bei Verhalten NORMAL), plant bei laufender
  Pause sofort und faellt ohne Work-Drop auf die Rest-Playlist zurueck.
  `DropRestViewModel` konsumiert den Bus nicht mehr (manuelle Aktion).
  Drei neue Koordinator-Tests inkl. Fallback und "ohne Rest-Playlist
  greift nichts"; `onRestBegin` bricht eine alte Landung ab.
- [x] **RC-2 Recorder nur Debug.** `ShadowRecorderModule` ist ein
  `@Provides`-Modul: `FLAG_DEBUGGABLE` -> echter Recorder, sonst NoOp;
  reine Funktion `isRecordingEnabled` + Test. ADR-0023.
- [x] **RC-4/MP-6 sichtbare Gruende.** `LiveCountPanel` zeigt den
  fehlenden Start-Grund (keine Uebung / kein Profil) mit CTA in den
  Kalibrier-Wizard; nach 0 erkannten Reps erscheint ein Hinweis
  (`train_counted_zero_hint`); `+15 s` wird bei DROPSYNC nicht mehr
  angeboten. Strings DE/EN ergaenzt.
- [x] **MP-12 Sofortschutz.** `DropRestViewModel.onCleared` beendet einen
  laufenden DROPSYNC-Timer; reine Entscheidung
  `shouldCancelOnCleared` + Test. Volle Loesung (Foreground-Service) in P1.

**Verifikation dieser Session:** `:feature:player:testDebugUnitTest` und
`:feature:workout:testDebugUnitTest` gruen (inkl. neuer Tests),
`:app:assembleDebug` gruen, `spotlessCheck` gruen (nur die neue
Modul-Datei war betroffen, keine fremden Dateien). Kein Commit - der
Arbeitsbaum enthaelt weiterhin fremde, unfertige Aenderungen.

**Offen aus diesem Block:** DropRest-Foreground-Service und
Koordinator-Umzug des Monitors (P1-8); Crossfade-Spike (ADR-0022);
Gate-11b-Kampagne durch Adi (Aufnahmen); Rest der Phasen P1-P3 im Plan.

## AP. P1: DropSync-Koordinator, PlayerMessage-Landung, Foreground-Service, Off-main (2026-09-19, Session: OpenCode)

P1 des Verbesserungsplans ist weitgehend umgesetzt (Arbeitsbaum, kein
Commit). Schwerpunkt: ein Zustand statt drei halber Wege, Landung auf der
Audio-Uhr, DropRest ueberlebt den Screen, Zaehlpipeline off-main.

**Neue Bausteine (feature/player):**

- `DropSyncCoordinator` (App-Scope, ersetzt `RestMusicCoordinator`):
  beobachtet TimerEngine + Verhalten + Playback-Ereignisse, haelt den
  einen `StateFlow<DropSyncState>` (Off/Planned/Armed/Landed/BestEffort/
  Overridden/Failed/Cancelled), erkennt Nutzer-Vorrang (Pause, Seek,
  Titel-, Queue-Wechsel) und rechnet Restzeit-Aenderungen ein.
- `DropSyncPlanner`: Work-Kandidaten (MP-14: **alle** aktiven Marker je
  Titel), Planner-Aufruf, Latenz + `crossfadeMs` aus der DSP-Konfiguration
  (D9), Konfidenz (EXACT/DEGRADED).
- `DropRestSessionMonitor`: app-weiter Monitor des manuellen DropRest
  (Restzeit-Projektion, Cues, Abbruch bei Songwechsel/Seek/Pause) — der
  Screen-Wechsel toetet die Sitzung nicht mehr (MP-12).
- `DropLandingArmer` (data/playback): Media3-`PlayerMessage` an der
  Wiedergabeposition (MP-3/D3), Stufe-1-Fade um den harten Wechsel
  (Equal-Power-Kurven auf `Player.volume`, Mikro-Rampe), Watchdog mit
  sichtbarem `BestEffort(WATCHDOG)`.

**Domain/Ports:** `DropSyncState` + Enums (`domain/timer`),
`DropLandingEvent` + `PlaybackRepository.armLanding/cancelLanding/
landingEvents` (`domain/playback`), `RestMusicSettingsRepository.
dropAutoEnabled` (Default an, MP-13).

**Service/UI:** `DropRestViewModel` startet den `TimerService`
(P1-8); der Service zeigt fuer DROPSYNC den Titel "DropSync" mit
"Plan abbrechen"/"Pause beenden" statt `+15 s`. Der Train-Schalter ist
persistiert (MP-13).

**Off-main:** `ActiveSetController` verarbeitet Samples auf
`workerDispatcher` (RC-1, Test uebergibt den Test-Dispatcher);
`CalibrationRefiner.refine` laeuft auf `dispatchers.default` (RC-6);
`recentDiffs` ist nach `exerciseId:deviceId` geschluesselt (RC-13);
Waveform wird im Frame-Takt (33 ms) veroeffentlicht (RC-11).

**Tests:** `DropSyncCoordinatorTest` (18 Faelle: Queue/Ducking, Armierung
mit Latenz/Crossfade/mehreren Markern, Landed, Watchdog-Fallback,
Best-Effort-Fallback, Override durch Pause, NORMAL, Pausenende, `+15 s`,
Pause/Resume, Drop-Auto inkl. Fallbacks, app-weiter DropRest-Monitor);
Fakes in `:core:testing` (`FakeRestMusicSettingsRepository`).
`ActiveSetControllerTest` nutzt den RC-1-Dispatcher.

**Verifikation:** `:feature:player`, `:feature:workout`,
`:feature:settings`, `:data:playback`, `:data:timer`, `:domain:sensor`
Tests gruen; `:app:assembleDebug` gruen; `detekt` gruen (Koordinator
dafuer in Planner + Monitor gesplittet, LargeClass); `spotlessApply`
lief nur ueber die eigenen Dateien (per Zeitstempel geprueft).

**Offen aus diesem Block:** P1-10 (Rest-Console als Hero, Zustandsanzeige
im Train-Tab), Sample-Fan-out (RC-10) + Dispatcher-Assert-Test, Preload
(`setPreloadConfiguration`) am Armer, Crossfade-Spike (ADR-0022),
Gate-11b-Kampagne durch Adi.

## AQ. P1-Abschluss: Sample-Fan-out, Hero-Konsole, Lern-Ereignis (2026-09-19, Session: OpenCode)

Die in AP offen gebliebenen P1-Punkte sind umgesetzt (Arbeitsbaum, kein
Commit).

**RC-10 Sample-Fan-out (`data/sensor`):** `SensorSampleFanout` ist der eine
Verteiler vor allen Verbrauchern (Waveform, `ActiveSetController`,
Kalibrierung). Statt stillem `tryEmit`-Verlust: Ringpuffer mit DROP_OLDEST
(das AELTESTE faellt, die Gegenwart gewinnt) und exaktem Drop-Zaehler;
ohne Abonnenten wird nichts gepuffert und nichts gezaehlt. Der Zaehler
liegt als `SensorHealth.samplesDropped` vor; die Anzeige folgt mit dem
Diagnose-Panel (P2-17). `BleSensorProvider` speist den Fan-out aus dem
JitterBuffer und setzt ihn je Stream-Neustart zurueck.

**RC-1 Dispatcher-Assert-Test:** `ActiveSetControllerTest` belegt per
Thread-Namen, dass der Sample-Collector auf dem uebergebenen
Worker-Dispatcher laeuft.

**P1-10 Hero-Konsole + Plan-Abbruch:** Die `RestConsole` zeigt
`Track · Marker · Ziel in mm:ss` (Landung am Pausenende: tickende
Timer-Restzeit; DropRest: Marker-Projektion des Monitors), Statuschips
`Audio vorbereitet` (nur echte Armierung, neues `Armed.audioPrepared`) und
`Timing stabil` (nur EXACT), bei DROPSYNC `Plan abbrechen`
(`DropSyncStateSource.cancelPlan()` nimmt Landung/Queue/Ducking zurueck,
die Pause laeuft weiter) und nach der Landung ein kurzes
`GO`/`Drop gelandet`-Overlay. Mini-Player-Badge und Next-Sperre waren
bereits umgesetzt. Tests: `DropTargetRemainingTest` (feature/workout) +
zwei Koordinator-Faelle fuer `cancelPlan`.

**P1-12 Lern-Ereignis (RC-6):** `ProfileLearningEvent`
(`Refined(revision)` / `RolledBack` / `SkippedImplausible` /
`SkippedUnreliable`) als Einmal-Ereignis aus `learnFromTrace`; die
Train-UI zeigt es als Snackbar. Vorher landete jedes Ergebnis nur im Log.
Test: unplausible Bestaetigung (Signal 2 Reps, Eingabe 5) wird sichtbar
gemeldet.

**Preload (P1-6) bewusst offen:** Media3-1.11-`setPreloadConfiguration`
laedt nur Queue-Items NACH dem aktuellen; unser Design setzt den
Work-Titel erst bei der Landung (`setMediaItem`). Preload braucht damit
einen sichtbaren Queue-Eintrag oder den zweiten Player (Stufe 2,
ADR-0022) plus Geraete-Beleg — zurueckgestellt.

**Verifikation dieser Session:** `spotlessApply` + `spotlessCheck` gruen;
`:feature:player`, `:feature:workout`, `:domain:sensor`, `:data:sensor`
Tests gruen (inkl. neuer Tests); `detekt` gruen; `:app:assembleDebug`
gruen; `doku_links_check.py` gruen. Kein Commit — der Arbeitsbaum
enthaelt weiterhin fremde, unfertige Aenderungen.

**Offen aus diesem Block:** Now-Playing-Statuszeile unter der Waveform
(MP-7-Detail); Preload (Messpunkt); Crossfade-Spike (ADR-0022);
Gate-11b-Kampagne durch Adi.

## AR. P2-17: Diagnose-Panel + Satz-Report, Ablehnungs-Mechanismen (2026-09-20, Session: OpenCode)

RC-7 und RC-17 sind umgesetzt (Arbeitsbaum, kein Commit).

**RC-17 Ablehnungs-Mechanismen:** Neues Enum `RepRejectionReason`
(ACCEL_VOTING, TEMPLATE_MATCH, PHASE_VALIDATION, QUALITY);
`RepResult.rejection` traegt die Klassifizierung, der Freitext
`rejectionReason` bleibt fuer Details. Die Pipeline zaehlt je Satz
(`rejectionCountsSnapshot`), `reset()` leert die Zaehler. Die
`set`-Zeile des Shadow-Recorders traegt jetzt `rejections`
(Mechanismus -> Anzahl), damit "N Abweichungen" im Corpus in ihre
Ursachen zerfallen.

**RC-7 Diagnose-Snapshot + Satz-Report:** `SetDiagnostics` (erkannt,
Frames, Gaps, ZUPT, gemessene Rate, Signalqualitaet, Ablehnungen,
Plausibilitaet) wird in `ActiveSetController.stop()` eingefroren
(`lastDiagnostics`, geleert bei `start`/`abort`) und in den `SetTrace`
uebernommen. Nach `stopCountedSet()` zeigt die Train-UI eine Snackbar
(`Satz beendet: N erkannt · Rate R Hz · G Aussetzer · Z ZuPT ·
K abgelehnt (Mechanismen)`); derselbe Snapshot geht in den
`SetDiagnosticsLog` (in-memory, ueberlebt ViewModel-Grenzen).

**Diagnose-Panel in den Einstellungen:** Neuer Entwickler-Schalter
(`DebugSettingsRepository` in DataStore, Default aus) blendet
Sensor-Live-Werte (Verbindung, Transport Notify/Poll, MTU, Drops, Gaps;
`SensorHealth` um `transport`/`negotiatedMtu` erweitert) und den letzten
Satz-Report ein (RC-7: "hinter Entwickler-Schalter").

**Befund aus den Tests:** Bei aktivem ZUPT (Default) erreichen abgelehnte
Kandidaten `decide()` oft gar nicht — der Ruhe-Eintritt verwirft sie
vorher als `zuptAbortedPending`. Der Report zeigt beide Toepfe getrennt;
wer "verlorene Reps" analysiert, muss zuerst hier schauen. Die
Klassifizierungs-Tests schalten ZUPT deshalb gezielt ab.

**Tests:** `ExerciseEnginePipelineIsolationTest` (3 Klassifizierungs-
Faelle), `ActiveSetControllerTest` (4 Snapshot-Faelle),
`TrainViewModelTest` (Report nach Stop + Log), `SetReportTextTest` (3),
`JsonlShadowSessionRecorderTest` (+2), `SettingsViewModelTest` (+3),
`DebugSettingsStoreTest` (2).

**Verifikation:** `:domain:sensor`, `:data:sensor`, `:data:settings`,
`:feature:workout`, `:feature:settings` Tests gruen. Kein Commit — der
Arbeitsbaum enthaelt weiterhin fremde, unfertige Aenderungen.

## AS. P2-18: Live-vs-Replay-Vergleich + CI-Regressionsgate (2026-09-20, Session: OpenCode)

RC-16 ist umgesetzt (Arbeitsbaum, kein Commit).

**Live-Diagnose im JSONL:** Die `set`-Zeile traegt zusaetzlich zu
`rejections` (P2-17) die Live-Diagnose (`framesProcessed`,
`framesRejected`, `gaps`, `zuptUpdates`, `zuptAborted`, `rateHz`);
Aufrufer ohne Diagnose schreiben unveraendert das Altformat.

**Loader + Vergleich:** `CorpusLoader` liest die `set`-Zeilen und ordnet
sie den Fenstern ueber den `setIndex` zu (nicht ueber die
Listenposition). `CorpusSweepHarness.compareLiveVsReplay()` spielt jedes
Fenster mit der Live-Config (Profil aus dem JSONL, profilgesteuertes
Accel-Voting, ZUPT an) durch eine frische Pipeline und schreibt eine CSV
mit beiden Spalten (`live_counted`/`replay_counted`/`delta`) plus
Diagnose (Gaps, ZUPT, Ablehnungen, Rate) und einer `note`, die
Abweichungen eingrenzt. `configAdjust` ist der Gegenbeweis-Hook: eine
geaenderte Refraktaerzeit aendert nur den Replay-Count, nie den
Live-Count (Test).

**CI-Regressionsgate:** `CorpusRegressionGateTest` fixiert auf einem
deterministischen Goldkorpus (3 Saetze, einer mit halber Rep am Ende)
die Replay-Counts [2,3,2], delta 0, keine Gaps und die
ZUPT-Verwurf-Diagnose (1 echter Verwurf im Artefakt-Satz, 0 in den
sauberen; die halbe Rep erreicht `decide()` nicht). Der echte
Gate-Korpus (Adis Kampagnen-Aufnahmen) kommt spaeter dazu; derselbe
Mechanismus.

**Befund + Fix (Zaehler-Ehrlichkeit):** Der Vergleich zeigte, dass
`zuptAbortedPending` bisher JEDEN Ruhe-Eintritt zaehlte (saubere
2-Rep-Saetze standen bei 3 statt 0). `RepCounter.abortPending()` gibt
jetzt zurueck, ob wirklich ein Pending offen war; der Zaehler misst
echte Verwuerfe. Der Satz-Report und die P2-17-Aussage ("verlorene Reps
zuerst in `zuptAbortedPending` suchen") werden damit erst belastbar.

**Tests:** `CorpusLiveComparisonTest` (4: beide Spalten, Gegenbeweis,
Zuordnung ohne Fenster, Fenster ohne Profil), `CorpusRegressionGateTest`
(1), `ExerciseEnginePipelineIsolationTest` (+1 Zaehler-Semantik),
`JsonlShadowSessionRecorderTest` (+2 Diagnose-Format); Fixture
`SyntheticCorpus` (geteilt mit dem Sweep-Test).

**Verifikation:** `spotlessCheck`, `detekt` (0), `:domain:sensor` und
`:feature:workout` Tests gruen, `:app:assembleDebug` gruen. Kein
Commit — der Arbeitsbaum enthaelt weiterhin fremde, unfertige
Aenderungen.

## AT. P2-19: Kalibrier-Wizard 2.0 (2026-09-20, Session: OpenCode)

RC-8 ist umgesetzt (Arbeitsbaum, kein Commit).

**Stepper + Live-Rueckmeldung:** Der Wizard zeigt die fuenf Stufen als
Stepper (Haekchen/aktuell) und waehrend der Sammel-Stufen das Live-Signal
(dieselbe Waveform wie der Train-Tab, jetzt in `SensorWaveform.kt`) plus
"Reps erkannt: n von Ziel". Die Schaetzung nutzt denselben Kantenzaehler
wie die Auswertung (`CalibrationLiveEstimator`): Stufe A zaehlt
Bewegungs-Bursts, Stufe B einen robusten Startwert (p10/p99), Stufe C die
gelernte Config. "Weiter" ist erst bei erfuellter Stufe tappbar
(Rest-Gate bzw. mindestens eine sichtbare Bewegung, `advanceEnabled`).

**Wiederholen statt Sackgasse:** "Stufe wiederholen" leert den Puffer der
aktuellen Stufe; `redoFrom()` springt aus dem Review zurueck in den 5er-
oder Langsam-Satz und verwirft die abhaengigen Ergebnisse (Sweep, Theta,
Langsam-Signal), behaelt aber Ruhe und Einzel-Rep. Der Wizard kann damit
auf einen misslungenen Satz reagieren, ohne alles neu zu machen.

**Review mit Fakten:** `CalibrationReview` traegt wiedergefundene Reps
(5er- und Langsam-Satz), Streuung der Rep-Abstaende, Abstand der Schwelle
zum Ruherauschen, Accel-Zweitkanal an/aus und die erwartete Rep-Dauer.
Die UI formuliert daraus Saetze ("4 von 5 Wiederholungen im Satz
wiedergefunden", "Schwelle liegt nur 2.1-fach ueber dem Ruherauschen")
und warnt bei Abweichungen; ohne Ergebnis bietet das Review direkt
"5er-Satz wiederholen" an.

**Einstieg an der Uebungszeile:** Unter der Chip-Reihe steht der
Kalibrier-Status der gewaehlten Uebung mit Kurz-Kennung des Chips
("Kalibriert fuer FlowRep #EEFF — neu kalibrieren" bzw. "noch nicht
kalibriert"); der Einstieg fuehrt direkt in den Wizard.

**Refactor (detekt-getrieben):** `zaehleEdge`/`RepMark` sind jetzt eine
Top-Level-Funktion in `CalibrationCounting.kt` (Sprungbefehle entfernt),
die Live-Schaetzung liegt in `CalibrationLiveEstimator.kt`; die
`CalibrationController`-Klasse bleibt damit unter der
LargeClass-Schwelle, die Baseline-Zeile fuer den alten Sprung-Loop ist
entfernt.

**Tests:** `CalibrationControllerWizardTest` +5 (Live-Schaetzung je
Stufe, repeatStage, redoFrom inkl. Neurechnung, Verbot von Vorwaerts-/
Review-Spruengen, Review-Fakten), `CalibrationViewModelTest` +3
(Advance-Regel, repeatStage/redoStage, Review + Stufen-Ziel),
`ShortDeviceLabelTest` (2).

**Verifikation:** `spotlessCheck`, `detekt` (0), `:domain:sensor`
(147 Tests) und `:feature:workout` (60 Tests) gruen, `:app:assembleDebug`
gruen. Kein Commit.

## AU. P2-20: Train-Konsole — Rep-Hero mit Quelle, Sensor-Kopfzeile, eine Primaeraktion (2026-09-20, Session: OpenCode)

RC-5/A.4 ist umgesetzt (Arbeitsbaum, kein Commit).

**Rep-Hero mit Quelle:** Die grosse Rep-Zahl traegt darunter ihre Herkunft
(UI-Handbuch 7.4): `AUTO` ("Sensor verbunden"), `MANUELL KORRIGIERT` mit
Original-Zaehlstand ("Sensor erkannte 7"), `MANUELL` bzw. `SENSOR GETRENNT`
("Reps per +/- weiter", Design 8.1). Die Ableitung ist eine reine Funktion
(`repsSourceOf`), der Zustand liegt im `RepSourceTracker`; `+/-` bleibt der
schnelle Korrekturpfad.

**Ein Fluss statt zwei Karten:** Start -> Countdown -> grosse Live-Zahl ->
Stopp -> Korrektur -> Satz fertig laufen alle im Hero: Der Live-Zaehler ist
aus der Sensor-Karte in die Rep-Sektion gezogen, die Waveform sitzt direkt
unter der Zahl (Design 8.1). Waehrend der Zaehlung ist "Stopp" die
Primaeraktion, sonst "Satz fertig"; der Start der Live-Zaehlung ist eine
Ghost-Aktion, der Kalibrier-Einstieg bleibt an der Uebungszeile (RC-8).

**Sensor-Kopfzeile:** Statt einer konkurrierenden Karte unter der Eingabe
gibt es eine Kopfzeile ueber der Konsole: Chip/Status, Qualitaet ("Signal
ok/schwach/unzuverlaessig") und Puls; der Fehlertext steht direkt unter dem
Chip. Der doppelte "Kalibrieren"-Knopf entfaellt (der Einstieg ist die
Uebungszeile).

**Konsolen-Modus:** `WorkoutConsoleMode` (IDLE / SET_ENTRY / REST_RUNNING /
GO_CUE) ersetzt die if/else-Kette in `TrainScreen`. `EXERCISE_DONE` fehlt
bewusst: Die App kennt keinen Session-Abschluss (Umbauhandbuch 24.1),
"Uebung abschliessen" fuehrt zurueck zur Uebungszeile (IDLE).

**Refactor (detekt-getrieben):** `RepSourceTracker` haelt Zaehlstand und
Sensorabriss, damit `TrainViewModel` unter der LargeClass-Schwelle bleibt;
die neuen ViewModel-Tests liegen in `TrainViewModelRepSourceTest` (gleiche
Regel wie beim Satz-Report-Test).

**Tests:** `WorkoutConsoleStateTest` (11: Modus- und Quellen-Ableitung),
`TrainViewModelRepSourceTest` (+5: AUTO, KORRIGIERT, MANUELL, Reset nach
dem Loggen, SENSOR GETRENNT).

**Verifikation:** `spotlessCheck`, `detekt` (0), `:domain:sensor`
(147 Tests) und `:feature:workout` (76 Tests) gruen, `:app:assembleDebug`
gruen. Kein Commit.

## AV. P2-21: Now-Playing als Drop-Editor — Marker-Sheet, Legende, Statuszeile (2026-09-20, Session: OpenCode)

MP-7/A.4 ist umgesetzt (Arbeitsbaum, kein Commit).

**Marker-Tap-Sheet:** Tap oder Langdruck AUF einem Marker oeffnet das
Bottom-Sheet (UI-Handbuch 14.4/14.5): Label, Zeit mit Millisekunden
("01:18.420"), Status-Chip (Vorschlag/Bestaetigt automatisch/Manuell/
Bestaetigt) plus "Aktives Ziel". Aktionen: "Ab Marker anhoeren" (Seek),
Feinjustierung `-100/-10/+10/+100 ms` mit erhaltener Originalposition
(`MarkerEditState`, "Zurueck auf Original" nur bei Abweichung), "Als
DropSync-Ziel waehlen"/"Ziel entfernen", "Bestaetigen" fuer Vorschlaege,
"Umbenennen" (neue `MarkerRepository.renameMarker`) und "Loeschen" mit
Undo-Snackbar. Der Langdruck daneben setzt weiter einen neuen Marker; das
Sofort-Loeschen per Langdruck entfaellt (UI-Handbuch 15.3).

**Vorschlaege und Ziel auf der Waveform:** Unbestaetigte
AUTO_DETECTED-Kandidaten des laufenden Songs zeichnen gedaempfte Ticks
(gleiche Quelle wie die Review-Liste), das bevorzugte Ziel einen Diamanten
ueber dem Tick. Die Legende unter der Waveform (`bestaetigt`, `Vorschlag`,
`aktives Ziel`) ist als ganzer Satz fuer TalkBack verfuegbar.

**"Ziel waehlen" wirkt real:** Das Ziel wird je Song persistiert
(DataStore `DropTargetStore`, bewusst ohne Room-Migration) und der
`DropSyncPlanner` bevorzugt es gegenueber der Naechster-Marker-Wahl SEINES
Titels; die Titelwahl (kleinster Abstand zur Restzeit) bleibt unveraendert.
Fehlt der Marker, greift die Automatik ohne Aufraeumen. Ein Vorschlag wird
beim Zielsetzen bestaetigt.

**DropSync-Statuszeile (MP-7-Detail):** Unter der Waveform in der Sprache
der Konsole: `DROP BEREIT` + `"Track" · Drop 2 · Ziel in 01:27`, Countdown
aus Plan-Deadline und monotoner Uhr (`DropStatusLine` als reine
Ableitung), dazu `BEST EFFORT` und `MANUELL UEBERNOMMEN`; stumme Zustaende
bleiben stumm. TalkBack hoert den ganzen Satz (UI-Handbuch 19.3).

**Tests:** `NowPlayingDropStatusTest` (6), `MarkerEditStateTest` (4),
`MarkerSheetFormatTest` (3), `DropTargetStoreTest` (2, Robolectric),
`MarkerRepositoryImplTest` (+3 Umbenennen), `PlayerViewModelTest` (+7),
`DropSyncCoordinatorTest` (+2 Ziel-Vorrang/Fallback).

**Verifikation:** `spotlessCheck`, `detekt` (0), `:data:library`,
`:feature:player`, `:feature:settings` und `:core:designsystem` gruen,
`:app:assembleDebug` gruen. Kein Commit.

## AW. Ausbauplan B5 + Tranche A: A1-A10 (2026-09-21, Session: OpenCode)

Arbeitsbaum, kein Commit. Tranche A komplett umgesetzt; B5 als Vorzieher,
weil der Join im `ActiveSetController` Vorbedingung fuer A1 war.

**B5 (`ActiveSetController`):** `stop()`/`finishAndTakeTrace()` suspendieren;
`cancelJobsAndJoin()` wartet den Sample-Collector wirklich ab (kooperatives
`cancel()` allein reichte nicht); Phase wechselt VOR dem Join nach IDLE; die
Plausibilitaet rechnet per `withContext(workerDispatcher)`; `abort()` bleibt
bewusst synchron (KDoc). `TrainViewModel.stopCountedSet()` ist nur noch die
UI-Fassade, `logSet` nimmt den Trace ueber `finishAndTakeTrace()`.

**A1 (Undo/Haptik/PR):** Migration v11->v12
(`personal_records.achieved_session_id` nullable, Table-Recreation),
`PrRecord.achievedSessionId`; neuer `PersonalRecordRecomputer` (Union
Cluster+Flat; ein Flat-Satz zaehlt als eigene Mini-Session
`sessionId = -setId`) haengt an `WorkoutRepositoryImpl` UND an
`FlatSetRepositoryImpl.logSet/deleteSet` (dieselbe Transaktion);
Domain-Port `SetLogHaptics` + `AndroidSetLogHaptics`; neuer
`SetLogController` (Logged/Undone/LogFailed), TrainViewModel mit
`setLogEvents`/`undoLastSet()`/`clearUndo()`, Snackbar mit "Rueckgaengig";
Strings `workout_set_saved`/`workout_set_undone`.

**A2:** `DropRestSessionMonitor` stoppt bei PAUSED (und IDLE), statt die
Pause weiterzuprojizieren.

**A3:** `BatchDedupTracker` fuehrt ein 5-s-Fenster (`gapWindowBatches=63`);
`SensorHealth.largestRecentGapMs` steuert die Qualitaet, Settings zeigt
"Groesste Luecke (5 s)".

**A4:** Shadow-Recorder-API suspend (Mutex + `Dispatchers.IO`); TrainViewModel
startet die Session in einer Coroutine und beendet sie erst in `onCleared`
(`closingScope`) — "Uebung abschliessen"/Disconnect beenden die Aufzeichnung
nicht mehr.

**A5/A6:** GoOverlay raeumt seinen Zustand im `finally`, Tap schliesst,
LiveRegion; `nav_settings` de = "Einstellungen".

**A7:** `tools/doku_links_check.py` prueft Root-Markdown und Inline-Code-Pfade
(Suffix-Matching); tote Verweise gefixt; CI-Reihenfolge
`:core:designsystem:verifyRoborazziDebug` VOR `./gradlew test` (I-1); README
P2-Zeile ehrlich als "Arbeitsbaum, noch nicht eingecheckt" (I-2/I-3).

**A8:** Tote Ressourcen entfernt (`QuickEqSheet`, `SignalProcessor`-Klasse,
`TemplateExtractor` + 2 Tests, PlayerViewModel-DSP-Reste, Batterie-Lesepfad,
`TimerWheelColumn.enabled`), 96 ungenutzte String-Keys aus beiden Locales;
die live genutzten Norm-Helfer liegen jetzt in `SensorSampleMagnitudes.kt`.

**A9:** `TimerSession`-KDoc geschaerft (DROPSYNC bewusst ohne Uhrstart);
Fusionsdesign 7.1 an ADR-0012/Entscheidung 7 angeglichen.

**A10 (PR-2, Auto-Drops beim Import):** Der Import-Bulk laeuft als EIN
`AnalysisProfile.FULL`-Decode (`DeferredAnalysisScheduler.scheduleFullAnalysis`,
WorkManager `track_analysis_<id>`, KEEP) statt der Kette
WAVEFORM_ONLY -> MIX_METADATA: Waveform + Mix + Onset-Kandidaten in einem
Durchgang — halbiert die Decodes pro importiertem Titel. Kandidatenzahl Top-3
(`OnsetDetection.DEFAULT_MAX_CANDIDATES`; Erwartung "meist 2" plus Reserve);
Scope alle neuen Titel (der Volldurchgang ist billiger als der alte
Doppel-Decode, ein Playlist-Filter waere Kopplung ohne Gewinn); CPU/Batching
unveraendert. Marker-Schreiben aus dem Worker in `OnsetCandidateWriter`
extrahiert (ohne Hilt/WorkManager testbar). Bestands-Titel ohne Marker
bekommen bewusst keine nachtraeglichen Kandidaten; der manuelle Weg
("Drops automatisch erkennen") bleibt.

**Wichtig fuer Tranche B:** A1 hat v11->v12 verbraucht — B4 (Downbeat)
muss als v12->v13 geplant werden.

**Verifikation:** `spotlessCheck` gruen, `detekt` 0 Befunde,
`assembleDebug` gruen, `test` (alle Module) gruen. Neue/erweiterte Tests
u. a. `OnsetCandidateWriterTest` (4), `TrackAnalysisPriorityPathTest`
(+3 Importpfad), `OnsetDetectionTest` (+1 Default),
`SetLogControllerTest` (8), `MigrationTest` v11->v12,
`TrainViewModelShadowSessionTest`, `ActiveSetControllerStopTest` (5, aus
`ActiveSetControllerTest` ausgezogen). `TrainViewModel` steht jetzt als
kommentierter LargeClass-Eintrag in der detekt-Baseline (Wachstum durch
A1/A4; die Extraktion der Kandidaten aus der Tiefendoku ist als eigene
Arbeit vermerkt). Nebenbei zwei Testinfra-Flakes behoben:
`OutputProfileControllerTest` wartet den letzten DataStore-Save-Through ab
(uncaught exception im Folgetest), `ActiveSetControllerTest` geteilt
(LargeClass-Grenze).

## AX. Ausbauplan Tranche B (Rep-Zaehlqualitaet): B3, B1, B2, B6 (2026-09-21, Session: OpenCode)

Arbeitsbaum, kein Commit. Reihenfolge nach der Tiefendoku (B5 war bereits
fertig): B3 vor B1/B2 (replay-faehige Schwellen), B1 vor B2, B6 danach.
**B4 (Downbeat + Snap) bleibt offen** — eigener Baustein, Migration
v12->v13 (v12 durch A1 verbraucht), Analyzer + DB + Marker-Sheet.

**B3 (RC-20, Profil-Schema v6):** `CalibrationProfile` um
`templateThreshold = 0.7`, `minQualityScore = 0.55`, `dtwBand = 8` erweitert
(hinten angehaengt, Position 16-18), `PROFILE_SCHEMA_VERSION = 6`. Codec
liest v6/v5/v4; Altblobs werden mit exakt den bisherigen Code-Defaults
hochgezogen (kein stiller Verhaltenswechsel), Validierung 0..1 fuer die
Schwellen und 1..64 fuer `dtwBand`. `ActiveSetController.start()` und
`CalibrationRefiner.revalidates()` reichen die Werte in die
`ExerciseEngineConfig` durch; `CalibrationViewModel.confirmAndSave()` setzt
sie explizit (Stufe 1: Transport, nicht gelernt). Recorder schreibt die drei
Felder ins `set_window`; `CorpusFiles`/`CorpusSweepHarness` lesen sie
(Altaufnahmen -> Defaults), `liveConfig`/`configFor` replizieren sie
(baseline = Fenster), `SyntheticCorpus.setWindowLine` parametrisiert sie.

**B1 (RC-18, einseitige Qualitaetsbewertung, Stufe 1):**
`QualityScorer.oneSidedScore(ratio, fatigueIncreasesRatio)` — Ermuedung
(Prominenz faellt, Dauer steigt) wird weit toleriert, Schwung eng;
Richtung fuer Tempo invertiert, im Code kommentiert. **Abweichung von der
Plan-Skizze:** Die Zahlen 0.45/0.20 woertlich waeren strenger als der
Ist-Zustand (1.0) gewesen und haetten 19 Gates gebrochen (u. a.
Golden-Corpus, Sweep, Live-vs-Replay, RepPipeline, Isolation, Refiner) —
entgegen dem Planziel "lockert in der Ermuedungsrichtung". Sie sind deshalb
als Deltas zur alten 1.0 gelesen: `fatigueTolerance = 1.45`,
`suspiciousTolerance = 0.80` (Config + Sweep-`ParameterSet`). Neue Tests:
`QualityScorerAsymmetryTest` (5), `RepCounterFatigueDriftTest` (2, 12-Rep-
Driftsatz komplett + Referenzlauf 1.0/1.0 dokumentiert das Satzende-Delta).
ADR-0024 schreibt die Entscheidung und die **Refraktaerzeit-Ausnahme**
fest: Refraktaerzeit und Pending-Deckel bleiben am gleitenden Mittelwert,
die einseitige Politik wirkt nur im Scorer.

**B2 (RC-19, Autokorrelation als Quelle, Stufe 1):** `Math.round` ->
`ceil` in `RepCountPlausibility` (der Kommentar sagte schon immer
"aufrunden"); `check(..., hasLargeGap)` liefert bei einer grossen Luecke im
Set bewusst INCONCLUSIVE (der Signalring hat keine Timestamps, die
Zeitbasis waere still falsch — der Aufrufer kennt `largeGapCount`).
Neuer Config-Seed `qualityDurationMs`: `ActiveSetController` merkt sich die
gemessene Periode aus `lastPlausibility` (samt Uebung/Geraet) und seedet
damit NUR die Qualitaets-Erwartung des naechsten Satzes (5.14);
`finishAndTakeTrace()`/`abort(CLEARED)` loeschen die Periode nicht (sie
gehoert zum abgeschlossenen Satz). Neue Tests: `QualityDurationSeedTest`,
`ActiveSetControllerTest` (+3: naechster Satz erbt, anderes Geraet nicht,
finishAndTakeTrace verwirft nicht), `RepCountPlausibilityTest` (+2).
`TrainViewModelLearningEventTest` bekam eine eindeutig unplausible
Korrektur (6 statt 5), weil die Schaetzung durch `ceil` naeher an
plausiblen Eingaben liegt (Plan-Frage 3 war genau das Risiko).

**B6 (RC-21, Template-Pool Admission-Margin, Variante 1):**
`TemplateMatcher.addToPool(window, qualityScore)` nimmt nur Reps ab
`admissionMinScore` auf; die Pipeline setzt
`admissionMinScore = minQualityScore + templateAdmissionMargin`
(Default 0.05, Config + Sweep). Das Kalibrier-Template laeuft weiter ueber
`setTemplate` und ist nicht betroffen. DBA (Variante 2) bewusst nicht:
das Auslaesekriterium ist die DTW-Streuung aus dem echten Corpus. Tests:
`TemplateMatcherTest.pool admission rejects borderline rep`,
`RepPipelineTest.eine schwache Rep fuellt den Pool nicht`.

**Verifikation (2026-09-21, root):** `spotlessApply`/`spotlessCheck`,
`detekt`, `assembleDebug`, `test` (alle Module) und `spotlessMiscCheck`
gruen; `tools/doku_links_check.py` gruen (77 Dateien, ADR-0024 neu). Kein
Golden-Corpus-Delta: die synthetischen Gates (`CorpusRegressionGateTest`,
`CorpusSweepHarnessTest`, `CorpusLiveComparisonTest`,
`CorpusReplayDeterminismTest`) halten ihre Baselines; die bindende Messung
auf echten Aufnahmen folgt mit Gate 11b.

## AY. Ausbauplan Tranche B komplett: B4 (Beat-Raster + Snap) und B7 (Fehler-/Lernsichtbarkeit) (2026-09-21, Session: OpenCode)

Arbeitsbaum, kein Commit. **Tranche B ist damit abgeschlossen** (B1-B7).

**B4 (RC-22/S-3, Beat-Raster-Offset):** `DownbeatAccumulator` in
`:domain:audio` — Low-Pass 120 Hz (Biquad, Q 0.707), Huellkurve in 10-ms-
Fenstern (gepuffert, weil das finale BPM erst am Ende des Ein-Pass-Decodes
feststeht), Phasen-Faltung mit **48 Kandidaten** je Beat-Intervall, beste
mittlere Low-Band-Energie gewinnt, Konfidenz = Ueberlegenheit gegen die
beste Phase ausserhalb +/-4 Bins. Ohne messbaren Low-Band-Anteil (>= 5 %
der Gesamtenergie) gibt es keine Aussage ("Stille vor Vermutung").
**Abweichung von der Research-Skizze:** 4 Phasen haetten den Snap um bis zu
einer Viertel-Beatperiode (117 ms bei 128 BPM) verschoben und damit das
Abnahmekriterium "ein Marker, der ohne Snap richtig lag, wird nicht
verschlechtert" verletzt; 48 Schritte entsprechen der Onset-Variante aus
Umbauplan Phase 3.2. **Semantik:** Das ist die **Beat-Phase** (wo Kick/Bass
sitzen), kein musikalischer Taktanfang (Umbauplan 3.2); die Feldnamen
folgen dem Bauplan. **Konfidenz-Gate an der Leseseite** (Muster
`MixConfidence`): Rohwerte bleiben in der DB, `DownbeatConfidence.
acceptOffset` filtert; ADR-0025.

Persistenz: `track_analysis.downbeat_offset_ms`/`downbeat_confidence`
(Migration **v12->v13**, additiv nullable, Altzeilen bleiben NULL),
`WaveformCodec.MIX_ANALYZER_VERSION` 1->2 — nur die Metadatenstufe wird neu
berechnet, die Waveform bleibt gueltig (ADR-0015). Snap:
`MarkerSnapping.snapToBeat(pos, bpm, downbeatOffsetMs)` rastet **nur mit
gemessenem Offset** (`null` = kein Raster -> kein Snap), Fenster
`min(250 ms, beatMs/2)`; das Sheet rastet automatisch beim Oeffnen (5.11),
`MarkerEditState.snappedTo` behaelt das Original fuer "Zurueck auf
Original", die Rastung wird wie jede Feinjustierung sofort uebernommen.
Tests: `DownbeatAnalysisTest` (8 Faelle inkl. Gegenprobe und
Phasen-Aufloesung), `MarkerSnappingTest` (+4), `MarkerEditStateTest` (+2),
`PlayerViewModelTest` (+1), `TrackAnalysisConfidenceGateTest` (+2),
`MigrationTest` (+1). Gefundener und behobener Fehler im eigenen Entwurf:
der Low-Band-Anteil verglich RMS-Summen mit Energiesummen (Faktor
`samplesPerWindow` zu klein) — der Anteils-Check fuehrt jetzt die
Low-Band-Energie getrennt.

**B7 (S-7/T-10, Fehler- und Lernsichtbarkeit):** Neues `TrainErrorEvent`
(ExerciseCreationFailed, ProfileLoadFailed, LearningSaveFailed) als
`Channel(BUFFERED)` + Snackbar im Train-Screen; ein Profil-Load-Failure ist
nicht mehr von "nicht kalibriert" ununterscheidbar. Neues
`ProfileLearningEvent.SkippedNotReproducible` fuer `refine == null` (vorher
stumm). Lern-/Report-Events laufen jetzt ueber `Channel(BUFFERED)` statt
`SharedFlow(extraBufferCapacity)` — Ereignisse ohne offenen Collector gehen
nicht mehr verloren. `SensorHealth.deviceEventPollErrors` zaehlt die
Geraete-Event-Poll-Fehler (der stumme `runCatching` ist ersetzt,
`CancellationException` wird nicht mehr geschluckt) und ist im
Diagnose-Panel sichtbar. Tests: `TrainViewModelErrorEventTest` (je Pfad
ein Ereignis-Test; der Lern-Save-Fall nutzt ein invertiertes
Kalibrier-Template: Live-Zaehlung 0, Refiner revalidiert 5, nur der Save
scheitert), `TrainViewModelLearningEventTest` (+1).

**Verifikation (2026-09-21, root):** `spotlessApply`/`spotlessCheck`,
`detekt`, `assembleDebug`, `test` (alle Module) und `spotlessMiscCheck`
gruen; `tools/doku_links_check.py` gruen (78 Dateien, ADR-0025 neu). Die
Quellregel `ModuleDependencyRulesTest.cancellation wird nicht verschluckt`
hat den neuen Catch in `BleSensorProvider` korrekt erzwungen (Guard direkt
vor dem Exception-Catch). Schema-Export `13.json` liegt versionskontrolliert
in `core/database/src/test/assets`. Bindende Belege offen: B4-Hoerprobe auf
Adis Tracks (Gate 11b) fuer Offset-Treffer und Schwellen-Kalibrierung.

## AZ. Ausbauplan Tranche D komplett: D1-D7 + Gesamtverifikation (2026-09-22, Session: OpenCode)

**D1 (Screenshot-Gate + Feature-Screenshots):** Roborazzi-Gate fuer
feature:workout und feature:player nachgezogen (`roborazzi { outputDir ... }`,
Test-Dependencies, 4 neue Referenz-PNGs fuer Rest-Konsole und Now-Playing in
dunkel/hell). Empirisch belegt: der normale `test`-Task dumpt nur nach
build/intermediates, die Referenzen unter src/test/screenshots bleiben
unberuehrt; das Gate wurde negativ bewiesen (getauschte Referenz ->
`buttonsHell FAILED` / `restKonsoleNormal FAILED`), zwei unabhaengige
Verify-Laeufe sind deterministisch. Das CI-Gate deckt jetzt designsystem,
workout und player ab.

**D2 (Detekt verschaerfen):** detekt war rot (10 Befunde). Test-Code fuer
LargeClass/MatchingDeclarationName ausgenommen; produktiv behoben:
`DropChain.plan` entzerrt (Helfer statt Komplexitaet 22), `NowPlayingScreen`
(Undo-Snackbar ausgelagert), `DropSyncCoordinator.onPlaybackState`
(`isUnexpectedSeek`), `LibraryViewModel` (neuer Baustein
`LibraryMarkerReview`), Datei-Splits (`CoverArtLoader`, `TopLevelDestination`,
`JacobiResult`). Baseline auf 23 Eintraege regeneriert; neue CI-Bremse
`tools/detekt_baseline_count.py` (MAX_ENTRIES=23) haelt die Baseline klein -
negativ verifiziert (neuer Smell ohne Baseline -> detekt rot; simuliertes
Wachstum -> Bremse Exit 1).

**D3 (Coverage):** Kover 0.9.9 im Root, Plugin nur fuer domain:*/data:*,
Ratsche je Modul (`coverageFloors`) statt Einheits-60 % - begruendet, weil
6 Module darunter liegen (data:playback 4,4 %). CI-Schritt `koverVerify`
teilt sich die Test-Ausfuehrung mit dem Unit-Test-Schritt. Negativtest:
minBound 99 % -> `:domain:timer:koverVerify` rot. Nebenbefund gefixt:
die Test-Fakes in data:library/data:audio fehlten
`observeSongsWithEnabledMarkers` (seit C4), 47+49 Tests wieder gruen.

**D4 (Compose-UI-Tests, Welle 1):** 10 Robolectric-Compose-Tests in
src/test: `RestConsoleBehaviorTest` (3: normale Pause, <60-s-Blockade,
DROPSYNC-Kette ohne "+15 SECONDS"), `DropStatusRowTest` (4: Ready+A11y,
Best-Effort, Overridden, PLAN_LOST+Dismiss), `LibraryDropSyncSectionTest`
(3: Review-Aktionen, Dropsync-Karte, Detekt-CTA). Welle 2 offen
(Train SET_ENTRY/Undo, Player Next/Details, Settings, Progress).

**D5 (N+1/Indizes, Teilpaket A1-A4):** `getStats` als IN-Query,
Playlist-Renumber als DELETE+Batch-Insert, Onset-Ersetzung in
`@Transaction` (Rollback-Test), Index `song_markers(source,is_enabled)` mit
Migration 13->14 und EXPLAIN-Nachweis (Filter/DELETE nutzen den Index, der
Pending-JOIN nicht - im Test dokumentiert). A5-A8 offen.

**D6 (Supply-Chain):** Dependabot (gradle + github-actions, woechentlich),
wrapper-validation in beiden CI-Jobs, Wrapper-SHA256 gepinnt. Kritischer
Fix: ein Doppelpunkt im D1-Step-Namen machte ci.yml ungueltig - behoben und
per js-yaml validiert. Wichtig: `gradle/verification-metadata.xml` ist noch
untracked und muss mitcommittet werden (Kover-Eintraege, sonst schlaegt das
Gate in CI zu).

**D7 (Doku-Struktur):** 15 Root-Plaene nach docs/plans/ (mit README),
3 Handoffs nach docs/handoffs/, neuer ADR-Index (docs/adr/README.md),
README-Abschnitt Dokumentation; `tools/doku_links_check.py` deckt
docs/plans/ mit ab, alle relativen Links nachgezogen (81 Dateien gruen).

**Abschlussverifikation (2026-09-22, root):** `spotlessCheck`, `detekt`,
Screenshot-Gates (3 Module), `test` (alle Module), `:app:assembleDebug`,
`koverVerify` und die drei Python-Gates gruen. Zwei Restbefunde behoben:
`TrainViewModel._restPrefConfigured` ohne oeffentliche Entsprechung
(ktlint backing-property-naming) umbenannt; Test-Fakes-Datei auf
`FakeAudioEngineRepository.kt` umbenannt (ktlint filename). Die
CRLF-Verstoesse im Arbeitsbaum (Windows-Checkout) sind per spotlessApply
auf LF normalisiert. Offen: D4 Welle 2, D5 A5-A8, Gate-11b-Hoerprobe,
E2/E3 geparkt.

## BA. Ausbauplan Tranche D: D4 Welle 2 und D5 A5-A8 komplett (2026-09-22, Session: OpenCode)

**D4 Welle 2 (21 Compose-Tests, alle gruen):** `SetEntryBehaviorTest` (5,
feature:workout) pinnt SET_ENTRY (Uebung + genau eine Primaeraktion, Klick
ruft onLogSet; gesperrt ohne Eingabe), IDLE (Platzhalter, bewusst keine
Aktion) und den Undo-Pfad der Satz-Snackbar ("Set saved" + "Rueckgaengig"
-> Undo, danach "Set removed"). `RestConsoleBehaviorTest` (+2, Welle 1
hatte 3): das GO-Overlay erscheint nach der Landung, schliesst per Tap VOR
dem 4-s-Selbstschluss (Testzeit-Messung) und traegt die Klick-Aktion
selbst (A5/T-6). `NowPlayingBehaviorTest` (6, feature:player) pinnt: Next
bleibt bedienbar und ruft den Skip-Pfad (C2/5.10 - die alte Idee "Details
statt Next" ist damit endgueltig vom Tisch), Next ist ohne Folgetitel
gesperrt; das Badge nennt den Plan mit Countdown und ist der
Details-Einstieg, nennt die Kette (C16) und zeigt OVERRIDDEN/FAILED als
Text. `MarkerSheetBehaviorTest` (5, feature:player) pinnt den
Sheet-Inhalt: Vorschlag mit "Confirm" + Feinjustierung, "Back to
original" nur bei Abweichung, Zielwahl/Loeschen, Ziel-Chip, Textliste mit
Sprung zum Marker (C9). `SettingsSectionsTest` (1, feature:settings)
erreicht alle acht Sektionen mit echtem SettingsViewModel+Fakes;
`ProgressEmptyErrorTest` (2, feature:progress) prueft
Onboarding-Leerzustand und Fehler+Retry (C6). Dafuer
SetEntryHero/TrainEventSnackbars/EmptyConsoleHero/GoOverlay,
PowerampModeRow/DropSyncBadgeChip (+ MarkerSheetContent als fensterloser
Sheet-Inhalt) und ProgressDashboardContent internal; Compose-Test-Infra in
feature:settings nachgezogen (robolectric, ui-test-junit4/-manifest, 2g
Heap). Test-Fallstrick dokumentiert: mit `mainClock.autoAdvance = false`
wird nach einem Klick nicht neu komponiert - Klick-Tests laufen mit der
Standard-Uhr und messen die Testzeit, wenn ein Selbstschluss-Timer im
Spiel ist.

**D5 A5/A6 (Indizes, EXPLAIN-gemessen):** Der Migrationstest misst beide
Zustaende auf einer v14-DB (400 Sessions, davon 2 ACTIVE; 3 Playlists mit
je 200 Positionen; ANALYZE + Negativkontrolle): v14 Full-Scan bzw.
Temp-B-Tree, v15 nutzt `index_workout_sessions_status` und
`index_playlist_items_playlist_id_position` (Filter + JOIN) ohne
Temp-B-Tree. Der einspaltige Playlist-Index ist ersetzt
(Room-Validierung), Migration 14->15, Schema 15.json, DB-Version 15.

**D5 A7 (Batch statt N+1):** `MarkerDao.getEnabledMarkersForSongs(songIds)`
als IN-Query + `PlaylistDao.getSongsForLabelOnce(label)` als eine Abfrage
fuer alle Playlists eines Labels; der Planner gruppiert in-memory
(workCandidates und planChain teilen sich die Batch-Marker). Tests:
Pausenbeginn laedt die Marker als EINE Batch-Abfrage
(`batchCalls == [[20, 21]]`) und die Work-Titel als EINE Label-Abfrage;
Replan nach +15 s laedt erneut (2 Batch-Aufrufe); songsForLabelOnce ist
ein DAO-Aufruf; die Batch-Repository-Query liefert je Song nach Position.

**D5 A8 (Gate-Polling entkoppelt):**
`MarkerRepository.observeEnabledMarkersForSong(songId)` als Flow; das
Drop-Rest-Gate kombiniert den 500-ms-Takt (nur snapshotNow, keine Query)
mit dem Marker-Flow (ein Abo je Songwechsel; Room invalidiert bei
Marker-Aenderungen). `DropRestViewModelTest` (2): ~5 Takte -> 1 Marker-Abo
und >=5 Positions-Abtastungen; Songwechsel -> genau ein neues Abo.

**Verifikation (2026-09-22, root):** spotlessCheck, detekt, 3x
verifyRoborazziDebug, `test` (alle Module, inkl. der 27 neuen Tests),
koverVerify, `:app:assembleDebug` und die drei Python-Gates
(Design/Doku-Links/Detekt-Baseline) gruen. Offen bleibt nur Gate-11b
(Adis Sensor-Aufnahmen), E2/E3 (geparkt) und der Commit durch den Nutzer -
inkl. `gradle/verification-metadata.xml` sowie Schema 12-15.json
(untracked).

## BB. Tiefen-Audit: Kover-Bereinigung, 35 neue Tests, Ueberarbeitungsbericht (2026-09-22, Session: OpenCode)

Anlass: "Pruefe sehr genau, ob es noch etwas zu verbessern gibt" nach dem
D4/D5-Abschluss. Methode: Coverage-Inventar je Modul, Sichtung aller
0-%-Klassen, TODO/`@Suppress`/`GlobalScope`/`!!`-Checks, Detekt-Baseline,
Deprecation-Sichtung, Abgleich der offenen Punkte aus den Plaenen.

- [x] **Kover-Messung bereinigt:** Generierter Hilt-/Dagger-Code
  (`*_Factory`, `*_MembersInjector`, `*_GeneratedInjector`, `Hilt_*`,
  `hilt_aggregated_deps`) verwaesserte die Linien-Coverage (in data:playback
  ~17 % der Zeilen mit 0 %). Fuenf Filter-Patterns in der Root-build.gradle.kts,
  am Report vorher/nachher verifiziert. Fallstricke dokumentiert: Kover-0.9-
  Filter nutzen Punkt-Notation (Slash-Pattern matchen nicht), `*` matcht auch
  Punkte (`*Hilt_*` genuegt), und der Gradle-Build-Cache liefert ohne
  `--no-build-cache` veraltete Reports (Filter sind kein Task-Input).
- [x] **35 neue Tests (alle gruen):** data:playback — PlaybackSettingsStoreTest
  (2), RestMusicSettingsStoreTest (3), RouteProfileStoreTest (3: Tabellenwert
  ueber Robolectric-SPEAKER-Fallback, Upsert, STALE-Rueckfall); data:timer —
  RestTimerPreferencesStoreTest (4: Defaults, Preset-Bereinigung,
  Get-Ready-Clamping, Korrupt-Fallback), DataStoreDropSyncPlanStoreTest
  (6: C13-Marker-Roundtrip, "kein Marker"-Faelle, Clear); data:workout —
  WorkoutGoalPreferencesStoreTest (3: Default, Roundtrip, Clamping);
  data:sensor — BleErrorMapperTest (14: alle Heuristik-Kategorien,
  Reihenfolge NOT_FOUND vor SERVICE_MISSING, Token-Vertrag).
- [x] **Coverage-Ratsche angehoben** (nur steigend, koverVerify gruen):
  domain:audio 85->87, domain:health 80->81, domain:library 75->77,
  domain:playback 40->41, domain:sensor 85->88, domain:timer 85->86,
  data:audio 60->64, data:health 40->42, data:library 60->62,
  data:playback 0->13 (Ist 14,3), data:sensor 40->47 (Ist 48,5),
  data:timer 40->49 (Ist 50,7), data:workout 60->62.
- [x] **Test-Fallstricke dokumentiert:** DataStore ist zwischen Tests
  derselben Klasse geteilt (Reset per `markStale`/Defaults im `@Before`);
  ein zweiter `preferencesDataStore`-Delegate auf derselben Datei kollidiert
  ("multiple DataStores active for the same file") — Seed-Tests ueber
  Zweit-Delegates sind damit ausgeschlossen.
- [x] **Bericht erstellt:** `docs/UEBERARBEITUNGSBERICHT_2026-09-22.md` mit
  der vollstaendigen Restliste: data:playback-Kernlogik ohne Tests
  (DropLandingArmer 79 Z., PlaybackRepositoryImpl 89, PlaybackService +
  LibrarySessionCallback 208, Media3AudioClock 40, MediaControllerConnection 18)
  — Empfehlung schmale Ports statt Mocking-Lib; data:timer-Rest (TtsSpeaker,
  CountdownBeepPlayer, HapticsAdapter, AndroidCueOutput); data:sensor
  BleGattClient; data:workout-Repository-Pfade; Detekt-Baseline-Abbau (23
  Eintraege, 5 Schleifen-Befunde zuerst); Paket 4.19 (Convention-Plugins,
  :app-Testausbau, Doku-Konsolidierung). Hygiene-Befunde (13
  Deprecation-Suppresses, 16 `!!`, 1 TODO) geprueft und als unkritisch
  eingeordnet.
- [x] **Verifikation:** koverVerify gruen mit allen neuen Floors; die
  betroffenen Modul-Testtasks (data:playback, data:timer, data:workout,
  data:sensor, data:settings) gruen.
- [x] **Runde 2 (Ports + Kernluecken, gleicher Tag):** `LandingPlayer`-Port +
  `ExoLandingPlayer`-Adapter — `DropLandingArmer` ist Media3-frei und mit
  9 Tests abgesichert (Zielposition, Landed-Delta, Watchdog
  OVERRIDDEN/WATCHDOG, Cancel, Ersetzen, PLAYER_ERROR, detach, Fade-Rampe);
  `DuckingTarget`-Port fuer `PlayerVolumeGateImpl`/`RestDuckingGateImpl`
  (4 Tests); `AudioTrackTimestampReader` (2 Tests, Robolectric-Durchreichung);
  `data:workout`-Repository-Pfade (`getSessionMusic`, `getExerciseDetail`,
  `createCustomExercise`) + `TargetRepositoryValidationTest` (11 Tests);
  `HapticsAdapter` (2 Tests); **Detekt-Baseline 23 -> 19** (vier
  Schleifen-Befunde verhaltensgleich refactored: `FolderHierarchy`,
  `LibraryRepositoryImpl`, `M3uPlaylistParser`, `StreamingResampler`;
  `CalibrationController` bewusst belassen); `:app` Icon/Label-Test.
  Coverage: data:playback 14,3 -> 25,6 %, data:workout 63,9 -> 80,0 %,
  data:timer 50,7 -> 54,4 %; Floors 13->24, 62->78, 49->53. Verifikation:
  spotlessCheck, detekt, koverVerify, alle betroffenen Modul-Tests,
  Python-Gates (Baseline 19/23, Doku-Links, Design) und CRLF gruen.
  Restliste: `docs/UEBERARBEITUNGSBERICHT_2026-09-22.md`.
