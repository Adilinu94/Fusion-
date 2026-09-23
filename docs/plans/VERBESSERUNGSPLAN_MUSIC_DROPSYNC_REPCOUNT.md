# Verbesserungsplan MusicPlayer/DropSync und BLE/IMU-Rep-Zaehlung

Stand: 19.09.2026. Auftrag: zwei Schwerpunkte deutlich verbessern —
(a) MusicPlayer mit seiner DropSync-Funktion, (b) BLE-/IMU-basiertes
automatisches Wiederholungszaehlen fuer Krafttraining. Beides technisch
und UI-seitig.

Dieses Dokument ist eine **Befundliste mit Zielbild und Reihenfolge**, kein
Architekturentwurf. Es aendert keinen Code und hebt keine Regel auf. Wo ein
Befund eine Architekturentscheidung beruehrt, ist der noetige ADR benannt.

## Geltung

Vorrang bei Widerspruch (wie im bestehenden Plan):

1. `docs/adr/*` — verbindliche Entscheidungen
2. `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (Fusionsdesign)
3. `docs/design/2026-08-22-flowtimer-integration-CONTEXT.md` (gesperrte E1-E9)
4. dieser Plan
5. `README.md`-Statustabellen und `VERBESSERUNGSPLAN.md`

Gelesen und gegen den Code geprueft wurden fuer diesen Plan: `VERBESSERUNGSPLAN.md`
(alle 1538 Zeilen), `docs/VERBESSERUNGSANALYSE_2026-09.md`,
`UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md`, `docs/STATUS_FORTSCHRITT.md`
(Abschnitte A-AN), das Fusionsdesign (Abschnitte 3-13), `tools/golden_shadow_corpus/README.md`
sowie der Code der Module `:domain:timer`, `:domain:sensor`, `:data:sensor`,
`:data:playback`, `:feature:player`, `:feature:workout`, `:app`.
Jeder Befund ist mit Datei und Zeile belegt.

Was die letzten Pakete bereits erledigt haben und hier **nicht** erneut
vorkommt: Dezimalkomma-Fix, Reduced-Motion-Local, DataStore-Corruption-Handler,
Listener-Reconnect, Bit-Perfect-Verdrahtung, ReplayGain-Option, FTS-Suche,
Playlist-Duplikat-Schutz, Timer-Presets, Compose BOM 2026.08.00,
`FlowRepTopBar`, Empty/Error-States, Drop-Auto-**Verdrahtung** (Bus-Aufruf),
Log-Fehler-Snackbar, echte PRs im Dashboard. Die Drop-Auto-Verdrahtung wird
unten trotzdem behandelt, weil sie zwar verdrahtet ist, aber nachweislich
nicht funktioniert (MP-1).

---

## 0. Kurzfassung: die zentralen Befunde

| ID | Schwere | Befund | Wirkung |
|---|---|---|---|
| MP-1 | KRITISCH | Drop-Auto ist Ende-zu-Ende wirkungslos: REST-Timer belegt die eine Engine, bevor der Drop-Rest startet; Bus-Konsument lebt nur im Now-Playing-Screen; `replay=0` verwirft den Request | Der Schalter tut nichts, ohne Fehler und ohne Anzeige |
| MP-2 | HOCH | DropRest-Gate verlangt einen zukuenftigen Marker des **laufenden** Songs — Rest-Musik wechselt aber auf die Rest-Playlist | Drop-Auto und Rest-Musik schliessen sich praktisch aus |
| MP-3 | HOCH | Landung ist ein einmaliges `delay()` ohne Driftkorrektur und ohne Re-Check gegen die monotone Uhr | Drop kann deutlich daneben liegen; ADR-0012-Zusage (+/-100-200 ms) ist unbelegt |
| MP-4 | HOCH | Landung ist ein harter Wechsel (`crossfadeMs = 0`), Crossfade-Code ist ohne Konsument, Ducking springt hart zurueck | Hoerbarer Bruch mitten im Takt, Design 7.1a nicht umgesetzt |
| MP-5 | HOCH | Nutzer-Vorrang wird nur auf `isPlaying` geprueft; Skip/Seek/Queue-Wechsel brechen den Plan nicht ab; kein sichtbarer Zustand | Nutzer aendert Musik, der Plan feuert trotzdem; niemand sieht warum |
| MP-6 | HOCH | DropSync ist im Train-Tab unsichtbar; `+15 s` ist bei laufendem DropSync wirkungslos, der Button bleibt aktiv | Die Workout-Konsole fuehrt den Nutzer in eine tote Aktion |
| RC-1 | HOCH | Die komplette Zaehlpipeline laeuft auf `Dispatchers.Main.immediate` (Controller am `viewModelScope`) | Jank-Risiko im Train-Tab; Filter + DTW + Validierung auf dem UI-Thread |
| RC-2 | HOCH | Roh-IMU-Recorder (`JsonlShadowSessionRecorder`) ist in **jedem** Build gebunden und schreibt pro Satz alle Samples auf die Platte | Speicher- und Datenschutzlast in der Auslieferung, kein Aufraeumer |
| RC-3 | HOCH | Gate 11b (5 Freigabe-Szenarien) steht aus; Werkzeuge existieren, Aufnahmen fehlen | Jede Genauigkeitsaussage der App ist unbelegt |
| RC-4 | HOCH | Start-Verweigerung und 0-Rep-Ergebnis sind stumm; "Zuerst kalibrieren" hat keinen Weg in den Wizard | Nutzer weiss nicht, warum nicht gezaehlt wird |

**Nachtrag nach vertiefter Pruefung (19.09.2026)** — zusaetzlich gefunden:

| ID | Schwere | Befund | Wirkung |
|---|---|---|---|
| MP-12 | HOCH | DropRest startet keinen Foreground-Service; der Monitor lebt im Now-Playing-ViewModel | Pause ohne Benachrichtigung, nach Verlassen des Players haengt der Timer ohne Cues/Haptik |
| MP-15 | HOCH | `+15 s` verlaengert den Rest, aber nicht den Landungsplan; Pause/Resume startet die Rest-Playlist von vorn | Drop landet zu frueh; Musik springt beim Fortsetzen zurueck |
| MP-13 | MITTEL | Drop-Auto ist nicht persistiert (Default aus), Design 5.2 verlangt Default an | Einstellung ueberlebt keinen App-Neustart |
| MP-14 | MITTEL | Landungs-Kandidaten sind auf den **ersten** Marker je Work-Titel reduziert | Der Planner kann Marker 2+ nie waehlen, obwohl Entscheidung 37 das vorsieht |
| MP-16 | MITTEL | Doku-Widerspruch: ADR-0012 sagt ohne Rest-Playlist "NORMAL", Design 7.1 Fallback 3 sagt "nur Ducking" | Unklarer Produktvertrag, Code folgt der ADR |
| RC-13 | MITTEL | `recentDiffs` wird beim Uebungswechsel nicht geleert; `ProfileLearningPolicy` prueft nur die letzten zwei Werte | Rollback kann die falsche Uebung treffen |
| RC-14 | NIEDRIG | Der Peak-Blitz nutzt eine eigene Heuristik statt der Engine-Rep-Events | Anzeige und Zaehler koennen sich widersprechen |
| RC-15 | MITTEL | Die Accel-Kalibrierungs-Konstanten sind laut ADR-0017 nur an synthetischen Signalen gesetzt | Der live aktive Accel-Kanal ist unbelegt |
| RC-16 | HOCH | Live- und Replay-Pfad werden nie gegeneinander gemessen (Beleg: BarSpeed #115, Live liest 53-59 % der Batch-Werte) | Systematische Pfad-Differenz bleibt unsichtbar, Gates werden gegen verzerrte Werte kalibriert |
| RC-17 | MITTEL | Ablehnungsgruende werden nicht klassifiziert und gezaehlt (Beleg: BarSpeed #94 zerlegt 27 Fehler in wenige Mechanismen) | "N Abweichungen" lassen sich nicht gezielt beheben |
| DOC-1 | MITTEL | `HARDWARE_TESTPLAN.md` Teil A sagt "Flags bleiben aus" (widerspricht ADR-0017) und B1/B2/B5 setzen einen Crossfade voraus, den es nicht gibt | Testplan prueft Zusagen, die der Code nicht hat |
| DOC-2 | NIEDRIG | ADR-0012 fordert `crossfadeTo`/`ACTION_CROSSFADE_TO`; der Code hat `playSongAt`/`ACTION_PLAY_SONG_AT` | ADR und Code driften auseinander |

Weitere Befunde aus der bestehenden Tiefenrecherche (RC-18 Ermuedungsdrift,
RC-19 Autokorrelation als Quelle, RC-20 Schwellen pro Uebung, RC-21 DBA,
RC-22 Downbeat-Offset) stehen in **Anhang B**.

Die Befunde sind bewusst gegen den aktuellen Arbeitsbaum verifiziert, nicht
aus aelteren Plaenen uebernommen. **Korrektur zur Erstfassung:** RC-9
behauptete, `accelEnabled` sei aus — das ist falsch. ADR-0017 hat den Kanal
bewusst profilgesteuert scharfgeschaltet; nur `orientationTrackingEnabled`
ist aus (siehe RC-9 unten).

## 0.1 Methodik und externe Recherche (Nachtrag 19.09.2026)

Geprueft wurden zusaetzlich: ADR-0012, ADR-0014, ADR-0017,
`docs/design/REP_ZAEHLUNG_UMBAUPLAN_2026-08-12.md` (Punkte 7/9, DoD),
`docs/HARDWARE_TESTPLAN.md` (Teil A/B), `docs/qa/UI_REVIEW.md`,
`ProfileLearningPolicy`, `CuePlanner`, `RestTimerServiceStarter` sowie die
externe Literatur unten. Die wichtigsten Quellen und was sie fuer den Plan
aendern:

| Quelle | Kernaussage | Folge fuer den Plan |
|---|---|---|
| Media3 1.11 "What's new" (Android Developers Blog, 08/2026): `PlayerPool`, `rememberPooledPlayer` | Player-Recycling und **Preloading** sind jetzt offizielle APIs | Der Work-Titel kann fuer die Landung vorgeladen werden (`ExoPlayer.setPreloadConfiguration`), statt im Moment des Drops zu puffern — direkt verwertbar fuer MP-3/MP-4 |
| Media3-Doku "Preload media" / Release Notes | Preloading reduziert Join-Latenz beim naechsten Titel | Stuetzt die Landungs-Praezision ohne eigenen Preload-Bau |
| ExoPlayer-Doku "Troubleshooting"/`setSeekParameters` | Seek-Genauigkeit ist explizit steuerbar (`EXACT` vs. `CLOSEST_SYNC`) | Beim DIRECT_TO_DROP-Sprung `SeekParameters.EXACT` setzen und die Preroll-Idee (Design 7.1c) pruefen |
| Android "Schedule alarms" + Doze-Praxis | Exakte Alarme (`setExactAndAllowWhileIdle`/`setAlarmClock`) sind unter Doze robust, aber genehmigungspflichtig und fuer Fitness-Apps policy-seitig nicht vorgesehen | Timing bleibt beim Foreground-Service + monotoner Deadline-Schleife; kein Exact-Alarm-Antrag. Kurzer Wake-Lock nur um den Landepunkt (MP-3/MP-12) |
| BLE-Throughput-Praxis (Punch Through, Novel Bits, Memfault) | MTU, Connection Interval und PHY bestimmen den Durchsatz; Notify ist der richtige Transport fuer Sensor-Streams | Bestaetigt den Ist-Pfad (Notify + MTU + `CONNECTION_PRIORITY_HIGH`); `setPreferredPhy(LE_2M)` als optionale, unverbindliche Verbesserung |
| Compose `withFrameNanos`-Doku | Frame-Scheduling ist ein eigener Takt, unabhaengig von Composition | Fuer RC-11 die Waveform auf Frame-Grenzen drosseln statt je Sample |
| RecoFit (Microsoft Research), MM-Fit (UbiComp 2021), LEAN (MDPI 2023), Czekaj 2024 (Sensors), Few-Shot-Rep-Counting (arXiv 2410.00407, 2024), Brennan 2025 (Sports Medicine, Systematic Review) | Klassische Peak-/Template-Verfahren sind fuer Echtzeit am Handgelenk weiter konkurrenzfaehig; ML braucht Trainingsdaten und bringt oberhalb eines gewissen Aufwands nur marginale Gewinne | Der eingeschlagene Weg (kalibrierte Pipeline + Korpus-Validierung) ist richtig; ML ist eine spaetere Option, kein Ersatz fuer Gate 11b |

## 0.2 Werkzeugevaluierung: drei Repos real getestet (19.09.2026)

Auftrag war, drei Recherche-Werkzeuge zu testen und zu pruefen, ob sie
weitere Verbesserungen fuer diesen Plan liefern. Getestet auf Windows mit
Python 3.13/uv, Git und authentifiziertem `gh` CLI; alle Ergebnisse sind
reproduzierbar.

| Werkzeug | Test | Ergebnis | Grenze |
|---|---|---|---|
| hyperresearch (MIT, Python) | venv + `pip install -e .`, eigene Testsuite, `hpr scholar search`, Vault `init`+`fetch` | Testsuite: **1242 Tests, 1 Fehlschlag** (nur wegen fehlendem crawl4ai-Extra), 0 Fehler, 8 skipped. `scholar search` liefert echte Treffer aus OpenAlex/Crossref/DOAB ohne Key; `fetch` speicherte das Few-Shot-Paper (7427 Woerter) in den Vault | Die 16-Schritt-Pipeline braucht Claude Code; die CLI-Teile (scholar/fetch/search/note) laufen ohne |
| OpenResearch / `orx` (alphaXiv, Rust) | Windows-Beta-CLI geladen, `discover keyword/openalex`, `paper --full` | CLI laeuft; `orx paper 2410.00407 --full` liefert **52 KB Volltext** aus alphaXiv | `discover` lieferte in zwei Testqueries unbrauchbare Treffer (schwache Relevanz); Volltext nur fuer den arXiv-Korpus (CS/Math/Physik/Stats) |
| Agent-Reach (MIT, Python) | venv + Testsuite, `doctor`, GitHub-Kanal via `gh` | **589 Tests bestanden**, 3 Umgebungsfehler (ffprobe fehlt, lokale Config), 15 skipped; `doctor` 3/16 Kanaele in dieser Umgebung; GitHub-Suche lieferte die unten genutzten Issues | Reddit/Twitter/XiaoHongShu brauchen Cookies/Login; Reddit ueber Jina ist 403 (verifiziert) |

**Konsequenz fuer den Plan:** Die Werkzeuge ersetzen keine eigene
Pruefung, aber sie liefern drei Dinge, die der Plan vorher nicht hatte:
belastbare Literatur mit DOIs (hyperresearch), Volltexte von
arXiv-Papieren (orx) und echte Fehlerberichte vergleichender Projekte
(Agent-Reach/GitHub). Daraus abgeleitet: **RC-16** (Live-vs-Replay),
**RC-17** (Ablehnungs-Mechanismen) und die Zusatzszenarien A10-A12 im
Kampagnenplan; die Nutzung steht in C.4. Die beiden von Adi gewaehlten
Recherche-Punkte sind als Notizen abgelegt:
`docs/research/2026-09-19-pausen-benachrichtigung-service-typ.md` und
`docs/research/2026-09-19-rep-counting-per-uebung.md`.

---

# Teil A — MusicPlayer und DropSync

## A.1 Ist-Stand (verifiziert)

**Was existiert und funktioniert:**

- Ein MediaSession-Service mit genau einem ExoPlayer; `PlaybackRepository`
  als einzige App-Schnittstelle (`data/playback/PlaybackRepositoryImpl.kt`).
- `TimerEngine` als **eine** geteilte Zustandsmaschine fuer NORMAL, REST und
  DROPSYNC (`domain/timer/TimerEngine.kt:26-33`), @Singleton in
  `data/timer/.../di/TimerDataModule.kt:79-83`.
- Drop-Landung als reine, getestete Planungslogik
  (`domain/timer/DropLanding.kt:81-139`): INTRO (Start vor dem Pausenende)
  und DIRECT_TO_DROP (Sprung beim Go), Markerwahl nach Entscheidung 37.
- Rest-Musik-Orchestrierung (`feature/player/DropSyncCoordinator.kt`):
  Rest-Playlist bei Pausenbeginn, Ducking, geplante Landung, Work-Titel am
  Pausenende, Generation-Token.
- Manueller Drop-Rest "Rest bis zum naechsten Drop" im Player
  (`DropRestViewModel` + `DropRestCard`), mit Gate, Monitor und Abbruch.
- Route-Latenzprofile (`RouteProfileStore`), AudioClock-Bausteine,
  Ducking-Rampe (`AudioPipeline.setRestDuckDb`), `DropRestRequestBus`.

**Der Datenfluss heute:**

```
Satz fertig (TrainViewModel.logSet)
  -> TimerEngine.start(REST)                 (TrainViewModel.kt:207-211)
  -> wenn Drop-Auto: bus.request()           (TrainViewModel.kt:216)
        -> DefaultDropRestRequestBus (replay=0, Kapazitaet 1)  (:15)
        -> Konsument: DropRestViewModel, existiert nur mit NowPlayingScreen
           -> startDropSync(...)             (DropRestViewModel.kt:85-95)
              -> TimerConflict, weil REST schon RUNNING
                 (TimerEngine.kt:297-301) -> stiller return
  -> RestMusicCoordinator.onRestBegin        (RestMusicCoordinator.kt:136)
        -> setQueue(REST-Playlist), Ducking an
        -> delay(plan.startAfterDelayMs)     (:177-181)
        -> playSongAt(workSong, ...)         (:191)
