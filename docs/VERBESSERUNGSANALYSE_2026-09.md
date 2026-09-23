# Verbesserungsanalyse FlowRep/DropSync

Stand: 2026-09-13. Erstellt aus sechs Codebereichsanalysen (alle Module),
dem Skill `mobile-design` (Touch-Psychologie, Plattform-Android, Performance,
Typografie, Farbe, Navigation, Testing) und externer Recherche
(Android Developers, Material 3, Media3-1.11-Blog, Compose-1.12-Release,
Android-16-Adaptiv-Aenderungen, Health Connect, Google Play).

Kein Code geaendert. Dieses Dokument sammelt Befunde und Vorschlaege; die
Umsetzung braucht eigene Commits je Paket.

---

## 1. Sofort rot: CI faellt bei jedem Lauf

`ci.yml` prueft seit B1, dass
`app/src/release/generated/baselineProfiles/baseline-prof.txt` existiert und
nicht leer ist. Diese Datei ist **nicht im Repo** (`git ls-files
"*baseline-prof.txt"` ist leer, `app/src/release/` existiert nicht, keine
Ignore-Regel versteckt sie). Damit bricht der Build-Job immer am
Baseline-Gate ab, unabhaengig von Tests, Lint und Builds.

Fix, zwei Wege:
1. Profil auf einem Geraet erzeugen (`:benchmarks`, Runbook
   `docs/HARDWARE_TESTPLAN.md` Abschnitt B1) und einchecken. Danach liefert
   das Gate echten Nutzen.
2. Bis dahin das Gate nicht blockierend stellen (`continue-on-error`), sonst
   ist die CI dauerhaft rot und ihre Aussagekraft ist null.

---

## 2. Strategische Befunde aus der Recherche (neu)

### 2.1 Material 3 Expressive ist inzwischen stabil

