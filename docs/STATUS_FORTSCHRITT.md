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
