# DropSync Musik-Workout-Kopplung — Ausbauplan

Stand: 27.07.2026. Dieser Plan beschreibt den Ausbau der Kopplung zwischen
Musikwiedergabe, Playlisten und dem Krafttraining. Verbindlich bleiben die
Regeln des technischen Bauplans (Abschnitt 3.2) und der bestehenden ADRs
(0004 Playback, 0005-0009 Audio-Engine, 0011 Track-Analyse). Neue
Grundsatzentscheidungen werden als ADR-0012 dokumentiert.

## Ziel

Das Krafttraining und die Musik so verzahnen, dass die App die Pause aktiv
mit Musik gestaltet — bis hin zur punktgenauen "Drop-Landung": kurz vor dem
Ende des Pausen-Countdowns startet vorgezogen ein Titel aus dem "Work"-Label,
und dessen Drop (auto-erkannt oder manuell gesetzt) faellt exakt mit dem
Pausenende und damit dem naechsten Satz zusammen. Das ist "DropSync
rueckwaerts": statt "Pause bis zum Drop" gilt "Titel so timen, dass der Drop
das Pausenende trifft".

## Umfang

1. **Playlist-Oberflaeche** (F1): sichtbares Verwalten von Playlisten
   (anlegen, umbenennen, loeschen, Titel hinzufuegen/entfernen, Reihenfolge
   per Drag). Die Datenschicht (`PlaylistDao`, `LibraryBrowseRepository`)
   existiert bereits vollstaendig; es fehlt nur die UI.
2. **Playlist-Labels "Rest/Pause" und "Work"** (F2): additive DB-Spalte,
   Label-Auswahl in der UI.
3. **Pausen-Musik-Automatik** (F3) inklusive **Drop-Landung**, nahtlos
   (gapless/Crossfade), **in den Einstellungen umschaltbar** (Aus = normale
   Wiedergabe/Shuffle laeuft unveraendert weiter).
4. **Hardware-/Touch-Steuerung** (F4): verifizieren, haerten, ehrlich
   dokumentieren. Standard-Medientasten laufen bereits ueber die
   MediaSession.
5. **Extras**: Intelligentes Shuffle (A5), Rest-Timer-Presets (B8),
   Vorbereitungs-Countdown 3-2-1 (B9).

## Grundregeln

- Vollstaendig offline, keine Analytics, keine Telemetrie, kein Netzzugriff.
- Keine neue schwere Abhaengigkeit (kein Coil, kein neuer Player); genau ein
  Player und eine MediaSession im `PlaybackService`.
- Enums werden als stabile Strings gespeichert (keine Ordinals); keine
  destruktive Migration.
- Kein Feature-Modul importiert ein anderes Feature-Modul. Die Kopplung
  laeuft ueber schmale Domain-Schnittstellen (Muster: `DropRestRequestBus`).
- Zeiten als UTC-Epoch-Millis bzw. monotone `elapsedRealtime`-Millis fuer die
  Timing-Praezision; keine Ordinals.
- ASCII-Umlaute (ue/ae/oe/ss) in Code, Strings und Dokumentation.
- Nach jeder Phase: `spotlessApply`, betroffene Unit-Tests, `:app:assembleDebug`,
  README-Statustabelle pflegen, committen und pushen.

## Architekturentscheidungen

- **Ein Verhaltensschalter statt vieler Flags.** Neuer Enum
  `RestMusicBehavior { NORMAL, REST_PLAYLIST, DROP_LANDING }` in `:core:model`,
  in den Einstellungen waehlbar:
  - `NORMAL`: die App greift nicht ein — die laufende Wiedergabe (Queue,
    Shuffle) laeuft weiter wie bisher. Das ist der Aus-Zustand.
  - `REST_PLAYLIST`: bei Pausenbeginn wird auf eine "Rest/Pause"-Playlist
    umgeschaltet; am Pausenende startet ein Titel aus der "Work"-Playlist.
  - `DROP_LANDING`: wie `REST_PLAYLIST` waehrend der Pause, zusaetzlich wird
    kurz vor Schluss ein "Work"-Titel vorgezogen, dessen Drop das Pausenende
    trifft.
  Damit ist auch die Frage "laeuft die Rest-Playlist bei jeder festen Pause?"
  eindeutig beantwortet: nur bei `REST_PLAYLIST`/`DROP_LANDING`.