```

## A.2 Befunde mit Beleg, Wirkung, Fix, Verifikation

<a name="mp-1"></a>
### MP-1 — KRITISCH — Drop-Auto ist Ende-zu-Ende wirkungslos

**Beleg (drei unabhaengige Ursachen):**

1. **Reihenfolge:** `TrainViewModel.startRestTimer()` startet zuerst den
   REST-Timer (`TrainViewModel.kt:207-211`) und feuert danach den Bus
   (`:216`). `TimerEngine.beginSession()` lehnt jede neue Sitzung ab, solange
   RUNNING/PREPARING/PAUSED (`TimerEngine.kt:297-301`). `startDropSync` kann
   also nie starten, solange der Rest laeuft.
2. **Lebensdauer des Konsumenten:** `DropRestViewModel` wird ausschliesslich
   in `NowPlayingScreen` erzeugt (`NowPlayingScreen.kt:637-654`) und haengt
   damit am Now-Playing-Navigationseintrag. Wer den Satz im Train-Tab loggt,
   ohne den Player je geoeffnet zu haben, hat nie einen Konsumenten; wer den
   Player per Zurueck verlaesst, zerstoert ihn (der Eintrag wird gepoppt).
   `navigateTopLevel` rettet den Zustand nur bei Tab-Wechseln
   (`DropSyncApp.kt:569-573`) — eine tragfaehige Lebensdauer ist das nicht.
3. **Bus-Semantik:** `replay = 0, extraBufferCapacity = 1`
   (`DefaultDropRestRequestBus.kt:15`) — ohne aktiven Collector ist der
   Request verloren.

**Der Test verdeckt es:** `TrainViewModelTest.kt:739-757` prueft nur
`dropRestRequestBus.requestCount == 1`. Dass danach nichts passiert, sieht
der Test nicht. Der STATUS-Eintrag "Paket 3.14: Drop-Auto verdrahtet" ist
damit korrekt fuer den Aufruf und falsch fuer die Wirkung.

**Wirkung:** Der sichtbare Schalter "Drop-Auto" ist eine Falschaussage
gegenueber dem Nutzer. Zusaetzlich erklaert er das Produktversprechen aus
Design 4.2 ("Workout fordert DropSync-Rest an") fuer nicht eingeloest.

**Fix (Produktentscheidung noetig, Empfehlung):**
Der Bus gehoert laut Design 4.2 **nicht** an den DropRest-Timer, sondern an
die Landing-Planung des `RestMusicCoordinator`:

- `Drop-Auto AN` = "Der Rest bleibt die eingestellte Dauer; die **Musik**
  landet so, dass ihr Drop das Pausenende trifft" (DROP_LANDING).
- `Rest bis zum naechsten Drop` (DropRestCard im Player) bleibt die
  **manuelle** Aktion "Pause endet am naechsten Marker des laufenden Songs".

Umsetzung:
1. Konsument des Bus wird ein **app-weiter** Koordinator (nicht der
   Screen-ViewModel): `RestMusicCoordinator` oder ein neuer
   `DropSyncCoordinator` im Singleton-Scope, gestartet in
   `DropSyncApplication.onCreate()` (Muster `RestMusicCoordinator.start()`).
2. `TrainViewModel.startRestTimer()` startet den REST-Timer und feuert den
   Bus; der Koordinator plant die Landung fuer **diese** Rest-Sitzung
   (Session-ID + Generation), unabhaengig davon, welcher Screen sichtbar ist.
3. Schlaegt die Planung fehl (keine Work-Playlist, kein Marker), faellt sie
   auf NORMAL zurueck und der Grund wird **sichtbar** (UI, siehe MP-6).
4. `DropRestViewModel` bleibt fuer die manuelle Aktion; sein `startDropRest`
   bricht kuenftig vor dem `startDropSync` sauber ab, wenn bereits ein Timer
   laeuft, und meldet den Grund statt still zu returnen.

**Verifikation:**
- Neuer Test auf Koordinator-Ebene: `request()` bei laufendem REST-Timer
  erzeugt einen `DropLandingPlan` fuer genau diese Session (echte
  `TimerEngine`, Fake-Playback).
- Gegenbeweis: ohne Work-Playlist kein Plan, REST laeuft normal weiter.
- Bestehender Test wird um die Wirkungspruefung erweitert (nicht nur
  `requestCount`).

<a name="mp-2"></a>
### MP-2 — HOCH — DropRest-Gate und Rest-Musik schliessen sich aus

**Beleg:** `DropRestGate.evaluate` verlangt `isPlaying` und einen zukuenftigen
Marker **des aktuellen Songs** (`DropRest.kt:57-87`). Der Coordinator wechselt
bei Pausenbeginn aber die Queue auf die REST-Playlist
(`RestMusicCoordinator.kt:140-151`). Rest-Titel haben in der Regel keine
Work-Marker — das Gate ist dann dauerhaft `NO_FUTURE_MARKER`.

**Wirkung:** Genau die Kombination, die das Produkt will ("Musik in Pausen
an" + DropSync), ist die Kombination, in der DropRest nie greift.

**Fix:** Die DropSync-Planung darf sich nicht am laufenden Titel orientieren,
sondern an der **Work-Quelle**:

- Kandidaten kommen aus `workCandidates()` (Work-Playlist + aktive Marker),
  wie bei der Landung (`RestMusicCoordinator.kt:212-234`).
- Der manuelle DropRest im Player bleibt zusaetzlich am laufenden Titel
  (das ist seine Daseinsberechtigung: "ich hoere gerade etwas, der naechste
  Drop soll mein Pausenende sein"), aber sein Blockadegrund wird auch im
  Player sichtbar (heute wird die Karte bei Ineligible komplett versteckt,
  `NowPlayingScreen.kt:651`).

**Verifikation:** Gate-Tests mit "Rest-Playlist aktiv + Work-Marker
vorhanden" -> Plan entsteht; "nur Rest-Titel, keine Work-Playlist" ->
sichtbarer Fallback.

<a name="mp-3"></a>
### MP-3 — HOCH — Landung ohne Driftkorrektur

**Beleg:** `RestMusicCoordinator.onRestBegin` berechnet `remaining` einmal
(`:157-160`), plant `startAfterDelayMs` (`:164-172`) und wartet mit einem
einzigen `delay()` (`:177-181`). Danach folgt nur der `isPlaying`-Check
(`:185-190`) und `playSongAt` (`:191`). Kein Vergleich "geplante Zielzeit vs.
jetzt", keine Nachkorrektur der Startposition, kein Nachziehen, wenn
`setQueue`/`prepare` laenger braucht oder die Route wechselt.

Der Kommentar im Coordinator nennt +/-100-200 ms als "realistisch"
(`:48-49`) — das ist eine Annahme, keine Messung. `delay()` feuert unter
Last, Doze oder CPU-Drossel spaeter; die Abweichung wird nicht bemerkt.

**Fix:**
1. **`PlayerMessage` statt `delay()` (primaer):** Media3 fuehrt ein
   `ExoPlayer.createMessage(...).setPosition(...)` auf dem Playback-Thread
   **an einer Wiedergabeposition** aus — die richtige Primitive fuer die
   Landung (Tiefenrecherche D3, `docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md`
   Abschnitt 3.2). Die Deadline-Schleife aus A.3 bleibt nur Fallback.
2. **Startposition nachziehen:** `startAtPositionMs` beim Landen um die
   bereits verstrichene Zeit korrigieren (INTRO: Startposition 0 + delta,
   solange < Drop; DIRECT_TO_DROP: unveraendert).
3. **Konfidenz statt Blindflug:** Latenz aus `RouteProfileStore` +
   Route-Wechsel waehrend des Plans erkennen; bei ungueltiger Basis
   `BEST_EFFORT` markieren (Design 7.1b) statt so zu tun, als waere der Drop
   synchron.
4. **Messung (Instrumentierung jetzt, Messung spaeter):** Geplante vs.
   tatsaechliche Startzeit protokollieren (Logcat-Tag + Diagnose-Screen)
   und `AudioRouteProfile.p50ErrorMs`/`p95ErrorMs` befuellen — die Felder
   existieren, sind aber nie geschrieben worden. Adi hat die Messung
   vorerst zurueckgestellt (Entscheidung 5); die Instrumentierung wird
   trotzdem gebaut, damit die Messung spaeter nur noch abgelesen wird.
   Zielwerte: ±25 ms Anspruch, ±50 ms Akzeptanz (Tiefenrecherche D5,
   Wahrnehmungsschwellen 27-49 ms).

**Verifikation:** JVM-Test fuer die Deadline-Schleife mit FakeClock
(spaeter Tick korrigiert die Restzeit); Geraetemessung P95 pro Route als
Abnahmekriterium (Design 11, "P95 Drop-Fehler pro Route dokumentiert").

<a name="mp-4"></a>
### MP-4 — HOCH — Harter Wechsel; Crossfade-Code ohne Konsument

**Beleg:** Der Coordinator uebergibt `crossfadeMs = 0L` hart
(`RestMusicCoordinator.kt:170`). `MixPreset`/`CrossfadeCurves` haben keinen
Produktivkonsumenten, die Bedienflaechen sind seit B-AUD-5 ausgegraut. Das
Ducking wird hart an/aus geschaltet (`restDucking.setActive(true/false)`,
`:154`, `:195`), obwohl `AudioPipeline.setRestDuckDb` eine Rampe hat.

**Wirkung:** Die INTRO-Landung reisst die Restmusik mitten im Takt ab; der
Duck springt zurueck; Design 7.1a (Gain-Rampen 5-10 ms, kein Knacksen) ist
nicht umgesetzt.

**Fix in zwei Stufen:**
- **Stufe 1 (jetzt, kein ADR):** Mikro-Rampe am Wechsel — Player-Volume im
  Service kurz aus-/einblenden (5-10 ms) und Ducking ueber die vorhandene
  Rampe statt hart. Fuer DIRECT_TO_DROP nur Klick-Schutz, der Sprung selbst
  bleibt hart (der Drop muss sitzen).
- **Stufe 2 (spaeter, eigener ADR):** echter Crossfade mit zweitem,
  vorbereitetem Player/Preload (Design 7.1a). Voraussetzung: Messung aus
  MP-3, Klaerung Sample-Rate-Wechsel, CPU-Kosten. Bis dahin bleiben die
  ausgegrauten Panels korrekt.

**Verifikation:** Hoertest + Klick-Metrik auf synthetischen Fixtures
(Design 11: Gain-Kontinuitaet, Hochpass-Energie der ersten 10-30 ms).

<a name="mp-5"></a>
### MP-5 — HOCH — Nutzer-Vorrang unvollstaendig, Zustand unsichtbar

**Beleg:** Nur `!snapshot.isPlaying` bricht die Landung ab
(`RestMusicCoordinator.kt:185-190`). Skip, Seek, Queue-Aenderung und
Songwechsel waehrend der Pause werden nicht geprueft. `startWorkTitle()`
(`:200-205`) setzt am Pausenende die WORK-Queue, auch wenn der Nutzer
zwischenzeitlich bewusst etwas anderes gehoert hat. Es gibt keinen
UI-Zustand fuer geplant/armed/uebernommen/best-effort — der Nutzer sieht
weder dass ein Plan laeuft, noch dass er verworfen wurde.

**Fix:**
1. **Playback-Events waehrend des Plans auswerten:** Der Coordinator
   beobachtet `PlaybackRepository.state` (bzw. Events) und wertet
   `EVENT_MEDIA_ITEM_TRANSITION`, `EVENT_POSITION_DISCONTINUITY` und
   `EVENT_IS_PLAYING_CHANGED` als Nutzer-Override — mit Ausnahme der eigenen
   Landung (Token/Generation, damit sich der Plan nicht selbst abbricht).
2. **Zustandsmodell** (StateFlow) mit den Zustaenden aus Design 4.5:
   `Off / Planned / Armed / Landed / BestEffort / Overridden(reason) /
   Failed(reason) / Cancelled`. UI-Handbuch 4.5 verlangt genau das; die
   konkreten Typen unten in A.3.
3. **Sichtbare Ruecknahme:** Nach Override ein Hinweis ("DropSync fuer diese
   Pause beendet — du hast die Musik geaendert") und, wo sinnvoll, Undo nur
   fuer den UI-Plan, nie fuer einen verpassten Drop.

**Verifikation:** Tests je Event-Art (Skip/Seek/Pause) gegen den
Koordinator; Test "eigene Landung bricht sich nicht selbst ab"; UI-Test der
Zustandstexte.

<a name="mp-6"></a>
### MP-6 — HOCH (UI) — DropSync in der Train-Konsole unsichtbar; toter +15-s-Knopf

**Beleg:** Die `RestConsole` zeigt nur Zeit und drei Aktionen
(`TrainScreen.kt:597-663`); der Drop-Auto-Schalter steht ohne Zustand in der
Satz-Karte (`:267-281`); die `DropRestCard` existiert nur im Player
(`NowPlayingScreen.kt:405-410`, `:637-654`). `TimerEngine.addTime()` lehnt
DROPSYNC ab (`TimerEngine.kt:227-233`), aber die `RestConsole` bietet
`+15 s` fuer **jeden** laufenden Timer an (`TrainScreen.kt:633-652`) —
bei einem laufenden Drop-Rest ist der Knopf also wirkungslos, ohne dass man
es sieht.

**Fix (UI-Konzept, Details in A.4):** Die Rest-Console wird der gemeinsame
Hero fuer Normal-Rest und DropSync; sie kennt den Modus und blendet
kontextabhaengige Aktionen ein. Bei DROPSYNC gibt es `Plan abbrechen` statt
`+15 s`; der Ziel-Track, der Marker und der Drop-Countdown stehen sichtbar
unter der Zeit.

**Verifikation:** Compose-/State-Tests: bei `TimerMode.DROPSYNC` ist `+15 s`
nicht vorhanden, `Plan abbrechen` schon; bei REST umgekehrt. Roborazzi-
Screenshots der drei Zustaende (Normal, DropSync armed, Best Effort).

<a name="mp-7"></a>
### MP-7 — MITTEL — Mini-Player und Now-Playing kennen keinen DropSync-Zustand

**Beleg:** `MiniPlayerState` (`PlayerViewModel.kt:39-48`) und
`NowPlayingUiState` (`:55-68`) haben kein DropSync-Feld. Der Mini-Player
zeigt immer Play/Queue (`MiniPlayer.kt:133-148`); der `Next`-Knopf ist
waehrend eines Plans ungeschuetzt. UI-Handbuch 11.3-11.4 verlangt
`DROP READY` + `Details` statt `Next` und eine Abbrechen-Snackbar bei Skip.

**Fix:** Beide Zustaende um `dropSync: DropSyncUiStateModel` erweitern
(Quelle: Koordinator-StateFlow, keine zweite Wahrheit). Mini-Player:
Badge "Drop in 0:47"; waehrend Armed `Next` durch `Details` ersetzen;
Skip -> Plan abbrechen + Snackbar mit Undo (nur UI). Now-Playing: Status-
zeile unter der Waveform, gleiche Sprache wie die Konsole.

**Verifikation:** State-Tests + Screenshot; TalkBack-Ansage nennt Track,
Marker und Zielzeit (UI-Handbuch 19.3).

<a name="mp-8"></a>
### MP-8 — MITTEL — N+1 auf dem Wiedergabepfad bei jedem Pausenbeginn

**Beleg:** `workCandidates()` laedt je Pause die Work-Playlist und je Titel
die Marker (`RestMusicCoordinator.kt:212-234`); `songsForLabel()` laedt
Playlists und Songs bei jedem Aufruf neu (`:236-241`). Der `RestMusicCoordinator`
haelt einen Scope ohne Cancel-Pfad (`:65`, bereits im Analyse-Dokument
notiert).

**Fix:** Work-Kandidaten als Flow cachen und bei Playlist-/Marker-Aenderung
invalidieren (Room-Flow), oder eine kombinierte Query
(`playlist_items JOIN song_markers`). Der Coordinator-Scope bekommt einen
`close()`/Cancel-Pfad, auch wenn er app-weit lebt (Testbarkeit).

**Verifikation:** Test "Pausenbeginn ohne DB-Aenderung fragt keine Songs
einzeln ab" (Fake-Repository zaehlt Aufrufe).

<a name="mp-9"></a>
### MP-9 — MITTEL — Planner-Parameter ohne Wirkung; Ducking-Level nicht am Ort

**Beleg:** `DropLandingPlanner.plan(crossfadeMs)` wird nur mit 0 aufgerufen
(`RestMusicCoordinator.kt:170`) — der Parameter existiert, wirkt aber nie.
Der Rest-Duck-Regler liegt ausschliesslich in den Einstellungen.

**Fix:** Entweder Crossfade verdrahten (Stufe 2 aus MP-4) oder Parameter
entfernen/dokumentieren. Den Duck-Level im Rest-Hero erreichbar machen
(kompakter Regler oder Sheet), mit demselben Wert wie in den Einstellungen.

<a name="mp-10"></a>
### MP-10 — MITTEL — Kein Recovery fuer DROPSYNC nach Kill/Reboot

**Beleg:** `TimerEngine.snapshot()` nimmt DROPSYNC bewusst aus
(`TimerEngine.kt:396-399`); der Kill-Fallback deckt nur NORMAL/REST. Nach
einem Prozess-Kill ist der Plan weg, die Restmusik laeuft weiter, der Nutzer
bekommt keinen Hinweis. Das ist als bewusste Entscheidung dokumentiert,
aber die Konsequenz ist nicht sichtbar.

**Fix:** Beim Start des Koordinators einen laufenden REST-Timer ohne Plan
erkennen und einmal neu planen, wenn Marker/Queue verfuegbar sind; sonst
`BestEffort(PLAN_LOST)` anzeigen. Alternativ die Entscheidung im UI
erklaeren ("DropSync wird nach einem Neustart nicht wiederhergestellt").

<a name="mp-11"></a>
### MP-11 — NIEDRIG — `markRunning` setzt die Startzeit nicht

**Beleg:** `TimerEngine.markRunning` kopiert nur den Status
(`TimerEngine.kt:96-102`); `TimerSession.startedElapsedRealtimeMs` bleibt bei
DROPSYNC `null` (gesetzt nur in `beginSession`, `:324-325`). Heute nutzt
niemand die Startzeit einer DROPSYNC-Sitzung — der Coordinator steigt vorher
aus (`RestMusicCoordinator.kt:157`). Das ist eine latente Falle fuer die
naechste Aenderung.

**Fix:** In `markRunning` die Startzeit setzen; oder den Vertrag im KDoc
explizit als "null bei DROPSYNC" festschreiben und einen Test darauf legen.

<a name="mp-12"></a>
### MP-12 — HOCH — DropRest ohne Foreground-Service; Timer kann haengen bleiben

**Beleg:** `RestTimerServiceStarter` wird nur vom Train-Pfad
(`TrainViewModel.kt:214`) und von `TimerRecoveryStarter` benutzt — nicht vom
`DropRestViewModel`. Ein DROPSYNC-Timer laeuft also ohne Foreground-Service
und ohne Notification. Sein einziger Lebenszyklus ist der `monitorJob` im
`viewModelScope` des Now-Playing-Screens (`DropRestViewModel.kt:72-100`,
`:117-161`); `onCleared` faengt den Fall nicht ab. `TimerEngine.snapshot()`
nimmt DROPSYNC bewusst aus (`TimerEngine.kt:396-399`), es gibt also auch
keinen Recovery-Pfad.

**Wirkung (drei Ebenen):**
1. Verlaesst der Nutzer den Player, stoppt der Monitor; die Sitzung bleibt
   `RUNNING`, niemand projiziert die Restzeit, niemand liefert die Cues. Der
   Drop wird nicht mehr angesagt (Haptik/Beep), die Karte zeigt eine
   eingefrorene Zahl, bis der Nutzer sie manuell abbricht.
2. Ohne Notification sieht der Nutzer bei dunklem Bildschirm keinen
   Drop-Countdown und hat keine Aktionen.
3. Ohne Service kann der Prozess unter Speicherdruck sterben — der Rest
   verschwindet dann ganz (kein Snapshot).

**Fix:** Den DROPSYNC-Timer denselben Service-Pfad geben wie REST:
`RestTimerServiceStarter` beim Start aufrufen, `TimerService` um den
DROPSYNC-Modus erweitern (Fortschrittsanzeige aus der projizierten
Restzeit, Aktionen `Plan abbrechen`/`+15 s`-Ersatz), und den Monitor aus
dem Screen lösen (in den neuen DropSync-Koordinator, A.3). Der Recovery-Fall
bleibt ausgenommen, muss aber sichtbar sein (MP-10).

**Verifikation:** Robolectric-Test "DropRest startet den Service";
Instrumentiert: Notification erscheint, Prozess-Kill-Test (A5 aus dem
Hardware-Testplan), Cues feuern bei dunklem Bildschirm.

<a name="mp-13"></a>
### MP-13 — MITTEL — Drop-Auto ist nicht persistiert und default aus

**Beleg:** `_dropAutoEnabled = MutableStateFlow(false)` ist reiner
View-Zustand (`TrainViewModel.kt:165-170`); kein Store, kein DataStore-Key.
Das Fusionsdesign 5.2 fuehrt dagegen `RestMusicSettings` mit "dropAuto pro
Pause **default an**". `RestMusicSettingsStore` persistiert nur `behavior`
(`data/playback/RestMusicSettingsStore.kt:27-38`).

**Fix:** Entweder den Schalter bewusst als Sitzungs-Schalter dokumentieren
(Design 5.2 korrigieren) oder wie vorgesehen persistieren (Default an,
pro Uebung optional). Empfehlung: persistieren, aber als "pro Pause"
beschriftet lassen — der Default an ist der Produktabsicht nach richtig.

<a name="mp-14"></a>
### MP-14 — MITTEL — Nur der erste Marker je Work-Titel wird Landungs-Kandidat

**Beleg:** `workCandidates()` reduziert je Song auf
`minByOrNull { it.positionMs }` (`RestMusicCoordinator.kt:216-232`). Der
Planner sieht damit pro Titel genau einen Marker, obwohl Entscheidung 37
den Kandidaten mit dem kleinsten `|Rest - Drop|` waehlt und das Fusionsdesign
7.1 ausdruecklich "bei mehreren Markern" spezifiziert.

**Wirkung:** Ein Titel mit einem fruehen und einem spaeter passenden Marker
wird nie mit dem passenden Drop gewaehlt; die Landung springt unnoetig auf
den ersten Marker.

**Fix:** Alle aktiven Marker je Song als Kandidaten uebergeben (Songdaten
bleiben gemappt), der Planner waehlt wie spezifiziert. Test: zwei Marker am
selben Titel, der naehere gewinnt.

<a name="mp-15"></a>
### MP-15 — HOCH — Restzeit-Aenderungen erreichen den Plan nicht; Resume startet die Musik neu

**Beleg:** `RestMusicCoordinator.onState` reagiert nur auf Sitzungswechsel
oder Sitzungsende (`:110-133`). `TimerEngine.addTime` aendert nur
`remainingMs` (`TimerEngine.kt:227-255`) — der Landungs-Job laeuft mit dem
alten `delay` weiter. Umgekehrt gilt: Wird der Rest pausiert, raeumt der
Coordinator auf (`activeSessionId = null`, `:118-132`); beim Fortsetzen
laeuft `onRestBegin` erneut und setzt die Rest-Queue mit
`startIndex = 0, playWhenReady = true` (`:151`) — die Restmusik beginnt von
vorn.

**Wirkung:** `+15 s` in einer DropSync-Pause laesst den Drop 15 s zu frueh
landen; Pause/Resume ist ein hoerbarer Bruch (Titel springt zurueck). Beides
sind alltaegliche Bedienungen, keine Randfaelle.

**Fix:** Den Plan bei Restzeit-Aenderungen neu bewerten (Restzeit aus der
Engine lesen, Deadline verschieben; Landung ggf. neu planen oder abbrechen)
und beim Resume die Queue-Position erhalten statt neu zu setzen. Tests:
"+15 s verschiebt die Landung", "Pause/Resume behaelt den Titel".

<a name="mp-16"></a>
### MP-16 — MITTEL — Doku-Widerspruch beim Fallback ohne Rest-Playlist

**Beleg:** ADR-0012 ("Folgen") sagt: "Keine 'Rest/Pause'-Playlist ->
`NORMAL`-Verhalten (Musik laeuft weiter)". Das Fusionsdesign 7.1 nennt als
Fallback 3 dagegen "keine Rest Playlist -> nur Ducking ohne Queue Wechsel".
Der Code folgt der ADR (`RestMusicCoordinator.kt:140-146` kehrt vor Ducking
und Planung zurueck).

**Fix:** Entscheiden und die unterlegene Quelle korrigieren. Empfehlung:
beim ADR bleiben (kein Ducking ohne Queue-Wechsel — Ducking ohne Grund ist
fuer den Nutzer schwer erklaerbar), aber das Design 7.1 nachziehen, damit
der Testplan nicht gegen die falsche Zusage prueft.

## A.3 Zielbild Technik: ein DropSync-Koordinator statt drei halber Wege

**Kern der Aenderung:** DropSync ist heute auf drei Stellen verteilt
(`DropRestViewModel`, `RestMusicCoordinator`, `DropAuto`-Bus) und hat keinen
gemeinsamen Zustand. Ziel ist **eine** Planungs- und Ausfuehrungseinheit mit
**einem** Zustandsmodell, das UI und Diagnose speist.

```
DropSyncCoordinator (@Singleton, App-Scope)
  Eingaben:
    - TimerEngine.state (REST/DROPSYNC-Sitzungen)
    - PlaybackRepository.state (Position, isPlaying, Queue-Events)
    - RestMusicSettingsRepository.behavior
    - MarkerRepository / LibraryBrowseRepository (Work-Kandidaten)
    - RouteProfileRepository (Latenz, Konfidenz)
    - DropRestRequestBus (Anforderung aus dem Trainingslog)
  Ausgaben:
    - StateFlow<DropSyncState>
    - DropSyncEvent (Landed, Missed, Overridden, Failed) fuer Snackbar/Log
  Verantwortung:
    - Plan waehlen (Kandidat, Modus, Deadline, Konfidenz)
    - Queue/Ducking setzen, Landung deadline-basiert ausfuehren
    - Nutzer-Override erkennen und sichtbar machen
    - Diagnose: geplante vs. tatsaechliche Startzeit
