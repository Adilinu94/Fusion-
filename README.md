# DropSync

Lokale Android-App fuer Musikwiedergabe, markerbasierte Timer (DropSync) und
ein Krafttrainingstagebuch — vollstaendig offline, ohne Konto, ohne Analytics.

> Sichtbarer App-Name (Launcher/Store): **FlowRep**. Projekt- und Paketname
> bleiben aus Kontinuitaetsgruenden `DropSync` bzw. `com.dropsync`.

Grundlage ist der verbindliche technische Bauplan
(`DropSync-Technischer-Bauplan.md`, Stand 27.07.2026). Abweichungen vom
Bauplan sind nur ueber ADRs in [`docs/adr/`](docs/adr/) erlaubt.

## Status der Umsetzungsschritte

| Schritt | Inhalt | Status |
|---|---|---|
| 1 | Projekt und Lieferkette | Abgeschlossen |
| 2 | Modulgrenzen, DI, Fehlervertrag | Abgeschlossen |
| 3 | Datenbank und Migrationen | Abgeschlossen |
| 4 | Lokale Medienbibliothek | Abgeschlossen (Datenschicht; UI folgt in Schritt 12) |
| 5 | Media3-Playback-Service | Abgeschlossen (Code; Geraeteverifikation in Schritt 13) |
| 6 | Songmarker-Import | Abgeschlossen (Import/Zuordnung; Settings-UI folgt in Schritt 12) |
| 7 | Timerkern | Abgeschlossen (Domainkern; Cue-Adapter folgen in Schritt 8) |
| 8 | Audio-Cues, Ducking, Haptik | Abgeschlossen (Adapter; TTS-/Haptik-Geraetetest in Schritt 13) |
| 9 | Uebungen, Routinen, Session-Log | Abgeschlossen (Datenschicht; UI folgt in Schritt 12) |
| 10 | Volumen, PRs, Verlauf | Abgeschlossen (Berechnung/Persistenz; Verlaufs-UI folgt in Schritt 12) |
| 11 | Musik-/Workout-Integration | Abgeschlossen (Domainregeln + Snapshots; UI-Verdrahtung in Schritt 12) |
| 12 | UI und Barrierefreiheit | Abgeschlossen (Shell, Navigation, Satz-Logging mit Undo, Resttimer, Markerimport mit Zuordnung, Drop-Rest; TalkBack-/200%-Schrift-Abnahme nur am Geraet, siehe Schritt 13) |
| 13 | Tests, Performance, Release-Gates | Offen |
| 14 | Datenschutz-, Lizenz-, Releasepruefung | Offen |