- **Drop-Landung als reine Domain-Mathematik** in `:domain:timer`
  (`DropLanding.kt`), direkt neben dem bestehenden `DropRest` (dort leben
  bereits `MarkerPoint`/`PlaybackSample`). Voll unit-testbar, ohne Android.
- **Kopplung ueber Beobachtung der einen `TimerEngine`**, nicht ueber
  Feature-Import. Der Rest-Timer wird heute schon von `:feature:workout`
  ueber `TimerEngine.start(TimerMode.REST, ...)` gestartet. Die
  Orchestrierung lebt im `:feature:player` (einziges Feature, das Playback +
  Marker kennen darf) und reagiert auf `TimerState` (Modus REST + Restzeit).
- **Playlist-Label als additive Spalte** `label TEXT` (nullable) an
  `playlists`, DB v3 -> v4, `MIGRATION_3_4` reines `ALTER TABLE ... ADD
  COLUMN` (nicht destruktiv).
- **Praezision ehrlich benannt.** Die Drop-Landung nutzt die monotone
  Startzeit des Rest-Timers und die Song-Drop-Position; realistisch sind
  ca. +/-100-200 ms (Audio-Puffer/Decoder-Latenz), kein Sample-genaues
  Ausrichten. Manuelle Eingriffe (Play/Pause/Weiter/Seek am Kopfhoerer)
  brechen die Automatik ab (Nutzer hat Vorrang) — analog `DropRestMonitor`.
- **Hardware-Control bleibt Standard-AVRCP.** Play/Pause/Weiter/Zurueck
  laufen ueber die vorhandene `MediaLibrarySession`, Lautstaerke ueber den
  System-Medienstream. Die proprietaere Tastenbelegung des Kopfhoerers
  (z. B. ANC30C) liegt in dessen Firmware/Companion-App; DropSync empfaengt
  nur die Standardbefehle und belegt keine Gesten um.

## Datenmodell-Aenderungen

- `:core:model` (Enums.kt): `PlaylistLabel { REST, WORK }`,
  `RestMusicBehavior { NORMAL, REST_PLAYLIST, DROP_LANDING }`.
- `:core:database`: `PlaylistEntity` + `label: String?`; `DropSyncDatabase`
  `version = 4`; `MIGRATION_3_4` in `Migrations.kt` + Registrierung im
  `DatabaseModule`; Schema-Export `4.json`; `PlaylistDao` + `setLabel`,
  + `observePlaylistsByLabel`, Label in `PlaylistRow`/`observePlaylists`.
- `:data:playback`: `RestMusicSettingsStore` (DataStore) fuer
  `RestMusicBehavior` (+ Vorlauf-Reserve/Lead-Millis, Default aus).
- Kein Loeschen, kein Umbenennen bestehender Spalten/Enums.

## Kernmathematik: Drop-Landung (Skizze)

Reine Funktion in `:domain:timer` (`DropLandingPlanner.plan`):

    data class WorkSongDrop(songId, dropPositionMs, durationMs, markerId)
    data class DropLandingPlan(songId, markerId, startAtPositionMs, startAfterDelayMs)
    sealed DropLandingResult { Scheduled(plan) | NotPossible(reason) }

Gegeben Restzeit R (ms) und ein Kandidat mit Drop-Position D (ab Songanfang):

- R < MIN_REST_MS (Default 5 s)  -> NotPossible(REST_TOO_SHORT)
- keine Kandidaten mit Drop        -> NotPossible(NO_WORK_SONG_WITH_DROP)
- D >= R:  startAtPositionMs = D - R;  startAfterDelayMs = 0
           (Song sofort, vorgespult; nach R ms kommt der Drop = Pausenende)
- D <  R:  startAtPositionMs = 0;      startAfterDelayMs = R - D
           (erst Pausenmusik, dann Song von vorn; nach D ms Drop = Pausenende)

In beiden Faellen trifft der Drop exakt das Pausenende. Fallbacks: keine
"Rest/Pause"-Playlist -> `NORMAL`-Verhalten (Musik laeuft weiter);
`DROP_LANDING` ohne brauchbaren Work-Drop -> Verhalten faellt auf
`REST_PLAYLIST` zurueck (Angebot "Drops zuerst erkennen").

