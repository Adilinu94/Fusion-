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
