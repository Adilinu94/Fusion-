# Bauplan Verbesserungen — Tranchen A-D (2026-09-20)

**Status:** Entscheidungen beantwortet (Abschnitt 5); Umsetzung offen. Kein Commit.
**Grundlage:** `VERBESSERUNGSPLAN_MUSIC_DROPSYNC_REPCOUNT.md` (P2-15..27, P3-28..34),
`UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md` (v. a. Abschnitt 30 "Definition of Done"),
fuenf Code-Audits vom 20.09.2026 (Train, Player, Sensor/Data, App-UI/A11y, Infrastruktur)
auf dem Arbeitsbaum nach P2-21.

**Vertiefung:** Die schwierigen Pakete (Signalmathematik, Schema, Nebenlaeufigkeit,
Zustandsmaschinen, Datenmodell) sind in
`BAUPLAN_TIEFENDOKUMENTATION_2026-09-20.md` umsetzungsreif dokumentiert
(Ist-Zustand mit Datei:Zeile, Fallstricke, Aenderungspunkte mit Signaturen,
Testplaene, offene Fragen).

**Lesehinweis:** Zeilennummern beziehen sich auf den Arbeitsbaum vom 20.09.2026
(P0 bis P2-21 sind uncommittet) und koennen sich mit dem naechsten Commit verschieben.
Die Audits waren rein lesend; die kritischsten Befunde (Sensor-Health, Light-Kontrast,
CI-Screenshot-Gate, Peak-Blitz-Heuristik) wurden am Code gegengeprueft.

---

## 1. Ist-Stand

### 1.1 Fertig (bewusst nicht erneut planen)

- **P0:** Drop-Auto wirksam (Koordinator), Recorder-Gate (ADR-0023), sichtbare
  Start-Gruende/0-Ergebnis, `+15 s` nur bei REST.
- **P1:** `DropSyncCoordinator` + Zustandsmodell + PlayerMessage-Landung, Landung
  Stufe 1 (Mikro-Rampe/Crossfade verdrahtet), DropRest am Foreground-Service,
  Restzeit-Aenderungen/Resume, Rest-Console-Hero, Mini-Player-Badge + Next-Sperre,
  Zaehlpipeline off-main + Sample-Fan-out + Waveform-Drosselung, Lernpfad off-main
  mit sichtbarem Ereignis (RC-6 erledigt), Drop-Auto persistiert, alle Marker als
  Kandidaten.
- **P2:** P2-17 Diagnose-Panel/Satz-Report, P2-18 Live-vs-Replay + Korpus-Gate,
  P2-19 Kalibrier-Wizard 2.0, P2-20 Train-Konsole (Rep-Hero/Quelle/Kopfzeile),
  P2-21 Now-Playing als Drop-Editor (Marker-Sheet, Legende, Ziel, Statuszeile).
- **Solide laut Audit:** Migrationen 1..11 inkl. Rollback getestet, Room-Indizes mit
  Nutzungsnachweis, Architekturregeln per Test durchgesetzt, Release-R8/Signierung,
  Reduced Motion zentral, Dark-Mode-Kontrast-Snapshot, Queue-/Marker-Undo,
  Timer-A11y (`stateDescription`), Progress-Empty-State, Settings-progressive
  Offenlegung, Configuration-Cache/parallel/caching.

### 1.2 Offen aus dem bestehenden Plan (Mapping auf diesen Bauplan)

| Bestehend | Inhalt | Neues Paket |
|---|---|---|
| P2-22 | Peak-Blitz an Engine-Events | C14 |
| P2-23 | Ermuedungsdrift-Modell | B1 |
| P2-24 | Downbeat-Offset | B4 |
| P2-25 | Autokorrelation als Quelle | B2 |
| P2-26 | Schwellen pro Uebung (v6) | B3 |
| P2-27 | Crossfade Stufe 2 | E2 (ADR zuerst) |
| P3-28 | DROPSYNC-Recovery | C13 |
| P3-29 | Geraetetaste | E3 (optional) |
| P3-30 | Satz-Undo + Haptik | A1 |
| P3-31 | `markRunning`-Vertrag | A9 |
| P3-32 | Fallback-Doku (MP-16) | A9 |
| P3-33 | ADR-0012/DOC-2 | A9 |
| P3-34 | DBA-Template | B6 |
| P2-15/16 | Gate-11b + Accel-Konstanten | E1 (blockiert: Adis Aufnahmen) |
| MP-8 | N+1 auf dem Wiedergabepfad | D5 |
| MP-9 | Ducking-Level am Ort | C3 |

---

## 2. Befundregister (Audit 20.09.2026)

### 2.1 Train/Workout (T)

| ID | Befund | Schwere | Beleg | Aufwand |
|---|---|---|---|---|
| T-1 | Satz-Loggen ohne Undo, Haptik, Erfolgsbestaetigung; `workout_undo` ungenutzt | hoch | `TrainScreen.kt:1372-1378`, `TrainViewModel.kt:338-399`, `values/strings.xml:16` | M |
| T-2 | Train speist `personal_records` nie; PR nur aus Session-Clustern (`completeCluster` ohne Produktionsaufrufer); keine PR-Feier im Train | hoch | `FlatSetRepositoryImpl.kt:46-81`, `WorkoutRepositoryImpl.kt:206-268,712-725`, `ProgressDashboardScreen.kt:124,267-278`, `TrainScreen.kt:1394-1402` | L |
| T-3 | Rest-Praeferenz-Dialog + "Rest 90 s · DropSync [Aendern]" fehlen; `restMode` toter Pfad; `setRestPref` ohne Aufrufer | mittel | `values/strings.xml:32-39`, `TrainViewModel.kt:178-179,276-284`, `WorkoutRepositoryImpl.kt:518` | M |
| T-4 | Shadow-Recorder endet bei "Uebung abschliessen"/Disconnect dauerhaft | mittel | `TrainViewModel.kt:227,646,786,263-274`, `JsonlShadowSessionRecorder.kt:52-57` | S |
| T-5 | JSONL-Rohdaten werden synchron auf dem Main-Thread geschrieben | mittel | `TrainViewModel.kt:375-387`, `JsonlShadowSessionRecorder.kt:59-89` | S |
| T-6 | GO-Overlay nicht antippbar, schluckt keine Touches, kann haengen bleiben (Delay ohne `finally`) | mittel | `TrainScreen.kt:688-696,852-873` | S |
| T-7 | Rest-Konsole ohne Pause/Resume-Button; COMPLETED nicht unterscheidbar | mittel | `TrainScreen.kt:792-850,252-254` | S |
| T-8 | TalkBack: Restzeit ohne `stateDescription`, DropSync-Zeile kein Satz | mittel | `TrainScreen.kt:768-784,754-766,982-1028` | S |
| T-9 | Kalibrier-Wizard: "1/4..4/4" vs. 5 Schritte; FAILED zeigt Schritt 1; kein Ausweg bei fehlendem Chip; Rep-Stufe ohne `liveRegion` | mittel | `CalibrationWizardScreen.kt:203-208,248,118-126,360-382`, `strings.xml:203-207,224` | S |
| T-10 | Stille Fehlschlaege: `createExercise`, Profil-Load (Failure = "nicht kalibriert"), Lern-Save nur `Log.w`, Event-Puffer `tryEmit` (1/4) | mittel | `TrainViewModel.kt:550-555,479-488,908-910,412-430` | M |
| T-11 | Keine Compose-UI-Tests fuer die Train-Konsole (DoD offen) | mittel | `feature/workout/src` ohne `androidTest` | M |
| T-12 | 65 ungenutzte Strings im Workout-Modul (u. a. `workout_rest_*`, `detail_prs_title`) | niedrig | `feature/workout/src/main/res/values/strings.xml` | S |
| T-13 | `markRunning` setzt keine Startzeit (`startedElapsedRealtimeMs` null bei DROPSYNC) | niedrig | `TimerEngine.kt:96-102`, `TimerModels.kt:25` | S |
| T-14 | Peak-Blitz nutzt eigene Heuristik (steigende Flanke) statt Engine-Rep-Events | niedrig | `TrainViewModel.kt:814-816` | S |

### 2.2 Player/Music (P)

