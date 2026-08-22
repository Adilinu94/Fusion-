# Design: Flowtimer-Integration — Gemeinsamer Training-Core

**Datum:** 2026-08-22 (Fakten gegen den Code korrigiert 2026-08-22, siehe [Revisionen](#revisionen))
**Status:** Genehmigt (Brainstorming + Grilling-Session, beide 2026-08-22)
**Beteiligte Repos:** Fusion (dieses Repo), Flowtimer (`C:\Users\adini\Desktop\Teanning\Flowtimer`), neu: `training-core` (privates GitHub-Repo, lokaler Arbeitsstand `C:\Users\adini\Desktop\flowrepProjekt\training-core`)

**Verbundene Dokumente:**

| Dokument | Rolle |
|---|---|
| [`2026-08-22-flowtimer-integration-CONTEXT.md`](2026-08-22-flowtimer-integration-CONTEXT.md) | Gesperrte Umsetzungsentscheidungen (E1–E9) und offene Recherchepunkte |
| [`2026-08-22-flowtimer-integration-UI.md`](2026-08-22-flowtimer-integration-UI.md) | Screen-Vertrag des Progress-Dashboards |
| [`../adr/0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md`](../adr/0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md) | Geltungsordnung gegenüber dem Fusionsdesign |
| [`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`](FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md) | Verbindliche visuelle Sprache und Komponententokens |

## Kontext

- **Fusion (DropSync/FlowRep):** Musikplayer + Workouttracker mit BLE/IMU-Rep-Counting (M5StickC-Plus2). 29 Gradle-Module (30 mit `:libs:media3-ffmpeg`), strikte Clean Architecture (Architektur-Tests), Hilt, Room 2.8.4 (DB v8, 29 Entities), Media3, eigene `TimerEngine` mit DropSync-Ruhepausen (Rest bis zum musikalischen Drop). ~50k LOC. Zweisprachige UI (`values/` englisch, `values-de/` deutsch).
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
| 7 | Hosting | `training-core` in einem **privaten GitHub-Repo**, Einbindung per Submodule in beiden Apps (siehe [Revisionen](#revisionen)) |
| 8 | CI | GitHub-CI bleibt **durchgehend aktiv**; zusätzlich lokale Gates (test/spotless/detekt/lint/assemble) vor jedem Commit |
| 9 | PR-Mathematik | **Fusions Mathematik wandert in den Kern**, Flowtimers `bestFor` wird darauf abgebildet (Richtung korrigiert, siehe [Revisionen](#revisionen)) |
| 10 | Progression | **Kein Vorschlags-System** (kein +2,5-kg-Chip, keine Regel im Kern); Ziele mit Statusanzeige bleiben |
| 11 | WIP-Commits | Beim Integrations-Start je ein beschrifteter Checkpoint-Commit pro Repo |
| 12 | Dashboard | Vier Inhalte, aber **nicht gleichwertig**: der Wochenziel-Ring (Default 3 Trainingstage/Woche) ist die dominante Aussage; Streak, Wochen-Volumen (~8 Wochen) und Pro-Übung-Zielstatus sind ihm untergeordnet. Layoutvertrag in [`…-UI.md`](2026-08-22-flowtimer-integration-UI.md) |
| 13 | Ziel-UI | Pflege in der ExerciseLibrary (Zielgewicht + Ziel-Reps als Felder); TrainScreen bleibt frei |
| 14 | Ziel-Semantik | **Satz-basiert, beide Bedingungen:** Ziel erreicht, wenn ein einzelner Satz `gewicht >= zielGewicht` **und** `reps >= zielReps` erfüllt. Maßgeblich ist der beste Satz, nicht der letzte |
| 15 | Wochenstart | **Montag** (ISO-8601) für alle Wochen-Metriken (Ring, Chart) |
| 16 | Übung löschen | **Archivieren** (Soft-Delete): Übung wird ausgeblendet, Sätze/Historie bleiben, wiederherstellbar — Flag existiert bereits, siehe [Revisionen](#revisionen) |
| 17 | Verlaufsliste | **Umschaltung im Progress-Tab** („Übersicht | Verlauf"); Shell bleibt 4 Tabs |
| 18 | Workout-Definition | **Ein Kalendertag mit mindestens einem Satz = ein Workout.** Tagesgrenze lokale Mitternacht. Keine Session-Tabelle, keine Start/Stop-Knöpfe |
| 19 | Kern-Einheit | Öffentliche Schnittstelle in **Kilogramm als `Double`**; jede Kern-Funktion rundet Gewichts-Eingaben am Eingang auf 3 Dezimalstellen |
| 20 | Streak | **Tages-Streak:** Trainingstage in Folge, bricht erst nach **3 zusammenhängenden Ruhetagen**. Zwei Ruhetage sind frei — das 48–72-h-Fenster des Krafttrainings darf den Zähler nicht brechen |
| 21 | Zahlenformate | Gewichte ganzzahlig ohne Dezimalstelle (`95 kg`, nie `95,0 kg`); krumme Werte mit einer Stelle; Volumen unter 1000 kg in kg, darüber in Tonnen mit einer Stelle. Formatierung über `Locale.getDefault()` |

<a name="revisionen"></a>
## Revisionen gegenüber der Erstfassung

Nach Prüfung gegen beide Repos wurden sechs Aussagen dieses Dokuments
richtiggestellt. Die Entscheidungstabelle oben ist bereits korrigiert;
Begründungen und Codestellen stehen in
[`2026-08-22-flowtimer-integration-CONTEXT.md`](2026-08-22-flowtimer-integration-CONTEXT.md).

| Erstfassung | Korrektur |
|---|---|
| `archived`-Flag muss in DB v9 ergänzt werden | Existiert bereits (`ExerciseEntity.is_archived`, `archiveExercise()`, DAO-Query). v9 bringt nur `TargetEntity`. Was fehlt: `UPDATE exercises SET is_archived = 0` und die UI |
| „millikg ist exakt in kg darstellbar" | Falsch — 0,001 ist als Binär-Double nicht exakt. Deshalb Entscheidung 19: Rundung am Kern-Eingang |
| „Alle 33 vorhandenen Unit-Tests bleiben grün" | Flowtimer hat 40 `@Test`-Methoden in 9 Dateien. Abnahmekriterium neu: alle Tests grün, keiner gelöscht oder abgeschwächt |
| „26 Gradle-Module, 28 Entities" | 29 Module (30 mit ffmpeg), 29 Entities. Room v8 stimmt |
| Entscheidung 9: Flowtimers PR-Mathematik ersetzt Fusions | Umgekehrt. Fusion kennt 3 PR-Arten, den Hantel-Multiplikator 1/2 und eine Gleichstandsregel; Flowtimer kennt eine Art und keinen Multiplikator. Fusions Version ist die Grundlage |
| Entscheidung 7/8: nur lokal, CI pausiert | Aufgehoben. Ein Submodule mit absolutem Windows-Pfad macht Fusion auf keinem anderen Rechner und aus keinem Backup mehr herstellbar. Privates GitHub-Repo kostet nichts und hält die CI grün |

## Architektur

### 1. Gemeinsamer Kern: `training-core`

- Neues **privates GitHub-Repo** mit einem **pure Kotlin JVM-Modul** (keine Android-Abhängigkeiten); lokaler Arbeitsstand unter `C:\Users\adini\Desktop\flowrepProjekt\training-core`.
- **Inhalt:** Fusions `WorkoutMath` und `PrCalculator` als Grundlage (3 PR-Arten, Hantel-Multiplikator, Gleichstandsregel), dazu aus Flowtimers `domain\`: `Streak`, `WeekAgg`, `TargetMath` — inklusive aller vorhandenen Unit-Tests beider Seiten. Flowtimers `bestFor` wird als Sonderfall auf Fusions `PrCalculator` abgebildet.
- **Verhaltensänderung im Kern:** `streakCount` (`Streak.kt`) bekommt einen Parameter `maxGapDays: Int = 0`. Default 0 hält Flowtimers Verhalten unverändert; Fusion übergibt 2 (Entscheidung 20). Neue Tests für Lücke 1, 2 und 3 sind Pflicht, die 4 bestehenden `StreakTest`-Fälle bleiben grün. `TargetMath.targetPct` wird auf zwei Dimensionen erweitert (Gewicht und Reps), weil Entscheidung 14 beide Bedingungen prüft.
- **Einbindung:** Git-Submodule aus GitHub in **beiden** Apps (gepinnter Stand). Versionsdisziplin: Version des Kerns bumpen + Submodule-Pointer in beiden Apps aktualisieren.
- **Konventionen:** Kilogramm als `Double` an der öffentlichen Schnittstelle; jede Funktion rundet Gewichts-Eingaben am Eingang auf 3 Dezimalstellen (ganze Gramm) und rechnet intern ganzzahlig weiter — sonst kann Flowtimers `==`-Vergleich auf Gewichte einen PR übersehen. Fusion konvertiert millikg-`Long` ↔ kg an der Repository-Grenze und behält seine Invariante „keine Gleitkommazahlen im Datenmodell". Wochenstart Montag (ISO-8601), Tagesgrenze lokale Mitternacht.
- **Zeitrechnung:** `java.time` mit injizierter `Clock` und `ZoneId` statt `Calendar.getInstance()` — der Kern muss deterministisch testbar sein.

### 2. Flowtimer-Seite (Refactor ohne Verhaltensänderung)

- Lokale `domain\`-Dateien entfernen, `training-core` als Submodule konsumieren.
- **Abnahmekriterium:** Alle vorhandenen Unit-Tests bleiben grün; keiner wird gelöscht oder abgeschwächt. Stand 2026-08-22: 40 `@Test`-Methoden in 9 Dateien, nach Phase 15/16 mehr.
- MotionDetector und alle v1/v2-Features bleiben unangetastet (nur der `domain\`-Import wechselt).

### 3. CI und Qualitätsgates

- GitHub Actions CI (`.github/workflows/ci.yml`) bleibt **durchgehend aktiv** — das private `training-core`-Repo ist per Submodule klonbar (`actions/checkout` mit `submodules: true` und Deploy-Key oder PAT).
- Ergänzend vor jedem Commit lokal: `./gradlew test spotlessCheck detekt lintDebug assembleDebug` und `python3 tools/doku_links_check.py`.
- Toolchain-Spreizung beachten: Fusion baut mit AGP 9.3.1 / Kotlin 2.4.10, Flowtimer mit 9.3.0 / 2.4.0 (beide Gradle 9.5.0). Der Kern fixiert `jvmToolchain(17)` und eine `apiVersion`, die beide Seiten akzeptieren.

### 4. Fusion: Datenlayer

- **Room v8 → v9** (additiv, keine destruktive Migration — die DB enthält die echte Musikbibliothek):
  - Neue Tabelle `TargetEntity` (pro Übung: `exerciseId` als Primary Key oder Unique-Index — genau ein Ziel je Übung, `targetWeightMilliKg: Long`, `targetReps: Int`).
  - Ein `archived`-Flag wird **nicht** ergänzt: `exercises.is_archived` existiert bereits. Nachzuliefern ist nur ein `UPDATE exercises SET is_archived = 0`-Query plus UI zum Wiederherstellen.
  - Migration + erweiterte `MigrationTest`. Schema-Export geht nach `src/test/assets`, nicht `schemas/` — die `9.json` muss dort landen und committet werden.
- **Wochenziel:** DataStore-Preference `weeklyGoal` (Default: 3 Trainingstage/Woche), Muster wie `RestTimerPreferencesStore`.
- **Aggregation:** Streak/Wochenvolumen/-sets als Flow-basierte DAO-Queries auf `flat_sets`; Berechnung über `training-core`. Keine gespeicherten Aggregate.
- **Neue Repos** in `data/workout` mit Interfaces in `domain/workout`: `TargetRepository`, `ProgressRepository`. Einheiten-Adapter millikg ↔ kg sitzen hier.

### 5. Fusion: Migration der Mathematik

- `WorkoutMath` und `PrCalculator` wandern aus `domain/workout` in den Kern; Fusion konsumiert sie von dort. Flowtimers `bestFor`/`e1rm` werden als Sonderfall darauf abgebildet, damit der Kern nur eine Wahrheit enthält.
- Der ungenutzte Session/Cluster/Segment-**Datenpfad** bleibt unangetastet liegen — er wird weder gelöscht noch an die UI angeschlossen. Nur die Mathematik wandert.
- Abgesichert durch Fusions bestehende Tests und Flowtimers 40 Tests; Verhaltensdifferenzen werden vorher als Tests dokumentiert und entschieden.
- **Hinweis für die Planung:** Der heute in der UI wirksame PR-Wert ist `FlatSetDao.getMaxVolumeForExercise` (`MAX(weight_milli_kg * reps)`), also Volumen — nicht Gewicht. Ob das Dashboard diesen Wert behält oder auf die Kern-Bestwerte umstellt, entscheidet die Planung.

### 6. Fusion: Features & UI

Der vollständige Screen-Vertrag steht in
[`2026-08-22-flowtimer-integration-UI.md`](2026-08-22-flowtimer-integration-UI.md).
Kurzfassung:

- **Verlauf-Tab wird Progress-Dashboard** mit Umschaltung „Übersicht | Verlauf".
- `HistoryScreen` zieht von `:app` nach **`:feature:progress`** (Architektur-Regeln: Features importieren sich nicht gegenseitig, DB nur im Datenlayer). Dabei: der Screen hat heute **keine** `stringResource`-Aufrufe — alle Texte stehen fest auf Deutsch im Code und müssen nach `values/` + `values-de/`. `HistoryUiStateTest` zieht mit um.
- **Ziele:** Pflege in der ExerciseLibrary (Zielgewicht + Ziel-Reps als Felder im Bearbeiten-Dialog); Anzeige des Status lesend im Dashboard. Kein Vorschlags-Chip, keine Auto-Änderung von Satzgewichten im TrainScreen.
- **Übungsverwaltung:** Fusions `ExerciseLibrary` + Muscle-Slugs bleiben; CRUD wird vervollständigt (anlegen/bearbeiten/**archivieren**/wiederherstellen, deutsche Muskelgruppen). Kein zweites Übungsmodell.
- **Design:** verbindlich ist [`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`](FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md) — Poppins, Dark-first, Lime nur als eine Hauptaktion oder aktiver Fortschritt pro Kontext. Strings zweisprachig.

### 7. Fehlerbehandlung

- Neue Repos folgen dem `AppResult/AppError`-Vertrag (`core/common`).
- Dashboard zeigt Leerzustände („Noch keine Sätze geloggt") statt Fehlerzustände, wenn keine Daten vorliegen.

### 8. Testing

- `training-core`: Fusions Mathematik-Tests + portierte Flowtimer-Tests + neu:
  - `TargetMath` — satzbasierte Ziel-Erfüllung mit **beiden** Bedingungen, inklusive der Falle „hohes Volumen bei zu leichtem Gewicht erfüllt nicht".
  - `streakCount` mit `maxGapDays` — Lücke 1, 2 und 3; Grenzfall „heute noch kein Satz, gestern schon".
  - Eingangs-Rundung auf 3 Dezimalstellen, inklusive `bestFor`-Vergleich mit zwei Werten, die als Double ungleich, als Gramm aber gleich sind.
  - Zahlenformatierung: ganzzahlig ohne Dezimalstelle, krummer Wert mit einer, Volumen-Schwellwert bei 1000 kg.
- Fusion: DAO-/Repository-Tests (Robolectric), MigrationTest v8→v9, ViewModel-Tests, Emulator-Smoke-Test fürs Dashboard.
- Non-Regression: bestehende Test-Suiten bleiben vollständig grün (insbesondere Musikplayer/TimerEngine/BLE).

## Non-Goals

- Kein MotionDetector-/Autopause-Port in Fusion (manuelle Pause bleibt; MotionDetector bleibt Flowtimer-exklusiv).
- Kein Progressions-Vorschlags-System (+2,5-kg-Regel bewusst gestrichen — auch nicht im Kern).
- Keine Datenmigration aus Flowtimer.
- Keine geteilte UI-, Daten- oder DB-Schicht (Designsysteme und Room-Versionen bewusst getrennt).
- Keine Änderung an Fusions `TimerEngine`/DropSync-Flow.
- Keine Session-Tabelle und keine Start/Stop-Knöpfe im TrainScreen (Entscheidung 18).
- Kein Löschen und kein Anschließen des ungenutzten Session/Cluster/Segment-Datenpfads.

## Implementierungsreihenfolge

1. **Vorbedingung 0:** Flowtimer v2 fertigstellen (Phase 15 Builder + Phase 16 Polish) — in Flowtimer, auf alter Struktur.
2. **Vorbedingung 1:** Beim Integrations-Start beide Working Trees committen — je ein beschrifteter Checkpoint-Commit (Fusion ~162, Flowtimer ~1.100 offene Änderungen).
3. Privates `training-core`-Repo auf GitHub anlegen; Fusions `WorkoutMath`/`PrCalculator` hineinziehen, Flowtimers `Streak`/`WeekAgg`/`TargetMath` ergänzen, `bestFor` darauf abbilden. Flowtimer auf Submodule umstellen, alle Flowtimer-Tests grün.
4. Fusion: Submodule einbinden (CI-Checkout mit Deploy-Key), DB v9 (`TargetEntity`) + `TargetRepository`/`ProgressRepository`.
5. Fusion: Mathematik-Umzug abschließen (`domain/workout` konsumiert den Kern).
6. Fusion: Progress-Dashboard (`:feature:progress`, Übersicht | Verlauf) nach [`2026-08-22-flowtimer-integration-UI.md`](2026-08-22-flowtimer-integration-UI.md).
7. Fusion: Ziele-Pflege in der ExerciseLibrary + Übungs-CRUD (Archivieren/Wiederherstellen).
8. Arbeit läuft auf `fusion/training-core`; jeder Schritt ein eigener Commit, Merge nach `master` erst mit grüner CI.