### Audio-Engine-Ausbau (Plan `AUDIO_ENGINE_AUSBAU_PLAN.md`, ADR-0005 bis ADR-0010)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 1 (Schritt 15) | Audio-Engine-Fundament: Float-Output, DSP-Kette (64-Bit-Double), Preamp+Limiter, Audioinformationen | Abgeschlossen (`:domain:audio`, `:data:audio`, Service-Wiring; Audioinformationen-Panel in `:feature:audio`; Cue-Ducking auf dem Preamp-Knoten der DSP-Kette statt Player-Lautstaerke, kollisionsfrei zu DVC) |
| 2 (Schritt 16) | EQ (32 Baender), Bass/Hoehen, Stereo Expansion, Reverb, Dither, Resampler, DVC, Presets | Abgeschlossen (`MasterDspProcessor`, EQ-Presets in Room + Seeder/CRUD; UI in `:feature:audio`: EQ grafisch/parametrisch mit Presets, Klangregler, Stereobreite, Reverb, Resampler, Dither, DVC, Crossfade) |
| 3 (Schritt 17) | FFmpeg-Formate (ALAC/AIFF/WMA/APE/TAK/TTA/DSD), CUE, M3U, SAF-Ordnerscan | Codeseitig abgeschlossen: Parser, Formatkatalog, SAF-Ordnerscan, `cue_tracks` + Clipping-MediaItems, Renderer-Extension-Mode (`DspRenderersFactory`, `EXTENSION_RENDERER_MODE_PREFER`), M3U/M3U8-Playlisten-Import. FFmpeg-Extension ist artifact-ready: Gradle-Flag `dropsync.enableFfmpeg` (Default aus), automatisches Wiring in `settings.gradle.kts` + `:data:audio`, Build-Skript `scripts/build-ffmpeg.sh` + Anleitung `docs/ffmpeg-build.md`. Code-seitig vollstaendig und verifiziert (Wiring, Lizenz, Renderer-Prioritaet); das native Artefakt erfordert einen Entwickler-Host mit NDK r28+ (16-KB-Page-Alignment, siehe `docs/ffmpeg-build.md`) — kein Projektrueckstand, sondern strukturelle Voraussetzung nativer Android-Builds |
| 4 (Schritt 18) | Gapless-Absicherung, Crossfade (Dual-Player), Auto-Resume, MusicFX | Abgeschlossen: `CrossfadeController` (Equal-Power, Gapless-/CUE-Ausschluss, Fallback harter Uebergang), `onPlaybackResumption` aus `PlayerStateStore`, Option "Bei BT-Verbindung automatisch fortsetzen", MusicFX-Session-Broadcasts + `useSystemEffects`-Bypass |
| 5 (Schritt 19) | Pro-Ausgang-Profile, Bit-Perfect (USB, Android 14+), BT-Anzeige | Abgeschlossen: Profile je Geraet (`DeviceProfileStore` + `OutputProfileController`, automatischer Wechsel + Save-Through), `BitPerfectGateway` (AudioMixerAttributes, API 34+), Bit-Perfect-Bypass (DSP aus, Float-Output aus, Crossfade aus), Vertrag `activeOutputProfileKey`/`bitPerfectSupport`; UI in `:feature:audio` (Bit-Perfect-Panel mit DAC-Faehigkeiten, aktives Ausgabeprofil, BT-Hinweis + Link zu den System-Toneinstellungen), erreichbar ueber Einstellungen -> Audio & DSP |
| 6 (Schritt 20) | Bibliothek: Kuenstler/Alben/Genres/Ordner, Statistiken, Favoriten, Suche, Queue | Datenschicht + UI-Ansichten abgeschlossen: Room (`play_stats`, `favorites`, `playlists`/`playlist_items`, FTS4 `song_fts`, `genre`-Spalte), `LibraryBrowseRepository` (Alben/Kuenstler/Genres/Ordner, zuletzt/meistgespielt, Favoriten, Volltextsuche, Playlisten-CRUD/Move, M3U-Import); `LibraryScreen`/`LibraryContent` mit Ansichts-Chips, Volltextsuche, Sortierung, Filtern nach Format/Dauer/Hi-Res, Favoriten-Toggle, Alphabet-Schnellscroller, horizontalem Swipen zwischen Ansichten (`HorizontalPager`) und Sammlungs-Drilldown; konfigurierbare Ansichten (ein-/ausblenden + Reihenfolge, persistiert via `LibraryViewPreferencesRepository`); Queue-Editor (verschieben/entfernen/als Naechstes/zur Queue) ueber `PlaybackRepository` als Bottom-Sheet am Mini-Player; `MediaLibraryService`-Browse-Baum (Root -> Titel/Alben/Interpreten/Ordner -> Songs) fuer Android Auto/BT |
| 7 (Schritt 21) | Feinschliff, Barrierefreiheit, Performance, Geraetetests | Codeseitig abgeschlossen: Barrierefreiheit (Slider mit `stateDescription`, 48-dp-Schaltflaechen, Schaltererklaerungen), DE/EN-Strings, Akku-Hinweis bei Hi-Res-Resampling, DSP-Durchsatz-/Stabilitaetswaechter (`DspPerformanceTest`: 32 Baender/48 kHz > 2x Echtzeit, keine NaN/Inf). Geraeteabhaengig offen (nicht automatisierbar): Baseline-Profile-Generierung (Macrobenchmark auf Geraet), reale CPU-Messung Mittelklasse, USB-DAC-Bit-Perfect, BT-Codec-Verhalten, MusicFX mit/ohne Systemequalizer |