| ID | Befund | Schwere | Beleg | Aufwand |
|---|---|---|---|---|
| P-1 | DropRest-Monitor stirbt bei Pause/Resume: keine Projektion, kein Cue, kein COMPLETED | hoch | `DropRestSessionMonitor.kt:59,129-135`, `TimerSection.kt:172-181`, `TimerEngine.kt:197-224` | S |
| P-2 | MP-8: N+1 je Planung (Playlists+Marker) und 500-ms-Gate-Polling im Now-Playing | mittel | `DropSyncPlanner.kt:118-155`, `DropRestViewModel.kt:56-66`, `NowPlayingScreen.kt:798-814` | M |
| P-3 | MP-9: Ducking-Level nur in Settings, nicht am Ort (Player/Rest-Hero) | mittel | `SettingsScreen.kt:934-955`, `RestDuckingGateImpl.kt:22` | S |
| P-4 | Kein DROPSYNC-Recovery/PLAN_LOST; Koordinator-Scope ohne Cancel; rekonstruierter REST setzt Queue neu auf | mittel | `TimerEngine.kt:396-399`, `DropSyncFailureReason.kt:52-64`, `DropSyncCoordinator.kt:134-151` | M |
| P-5 | Fehlerzustaende im Player unsichtbar (`Failed` → null); `setQueue`-Fehler verschluckt | mittel | `DropStatusLine.kt:66-68`, `PlayerViewModel.kt:333-339`, `DropSyncCoordinator.kt:299,303` | S |
| P-6 | Mini-Player: kein "Details" bei Armed, Next hart gesperrt ohne Override+Undo, Badge ohne Countdown | mittel | `MiniPlayer.kt:138-153`, `NowPlayingScreen.kt:405-408`, `DropSyncCoordinator.kt:510-514` | M |
| P-7 | Analyse-Fehler zeigt weiter "Waveform wird analysiert"; kein Retry | mittel | `NowPlayingScreen.kt:1020-1026`, `PlayerViewModel.kt:113-116` | S |
| P-8 | Waveform-A11y ohne Slider-Fallback/Seek-Aktion; Marker nicht per TalkBack erreichbar | mittel | `Waveform.kt:676-691`, `NowPlayingScreen.kt:1004` | M |
| P-9 | DropRestCard: Doppel-Countdown mit Statuszeile; Start-Knopf ohne Wirkung bei laufendem REST | mittel | `NowPlayingScreen.kt:458-472`, `DropRestCard.kt:144-157`, `DropRestViewModel.kt:69-101` | S |
| P-10 | Marker-"Anhoeren" ohne Vorlauf/Play; Review-Liste nicht aus dem Player erreichbar; Review ohne Feedback | niedrig | `MarkerSheet.kt:99-104`, `PlayerViewModel.kt:380-382`, `LibraryHomeScreen.kt:294-337` | M |
| P-11 | Toter Code/Strings: `QuickEqSheet`, `setEq*`/`setCrossfadeEnabled`, `MarkerSnapping` (nur Test), 10 Strings, `ROUTE_CHANGED` nie erzeugt | niedrig | `QuickEqSheet.kt:55`, `PlayerViewModel.kt:215-246`, `MarkerSnapping.kt:12`, `DropSyncState.kt:34` | S |
| P-12 | Testluecken: Mini-Player/Badge, Monitor-Pause, N+1-Zaehlung, keine UI-Tests | niedrig | `feature/player/src/test`, kein `androidTest` | M |

### 2.3 Sensor/Data (S)

| ID | Befund | Schwere | Beleg | Aufwand |
|---|---|---|---|---|
| S-1 | RC-18: gleitender Mittelwert bestraft das Satzende (Adaption ab 3. Rep); Drift im Korpus nicht messbar | hoch | `QualityScorer.kt:23-27,66-72`, `RepCounter.kt:274-294`, `SyntheticCorpus.kt:22-25` | L |
| S-2 | `largestGapMs` monoton: ein 500-ms-Gap macht den Stream dauerhaft UNRELIABLE (Live-Zaehlung tot bis Reconnect) | hoch | `BatchDedupTracker.kt:69`, `BleSensorProvider.kt:648-669`, `ActiveSetController.kt:227-236,172-183` | S |
| S-3 | RC-22: Beat-Snap Gitter ab 0 ms (bis ~234 ms Fehler), Snap-Fenster >= halber Beat, Feature derzeit nicht verdrahtet | mittel | `MarkerSnapping.kt:14,22-32`, `docs/research/2026-09-19-downbeat-offset.md:35-48` | M |
| S-4 | RC-20: DTW-/Quality-Schwellen global; Profil-Schema v5 traegt sie nicht (JSONL/Replay nicht reproduzierbar) | mittel | `ExerciseEngineConfig.kt:18-19`, `TemplateMatcher.kt:50,125`, `SensorModels.kt:126-134`, `CorpusSweepHarness.kt:530-535` | M |
| S-5 | RC-19: Autokorrelation nur Zweitmeinung, nie Quelle fuer `expectedDurationMs`; `round` widerspricht Kommentar | mittel | `RepCountPlausibility.kt:72-116,104-106`, `ActiveSetController.kt:254,328` | M |
| S-6 | `stop()`/`finishAndTakeTrace()` rechnen auf Main und lesen neben dem Worker (Race) | mittel | `ActiveSetController.kt:252-262,318-351,358-369`, `TrainViewModel.kt:859-860` | M |
| S-7 | Stille Fehler: Profil-Load-Failure = "nicht kalibriert"; `refine == null` stumm; Poll-`runCatching` ohne Zaehler | mittel | `TrainViewModel.kt:908-910,469-472`, `BleSensorProvider.kt:570` | S |
| S-8 | N+1/Indizes: Shuffle (1 Query je Song), Playlist-Renumber (1 UPDATE je Item), Onset-Ersetzung ohne Transaktion, `song_markers.source` unindiziert | mittel | `LibraryBrowseRepositoryImpl.kt:123-132,311-317,341-345`, `TrackAnalysisRepositoryImpl.kt:357-377`, `LibraryDaos.kt:122-140` | M |
| S-9 | RC-21: Template-Pool bleibt Oder-Gate (FIFO + `maxOf`); grenzwertige Reps wandern mit | niedrig | `TemplateMatcher.kt:76-83,96-101`, `RepCounter.kt:234` | M |
| S-10 | Testluecken: Drift-Korpus, Health-Recovery, v4→v5-Codec, Adaptions-Test, Dispatcher-Assert Lernpfad | mittel | `SyntheticCorpus.kt`, `BatchDedupTrackerTest.kt:70-77`, `DataStoreCalibrationProfileRepositoryTest.kt:255-280` | M |
| S-11 | Toter Code: `SignalProcessor`, `TemplateExtractor`, `readBatteryPercent` | niedrig | `SignalProcessor.kt:15`, `TemplateExtractor.kt:8`, `BleSensorProvider.kt:234` | S |
| S-12 | JSONL-Recorder synchron auf Main (siehe T-5) | niedrig | `JsonlShadowSessionRecorder.kt:59-89` | S |

### 2.4 App/UI/A11y (U)

