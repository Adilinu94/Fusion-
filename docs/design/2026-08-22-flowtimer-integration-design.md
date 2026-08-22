# Design: Flowtimer-Integration — Gemeinsamer Training-Core

**Datum:** 2026-08-22
**Status:** Genehmigt (Brainstorming-Session)
**Beteiligte Repos:** Fusion (dieses Repo), Flowtimer (`C:\Users\adini\Desktop\Teanning\Flowtimer`), neu: `training-core`

## Kontext

- **Fusion (DropSync/FlowRep):** Musikplayer + Workouttracker mit BLE/IMU-Rep-Counting (M5StickC-Plus2). 26 Gradle-Module, strikte Clean Architecture (Architektur-Tests), Hilt, Room 2.8.4 (DB v8, 28 Entities), Media3, eigene `TimerEngine` mit DropSync-Ruhepausen (Rest bis zum musikalischen Drop). ~50k LOC.
- **Flowtimer:** Eigenständige, weitergepflegte App. Single Module, kein DI (manuelle Singletons), Room 3.0.1 (`androidx.room3`, DB v4). v1 sensorgestützter Rest-Timer + v2 GetFitPro-Style Tracker. `domain\` ist pure Kotlin (Android-frei), voll getestet.

Beide Apps haben eigene Workout-Tracker, eigene `TimerEngine` und inkompatible Room-Versionen. Es geht daher um Zusammenführen von Logik, nicht um Code-Verschmelbung.

## Ziel

**Best-of-Both-Merge:** Fusion bleibt Basistracker (wegen BLE-Rep-Counting und DropSync-Kopplung). Flowtimers Stärken fließen in Fusion ein; am Ende existiert genau ein Tracker in Fusion.

## Entscheidungen (Brainstorming 2026-08-22)

1. **Best-of-Both-Merge** — Fusions Tracker ist Basis, kein Parallelbetrieb.
2. **Scope:** Fortschritts-Dashboard, Ziele + Progression, Übungsverwaltung. **Kein MotionDetector** — Pause wird manuell ausgelöst (Fusions bestehender TimerEngine-Flow bleibt unverändert).
3. **Keine Datenmigration** — Fusion startet mit leerem Workout-Bereich.
4. **Flowtimer bleibt eigenständig gepflegt** — deshalb kein einmaliger Port, sondern ein geteilter Kern.
5. **Ansatz A: Gemeinsamer Domain-Kern** (`training-core`), statt Einmal-Port (B) oder Modul-Einbettung (C, verworfen wegen Room-/DI-Inkompatibilität).

## Architektur

### 1. Gemeinsamer Kern: `training-core`

- Neues eigenständiges Git-Repo mit einem **pure Kotlin JVM-Modul** (keine Android-Abhängigkeiten).
- **Inhalt** (aus Flowtimers `domain\` extrahiert): `Pr` (bestFor, e1RM), `Streak`, `WeekAgg`, `TargetMath`, +2,5-kg-Progressionsregel — inklusive der vorhandenen Unit-Tests.
- **Einbindung:** Git-Submodule in **beiden** Apps (gepinnter Stand, CI-freundlich, kein Publish-Schritt). Versionsdisziplin: SemVer des Kerns bumpen + Submodule-Pointer in beiden Apps aktualisieren.
- **Einheiten-Konvention:** Der Kern rechnet mit **kg als `Double`** (Flowtimer-Konvention). Fusion konvertiert an seiner Repository-Grenze millikilogram-`Long` ↔ kg — die Umrechnung sitzt dort, wo Fusions Konvention heute schon gelebt wird. Keine Präzisionsverluste (millikg ist exakt in kg darstellbar).

### 2. Flowtimer-Seite (Refactor ohne Verhaltensänderung)

- Lokale `domain\`-Dateien entfernen, `training-core` als Submodule konsumieren.
- **Abnahmekriterium:** Alle 33 vorhandenen Unit-Tests bleiben grün.

### 3. Fusion: Datenlayer

- **Room v8 → v9:** Neue Tabelle `TargetEntity` (pro Übung: `exerciseId` FK, `targetWeightMilliKg: Long`, `targetReps: Int`). Migration + erweiterte `MigrationTest`.
- **Wochenziel:** DataStore-Preference `weeklyGoal` = **Anzahl Workouts pro Woche**.
- **Aggregation:** Streak/Wochenvolumen/-sets als Flow-basierte DAO-Queries auf den existierenden Set-/Session-Tabellen. Keine gespeicherten Aggregate (konsistent mit Fusions Stil).
- **Neue Repos** in `data/workout` mit Interfaces in `domain/workout`: `TargetRepository`, `ProgressRepository` (nutzt `training-core` für Streak/Aggregation/Progression).

### 4. Fusion: Features & UI

- **Verlauf-Tab wird Progress-Dashboard:** Streak-Anzeige, Wochenziel-Ring, Wochen-Aggregation (Volumen/Sets), Pro-Übung-Fortschritt inkl. Zielstatus. Die flache Verlaufsliste bleibt im Dashboard erreichbar.
- `HistoryScreen` zieht von `:app` nach **`:feature:progress`** (Architektur-Regeln: Features importieren sich nicht gegenseitig, DB nur im Datenlayer).
- **Ziele + Progression im Train-Flow:** Nach Set-Abschluss (BLE-gezählt oder manuell) evaluiert `TargetMath`: Ziel erreicht → Vorschlag-Chip „+2,5 kg nächstes Set" im TrainScreen. Ziele werden pro Übung gepflegt (Übungsbibliothek/Übungsdialog).
- **Übungsverwaltung:** Fusions `ExerciseLibrary` + Muscle-Slugs bleiben; CRUD für eigene Übungen wird vervollständigt (anlegen/bearbeiten/löschen, deutsche Muskelgruppen). Kein zweites Übungsmodell.
- **Design:** Fusions Designsystem (Raleway, Glass, Material 3). Kein Übernehmen des Flowtimer-Looks.

### 5. Fehlerbehandlung

- Neue Repos folgen dem `AppResult/AppError`-Vertrag (`core/common`).
- Dashboard zeigt Leerzustände („Noch keine Sessions") statt Fehlerzustände, wenn keine Daten vorliegen.

### 6. Testing

- `training-core`: portierte Flowtimer-Tests + neue Tests für `TargetMath`/Progressionsregel.
- Fusion: DAO-/Repository-Tests (Robolectric), MigrationTest v8→v9, ViewModel-Tests, Emulator-Smoke-Test für Dashboard und Ziel-Vorschlag.

## Non-Goals

- Kein MotionDetector-/Autopause-Port (manuelle Pause bleibt).
- Keine Datenmigration aus Flowtimer.
- Keine geteilte UI-, Daten- oder DB-Schicht (Designsysteme und Room-Versionen bewusst getrennt).
- Keine Änderung an Fusions `TimerEngine`/DropSync-Flow.

## Implementierungsreihenfolge

1. **Vorbedingung:** Beide Repos ihre Working Trees committen lassen (Fusion ~162, Flowtimer ~1.100 offene Änderungen — sonst steht die Integration auf Sand).
2. `training-core`-Repo anlegen (Extraktion aus Flowtimer), Flowtimer auf Submodule umstellen, Tests grün.
3. Fusion: DB v9 + `TargetRepository`/`ProgressRepository`.
4. Fusion: Progress-Dashboard (`:feature:progress`).
5. Fusion: Ziel-Vorschlag im Train-Flow + Übungs-CRUD vervollständigen.
6. Jeder Schritt ein eigener Commit auf `master`.