### Workout-Funktionen-Ausbau (Plan `WORKOUT_FUNKTIONEN_AUSBAU_PLAN.md`)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| A | Datenschicht: `exercise_rest_prefs` (Rest-Timer pro Uebung), DB v2 + `MIGRATION_1_2`, DAO-Erweiterungen (Bibliothek, Tausch, Wiederholen, Session-Tracks) | Abgeschlossen |
| B | Domain: eigene Uebungen mit Muskel-Mapping (`Slugs`), Uebungstausch KEEP/MOVE/DISCARD mit PR-Neuberechnung, `repeatLastSession`, Routinen aus Session, `ProgressSeriesBuilder` + `ProgressionClassifier` (Plateau-Alarm + Vorschlag) | Abgeschlossen |
| C | Musik-Kopplung: `DropRestRequestBus` (Workout fordert DropSync-Rest an), Playback-Snapshot beim Satzabschluss (best-effort, 11.1) | Abgeschlossen |
| D | Charts: animierte `LineChart`/`BarChart` in `:core:designsystem` (nur Compose-Canvas) | Abgeschlossen |
| E | Feature-UI: `WorkoutFeature`-NavHost (Session, Bibliothek, Uebungsdetail, Routinen, Fortschritt), Prefill, Rest Normal/DropSync, Swap-Dialog, Routinen-Aktionen, Track-Chip, Strings DE/EN | Abgeschlossen (Geraete-Abnahme in Schritt 13) |
| F | Tests: Domain (Slugs, Progressanalyse), Repository (18 Tests inkl. Swap/Wiederholen/RestPref/Snapshot), Migration 1 -> 2 gegen `2.json` | Abgeschlossen |

### Marker- und Waveform-Ausbau (Plan `MARKER_UND_WAVEFORM_AUSBAU_PLAN.md`)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 1 | Now-Playing-Screen-Fundament: `NowPlayingScreen` (Cover via `MediaMetadataRetriever.embeddedPicture`, da minSdk 26 < API 29 fuer `loadThumbnail()`), `NowPlayingUiState` + Positions-Ticker (200 ms `snapshotNow()`, nur bei sichtbarem Screen), `skipToPrevious`/`seekTo`, Route `now_playing` per Tap auf den Mini-Player | Abgeschlossen |
| 2 | Geteilte Analyse-Grundlage: `TrackAnalyzer`/`TrackAnalysisRepository` (`:domain:audio`), Streaming-Akkumulatoren (Min/Max-Buckets + RMS-Energie in einem Durchgang), `track_analysis`-Cache (DB v3, `MIGRATION_2_3`, `analyzer_version` zur Invalidierung), `TrackAnalyzerImpl` per MediaExtractor/MediaCodec (ADR-0011), aufschiebbarer `OneTimeWorkRequest` dedupliziert ueber `track_analysis_<songId>` | Abgeschlossen |
| 3 | Waveform-Anzeige mit Scrubbing: `Waveform`/`WaveformPlaceholder` (`:core:designsystem`, Compose-Canvas, Lime fuer gespielten Anteil), Tap = Sprung, Drag = Live-Vorschau mit Sprung beim Loslassen; Ladezustand pulsiert, Fehlerfall (persistiert als `bucket_count = 0`) faellt dauerhaft auf die Zeitleiste zurueck | Abgeschlossen |
| 4 | A1 - Manuelles Marker-Setzen: `createManualMarker`/`deleteMarker` in `MarkerRepository` (MANUAL-Marker + Link in einer Transaktion, Fingerprint aus Songfeldern wiederverwendet), Bestaetigungsdialog mit optionalem Label (Standard "Drop"), Marker-Ticks auf der Waveform; Long-Press auf freier Flaeche setzt, Long-Press nahe einem Tick loescht nach Bestaetigung (Tap bleibt der Sprung aus Phase 3) | Abgeschlossen |
| 5 | A2 - On-Device-Drop-/Onset-Erkennung: `OnsetDetection` (`:domain:audio`, Novelty = positive RMS-Differenz, Peak-Picking ueber gleitendem Schwellwert Mittel + k*Stdabw plus absolutem Mindestsprung, Mindestabstand 5 s, Top 5), Trigger "Drops automatisch erkennen" im Song-Kontextmenue (Worker `onset_detection_<songId>`), Kandidaten `AUTO_DETECTED`/`isEnabled=false` (nie Automatik; erneuter Lauf ersetzt nur unbestaetigte), Review-Liste in den Einstellungen mit Bestaetigen (`confirmMarker`) und Verwerfen (loeschen) | Abgeschlossen |