| ID | Befund | Schwere | Beleg | Aufwand |
|---|---|---|---|---|
| U-1 | Light-Mode: Lime (#DFFF2F) als Text/Icon auf hell ~1,04:1 — Labels/Herzen/Aktionen unsichtbar | hoch | `Theme.kt:68`, `LibraryHomeScreen.kt:275,288`, `ProgressDashboardScreen.kt:840`, `LibraryLists.kt:326,440` | M |
| U-2 | Music Home ohne Work-/Rest-Einstiege und ohne Drop-Abdeckung ("9/12 Drops") | hoch | `LibraryHomeScreen.kt:76-86`, `PlaylistScreens.kt:152-161,353-379` | M |
| U-3 | Marker Review ohne Vorschau/Anhoeren, ohne Feedback/Undo, ohne Empty-State | hoch | `LibraryHomeScreen.kt:188,295-337`, `LibraryViewModel.kt:589-595` | M |
| U-4 | Navigation: MUSIC als Start-Tab statt TRAIN; Label "Optionen" statt "Einstellungen" | mittel | `DropSyncApp.kt:111-114,277`, `values-de/strings.xml:6` | S |
| U-5 | Settings-IA: keine DropSync-Sektion; Sektionsheader uneinheitlich; Duck-Chips laufen bei grosser Schrift ueber | mittel | `SettingsScreen.kt:140,180,212-256,287,948-961` | M |
| U-6 | Progress: klickbare Zeilen unter 48 dp; kein `minimumInteractiveComponentSize` | mittel | `ProgressDashboardScreen.kt:686-707,837-849,1006-1035,1053-1068` | S |
| U-7 | Progress/Verlauf ohne Lade-/Fehlerzustand; `FlowRepErrorState` ungenutzt | mittel | `ProgressDashboardScreen.kt:118-148`, `AllSetsScreen.kt:52-55,109-116`, `FlowRepComponents.kt:188` | M |
| U-8 | A11y: Timer-Wheel-Buttons ohne Einheit, Health-Switch ohne Label, Onboarding-Dots ohne Semantik | mittel | `TimerSection.kt:151-189,280-317`, `SettingsScreen.kt:722-737`, `OnboardingScreen.kt:99-112` | S |
| U-9 | Tote Strings/Methoden: 27 Library-Keys, Settings-/Progress-Reste, `TimerSection.enabled` immer true | niedrig | `feature/library/.../strings.xml`, `SettingsViewModel.kt:342,479`, `TimerSection.kt:288` | S |
| U-10 | Drei eigene Kopfzeilen (`CategoryHeader`, `FlowRepTopBar`, Playlist-Eigenbau) | niedrig | `CategoryScaffold.kt:51-112`, `FlowRepComponents.kt:118-157`, `PlaylistScreens.kt:209-234` | S |
| U-11 | Testluecken: kein `LibraryViewModelTest`; Compose-Tests nur Timer-Wheel/ChartTile | mittel | `feature/library/src/test`, `feature/timer/.../TimerWheelTest.kt` | L |
| U-12 | Theme ohne `primaryContainer`/`onPrimaryContainer`; Light faellt auf M3-Lila zurueck; "Violett = Ziel" nur im Dark Mode | mittel | `Theme.kt:66-110`, `LibraryHomeScreen.kt:249`, `ProgressDashboardScreen.kt:899-911` | M |

### 2.5 Infrastruktur (I)

| ID | Befund | Schwere | Beleg | Aufwand |
|---|---|---|---|---|
| I-1 | Screenshot-Gate kann nie fehlschlagen: `./gradlew test` dumpt die PNGs (Roborazzi-Default `Dump`), danach vergleicht `verifyRoborazziDebug` gegen die frisch geschriebenen Dateien | hoch | `.github/workflows/ci.yml:59-60,97-98`, `core/designsystem/build.gradle.kts:11-13` | S |
| I-2 | P2-18-Korpus-Gate existiert nur im Arbeitsbaum (untracked) — README behauptet "CI-Regressionsgate" | mittel | `README.md:141`, untracked `domain/sensor/.../sweep/*` | S |
| I-3 | `app/lint.xml` und `app/src/test/` untracked, aber im Build referenziert → frischer Clone bricht `lintDebug` | mittel | `app/build.gradle.kts:98-101`, `git status` | S |
| I-4 | Doku-Link-Check blind fuer Root-Markdown und Inline-Code-Pfade; toter Handoff-Verweis (Zieldatei nie eingecheckt) | mittel | `tools/doku_links_check.py:19,40-41`, `docs/design/KI_EMULATOR_TESTSTRATEGIE_2026-08-14.md:109` | S |
| I-5 | Detekt: 8 Regeln global aus (u. a. `MagicNumber`, `ReturnCount`, `TooManyFunctions`), 24 Baseline-Eintraege, kein Wachstums-Gate | mittel | `config/detekt/detekt.yml:12,14,27,29,33,37,42,49`, `baseline.xml` | M |
| I-6 | Keine Coverage-Messung (kein Kover/JaCoCo) | mittel | Build-Dateien | M |
| I-7 | Screenshot-Gate deckt nur 6 Designsysten-Zustaende; keine Feature-Screenshots | niedrig | `ComponentScreenshotsTest.kt:40-106` | M |
| I-8 | Detekt-Quellenableitung scannt `.git` und nur 2 Ebenen tief | niedrig | `build.gradle.kts:52-70` | S |
| I-9 | Supply-Chain: kein Dependabot, keine Wrapper-Validierung, keine Dependency-Verification | niedrig | `.github/`, `gradle/wrapper/gradle-wrapper.properties:3` | S |
| I-10 | Doku-Hygiene: 3 verwaiste Handoffs, kein ADR-Index, 16 Root-Plane ohne Einstieg | niedrig | `docs/handoff_*.md`, `docs/adr/` | M |

### 2.6 Produktanforderungen (geprueft am 21.09.2026)

| ID | Anforderung | Ist-Zustand (Beleg) | Im Plan? |
|---|---|---|---|
| PR-1 | Beim Hinzufuegen von Musik automatisch die Wellenform erzeugen | **Erfuellt:** Scan -> `requestAnalysisForNewSongs` -> aufschiebbar `scheduleFullAnalysis` (`LibraryRepositoryImpl.kt:120`, `DeferredAnalysisScheduler.kt:27-36`); seit A10 ein Volldurchgang (Waveform + Mix + Onsets) | ja (Analyse-Pipeline) |
| PR-2 | Beim Hinzufuegen automatisch die Drops jedes Liedes erkennen (meist 2) | **Erfuellt (A10, 2026-09-21):** Import-Bulk laeuft als `AnalysisProfile.FULL`; Onset-Kandidaten landen als unbestaetigte `AUTO_DETECTED`-Marker in der Review-Liste. Kandidatenzahl Top-3 (`OnsetDetection.DEFAULT_MAX_CANDIDATES = 3`), alle neuen Titel, CPU unveraendert (ein Decode statt zwei). Der manuelle Weg bleibt | ja -> A10 (umgesetzt) |
| PR-3 | Kleiner DropSync-Schalter im Countdown-UI; bei z. B. 3 min sofort planen | **Teilweise:** Schalter liegt im Satz-Hero (`TrainScreen.kt:1380-1392`), nicht im Countdown; der Standalone-Timer (`ROUTE_TIMER`, `TimerScreen`/`TimerSection`) kennt DropSync nicht; Sofort-Planung bei laufender Pause existiert (`DropSyncCoordinator.kt:171-190`, Test vorhanden); Landung exakt auf Pausenende existiert (`DropLanding.kt:63-160`) | **teilweise -> C15** |
| PR-4 | Unter 1 Minute kein DropSync | **Fehlt:** Mindestwerte sind 5 s (`TimerEngine.MIN_DROPSYNC_DURATION_MS = 5_000`, `DropLandingPlanner.MIN_REST_MS = 5_000`) | **nein -> C15** |
| PR-5 | Kette: "wie lange der aktuelle Song spielen kann, wann uebergeleitet wird, zum naechsten Song" | **Fehlt:** Es wird genau EINE Landung geplant (Work-Titel + Marker); die Rest-Playlist laeuft als Fueller bis zum Landungsstart. Keine geplante Ueberleitungskette durch mehrere Titel | **nein -> C16 (entschieden 5.16: Kette)** |

---

## 3. Bauplan

### Tranche A — Sofort (Korrektheit & Quick Wins) — ca. 3-5 Tage

#### A1 — Satz-Undo + Haptik + Erfolgsbestaetigung (T-1; = P3-30)
**Ziel:** "Satz fertig" fuehlt sich bestaetigt an und ist ruecknehmbar.
**Entschieden (20.09.):** Haptik bei "Satz gespeichert" UND beim GO (Landung bleibt
bei der vorhandenen Cue-Haptik). Haptik-Intensitaet: kurzer Impuls (`tick()`, 35 ms).
PR-Kette: **Variante A** — der Flat-Pfad wird PR-faehig (Bestleistungen aus dem
Training), inkl. der noetigen DB-Aenderung (5.7/5.15).
**Umsetzung:** Log-Erfolgspfad (`TrainViewModel.kt:338-399`) emittiert
`SetLogEvent.Logged(setId)` ueber `Channel(BUFFERED)`; Undo ruft eine neue
`WorkoutRepository.deleteSet(setId)` (in `data/workout` ergaenzen, Muster
`completeCluster`-Transaktion, PR/Verlauf neu berechnen); Haptik ueber das
`HapticsAdapter`-Muster (nur wenn `hapticsEnabled`); Snackbar ueber den Shell-Host
(`DropSyncApp.kt:230-236`) mit `workout_set_saved` + `workout_undo`.
**Pflicht:** Log-/Undo-Logik in einen `SetLogController` auslagern — `TrainViewModel`
liegt mit 595 Zeilen 5 unter der LargeClass-Schwelle.
**Tests:** ViewModel (Log → Undo → Satz entfernt, PR neu), Event-Kanal-Test,
Haptik-Port-Fake. **Verifikation:** `:feature:workout`, detekt, assembleDebug.

#### A2 — DropRest-Monitor ueberlebt Pause/Resume (P-1)
**Ziel:** Nach Pause+Resume projiziert der Monitor weiter (Restzeit, Cues, COMPLETED).
**Umsetzung:** In `DropRestSessionMonitor` bei `PAUSED` sauber `stop()` und beim
Resume neu starten, wenn `monitorJob` beendet ist; alternativ Loop pausieren statt
beenden. **Tests:** neuer `DropRestSessionMonitorTest` "Pause/Resume projiziert weiter".

#### A3 — Sensor-Health erholt sich (S-2)
**Ziel:** Ein einzelner 500-ms-Gap sperrt die Live-Zaehlung nicht bis zum Reconnect.
**Umsetzung:** `largestGapMs` im gleitenden Fenster werten oder mit Zeitstempel
abklingen lassen (Muster `recentPacketLossRate` in `BatchDedupTracker`).
**Tests:** "500-ms-Gap, danach 5 s sauber → GOOD"; Regression im `ActiveSetController`
(Abbruch-Schwelle bleibt fuer echte Aussetzer scharf).

#### A4 — Shadow-Recorder: Lebenszyklus + IO (T-4, T-5, S-12)
**Ziel:** Die Messkampagne zeichnet die ganze Sitzung auf und blockiert die UI nicht.
**Umsetzung:** `startSession` in `selectExercise`/`connectSensor` neu aufsetzen
(oder `endSession` nur in `onCleared`); Recorder-API suspend + `withContext(io)`
oder Schreib-Queue mit Flush am Satzende. **Tests:** "zwei Saetze nach
`finishExercise` liegen im JSONL", Writer-Dispatcher-Assert.

#### A5 — GO-Overlay robust (T-6)
**Ziel:** Tap schliesst, keine Touch-Leaks, kein Haengen.
**Umsetzung:** `clickable(onDismiss)`, `showGoOverlay` per `finally`/
`DisposableEffect` zuruecksetzen, `liveRegion`-Semantik. **Tests:** Compose-Test
(Tap schliesst, Zustandswechsel innerhalb 4 s laesst kein Overlay zurueck).

#### A6 — Beschriftung "Einstellungen" (U-4)
**Ziel:** Der letzte Tab heisst "Einstellungen" statt "Optionen".
**Entschieden (20.09.):** Start-Tab bleibt MUSIC, Tab-Reihenfolge unveraendert;
nur das Label wird angepasst (EN ist bereits "Settings").
**Umsetzung:** `app/src/main/res/values-de/strings.xml:6` (`nav_settings`).
**Verifikation:** Screenshot/TalkBack-Stichprobe.

#### A7 — CI-/Gate-Korrekturen (I-1..I-4)
**Ziel:** Gates koennen rot werden; nichts referenziert Untracked.
**Umsetzung:** Screenshot-Gate echt machen (Designsystem-Tests vom `test`-Task
ausnehmen oder `verifyRoborazziDebug` vor `test`; CaptureType explizit setzen);
untrackte Dateien in den Commit (Auftraggeber) — bis dahin README-Formulierung
"Arbeitsbaum"; Doku-Checker auf Root-`*.md` + Inline-Code-Pfade erweitern; toten
Verweis fixen. **Verifikation:** CI-Lauf bzw. lokaler Nachbau der Reihenfolge.

#### A8 — Tote Ressourcen und Code (T-12, P-11, S-11, U-9)
**Ziel:** Keine Strings/Klassen, die entfernte oder offene Features vortaeuschen.
**Umsetzung:** Entfernen oder bewusst verdrahten. **Nicht loeschen:**
`MarkerSnapping` (wird B4 verdrahtet), `FlowRepErrorState` (wird C6 verdrahtet),
`workout_rest_*` (wird C3 verdrahtet). Optional: Unused-Strings-Check in die CI.

#### A9 — Doku-Widersprueche schliessen (T-13, P3-32, P3-33)
**Umsetzung:** `markRunning` — Startzeit setzen ODER KDoc schaerfen; Fusionsdesign
7.1 an ADR-0012/Entscheidung 7 angleichen (Doku); ADR-0012 nachziehen.

#### A10 — Auto-Drop-Erkennung beim Import (PR-2)
**Ziel:** Neue Titel bekommen Waveform **und** Drop-Vorschlaege ohne Nutzeraktion.
**Ist:** Waveform laeuft automatisch (`requestAnalysisForNewSongs`), Onsets nur
per Kontextmenue (`LibraryViewModel.detectDrops`, `AnalysisProfile.FULL`).
**Umsetzung (Optionen):**
- A) Import-Pipeline auf `AnalysisProfile.FULL` umstellen — ein Decode liefert
  Waveform + Onsets + Mix (heute: `WAVEFORM_ONLY` -> `MIX_METADATA` = zwei Decodes).
