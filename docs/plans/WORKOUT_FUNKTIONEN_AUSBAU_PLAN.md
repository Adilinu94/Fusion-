# DropSync Workout-Funktionen-Ausbau — Plan (umgesetzt am 28.07.2026)

Dieser Plan wurde vollstaendig umgesetzt (Phasen A-F) und dient als
dauerhafte Dokumentation der Grundlage, analog zu
`AUDIO_ENGINE_AUSBAU_PLAN.md`. Verbindlich bleiben die Regeln des
Bauplans: Gewichte als ganze Millikilogramm (Long), Zeiten als
UTC-Epoch-Millis, Enums als stabile Strings, kein Feature importiert
ein anderes Feature, keine destruktive Migration, kein
Netzwerk/Analytics. Die Cue-Ausgabelogik (180/120/60/30 s TTS; 10-1 s
Haptik/visuell; 0 s Haptik+Ton) blieb unveraendert.

## Ziel

Die vorhandene Workout-Daten-/Domainschicht (Schritte 9-11) um die
fehlende Feature-UI, ergaenzende Repository-/Domain-Methoden, eine
Chart-Komponente und die aktive Musik-Verdrahtung erweitern:

- 1 Kern-Logging: Bibliothek + eigene Uebungen mit Muskel-Mapping,
  Prefill aus letzter Session, Uebungstausch KEEP/MOVE/DISCARD,
  Rest-Timer pro Uebung gemerkt (kein globaler Standard).
- 2 Trainingsplanung: Routinen (Template speichern, aus Session
  erstellen), letzte Session mit einem Tap wiederholen.
- 3 Fortschritt/Analyse: 3 getrennte PR-Kategorien, 1RM nur als Trend
  (nie PR), Detail-Charts, Klassifizierung progressiv/stagnierend/
  ruecklaeufig mit Plateau-Alarm und Vorschlag.
- 8 Musik: Rest optional per DropSync (naechster Satz auf dem naechsten
  Drop), Session-Song-Verknuepfung ("zu diesem PR lief dieser Track").
- 9 Lokal-first, vollstaendig offline (keine neue Abhaengigkeit nach
  aussen).

## Architekturentscheidungen

- Rest-Timer aus dem Workout nutzt die vorhandene, einzige
  `TimerEngine`: `:feature:workout` -> `:domain:timer` (Praezedenz
  `:feature:timer`/`:feature:player`). Genau ein sichtbarer Timer.
- Musik-Verknuepfung: `:data:workout` -> `:domain:playback` (Praezedenz
  `:data:timer` -> `:domain:playback`); `WorkoutRepositoryImpl` erfasst
  beim Satzabschluss best-effort einen `PlaybackSnapshot` (Fehler
  duerfen den Satz nie fehlschlagen lassen).
- DropSync-Rest aus dem Workout: schmale Domain-Schnittstelle
  `DropRestRequestBus` in `:domain:timer`, Singleton-Impl in
  `:data:timer`; `:feature:workout` sendet den Wunsch,
  `DropRestViewModel` in `:feature:player` konsumiert ihn (einzeiliger
  Collector — einziger Eingriff ins Musik-Feature, Koordinationspunkt
  mit dem Musik-Agenten).
- Diagramme: eigene animierte Compose-Canvas-Charts in
  `:core:designsystem` (`chart/Charts.kt`), keine neue Abhaengigkeit,
  Lime-Akzent, Barrierefreiheit ueber zusammenfassende
  `contentDescription`.
- Genau eine Schema-Migration (v1 -> v2) fuer die neue Tabelle
  `exercise_rest_prefs`; alle anderen Features nutzen Bestandstabellen.

## Phasen und Status

| Phase | Inhalt | Status |
|---|---|---|
| A | Datenschicht + Migration v2: `RestMode`-Enum, `ExerciseRestPrefEntity` (`exercise_rest_prefs`), `MIGRATION_1_2` (`Migrations.kt`, Registrierung in `DatabaseModule`), DAO-Erweiterungen (Bibliothek, Rest-Prefs, letzte Session, Uebungstausch, `getPlayedTracksForSession`) | Umgesetzt |
| B | Domain-Logik: `SwapStrategy`, `RestPref`, `CustomExerciseInput`, `MuscleContribution`, `ExerciseDetail`, `Slugs` (Transliteration, Regex `[a-z0-9]+(_[a-z0-9]+)*`), `ProgressSeriesBuilder`, `ProgressionClassifier` (+-1.5 %-Schwelle, Plateau >= 3 Sessions ohne Bestleistung), `WorkoutRepository`-Vertrag + Impl (Swap in einer Transaktion mit PR-Neuberechnung, `repeatLastSession`, Routinen, Analyse) | Umgesetzt |
| C | Musik-Kopplung: `DropRestRequestBus` (Domain + Data-Singleton), Snapshot-Erfassung in `completeCluster` (best-effort), Collector in `DropRestViewModel` | Umgesetzt |
| D | Chart-Komponente: `LineChart` + `BarChart` in `com.dropsync.core.designsystem.chart` (nur Compose-Canvas, animiert) | Umgesetzt |
| E | Feature-UI: interner NavHost `WorkoutFeature` (Routen `session`, `library`, `exercise/{id}`, `routines`, `routine/{id}`, `progress`); SessionScreen mit Prefill, Rest-Button + Modus Normal/DropSync, Swap-Dialog, "Als Routine speichern", "Letzte Session wiederholen", Track-Chip; Uebungsbibliothek + Neuanlage (Name de/en, Art, Equipment, Muskelprozente); Routinen + Editor; Uebungsdetail (PRs, 1RM-Trend, Volumen, Gewichtsverlauf, Klassifizierungs-Badge); Fortschrittsuebersicht; Strings en/de; `:app` ruft `WorkoutFeature` | Umgesetzt |
| F | Tests: `SlugsTest`, `ProgressAnalysisTest`, `WorkoutRepositoryImplTest` (18 Tests inkl. Swap KEEP/MOVE/DISCARD, `repeatLastSession`, Routine-aus-Session, RestPref-Roundtrip, Snapshot best-effort), `MigrationTest` 1 -> 2 gegen exportiertes `2.json`; Architektur-Test und `spotlessCheck` gruen | Umgesetzt |

## Verifikation

`:core:testing:test`, `:domain:workout:test`, `:domain:timer:test`,
`:data:timer`/`:data:workout`/`:core:database`/`:feature:player`/
`:feature:workout` Unit-Tests sowie `:app:compileDebugKotlin` liefen
vollstaendig gruen (230 Tasks mit `--rerun-tasks`); `spotlessCheck`
gruen. Geraete-/Emulator-Abnahme der neuen Screens folgt mit Schritt 13.

## Annahmen und Koordinationspunkte

- MOVE beim Uebungstausch schreibt Saetze bewusst der neuen Uebung zu
  (explizite Nutzerentscheidung); KEEP/DISCARD lassen die Historie
  unveraendert bzw. entfernen sie nur in dieser Session.
- Der `DropRestViewModel`-Collector ist der einzige Eingriff ins
  Musik-Feature und mit dem Musik-Agenten abzustimmen.
- Routinen werden ueber `createRoutine`/`createRoutineFromSession`
  angelegt; ein nachtraegliches Bearbeiten bestehender Routinen ist
  bewusst nicht Teil dieses Ausbaus.