### Musik-Workout-Kopplung (Plan `MUSIK_WORKOUT_KOPPLUNG_AUSBAU_PLAN.md`, ADR-0012)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 1 | Playlist-Oberflaeche (F1): anlegen/umbenennen/loeschen, Titel hinzufuegen/entfernen, Reihenfolge (Auf/Ab), "Zu Playlist hinzufuegen" im Songmenue | Abgeschlossen |
| 2 | Playlist-Labels "Rest/Pause" und "Work" (F2): additive Spalte `label`, DB v3->v4 (`MIGRATION_3_4`, `4.json`), Label-Auswahl + Badge in der UI | Abgeschlossen |
| 3 | Rest-Musik-Domain + Einstellungen: `RestMusicBehavior`, reiner `DropLandingPlanner` (`:domain:timer`), `RestMusicSettingsRepository`/-`Store` (DataStore), Abschnitt "Musik in Pausen" in den Einstellungen | Abgeschlossen |
| 4 | Rest-Musik-Orchestrierung + Drop-Landung (F3): `RestMusicCoordinator` (`:feature:player`) beobachtet `TimerEngine`+Einstellung, setzt Rest-Queue bei Pausenbeginn, terminiert die Drop-Landung und wechselt per `crossfadeTo` (MediaSession-Custom-Kommando -> erweiterter `CrossfadeController`), Fallback-Kette + Nutzer-Vorrang; ADR-0012 | Abgeschlossen |
| 5 | Hardware-/Touch-Control (F4): Verifikation MediaSession-Standardbefehle, Override-Absicherung, Doku `docs/hardware-control.md` | Abgeschlossen (codeseitig: Standardkommandos + Notification-Aktionen ueber `MediaLibrarySession`, Nutzer-Vorrang der Automatik getestet, Doku; Geraeteabnahme wie Schritt 13 offen) |
| 6 | Extras: Intelligentes Shuffle (A5, reiner `SmartShuffle` in `:domain:library` ueber play_stats/Favoriten, Schalter in den Einstellungen, `shufflePlay` in der Titelliste), Rest-Timer-Presets (B8, `RestTimerPreferencesRepository`/-`Store` (DataStore), Schnellwahl-Chips im Rest-Dialog + Editor in den Einstellungen), Get-Ready-Countdown 3-2-1 (B9, `prepMs`/`PREPARING` in `TimerEngine`, Schalter+Dauer in den Einstellungen) | Abgeschlossen |