Die C1-Entscheidung ("kein Expressive-Umstieg, material3 1.4.0 hat nichts
Stabiles") ist ueberholt. Die Release-Seite `compose-material3` zeigt einen
stabilen Stand vom **09.09.2026 mit 1.5.0** (Expressive-Listen, ButtonGroup,
LoadingIndicator); Material selbst kuendigte an, mit Compose 1.5.0 die
Expressive-APIs von experimentell auf stabil zu heben, plus Material-Views
1.14 ist bereits das letzte Views-Release (Material ist Compose-first).

Konsequenz: vor der Umsetzung die Version verifizieren, dann ist der Weg frei
fuer `MaterialExpressiveTheme`, Expressive Motion, `ButtonGroup`,
`LoadingIndicator` und expressive Listen. Das ist eine ADR-Aenderung zu
ADR-0018/C1, keine stille Anpassung.

### 2.2 Compose BOM 2026.06.01 -> 2026.08.00 (Compose 1.12)

Direkt relevante Neuerungen fuer diese App:
- `DeferredAnimatedContent` / `DeferredAnimatedVisibility`: zweistufige
  Uebergaenge mit manueller Steuerung und Velocity-Handoff, genau fuer
  Predictive-Back-Tracking gebaut. Die Bibliothek baut das heute von Hand
  (`LibraryContent.kt`, `graphicsLayer` + `PredictiveBackHandler`).
- `SideEffect(key1, key2)`: bis 90 % schneller als `LaunchedEffect`, rund
  20 % schneller als `DisposableEffect` (Ticker, Analytics-artige Effekte).
- `Modifier.onVisibilityChanged()` ersetzt das deprecated `onFirstVisible()`.
- `Grid` mit benannten Bereichen, `SelectionState`, Test-Synchronisation
  (`hasPendingWork`, `runWithoutImplicitWait`), Startup-Paritaet zu Views,
  Wide Color Gamut/HDR, Mesh-Gradients.
- Bricht nichts: compileSdk 37 und AGP 9.1.1+ sind bereits erfuellt.

### 2.3 Media3 1.11 ist installiert, aber ungenutzt

Die App faehrt Media3 1.11.0 (A2), nutzt die neuen Module aber nicht. Verfuegbar
waeren:
- `media3-ui-compose-material3`: **MiniController** mit Dynamic-Color,
  `Player` mit Slots (`topControls`/`centerControls`/`bottomControls`/
  `errorOverlay`), `rememberCurrentMediaItemState`, `rememberPlaylistState`,
  `rememberErrorState`, `ProgressSlider` (1.10), `PlaybackSpeedState`
  (Fast-Forward per Long-Press).
- **SystemUI Output Switcher**: `CastParams` mit
  `setShowSystemOutputSwitcherOnCastButtonClick(true)` plus
  `MediaRouteButton()`-Composable. Das ist genau der im Ausbauplan C3
  gesuchte Output-Switcher, ohne eigene Routing-UI.
- `MediaSession.Callback.onConnectAsync()` fuer asynchrone
  Autorisierungspruefung; die 1.11-Defaults teilen Session-Daten nicht mehr
  automatisch mit untrusted Controllern.
- Neue Muxer (`OggMuxer`, `WavMuxer`), Kapitel-Extraktion aus m4a/m4b/Matroska
  (Horspiel/Podcast-Kapitel), `PlayerPool` fuer Preloading.

Die Modulregel 3.2/4 (kein media3 in `:feature:*`) bleibt bestehen. Der
saubere Weg ist ein Wrapper `:core:mediaui` mit media3-freier Fassade
(ADR-0020 nennt das bereits als Folgearbeit); die Recherche liefert jetzt den
konkreten Grund, ihn zu bauen.

### 2.4 Android 16/17: Adaptivitaet ist Pflicht, ohne Opt-out

Ab targetSdk 37 ignoriert Android auf grossen Screens (>600 dp) Orientierungs-,
Resizability- und Aspect-Ratio-Sperren **ohne Opt-out** (bei 36 war Opt-out
noch erlaubt). Google Play verlangt API 36 seit 31.08.2026 (Verlaengerung bis
01.11.2026) und API 37 ab August 2027.

Positiv: Die App hat **keine** `screenOrientation`-, `resizeableActivity`- oder
Aspect-Ratio-Eintraege in den Manifesten. Sie ist damit nicht akut gefaehrdet.
Die Empfehlungen der Plattform (maximale Breiten setzen statt strecken, Inhalt
scrollbar halten, Zustand ueber Konfigurationswechsel erhalten) treffen aber
genau die C2-Baustellen: Cover-`widthIn` ist erst teilweise gesetzt, feste
Sheet-Hoehen und fixe dp-Werte bleiben.

### 2.5 Health Connect

Hintergrund-Lesen gibt es nur mit zusaetzlicher, explizit vom Nutzer erteilter
Berechtigung (`READ_HEALTH_DATA_IN_BACKGROUND`). Die App verzichtet bewusst
darauf; das ist eine legitime Entscheidung, sollte aber im UI ehrlich stehen
("Puls nur waehrend die App offen ist"), sonst wirkt der Wert still veraltet.

---

## 3. Technische Befunde je Bereich

### 3.1 Architektur, Build, Persistenz

| Befund | Ort | Wirkung |
|---|---|---|
| Kein `build-logic`: `android{}`-Block ~20x dupliziert, Test-Deps je Modul | alle Modul-`build.gradle.kts` | SDK-/AGP-Upgrade beruehrt 20 Dateien; Drift schon sichtbar (`isIncludeAndroidResources` fehlt in `feature/library`, `feature/player`) |
| App-Version hartkodiert (`0.1.0`, versionCode 1) | `app/build.gradle.kts:19,28-29` | widerspricht der eigenen "Versionen nur im Katalog"-Regel |
| Room-Schemas liegen in `src/test/assets` | `core/database/build.gradle.kts:43` | Schemaversion haengt am Test-Sourceset statt am Build-Vertrag |
| Detekt-Baseline mit 24 Eintraegen, Ziel <15 | `config/detekt/baseline.xml` | Altlast bleibt; Abbau braucht je Anlass einen Composable-Split |
| Lint ohne Konfiguration/Baseline, nur Fehler blockieren | kein `lint.xml` | die 43 bekannten Warnungen sind ungegated und unsichtbar |
| ~15 DataStores ohne `ReplaceFileCorruptionHandler` | `data/*/...*Store.kt` | korrupte Preferences-Datei wirft statt auf Defaults zurueckzufallen |
| App-weite `CoroutineScope`s ohne Cancel-Pfad | `DropSyncApplication.kt:47,50`, `RestMusicCoordinator.kt:65`, u.a. | Prozess-Singletons: vertretbar, aber ohne Lebensende ein Leck-/Testrisiko |
| N+1 auf dem Wiedergabepfad | `RestMusicCoordinator.workCandidates()` / `songsForLabel()` | pro Pausenbeginn eine Query je Song/Playlist |
| `:benchmarks` nicht in `test`/`assemble*` | `benchmarks/build.gradle.kts` | Modul verrottet leise, CI baut nur `compileBenchmarkReleaseKotlin` |
| `:app` ohne `src/test` | `app/` | Navigation, `DropSyncApp`, Onboarding-VM, `MainActivity` ungetestet |
| FTS-Index-Rebuild pro Suche | `LibraryBrowseRepositoryImpl.search()` -> `rebuildSearchIndex()` | O(N) je Tastendruck, wenn die Suche je verdrahtet wird |
| Aggregat `COUNT(DISTINCT album) GROUP BY artist` nicht indexgedeckt | `LibraryBrowseDaos.observeArtists()` | teurer als die uebrigen Aggregate |

### 3.2 Audio und Playback

| Befund | Ort | Wirkung |
|---|---|---|
| Bit-Perfect erreicht den Mixer nie: `setPreferredMixerAttributes` wird nirgends gerufen | `PlaybackService.kt`, `BitPerfectGateway.kt` | der Modus umgeht nur die DSP-Kette, nicht den Systemmixer |
| `floatOutput` hart `false`, unabhaengig von der Konfiguration | `PlaybackService.kt:114` | Hi-Res-Pfad (24/32-Bit -> Float) wird downstream nach int16 gewandelt |
| Crossfade toter Code: 6 gepruefte Kurven, kein Aufrufer, ADR-0007 nicht umgesetzt | `CrossfadeCurves.kt`, `MixPreset.kt` | UI-Regler bewusst ausgegraut, Feature fehlt |
| Listener geht bei Controller-Reconnect verloren | `PlaybackRepositoryImpl.listenerAttached`, `PlayerConnection.kt:33-44` | nach Service-Neustart friert `state` ein |
| `AudioSessionId` nur einmal gelesen/gebroadcastet | `PlaybackService.kt:149` | MusicFX-Bindung bricht bei Sink-Neuaufbau |
| `EnergyAccumulator.windows` als geboxte `MutableList<Double>` | `TrackAnalysisMath.kt:115` | bis ~600 KB Boxen je 10-Min-Track bei `FULL` |
| Kein Test fuer `AudioPipeline` (Ducking-Mutex/Rampen) | `data/playback` | B-AUD-3-Fix nur "durch Konstruktion" belegt |
| ReplayGain/Loudness nur Analyse, nie angewandt; True-Peak ohne Oversampling | `DuckingMixer.kt:52-57` | Funktion liegt brach |
| Resampler-Hot-Loop mit Bounds-Checks je Tap | `StreamingResampler.kt:119-146` | CPU im Audiothread bei hohen Zielraten |
| `setEnableAudioTrackPlaybackParams` deprecated | `DspRenderersFactory.kt:44` | Aufraeumen (B-AUD-8) |
| DSD/DoP nur Downconvert | ADR-0009 | Folgeausbau |

### 3.3 Training, Sensor, Fortschritt

| Befund | Ort | Wirkung |
|---|---|---|
| **Dezimalkomma blockiert das Loggen**: `toDoubleOrNull()` scheitert bei "92,5", `canLog` wird false, Button bleibt stumm | `TrainViewModel.canLog/logSet`, `TrainScreen.WeightInput` | deutscher Nutzer kann keinen krummen Satz eintragen; Loesung existiert im Bibliothekspfad (`WorkoutMath.roundKgInputToMilliKg`) |
| Gewichtsschritt ±2.5 kg per String-Arithmetik | `TrainViewModel.adjustWeight` | Artefakte wie `22.499999999999996`; keine Scheibenlogik, kein 1.25-kg-Schritt, keine Einheit kg/lb |
| Drop-Auto-Schalter ist reiner View-State, `DropRestRequestBus` hat keinen Produzenten | `TrainScreen.kt`, `DropRestRequestBus.kt` | "Workout fordert DropSync-Rest an" funktioniert nicht |
| Session-/Cluster-API (Routinen, Supersaetze, "Letzte Session", Uebungstausch) ohne UI | `WorkoutRepository`, `routineDao` | Tabellen und Tests existieren, der Nutzer sieht nichts |
| PR-Logik dreifach: Dashboard-Volumenbestwert, Train-Max-Volumen, echte `PrCalculator`-PRs ohne UI | `ProgressFeedUiState`, `FlatSetRepository`, `training-core/PrCalculator` | die fachlich richtigen PRs sind unerreichbar |
| Waveform publiziert bei ~50 Hz ein neues `FloatArray` | `TrainViewModel.pushWaveformSample` | Recomposition des gesamten Sensor-Panels; Ringpuffer + frame-synchrone Invalidierung waere billiger |
| Zwei Sample-Collector auf demselben Flow, `tryEmit` verwirft still | `TrainViewModel`, `ActiveSetController`, `BleSensorProvider` | verlorene Samples erscheinen nirgends in der Sensor-Health |
| "Alle Saetze"-Route ohne Limit | `AllSetsUiState.from`, `AllSetsScreen` | mappt/rendert die gesamte Historie |
| 3 DB-Roundtrips je geloggtem Satz | `TrainViewModel.logSet` | Last/LastMax/Recent einzeln |
| Fehlerpfade stumm (Log-Failure, loadLastSet, createExercise) | `TrainViewModel` | keine Snackbar, kein Retry im Train-Tab |
| Live-Count-Start verweigert stumm (kein Profil/STREAMING/Device) | `TrainViewModel.startCountedSet` | Button ist nur disabled, ohne Grund |
| Geraeteabnahme Gate 11b offen | Doku | jede Genauigkeitsaussage ist synthetisch |

### 3.4 Bibliothek, Timer, Einstellungen, Health

| Befund | Ort | Wirkung |
|---|---|---|
| FTS-Suche nicht verdrahtet, UI filtert in-memory ueber `rawSongs` | `LibraryViewModel.searchResults` (ungenutzt), `CategoryScreens.SongCategoryScreen` | Suche skaliert nicht und nutzt den Index nicht |
| Kein Paging, komplette Listen in `stateIn`-Flows | `LibraryViewModel` | 10k+ Titel liegen vollstaendig im Speicher |
| Hardcodierte deutsche Strings | `LibraryHomeScreen.kt` ("Fuer jetzt", "Bibliothek", "Noch keine Titel", "Training und Pausen") | englische Geraete sehen Deutsch |
| SAF-Ordnerscan und M3U-Import ohne UI-Einstieg | `LibraryRepository.scanFolder`, `importM3uPlaylist` | fertig und getestet, aber unerreichbar |
| Queue-Verwaltung fehlt in der Bibliothek | `CategoryScreens.QueueCategoryScreen` | nur Abspielen, kein Entfernen/Umsortieren, obwohl Repo es kann |
| Playlist-Duplikate erlaubt, Fehler unsichtbar | `LibraryBrowseRepositoryImpl.addToPlaylist`, `LibraryViewModel.createPlaylist` | gleicher Titel mehrfach; Dialog schliesst ohne Meldung bei Namenskonflikt |
| Import-Verstoesse nur als Zahl | `SettingsScreen.ImportResultText` | `rejectedViolations` liegen vor, werden nicht gezeigt |
| Timer schreibt ~5x pro Sekunde in DataStore | `TimerService.startTicking` (`persistSnapshot` je 200-ms-Tick) | unnoetige Disk-Last; nur bei Zustands-/Sekundenwechsel noetig |
| Standalone-Timer ignoriert die Nutzer-Presets | `TimerSection.REST_PRESETS_SECONDS` hart 60/90/120/180 | Einstellungen wirken nicht im Timer |
| POST_NOTIFICATIONS nur im Train-Tab angefragt | `TrainScreen.kt:168` | wer den Timer ueber Einstellungen oeffnet, bekommt still keine Notification |
| Zwei Evaluate-Schleifen parallel (Service 200 ms, VM 250 ms) | `TimerService`, `TimerViewModel` | doppelte Arbeit im Vordergrund |
| Health: nur HF, kein Write-back, generische Fehler, kein Resume-Refresh | `HealthConnectHeartRateSource` | Puls kann still veralten, Training wandert nicht nach Health Connect |
| Onboarding: keine Zurueck-Navigation, Indikatoren ohne Semantik, Medienberechtigung fehlt im Text, nicht erneut aufrufbar | `app/OnboardingScreen.kt` | TalkBack-Nutzer erhalten keine Seitenposition |

---

## 4. UI/UX-Befunde (mobile-design-Raster angewandt)

Angewandte Prinzipien: Fitts' Law (48 dp, 44 px WCAG 2.5.8), Thumb-Zone,
Discoverability von Gesten (immer sichtbare Alternative), Reduced Motion,
Dynamic Type bis 200 %, Kontrast AA, kein reines Farb-Signal, OLED-Dark,
canonical layouts ab 600/840 dp.

### 4.1 Hoher Impact

1. **Reduced Motion fast ueberall ignoriert.** Nur das Dashboard wertet
   `ANIMATOR_DURATION_SCALE` aus. Betroffen sind Navigation-Pill-Spring und
   Icon-Pop, Now-Playing-Slide plus Dismiss, Library-Fades,
   Waveform-Einblendung, `CountUpText`, Press-Scale. Zentrale
   `LocalReducedMotion` einfuehren und alle Federn darauf umstellen.
2. **A-Z-Schnellscroller ist unbedienbar.** Spalte 24 dp breit, Treffer je
   Buchstabe ~16 dp, ohne Semantik (`LibraryLists.kt:346-382`). Klarer
   Fitts-Verstoss; Alternative: 48-dp-Treffer plus Sprungliste, oder Scroller
   nur in der Songliste durch eine Such-/Filterleiste ersetzen.
3. **Custom-EQ-Slider ohne TalkBack-Bedienung.** `VerticalBandSlider` hat nur
   eine Beschreibung, keine `Role.Slider` und keine `setProgress`-Aktion
   (`QuickEqSheet.kt:112-213`). Disabled wird nur ueber Farbe gezeigt
   (verstoesst gegen "nie Farbe allein").
4. **Hartcodierte deutsche Texte** in `LibraryHomeScreen.kt` (siehe 3.4) und
   im `TempoSheet` ("Tempo ..."), inklusive `contentDescription`.
5. **Drei konkurrierende Kartenradien**: `Spacing.radiusMedium` = 16
   (`FlowRepSurface`), `Spacing.radiusCard` = 20 (MiniPlayer, NowPlayingCard),
   `shapes.medium` = 24 (BrandCard). Kein Token-Konsens, sichtbar uneinheitlich.
6. **Fuenf Top-Bar-Muster** nebeneinander: gar keine (Settings), Material3
   `TopAppBar` (Timer/Audio), eigene `CategoryHeader` (Library/Train),
   `FlowRepIconButton` + ArrowBack (AllSets/Calibration), `TextButton` "Back"
   (ExerciseLibrary). Vereinheitlichen.

### 4.2 Mittlerer Impact

7. **Leer-, Lade- und Fehlerzustaende uneinheitlich oder fehlend.**
   `ExerciseLibraryScreen` und `AllSetsScreen` haben keine Zustaende;
   Library zeigt drei verschiedene Leer-Muster; Import/Export nur als
   Textzeile. Ein `FlowRepEmptyState`/`FlowRepErrorState` im Designsystem
   wuerde alle Screens angleichen.
8. **Bottom-Nav-Label 11 sp, einzeilig ohne Ellipsis** (`DropSyncApp.kt:501`).
   Bei 200 % Schrift oder schmalen Geraeten droht Abschnitt.
9. **Feste Sheet-/Dialoghoehen** (360/400/480 dp) ohne `fontScale`-Adaption.
10. **App-Shell bleibt auf `now_playing` sichtbar** (nur Mini-Player wird
    ausgeblendet): die Glas-Pille konkurriert mit den Transportelementen.
    Bewusst so kommentiert, UX-seitig aber fragwuerdig.
11. **Feste `sp`-Literale** am Player-Zeitstempel (15 sp) und in der
    Chart-Wert-Pille (12 sp) umgehen die Typo-Skala.
12. **Feste Menue-Verschiebung** `DpOffset(x = -136.dp)` in
    `LibraryHomeScreen.kt:122` bricht bei laengeren Labels/anderer Sprache.
13. **Zwei Layout-Bugs**: `Spacer(Modifier.width(8.dp))` innerhalb einer
    `Column` (`LibraryLists.kt:297,405`) hat keine Wirkung.
14. **Orphaned Komponenten**: `DropRestCard`, `FlowRepMetricCard`, `BrandCard`,
    `BrandButtonSecondary`, `LineChart`, `MiniWaveform` sind gebaut und
    getestet, aber in keiner Route verdrahtet.
15. **Haptik**: `HapticsAdapter` existiert fuer Timer-Cues. Fuer Satz-Logging,
    Satz-Abbruch und Umsortieren ist kein Feedback verdrahtet, obwohl das
    Touch-Raster es fuer bestaetigende Aktionen vorsieht.

### 4.3 Positiv (bewahren)

- Semantik ist ernst genommen: lokalisierte `stateDescription`, Live-Regions
  am Rep-Zaehler und Plausibilitaetshinweis, gemergte Satzzeilen,
  `progressBarRangeInfo` an der Waveform.
- Kontrastarbeit dokumentiert: Waveform-Rest auf `onSurfaceVariant`,
  Kategoriefarben im Light-Mode abgedunkelt, Akzent per `ensureContrast` auf
  3:1 geprueft.
- Umsortieren ueber sichtbare Pfeil-Buttons statt Drag-and-drop (bewusst
  barrierefrei), Undo statt Bestaetigungsdialoge.
- 48-dp-Ziele an den meisten Stellen, Zurueck-Affordance im Player sichtbar
  statt nur Geste.
- Edge-to-edge mit themengekoppelten Systembar-Icons.

---

## 5. Vorschlag: Pakete in Reihenfolge

### Paket 0, sofort (halber Tag)
1. CI-Baseline-Gate entschaerfen oder Profil erzeugen (Abschnitt 1).
2. Dezimalkomma-Bug im Train-Tab fixen, mit Gegenbeweis-Test.
3. Reduced-Motion-Local einziehen und die fuenf Hauptanimationsorte umstellen.

### Paket 1, Woche: Release und Verlaesslichkeit
4. `signingConfig` (B-SEC-2), `verification-metadata.xml` (B-SEC-3),
   Lizenzabgleich Poppins/Raleway (B-SEC-4).
5. Lint-Konfiguration mit `warningsAsErrors` fuer reparierte Regeln.
6. `:app`-Unit-Tests fuer Navigation/Onboarding plus
   `:benchmarks:assembleBenchmarkRelease` in der CI.
7. DataStore-Korruptionshandler zentral, `PlaybackRepositoryImpl`-Listener
   beim Reconnect erneuern, `AudioSessionId`-Listener.

### Paket 2, Woche: Audio-Versprechen einloesen
8. Bit-Perfect verdrahten: `setPreferredMixerAttributes`/
   `clearPreferredMixerAttributes` fuer USB (>= API 34), `floatOutput` aus der
   Konfiguration speisen, Service-Neustart bei Wechsel, UI-Text an den echten
   Zustand koppeln.
9. Crossfade fertigstellen (Kurven existieren, Dual-Player fehlt) oder die
   toten Kurven und Regler entfernen.
10. ReplayGain als Option scharf schalten (Analyse liegt vor, inkl.
    True-Peak-Oversampling nachziehen).
11. `:core:mediaui`-Wrapper mit `media3-ui-compose-material3` (MiniController,
    ProgressSlider, `rememberPlaylistState`/`rememberErrorState`) plus
    `MediaRouteButton`/`CastParams` fuer den System-Output-Switcher; ADR-0020
    fortschreiben.

### Paket 3, Woche: Bibliothek und Training greifbar machen
12. Suche verdrahten (FTS), Index beim Scan pflegen statt je Query; Paging
    fuer grosse Bibliotheken.
13. Queue-Verwaltung in der Bibliothek, Playlist-Duplikatwarnung,
    Import-Fehlerdetails, SAF-/M3U-Einstieg.
14. Trainerlebnis: `canLog` beobachtbar plus Fehlertext, Gewichtsschritt
    sauber (1.25/2.5, Dezimalpunkt-Normalisierung), Session-/Routinen-UI
    zurueckholen (Routinen, letzte Session, Supersaetze), echte PRs an
    Dashboard binden, Drop-Auto verdrahten oder entfernen.
15. Timer: Presets aus den Einstellungen nutzen, Persistenz drosseln,
    Permission-Anfrage auch in der Timer-Route.

### Paket 4, Modernisierung
16. Compose BOM auf 2026.08.00; Predictive Back auf `DeferredAnimatedContent`
    umstellen; `SideEffect` mit Keys fuer Ticker.
17. Material 3 Expressive (1.5.0 verifizieren): Theme, Motion, ButtonGroup,
    LoadingIndicator, expressive Listen. Neue ADR statt stiller Umstellung.
18. Designsystem aufraeumen: ein Radius-Token-Satz, ein App-Bar-Muster,
    `FlowRepEmptyState`/`FlowRepErrorState`, orphaned Komponenten verdrahten
    oder entfernen.
19. build-logic-Convention-Plugins, Detekt-Baseline-Abbau, Doku-Konsolidierung
    (B-DOC-2/3/6, HARDWARE_TESTPLAN-Widerspruch), `:app`-Testausbau.

---

## 6. Kennzahlen zur Erfolgsmessung

- CI: Baseline-Gate gruen mit echter Datei; Emulator-Job irgendwann ohne
  `continue-on-error`; Instrumentierung mindestens 10 % der Testfaelle.
- Tests: `:app` > 0; `:feature:library`-ViewModel-Test vorhanden;
  Roborazzi-Screenshots fuer die fuenf groessten Screens je Breakpoint.
- Detekt-Baseline < 15 Eintraege; Lint-Warnungen = 0 mit `warningsAsErrors`.
- Startup: p50/p90 aus `StartupBenchmark` mit eingechecktem Profil.
- A11y: 200-%-Lauf ohne Clipping, TalkBack-Durchlauf fuer Player, Timer,
  Charts, QuickEQ und A-Z-Scroller.
- Audio: Bit-Perfect nachweisbar (Mixer-Attribute gesetzt), Crossfade hoerbar
  oder entfernt.