- B) Nach der Waveform einen aufschiebbaren Onset-Lauf anhaengen
  (`scheduleOnsetDetection`, WorkManager `onset_detection_<songId>`).
**Zu entscheiden:** Kandidatenzahl (heute Top-5; "meist 2" -> z. B. Top-3?),
Scope (nur WORK/REST-Playlist-Titel oder alle), CPU-Budget/Batching.
**Tests:** Scan mit neuen Titeln -> nach Worker-Lauf liegen unbestaetigte
AUTO_DETECTED-Marker vor; `AnalysisProfile`-Wahl; Kandidatenzahl.
**Aufwand:** M.

**Umgesetzt (2026-09-21, Option A + Entscheidungen):** `scheduleFullAnalysis`
statt der Kette (EIN Decode fuer Waveform + Mix + Onsets; der alte Weg kostete
zwei Decodes pro Titel). Kandidatenzahl Top-3. Scope alle neuen Titel (der
Volldurchgang ist billiger als der alte Doppel-Decode; ein Playlist-Filter
waere Kopplung ohne Gewinn). CPU/Batching unveraendert (WorkManager, KEEP).
Marker-Schreiben in `OnsetCandidateWriter` extrahiert. Tests:
`OnsetCandidateWriterTest`, `TrackAnalysisPriorityPathTest` (+3),
`OnsetDetectionTest` (+1). Details: `docs/STATUS_FORTSCHRITT.md` Abschnitt AW.

### Tranche B — Rep-Zaehlqualitaet — ca. 1,5-2 Wochen

**Stand 2026-09-21:** **Tranche B komplett umgesetzt** (B1-B7, Arbeitsbaum,
kein Commit). Details: `docs/STATUS_FORTSCHRITT.md` Abschnitte AX/AY,
ADR-0024 (einseitige Bewertung), ADR-0025 (Beat-Raster-Offset). Bindende
Belege offen: B4-Hoerprobe auf echten Tracks und die Corpus-Messung fuer B1
(Gate 11b).

#### B1 — Ermuedungsdrift-Modell (S-1; = P2-23) — **Erfuellt (Stufe 1)**
**Ziel:** Das Satzende wird nicht mehr systematisch abgewertet; die Qualitaet misst
Form, nicht Naehe zur juengsten Vergangenheit.
**Entschieden (5.13):** Stufe 1 sind **einseitige Scores** (langsamer/kleiner erlaubt,
schneller/groesser verdaechtig); ein Driftmodell entfaellt zunaechst und bleibt als
spaetere Option dokumentiert. Die Refraktaerzeit bleibt am gleitenden Mittelwert.
**Umsetzung:** `QualityScorer.oneSidedScore` + `fatigueTolerance`/`suspiciousTolerance`
in der Config (sweepbar). **Abweichung:** Die Plan-Zahlen 0.45/0.20 sind als Deltas
zur alten 1.0 gelesen (1.45/0.80) — woertlich haetten sie 19 Gates gebrochen und
das Planziel "lockert in der Ermuedungsrichtung" verfehlt (Begruendung in ADR-0024).
**Tests:** `QualityScorerAsymmetryTest`, `RepCounterFatigueDriftTest`; Golden-Corpus
und Sweep halten ihre Baselines. Offen bleibt die Corpus-Messung (Gate 11b) und das
Driftmodell als Stufe 2.

#### B2 — Autokorrelation als Quelle (S-5; = P2-25) — **Erfuellt (Stufe 1)**
**Ziel:** Die theta-unabhaengige Periodenschaetzung verbessert die
Qualitaetsbewertung.
**Entschieden (5.14):** Nur fuer die Bewertung (`expectedDurationMs` des
QualityScorers); die Wiederholungs-Erkennung/Refraktaerzeit bleibt am gleitenden
Mittelwert. `ceil` statt `round` bleibt als kleiner Genauigkeitsfix enthalten.
**Umsetzung:** `qualityDurationMs`-Seed in der Config; `ActiveSetController` uebernimmt
die gemessene Periode fuer den naechsten Satz derselben Uebung/Geraets; `ceil` in
`RepCountPlausibility`; grosse Luecke -> INCONCLUSIVE (`hasLargeGap`).
**Tests:** `QualityDurationSeedTest`, `ActiveSetControllerTest` (+3),
`RepCountPlausibilityTest` (+2). `TrainViewModelLearningEventTest` brauchte eine
eindeutig unplausible Korrektur (6 statt 5), weil `ceil` die Schaetzung naeher an
plausible Eingaben rueckt (Plan-Frage 3).

#### B3 — Schwellen pro Uebung, Profil-Schema v6 (S-4; = P2-26) — **Erfuellt**
**Ziel:** DTW-/Quality-Schwellen je Uebung kalibrierbar und im Replay reproduzierbar.
**Umsetzung:** `templateThreshold`/`minQualityScore`/`dtwBand` in `CalibrationProfile`
(v6, Position 16-18), Codec liest v6/v5/v4 mit exakten Defaults, Validierung;
Controller/Refiner/ViewModel tragen die Werte; Recorder + `CorpusFiles`/Sweep lesen
und replizieren sie. Stufe 1 transportiert (Kalibrierung liefert die Werte noch nicht).
**Tests:** v5-/v4-Blob-Read, v6-Round-Trip, ungueltiges `dtwBand`, JSONL-Round-Trip,
`liveConfig`/baseline replizieren das Fenster.

#### B4 — Beat-Raster-Offset + Snap-Fenster (S-3; = P2-24) — **Erfuellt**
**Ziel:** Beat-Snap rastet auf den gemessenen Raster-Anker, nicht auf ein
0-ms-Gitter; nie gewaltsam.
**Entschieden (5.11/5.12):** Der Snap laeuft **automatisch beim Oeffnen** des
Marker-Sheets, aber nur mit gemessenem Offset (sonst kein Snap); die
Originalposition bleibt fuer "Zurueck auf Original" erhalten, freie Korrektur
danach unveraendert. Der Offset liegt **dauerhaft in der DB**
(Migration **v12->v13**; v12 ist durch A1 verbraucht).
**Umsetzung:** `DownbeatAccumulator` (Low-Band 120 Hz, 10-ms-Huellkurve,
48 Phasen-Bins, Low-Band-Anteil + Konfidenz-Gate an der Leseseite),
`TrackAnalysis.downbeatOffsetMs`/`downbeatConfidence`, Persistenz in
`track_analysis` (+ `MIX_ANALYZER_VERSION` 1->2), `snapToBeat(pos, bpm,
downbeatOffsetMs)` mit `null`-Guard und Fenster `min(250, beatMs/2)`,
Auto-Snap im Sheet. **Abweichung:** 48 statt 4 Phasen (Research) — vier
Bins haetten den Snap um bis zu T/4 (117 ms bei 128 BPM) verschoben;
ADR-0025. **Semantik:** Beat-Phase, kein Taktanfang (Umbauplan 3.2).
**Beleg:** Adis echte Tracks noetig (Hoerprobe); Offline-Tests:
`DownbeatAnalysisTest`, `MarkerSnappingTest`, `MarkerEditStateTest`,
`PlayerViewModelTest`, `TrackAnalysisConfidenceGateTest`, Migrationstest.


#### B5 — Stop/Pause off-main + Race (S-6) — **Erfuellt**
**Ziel:** Autokorrelation/Pufferkopie laufen nicht auf Main und nicht neben dem Worker.
**Umsetzung:** `stop()`/`finishAndTakeTrace()` suspend + `cancelJobsAndJoin()`,
Plausibilitaet auf `workerDispatcher`, Phase IDLE vor dem Join; `abort()` bleibt
bewusst synchron (KDoc). Details: `STATUS_FORTSCHRITT.md` Abschnitt AW.

