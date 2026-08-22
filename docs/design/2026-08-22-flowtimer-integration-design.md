# Design: Flowtimer-Integration — Gemeinsamer Training-Core

**Datum:** 2026-08-22
**Status:** Genehmigt (Brainstorming + Grilling-Session, beide 2026-08-22)
**Beteiligte Repos:** Fusion (dieses Repo), Flowtimer (`C:\Users\adini\Desktop\Teanning\Flowtimer`), neu: `training-core` (lokal, `C:\Users\adini\Desktop\flowrepProjekt\training-core`)

## Kontext

- **Fusion (DropSync/FlowRep):** Musikplayer + Workouttracker mit BLE/IMU-Rep-Counting (M5StickC-Plus2). 26 Gradle-Module, strikte Clean Architecture (Architektur-Tests), Hilt, Room 2.8.4 (DB v8, 28 Entities), Media3, eigene `TimerEngine` mit DropSync-Ruhepausen (Rest bis zum musikalischen Drop). ~50k LOC. Zweisprachige UI (`values/` englisch, `values-de/` deutsch).
- **Flowtimer:** Eigenständige, weitergepflegte App. Single Module, kein DI (manuelle Singletons), Room 3.0.1 (`androidx.room3`, DB v4). v1 sensorgestützter Rest-Timer (MotionDetector) + v2 GetFitPro-Style Tracker (Phase 15 Builder + 16 Polish offen). `domain\` ist pure Kotlin (Android-frei), voll getestet.

Beide Apps haben eigene Workout-Tracker, eigene `TimerEngine` und inkompatible Room-Versionen. Es geht daher um Zusammenführen von Logik, nicht um Code-Verschmelzung.

## Ziel

**Best-of-Both-Merge:** Fusion wird die **finale App** — ein vollwertiger Musikplayer mit Workout-Log, DropSync/Dropbeat-Funktion und BLE/IMU-basiertem automatischem Wiederholungszählen, erweitert um Flowtimers Fortschritts-Dashboard, Übungs-Ziele und Übungsverwaltung.

### Non-Regression-Klausel (verbindlich)

Keine Funktion irgendeines Repos geht verloren:

- **Fusion verliert nichts:** Musikplayer (inkl. EQ/DSP/Playlists), Workout-Log, DropSync/Dropbeat, BLE/IMU-Rep-Counting, TimerEngine bleiben vollständig erhalten. Non-Regression ist explizites Abnahmekriterium.
- **Flowtimer bleibt** als eigenständige App vollständig erhalten und wird weitergepflegt (inkl. MotionDetector, der **exklusiv dort** bleibt — in Fusion wird die Pause weiterhin manuell ausgelöst).
- Geteilt wird nur der neue `training-core`; Fusion erhält ausschließlich Funktionen dazu.

## Entscheidungen

| # | Thema | Entscheidung |
|---|-------|--------------|
| 1 | Merge-Form | Best-of-Both-Merge; Fusion ist Basis, ein Tracker in Fusion |
| 2 | Scope | Fortschritts-Dashboard, Ziele, Übungsverwaltung; **kein MotionDetector in Fusion** |
| 3 | Datenmigration | Keine — Fusion startet mit leerem Workout-Bereich |
| 4 | Flowtimer-Zukunft | Bleibt eigenständig gepflegt |
| 5 | Ansatz | Gemeinsamer Domain-Kern (`training-core`) statt Port oder Modul-Einbettung |
| 6 | Reihenfolge | Flowtimer v2 wird **zuerst fertiggestellt** (Phase 15 + 16); Integration startet danach |
| 7 | Hosting | `training-core` **nur lokal**, Einbindung per Pfad-Submodule in beiden Apps |
| 8 | CI | GitHub-CI pausiert für die Dauer der lokal gehosteten Phase; Qualitätsgates laufen lokal (test/spotless/detekt). Späterer Umzug auf GitHub ohne Architekturänderung möglich |
| 9 | PR-Mathematik | **Voll ersetzen:** PrCalculator/WorkoutMath migrieren auf `training-core`, Duplikate löschen |
| 10 | Progression | **Kein Vorschlags-System** (kein +2,5-kg-Chip, keine Regel im Kern); Ziele mit Statusanzeige bleiben |
| 11 | WIP-Commits | Beim Integrations-Start je ein beschrifteter Checkpoint-Commit pro Repo |
| 12 | Dashboard | Genau 4 Elemente: Streak, Wochenziel-Ring (Default 3/Woche), Wochen-Volumen/Sets (~8 Wochen), Pro-Übung-Fortschritt mit Zielstatus |
| 13 | Ziel-UI | Pflege in der ExerciseLibrary (Zielgewicht + Ziel-Reps als Felder); TrainScreen bleibt frei |
| 14 | Ziel-Semantik | **Satz-basiert:** Ziel erreicht, wenn ein einzelner Satz ≥ Zielgewicht × Ziel-Reps |
| 15 | Wochenstart | **Montag** (ISO-8601) für alle Wochen-Metriken (Ring, Chart, Streak-Woche) |
| 16 | Übung löschen | **Archivieren** (Soft-Delete): Übung wird ausgeblendet, Sätze/Historie bleiben, wiederherstellbar |
| 17 | Verlaufsliste | **Umschaltung im Progress-Tab** („Übersicht | Verlauf"); Shell bleibt 4 Tabs |

## Architektur

### 1. Gemeinsamer Kern: `training-core`

- Neues lokales Git-Repo unter `C:\Users\adini\Desktop\flowrepProjekt\training-core` mit einem **pure Kotlin JVM-Modul** (keine Android-Abhängigkeiten).
- **Inhalt** (aus Flowtimers `domain\` extrahiert): `Pr` (bestFor, e1RM), `Streak`, `WeekAgg`, `TargetMath` (ohne Vorschlagslogik) — inklusive der vorhandenen Unit-Tests.
- **Einbindung:** Git-Submodule per lokalem Pfad in **beiden** Apps (gepinnter Stand). Versionsdisziplin: Version des Kerns bumpen + Submodule-Pointer in beiden Apps aktualisieren.
- **Konventionen:** Kilogramm als `Double` im Kern (Fusion konvertiert millikilogram-`Long` ↔ kg an der Repository-Grenze; millikg ist exakt in kg darstellbar). Wochenstart Montag (ISO-8601).

### 2. Flowtimer-Seite (Refactor ohne Verhaltensänderung)

- Lokale `domain\`-Dateien entfernen, `training-core` als Submodule konsumieren.
- **Abnahmekriterium:** Alle 33 vorhandenen Unit-Tests bleiben grün.
- MotionDetector und alle v1/v2-Features bleiben unangetastet (nur der `domain\`-Import wechselt).

### 3. CI während der lokal gehosteten Phase

- GitHub Actions CI für Fusion wird pausiert (Workflow deaktivieren), da GitHub das lokale Submodule nicht klonen kann.
- Ersatz: Qualitätsgates laufen lokal über Gradle (`test`, `spotlessCheck`, `detekt`, `assembleDebug`).
- Der spätere Umzug von `training-core` auf GitHub (privat) reaktiviert die CI, ohne dass sich an der Architektur etwas ändert.

### 4. Fusion: Datenlayer

- **Room v8 → v9** (additiv, keine destruktive Migration — die DB enthält die echte Musikbibliothek):
  - Neue Tabelle `TargetEntity` (pro Übung: `exerciseId` FK, `targetWeightMilliKg: Long`, `targetReps: Int`).
  - `archived`-Flag an der Exercise-Entity (Soft-Delete, wiederherstellbar).
  - Migration + erweiterte `MigrationTest`.
- **Wochenziel:** DataStore-Preference `weeklyGoal` (Default: 3 Workouts/Woche).
- **Aggregation:** Streak/Wochenvolumen/-sets als Flow-basierte DAO-Queries auf den existierenden Set-/Session-Tabellen; Berechnung über `training-core`. Keine gespeicherten Aggregate.
- **Neue Repos** in `data/workout` mit Interfaces in `domain/workout`: `TargetRepository`, `ProgressRepository`. Einheiten-Adapter millikg ↔ kg sitzen hier.

### 5. Fusion: Migration der Mathematik (Vollersatz)

- `PrCalculator`/`WorkoutMath`-Callstellen werden auf `training-core` migriert; die Duplikate werden gelöscht — `training-core` ist die **einzige** Mathematik-Quelle in Fusion.
- Abgesichert durch Fusions bestehende Tests; Verhaltensdifferenzen werden vor dem Löschen als Tests dokumentiert und entschieden.

### 6. Fusion: Features & UI

- **Verlauf-Tab wird Progress-Dashboard** mit Umschaltung „Übersicht | Verlauf":
  - **Übersicht:** Streak-Anzeige, Wochenziel-Ring (Workloads diese Woche vs. Wochenziel), Wochen-Volumen/-sets (~8 Wochen, Balken/Sparkline), Pro-Übung-Fortschritt mit Zielstatus (erreicht / Differenz offen).
  - **Verlauf:** die heutige flache Session-Liste, unverändert.
- `HistoryScreen` zieht von `:app` nach **`:feature:progress`** (Architektur-Regeln: Features importieren sich nicht gegenseitig, DB nur im Datenlayer).
- **Ziele:** Pflege in der ExerciseLibrary (Zielgewicht + Ziel-Reps als Felder im Bearbeiten-Dialog); Anzeige des Status lesend im Dashboard. Kein Vorschlags-Chip, keine Auto-Änderung von Satzgewichten im TrainScreen.
- **Übungsverwaltung:** Fusions `ExerciseLibrary` + Muscle-Slugs bleiben; CRUD wird vervollständigt (anlegen/bearbeiten/**archivieren**, deutsche Muskelgruppen). Kein zweites Übungsmodell.
- **Design:** Fusions Designsystem (Raleway, Glass, Material 3); Strings zweisprachig (`values/` + `values-de/`).

### 7. Fehlerbehandlung

- Neue Repos folgen dem `AppResult/AppError`-Vertrag (`core/common`).
- Dashboard zeigt Leerzustände („Noch keine Sessions") statt Fehlerzustände, wenn keine Daten vorliegen.

### 8. Testing

- `training-core`: portierte Flowtimer-Tests + neue Tests für `TargetMath` (satzbasierte Ziel-Erfüllung).
- Fusion: DAO-/Repository-Tests (Robolectric), MigrationTest v8→v9, ViewModel-Tests, Emulator-Smoke-Test fürs Dashboard.
- Non-Regression: bestehende Test-Suiten bleiben vollständig grün (insbesondere Musikplayer/TimerEngine/BLE).

## Non-Goals

- Kein MotionDetector-/Autopause-Port in Fusion (manuelle Pause bleibt; MotionDetector bleibt Flowtimer-exklusiv).
- Kein Progressions-Vorschlags-System (+2,5-kg-Regel bewusst gestrichen — auch nicht im Kern).
- Keine Datenmigration aus Flowtimer.
- Keine geteilte UI-, Daten- oder DB-Schicht (Designsysteme und Room-Versionen bewusst getrennt).
- Keine Änderung an Fusions `TimerEngine`/DropSync-Flow.
- Keine aktive GitHub-CI während der lokal gehosteten `training-core`-Phase.

## Implementierungsreihenfolge

1. **Vorbedingung 0:** Flowtimer v2 fertigstellen (Phase 15 Builder + Phase 16 Polish) — in Flowtimer, auf alter Struktur.
2. **Vorbedingung 1:** Beim Integrations-Start beide Working Trees committen — je ein beschrifteter Checkpoint-Commit (Fusion ~162, Flowtimer ~1.100 offene Änderungen).
3. `training-core`-Repo anlegen (Extraktion aus Flowtimer), Flowtimer auf Submodule umstellen, alle 33 Tests grün.
4. Fusion: GitHub-CI pausieren; DB v9 (`TargetEntity`, `archived`-Flag) + `TargetRepository`/`ProgressRepository`.
5. Fusion: Mathematik-Vollersatz (PrCalculator/WorkoutMath → `training-core`).
6. Fusion: Progress-Dashboard (`:feature:progress`, Übersicht | Verlauf).
7. Fusion: Ziele-Pflege in der ExerciseLibrary + Übungs-CRUD (Archivierung).
8. Jeder Schritt ein eigener Commit auf `master`.
