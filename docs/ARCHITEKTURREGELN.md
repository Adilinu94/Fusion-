# Architekturregeln (Bauplan-Rekonstruktion)

**Stand:** 2026-09-11 — Weg b aus Verbesserungsplan [B-DOC-1](plans/VERBESSERUNGSPLAN.md#b-doc-1)
(Nutzerentscheidung "Regeln extrahieren").

**Was das ist:** Der verbindliche technische Bauplan
(`DropSync-Technischer-Bauplan.md`, Stand 27.07.2026) existiert nicht im Repo.
Dieses Dokument ersetzt ihn **nicht** — es sammelt die Regeln, die der Code,
die Build-Skripte und der Architekturtest *tatsaechlich zitieren*, mit der
Original-Nummerierung. Ein Zitat `Regel 3.2/4` oder `Bauplan Schritt 2.4`
meint ab jetzt diese Datei.

**Ehrlichkeitsregeln dieser Datei:**

1. Jede Regel nennt ihre Belegstelle (Datei + Zeilenkontext). Was nur dem
   Namen nach zitiert wird ("Trainingslog (Schritt 9/10)"), steht hier auch
   nur dem Namen nach — dazu erfunden wird nichts.
2. Normativ (Test-geprueft) ist nur Abschnitt 3.2: `ModuleDependencyRulesTest`
   laesst CI bei Verstoss fehlschlagen. Die Schritt-Tabelle ist
   dokumentarisch — sie haelt fest, was ein Schritt im Code *bedeutet*, nicht
   was er im verlorenen Original *forderte*.
3. Faellt ein neues Zitat auf, das hier fehlt, wird es hier ergaenzt statt in
   einen Kommentar ohne Rueckhalt.

## Abschnitt 3.2 — Modulregeln (normativ, Test-geprueft)

Quelle: `core/testing/src/test/kotlin/com/dropsync/core/testing/ModuleDependencyRulesTest.kt`
(Bauplan Schritt 2.6 — der Test prueft Gradle-Deklaration UND Kotlin-Import).

| Regel | Inhalt | Durchsetzung |
|---|---|---|
| **3.2/1** | `:core:model` haengt von keinem anderen App-Modul ab (kein `project(":…")`). | Test `core model haengt von keinem anderen app modul ab` |
| **3.2/2** | `:domain:*` ist reines JVM: kein `android.library`/`android.application`, kein Room, kein Media3/ExoPlayer, kein `:core:database`, kein `:data:*`, kein `:feature:*`. | Test `domain module sind reine jvm module ohne room und player` |
| **3.2/3** | `:data:*` implementiert Domain-Schnittstellen, kennt keine UI: kein `project(":feature:")`, kein `project(":app")`. | Test `data module kennen keine feature module` |
| **3.2/4** | `:feature:*` nutzt nur Domain, UI-State und Designsystem: kein Room, kein Media3/ExoPlayer, kein `:core:database`, kein anderes Feature, kein `:data:*`. | Test `feature module kennen weder room noch media3 noch andere features` |

Zusaetzlich prueft derselbe Test die Pflichtmodul-Liste (`alle bauplan module
existieren`, ADR-0016): `app`, `training-core`, `core/common`, `core/model`
und die Module unter `core/database`, `core/designsystem`, `core/testing`,
`data/*`, `domain/*`, `feature/*`. `:benchmarks` und `:libs:media3-ffmpeg`
bleiben bewusst draussen.

## Bauplan-Schritte (dokumentarisch, aus Zitaten rekonstruiert)

Jede Zeile: Schrittnummer wie zitiert, Bedeutung wie aus der Belegstelle
ablesbar, Belegstelle.

| Schritt | Bedeutung | Beleg |
|---|---|---|
| 1.4 | Kotlin-Formatierung und statische Analyse (Spotless, Detekt). | Root-`build.gradle.kts:17` |
| 1.7 | R8-Minifizierung im Release; keine Secrets, keine API-Keys. | `app/build.gradle.kts:48` |
| 2.2 | Geschlossener Fehlervertrag der App (`AppError`). | `core/common/.../AppError.kt:4` |
| 2.3 | Typisierter Erfolg oder `AppError` (`AppResult`). | `core/common/.../AppResult.kt:4` |
| 2.4 | Hilt-Singletons (u. a. Datenbank, Dispatcher); zentrale Dispatcher- und Zeitvertraege. | `AppModule.kt:31`, `DispatcherProvider.kt:6`, `DatabaseModule.kt:34` |
| 2.5 | Zeitquelle der App (`Clock`); kontrollierbare Zeit fuer Tests (`FakeClock`). | `Clock.kt:4`, `FakeClock.kt:6`, `AppModule.kt:16` |
| 2.6 | Modulabhaengigkeitstest auf zwei Ebenen (Abschnitt 3.2). | `ModuleDependencyRulesTest.kt:8` |
| 3.4 | Abstraktion ueber Room-Transaktionen (`TransactionRunner`). | `TransactionRunner.kt:6` |
| 3.5 | Getestete Schema-Migrationen; Schema-Export in die Test-Assets. | `Migrations.kt:6`, `core/database/build.gradle.kts:39` |
| 3.6 | Versionierte Standarduebungsbibliothek, idempotenter Seed (Konflikte ignorieren, Benutzerdaten unberuehrt). | `ExerciseSeeder.kt:17` |
| 4 | Bibliotheksabgleich gegen den MediaStore. | `LibraryRepositoryImpl.kt:28`, `MediaStoreGateway.kt:12` |
| 4.3 | Scan-Zustand: unveraenderter Stand heisst kein Vollscan. | `ScanStateStore.kt:11` |
| 5 | Wiedergabe-Datenschicht (Media3). | `PlaybackDataModule.kt:32` |
| 5.4 | Song→MediaItem: mediaId ist die MediaStore-ID, Metadaten lokal fuer Systembenachrichtigungen. | `MediaItemFactory.kt:10` |
| 5.5 | Player-Zustand wird bei jeder relevanten Aenderung persistiert. | `PlayerStateStore.kt:15` |
| 6 | Markerimport und -zuordnung. | `MarkerRepositoryImpl.kt:29`, `MarkerRepository.kt:18` |
| 7 | Timerkern als deterministisch testbare Zustandsmaschine. | `TimerEngine.kt:13` |
| 7.1–7.3 | Timer-Grundtypen. | `TimerModels.kt:3` |
| 7.2 | Erlaubte Timer-Zustandsuebergaenge; Endzustaende. | `TimerTransitions.kt:4` |
| 7.6 | Cue-Ausgabe als Schnittstelle (getrennte Kanaele). | `CueOutput.kt:4` |
| 7.8/7.9 | Reboot-Erkennung ohne Boot-ID (ADR-0002). | `RebootGuard.kt:4` |
| 8 | Komponierte Cue-Ausgabe (TTS mit Ducking, Haptik). | `AndroidCueOutput.kt:52` |
| 8.1–8.3 | TTS-Adapter. | `TtsSpeaker.kt:11` |
| 8.5 | Haptikadapter prueft die Geraetefaehigkeit vorab. | `HapticsAdapter.kt:12` |
| 9/10 | Trainingslog (Vertrag + Implementierung + Verdrahtung). | `WorkoutRepository.kt:36`, `WorkoutRepositoryImpl.kt:58` |
| 9.1, 9.2 | Uebungs-Seed-Kontext (mit 3.6 zitiert, keine eigene Bedeutung belegt). | `ExerciseSeeder.kt:17` |
| 11 | "Rest bis zum naechsten Drop" als reine Domainlogik. | `DropRest.kt:3` |
| 11.2–11.4 | Drop-Rest-ViewModel: Gate, Start, Ueberwachung; effektive Dauer ist immer Marker minus Playerposition, nie editierbar (11.3). | `DropRestViewModel.kt:32` |
| 12.7 | App-Texte liegen in Deutsch und Englisch vor (Version 1). | `app/build.gradle.kts:39` |

Nicht belegt und daher nicht aufgefuehrt: alle Schritte, die nirgends
zitiert werden. Das Fehlen eines Schritts hier heisst "nie referenziert",
nicht "existiert nicht".