#### B6 — Template-Pool: DBA statt Oder-Gate (S-9; = P3-34) — **Erfuellt (Variante 1)**
**Umsetzung:** Admission-Margin statt DBA: `TemplateMatcher.addToPool(window,
qualityScore)` nimmt nur Reps ab `minQualityScore + templateAdmissionMargin`
(Default 0.05, Config + Sweep) auf; das Kalibrier-Template bleibt ausgenommen.
DBA (Variante 2) bewusst nicht — Auslaesekriterium ist die DTW-Streuung aus dem
echten Corpus. **Tests:** `pool admission rejects borderline rep`,
`eine schwache Rep fuellt den Pool nicht`.

#### B7 — Fehler- und Lernsichtbarkeit (S-7, T-10) — **Erfuellt**
**Umsetzung:** `TrainErrorEvent` (ExerciseCreationFailed, ProfileLoadFailed,
LearningSaveFailed) als `Channel(BUFFERED)` + Snackbar im Train-Screen;
`ProfileLearningEvent.SkippedNotReproducible` fuer `refine == null`;
Profil-Load-Failure ist nicht mehr "nicht kalibriert"; `SensorHealth.
deviceEventPollErrors` zaehlt die Geraete-Event-Poll-Fehler (Diagnose-Panel),
der stumme `runCatching` ist ersetzt. **Tests:** `TrainViewModelErrorEventTest`
(3 Pfade), `TrainViewModelLearningEventTest` (+1).

### Tranche C — UI/UX & DropSync — ca. 2 Wochen

#### C1 — DropSync-Fehler sichtbar (P-5, P-7)
**Umsetzung:** `Failed`/PLAN_LOST als einmalige Statuszeile im Player; `setQueue`-
Result pruefen und `Failed(PLAYBACK_ERROR)` melden; Analyse-Fehler von Loading
trennen ("Analyse nicht moeglich" + Retry). **Tests:** `DropStatusLine`-Faelle,
ViewModel-Event.

#### C2 — Mini-Player & Skip-Schutz (P-6)
**Entschieden (5.10):** Skip waehrend `Armed` ist nur mit **Doppelbestaetigung**
moeglich: erster Druck zeigt einen Hinweis ("Nochmal druecken zum Ueberspringen"),
zweiter Druck innerhalb des Hinweisfensters loest den Skip aus -> `Overridden` +
Undo-Snackbar. Die heutige harte Sperre entfaellt.
**Umsetzung:** Bei `Armed` Next bleibt sichtbar, aber der erste Druck armiert nur
(State + Hinweis), der zweite bestaetigt; Skip ueber Queue/Kopfhoerer wird weiter
per Zustandserkennung als `Overridden` gemeldet (Undo verfuegbar). Zusaetzlich:
`Details`-Einstieg zum Plan und Badge um Countdown aus `DropStatusLine` ergaenzen.
**Tests:** Badge bei Armed, Doppelbestaetigung (1. Druck = kein Skip, 2. Druck =
Skip + Undo), Compose-Test Next-Verhalten.

#### C3 — Rest-Praeferenz-Dialog + Ducking am Ort (T-3, P-3; = MP-9)
**Entschieden (20.09.):** Ja — Pausendauer (60/90/120/180) und Musikmodus
(normal / Pausen-Playlist / DropSync) werden je Uebung gespeichert.
**Umsetzung:** Kompakte Zeile im SetEntryHero ("Rest 90 s · DropSync") + Dialog
mit Bereitschaftsgrund direkt am Schalter; `setRestPref` verdrahten;
Ducking-Regler in RestConsole/DropRestCard auf dieselbe `DspConfig.restDuckDb`-Quelle.
**Tests:** Dialog-Auswahl persistiert, Ducking-Regler schreibt/liest denselben Wert.

#### C4 — Music Home: Work/Rest + Marker-Review (U-2, U-3)
**Umsetzung:** WORK-/REST-Karten direkt auf Music Home (Filter `PlaylistLabel`),
Abdeckung ("9/12 Drops") + "Noch 3 Marker pruefen" in Liste/Detail, Aktion
"DropSync verwenden"; Review-Zeile mit Anhoeren ab Marker-3 s, Snackbar+Undo nach
Bestaetigen/Verwerfen, Empty-State "Keine Drop-Marker" mit CTA.
**Tests:** LibraryViewModel (Filter, Review-Aktionen), Compose-Test Home.