```

**Zustandsmodell (Vorschlag, deckt Design 4.5 und UI-Handbuch ab):**

```kotlin
sealed interface DropSyncState {
    data object Off : DropSyncState
    data class Planned(
        val songTitle: String,
        val markerLabel: String,
        val targetElapsedRealtimeMs: Long,
        val confidence: TimingConfidence,   // EXACT / DEGRADED / UNKNOWN
        val mode: Mode,                     // LANDING_AT_REST_END / UNTIL_MARKER
    ) : DropSyncState
    data class Armed(/* wie Planned + verbleibende Zeit */) : DropSyncState
    data class Landed(val atElapsedRealtimeMs: Long, val deltaMs: Long) : DropSyncState
    data class BestEffort(val reason: BestEffortReason) : DropSyncState
    data class Overridden(val reason: OverrideReason) : DropSyncState
    data class Failed(val reason: FailureReason) : DropSyncState
    data object Cancelled : DropSyncState
}
```

**Ausfuehrungsregeln (festzuschreiben, per Test):**

1. **Eine aktive Sitzung.** Nur der Koordinator plant; `DropRestViewModel`
   wird zur duennen UI-Fassade (Gate-Anzeige + Start/Abbrechen).
2. **Deadline-Monotonie.** Alle Zeitrechnungen gegen
   `clock.elapsedRealtimeMs()`; `delay()` ist Transport, nie Wahrheit.
3. **Nutzer schlaegt Plan.** Jedes fremde Playback-Ereignis waehrend des
   Plans beendet ihn sichtbar (Token-Ausnahme fuer die eigene Landung).
4. **Keine stillen Fehlschlage.** Jeder Pfad endet in einem Zustand oder
   Event, das die UI zeigen kann (Grund als Enum, Text in `strings.xml`).
5. **Best Effort ist ein Zustand, kein Unfall.** Route-Wechsel, unbekannte
   Latenz oder verpasster Drop erzeugen `BestEffort`/`Failed`, nie ein
   stilles Danebenliegen.
6. **Generation bleibt.** Der bestehende `PlaybackGeneration`-Token wird
   beibehalten und auf alle Ausfuehrungspfade angewendet.
7. **Lebensdauer am Prozess, nicht am Screen.** Ein laufender Plan haelt den
   Foreground-Service (MP-12); der Koordinator ist app-weit und ueberlebt
   Screen-Wechsel. Der Service zeigt die projizierte Restzeit und die
   Plan-Aktionen.
8. **Vorladen statt Puffern.** Fuer die Landung wird der Work-Titel
   vorgeladen (Media3-1.11-Preloading, `setPreloadConfiguration`); der
   DIRECT_TO_DROP-Sprung nutzt `SeekParameters.EXACT`. Beides zusammen
   reduziert die Startunsicherheit, die MP-3 heute nur wegkorrigiert.

**Zeitplanung konkret (MP-3):**
```
targetElapsed = now + startAfterDelayMs           // monoton, einmal
while (remaining > WINDOW) { delay(min(remaining - WINDOW, 250ms)) }  // grob
while (now < targetElapsed - 30ms) { /* busy-arme kurze Warteschleife */ }
land()                                            // + optionale Wake-Lock-Spanne
```
Kein Exact-Alarm: `USE_EXACT_ALARM` ist fuer Fitness-Apps policy-seitig
nicht vorgesehen, und der Foreground-Service deckt den Fall ab (siehe 0.1).

**Aufraeumen im Umfeld (klein, aber Voraussetzung):**
`TimerEngine.markRunning` setzt die Startzeit (MP-11); der DROPSYNC-Zweig
von `addTime` wird entweder unterstuetzt (Landung verschieben, MP-15) oder
die UI bietet ihn dort nicht an (MP-6); `DropRestRequestBus` bekommt einen
app-weiten Konsumenten (MP-1).

## A.4 Zielbild UI: Workout-Konsole mit sichtbarem DropSync

Das UI-Umbauhandbuch beschreibt das Ziel bereits (Abschnitte 6-11, 17-18).
Hier die auf den Ist-Code zugeschnittene Umsetzung:

**1. Train-Konsole (TrainScreen.kt).**
- `WorkoutConsoleUiState` mit den Modi `IDLE / SET_ENTRY / REST_RUNNING /
  GO_CUE / EXERCISE_DONE` einfuehren (UI-Handbuch 4.1); die `when`-Struktur
  ersetzt die heutige Abfolge aus Satz-Karte + Sensor-Karte + Waveform +
  Verlauf (`TrainScreen.kt:169-375`).
- Genau **eine** Primaeraktion pro Zustand: Satz fertig (SET_ENTRY) bzw.
  Pause/Plan abbrechen (REST_RUNNING).
- Der Sensor-Status wandert in eine Kopfzeile (Chip + Qualitaet + Puls),
  nicht in eine konkurrierende Karte unter der Eingabe.

**2. Rest-Console als gemeinsamer Hero (ersetzt `RestConsole`, `TrainScreen.kt:597-663`).**
- Kopf: `REST` oder `DROPSYNC` (Label + Lime nur im aktiven DropSync).
- Zeit gross; darunter bei DropSync:
  `„Track Name" · Drop 2 · Ziel in 01:27` und Statuschips
  `Audio vorbereitet` / `Timing stabil` / `BEST EFFORT`.