## Phasen und Status

| Phase | Inhalt | Status |
| ----- | ------ | ------ |
| 0 | Plan + ADR-0012 | Abgeschlossen (ADR-0012 in Phase 4 geschrieben) |
| 1 | Playlist-Oberflaeche (F1) auf vorhandener Repo-Schicht | Abgeschlossen |
| 2 | Playlist-Labels + DB v3->v4 (F2) | Abgeschlossen |
| 3 | Rest-Musik-Domain + Einstellungen (RestMusicBehavior, DropLandingPlanner, Settings-UI) | Abgeschlossen |
| 4 | Rest-Musik-Orchestrierung (F3): Coordinator, Rest-Playlist, Drop-Landung, gapless | Abgeschlossen |
| 5 | Hardware-/Touch-Control (F4): Verifikation, Override-Absicherung, Doku | Abgeschlossen (Geraeteabnahme offen) |
| 6 | Extras: Intelligentes Shuffle (A5), Rest-Presets (B8), Get-Ready 3-2-1 (B9) | Abgeschlossen |

### Phase 1 — Playlist-Oberflaeche (F1)

- `:feature:library`: `LibraryViewModel` um Playlist-Zustand und -Aktionen
  erweitern (`playlists`, `songsOfPlaylist`, `createPlaylist`, `rename`,
  `delete`, `addToPlaylist`, `removeFromPlaylist`, `moveInPlaylist`) — nutzt
  das vorhandene `LibraryBrowseRepository`.
- Neue Ansicht "Playlisten" (in `LibraryContent.kt`/View-Chips) + Detailroute
  mit Titelliste, Entfernen, Drag-Reihenfolge; Dialoge Anlegen/Umbenennen/
  Loeschen; im `SongRow`-Ueberlaufmenue (`LibraryLists.kt`) Eintrag "Zu
  Playlist hinzufuegen" (Auswahl-Dialog).
- Strings DE/EN.
- Tests: `LibraryViewModel`-Playlist-Aktionen gegen Fake-Repo.
- Keine DB-Aenderung.

### Phase 2 — Playlist-Labels + Migration (F2)

- `:core:model`: `PlaylistLabel`.
- `:core:database`: Spalte `label`, DB v4, `MIGRATION_3_4`, `4.json`,
  `MigrationTest` 3->4; `PlaylistDao`-Erweiterungen.
- `:domain:library`: `Playlist` + `label`; `LibraryBrowseRepository`
  + `setPlaylistLabel`, + `playlistsByLabel`.
- `:data:library`: Impl; Fakes aktualisieren.
- `:feature:library`: Label-Auswahl (Rest/Pause, Work, keins) in der
  Playlist-Detailansicht + Label-Badge in der Liste; Strings DE/EN.
- Tests: DAO/Migration, Repo-Label-Roundtrip.

### Phase 3 — Rest-Musik-Domain + Einstellungen

- `:core:model`: `RestMusicBehavior`.
- `:domain:timer`: `DropLanding.kt` (siehe Kernmathematik) + Tests
  (synthetische Fixtures analog `OnsetDetectionTest`: Drop spaeter/frueher
  als Restzeit, kein Drop, Restzeit zu kurz, Songwahl).
- `:domain:playback`: `RestMusicSettingsRepository` (Flow<RestMusicBehavior>
  + `setBehavior`).
- `:data:playback`: `RestMusicSettingsStore` (DataStore) + DI-Provider.
- `:feature:settings`: Abschnitt "Musik in Pausen" (drei Optionen mit
  Erklaertext); Strings DE/EN.

### Phase 4 — Rest-Musik-Orchestrierung (F3, Kern)

