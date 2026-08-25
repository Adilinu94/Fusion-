# Kontext: Flowtimer-Integration — gesperrte Umsetzungsentscheidungen

**Datum:** 2026-08-22
**Status:** Entschieden (Diskussion nach Prüfung beider Repos)
**Gehört zu:** [`2026-08-22-flowtimer-integration-design.md`](2026-08-22-flowtimer-integration-design.md)

<a name="zweck"></a>
## Zweck

Das Design-Dokument beschreibt **was** gebaut wird. Diese Datei hält die
Umsetzungsentscheidungen fest, die dort offen oder falsch waren. Wer den
Kern extrahiert oder das Dashboard baut, liest **beide** Dateien; bei
Widerspruch gilt diese hier, weil sie jünger und gegen den Code geprüft
ist.

<a name="korrekturen"></a>
## Korrekturen am Design-Dokument (gegen Code geprüft)

Diese Punkte im Design-Dokument stimmen nicht mit dem Repo überein und
sind hiermit richtiggestellt:

| Aussage im Design-Dokument | Tatsächlicher Stand |
|---|---|
| `archived`-Flag muss in v9 ergänzt werden | Existiert bereits: `ExerciseEntity.is_archived` (`core/database/src/main/kotlin/com/dropsync/core/database/entity/WorkoutEntities.kt:52`), `archiveExercise()` (`data/workout/src/main/kotlin/com/dropsync/data/workout/WorkoutRepositoryImpl.kt:448`), DAO (`core/database/src/main/kotlin/com/dropsync/core/database/dao/WorkoutDaos.kt:86`). Auch `routines` hat es (Zeile 140). |
| „millikg ist exakt in kg darstellbar" | Falsch. 0,001 ist als Binär-Double nicht exakt. Siehe [Kern-Einheit](#e1). |
| „Alle 33 vorhandenen Unit-Tests bleiben grün" | Flowtimer hat 40 `@Test`-Methoden in 9 Dateien (`PrTest` 4, `SessionEngineTest` 13, `StreakTest` 4, `TargetMathTest` 2, `WeekAggTest` 3, `FormattersTest` 4, `RestMapTest` 4, `StatsTest` 2, `WeekBarsTest` 4). Nach Phase 15+16 werden es mehr. |
| „26 Gradle-Module, 28 Entities" | 29 Module (30 mit `:libs:media3-ffmpeg`), 29 Entities, Room v8 — v8 stimmt. |
| PR-Mathematik „voll ersetzen" durch Flowtimers | Umgekehrt, siehe [Mathematik-Richtung](#e6). |
| `training-core` privat, CI-Checkout mit Deploy-Key (Entscheidung 7, Architektur 3, Reihenfolge 4) | Repo ist **öffentlich**, Checkout braucht nur `submodules: recursive`. Siehe Nachtrag in [E3](#e3). |

<a name="entscheidungen"></a>
## Gesperrte Entscheidungen

<a name="e1"></a>
### E1 — Kern-Einheit: Kilogramm als `Double`, Eingang gerundet

Die öffentliche Schnittstelle von `training-core` nimmt und gibt
**Kilogramm als `Double`** (Flowtimers Format). Damit muss Flowtimer nicht
umgebaut werden.

**Pflicht dabei:** Jede Kern-Funktion rundet Gewichts-Eingaben sofort auf
**drei Dezimalstellen** (ganze Gramm) und rechnet intern mit ganzen
Zahlen weiter. Grund: Flowtimers `bestFor` vergleicht Gewichte mit `==`
(`app/src/main/java/com/flowtimer/app/domain/Pr.kt:16`). Bei
ungerundeten Doubles kann dieser Vergleich fehlschlagen und ein PR wird
nicht erkannt.

Fusion konvertiert an der Repository-Grenze `Long` millikg ↔ `Double` kg.
Fusions eigene Invariante bleibt unangetastet: keine Gleitkommazahlen in
Entities oder im Datenmodell
(`core/database/src/main/kotlin/com/dropsync/core/database/DropSyncDatabase.kt:53`,
`domain/workout/src/main/kotlin/com/dropsync/domain/workout/WorkoutMath.kt:9`).

Zu klären in der Recherche: ob die Rundung als eigener Wertetyp
(`Weight`-Klasse mit Fabrikmethode) sauberer ist als Rundung in jeder
einzelnen Funktion. Entschieden ist nur **dass** am Eingang gerundet wird.

<a name="e2"></a>
### E2 — Ein Workout = ein Kalendertag mit mindestens einem Satz

Der Wochenziel-Ring zählt Tage, an denen mindestens ein Satz in
`flat_sets` liegt. Keine neue Session-Tabelle, keine Start/Stop-Knöpfe im
TrainScreen.

Tagesgrenze: **lokale Mitternacht**. Ein Satz um 0:30 zählt zum neuen Tag.

Folge, die im Dashboard-Text sichtbar werden muss: zwei getrennte
Trainings am selben Tag zählen als eines. Der Ring beschriftet daher
Trainings**tage**, nicht Trainings.

<a name="e3"></a>
### E3 — `training-core` liegt in einem eigenen GitHub-Repo

Entscheidung 7 und 8 des Design-Dokuments (nur lokal, CI pausiert) sind
**aufgehoben**.

Grund: `git submodule add` mit lokalem Windows-Pfad schreibt einen
absoluten Pfad in `.gitmodules`. Fusion wäre danach auf keinem anderen
Rechner und aus keinem Backup mehr vollständig herstellbar — bei ~50k LOC
und einer Datenbank mit der echten Musikbibliothek ist das der teuerste
Punkt des ganzen Plans. Ein eigenes Repo hält die GitHub-CI
(`.github/workflows/ci.yml`) durchgehend grün.

Damit entfällt auch der Non-Goal „keine aktive GitHub-CI" aus dem
Design-Dokument.

**Nachtrag 2026-08-25 — das Repo ist öffentlich, nicht privat.** Der
erste CI-Lauf mit eingebundenem Submodule schlug fehl:
`fatal: repository 'https://github.com/Adilinu94/training-core.git/' not
found`. `GITHUB_TOKEN` gilt nur für das Repo, das den Workflow auslöst;
ein privates Submodule braucht zusätzlich einen Deploy-Key oder PAT als
Secret. Der ursprüngliche Grund für „privat" war, dass es nichts kostet —
nicht Geheimhaltung. Das eigentliche Ziel dieser Entscheidung ist
Reproduzierbarkeit, und die ist bei einem öffentlichen Repo genauso
erfüllt. Der Kern enthält reine Trainingsmathematik: keine Schlüssel,
keine Nutzerdaten, keine Geschäftslogik, die Schaden anrichtet. Damit
bleibt die CI ohne Secret grün, und Flowtimer kann dasselbe Submodule
ohne eigene Zugangsverwaltung einbinden.

<a name="e4"></a>
### E4 — Ziel erreicht heißt: Gewicht UND Wiederholungen erreicht

Ein Ziel ist erfüllt, wenn **ein einzelner Satz** beides schafft:
`satzGewicht >= zielGewicht` **und** `satzReps >= zielReps`.

Kein Volumenvergleich. Beispiel: Ziel 100 kg × 5. Ein Satz mit
60 kg × 12 erfüllt es **nicht**, obwohl das Volumen höher ist.

Maßgeblich ist der **beste** Satz der Übung (höchstes Gewicht, bei
Gleichstand mehr Reps), nicht der letzte — sonst fällt der Status nach
einem leichteren Abschlusssatz grundlos zurück.

<a name="e4b"></a>
### E4b — Tages-Streak mit zwei freien Ruhetagen

Der Streak zählt **Trainingstage in Folge** und bricht, wenn **drei oder
mehr** aufeinanderfolgende Tage ohne einen Satz vergehen. Zwei Ruhetage
sind frei.

Begründung: Bei 3 Trainings pro Woche liegt zwischen zwei Einheiten immer
mindestens ein Ruhetag. Ein Streak über strikt aufeinanderfolgende Tage
stünde dauerhaft auf 1 oder 2 und würde korrekt eingehaltene Ruhetage als
Abbruch werten. Die Toleranz von zwei Tagen deckt sich mit dem
empfohlenen 48–72-Stunden-Fenster für Krafttraining.

Umsetzung: `streakCount` in Flowtimers `Streak.kt` dekrementiert heute
fest um einen Tag pro Iteration. Es braucht einen Parameter
`maxGapDays: Int = 0` — Default 0 hält Flowtimers Verhalten unverändert,
Fusion übergibt 2. Neue Tests für Lücke 1, 2 und 3 sind Pflicht; die 4
bestehenden `StreakTest`-Fälle bleiben grün.

<a name="e4c"></a>
### E4c — Zahlenformate

Gewichte werden ganzzahlig eingegeben (55 kg, nie 55,5 kg). Die Anzeige
folgt dem:

- Ganzzahliges Gewicht: **ohne** Dezimalstelle (`95 kg`). `95,0 kg` ist
  verboten.
- Krummer Wert (Import, Tippfehler): eine Dezimalstelle (`92,5 kg`). Wird
  nie stillschweigend gerundet.
- Volumen unter 1000 kg: kg, ganzzahlig (`840 kg`).
- Volumen ab 1000 kg: Tonnen, eine Dezimalstelle (`12,4 t`). Fester
  Schwellwert, nicht dynamisch.
- Reps: immer ganzzahlig.

Formatierung über `Locale.getDefault()`, nicht `Locale.ROOT`. Der heutige
`HistoryScreen.kt:199` nutzt `Locale.ROOT` und zeigt deutschen Nutzern
`12.4` statt `12,4` — wird beim Umzug korrigiert.

<a name="e4d"></a>
### E4d — Bento-Layout, Ausblenden, erweiterte Palette

**Layout:** Bento-Grid, zwei Spalten, Tiles bewusst unterschiedlich groß
(`LazyVerticalStaggeredGrid`, breite Tiles via
`StaggeredGridItemSpan.FullLine`). Bei `fontScale > 1.5` einspaltig — das
ist der Punkt, an dem ein Bento-Layout bricht, und der Grund für ein
Lazy-Grid statt fester `Row`-Anordnung.

**Ausblenden statt leer zeigen:** Ein Tile ohne Aussage existiert nicht.
Streak erst ab 2 Tagen, Chart erst ab 2 Wochen mit Sätzen, Volumen-Tile
nur bei Sätzen in dieser Woche, PR-Zeile nur bei PR in den letzten 7
Tagen. Nur der Aussage-Tile (Ring) bleibt immer sichtbar, solange
überhaupt Sätze existieren. Nachrücken über `animateItem()`.

**Segmented Control gestrichen** — Entscheidung 17 des Design-Dokuments
ist aufgehoben. Der Satz-Verlauf ist der letzte Tile mit
`Alle Sätze anzeigen` → eigene Route innerhalb `:feature:progress`. Damit
kein Modus-Zustand, keine `SavedStateHandle`-Verdrahtung, und
Android-Back funktioniert normal.

**Palette — je Farbe eine Rolle:**

| Farbe | Rolle im ColorScheme | Aufgabe |
|---|---|---|
| `DFFF2F` | `primary` (unverändert) | Die eine Aktion / aktiver Fortschritt |
| `756FFA` | `secondary` (war `BrandWhite`) | Ziele — Punkte, Häkchen, Erst-Aufblitzen |
| `141414` | `background`, `surface` (war `101010`) | Grund unter den Tiles |
| `E7E6FB` | `secondaryContainer` (neu) | Genau ein heller Tile pro Screen |

`onSecondary` = `141414`, `onSecondaryContainer` = `141414`.
`surfaceContainer`/`High` (`1D1D1D`/`252525`), `outline`, `onSurfaceVariant`
bleiben unverändert. `AccentColor` (`LIME | BLUE`) wird **nicht**
erweitert — Violett ist eine feste semantische Rolle, keine wählbare
Akzentfarbe.

**Drei Kontrastregeln, gemessen mit der Formel aus
`ThemeColorSnapshotTest`:**

1. Auf Violett steht **dunkler** Text `141414` (4,75), nicht weißer (3,73).
2. Lime auf `E7E6FB` ist **verboten** (1,08). Auf dem hellen Tile wird
   Betonung über Schriftgewicht gelöst, nicht über Farbe.
3. Violett und Lime stehen nie direkt nebeneinander (3,42) — zwischen
   ihnen liegt Grund oder ein dunkler Tile.

Der Test bekommt drei neue Fälle, wichtigster: Lime wird nie mit
`secondaryContainer` gepaart.

Details, Tile-Reihenfolge und Sichtbarkeitstabelle in
[`2026-08-22-flowtimer-integration-UI.md`](2026-08-22-flowtimer-integration-UI.md).

<a name="e5"></a>
### E5 — Der ungenutzte Session-Pfad bleibt unangetastet liegen

Fusion hat zwei Workout-Datenmodelle. Genutzt wird ausschließlich
`flat_sets` — TrainScreen und HistoryScreen sprechen nur diesen an. Die
Kette Session → Cluster → Segment samt `PrCalculator` ist vollständig
implementiert und getestet, wird aber von keinem Bildschirm aufgerufen
(`startSession`/`completeCluster` erscheinen nur in Repository und Tests).

Entschieden: **nichts löschen, nichts anschließen.** Nur die *Mathematik*
wandert in den Kern (siehe [E6](#e6)). Der Datenpfad bleibt, wie er ist.

Folge für das Dashboard: alle Aggregate lesen `flat_sets`. Der lebende
PR-Wert ist heute `FlatSetDao.getMaxVolumeForExercise`
(`core/database/src/main/kotlin/com/dropsync/core/database/dao/FlatSetDao.kt:37`,
`MAX(weight_milli_kg * reps)`) — also Volumen, nicht Gewicht. Ob das
Dashboard diesen Wert weiterverwendet oder auf die Kern-Bestwerte
umstellt, entscheidet die Planung.

<a name="e6"></a>
### E6 — Fusions Mathematik ist die Grundlage des Kerns

Richtung umgedreht gegenüber Entscheidung 9 des Design-Dokuments:
`WorkoutMath` und `PrCalculator` aus `domain/workout` **wandern in den
Kern**. Flowtimers `bestFor` wird als Sonderfall darauf abgebildet.

Grund: Fusions Version kann strikt mehr.

| | Fusion | Flowtimer |
|---|---|---|
| PR-Arten | 3 (`HIGHEST_LOAD`, `HIGHEST_SESSION_VOLUME`, `MOST_REPS_AT_LOAD`) | 1 (max Gewicht) |
| Hantel-Faktor | `loadMultiplier` 1 oder 2 | keiner |
| Gleichstand | frühestes Segment gewinnt | mehr Reps gewinnen |
| e1RM | Epley, nur Reps 1–10, sonst `null`, mit Formelversion | Epley, alle Reps |

Der Kern darf keine zwei Wahrheiten enthalten. Flowtimers Verhalten muss
dabei bitgleich bleiben — die 40 Tests sind der Nachweis.

Abnahmekriterium neu formuliert: **alle Flowtimer-Tests bleiben grün,
keiner wird gelöscht oder abgeschwächt.** Keine Zahl, die veraltet.

<a name="e7"></a>
### E7 — ADR klärt die Geltungsordnung

`FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` bezeichnet sich selbst als
„oberste Referenz für Produktentscheidungen". Das neue Design ändert
genau solche (Verlauf-Tab, Übungsverwaltung), ohne das aufzulösen.

Zu schreiben: ein ADR nach dem Muster von
[`0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md`](../adr/0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md)
und ADR-0010. Inhalt: welche Punkte des Fusionsdesigns durch die
Flowtimer-Integration ersetzt werden, und dass diese CONTEXT-Datei die
Umsetzungsentscheidungen trägt.

Zusätzlich fehlen im Design-Dokument: Eintrag in die Kopftabelle
„Verbundene Dokumente" des Fusionsdesigns und eine Zeile in
`docs/STATUS_FORTSCHRITT.md`.

<a name="e8"></a>
### E8 — `:feature:progress` wird angelegt, `HistoryScreen` zieht um

Bleibt wie im Design-Dokument. Zwei Fallen dabei:

1. `HistoryScreen` hat heute **keine** `stringResource`-Aufrufe — alle
   Texte stehen fest auf Deutsch im Code („Verlauf", „Letzte Sätze",
   „Noch kein Training geloggt"). Beim Umzug müssen sie nach
   `values/strings.xml` und `values-de/strings.xml`, sonst bricht die
   Zweisprachigkeit still.
2. `HistoryViewModel` und `HistoryUiState` liegen in derselben Datei und
   werden von `app/src/test/kotlin/com/dropsync/app/HistoryUiStateTest.kt`
   getestet — der Test zieht mit um.

Die Architekturregeln, gegen die das laufen muss, stehen in
`core/testing/src/test/kotlin/com/dropsync/core/testing/ModuleDependencyRulesTest.kt`:
Features importieren sich nicht gegenseitig, kein `:core:database`,
kein `:data:*`, kein Room, kein Media3 im Feature-Modul. Die
Pflichtmodul-Liste dort (Zeile 128) ist bereits unvollständig — sie
kennt `health`, `sensor`, `audio` nicht. `progress` gehört ergänzt,
zusammen mit den fehlenden.

<a name="e9"></a>
### E9 — Flowtimer v2 zuerst

Bleibt Vorbedingung: Phase 15 (Builder) und Phase 16 (Polish) in
Flowtimer fertig, **danach** Kern-Extraktion. Der Kern wird aus einem
stabilen Stand gezogen, nicht aus einer Baustelle.

<a name="offen"></a>
## Offen für Recherche und Planung

Nicht entschieden, weil es Umsetzungsdetails sind:

1. **Gradle-Einbindung des Kerns.** Ein Submodule liefert nur Dateien,
   keinen Build. Nötig ist `include(":training-core")` mit gesetztem
   `projectDir` oder `includeBuild` (Composite). Achtung bei Composite:
   `ModuleDependencyRulesTest` sucht die Repo-Wurzel daran, dass dort ein
   `settings.gradle.kts` liegt (Zeile 23) — ein zweites im Kern kann das
   kippen.
2. **Toolchain-Spreizung.** Fusion baut mit AGP 9.3.1 / Kotlin 2.4.10,
   Flowtimer mit 9.3.0 / 2.4.0 (beide Gradle 9.5.0). Der Kern braucht
   fixierte `jvmToolchain(17)` und eine `apiVersion`, die beide Seiten
   akzeptieren.
3. **`TargetEntity`-Schlüssel.** Ein Ziel pro Übung heißt: `exerciseId`
   als Primary Key oder Unique-Index. Sonst entstehen Duplikate.
4. **Schema-Export.** Fusion exportiert nach `src/test/assets`, nicht
   `schemas/` (`core/database/build.gradle.kts:42`). Die `9.json` muss
   dort landen und committet werden, sonst schlägt `MigrationTest` fehl.
5. **Un-Archivieren fehlt.** Es gibt kein
   `UPDATE exercises SET is_archived = 0`. „Wiederherstellbar" aus
   Entscheidung 16 braucht diese Query plus UI.
6. **Wochenstart existiert doppelt.** `HistoryScreen.kt:258` hat
   `startOfWeek()` mit `Calendar.MONDAY`; Flowtimers `weekStartLocal`
   löst dasselbe über `(DAY_OF_WEEK + 5) % 7`. Eine gewinnt, die andere
   wird gelöscht. Beide nutzen `Calendar.getInstance()` mit
   Default-Zeitzone — für einen deterministisch testbaren Kern besser
   `java.time` mit injizierter `Clock` und `ZoneId`.
7. **Branch statt direkt auf `master`.** Das Design-Dokument will jeden
   Schritt direkt auf `master`, bei einer Änderung die die DB-Version
   hebt und Mathematik verschiebt. Die CI-Trigger deckt `fusion/**`
   bereits ab (`.github/workflows/ci.yml`) — ein Branch ist billiger
   rückrollbar.
8. **Abbruchkriterium.** Woran wird erkannt, dass die Kern-Extraktion
   teurer wird als geplant, und was ist der Rückweg?

### Entschieden 2026-08-24 (vor Schritt 3, Kern-Extraktion)

Die Punkte 1, 2 und 8 sind nach Code-Recherche entschieden; 5 und 7 sind
umgesetzt (Schritt 7 bzw. Branch-Arbeit), 3 und 4 klärt die DB-v9-Planung,
6 erledigt sich mit dem java.time-Umzug im Kern.

**Punkt 1 — Gradle-Einbindung: `include(":training-core")` mit `projectDir`,
kein Composite-Build.** Der Architekturtest sucht die Repo-Wurzel per
Aufwärts-Traversierung ab `user.dir` und findet Fusions `settings.gradle.kts`
immer zuerst — ein eigenes Settings-File im Submodule-Verzeichnis (unterhalb
der App-Wurzel) kann das nicht kippen. `modulesUnder` prüft nur `core/`,
`domain/`, `data/`, `feature/`: Der Kern an der Repo-Wurzel wird von den
Regeln nicht erfasst (und verstieße als pure JVM auch nicht). Jede App
kompiliert den Kern-Quelltext als gewöhnliches Subprojekt mit der eigenen
Toolchain — deshalb die fixierte apiVersion (Punkt 2). Das eigene
`settings.gradle.kts` im Kern-Repo bleibt für dessen Standalone-CI erhalten.

**Punkt 2 — Toolchain: `jvmToolchain(17)` (Java 17, `JvmTarget.JVM_17`) und
`apiVersion = 2.0`.** Beide Apps kompilieren den Kern selbst (Fusion Kotlin
2.4.10, Flowtimer 2.4.0); apiVersion 2.0 hält den Kern-Bitcode für beide
konsumierbar, auch wenn eine Seite später einmal zurückfällt. Das Modul-Muster
liegt mit `domain/workout/build.gradle.kts` vor.

**Punkt 8 — Abbruchkriterium und Rückweg.** Abbruch, wenn (a) Flowtimers
Tests nicht ohne Abschwächung grün gehalten werden können (verletzt das
Abnahmekriterium aus design.md), (b) die Einbindung mehr verlangt als je eine
`include`-Zeile plus Submodule-Registrierung pro App, oder (c) Kern-Extraktion
plus Flowtimer-Umstellung eine Arbeitssession deutlich überschreitet. Rückweg:
Schritt 3 ist rein additiv — Fusions Mathematik liegt bis Schritt 5 weiter in
`domain/workout`; Submodule-Pointer zurücksetzen bzw. `include`-Zeile
entfernen stellt beide Apps auf den Stand vor Schritt 3 zurück (master 1f1f3d0
bleibt wiederherstellbar). Keine DB- oder Datenänderung in diesem Schritt.

<a name="code"></a>
## Code-Bestand, der wiederverwendet wird

| Zweck | Ort |
|---|---|
| Lebender Satz-Log | `core/database/src/main/kotlin/com/dropsync/core/database/entity/FlatSetEntity.kt`, `dao/FlatSetDao.kt` |
| Fusions Mathematik (→ Kern) | `domain/workout/src/main/kotlin/com/dropsync/domain/workout/WorkoutMath.kt`, `PrCalculator.kt` |
| Flowtimers Mathematik (→ Kern) | `Flowtimer/app/src/main/java/com/flowtimer/app/domain/` (`Pr.kt`, `Streak.kt`, `WeekAgg.kt`, `TargetMath.kt`) |
| Archivierung, schon fertig | `data/workout/.../WorkoutRepositoryImpl.kt:448`, `dao/WorkoutDaos.kt:86` |
| Umzugskandidat | `app/src/main/kotlin/com/dropsync/app/HistoryScreen.kt` + `app/src/test/.../HistoryUiStateTest.kt` |
| Übungsverwaltung | `feature/workout/src/main/kotlin/com/dropsync/feature/workout/ExerciseLibraryScreen.kt`, `ExerciseLibraryViewModel.kt` (17 `stringResource`, Muster für E8) |
| DataStore-Muster für `weeklyGoal` | `data/settings/.../ThemeSettingsStore.kt`, `data/timer/.../RestTimerPreferencesStore.kt` |
| Fehlervertrag | `core/common` — `AppResult`/`AppError` |
| Architekturregeln | `core/testing/src/test/kotlin/com/dropsync/core/testing/ModuleDependencyRulesTest.kt` |
| Migrationen | `core/database/src/main/kotlin/com/dropsync/core/database/Migrations.kt`, `MigrationTest.kt` |
| Farbrollen + Snapshot-Test | `core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/theme/Theme.kt` (Zeile 71 ff. `DarkColors`), `src/test/.../ThemeColorSnapshotTest.kt` (Kontrastformel bereits vorhanden) |
| Ring, fertig verwendbar | `core/designsystem/.../component/ProgressRing.kt` (Feder-Animation, Center-Slot) |
| Chart, fertig verwendbar | `core/designsystem/.../chart/Charts.kt` — `BarChart` (Canvas, animiert, `contentDescription`); nur die Grundlinien-Markierung fehlt |
| Zahl-Animation | `core/designsystem/.../component/CountUpText.kt` |
| Tile-Container | `core/designsystem/.../component/FlowRepComponents.kt` — `FlowRepSurface`, `FlowRepMetricCard`, `FlowRepSectionHeader` |
| Form-Tokens | `core/designsystem/.../theme/Spacing.kt` — `radiusCard` 20dp, `radiusHero` 28dp, `space12`/`space16`/`space24` |

<a name="refs"></a>
## Pflichtlektüre für nachfolgende Sessions

| Datei | Rolle |
|---|---|
| [`2026-08-22-flowtimer-integration-design.md`](2026-08-22-flowtimer-integration-design.md) | Was gebaut wird — mit den [Korrekturen](#korrekturen) oben lesen |
| [`2026-08-22-flowtimer-integration-UI.md`](2026-08-22-flowtimer-integration-UI.md) | Screen-Vertrag des Progress-Dashboards, Zahlenformate, Streak-Anzeige |
| [`../adr/0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md`](../adr/0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md) | Geltungsordnung und Dokumenten-Rangfolge |
| [`FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md`](FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md) | Bisherige oberste Produktreferenz, siehe [E7](#e7) |
| [`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`](FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md) | Designsystem für das Dashboard |
| [`../STATUS_FORTSCHRITT.md`](../STATUS_FORTSCHRITT.md) | Koordination bei parallelen Sessions — zuerst lesen, Zeile setzen |
| [`../adr/0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md`](../adr/0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md) | Muster für das ADR aus [E7](#e7) |
| `C:\Users\adini\Desktop\Teanning\Flowtimer\CLAUDE.md` | Flowtimers eigene Regeln, vor der Kern-Extraktion lesen |

Prüfbefehle vor jedem Commit: `./gradlew test spotlessCheck detekt lintDebug assembleDebug`
und `python3 tools/doku_links_check.py` (verbietet in `docs/design/` Verweise
der Form „Abschnitt <Zahl>" und prüft alle Anker).