- Aktionen: bei REST `+15 s` + `Pause beenden`; bei DROPSYNC
  `Plan abbrechen` + `Pause beenden`; bei Override ein erklaerender Hinweis.
- Go-Zustand als kurzes Overlay in der Konsole (`GO` + naechster Satz +
  `Drop gelandet`), keine Navigation.

**3. Satz-Eingabe (Hero).**
- Rep-Zahl gross, darunter die **Quelle**: `AUTO`, `MANUELL KORRIGIERT`,
  `MANUELL`, `SENSOR GETRENNT` (UI-Handbuch 7.4). Der Live-Zaehler zieht aus
  der Sensor-Karte in den Hero um (RC-5).
- `+/-`-Stepper bleiben (vorhanden, `TrainScreen.kt:527-585`), Gewicht bleibt
  kommatolerant (bereits gefixt).
- Nach dem Loggen: Haptik + Undo-Snackbar (Undo existiert fuer Sätze noch
  nicht — siehe P2).

**4. Drop-Auto-Schalter.**
- Nicht mehr in der Satz-Karte, sondern in der Rest-Console bzw. im
  Rest-Dialog (UI-Handbuch 17): `Rest 90 s · DropSync` mit `Aendern`.
- Ist DropSync nicht bereit (keine Work-Playlist/Marker), steht der Grund
  **direkt am Schalter**, nicht erst nach dem Speichern.

**5. Mini-Player und Now-Playing.**
- Mini-Player: `DROP BEREIT`-Badge mit Restzeit bis zum Drop; waehrend
  Armed `Next` -> `Details` (UI-Handbuch 11.3); Skip schuetzen (11.4).
- Now-Playing: Marker-Legende, Marker-Tap-Sheet mit
  `Als DropSync-Ziel waehlen`, Feinkorrektur `-100/-10/+10/+100 ms`
  (UI-Handbuch 14.3-14.5). Das ist der Ort, an dem der Nutzer den Drop
  hoert und festlegt — heute fehlt beides.

