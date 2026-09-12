# App-Verbesserung-Ausbau-Plan (DropSync / FlowRep)

Stand: 2026-09-12. Quelle: lesende Tiefenanalysen (Technik + UI, alle P0-Behauptungen
am Code verifiziert) plus Plattformrecherche (Android 16, Media3 1.11.0, M3 Expressive
stabil, Health-Connect-1.1.0, Play-Vorgaben September 2026).

Verwandte Plaene: `VERBESSERUNGSPLAN.md` (Befunde B-SEC/B-DB/B-ARCH/B-UI/B-DOC),
`README.md` Schritte 13/14, `docs/ARCHITEKTURREGELN.md` (verbindlich).

## 1. Ziele (messbar)

1. Schritt 14 wird erreichbar: keine P0-Befunde, `signingConfig` + Lizenzen geklaert.
2. Startup wahrnehmbar schneller (Baseline Profile wirken, vorher/nachher per Macrobenchmark).
3. TalkBack-Nutzer koennen Timer, Player und Charts vollstaendig bedienen.
4. Keine Main-Thread-Blockaden, keine verschluckten Coroutine-Abbrueche, keine
   ungehärteten exportierten Services.
5. Jede Tranche endet gruen: `./gradlew test spotlessCheck lintDebug detekt`,
   `assembleDebug` + `assembleRelease`, `tools/doku_links_check.py`.

## 2. Leitplanken

- Offline-First und ohne Konto bleiben unangetastet (kein Cloud-Sync; T6 loest das
  Transfer-Problem lokal).
- Modulregeln aus `docs/ARCHITEKTURREGELN.md`; Abweichungen nur per ADR in `docs/adr/`.
- Fakes modul-lokal, kein Robolectric ohne Context (B-UI-1-Muster).
- Jeder Bugfix mit Gegenbeweis (Test rot vor, gruen nach dem Fix).
- `THIRD_PARTY_NOTICES.md` bei jedem Versionswechsel pflegen.

## 3. Tranche A — Release-Sicherheit (ca. 1 Woche)

**Status:** `[x]` erledigt (Session: OpenCode, STATUS AJ). Alle Abnahmen grün.

### A1. Main-Thread-Blockade im Timer-Service entfernen (T1)

- Ziel: Kein `runBlocking` auf `Dispatchers.Main` mehr; Kill-Race aus Phase 10.3
  bleibt geschlossen.
- Schritte: `data/timer/.../TimerService.kt:104-113,126` — synchronen Clear durch
  Generation-Counter im Snapshot ersetzen (Kill mit alter Generation wird beim
  Restore verworfen) oder `runBlocking(Dispatchers.IO)` mit Begruendung.
- Abnahme: Neuer `TimerService`-Test (Robolectric, `sdk=33`-Muster) beweist
  Restore-Verwerfung; StrictMode meldet keine Main-Disk-I/O; `terminateTimer`
  und `onPlus15` je 1 Test.
- Aufwand: 2-4 h. Haengt von nichts ab.

### A2. PlaybackService haerten + Media3 1.11.0 (T2)

- Ziel: Exportierter Service nur fuer berechtigte Clients; Session-Sicherheits-
  Default der Bibliothek nutzen.