#### C5 — A11y-Ansagen (T-8, U-8)
**Umsetzung:** `stateDescription` fuer die Restzeit ("Pause laeuft, noch 1 Minute
27 Sekunden"), DropSync-Zeile als Satz (Muster aus P2-21), Wheel-Buttons mit
Einheit/Richtung, Health-Switch-Label, Onboarding "Seite x von 3", Rep-Stufe im
Wizard `liveRegion`. **Tests:** Semantik-Asserts (Compose).

#### C6 — Progress: Lade-/Fehlerzustand + Touch-Ziele (U-6, U-7)
**Umsetzung:** UI-State um Loading/Error erweitern, `FlowRepErrorState` mit Retry
verdrahten; `minimumInteractiveComponentSize()`/`heightIn(min=48.dp)` auf den vier
klickbaren Zeilen. **Tests:** Compose-Test Empty/Error/Retry, Touch-Ziel-Assert.

#### C7 — Settings-IA (U-5)
**Umsetzung:** Sektionsheader vereinheitlichen (Training & Pausen zuerst), neue
DropSync-Sektion (Work-/Rest-Playlist, Auto-Vorschlaege), Duck-Chips als FlowRow.
**Entscheidung:** siehe 5.3.

#### C8 — Theme-Tokens + Light-Kontrast (U-1, U-12)
**Entschieden (20.09.):** Dunkleres Lime fuer Schrift und Symbole im hellen Modus;
Lime bleibt als Fuellfarbe (Fortschritt, Buttons) erhalten.
**Umsetzung:** `primaryContainer`/`onPrimaryContainer` und sekundaere Zielfarbe je
Modus als Tokens; Light-Primary fuer Text/Icons als abgedunkelter Lime-Ton
(Kontrast >= 4,5:1); Kontrast-Snapshot fuer beide Modi.

#### C9 — Waveform-A11y: Slider-Fallback + Marker-Textliste (P-8)
**Umsetzung:** `setProgress`-Semantik auf der Waveform (Seek per TalkBack) und eine
Textliste der Marker (LazyColumn nur fuer Accessibility/Detailmodus, UI-Handbuch 19.4).
**Tests:** Semantik-Assert Seek, Liste enthaelt Marker.

#### C10 — Marker anhoeren mit Vorlauf + Review-Link (P-10)
**Umsetzung:** Anhoeren ab Marker minus 2-3 s inkl. `play()`; Review-Liste aus dem
Player-Overflow verlinken; Bestaetigen/Verwerfen mit Snackbar quittieren.

#### C11 — DropRestCard entdoppeln (P-9)
**Umsetzung:** Statuszeile ausblenden, wenn die Karte sichtbar ist; Start-Button bei
nicht-IDLE-Engine deaktivieren (kein stiller Fehlgriff). **Tests:** Zustandsmatrix.

#### C12 — Kopfzeilen vereinheitlichen (U-10)
**Umsetzung:** Library/Playlist auf `FlowRepTopBar` migrieren, `CategoryHeader` nur
noch als Inhaltskopf darunter.

#### C13 — DROPSYNC-Recovery + PLAN_LOST (P-4; = P3-28)
**Entschieden (5.8/5.9):** Nach Kill/Reboot wird **neu geplant, wenn moeglich**;
sonst zeigt die App einmalig "Plan verloren". Ein **manueller DropRest wird nicht
wiederhergestellt**, aber sichtbar gemeldet (kein stiller Verlust).
**Umsetzung:** Beim Koordinator-Start laufende REST-Sitzung erkennen und neu planen
ODER einmalig "Plan verloren" anzeigen; `DropSyncFailureReason.PLAN_LOST`; Scope mit
`close()`/Cancel fuer Tests; rekonstruierten REST nicht per `setQueue(0)` neu aufsetzen.
**Tests:** Recovery-Szenario mit Fake-Snapshot.

#### C14 — Peak-Blitz an Engine-Events (T-14; = P2-22)
**Umsetzung:** `_lastPeakMs` (Heuristik `TrainViewModel.kt:814-816`) an die
Rep-/Peak-Events des `ActiveSetController` binden (Rep-Event-Kanal existiert bereits
fuer die Live-Zahl); Heuristik entfernen. **Tests:** Blitz nur bei echtem Rep-Event.

#### C15 — DropSync-Schalter im Countdown + 60-s-Minimum (PR-3/PR-4)
**Ziel:** Im Countdown-UI (Rest-Konsole und Standalone-Timer) aktiviert ein kleiner
Schalter DropSync; unter 1 Minute ist DropSync nicht moeglich.
**Ist:** Schalter im Satz-Hero (`TrainScreen.kt:1380-1392`); der Standalone-Timer
(`ROUTE_TIMER`, `TimerScreen`/`TimerSection`) kennt DropSync nicht; Mindestwerte
sind 5 s (`TimerEngine.MIN_DROPSYNC_DURATION_MS`, `DropLandingPlanner.MIN_REST_MS`).
Die Sofort-Planung bei laufender Pause existiert bereits
(`DropSyncCoordinator.kt:171-190`, Test "Drop-Auto waehrend laufender Pause plant
sofort"); die Landung exakt auf dem Pausenende ist der Kern des `DropLandingPlanner`.
**Umsetzung:**
- Schalter in die Rest-Konsole und in den Standalone-Timer: feuert denselben
  `DropRestRequestBus` und persistiert `dropAutoEnabled` (eine Wahrheit).
- 60-s-Minimum: UI (Schalter deaktiviert + Grund direkt am Schalter) UND Domain.
  Vorschlag: eigener `MIN_DROP_AUTO_REST_MS = 60_000` fuer Drop-Auto, damit der
  manuelle DropRest bei 5 s unberuehrt bleibt (Entscheidung).
**Tests:** Schalter im Countdown plant sofort; < 60 s -> Schalter aus + Grund;
>= 60 s -> Planung.
**Aufwand:** M.

#### C16 — Geplante Ueberleitungskette (PR-5; entschieden 5.16, Design 5.17-5.22)
**Ziel:** Fuer die Countdown-Zeit berechnet die App eine Folge von Uebergaengen
("aktueller Song spielt noch X, dann Wechsel zu Y, ...") und landet am Ende auf
dem Drop.
**Abhaengigkeiten:** C13 (Queue nicht per `setQueue(0)` zuruecksetzen),
Crossfade (ADR-0022; v1 mit Stufe 1, Stufe 2 optional), `DropLandingPlanner`
(die letzte Landung bleibt die bestehende Mathematik).
**Entschieden (21.09.2026, s. 5.17-5.22):** Kandidaten aus der **aktuellen
Wiedergabe-Liste**; der laufende Titel darf die Landung sein, wenn sein Drop
passt (kein Wechsel); **max. 2 geplante Songs** nach dem laufenden (Pausen
1-5 Min -> praktisch 2-3 Lieder); Zwischenuebergaenge **auf dem Drop des
Fuellers**, wenn er im Zeitfenster liegt; **Stufe 1** reicht; Anzeige in
**Konsole + Mini-Player**.
**Umsetzung (Entwurf):**
- `:domain:timer` `DropChainPlanner`: `plan(remainingRestMs, currentSong?,
  queue, workDrops, latencyMs, crossfadeMs, minSegmentMs, maxPlannedSongs = 2)`
  -> `DropChain` mit Segmenten (FILLER/LANDING); letztes Segment via
  `DropLandingPlanner`; laufender Titel ist Segment 0 und kann selbst LANDING
  sein.
- Koordinator: Uebergaenge deadline-basiert ausfuehren (Muster Fallback-Landung);
  bei Replan (+15 s/Resume/Drop-Auto) die ganze Kette neu bauen.
- Player-Port: `transitionTo(song, positionMs, fadeMs)` (Stufe 1).
- UI: Kette in der Rest-Konsole und im Mini-Player ("A -> B -> Drop in 1:27").
**Tests:** reine Planner-Tests (fuellt die Zeit, Mindestsegment, max. 2
geplante Songs, landet auf Drop, laufender Titel bleibt als Landung,
Fueller-Wechsel auf Drop, < 60 s -> keine Kette), Koordinator-Test (zwei
Uebergaenge terminiert), Determinismus.
**Details:** `BAUPLAN_TIEFENDOKUMENTATION_2026-09-20.md` Kapitel 14.
**Aufwand:** L.

### Tranche D — Infrastruktur & Belegbarkeit — ca. 1 Woche

#### D1 — Screenshot-Gate + Feature-Screenshots (I-1, I-7) — **Erfuellt (2026-09-22)**
**Umsetzung:** CaptureType explizit (`record` nur lokal, `verify` in CI), Reihenfolge
im Workflow korrigieren; 1-2 Feature-Screens (Train-Konsole, NowPlaying) als
Roborazzi-Tests ins Gate. **Verifikation:** absichtlich geaenderter Screenshot muss
CI rot machen.
**Ergebnis:** Reihenfolge war bereits korrigiert (Gate vor `test`); empirisch
belegt, dass der normale `test`-Task nur nach `build/intermediates/roborazzi`
dumpt und die Referenzen unter `src/test/screenshots` unberuehrt laesst.
Gate erweitert: `:core:designsystem:verifyRoborazziDebug` +
`:feature:workout:verifyRoborazziDebug` + `:feature:player:verifyRoborazziDebug`
(ci.yml). Neue Feature-Screens: Rest-Konsole (normal + C16-Kette) in
`RestConsoleScreenshotsTest`, Now-Playing-Hero (hell + dunkel) in
`NowPlayingScreenshotsTest`; dafuer `RestConsole`, `NowPlayingPalette`,
`PowerampTitleRow`, `PowerampWaveformTransport`, `MarkerLegendSection`,
`DropSyncStatusRow` private -> internal. Verifikation: absichtlich getauschte
Referenz laesst `verifyRoborazziDebug` in beiden Modulen rot werden
(`restKonsoleNormal FAILED` / `buttonsHell FAILED`), zwei unabhaengige
Verify-Laeufe sind deterministisch.

#### D2 — Detekt verschaerfen (I-5) — **Erfuellt (2026-09-22)**
**Umsetzung:** Regeln fuer Produktivcode schrittweise aktivieren (Tests excluden),
CI-Schritt "Baseline-Anzahl <= 24" als Wachstumsbremse. **Risiko:** breite
Fundstellen — in Teilpaketen einfuehren.
**Ergebnis:** Detekt war zu Beginn rot (10 Fundstellen, 3 davon nur Tests).
Tests sind fuer `LargeClass`/`MatchingDeclarationName` ausgenommen
(detekt.yml, begruendet). Produktivcode-Fundstellen behoben: `DropChainPlanner.plan`
in Helfer zerlegt (Komplexitaet 22 -> unter 20, Loop-Befund weg),
`NowPlayingScreen` (Undo-Snackbar + Drop-Status als eigene Funktionen),
`DropSyncCoordinator.onPlaybackState` (Seek-Pruefung extrahiert),
`LibraryViewModel` (C4-Marker-Review als eigener Baustein
`LibraryMarkerReview` — der ViewModel-Body haelt die Schwelle),
`CoverArtLoader`/`TopLevelDestination`/`JacobiResult` in eigene Dateien
(MatchingDeclarationName, alle drei Altlasten weg). Baseline: 25 -> 23
Eintraege (2 neue C16-Koordinator-Eintraege mit Abbau-Vermerk).
Bremse: `tools/detekt_baseline_count.py` (23, CI-Schritt nach `detekt`).
Verifikation: `detekt` gruen; neuer Smell ohne Baseline-Eintrag laesst
`detekt` rot werden; simuliertes Baseline-Wachstum laesst die Bremse rot
werden (Exit 1).

#### D3 — Coverage messen (I-6) — **Erfuellt (2026-09-22)**
**Entschieden (20.09.):** Ja — Kover mit 60-%-Schwelle fuer die Kern-Module
(`domain:*` und `data:*`); UI-Feature-Module zunaechst nur als Report.
**Umsetzung:** Kover im Root anbinden, Report fuer `domain:*`/`data:*` mit
Schwelle 60 %, in CI als Gate (UI-Module informativ).
**Ergebnis:** Kover 0.9.9 im Root angebunden (`coverageFloors`-Ratsche in
`build.gradle.kts`, Plugin nur auf `domain:*`/`data:*`); CI-Schritt
`./gradlew koverVerify` nach den Unit-Tests (Test-Ausfuehrung wird geteilt,
kein Doppellauf). Abweichungen vom Plan, bewusst und begruendet:
(a) **Ratsche statt Einheits-60 %** — die Messung (Kover LINE, 2026-09-22)
zeigte 6 Module unter 60 % (data:playback 4,4; data:health 41,3;
data:timer 42,8; domain:playback 42,9; data:sensor 44,8; data:settings 55,1).
Die Untergrenze je Modul ist der abgerundete Ist-Stand und darf nur steigen;
das 60-%-Ziel steht als Kommentar und fuer Module ueber dem Ziel gilt der
Ist-Stand als Floor (z. B. domain:sensor 85). (b) UI-Feature-Module bleiben
ganz aussen vor (Report ohne Leser waere Aufwand ohne Nutzen).
**Nebenbefund (gefixt):** `:data:library` und `:data:audio` kompilierten ihre
Tests nicht mehr — C4 hatte `MarkerDao.observeSongsWithEnabledMarkers`
ergaenzt, die Test-Fakes (`FakeMarkerDao`, `RecordingMarkerDao`) fehlten.
Beide Fakes nachgezogen (47 + 49 Tests wieder gruen). Verifikation:
`koverVerify` gruen; kuenstlich auf 99 % gesetzte Schwelle laesst
`:domain:timer:koverVerify` rot werden ("Rule violated: lines covered
percentage is 87.835400, but expected minimum is 99").
**Nachtrag 2026-09-22 (Audit):** Die Messung war durch generierten
Hilt-/Dagger-Code verwaessert (`*_Factory`, `*_MembersInjector`,
`*_GeneratedInjector`, `Hilt_*`, `hilt_aggregated_deps` — in `data:playback`
~17 % der Zeilen mit 0 %). Fuenf Kover-Filter-Patterns schliessen ihn aus
(Root-`build.gradle.kts`, am Report vorher/nachher verifiziert; Kover-0.9-
Filter nutzen Punkt-Notation, `*` matcht auch Punkte). Die Ratsche wurde
mit dem bereinigten Stand angehoben (u. a. data:playback 0 -> 13,
data:timer 40 -> 49, data:sensor 40 -> 47); zusaetzlich 35 neue Tests fuer
bisher ungetestete Stores und den BLE-Fehler-Mapper
(`PlaybackSettingsStore`, `RestMusicSettingsStore`, `RouteProfileStore`,
`RestTimerPreferencesStore`, `DataStoreDropSyncPlanStore`,
`WorkoutGoalPreferencesStore`, `BleErrorMapper`). Details und verbleibende
Luecken: `docs/UEBERARBEITUNGSBERICHT_2026-09-22.md`.

#### D4 — Compose-UI-Tests (T-11, P-12, U-11) — **Erfuellt (beide Wellen, 2026-09-22)**
**Umsetzung:** `androidTest`/Compose-Regel fuer Train (IDLE/SET_ENTRY/REST/GO, Undo),
Player (Next/Details, Marker-Sheet), Library (Home/Marker-Review), Settings-Sektionen,
Progress Empty/Error. **Aufwand:** L — in zwei Wellen.
**Ergebnis Welle 1 (10 Tests, alle gruen):** Compose-Tests laufen
Robolectric-basiert in `src/test` (kein Geraet noetig, Muster aus C9).
- feature:workout — `RestConsoleBehaviorTest` (3): normale Pause mit
  Beschriftungen; blockierter Schalter unter 60 s (C15); DROPSYNC-Kette
  nennt die Kette, zeigt kein "+15 SECONDS" und bietet CANCEL PLAN (C16).
- feature:player — `DropStatusRowTest` (4): Ready-Zeile inkl. A11y-Satz
  (C5), Best-Effort, Overridden, PLAN_LOST mit Dismiss (C13).
- feature:library — `LibraryDropSyncSectionTest` (3): Review-Aktionen mit
  stabilen IDs, Karte "9/12 drops" + Use DropSync (C4/C15), Empty-State
  fuehrt zum Detekt-CTA.
Dafuer `MarkerReviewSection`/`DropSyncSection` internal; Compose-Test-Infra
in feature:library nachgezogen (robolectric, ui-test-junit4/-manifest,
isIncludeAndroidResources). Verifikation: die drei Modul-Testtasks gruen,
Root-`test` gruen.
**Welle 2 (21 Tests, alle gruen, 2026-09-22):**
- feature:workout — `SetEntryBehaviorTest` (5): SET_ENTRY nennt Uebung und
  genau eine Primaeraktion ("SET DONE", Klick ruft `onLogSet`), gesperrt
  ohne Eingabe; IDLE zeigt den Platzhalter und bietet bewusst KEINE Aktion;
  die Undo-Snackbar "Set saved" traegt "Rueckgaengig" und ruft den
  Undo-Pfad, nach dem Undo meldet "Set removed" (A1/T-1).
- feature:workout — `RestConsoleBehaviorTest` (2 neu): das GO-Overlay
  erscheint nach der Landung, schliesst per Tap vor dem 4-s-Selbstschluss
  (Messung der Testzeit) und traegt die Klick-Aktion selbst (A5/T-6: kein
  Touch-Leak auf die Knoepfe darunter).
- feature:player — `NowPlayingBehaviorTest` (6): Next bleibt bedienbar und
  ruft den Skip-Pfad (C2/5.10; die alte Idee "Details statt Next" ist
  damit endgueltig vom Tisch), Next ist ohne Folgetitel gesperrt; das
  Badge nennt Plan mit Countdown und ist der Details-Einstieg, nennt die
  Kette (C16) und zeigt OVERRIDDEN/FAILED als Text.
- feature:player — `MarkerSheetBehaviorTest` (5): Vorschlag zeigt
  "Confirm" und die Feinjustierung (+100 ms ruft `onAdjust`), "Back to
  original" nur bei Abweichung, bestaetigter Marker bietet Zielwahl und
  Loeschen, gesetztes Ziel zeigt Chip + Entfernen, die Textliste listet
  alle Marker und springt per Zeile zum Marker (C9).
- feature:settings — `SettingsSectionsTest` (1): alle acht Sektionen
  (Pausen-Musik, DropSync, Design, Audio, Daten, Marker, Datenschutz,
  Entwickler) sind in der LazyColumn erreichbar; echter
  `SettingsViewModel` mit Fakes statt UI-Mock.
- feature:progress — `ProgressEmptyErrorTest` (2): Leerzustand ist
  Onboarding mit Trainings-Knopf; Ladefehler nennt den Grund, Retry baut
  den Flow neu auf (C6/U-7).
Dafuer `SetEntryHero`/`TrainEventSnackbars`/`EmptyConsoleHero`/`GoOverlay`
(workout), `PowerampModeRow`/`DropSyncBadgeChip` (player, plus
`MarkerSheetContent` als fensterloser Sheet-Inhalt) und
`ProgressDashboardContent` (progress) internal; Compose-Test-Infra in
feature:settings nachgezogen (robolectric, ui-test-junit4/-manifest,
2g Heap).
**Hinweis (Test-Fallstrick):** mit `mainClock.autoAdvance = false` wird
nach einem Klick nicht neu komponiert — Klick-Tests laufen mit der
Standard-Uhr und messen die Testzeit, wenn ein Selbstschluss-Timer im
Spiel ist.

#### D5 — N+1/Indizes (P-2, S-8) — **Erfuellt (2026-09-22)**
**Umsetzung:** Work-/Rest-Kandidaten als Flow cachen (Invalidierung bei Playlist-/
Marker-Aenderung) oder kombinierte Query; `getStats(ids)` als IN-Query;
Playlist-Renumber als DELETE+Reinsert; Onset-Ersetzung in `@Transaction`; Indizes
`song_markers(source,is_enabled)` und Workout-Status pruefen (EXPLAIN-Nachweis wie
bei v11). **Tests:** Aufruf-Zaehler ("Marker nur 1x je Song").
**Ergebnis (A1-A8 umgesetzt):**
- **A1:** `PlayStatDao.getStats(ids)` als IN-Query; `shuffleCandidates` laedt
  die Statistiken in EINER Abfrage. Test (echte Room-DB + zaehlender
  Statistik-DAO): `getStatsArgs == [[1,2,3]]`, `getStatArgs` leer.
- **A2:** Playlist-Neunummerierung als DELETE + ein Batch-Insert
  (`renumberPlaylist` in `LibraryBrowseRepositoryImpl`), kein Einzel-UPDATE
  mehr. Tests mit zaehlendem Playlist-DAO fuer `removeFromPlaylist` und
  `moveInPlaylist` (`updateItemPositionArgs` leer).
- **A3:** Onset-Ersetzung in EINER Transaktion
  (`MarkerDao.replacePendingCandidates` mit `@Transaction`) + eine Uhrzeit
  fuer den ganzen Lauf. Tests in `core:database`
  (`MarkerCandidateTransactionTest`): bestaetigte Marker ueberleben, alle
  neuen Kandidaten tragen dieselbe Zeit; PK-Konflikt mitten im Lauf rollt
  DELETE und erste Inserts komplett zurueck.
- **A4:** Index `song_markers(source, is_enabled)`, Migration 13->14,
  Schema 14.json, `DROPSYNC_MIGRATIONS`. EXPLAIN-Test mit Negativkontrolle
  und **Messung**: der Planer nutzt den Index fuer den reinen Filter und fuer
  den Kandidaten-DELETE (heisser Pfad), NICHT fuer die Pending-JOIN-Query
  (die faehrt ueber `index_marker_song_links_marker_id`) — im Test als
  Kommentar dokumentiert, keine Annahme.
- **A5/A6 (umgesetzt):** EXPLAIN-Messung VOR dem Anlegen — der
  Migrationstest misst beide Zustaende auf einer v14-DB (400 Sessions,
  davon 2 ACTIVE; 3 Playlists mit je 200 Positionen): vorher Full-Scan
  bzw. Temp-B-Tree, nachher `index_workout_sessions_status` fuer die
  Session-Suche und `index_playlist_items_playlist_id_position` fuer die
  Playlist-Queries (Filter + JOIN) ohne Temp-B-Tree; Negativkontrolle
  (unindizierte Spalte bleibt SCAN). Der einspaltige Playlist-Index ist
  ersetzt (Room-Validierung), Migration 14->15, Schema 15.json,
  DB-Version 15.
- **A7 (umgesetzt):** `MarkerDao.getEnabledMarkersForSongs(songIds)` als
  IN-Query + `PlaylistDao.getSongsForLabelOnce(label)` als eine Abfrage
  fuer alle Playlists eines Labels; der Planner gruppiert in-memory
  (`workCandidates` und `planChain` teilen sich die Batch-Marker). Tests:
  Pausenbeginn laedt Marker als EINE Batch-Abfrage
  (`batchCalls == [[20, 21]]`) und Work-Titel als EINE Label-Abfrage;
  Replan nach +15 s laedt erneut (2 Batch-Aufrufe); `songsForLabelOnce`
  ist ein DAO-Aufruf; die Batch-Repository-Query liefert je Song nach
  Position (fremde Songs fehlen).
- **A8 (umgesetzt):** `MarkerRepository.observeEnabledMarkersForSong(songId)`
  als Flow; das Drop-Rest-Gate kombiniert den 500-ms-Takt (nur
  `snapshotNow`, keine Query) mit dem Marker-Flow (ein Abo je Songwechsel;
  Room invalidiert bei Marker-Aenderungen). Test: ~5 Takte -> 1 Marker-Abo
  und >=5 Positions-Abtastungen; Songwechsel -> genau ein neues Abo.
- **Offen (bewusst):** keine. Weitere Indizes nur mit neuer Messung.

#### D6 — Supply-Chain (I-9) — **Erfuellt (2026-09-22)**
**Umsetzung:** Dependabot (gradle), `gradle/actions/wrapper-validation`,
Dependency-Verification-Metadaten.
**Ergebnis:** `.github/dependabot.yml` neu (gradle + github-actions, woechentlich,
minor/patch gebuendelt; Kommentar: Gradle-Bumps muessen die Metadaten mit
`--write-verification-metadata sha256` nachziehen). `wrapper-validation@v4` in
beiden CI-Jobs vor dem Build. Wrapper-Distribution gepinnt
(`distributionSha256Sum=553c78f5...b746`, Wert von
`services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256`).
Verifikations-Metadaten sind aktiv (`gradle/verification-metadata.xml`, beim
Kover-Hinzufuegen hat das Gate sofort gegriffen) — die Datei ist im
Arbeitsbaum **noch untracked** und muss mit committet werden, sonst greift
das Gate in der CI nicht.
Nebenbefund: Der D1-Step-Name enthielt `verifyRoborazziDebug:` mit
Doppelpunkt und machte `ci.yml` ungueltiges YAML — korrigiert, Datei jetzt
per `js-yaml` geprueft.

#### D7 — Doku-Struktur (I-10) — **Erfuellt (2026-09-22)**
**Umsetzung:** Handoffs archivieren/verlinken, ein ADR-Index unter `docs/adr/`,
Root-Plane in `docs/plans/` verschieben (Link-Check greift dann), README-Einstieg.
**Ergebnis:** 15 Root-Plaene nach `docs/plans/` verschoben (dort lag schon
`2026-08-09-fusion-foundation-plan.md`), Index `docs/plans/README.md`
(Datei, Titel, Stand). 3 verwaiste Handoffs nach `docs/handoffs/` +
`docs/handoffs/README.md`. Neuer `docs/adr/README.md` (25 ADRs mit Titel,
Datum, Status). README-Abschnitt "Dokumentation" als Einstieg
(plans/adr/STATUS/Architekturregeln/handoffs/Hardware-Testplan). Die
Inline-Pfad-Pruefung in `tools/doku_links_check.py` gilt jetzt auch fuer
`docs/plans/` — sonst haette der Umzug die Pruefung still abgeschwaecht.
Relative Links in `FLOWREP_DESIGN_PLAN.md` (12x `../../`-Praefix) und im
NowPlaying-Handoff (`../design/`, `../qa/`) nachgezogen; `IMPORT_README.md`
bleibt als Provenienz-Nachweis im Root. Link-Check gruen (81 Dateien).

### Blockiert / separat

| ID | Paket | Bedingung |
|---|---|---|
| E1 | Gate-11b-Kampagne + Accel-Konstanten (= P2-15/16) | Adis Sensor-Aufnahmen (5 Szenarien x >= 3 Sessions) |
| E2 | Crossfade Stufe 2 (= P2-27) | ADR + Spike vor Baubeginn (ADR-0022) |
| E3 | Geraetetaste belegen (= P3-29) | **Geparkt (Entscheidung 20.09.):** spaeter entscheiden; kein Aufwand in diesem Plan |

---

## 4. Empfohlene Reihenfolge

1. **Tranche A komplett** (Korrektheit + Quick Wins, keine Abhaengigkeiten).
2. **B1 + B2** (messbare Zaehlqualitaet; B1 zuerst, weil B2/B3 darauf aufsetzen),
   danach **B4-B7**.
3. **C1, C2, C13** (DropSync-Sichtbarkeit/Stabilitaet), danach **C3-C12** nach
   Praeferenz; **C8** (Kontrast) frueh, weil es mehrere Screens betrifft.
4. **Tranche D** parallel zu B/C moeglich (D1/D2/D6 sind klein), D4 nach C.
5. **E1** startet, sobald Adi die Aufnahmen liefert; **E2** erst nach ADR.

**Gesamtaufwand (grob):** A ~0,5-1 Woche, B ~1,5-2 Wochen, C ~2 Wochen,
D ~1 Woche — zusammen ca. 5-6 Wochen konzentriert; unabhhaengig davon bleibt der
Arbeitsbaum bis zum ersten Commit ungesichert (siehe A7/I-2/I-3).

---

## 5. Entscheidungen — beantwortet am 20.09.2026

| # | Frage | Entscheidung | Wirkt in |
|---|---|---|---|
| 5.1 | Start-Tab und Reihenfolge | Start bleibt MUSIC, Reihenfolge unveraendert; nur Label "Optionen" -> "Einstellungen" | A6 |
| 5.2 | Schrift im hellen Modus | Dunkleres Lime fuer Text/Icons; Lime bleibt Fuellfarbe | C8 |
| 5.3 | Pause im Training einstellen | Ja: Dauer (60/90/120/180) + Musikmodus je Uebung, persistiert | C3 |
| 5.4 | Test-Abdeckung messen | Ja: Kover mit 60-%-Schwelle fuer `domain:*`/`data:*` | D3 |
| 5.5 | Geraetetaste | Geparkt: spaeter entscheiden | E3 |
| 5.6 | Haptik-Umfang | Satz speichern + GO | A1 |

Offen bleibt nur die Freigabe der Reihenfolge (Abschnitt 4) und der Start der
Umsetzung.

**Entschieden am 20.09.2026 (Runde 2, Tiefendokumentation):**

| # | Frage | Entscheidung | Wirkt in |
|---|---|---|---|
| 5.7 | Herkunft der Bestleistungen | Aus dem Training: Flat-Pfad wird PR-faehig (Variante A, inkl. DB-Aenderung) | A1/T-2 |
| 5.8 | Verhalten nach Kill/Reboot | Neu planen, wenn moeglich; sonst Meldung "Plan verloren" | C13 |
| 5.9 | Manueller DropRest nach Kill | Nicht wiederherstellen, aber sichtbare Meldung | C13 |
| 5.10 | Skip waehrend Armed | Nur mit Doppelbestaetigung (zweimal "Weiter"), dann `Overridden` + Undo-Snackbar | C2 |
| 5.11 | "Auf Beat einrasten" | Automatisch beim Oeffnen des Marker-Sheets (nur bei erkannter Beat-Position mit Konfidenz); freie Korrektur und "Zurueck auf Original" bleiben | B4 |
| 5.12 | Downbeat-Persistenz | Dauerhaft in der DB (Migration v11->v12) | B4 |
| 5.13 | Satzende-Bewertung | Einseitige Scores (langsamer/kleiner erlaubt, schneller/groesser verdaechtig); kein Driftmodell in Stufe 1 | B1 |
| 5.14 | Gemessene Rep-Zeit | Nur fuer die Qualitaetsbewertung, nicht fuer die Wiederholungs-Erkennung (Refraktaerzeit bleibt Mittelwert) | B2 |
| 5.15 | Haptik beim Satz | Kurzer Impuls (`tick()`, 35 ms) | A1 |

**Entschieden am 21.09.2026 (Produktanforderungen):**

| # | Frage | Entscheidung | Wirkt in |
|---|---|---|---|
| 5.16 | Kette oder Einzellandung? | **Geplante Ueberleitungskette**: die App berechnet fuer die Countdown-Zeit eine Folge von Uebergaengen ("aktueller Song spielt noch X, dann Wechsel zu Y, ...") und landet am Ende auf dem Drop | C16/PR-5 |

**Entschieden am 21.09.2026 (Kette C16):**

| # | Frage | Entscheidung | Wirkt in |
|---|---|---|---|
| 5.17 | Kandidatenquelle der Kette | **Aktuelle Wiedergabe-Liste** (Queue des Players), nicht die Pausen-Playlist | C16 |
| 5.18 | Landung ohne Wechsel | **Ja**: hat der laufende Titel einen Drop im Zielzeitfenster, bleibt er und ist selbst die Landung | C16 |
| 5.19 | Kettenlaenge | **Max. 2 geplante Songs** nach dem laufenden Titel (davon der letzte die Landung); Pausen sind 1-5 Min, dadurch praktisch nie mehr als 2-3 Lieder | C16 |
| 5.20 | Wechselpunkt der Fueller | **Ja, auf den Drop** des Fuellers, wenn er im erlaubten Zeitfenster liegt; sonst Segmentende | C16 |
| 5.21 | Uebergangsart der Kette | **Stufe 1** (Rampen) reicht; Stufe 2 bleibt optionale ADR-0022-Frage | C16 |
| 5.22 | Anzeige der Kette | **Pausen-Konsole + Mini-Player** (nicht Now-Playing) | C16 |

---

## 6. Bewusst nicht in diesem Bauplan

- Signalmathematik-Neubau (Filter/DTW/PhaseValidator) — bleibt unangetastet.
- Gate-11b/Accel-Konstanten (E1) und Crossfade Stufe 2 (E2) — externe Bedingung/ADR.
- Neue Produktfeatures ausserhalb des Handbuchs (Visualizer, Sleep-Timer usw.).
- Commits: Der Arbeitsbaum wird vom Auftraggeber gesichert; dieser Plan setzt den
  Ist-Stand des Arbeitsbaums voraus.