**6. Statussprache und A11y.**
- Nur Zustaende zeigen, nie Technik (`DROP BEREIT`, nicht `ARMED`).
- TalkBack: `stateDescription` am DropSync-Block ("DropSync bereit. Track X.
  Marker Drop 2. Ziel in 1 Minute 27 Sekunden."), Timer-Ansage nicht
  sekundenweise fluten.
- Farben nie allein: `BEST EFFORT` bekommt Text + Icon, nicht nur Amber.

---

# Teil B — BLE/IMU-Rep-Zaehlung

## B.1 Ist-Stand (verifiziert)

```
M5StickC Plus2
  -> BLE Notify (fee1), Poll-Fallback fuer HyperOS (BleSensorProvider.kt:422-551)
  -> BatchDedupTracker + JitterBuffer (6x20 ms)  (:119-120)
  -> SensorSample-Flow (SharedFlow, Kapazitaet 64, tryEmit)  (:107-108)
  -> ActiveSetController (Countdown, Engine, Puffer)  (domain/sensor/ActiveSetController.kt)
  -> ExerciseEnginePipeline
       SignalChain (OneEuro, Envelope, optionale Madgwick-Rotation, ZUPT-Bias)
       -> PeakDetector (adaptiv, kalibrierte Schwelle theta)
       -> RepCounter (Zweiphasen-Pending, Accel-Voting optional, Template-Pool)
            -> TemplateMatcher (DTW, Pool 5)
            -> PhaseValidator
            -> QualityScorer
  -> TrainViewModel (Live-Zahl, Waveform, Lernpfad, Shadow-Recorder)
```

Das ist eine ernsthafte, gut getestete Pipeline (`:domain:sensor` 127 Tests,
`ExerciseEnginePipelineIsolationTest`, `RepPipelineTest`, `ZuptDetectorTest`,
`OrientationTrackerTest`, `CorpusSweepHarness`). Die Befunde unten betreffen
**Betrieb, Sichtbarkeit und Belegbarkeit**, nicht die Signalmathematik.

## B.2 Befunde

<a name="rc-1"></a>
### RC-1 — HOCH — Zaehlpipeline laeuft auf dem Main-Thread

**Beleg:** `ActiveSetController` wird mit `scope = viewModelScope` erzeugt
(`TrainViewModel.kt:607-614`); der Sample-Collector startet dort
(`ActiveSetController.kt:145`) und ruft `engine.processSample(...)` direkt
(`:177-191`). `viewModelScope` laeuft auf `Dispatchers.Main.immediate`.
Zusaetzlich laeuft der Waveform-Collector im `TrainViewModel.init` auf
denselben Dispatcher (`TrainViewModel.kt:695-706`) und allokiert je Sample
ein neues `FloatArray` (`:585-595`).

**Wirkung:** Pro Sample laufen Filter, ZUPT und Peak-Erkennung auf dem
UI-Thread; bei jedem Peak zusaetzlich DTW-Template-Matching,
Phasenvalidierung und Qualitaetsbewertung. Bei 50 Hz ist das eine dauerhafte
Last auf genau dem Thread, der Waveform, Buttons und Ticker bedient. Es gibt
keinen Messwert, der das entlastet.

**Fix:**
1. Verarbeitung auf `dispatchers.default` (eigener Scope im Controller oder
   `flowOn`), UI-Zustaende weiterhin ueber `MutableStateFlow` (thread-safe).
2. Waveform auf Anzeigetakt drosseln (z. B. 20-30 Hz) oder frame-synchron
   lesen; Ringpuffer behalten, Snapshot nur bei Bedarf.
3. Messung: Frame-Timing im Train-Tab waehrend eines gezaehlten Satzes
   (Macrobenchmark oder `gfxinfo`), vorher/nachher.

**Verifikation:** Dispatcher-Assert-Test ("Engine laeuft nicht auf Main");
Jank-Messung auf Geraet als Abnahme.

<a name="rc-2"></a>
### RC-2 — HOCH — Rohdaten-Recorder schreibt in jedem Build

**Beleg:** `ShadowRecorderModule` bindet `JsonlShadowSessionRecorder` ohne
BuildType-Gate (`feature/workout/.../shadow/di/ShadowRecorderModule.kt:16-22`).
Die Implementierung schreibt `session_start`, je Satz `set_window` + **alle
Rohsamples** inkl. `axis`, `bias`, `theta` nach
`getExternalFilesDir()/recordings/<session>.jsonl`
(`JsonlShadowSessionRecorder.kt:39-89`, `:118-128`). `TrainViewModel` startet
die Session im `init` (`:672`) und zeichnet bei jedem `logSet` auf
(`:302-339`). Die Doku beschreibt das als gewollt ("der JSONL-Recorder ist im
normalen Build aktiv", `tools/golden_shadow_corpus/README.md:86-88`) und
nennt ~0,4 MB je Minute. Ein Aufraeumer oder Deckel existiert nicht.

**Wirkung:** Die ausgelieferte App sammelt dauerhaft Bewegungsrohdaten im
app-spezifischen externen Verzeichnis (per USB/Dateimanager zugaenglich,
nicht durch Scoped Storage geschuetzt) — ohne Deckel, ohne Rotation, ohne
Loeschweg. Das ist Speicherlast und ein Datenschutzthema (Bewegungsprofile
plus Kalibrierdaten), auch wenn nichts das Geraet verlaesst. Fuer die
Gate-11b-Kampagne ist der Recorder notwendig — fuer den Alltag nicht.

**Fix:**
1. Recorder an den Build-Typ binden (`debug`/`benchmark` echt, `release`
   NoOp) **oder** an einen expliziten Diagnose-Schalter in den Einstellungen
   mit sichtbarem Hinweis.
2. Aufraeumer: Rotation (N Dateien / X MB), Loeschaktion in den Einstellungen,
   Session-Ende schliesst sauber (existiert).
3. Fuer die Abnahme: Debug-Build mit eingeschaltetem Recorder, Anleitung
   bleibt wie in `tools/golden_shadow_corpus/README.md`.

**Verifikation:** Release-Build enthaelt keinen Schreibpfad (Grep auf
`recordings` im gemergten Release/Code-Shrinker-Output); Debug-Schalter-Test;
Groessendeckel-Test.

<a name="rc-3"></a>
### RC-3 — HOCH — Gate 11b steht aus; Genauigkeit ist unbelegt

**Beleg:** Design 11b fordert fuenf Freigabe-Szenarien auf echter Hardware
(`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md:570-586`);
`tools/golden_shadow_corpus/README.md:72-80` listet sie mit
Manifest-Tags und Abnahmekriterien (Exact-Match 100 %, MAE 0, mindestens 3
unabhaengige Sessions). Die Werkzeuge sind fertig
(`tools/shadow_harness.py`, `recofit_bootstrap.py`, `mmfit_bootstrap.py`,
`CorpusSweepHarness`), es fehlen ausschliesslich Aufnahmen.
ADR-0014 haelt fest, dass die Pipeline entgegen der Shadow-Absicht live
zaehlt — ohne Freigabe.

**Wirkung:** Jede Aussage "zaehlt zuverlaessig" ist unbelegt. Zusaetzlich ist
der live aktive Accel-Kanal nur synthetisch belegt (RC-15), und
`orientationTrackingEnabled` bleibt bis zur Freigabe aus
(`ExerciseEnginePipeline.kt:38-39`). **Korrektur zur Erstfassung:**
`accelEnabled` ist nicht aus — ADR-0017 aktiviert ihn profilgesteuert
(RC-9).

**Fix — konkreter Kampagnenplan (integriert in `docs/HARDWARE_TESTPLAN.md`):**
1. Recorder-Schalter (RC-2) bauen; Debug-Build installieren.
2. `HARDWARE_TESTPLAN.md` Teil A abarbeiten: A1-A5 (die fuenf
   Freigabe-Szenarien), je Szenario ≥ 3 unabhaengige Sessions, Handnotizen
   direkt nach jedem Satz. A6 (Korrektur/D3) und A7-A9 (Abbruch, Akku,
   Reichweite) laufen als Zusatzszenarien mit.
3. Zusatzszenarien aus den Werkzeug-Befunden ergaenzen (0.2):
   **A10 Befestigung/Position variieren** (Sensor-Displacement-Literatur:
   "Dealing with the Effects of Sensor Displacement", Sensors 2014; ExerSense,
   Sensors 2020), **A11 Stoergesten/Re-Rack** duerfen nicht zaehlen
   (MindArc #2: False Positives durch Handgesten), **A12 Live-vs-Replay**
   je Satz (RC-16).
4. Vorher die dortige Flag-Aussage korrigieren (DOC-1) und RC-15 ergaenzen:
   Szenario `calibrated` einmal mit und einmal ohne Accel-Voting sowie mit
   dem Sweep-Parametersatz rechnen.
5. Harness laufen lassen; Abweichungen einzeln dokumentieren und nach
   Mechanismus klassifizieren (RC-17); Ergebnis in
   `docs/STATUS_FORTSCHRITT.md` als Freigabe-Protokoll.
6. Danach Entscheidung: `orientationTrackingEnabled` freigeben oder nicht;
   Accel-Konstanten aus dem Sweep nachziehen; erst dann die Pipeline als
   freigegeben bezeichnen (ADR-0014 bleibt bis dahin die formal korrekte
   Einordnung).

**Verifikation:** Harness-Report mit Exact-Match-Rate und MAE je Szenario;
Protokolleintrag; Doku-Abgleich (DOC-1) vor dem Lauf.

<a name="rc-4"></a>
### RC-4 — HOCH — Stille Verweigerung und stummes 0-Ergebnis

**Beleg:** `startCountedSet` hat vier stille Guards
(`TrainViewModel.kt:717-723`): Phase, Profil, Verbindung, Device-ID.
`stopCountedSet` kopiert nur bei `counted > 0` (`:730-736`) — bei 0 passiert
nichts. Der Start-Knopf traegt bei fehlender Kalibrierung nur das Label
"Zuerst kalibrieren" (`TrainScreen.kt:891-912`), ohne Aktion. Die
Plausibilitaets-Zweitmeinung existiert (`TrainViewModel.kt:648-654`) und wird
angezeigt — aber nur, wenn der Nutzer die Zahl vorher nicht angefasst hat.

**Wirkung:** Der Nutzer steht vor einem Knopf, der nichts tut, oder vor
einer leeren Rep-Zahl ohne Erklaerung. Das ist die haeufigste
Enttaeuschung im Live-Zaehlfluss.

**Fix:**
1. `LiveCountPanel` bekommt einen expliziten UI-Zustand mit Grund:
   `KEIN_PROFIL` (CTA -> Wizard), `SENSOR_NICHT_VERBUNDEN`,
   `SENSOR_STREAMT_NICHT`, `SIGNAL_UNRELIABLE`, `BEREIT`.
2. Nach `stop` mit 0: Hinweis "0 erkannt" + Grund-Hinweis (Signalqualitaet,
   Plausibilitaet) + CTA "Manuell eintragen" oder "Kalibrierung pruefen".
3. Start-Knopf nur aktiv, wenn alle Vorbedingungen sichtbar erfuellt sind;
   sonst zeigt er den fehlenden Grund.

**Verifikation:** State-Tests je Grund; Compose-Test "Knopf zeigt Grund";
Screenshot der Zustaende.

<a name="rc-5"></a>
### RC-5 — HOCH (UI) — Der Live-Zaehler ist nicht das Zentrum der Konsole

**Beleg:** Der Zaehler lebt in der Sensor-Karte (`TrainScreen.kt:790-800`,
`:881-961`), das Rep-Feld im Satz-Hero (`:242-245`); die Quelle der Zahl
(AUTO/MANUELL/KORRIGIERT/GETRENNT) fehlt vollstaendig. Design 8.1 will die
grosse Rep-Zahl im Hero, die Korrektur als Hauptpfad ohne Stick.

**Fix:** Rep-Hero mit grosser Zahl, Quelle darunter, `+/-` als schneller
Korrekturpfad (vorhanden); Sensor-Karte auf Kopfzeile reduzieren;
Live-Waveform unter den Hero. Der Wechsel Zaehlen -> Loggen wird ein
einziger Fluss (Start -> Countdown -> Zahl -> Stopp -> Korrektur -> Satz
fertig) statt zweier getrennter Karten.

<a name="rc-6"></a>
### RC-6 — MITTEL — Lernpfad rechnet auf dem Main-Thread und lernt stumm

**Beleg:** `logSet` startet `viewModelScope.launch` (Main) und ruft
`learnFromTrace` (`TrainViewModel.kt:293-339`, `:375-414`), das
`CalibrationRefiner.refine` ausfuehrt (`CalibrationRefiner.kt:45-101`) —
inklusive mehrfachem Durchlauf ueber alle Samples und einer
Revalidierung durch die echte Pipeline (`:119+`). Das
`CalibrationViewModel` macht denselben Aufwand seit dem P1-Fix bewusst auf
`dispatchers.default` (`CalibrationViewModel.kt:112-125`) — der Lernpfad
nicht. Ergebnis (Kandidat gespeichert, Rollback, verworfen) geht nur ins Log.

**Fix:** `withContext(dispatchers.default)` um den Refiner; Ergebnis als
Event fuer ein Diagnose-Panel ("Profil verfeinert (Revision N)" / "Rollback"
/ "Lernen uebersprungen: Signal unplausibel"); Dispatcher-Assert-Test.

<a name="rc-7"></a>
### RC-7 — MITTEL — Kein Zaehlqualitaets-Bild

**Beleg:** Es gibt reichlich Diagnose, aber keine Anzeige:
`framesProcessed/framesRejected`, `largeGapCount`, `zuptBiasUpdates`,
`zuptAbortedPending`, `estimatedSampleRateHz`, `checkPlausibility()`
(`ExerciseEnginePipeline.kt:167-224`, `:346-361`); `SensorHealth`
(duplicates/missed/parse errors/jitter drops/largest gap) im Provider
(`BleSensorProvider.kt:641-653`); Transport/MTU (`:145-172`). Design 8.4
will "Jitter und Dedup Diagnose Werte optional hinter Entwickler Schalter".

**Fix:**
1. Diagnose-Abschnitt in den Einstellungen (hinter Entwickler-Schalter):
   Rate, Transport (Notify/Poll), MTU, Drops, ZuPT-Zaehler, letzte
   Plausibilitaet.
2. Nach jedem gezaehlten Satz ein kompakter Report (Snackbar/Sheet):
   erkannt X, Qualitaet Y, Rate Z Hz, Aussetzer N, ZuPT-Korrekturen M.

**Verifikation:** Werte gegen `CorpusSweepHarness`/JSONL nachrechenbar;
Test "Report erscheint nach Stop".

<a name="rc-8"></a>
### RC-8 — MITTEL (UI) — Kalibrier-Wizard ist eine Textliste

**Beleg:** `CalibrationWizardScreen` zeigt Titel, Anweisung, Sample-Zaehler,
Progress-Balken und "Weiter" (`:65-188`); keine Live-Waveform, kein
Rep-Feedback waehrend der Stufen, kein Wiederholen einer Stufe, kein
Einstieg aus der Uebungszeile. Fehler werden als Text gemeldet
(`CalibrationUiError`), aber ohne Handlung ("Stufe wiederholen").

**Fix:** Wizard 2.0 (Design 8.1/Phase 4):
- Stufen-Stepper (Ruhe -> 1 Rep -> 5 Reps -> 3 langsam -> Review).
- Live-Signal (vorhandene Waveform-Komponente) + "Reps erkannt: n".
- Fehlergrund + `Stufe wiederholen`; `Weiter` nur bei erfuellter Stufe.
- Review erklaert die Qualitaet (was gut/schlecht ist und was das fuer die
  Zaehlung bedeutet) statt nur Prozent.
- Einstieg "Kalibriert fuer FlowRep #A3 - neu kalibrieren" direkt an der
  Uebungszeile (Design 8.1).

<a name="rc-9"></a>
### RC-9 — MITTEL — Rollout-Status der Kanaele ist inkonsistent dokumentiert

**Beleg (Korrektur zur Erstfassung):** `orientationTrackingEnabled` ist
bewusst aus (`ExerciseEnginePipeline.kt:38-39`; Rollout-Kommentar
`ActiveSetController.kt:121-124`). **`accelEnabled` ist dagegen nicht aus:**
`ActiveSetController` setzt `accelEnabled = profile.accelVotingAvailable`
(`:119`), und ADR-0017 hat diese Kopplung ausdruecklich entschieden — der
Kanal laeuft live, sobald die Kalibrierung eine Schwelle > 0 gemessen hat.
Altprofile (Schema < 5) laufen korrekt ohne Voting
(`SensorModels.kt:110-124`). ZUPT ist default an
(`ExerciseEnginePipeline.kt:45`).

**Die eigentliche Luecke ist damit eine andere:** Die Accel-Konstanten
(`ACCEL_PEAK_FRACTION`, `ACCEL_NOISE_MARGIN`, `ACCEL_PEAK_WINDOW_S`) sind
laut ADR-0017 ("Folgen") nur an synthetischen Signalen gesetzt und an echten
M5StickC-Traces ungeprueft — und der Kanal ist bereits live. `HARDWARE_TESTPLAN.md`
Teil A behauptet dagegen weiter, beide Flags blieben aus (DOC-1).

**Fix:**
1. `HARDWARE_TESTPLAN.md` Teil A korrigieren: `accelEnabled` ist
   profilgesteuert aktiv, `orientationTrackingEnabled` bleibt aus.
2. Die Accel-Konstanten in den Gate-11b-Korpus aufnehmen (ADR-0017 nennt das
   als offenen Punkt): Szenario `calibrated` einmal mit und einmal ohne
   Accel-Voting rechnen lassen und die Deltas vergleichen.
3. `orientationTrackingEnabled` erst nach dem Korpus-Lauf entscheiden
   (Sweep-Parameter existiert, A/B im `CorpusSweepHarness`).
4. ZUPT im Protokoll explizit mitfuehren (er ist heute unbemerkt aktiv).

<a name="rc-10"></a>
### RC-10 — MITTEL — Zwei Collectoren, stiller Sample-Verlust

**Beleg:** `_samples` ist ein `MutableSharedFlow(extraBufferCapacity = 64)`
mit `tryEmit` (`BleSensorProvider.kt:107-108`, `:119`); Verbraucher sind der
Waveform-Collector (`TrainViewModel.kt:695-706`) und der
`ActiveSetController` (`:145`). Zusaetzlich wechselt
`SwitchingSensorProvider` per `flatMapLatest` zwischen BLE und Fake
(`SwitchingSensorProvider.kt:51-52`), mit bewusst kommentierter
Umschaltluecke. Volle Puffer verwerfen still; der JitterBuffer zaehlt nur
seine eigenen Drops.

**Fix:** Einen zentralen Fan-out mit definierter Backpressure bauen
(z. B. `Channel`/`SharedFlow` mit `DROP_OLDEST` und explizitem
Drop-Zaehler), der beide Verbraucher speist; den Zaehler in `SensorHealth`
aufnehmen und im Diagnose-Panel zeigen. Alternativ den Waveform-Konsum aus
dem Controller speisen (ein Collector weniger).

<a name="rc-11"></a>
### RC-11 — NIEDRIG — Waveform-Allokation und Recomposition je Sample

**Beleg:** `pushWaveformSample` allokiert bei jedem der ~50 Samples/Sekunde
ein neues `FloatArray` (`TrainViewModel.kt:585-595`) und veroeffentlicht es
als State (`:594`); `SensorWaveform` liest `samples: FloatArray` direkt
(`TrainScreen.kt:976-1038`), d. h. jede Emission recomponiert den Bereich.

**Fix:** Ringpuffer behalten, Veroeffentlichung auf Anzeigetakt drosseln
und/oder die Waveform im Zeichenblock aus einem stabilen Array lesen
(`drawWithCache` existiert bereits). Messbar ueber `gfxinfo`/Frame-Metriken.

<a name="rc-12"></a>
### RC-12 — NIEDRIG — Geraetetaste ohne Funktion

**Beleg:** `deviceEvents` wird vom Provider emittiert
(`BleSensorProvider.kt:110-111`, `:569-584`), aber **von keinem Feature
konsumiert** (Grep ueber `src/main`: nur Provider, Fake, Interface).
Design 4.3 beschreibt die M5-Taste als "Kalibrierung pruefen, sonst Banner".

**Fix (Produktentscheidung):** Taste als "Satz starten/stoppen" verwenden
(ideal im Gym, Handschuhe) oder als "Marker setzen". Mindestens: Ereignis im
Diagnose-Panel sichtbar machen, damit die vorhandene Hardware nicht tot ist.

<a name="rc-13"></a>
### RC-13 — MITTEL — Rollback-Puffer wird beim Uebungswechsel nicht geleert

**Beleg:** `recentDiffs` ist ein ViewModel-Feld und wird nur bei
`learnFromTrace` gefuellt (`TrainViewModel.kt:404-413`) und beim erfolgreichen
Rollback geleert. `selectExercise` ruft `resetShadowEngine()`, das aber nur
`liveRepCount = 0` setzt (`:782-784`). `ProfileLearningPolicy.shouldRollback`
prueft die letzten zwei Abweichungen (`ProfileLearningPolicy.kt:23-27`) —
ohne Bezug auf Uebung oder Geraet.

**Wirkung:** Zwei schlechte Saetze bei Uebung A koennen den Rollback des
Profils von Uebung B ausloesen, sobald dazwischen gewechselt wird. Der
Rollback trifft dann die falsche Revision.

**Fix:** `recentDiffs` in `selectExercise` (und beim Geraetewechsel) leeren;
alternativ den Puffer an `exerciseId + deviceId` binden. Test: Wechsel
zwischen zwei Uebungen loest keinen fremden Rollback aus.

<a name="rc-14"></a>
### RC-14 — NIEDRIG — Peak-Blitz nutzt eine eigene Heuristik

**Beleg:** Der Blitz im Train-Tab stammt aus einer eigenen
Beschleunigungs-Heuristik (`PEAK_DELTA_G = 0.4`, `PEAK_MIN_G = 1.3`,
`TrainViewModel.kt:695-706`) und nicht aus den `RepEvent`s der Pipeline.
Anzeige und Zaehler koennen daher auseinanderlaufen (Blitz ohne Rep, Rep
ohne Blitz).

**Fix:** Den Blitz an `repEvents` bzw. `liveCountedReps`-Aenderungen haengen
oder die Heuristik entfernen. Kosten: ein Collector mehr (RC-10).

<a name="rc-15"></a>
### RC-15 — MITTEL — Accel-Konstanten sind live, aber nur synthetisch belegt

**Beleg:** ADR-0017 ("Folgen") sagt es selbst: die Konstanten der
Accel-Kalibrierung sind "an synthetischen Signalen gesetzt und an echten
M5StickC-Traces noch nicht geprueft". Gleichzeitig aktiviert
`ActiveSetController` den Kanal fuer jedes Profil mit gemessener Schwelle.
Der `CorpusSweepHarness` kann die Kanaele bereits variieren.

**Fix:** Die drei Konstanten in den Gate-11b-Lauf aufnehmen (Teil des
Freigabe-Protokolls), Ergebnis dokumentieren und erst danach entscheiden, ob
die Kalibrierungsregel (35 % des Medians, vierfaches Ruhe-Rauschen) bleibt.
Das ist der wichtigste inhaltliche Zusatz gegenueber der Erstfassung dieses
Plans.

<a name="rc-16"></a>
### RC-16 — HOCH — Live- und Replay-Pfad werden nie gegeneinander gemessen

**Evidenz:** Das vergleichbare Projekt BarSpeed hat gemessen, dass die
Live-Rekonstruktion auf denselben Wiederholungen **53-59 % der
Batch-Reichweite** liest und dass alle Live-Gates gegen diese verzerrte
Groesse kalibriert waren (Issue #115, offen). Das ist kein Zaehlfehler,
sondern eine systematische Pfad-Differenz — genau die Klasse, die auch
FlowRep treffen kann: Live laufen BLE-Luecken, Sample-Rate-Schaetzung,
ZUPT-Bias-Nachfuehrung und Threading anders als im Replay derselben Daten.

**Beleg im Code:** Der Harness vergleicht heute nur die Live-Zaehlung
gegen die Handzaehlung (`tools/shadow_harness.py`); die Rohsamples werden
zwar mitgeschrieben (Recorder, inkl. Achse/Bias/Schwellen), aber ein
Vergleich "Live-Zaehlung vs. Replay derselben Samples durch eine frische
Pipeline" existiert nicht. Die Bausteine dafuer sind vorhanden
(`CorpusReplayDeterminismTest` beweist Determinismus,
`CorpusSweepHarness` spielt Korpora ab).

**Fix:**
1. Harness um `replay_count` je Satz erweitern: Samples durch eine frische
   Pipeline (Profil aus dem JSONL) rechnen und mit `liveCountedReps`
   vergleichen. Abweichungen sind eine eigene Klasse, kein Zaehlfehler.
2. Bei Abweichung die Ursache eingrenzen: Sample-Rate, ZUPT, Pending-Timeout,
   Accel-Voting, Filterzustand.
3. Nach der Kampagne den Replay-Pfad als CI-Regressionsgate nutzen (goldener
   Korpus mit erwarteten Counts), damit Pipeline-Aenderungen sichtbar
   bleiben — dieselbe Rolle wie BarSpeeds `FieldDataRegressionTest`.

**Verifikation:** Harness-Report mit beiden Spalten. Gegenbeweis: eine
absichtlich geaenderte Refraktaerzeit muss den Replay-Count aendern und den
Live-Count nicht — sonst misst der Vergleich nichts.

<a name="rc-17"></a>
### RC-17 — MITTEL — Ablehnungsgruende werden nicht klassifiziert und gezaehlt

**Evidenz:** BarSpeed klassifiziert die 27 verpassten Wiederholungen nach
Mechanismus (ROM-Floor 8, fehlender Drive-Run 7, Drift 6, stumm 3,
Startschwelle 3) und stellt fest, dass "27 Fehler" in Wahrheit wenige
Ursachen sind (Issue #94). Diese Zerlegung ist die Grundlage jeder
Verbesserung — ohne sie wird an der falschen Stelle geschraubt.

**Beleg im Code:** FlowReps Pipeline liefert `RepResult.rejectionReason`
als String (Template-Match, Phasenvalidierung, Qualitaet, Accel-Voting)
und Zaehler wie `zuptAbortedPending`, `largeGapCount`, `framesRejected` —
aber nichts davon wird aggregiert, gespeichert oder angezeigt. Der
Recorder protokolliert nur bestaetigte Reps, keine Ablehnungen.

**Fix:** Ablehnungsgruende als Enum fuehren, je Satz zaehlen und in JSONL +
Satz-Report aufnehmen. Im Freigabe-Protokoll eine Mechanismus-Tabelle je
Szenario fuehren, damit "5 Abweichungen" in ihre Ursachen zerfallen. Das
ergaenzt RC-7 um die entscheidende Dimension: nicht nur wie viele Reps
fehlen, sondern warum.

<a name="rc-18"></a>
### RC-18 — HOCH — Ermuedungsdrift gegen gleitenden Mittelwert (aus der Tiefenrecherche)

**Beleg:** `docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md`
Abschnitt 1.2 (R3). `RepCounter.trackForAdaptation()` ueberschreibt ab der
dritten Rep die kalibrierten Erwartungswerte mit dem gleitenden Mittelwert
(`RepCounter.kt:264-284`); `romScore`/`tempoScore` messen damit Konsistenz
mit der unmittelbaren Vergangenheit statt Qualitaet. Da Geschwindigkeit und
Amplitude im Satz monoton fallen (Velocity Loss 15-65 % ist der Normalfall,
Rodriguez-Rosell et al. R = 0,97), bestraft das ausgerechnet die letzten,
harten Reps — 45 % des Qualitaetsgewichts haengen daran.

**Fix:** Erwartungswerte aus einem Drift-Modell (Referenz der ersten Reps
plus Korridor) oder einseitige Scores (langsamer/kleiner = normal,
schneller/groesser als je zuvor = verdaechtig). Der Korpus liefert die
gemessene Driftkurve je Uebung. Vor allen Optimierungen (Stufe 2 der
Tiefenrecherche), weil es ein belegter Konstruktionsfehler ist.

<a name="rc-19"></a>
### RC-19 — MITTEL — Autokorrelation nur als Log, nicht als Quelle

**Beleg:** Tiefenrecherche 1.4 (R6). uLift und RecoFit nutzen
Autokorrelation im Zaehlpfad (0,61 mittlerer Fehler bzw. ±1 Rep in 93 %);
im Repo laeuft sie nur am Set-Ende und landet im Log. Die Zweitmeinung ist
inzwischen als `PlausibilityHint` sichtbar, aber die Periodenschaetzung
wird nicht fuer `expectedDurationMs`/Refraktaerzeit genutzt.

**Fix:** Periodizitaet als ehrlichere Quelle fuer die erwartete Rep-Dauer
verwenden (unabhaengig vom gleitenden Mittelwert aus RC-18). Fuer kurze
Saetze (< 150 Samples) bleibt sie stumm — das ist dokumentiert.

<a name="rc-20"></a>
### RC-20 — MITTEL — DTW- und Qualitaets-Schwelle sind global

**Beleg:** Tiefenrecherche 1.5 (R8) plus neue Per-Uebungs-Daten aus dem
Few-Shot-Volltext (`docs/research/2026-09-19-rep-counting-per-uebung.md`):
Schwellen, Gewichte, Bandbreite und Refraktaer-Anteil sind global
verdrahtet (`ExerciseEngineConfig.kt:18-19`, `TemplateMatcher.kt:119`,
`QualityScorer.kt:23-26`), obwohl sich die Uebungen stark unterscheiden
(Scott-Curl "saubereres Signal" laut Repo-Priors, Lat-Pulldown andere
Bewegungsebene).

**Fix:** DTW-Schwelle und Qualitaets-Schwelle in das Profil-Schema v5 -> v6
aufnehmen und im KNOWN_SET-Sweep der Kalibrierung mitbestimmen (dieselbe
Mechanik wie theta).

<a name="rc-21"></a>
### RC-21 — NIEDRIG — Template-Pool ist ein Oder-Gate (FIFO statt DBA)

**Beleg:** Tiefenrecherche 1.6 (R10). Der Pool haelt 5 rohe Fenster und
nimmt das Maximum — eine einzige aehnliche Rep genuegt; eine schlechte Rep
wandert mit. DBA (Filippou et al. 2023) mittelt Ausreisser heraus.

**Fix:** Nachrangig nach der Messung; Bausteine (`resample`, `normalize`,
`dtwSimilarity`) existieren.

<a name="rc-22"></a>
### RC-22 — HOCH — Beat-Snap rastet auf ein Gitter ohne Downbeat-Offset

**Beleg:** Tiefenrecherche 3.4 (D2). `MarkerSnapping` snapt auf ein
Beat-Raster ab 0 ms; der Code nennt das selbst Spekulation
(`MarkerSnapping.kt:19-21`). Bei 128 BPM ist ein halber Beat 234 ms — mehr
als das gesamte Audio-Latenzbudget. Der Snap kann damit heute mehr Fehler
einbringen als alle Latenzterme zusammen.

**Fix:** Downbeat-Offset bestimmen (aus der Analyse oder manuell) und im
Snap beruecksichtigen; Snap bleibt optional. Gehoert in Stufe 2 der
Tiefenrecherche (belegter Defekt). Umsetzungsvorschlag mit Recherche:
`docs/research/2026-09-19-downbeat-offset.md` (Low-Band-Phasenheuristik,
offline und ohne ML pruefbar; neuronale Tracker sind laut SMC-Befund
unzuverlaessig und widersprechen dem Projektgrundsatz).

## B.3 Zielbild Technik: messbar, sichtbar, belegbar

1. **Threading:** Signalverarbeitung verlaesst den Main-Thread (RC-1);
   Waveform wird auf Anzeigetakt gedrosselt (RC-11); ein Sample-Fan-out mit
   Drop-Zaehler ersetzt die zwei stillen Collectoren (RC-10).
2. **Datenschutz/Storage:** Recorder ist build-typ- oder schaltergesteuert,
   mit Rotation und Loeschaktion (RC-2). Release schreibt nichts.
3. **Belegbarkeit:** Gate-11b-Kampagne mit Protokoll (RC-3); danach
   Flag-Entscheidung (RC-9). Bis dahin sagt die UI ehrlich "Genauigkeit
   wird am Geraet abgenommen" statt es zu versprechen (die bestehende
   README-Formulierung ist hier Vorbild).
4. **Qualitaet sichtbar:** Diagnose-Panel + Satz-Report (RC-7), Lernpfad
   off-main und mit Event (RC-6).
5. **Fehlerfuehrung:** Jeder Start-Grund und jedes 0-Ergebnis hat einen
   sichtbaren Zustand und eine Handlung (RC-4).

## B.4 Zielbild UI: Train-Konsole und Kalibrierung

- **Rep-Hero** mit Quelle und Steppern (RC-5), Live-Zaehler dort, nicht in
  der Sensor-Karte.
- **Sensor-Kopfzeile** statt konkurrierender Karte: Verbindung, Qualitaet,
  Puls, Akku (Batterie lesen existiert: `BleSensorProvider.readBatteryPercent`,
  wird aber nirgends angezeigt).
- **Start/Stop-Fluss** mit expliziten Zustaenden und Gruenden (RC-4),
  Countdown 3-2-1 (vorhanden), Stopp -> Zahl -> Korrektur -> Satz fertig.
- **Kalibrier-Wizard 2.0** (RC-8) mit Live-Signal und wiederholbaren Stufen.
- **Stille vor Vermutung (Trust-Regel, aus BarSpeed #145):** Der Live-Zaehler
  ist Beratung, nie Wahrheit. Keine Sprachansage und kein Speichern einer
  ungeprueften Zahl; bei Unsicherheit schweigt die App lieber, als eine
  falsche Zahl zu zeigen (eine verpasste Zahl ist nachholbar, eine falsche
  nicht). FlowRep ist mit der D3-Regel (nur editierte Reps zaehlen als
  Wahrheit) bereits auf dieser Linie — sie wird hier explizit gemacht und
  auf eine spaetere Sprachansage ausgeweitet.
- **Diagnose** hinter Entwickler-Schalter (RC-7); keine Technik im
  Normalpfad.
- **A11y:** Zaehler bleibt `liveRegion` (vorhanden, `TrainScreen.kt:929-942`),
  Start-Gruende werden vorgelesen, Farben nie allein.

---

# Teil C — Querschnitt

## C.1 Tests und Gates

- Jeder P0/P1-Fix bekommt einen Gegenbeweis-Test (Projektstandard:
  "Gegenbeweis gefahren, zurueckgerollt").
- Neue Dispatcher-Asserts fuer RC-1/RC-6 (Engine/Refiner nicht auf Main).
- End-to-End-Test des Drop-Auto-Pfads mit echter `TimerEngine` und
  Fake-Playback (MP-1), nicht nur Bus-Zaehlung.
- Geraeteabnahmen getrennt fuehren (P4): Drop-Timing P95 je Route (MP-3),
  Gate 11b (RC-3), Jank-Messung (RC-1).
- Release-Gate: kein `recordings`-Schreibpfad im Release (RC-2).

## C.2 Doku

- README/STATUS: Die Zeile "Drop-Auto verdrahtet" praezisieren (verdrahtet,
  aber wirkungslos bis MP-1 gefixt ist) — sonst wiederholt sich der
  Fehlermodus aus `VERBESSERUNGSPLAN.md` ("Statustabelle sagt
  Abgeschlossen").
- **DOC-1 (`HARDWARE_TESTPLAN.md`):** Teil A behauptet, `accelEnabled` und
  `orientationTrackingEnabled` blieben aus — Ersteres widerspricht ADR-0017.
  B1/B2/B5 setzen einen Crossfade voraus, den es nicht gibt (B-AUD-5). Beide
  Stellen vor der naechsten Kampagne korrigieren, sonst prueft der Testplan
  Zusagen, die der Code nicht hat.
- **DOC-2 (ADR-0012):** beschreibt `crossfadeTo`/`ACTION_CROSSFADE_TO`; der
  Code hat `playSongAt`/`ACTION_PLAY_SONG_AT` und keinen Dual-Player mehr.
  ADR-Nachtrag oder Korrektur, damit die naechste Session nicht gegen den
  alten Vertrag baut.
- ADRs: MP-3/MP-5 beruehren ADR-0012 (Timing/Plan), MP-4 Stufe 2 braucht
  einen neuen ADR (Dual-Player/Crossfade, Media3-1.11-Preloading als
  Grundlage), RC-2 ist eine Datenschutz-/Storage-Entscheidung, RC-9/RC-15
  die Freigabe des Accel-Kanals.
- UI-Handbuch und Fusionsdesign bleiben die Zielbeschreibung; dieser Plan
  widerspricht ihnen nicht, er setzt sie um. Die einzige Stelle mit
  echtem Zielkonflikt ist der Fallback ohne Rest-Playlist (MP-16).

## C.3 Koordination

Die Regel aus `VERBESSERUNGSPLAN.md` Abschnitt 0 gilt: Vor jedem Paket die
Zeile auf `[~] in Arbeit (Session, Datum)` setzen; die beiden Schwerpunkte
sind gross genug fuer je eine Session. `:data:sensor`/`:domain:sensor` und
`:feature:player` nicht parallel von zwei Sessions anfassen.

## C.4 Forschungswerkzeuge (getestet am 19.09.2026)

Drei Werkzeuge wurden real getestet (Ergebnisse in 0.2). Empfohlene Nutzung
fuer die offenen Entscheidungen dieses Plans — keines wird zur
Laufzeit-Abhaengigkeit der App:

- **hyperresearch** — Evidenz-Vault fuer Sensor- und Audio-Entscheidungen.
  Getestet: `hpr scholar search "<frage>" -j` (OpenAlex/Crossref/DOAB ohne
  Key), `hpr init`, `hpr fetch <url>`, `hpr search`. Ohne Claude Code
  laufen die CLI-Befehle; die 16-Schritt-Pipeline nicht.
  Einsatz: Gate-11b-Literatur, Accel-Konstanten, Crossfade-Entscheidung;
  der Vault ist Markdown + SQLite und damit versionierbar (z. B. unter
  `docs/research/`).
- **orx (OpenResearch)** — Volltexte fuer arXiv-Papiere.
  Getestet: `orx paper <arXiv-ID> --full` (52 KB Volltext),
  `orx discover keyword|openalex`. Einsatz: Volltext-Beweise fuer ML-nahe
  Fragen; `discover` nur mit engen Queries (Relevanz in Tests schwach).
- **Agent-Reach** — GitHub-Issue-Recherche ueber `gh`.
  Getestet: `agent-reach doctor`, eigene Testsuite (589 bestanden, 3
  Umgebungsfehler), `gh search issues`. Einsatz: Fehlermechanismen
  vergleichender Projekte (Belege in RC-16/RC-17). Reddit/Twitter brauchen
  Cookies und sind optional.

**Regel:** Ergebnisse aus diesen Werkzeugen sind Rohmaterial, keine
Wahrheit. Jede Aussage, die in einen ADR oder eine Statustabelle wandert,
wird gegen den Code oder die Hardware geprueft — dieselbe Cite-Check-Logik,
die hyperresearch selbst anwendet.

---

# Teil D — Umsetzungsreihenfolge

## P0 — Sofort (Korrektheit, Datenschutz, tote Knoepfe) — ca. 2-3 Tage

| # | Paket | Befund |
|---|---|---|
| 1 | Drop-Auto: Bus an app-weiten Koordinator, REST/Landing-Reihenfolge fixen, Wirkungstest | MP-1 |
| 2 | Recorder build-typ-/schaltergesteuert + Release-Gate | RC-2 |
| 3 | Start-Gruende und 0-Ergebnis sichtbar machen; `+15 s` bei DropSync entfernen | RC-4, MP-6 |
| 4 | README/STATUS-Zeile "Drop-Auto" korrigieren | C.2 |
| 5 | Sofortschutz MP-12: DropRest beim Verlassen des Players nicht haengen lassen (Timer abbrechen oder Service starten) | MP-12 |

P0 macht keine neuen Features; es stellt her, dass vorhandene Schalter tun,
was sie versprechen, und dass die Auslieferung keine Rohdaten sammelt.

**Umsetzungsstand 19.09.2026:** P0 ist umgesetzt (Arbeitsbaum, kein Commit):
Bus an den Koordinator + drei Koordinator-Tests, Recorder-Gate mit ADR-0023,
sichtbare Start-Gruende/0-Hinweis/`+15 s`, DropRest-Sofortschutz mit
Logik-Test. Verifikation: `:feature:player` und `:feature:workout` Tests
gruen, `:app:assembleDebug` gruen, `spotlessCheck` gruen. Details in
`docs/STATUS_FORTSCHRITT.md` Abschnitt AO.

## P1 — Kurzfristig (DropSync funktionsfaehig + Threading) — ca. 1-2 Wochen

| # | Paket | Befund |
|---|---|---|
| 6 | `DropSyncCoordinator` + Zustandsmodell + `PlayerMessage`-Landung + Override-Erkennung + Preload | MP-2, MP-3, MP-5, A.3 |
| 7 | Landung Stufe 1: Mikro-Rampe, Ducking-Rampe, `crossfadeMs` verdrahten (D9) | MP-4, MP-9 |
| 8 | DropRest an den Foreground-Service (Notification, Cues, Prozessschutz) | MP-12 |
| 9 | Restzeit-Aenderungen in den Plan einrechnen; Resume behaelt den Titel | MP-15 |
| 10 | Rest-Console als gemeinsamer Hero + Mini-Player-Badge + Skip-Schutz | MP-6, MP-7 |
| 11 | Zaehlpipeline off-main + Waveform-Drosselung (Frame-Takt) + Sample-Fan-out | RC-1, RC-10, RC-11 |
| 12 | Lernpfad off-main + Ereignis + `recentDiffs` je Uebung/Geraet | RC-6, RC-13 |
| 13 | Drop-Auto persistieren (Default an) | MP-13 |
| 14 | Alle Marker je Work-Titel als Landungs-Kandidaten | MP-14 |

**Umsetzungsstand 19.09.2026 (P1, Arbeitsbaum, kein Commit):**

- [x] **P1-6 Koordinator + Zustandsmodell + `PlayerMessage`.** Neu:
  `DropSyncCoordinator` (App-Scope, ein Zustand `DropSyncState`),
  `DropSyncPlanner` (Kandidaten/Plan/Konfidenz), `DropRestSessionMonitor`
  (app-weiter DropRest-Monitor), `DropLandingArmer` (Media3-`PlayerMessage`
  auf der Audio-Uhr + Watchdog) mit dem Port
  `PlaybackRepository.armLanding/cancelLanding` und
  `landingEvents`. `RestMusicCoordinator` ist ersetzt; `DropRestViewModel`
  ist duenne UI-Fassade. Nutzer-Vorrang (Pause/Seek/Titel/Queue) ergibt
  `Overridden(reason)`; fehlende Armierung landet per Deadline-Fallback
  sichtbar als `BestEffort`. **Preload bleibt offen** (eigener Messpunkt,
  zurueckgestellt): `ExoPlayer.setPreloadConfiguration(PreloadConfiguration)`
  (Media3 1.11) laedt nur Items vor, die in der Queue NACH dem aktuellen
  stehen — unser Design setzt den Work-Titel erst im Moment der Landung
  (`setMediaItem`). Preload erfordert damit einen sichtbaren Queue-Eintrag
  oder einen zweiten Player (Stufe 2) und einen Geraete-Beleg.
- [x] **P1-7 Landung Stufe 1.** `crossfadeMs` aus der DSP-Konfiguration
  fliesst in den Planner (D9); der Armer blendet aus/ein (Equal-Power-
  Kurven ueber `Player.volume`, Mikro-Rampe 12 ms) — kein Dual-Player
  (ADR-0022 Stufe 2 bleibt Spike-pflichtig).
- [x] **P1-8 DropRest am Foreground-Service.** `DropRestViewModel` startet
  den `TimerService`; der `TimerService` traegt den DROPSYNC-Modus
  (Titel "DropSync", Aktionen "Plan abbrechen"/"Pause beenden", kein
  `+15 s`); der Monitor liegt app-weit im Koordinator. Der
  `onCleared`-Sofortschutz aus P0 ist damit hinfaellig (Screen-Wechsel
  toetet die Sitzung nicht mehr).
- [x] **P1-9 Restzeit-Aenderungen + Resume.** `+15 s` plant die Landung
  neu (Toleranz 750 ms gegen den linearen Countdown); Pause entwertet die
  Armierung, Resume armiert neu **ohne** die Queue zu setzen (Titel
  bleibt). Tests: "plus 15 Sekunden verschiebt die Landung", "Pause und
  Resume behaelt den Titel".
- [x] **P1-11 (Teil) Waveform-Drosselung.** Veroeffentlichung im Frame-Takt
  (33 ms) statt je Sample; Ringpuffer bleibt.
- [x] **P1-11 Sample-Fan-out (RC-10).** `SensorSampleFanout` (data/sensor):
  EIN Verteiler speist alle Verbraucher (Waveform, Zaehlpipeline,
  Kalibrierung) mit derselben Folge; bei Ueberlast verdrangt ein
  Ringpuffer das AELTESTE Sample (DROP_OLDEST) und zaehlt jede Verdrangung.
  Der Zaehler liegt als `SensorHealth.samplesDropped` vor (Anzeige folgt mit
  dem Diagnose-Panel P2-17); ohne Abonnenten wird nichts gepuffert und
  nichts gezaehlt. Tests: `SensorSampleFanoutTest` (Fan-out an zwei
  Verbraucher, DROP_OLDEST mit exaktem Zaehler, No-op ohne Abonnenten,
  reset).
- [x] **P1-11 Dispatcher-Assert-Test (RC-1).** `ActiveSetControllerTest`
  weist nach, dass der Sample-Collector auf dem uebergebenen
  Worker-Dispatcher laeuft (eigener Thread-Name), nie auf dem Main-Thread.
- [x] **P1-12 (Teil) Lernpfad off-main + je Uebung/Geraet.**
  `CalibrationRefiner.refine` laeuft auf `dispatchers.default`;
  `recentDiffs` ist nach `exerciseId:deviceId` geschluesselt (RC-13).
- [x] **P1-12 Lern-Ereignis fuer die UI (RC-6).** `ProfileLearningEvent`
  (domain/sensor/calibration) als Einmal-Ereignis aus dem Lernpfad:
  `Refined(revision)` / `RolledBack` / `SkippedImplausible` /
  `SkippedUnreliable`. Die Train-UI zeigt es als Snackbar (vorher nur Log);
  das Diagnose-Panel (P2-17) nutzt dieselbe Quelle. Test: unplausible
  Bestaetigung wird sichtbar gemeldet.
- [x] **P1-13 Drop-Auto persistiert (Default an).**
  `RestMusicSettingsRepository.dropAutoEnabled` in DataStore; der
  Train-Schalter liest/schreibt ihn (`Eagerly`, damit
  `startRestTimer().value` stimmt).
- [x] **P1-14 Alle Marker je Work-Titel.** Der Planner uebergibt alle
  aktiven Marker; Test "mehrere Marker — der naechste gewinnt".
- [x] **P1-10 Rest-Console als Hero + Mini-Player-Badge + Skip-Schutz.**
  Die Konsole (A.4) kennt den Modus: Kopf `REST`/`DROPSYNC`, grosse Zeit,
  darunter `Track · Marker · Ziel in mm:ss` (bei Landung am Pausenende
  tickt die Timer-Restzeit, beim DropRest die Marker-Projektion des
  Monitors), Statuschips `Audio vorbereitet` (nur bei echter Armierung,
  `Armed.audioPrepared`) und `Timing stabil` (nur EXACT), bei DROPSYNC
  zusaetzlich `Plan abbrechen` (Port `DropSyncStateSource.cancelPlan()`:
  Landung/Queue/Ducking zurueck, Pause laeuft weiter). Nach der Landung
  ein kurzes `GO`/`Drop gelandet`-Overlay. Mini-Player-Badge und
  Next-Sperre bei `Armed` sind umgesetzt. **Rest:** Now-Playing-
  Statuszeile unter der Waveform (MP-7-Detail).
- [x] **RC-1 (Teil) Zaehlpipeline off-main.** `ActiveSetController`
  verarbeitet Samples auf `workerDispatcher` (Default `Dispatchers.Default`,
  im Test der Test-Dispatcher).


## P2 — Mittelfristig (Belegbarkeit + Kalibrierung) — ca. 2-4 Wochen

| # | Paket | Befund |
|---|---|---|
| 15 | Gate-11b-Kampagne nach `HARDWARE_TESTPLAN.md` Teil A (inkl. DOC-1-Korrektur und A10-A12) | RC-3 |
| 16 | Accel-Konstanten im Korpus pruefen; danach `orientationTrackingEnabled` per A/B-Sweep entscheiden | RC-9, RC-15 |
| 17 | Diagnose-Panel + Satz-Report (Rate, Drops, ZuPT, Plausibilitaet) inkl. Ablehnungs-Mechanismen | RC-7, RC-17 |
| 18 | Live-vs-Replay-Vergleich im Harness + CI-Regressionsgate auf dem goldenen Korpus | RC-16 |
| 19 | Kalibrier-Wizard 2.0 (Live-Signal, wiederholbare Stufen, Re-Kalibrieren-Link) | RC-8 |
| 20 | Train-Konsole: Rep-Hero mit Quelle, Sensor-Kopfzeile, eine Primaeraktion | RC-5, A.4 |
| 21 | Now-Playing als Drop-Editor (Marker-Legende, Ziel waehlen, Feinkorrektur) | MP-7, A.4 |
| 22 | Peak-Blitz an die Engine-Events binden | RC-14 |
| 23 | Ermuedungsdrift-Modell statt gleitender Mittelwert (Stufe 2 der Tiefenrecherche) | RC-18 |
| 24 | Downbeat-Offset fuer den Beat-Snap bestimmen und einsetzen | RC-22 |
| 25 | Autokorrelation als Quelle fuer die erwartete Rep-Dauer | RC-19 |
| 26 | DTW-/Qualitaets-Schwelle pro Uebung (Profil-Schema v6) | RC-20 |
| 27 | Crossfade Stufe 2 (Dual-Player + Preload) — **entschieden am 19.09.**, ADR vor Baubeginn, Gegenargumente der Tiefenrecherche adressieren | MP-4, Entscheidung 4 |

**Umsetzungsstand 20.09.2026 (P2-17, Arbeitsbaum, kein Commit):**

- [x] **P2-17 Diagnose-Panel + Satz-Report (RC-7, RC-17).** Umgesetzt:
  - **Ablehnungs-Mechanismen (RC-17).** Neues Enum `RepRejectionReason`
    (ACCEL_VOTING, TEMPLATE_MATCH, PHASE_VALIDATION, QUALITY);
    `RepResult.rejection` traegt die Klassifizierung, der Freitext
    `rejectionReason` bleibt fuer Details. Die Pipeline zaehlt je Satz
    (`rejectionCountsSnapshot`, geleert mit `reset()`).
  - **Diagnose-Snapshot (RC-7).** `SetDiagnostics` (erkannt, Frames,
    Gaps, ZUPT, gemessene Rate, Signalqualitaet, Ablehnungen,
    Plausibilitaet) wird in `ActiveSetController.stop()` eingefroren
    (`lastDiagnostics`, geleert bei `start`/`abort`) und in den
    `SetTrace` uebernommen.
  - **Satz-Report.** Nach `stopCountedSet()` zeigt die Train-UI eine
    Snackbar (`Satz beendet: N erkannt · Rate R Hz · G Aussetzer ·
    Z ZuPT · K abgelehnt (Mechanismen)`); derselbe Snapshot geht in den
    `SetDiagnosticsLog` (in-memory).
  - **Diagnose-Panel.** Neuer Entwickler-Schalter
    (`DebugSettingsRepository`, DataStore, Default aus) blendet in den
    Einstellungen Sensor-Live-Werte (Verbindung, Transport Notify/Poll,
    MTU, Drops, Gaps; `SensorHealth.transport/negotiatedMtu` neu) und
    den letzten Satz-Report ein.
  - **JSONL (RC-17).** Die `set`-Zeile des Shadow-Recorders traegt
    `rejections` (Mechanismus -> Anzahl).
  - **Befund aus den Tests:** Bei aktivem ZUPT (Default) erreichen
    abgelehnte Kandidaten `decide()` oft gar nicht — der Ruhe-Eintritt
    verwirft sie vorher als `zuptAbortedPending`. Der Report zeigt beide
    Toepfe getrennt; wer "verlorene Reps" analysiert, muss zuerst hier
    schauen.
  - Tests: `ExerciseEnginePipelineIsolationTest` (3 Klassifizierungs-
    Faelle), `ActiveSetControllerTest` (4 Snapshot-Faelle),
    `TrainViewModelTest` (Report nach Stop + Log), `SetReportTextTest`
    (3), `JsonlShadowSessionRecorderTest` (+2), `SettingsViewModelTest`
    (+3), `DebugSettingsStoreTest` (2).

**Umsetzungsstand 20.09.2026 (P2-18, Arbeitsbaum, kein Commit):**

- [x] **P2-18 Live-vs-Replay-Vergleich + CI-Regressionsgate (RC-16).** Umgesetzt:
  - **Live-Diagnose im JSONL.** Die `set`-Zeile traegt zusaetzlich zu
    `rejections` die Live-Diagnose (`framesProcessed`, `framesRejected`,
    `gaps`, `zuptUpdates`, `zuptAborted`, `rateHz`); Aufrufer ohne
    Diagnose schreiben unveraendert das Altformat.
  - **Loader.** `CorpusLoader` liest die `set`-Zeilen und ordnet sie den
    Fenstern ueber den `setIndex` zu (nicht ueber die Listenposition —
    ein Satz ohne Samples verrutscht so nichts). `CorpusWindow.live`
    (`CorpusLiveRecord`) traegt die Werte in den Harness.
  - **Vergleich.** `CorpusSweepHarness.compareLiveVsReplay()` spielt
    jedes Fenster mit der Live-Config (Profil aus dem JSONL,
    profilgesteuertes Accel-Voting, ZUPT an) durch eine frische Pipeline
    und schreibt eine CSV mit beiden Spalten
    (`live_counted`/`replay_counted`/`delta`) plus Diagnose (Gaps, ZUPT,
    Ablehnungen, Rate) und einer `note`, die Abweichungen eingrenzt
    (Gaps, Ablehnungen, ZUPT, Filterframes). `configAdjust` ist der
    Gegenbeweis-Hook: eine geaenderte Refraktaerzeit aendert nur den
    Replay-Count, nie den Live-Count.
  - **CI-Regressionsgate.** `CorpusRegressionGateTest` fixiert auf einem
    deterministischen Goldkorpus (3 Saetze, davon einer mit halber Rep)
    die Replay-Counts [2,3,2], delta 0, keine Gaps und die
    ZUPT-Verwurf-Diagnose (1 echter Verwurf im Artefakt-Satz, 0 in den
    sauberen). Der echte Gate-Korpus (Adis Aufnahmen) kommt spaeter
    dazu; derselbe Mechanismus.
  - **Befund + Fix (Zaehler-Ehrlichkeit).** Der Vergleich zeigte, dass
    `zuptAbortedPending` bisher JEDEN Ruhe-Eintritt zaehlte (saubere
    2-Rep-Saetze standen bei 3 statt 0). `RepCounter.abortPending()`
    gibt jetzt zurueck, ob wirklich ein Pending offen war; der Zaehler
    misst echte Verwuerfe.
  - Tests: `CorpusLiveComparisonTest` (4: beide Spalten, Gegenbeweis,
    Zuordnung ohne Fenster, Fenster ohne Profil),
    `CorpusRegressionGateTest` (1),
    `ExerciseEnginePipelineIsolationTest` (+1 Zaehler-Semantik),
    `JsonlShadowSessionRecorderTest` (+2 Diagnose-Format); Fixture
    `SyntheticCorpus` (geteilt mit dem Sweep-Test).

**Umsetzungsstand 20.09.2026 (P2-19, Arbeitsbaum, kein Commit):**

- [x] **P2-19 Kalibrier-Wizard 2.0 (RC-8).** Umgesetzt:
  - **Stufen-Stepper.** Fuenf Schritte (Ruhe, 1 Rep, 5 Reps, Langsam,
    Review) mit Haekchen fuer erledigte und Hervorhebung der aktuellen
    Stufe; TalkBack-Beschreibung "Schritt 3 von 5: ...".
  - **Live-Signal + Rep-Rueckmeldung.** Der Wizard zeigt waehrend der
    Sammel-Stufen dieselbe Waveform wie der Train-Tab (aus
    `TrainScreen` in `SensorWaveform.kt` herausgezogen) und
    "Reps erkannt: n von Ziel". Die Schaetzung laeuft auf demselben
    Kantenzaehler wie die Auswertung (`CalibrationLiveEstimator`):
    in Stufe A zaehlt sie Bewegungs-Bursts, in B mit robustem
    Startwert (p10/p99), in C mit der gelernten Config.
  - **"Weiter" nur bei erfuellter Stufe.** Ruhe braucht das bestandene
    Rest-Gate, die Rep-Stufen mindestens eine sichtbare Bewegung
    (`advanceEnabled` im ViewModel, testbar).
  - **Fehlergrund + Stufe wiederholen.** Der Fehlertext bleibt, dazu
    gibt es "Stufe wiederholen" (Puffer der Stufe leeren, Stufe
    bleibt). Aus dem Review heraus fuehrt `redoFrom()` zurueck in den
    5er- oder Langsam-Satz — Ruhe und Einzel-Rep bleiben erhalten;
    die abhaengigen Ergebnisse (Sweep, Theta, Langsam-Signal) werden
    verworfen.
  - **Review erklaert die Qualitaet.** `CalibrationReview` liefert
    nachrechenbare Fakten (wiedergefundene Reps in 5er- und
    Langsam-Satz, Streuung der Rep-Abstaende, Abstand der Schwelle zum
    Ruherauschen, Accel-Zweitkanal an/aus, erwartete Rep-Dauer); die
    UI formuliert daraus Saetze statt nur einer Prozentzahl und warnt
    bei Abweichungen. Misslingt der Sweep ganz, bietet das Review
    direkt "5er-Satz wiederholen" an statt einer Sackgasse.
  - **Einstieg an der Uebungszeile.** Unter der Chip-Reihe steht der
    Kalibrier-Status der gewaehlten Uebung ("Kalibriert fuer FlowRep
    #XXXX — neu kalibrieren" bzw. "noch nicht kalibriert"); die
    Kurz-Kennung kommt aus der Geraeteadresse.
  - **Refactor (detekt-getrieben).** `zaehleEdge`/`RepMark` liegen als
    Top-Level-Funktion in `CalibrationCounting.kt` (Kantenzaehler ohne
    Sprungbefehle, damit die Altlast-Baseline-Zeile entfaellt),
    Live-Schaetzung in `CalibrationLiveEstimator.kt`; die
    `CalibrationController`-Klasse bleibt damit unter der
    LargeClass-Schwelle.
  - Tests: `CalibrationControllerWizardTest` (+5: Live-Schaetzung je
    Stufe, repeatStage, redoFrom, Verbot von Vorwaerts-/Review-Spruengen,
    Review-Fakten), `CalibrationViewModelTest` (+3: Advance-Regel,
    repeatStage/redoStage, Review + Ziel), `ShortDeviceLabelTest` (2).

**Umsetzungsstand 20.09.2026 (P2-20, Arbeitsbaum, kein Commit):**

- [x] **P2-20 Train-Konsole: Rep-Hero mit Quelle, Sensor-Kopfzeile, eine
  Primaeraktion (RC-5, A.4).** Umgesetzt:
  - **Rep-Hero mit Quelle.** Die grosse Rep-Zahl traegt darunter die
    Herkunft (UI-Handbuch 7.4): `AUTO` ("Sensor verbunden"),
    `MANUELL KORRIGIERT` mit Original-Zaehlstand ("Sensor erkannte 7"),
    `MANUELL` bzw. `SENSOR GETRENNT` ("Reps per +/- weiter"). Die
    Ableitung ist eine reine Funktion (`repsSourceOf`), der Zustand
    liegt im `RepSourceTracker`; `+/-` bleibt der schnelle
    Korrekturpfad.
  - **Ein Fluss statt zwei Karten.** Start -> Countdown -> grosse
    Live-Zahl -> Stopp -> Korrektur -> Satz fertig laufen alle im Hero:
    der Live-Zaehler ist aus der Sensor-Karte in die Rep-Sektion
    gezogen, die Waveform sitzt direkt unter der Zahl (Design 8.1).
    Waehrend der Zaehlung ist "Stopp" die Primaeraktion, sonst "Satz
    fertig"; der Start der Live-Zaehlung ist eine Ghost-Aktion, der
    Kalibrier-Einstieg bleibt an der Uebungszeile (RC-8).
  - **Sensor-Kopfzeile.** Statt einer konkurrierenden Karte unter der
    Eingabe: Chip/Status + Qualitaet ("Signal ok/schwach/
    unzuverlaessig") + Puls in einer Zeile ueber der Konsole, der
    Fehlertext direkt darunter (Design 8.1). Der doppelte
    "Kalibrieren"-Knopf entfaellt (Einstieg ist die Uebungszeile).
  - **Konsolen-Modus.** `WorkoutConsoleMode` (IDLE / SET_ENTRY /
    REST_RUNNING / GO_CUE) ersetzt die if/else-Kette; `EXERCISE_DONE`
    fehlt bewusst (Umbauhandbuch 24.1: kein Session-Abschluss,
    "Uebung abschliessen" fuehrt zurueck zur Uebungszeile).
  - **Refactor (detekt-getrieben).** `RepSourceTracker` haelt Zaehlstand
    und Sensorabriss, damit `TrainViewModel` unter der
    LargeClass-Schwelle bleibt; die neuen ViewModel-Tests liegen in
    `TrainViewModelRepSourceTest` (gleiche Regel wie beim
    Satz-Report-Test).
  - Tests: `WorkoutConsoleStateTest` (11: Modus- und
    Quellen-Ableitung), `TrainViewModelRepSourceTest` (+5: AUTO,
    KORRIGIERT, MANUELL, Reset nach dem Loggen, SENSOR GETRENNT).
  - Verifikation: `spotlessCheck`, `detekt` (0 Issues),
    `:domain:sensor` 147 + `:feature:workout` 76 Tests,
    `:app:assembleDebug` gruen.

**Umsetzungsstand 20.09.2026 (P2-21, Arbeitsbaum, kein Commit):**

- [x] **P2-21 Now-Playing als Drop-Editor (MP-7, A.4).** Umgesetzt:
  - **Marker-Tap-Sheet (UI-Handbuch 14.4/14.5).** Tap (und Langdruck) AUF
    einem Marker oeffnet ein Bottom-Sheet: Label, Zeit mit Millisekunden
    (`01:18.420`), Status-Chip (`Vorschlag aus der Analyse`,
    `Bestaetigt automatisch`, `Manuell gesetzt`, `Bestaetigt`) und
    `Aktives Ziel`. Aktionen: `Ab Marker anhoeren` (Seek),
    `Position feinjustieren` (`-100/-10/+10/+100 ms`, Originalposition
    bleibt in `MarkerEditState`, `Zurueck auf Original` nur bei
    Abweichung), `Als DropSync-Ziel waehlen`/`Ziel entfernen`,
    `Bestaetigen` (Vorschlag), `Umbenennen` (neue
    `MarkerRepository.renameMarker`), `Loeschen` (Undo-Snackbar der
    Shell). Langdruck daneben setzt weiter einen neuen Marker; das
    Sofort-Loeschen per Langdruck entfaellt (UI-Handbuch 15.3).
  - **Vorschlaege und Ziel auf der Waveform.** Unbestaetigte
    AUTO_DETECTED-Kandidaten des laufenden Songs kommen als gedaempfte
    Ticks aus demselben Kandidaten-Flow wie die Review-Liste; das
    bevorzugte Ziel zeichnet einen Diamanten ueber dem Tick. Die
    Marker-Legende unter der Waveform (`bestaetigt`, `Vorschlag`,
    `aktives Ziel`) ist als Satz fuer TalkBack verfuegbar (14.3).
  - **"Ziel waehlen" wirkt real.** Das Ziel wird je Song persistiert
    (DataStore `DropTargetStore`, keine Room-Migration) und der
    `DropSyncPlanner` bevorzugt es gegenueber der Naechster-Marker-Wahl
    SEINES Titels; die Titelwahl (kleinster Abstand zur Restzeit) bleibt
    unveraendert. Fehlt der Marker (geloescht/deaktiviert), greift die
    Automatik ohne Aufraeumen. Ein Vorschlag wird beim Zielsetzen
    bestaetigt ("Drop gehoert, nimm den" = eine Handlung).
  - **DropSync-Statuszeile unter der Waveform (MP-7-Detail).** Gleiche
    Sprache wie die Konsole: `DROP BEREIT` + `"Track" · Drop 2 · Ziel in
    01:27` (Countdown aus dem Plan-Deadline und der monotonen Uhr,
    `DropStatusLine` als reine Ableitung), `BEST EFFORT` bzw.
    `MANUELL UEBERNOMMEN`; stumme Zustaende bleiben stumm. TalkBack
    hoert den ganzen Satz ("DropSync bereit. Track ... Ziel in 1 Minute
    27 Sekunden.", UI-Handbuch 19.3).
  - Tests: `NowPlayingDropStatusTest` (6), `MarkerEditStateTest` (4),
    `MarkerSheetFormatTest` (3), `DropTargetStoreTest` (2, Robolectric),
    `MarkerRepositoryImplTest` (+3 Umbenennen), `PlayerViewModelTest`
    (+7 Ziel/Vorschlaege/Countdown), `DropSyncCoordinatorTest` (+2
    Ziel-Vorrang und Automatik-Fallback).
  - Verifikation: `spotlessCheck`, `detekt` (0 Issues),
    `:data:library`, `:feature:player`, `:feature:settings`,
    `:core:designsystem` gruen; `:app:assembleDebug` gruen.

## P3 — Spaeter (eigene Entscheidungen)

| # | Paket | Befund |
|---|---|---|
| 28 | DROPSYNC-Recovery nach Kill/Reboot oder dokumentierter Verzicht | MP-10 |
| 29 | Geraetetaste belegen (Satz starten/stoppen) | RC-12 |
| 30 | Satz-Undo + Haptik beim Loggen | A.4 |
| 31 | `markRunning`-Startzeit/KDoc schaerfen | MP-11 |
| 32 | Fallback ohne Rest-Playlist: Design 7.1 nachziehen (Entscheidung 7) | MP-16 |
| 33 | ADR-0012 nachziehen (DOC-2); Testplan-Rest abgleichen | C.2 |
| 34 | DBA-Template statt FIFO-Pool (nach Messung) | RC-21 |

---

# Teil E — Kennzahlen (vorher -> Ziel)

| Kennzahl | Heute | Ziel |
|---|---:|---:|
| Drop-Auto: Anteil Pausen mit erfolgreicher Landung | 0 % (funktionslos) | > 95 % bei erreichter Work-Playlist |
| Drop-Timing-Fehler P50/P95 je Route | nicht gemessen (Messung zurueckgestellt, Entscheidung 5) | Zielwert-Absicht ±25 ms / ±50 ms; Instrumentierung liefert die Zahlen spaeter |
| Beat-Snap-Offset | Gitter ab 0 ms; bis zu 234 ms Fehler bei 128 BPM | Downbeat-Offset gesetzt (RC-22) |
| Ermuedungsdrift im Qualitaetsscore | gleitender Mittelwert bestraft das Satzende | Drift-Modell oder einseitige Scores (RC-18) |
| DropSync-Zustaende in der UI sichtbar | nein | alle (geplant/armed/best effort/override) |
| DropRest mit Notification/Foreground-Service | nein | ja (Prozess-Kill ueberlebt den Rest) |
| `+15 s` verschiebt die geplante Landung | nein | ja (Test "verlaengern verschiebt") |
| Landungs-Kandidaten je Work-Titel | 1 (erster Marker) | alle aktiven Marker |
| Zaehlpipeline auf Main-Thread | ja | nein (Dispatcher-Assert) |
| Rohdaten-Schreibpfad im Release | ja | nein |
| Gate-11b-Szenarien mit Exact-Match | 0/5 | 5/5 (≥ 3 Sessions je Szenario) |
| Accel-Konstanten an echten Traces geprueft | nein | ja (Teil des Freigabe-Protokolls) |
| Live-vs-Replay-Delta je Satz | nicht gemessen | 0 oder nach Mechanismus klassifiziert |
| Ablehnungsgruende je Satz aggregiert | nein | ja (Enum + Satz-Report + Protokoll) |
| Sichtbare Start-Gruende / 0-Ergebnis | nein | ja, je Grund ein Zustand |
| Waveform-Recomposition je Sample | ja | gedrosselt (Frame-Takt) |

---

# Teil F — Was dieser Plan bewusst nicht anfasst

- **Die Signalmathematik** in `:domain:sensor` (Filter, DTW, PhaseValidator,
  QualityScorer). Sie ist getestet und nicht der Engpass.
- **Die BLE-Transportmechanik** (Notify/Poll-Fallback, MTU-Verhandlung,
  JitterBuffer, Dedup) — sie ist im letzten Jahr gehaertet worden.
- **Die Modulgrenzen** und den Architekturtest.
- **Die DSP-Mathematik** und die Bit-Perfect-/ReplayGain-Arbeit.
- **Die bereits entschiedenen Wege b** (ausgegraute Panels, ehrliche Texte).
  Wo dieser Plan sie beruehrt (Crossfade), sagt er das explizit.

---

# Anhang — Entscheidungen (19.09.2026)

Die offenen Fragen der Erstfassung sind beantwortet. Diese Entscheidungen
sind ab jetzt bindend fuer die Umsetzung:

| # | Frage | Entscheidung | Folge im Plan |
|---|---|---|---|
| 1 | Drop-Auto-Semantik | **Musik landet am Pausenende**; der Rest behaelt die eingestellte Dauer | MP-1, MP-2: Bus an den Koordinator, Landung planen; der manuelle DropRest bleibt die "Pause bis zum Drop"-Aktion |
| 2 | Test-Aufnahmen | **Adi macht die Aufnahmen selbst** | Ich bereite Recorder-Schalter, Replay-Spalte, Protokoll und A10-A12 fertig vor (Pakete P0-2, P2-15) |
| 3 | Recorder | **Nur in der Testversion** (Release schreibt nichts) | RC-2 wird als Build-Typ-Gate umgesetzt |
| 4 | Musik-Uebergang | **Echter Crossfade (Dual-Player)** | MP-4 Stufe 2 wird verbindlich, eigener ADR. **Konflikt:** die Tiefenrecherche (3.6 "Was NICHT empfohlen wird") raet ausdruecklich davon ab; Media3 1.11 (Preloading/PlayerPool) aendert die Kostenseite. Der ADR muss die Gegenargumente adressieren (siehe 0.3) |
| 5 | Messungen am Handy | **Vorerst keine** | Zielwerte bleiben Absicht; die Instrumentierung (geplant vs. tatsaechlich, D5) wird trotzdem gebaut, damit die Messung spaeter nur noch abgelesen wird |
| 6 | Sensor-Befestigung | **Am Koerper (Arm/Handgelenk)** | A10 wird Pflichtszenario; die Empfehlung der Tiefenrecherche (R7: Hebelarm vs. Handgelenk messen) wird als Vergleichssession in A10 aufgenommen |
| 7 | Keine Pausen-Playlist | **Normal weiterlaufen, nichts tun** | MP-16: ADR-0012-Verhalten bestaetigt, das Fusionsdesign 7.1 wird nachgezogen |
| 8 | Pausen-Benachrichtigung | **Ja, mit Abbrechen-Knopf** | MP-12: TimerService (specialUse) traegt den DropRest mit |
| 9 | Startpunkt | **Recherche-Punkte 2 und 3** (Service-Typ, Per-Uebungs-Parameter) | erledigt, siehe `docs/research/2026-09-19-*` |
| 10 | Recherche-Notizen | **Im Projekt unter `docs/research/`** | neue Notizen liegen dort |
| 11 | Geraetetaste (RC-12) | **Satz starten/stoppen** (von der Umsetzung festgelegt, weil Adi alle Punkte delegiert hat) | P3-29 setzt die Belegung um |

## Anhang B — Abgleich mit der bestehenden Tiefenrecherche

`docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md` (70 KB,
Stand 2026-09-03) ist die tiefere Evidenzbasis fuer beide Schwerpunkte.
Dieser Plan baut darauf auf; der Abgleich:

**Korrekturen an der Tiefenrecherche (inzwischen erledigt):**
- **R1 (Rohsamples im Recorder)** ist seit Umbauplan Phase 0 (2026-09-04)
  umgesetzt: `JsonlShadowSessionRecorder.recordSamples()` schreibt alle
  Samples inkl. Achse/Bias/Schwellen. Die Aussage "keine Rohsamples" ist
  ueberholt; RC-2 regelt nur noch das Build-Typ-Gate.
- **R4 (Offline-Sweep)** existiert: `CorpusSweepHarness` +
  `CorpusReplayDeterminismTest` (Phase 6.2, 2026-09-11).
- **R5 (Accel-Voting)** ist seit ADR-0017 live (profilgesteuert); offen
  bleiben nur die Konstanten (RC-15).

**Befunde der Tiefenrecherche, die dieser Plan uebernimmt:**
- **R3 Ermuedungsdrift** (1.2): Der gleitende Mittelwert in
  `trackForAdaptation` ueberschreibt die kalibrierten Erwartungswerte und
  bestraft ehrliche Ermuedung; 45 % des Qualitaetsgewichts haengen daran.
  Wird als **RC-18** gefuehrt (Stufe-2-Paket, vor allen Optimierungen).
- **R6 Autokorrelation** (1.4): Zweitmeinung sichtbar (existiert als
  `PlausibilityHint`) und als ehrlichere Quelle fuer
  `expectedDurationMs` nutzen → **RC-19**.
- **R7 Hebelarm vs. Handgelenk** (1.3): eine Messsession; bei 4 von 5
  Uebungen ist der Hebelarm die strukturell bessere Position. Adi traegt
  am Handgelenk (Entscheidung 6) → als Vergleichssession in A10.
- **R8 Parameter pro Uebung** (1.5): DTW-/Qualitaets-Schwelle in das Profil
  (Schema v6) → **RC-20**; deckt sich mit den Per-Uebungs-Daten aus dem
  Few-Shot-Volltext (neue Notiz unter `docs/research/`).
- **R10 DBA-Template** statt FIFO-Pool (1.6) → **RC-21** (nachrangig,
  Nutzen bis zur Messung unbelegt).
- **D2 Downbeat-Offset** (3.4): Der Beat-Snap rastet heute auf ein Gitter
  ab 0 ms; ein halber Beat ist bei 128 BPM 234 ms — groesser als das
  gesamte Latenzbudget → **RC-22**.
- **D3 `PlayerMessage` statt `delay()`** (3.2): Media3 fuehrt ein
  `PlayerMessage` auf dem Playback-Thread an einer Wiedergabeposition aus.
  Das ist die richtige Primitive fuer die Landung und ersetzt den
  Deadline-Ansatz aus A.3 als **primaeren** Mechanismus (Deadline-Schleife
  bleibt Fallback) → in MP-3 eingearbeitet.
- **D5 Zielfenster** (3.3): ±25 ms Anspruch, ±50 ms Akzeptanz, P95 als
  Kennzahl; ein konstanter Offset ist unhoerbar, Streuung ist das Problem.
  Ersetzt das schwache "P95 < 250 ms" in Teil E.
- **D8 Underrun-Monitoring**, **D9 Crossfade in der Landung** (3.6):
  D9 deckt sich mit MP-9; D8 wird als Diagnose-Baustein in RC-7 aufgenommen.

**Der eine Konflikt:** Die Tiefenrecherche empfiehlt ausdruecklich **keinen**
Dual-Player-Crossfade ("wurde bewusst entfernt; ohne belegte Notwendigkeit
nicht zurueckholen"). Adi hat im Wizard den echten Crossfade gewaehlt. Der
Plan setzt die Nutzerentscheidung um, aber der ADR muss die Gegenargumente
behandeln: doppelte Decoder-Last, Sample-Rate-Wechsel, AudioFocus, der
bereits entfernte `CrossfadeController` — und belegen, was der Crossfade
gegenueber D9 (Kurve in der Landung, ein Player) tatsaechlich gewinnt.