- `:feature:player`: `RestMusicCoordinator` beobachtet `TimerEngine.state`
  (REST + Restzeit) und `RestMusicSettingsRepository`. Bei Modus
  `REST_PLAYLIST`/`DROP_LANDING`:
  - Pausenbeginn: Queue auf "Rest/Pause"-Playlist setzen und abspielen.
  - `DROP_LANDING`: Work-Titel mit aktiven Markern sammeln
    (`LibraryBrowseRepository.playlistsByLabel` + `songsOfPlaylist` +
    `MarkerRepository`-Drops), `DropLandingPlanner.plan(remaining, ...)`
    aufrufen; zum berechneten Zeitpunkt (monotone Restzeit) per Crossfade auf
    den vorgespulten Work-Titel wechseln.
  - Pausenende (`REST_PLAYLIST`, ohne Landung): Work-Playlist-Titel starten.
  - Manueller Eingriff (Play/Pause/Weiter/Seek) bricht die Automatik ab.
- Timing ueber `elapsedRealtime` relativ zur `TimerSession`-Startzeit;
  Crossfade/gapless nutzt den vorhandenen `CrossfadeController`.
- Tests: `DropLandingPlanner` (Phase 3) plus Fake-basierte Coordinator-Tests
  (Pausenbeginn setzt Rest-Queue; Plan wird erstellt/terminiert).
- ADR-0012, README.

### Phase 5 — Hardware-/Touch-Control (F4)

- Verifizieren, dass die `MediaLibrarySession` die Standardkommandos und die
  Notification-Aktionen vollstaendig anbietet.
- Haertung: manuelle Medientasten waehrend der Automatik brechen die
  Rest-Musik-Steuerung sauber ab (Nutzer-Vorrang); Weiter/Zurueck navigieren
  waehrend der Rest-Playlist innerhalb dieser Playlist.
- Doku `docs/hardware-control.md`: unterstuetzte Standardbefehle, Lautstaerke
  ueber System, Grenze bei proprietaeren Gesten. Geraete-Abnahme (reale
  Kopfhoerer) bleibt wie Schritt 13 geraeteabhaengig offen.

### Phase 6 — Extras (A5/B8/B9)

- **A5 Intelligentes Shuffle**: `:domain:library` `SmartShuffle.kt` (reine,
  getestete Gewichtung ueber `play_stats`/Favoriten, zuletzt Gespielte
  meiden); Einstellungs-Schalter; Verdrahtung im Shuffle-Pfad.
- **B8 Rest-Timer-Presets**: bearbeitbare Schnellwahl (Default 60/90/120/180 s),
  persistiert; Chips im Rest-Dialog (`WorkoutScreen`) + Editor in den
  Einstellungen.
- **B9 Get-Ready-Countdown**: `TimerEngine.start` um optionale
  `prepMs`-Vorbereitungsphase (`PREPARING`, 3-2-1 mit Haptik/Ton) erweitern;
  Einstellung Ein/Aus + Dauer; Tests fuer den PREPARING-Uebergang.

## ADR-0012 (neu, in Phase 4 zu schreiben)

Titel: "Drop-Landung: Work-Titel-Vorlauf trifft das Pausenende". Inhalt:
Warum die Berechnung als reine Domainfunktion in `:domain:timer` liegt; warum
die Kopplung ueber `TimerEngine`-Beobachtung statt Feature-Import laeuft;
Praezisionstoleranz und Abbruch bei manuellem Eingriff; Fallback-Kette.

## Verifikation

Pro Phase (PowerShell, JAVA_HOME auf das Android-Studio-JBR):

    ./gradlew spotlessApply
    ./gradlew <betroffene :module:testDebugUnitTest / :domain:*:test>
    ./gradlew :app:assembleDebug

Der Architektur-Waechter (`:core:testing:ModuleDependencyRulesTest`) und
`spotlessCheck` muessen gruen bleiben. Migrationstest gegen `4.json`.

## Annahmen und offene Punkte

- Waehrend aktiver Rest-Musik-Automatik uebernimmt die App die Queue; die
  vorherige Queue wird nicht wiederhergestellt (Modell: Rest-Musik <-> Work-
  Musik). Bei Modus `NORMAL` bleibt alles unveraendert.
- Songwahl fuer die Drop-Landung: der erste Work-Titel mit brauchbarem Drop
  (respektiert Intelligentes Shuffle, falls aktiv). Verfeinerung spaeter
  moeglich.
- Geraeteabnahme der Hardware-Steuerung (reale Bluetooth-Kopfhoerer) ist wie
  im Bauplan Schritt 13 nicht in der Sandbox automatisierbar.