### Mix-Uebergaenge (Plan `MIX_TRANSITIONS_AUSBAU_PLAN.md`)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 1 | BPM-/Tonart-Analyse additiv im bestehenden `TrackAnalyzer`-Durchgang (`track_analysis.bpm`/`camelot_key`), Migration v4->v5 | Entwurf |
| 2 | `MixPreset`-Enum (Fade/Rise/Blend/Wave/Melt/Slam) als Volume-Kurven mit Equal-Power-Invariante (`sqrt(1 - fadeIn^2)`), `CrossfadeController` auf Preset-Strategie (beide Rampen), SLAM-Klickschutz per Mikro-Rampe, `DspConfig.mixPreset` (DataStore + Profil-Codec) | Abgeschlossen |
| 3 | UI: Abschnitt "Mix-Uebergaenge" in den Einstellungen — an/aus, Uebergangsstil (6 Chips mit Erklaertext), Dauer 1-12 s, Bit-Perfect-Hinweis; Strings DE/EN. Steuerung global statt pro Playlist (Nutzerentscheidung); BPM/Key-Badges folgen mit Phase 1 | Abgeschlossen |
| 4 | Tests: `MixPresetTest` (Equal-Power-Eigenschaftstest, FADE bitidentisch zum Bestand, Monotonie, paarweise verschieden), `CrossfadeControllerTest` (Mikro-Rampe), `DspConfigCodecTest` (Roundtrip/Rueckfall) | Abgeschlossen (Analyzer-/Migrationstests folgen mit Phase 1) |
| 5 | Optional: zweite DSP-Kette fuer echte EQ-/Filter-Uebergaenge (ADR-0013-pflichtig) | Nicht geplant, nur beschrieben |
| 6 | Optional: Drop-Landung (`crossfadeTo`) mit waehlbarem Preset | Nicht geplant, nur beschrieben |

### Herzfrequenz ueber Health Connect (Plan `HERZFREQUENZ_HEALTH_CONNECT_PLAN.md`)

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 1 | `:domain:health` + `:data:health`: `HeartRateSource`-Vertrag (Permission-Contract via `@HealthPermissionContract`-Qualifier, kein SDK-Leak in Features), `getSdkStatus`-Verfuegbarkeit inkl. `UPDATE_REQUIRED`, Changes-API mit Token-Ablauf-Fallback, `connect-client` 1.1.0; JVM-Tests gegen Fake-Gateway | Abgeschlossen |
| 2 | Berechtigungs-UI in `:feature:settings`, Manifest (`health.READ_HEART_RATE` + Rationale-Intent-Filter), Datenschutz-/Rationale-Seite | Offen |
| 3 | `HeartRateBadge` in `:core:designsystem` (bpm + "zuletzt aktualisiert vor X min"), Einbindung Now-Playing + Session-Screen, Latenz-Messung am Geraet | Offen |

Grundsatz: Lesen nur im Foreground (kein `READ_HEALTH_DATA_IN_BACKGROUND`), keine eigene Persistenz ausser dem `changesToken`; Quelle ist Mi Fitness -> Health Connect, die App selbst bleibt offline.

## Build

Voraussetzungen:

- JDK 17 oder neuer (empfohlen: das mit Android Studio ausgelieferte JBR)
- Android SDK mit Platform `android-37` (Pfad in `local.properties` als
  `sdk.dir`, wird nicht eingecheckt)

```
./gradlew assembleDebug      # Debug-Build
./gradlew assembleRelease    # Release-Build (R8, unsigniert)
./gradlew test               # Unit-Tests aller Module
./gradlew spotlessCheck      # Formatierung und Lint
```

## Architektur

Feature- und schichtorientierte Module (Bauplan Abschnitt 3.2):

```
:app                 Navigation + Hilt-Verdrahtung, keine Fachlogik
:core:common         AppResult, AppError, Clock, DispatcherProvider
:core:model          reine Domain-Modelle
:core:database       Room, DAOs, Migrationen
:core:designsystem   Material-3-Theme, wiederverwendbare Komponenten
:core:testing        Fakes (FakeClock u. a.), Testregeln
:data:*              Repository-Implementierungen (MediaStore, Media3, Room)
:domain:*            Use Cases, Timerzustandsmaschine, Trainingsmathematik
:feature:*           Compose-Screens; kein Feature importiert ein anderes
```

Verbindliche Abhaengigkeitsregeln: Features kennen weder Room noch ExoPlayer;
Domain-Module importieren kein Android-UI-, Room- oder Player-API. Ein
Architekturtest (`:core:testing`) prueft diese Regeln bei jedem Testlauf.

## Richtlinien

- Versionen ausschliesslich in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
- Lizenzinventar in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- Zeitpunkte als UTC-Epoch-Millis, Gewichte als ganze Millikilogramm (`Long`)
- Keine Analytics, keine Telemetrie, kein Netzwerkzugriff; Auto Backup ist deaktiviert