- Schritte:
  1. `media3` 1.10.1 -> 1.11.0 in `gradle/libs.versions.toml` (+ Notices).
  2. `MediaSession.Callback.onConnect` ueberschreiben (eigenes Paket + System-UIDs,
     Rest ablehnen); `MediaSessionManager` (neu in 1.11) fuer aktive Sessions.
  3. Audio-Session-ID-Race-Fix (#3241) gegen MusicFX-Broadcast verifizieren
     (Geraet oder Robolectric-Test auf `broadcastEffectSession`).
- Abnahme: Fremd-UID-`onConnect` wird abgelehnt (Test); `data:playback`-Tests
  gruen; keine Regression in Auto/BT-Browse-Baum.
- Aufwand: ca. 1 Tag. Haengt von nichts ab.

### A3. Abbrueche nicht mehr verschlucken + Detekt schaerfen (T4)

- Ziel: `CancellationException` wird immer gerethrowt; die beiden abgeschalteten
  Detekt-Regeln kommen zurueck.
- Schritte: `catch (e: Exception)` in `WorkoutRepositoryImpl.kt` (18x),
  `MarkerRepositoryImpl.kt`, `LibraryRepositoryImpl.kt` auf Rethrow-Muster wie
  `HealthConnectHeartRateSource.kt:64` umstellen (ggf. kleine Detekt-Custom-Regel
  oder `SwallowedException`/`TooGenericExceptionCaught` in
  `config/detekt/detekt.yml` reaktivieren und Baseline schrittweise abbauen).
- Abnahme: Reaktivierte Regeln gruen; Abbruch-Test (Scope canceln -> kein
  `AppResult.failure`, Job ist cancelled).
- Aufwand: 4-8 h. Nach A1 sinnvoll (gleiche Fehlerklasse).

### A4. API-37-Hygiene + Predictive Back (T5)

- Ziel: Kein Target-Mismatch, vollstaendige Predictive-Back-Unterstuetzung.
- Schritte: `app/.../AndroidManifest.xml:28` `tools:targetApi="36"` -> `"37"`;
  `enableOnBackInvokedCallback="true"`; manuellen Bibliotheks-Backstack
  (`LibraryContent.kt:89-130`) auf `OnBackInvokedDispatcher`/`PredictiveBackHandler`
  umstellen; 16-KB-Page-Bereitschaft fuer `dropsync.enableFfmpeg` dokumentieren.
- Abnahme: Zurueck-Geste mit Vorschau auf allen Screens; Lint `NewApi` gruen.
- Aufwand: 0,5-1 Tag.

### A5. A11y-Sofortmassnahmen (U1)

- Ziel: TalkBack-Nutzer bedienen Charts, Mini-Player, Bottom-Bar korrekt.
- Schritte:
  1. Chart-Balken: `contentDescription`/`stateDescription` pro Spalte
     (`ProgressDashboardScreen.kt:591-605`).
  2. Mini-Player-Titel: echte Button-Semantik (`role=Button`, `onClickLabel`),
     48-dp-Ziel (`MiniPlayer.kt:87-92`).
  3. Overflow-Button Now-Playing 36 -> 48 dp; FilterChips `heightIn(min=48.dp)`
     (`NowPlayingScreen.kt:537-542`, `SettingsScreen.kt:652-664,767-778`).
  4. Bottom-Bar: `selectedIndex` ohne `coerceAtLeast(0)`-Fallback —
     Sub-Routen selektieren nichts statt faelschlich „Musik"
     (`DropSyncApp.kt:348-351`), echte `selected`-Semantik.
- Abnahme: Je Fund 1 Compose-UI-Test (Semantik-Assertions) oder TalkBack-Protokoll
  am Geraet; keine Regression der Feder-Indikator-Animation.
- Aufwand: ca. 1 Tag zusammen.

## 4. Tranche B — Spuerbarer Nutzer-Impact (ca. 1-2 Wochen)

### B1. Baseline Profile wirksam machen (T3)

- Ziel: Eingechecktes Profil + messbarer Startup-Gewinn.
- Schritte: Generatorlauf am Geraet (`:benchmarks`), `baseline-prof.txt` einchecken,
  CI-Gate (Profil vorhanden + `StartupBenchmark` gegen
  `CompilationMode.Partial(BaselineProfileMode.Require)`), Vorher/Nachher-Zahlen
  in `docs/STATUS_FORTSCHRITT.md`.
- Abnahme: Startup p50/p90 verbessert (Zahl im Status); CI faellt bei fehlendem Profil.
- Aufwand: 0,5-1 Tag. Braucht Geraet (Root oder API 33+, aosp-Abbild).

### B2. Timer auffindbar + lokalisiert (U2)

- Ziel: Timer in <10 s ab App-Start erreichbar, DE/EN vollständig.
- Schritte: Tab-Einstieg oder Settings-Kachel zusaetzlich zum Countdown-Tap;
  Wheel-Labels „STD/MIN/SEK" in Ressourcen (`TimerSection.kt:196-234`);
  Alternative zur deaktivierten Stunden-Spalte (Eingabefeld).
- Abnahme: 2 Nutzerpfad-Tests (Navigation), `androidTest`-Lokalisierungscheck;
  TalkBack liest Restzeit weiter vor (`onClickLabel`-Muster behalten).
- Aufwand: ca. 1 Tag.

### B3. Onboarding + Permission-Kontext (U3)

- Ziel: Erstnutzer verstehen Marker, Drop-Rest und Berechtigungen ohne Doku.
- Schritte: 3 Wischseiten (Musik -> Marker -> Training/Timer); `POST_NOTIFICATIONS`
  erst mit Kontext anfragen (`TrainScreen.kt:142-146`); Bit-Perfect-Grenze und
  Prewarming als erklaerende Hinweiszeilen statt Fachbegriffe.
- Abnahme: Onboarding nur beim First-Run (DataStore-Flag + Test); Geraeteabnahme
  der Permission-Floege.
- Aufwand: 1-2 Tage.

### B4. Undo statt Dialoge (U4)

- Ziel: Destruktive Aktionen sind rueckgaengig statt blockierend.
- Schritte: Snackbar+Undo fuer Queue-Entfernen (`QueueSheet.kt:148-153`),
  Playlist-Entfernen, Marker-Loeschen; vorhandenes `delete (Undo)` in
  `FlatSetRepository.kt:50` in der UI nutzen; destruktive Bestaetigungsdialoge
  nur wo kein Undo moeglich ist.
- Abnahme: Je Aktion 1 ViewModel-/UI-Test (Undo stellt wieder her).
- Aufwand: ca. 1 Tag.

### B5. Health-Connect-UX nach Vorgabe (T7-Anteil)

- Ziel: Offizielle UX-Muster (Google, Stand 07/2026) im Settings-Screen.
- Schritte: Sync-Toggle (Pause/Resume der Synchronisation), „Manage access"-Button
  (Deep-Link in Health-Connect-Einstellungen), „Insufficient access"- und
  „2x abgebrochen"-Zustaende; `revokeAllPermissions`-Hinweis ( Neustart noetig).
- Abnahme: UI-Tests der Zustaende mit Fake-PermissionController
  (`connect-testing`-Artefakt pruefen); Geraeteabnahme des Permission-Flows.
- Aufwand: ca. 1 Tag. (Client 1.1.0 bleibt; 1.2.0-alpha nur beobachten.)

## 5. Tranche C — Modernisierung (laufend)

### C1. M3 Expressive + Designsystem-Konsolidierung (U5)

- Ziel: Ein Radien-/Typo-/Farb-System, Expressive-Komponenten.
- Schritte: `material3`-Version mit stabilen Expressive-Komponenten anheben;
  Radien 16/20/24 auf Shape-Skala, `bodyLarge==bodyMedium` und 12 hartcodierte
  Kategorie-Farben (`LibraryHomeScreen.kt:446-476`, `DetailScreens.kt:192,223`,
  `Color.White/Black`-Scrims) auf Tokens; Dynamic Color als Option (passt zum
  AccentColor-Feature); Styles-API fuer One-off-Komponenten.
- Abnahme: Paparazzi-Screenshots der 5 groessten Screens vorher/nachher;
  kein hartcodierter `Color`-Wert ausserhalb des Themes (Grep-Gate).
- Aufwand: 2-3 Tage.

### C2. Adaptiv: WindowSizeClass + grosse Schrift (U6)

- Ziel: Tablet/Landscape/200 % ohne Bruch.
- Schritte: `material3-window-size-class` (bereits im Katalog) in Now-Playing,
  Train-Screen, Dashboard verdrahten; feste `30/18 sp` und `aspectRatio(1f)`-Cover
  durch adaptive Layouts ersetzen; Dashboard-`fontScale>1.5`-Muster verallgemeinern.
  (Android 16 ignoriert auf >=600 dp ohnehin Orientation-Locks.)
- Abnahme: Screenshot-Gate je Breakpoint; 200-%-Abnahme (Schritt 13).
- Aufwand: 2-3 Tage.

### C3. System-Media-Erlebnis (U7)

- Ziel: Player ausserhalb der App sichtbar und steuerbar.
- Schritte: `media3-ui-compose`-MiniController (Dynamic Color), `ProgressSlider`;
  fortschrittszentrierte Notifications (Android 16) fuer Timer/Rest;
  Output-Switcher (Android 16: Personal Audio Sharing); danach Widget-/Wear-Option
  direkt mit M3 Wear (Horologist ist tot).
- Abnahme: Manuelle Abnahme Notification/Switcher; Widget nur wenn C1 steht.
- Aufwand: 1-2 Tage (+ Wear extra).

### C4. Test-Gates + `src/test`-Luecken (T8)

- Ziel: Was existiert, laeuft in der CI.
- Schritte: Emulator-Job `connectedCheck` (P1-12/B-UI-6, `continue-on-error` zuerst);
  Macrobenchmark-Lauf als Gate (mit B1); Paparazzi-Gate (mit C1); `src/test` fuer
  `core/model` + `domain/settings` nachholen.
- Abnahme: CI-Matrix gruen inkl. neuer Gates; Kennzahl „Module ohne Tests" bleibt 0.
- Aufwand: 2-5 Tage (gestaffelt).

### C5. `build-logic` + Backup-ADR + Kleinigkeiten

- `build-logic`-Convention-Plugins (P2-14/B-ARCH-4): Duplikat-`build.gradle.kts`
  und manuelle Detekt-`source.setFrom`-Liste ersetzen. 2-3 Tage.
- Backup/Export-ADR (T6): verschluesselter Voll-Export (Room + DataStore) vs.
  bewusst kein Transfer + UI-Hinweis; `allowBackup=false` + Extraction-Rules
  anfassen. 1 Tag + Review.
- I18n-Reste + Top-Bars (U8): Home-Strings, Queue-Plural, `Locale.ROOT`-Dezimalen,
  EN-`contentDescription`; Top-Bars vereinheitlichen. ca. 1 Tag.
- Dependency-Updates: coroutines 1.11.0, datastore 1.2.1, Detekt/Benchmark-Alphas
  bei stabilen Releases abloesen (+ Notices). 0,5 Tag.
- Rest aus `VERBESSERUNGSPLAN.md`: B-SEC-2/3/4/6 (Release), B-AUD-7/8, B-DB-2/3/4,
  B-ARCH-5/6, B-UI-2, B-DOC-2/3/6/7, P4-Hardware (Schritt 13).

## 6. Reihenfolge und Abhaengigkeiten

A1-A5 parallelisierbar (A3 nach A1 sinnvoll). B1 braucht ein Geraet, sonst frei.
C1 vor C2/C3 (Tokens zuerst). C4 laeuft nebenher, Emulator-Job zuerst. C5-ADR vor
jedem Backup-Code. Nicht-Ziele: Wear/Auto als Pflicht, Cloud-Sync, Fit-Import
(optional, falls Fit-EOL-Nutzer kommen).

## 7. Abnahme je Tranche

Tranche gilt als fertig, wenn: alle Paket-Abnahmen erfuellt, `./gradlew test
spotlessCheck lintDebug detekt`, `assembleDebug` + `assembleRelease`,
`tools/doku_links_check.py` gruen sind, `VERBESSERUNGSPLAN.md`-Statuszeilen und
`docs/STATUS_FORTSCHRITT.md` nachgezogen wurden und der Commit die Gates nennt.
Geraeteabnahmen landen in `docs/HARDWARE_TESTPLAN.md`.
