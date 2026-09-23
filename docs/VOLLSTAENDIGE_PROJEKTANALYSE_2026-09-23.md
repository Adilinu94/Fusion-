# Vollständige Projektanalyse FlowRep/DropSync

**Stand:** 2026-09-23
**Umfang:** Alle Module, CI/Build, Audio/Playback, Training/Sensor, Architektur/Daten, UI/UX, Barrierefreiheit, Testabdeckung
**Methode:** Sechs parallele Codebereichsanalysen (Nur-Lese), jeder Befund gegen den konkreten Code mit Datei und Zeile verifiziert, Abgleich gegen die Alt-Analyse `docs/VERBESSERUNGSANALYSE_2026-09.md` (13.09.2026) und `docs/Kritische Befunde.md` (12.08.2026), dazu `git status`/`git diff` gegen HEAD.
**Es wurde nichts am Code geändert.** Dieses Dokument sammelt Befunde; Umsetzung braucht eigene Commits je Paket.

---

## Legende

| Kürzel | Bedeutung |
|---|---|
| 🆕 NEU | In dieser Analyse neu entdeckt, nicht in der Alt-Analyse |
| ⚠️ OFFEN | Alt-Befund, weiterhin im Code vorhanden |
| ✅ BEHOBEN | Alt-Befund, gegen Code geprüft und nicht mehr zutreffend |
| ⚠️🆕 | Alt-Befund, dessen Kern behoben ist, aber mit verbleibender Lücke |

Prioritäten: **KRITISCH** (Projekt-/Release-Gefahr) · **HOCH** (Nutzer- oder Datenwirkung) · **MITTEL** (Qualität/Wartbarkeit) · **NIEDRIG** (Feinschliff)

---

# Inhaltsverzeichnis

1. [Executive Summary](#1-executive-summary)
2. [Sofort kritisch: Arbeitsstand und Commits](#2-sofort-kritisch-arbeitsstand-und-commits)
3. [CI, Build und Lieferkette](#3-ci-build-und-lieferkette)
4. [Audio, Playback, DSP, Timer](#4-audio-playback-dsp-timer)
5. [Training, Sensor, Fortschritt](#5-training-sensor-fortschritt)
6. [Architektur, Daten, Persistenz](#6-architektur-daten-persistenz)
7. [UI/UX und Barrierefreiheit](#7-uiux-und-barrierefreiheit)
8. [Testabdeckung und QA](#8-testabdeckung-und-qa)
9. [Bestätigt behobene Befunde](#9-bestaetigt-behobene-befunde)
10. [Priorisierte Umsetzungs-Roadmap](#10-priorisierte-umsetzungs-roadmap)
11. [Kennzahlen zur Erfolgsmessung](#11-kennzahlen-zur-erfolgsmessung)

---

# 1. Executive Summary <a name="1-executive-summary"></a>

## 1.1 Gesamteinschätzung

Das Projekt ist inhaltlich deutlich weiter, als es die älteren Doku-Befunde vermuten lassen:

- **1.326 `@Test`-Methoden in 201 Testdateien** über 30 Module; das Sensor-Modul ist mit 179 Tests das stärkste des Projekts.
- Die meisten P0-Befunde aus August sind behoben (BLE-Lifecycle, Kalibrierungs-Signalvertrag, Dezimalkomma, DataStore-Korruptionsschutz, Package-Gating der Custom Commands, FTS-Suche, Queue-Verwaltung, Migrationsreihe 1→15 vollständig getestet).
- Modul- und Schichten-Disziplin wird durch einen echten Architekturtest erzwungen (`ModuleDependencyRulesTest`).

Die drei **realen Rest-Risiken** sind nicht algorithmischer, sondern prozessualer Natur:

1. **Der komplette Arbeitsstand ist ungesichert.** 228 geänderte Dateien, +14.284/−13.163 Zeilen, Dutzende neue Dateien, die der Build braucht — nichts davon ist eingecheckt.
2. **Die CI trägt grün, ohne alles zu prüfen.** Baseline-Gate auf Warnung abgeschwächt, gesamter Instrumentierungs-Job mit `continue-on-error`, die gesamte CI-Verschärfung selbst uncommitted.
3. **Halb verwirklichte Nutzer-Versprechen.** Crossfade ausgegraut ohne Wirkung, Bit-Perfect meldet Erfolg ohne Mixer-Umstellung, stille Fehlerpfade lassen den Nutzer im Unklaren.

## 1.2 Befundübersicht nach Priorität

| Priorität | Anzahl | Kernthemen |
|---|---:|---|
| KRITISCH | 4 | Ungesicherter Arbeitsstand, CI-Gates wirkungslos, fehlende CI-Dateien nach Commit |
| HOCH | 19 | DropSync-Arming still fehlerhaft, stille Fehlerpfade, Paging fehlt, Onboarding-Lücken, PlaybackService ungetestet, Ground-Truth-Traces fehlen |
| MITTEL | 24 | Design-Inkonsistenzen, feste Höhen, Refetch-Last, Health Write-back, Alpha-Toolchain, dünne Module |
| NIEDRIG | 12 | Orphaned Komponenten, Haptik-Reste, Veraltetes in Doku |

## 1.3 Die zehn wichtigsten Punkte in einem Satz

1. Den Arbeitsstand sofort in thematische Commits sichern (siehe Abschnitt 2).
2. `app/lint.xml` und `gradle/verification-metadata.xml` mit einchecken, sonst bricht der CI nach dem ersten Commit.
3. `sessionCommands()` um `ARM_LANDING`/`CANCEL_LANDING` ergänzen — sonst kann Drop-Sync-Arming still failen.
4. `handlePlaySongAt` muss Fehler durchreichen statt `RESULT_SUCCESS` zu melden.
5. `continue-on-error` am Instrumentierungs-Job und das Baseline-Gate mit Frist versehen — sonst täuscht die CI Erfolge vor.
6. Stille `AppResult`-Fehlerpfade schließen (`createPlaylist`, `renamePlaylist`, `toggleFavorite`, `undoLastSet`, `loadLastSet`).
7. Race in `ActiveSetController.abort()` durch Join statt losem `clear()` hart machen.
8. Onboarding: Zurück-Navigation, Berechtigungstext, erneut aufrufbar aus den Einstellungen.
9. Paging für große Bibliotheken und Loading-States für fast alle Screens.
10. Ground-Truth-Traces auf echter Hardware aufnehmen, dann erst Gate 11b und Release-Gates aktivieren.

---

# 2. Sofort kritisch: Arbeitsstand und Commits <a name="2-sofort-kritisch-arbeitsstand-und-commits"></a>

## 2.1 🆕 KRITISCH — Der gesamte Ausbaustand ist uncommitted

**Beleg:** `git status --short` (23.09.2026): 228 geänderte Dateien, `git diff --stat HEAD`: **+14.284 / −13.163 Zeilen**, dazu ~120 untracked Dateien/Verzeichnisse.

Das ist nicht „ein paar Feinschliff offen" — es ist der gesamte Ausbau der letzten Wochen: DropSync-Koordinator, Marker-Sheet, Sensor-Sample-Fanout, neue Domänentypen, zig Tests, der Dokumentations-Umzug nach `docs/` und die komplette CI-Verschärfung.

### 2.1.1 Untracked-Dateien, die der Build oder die CI referenziert

Diese Dateien liegen auf Disk, sind aber **nicht im Repository**. Ein frischer Clone — oder jeder Verlust der lokalen Maschine — macht den Build bzw. die CI defekt:

| Datei | Referenz | Folge, wenn nicht eingecheckt |
|---|---|---|
| `app/lint.xml` | `app/build.gradle.kts:99` (`lintConfig = file("lint.xml")`) | `lintDebug` bricht im CI-Checkout ab |
| `gradle/verification-metadata.xml` (527 KB) | Dependency-Pinning | Pinning faktisch wirkungslos, jede Abhängigkeit unpinned |
| `gradle/verification-keyring.gpg` | ergänzt die Verifikation | gleiche Folge |
| `app/src/test/` (`NavigationRoutesTest`, `OnboardingViewModelTest`) | `app/build.gradle.kts` Test-Sourceset | Navigation-/Onboarding-Tests fehlen |
| `docs/plans/*` (18 Dateien) | `README.md`, Doku-Link-Check | `tools/doku_links_check.py` meldet tote Links |
| `docs/adr/README.md`, `docs/adr/0022–0025` | Doku-Link-Check | gleiche Folge |
| `docs/handoffs/` | Doku-Link-Check | gleiche Folge |
| `docs/VERBESSERUNGSANALYSE_2026-09.md`, `docs/UEBERARBEITUNGSBERICHT_2026-09-22.md` | verlinkt aus README | tote Links |
| ~60 Quelldateien, u. a. `feature/player/.../DropSyncCoordinator.kt`, `DropSyncPlanner.kt`, `MarkerSheet.kt`, `data/sensor/.../SensorSampleFanout.kt`, `core/designsystem/.../ReducedMotion.kt`, `core/common/.../datastore/ResilientDataStore.kt` | von geänderten/gehaltenen Klassen importiert | **der Build bricht**, weil Klassen fehlen |
| `core/database/src/test/assets/.../12.json`–`15.json` | `MigrationTest` (Migrationsreihe bis v15) | Migrationstests schlagen fehl |
| `.github/dependabot.yml` | Updates laufen nicht | keiner Fehler, aber Feature fehlt |
| `feature/player/src/test/screenshots/`, `feature/workout/src/test/screenshots/` | Roborazzi-Verify-Gate | Screenshot-Gate schlägt fehl |

### 2.1.2 Gelöschte, aber nur lokal entfernte Dateien

`git status` zeigt 16 `D`-Einträge (Pläne im Root, `docs/handoff_*.md`, `SignalProcessor.kt`, `TemplateExtractor.kt`, `QuickEqSheet.kt`, `RestMusicCoordinator.kt`) — auch diese Löschung ist **nur im Working Tree**, nicht im Commit. Der Dokumentations-Umzug von Root nach `docs/plans/` ist also nur zur Hälfte vollzogen: die Kopien liegen als `??` bereit, die Originale stehen noch im HEAD.

### 2.1.3 Empfohlene Commit-Reihenfolge

Damit kein Zustand entsteht, in dem der Build zwischen den Commits bricht:

1. **Commit A — Doku-Umzug:** Root-Pläne löschen, `docs/plans/`, `docs/adr/README.md`, `docs/handoffs/`, `docs/VERBESSERUNGSANALYSE_2026-09.md`, `docs/UEBERARBEITUNGSBERICHT_2026-09-22.md` einchecken, `tools/doku_links_check.py` + README-Links anpassen.
2. **Commit B — Build/CI-Basis:** `app/lint.xml`, `gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `.github/dependabot.yml`, `app/src/test/` einchecken — **vor** den CI-Änderungen, die darauf referenzieren.
3. **Commit C — CI:** `.github/workflows/ci.yml` (+90 Zeilen), `app/build.gradle.kts` (signingConfig, lint-Block), `build.gradle.kts` (kover), `benchmarks/build.gradle.kts`, `config/detekt/*`.
4. **Commits D…n — je Fachpaket:** DropSync, Marker/Waveform, Sensor-Fanout/Train, Designsystem/ReducedMotion, Datenbank-Migrationen 12–15, jeweils mit den passenden Tests im selben Commit.

Erst nach Commit B prüfen, dass `./gradlew lintDebug` und `./gradlew test` lokal grün sind; dann Commits C…n.

---

# 3. CI, Build und Lieferkette <a name="3-ci-build-und-lieferkette"></a>

## 3.1 KRITISCH — Die gesamte CI-Verschärfung existiert nur lokal

**Beleg:** `git diff HEAD -- .github/workflows/ci.yml` = ±90 Zeilen **uncommitted**.

Im HEAD liegt die alte, dünne CI. Verbessert wurden lokal (jetzt im Working Tree):

- Screenshot-Gate (`:core:designsystem:verifyRoborazziDebug` vor `test`, `ci.yml:77-83`)
- `koverVerify` Coverage-Floors (`ci.yml:91-92`)
- Design- und Doku-Link-Check (`ci.yml:94-101`)
- `lintDebug` und `detekt` als Gates (`ci.yml:103-119`)
- `:benchmarks:assembleBenchmarkRelease` (`ci.yml:128-129`)
- R8-Release-Build (`ci.yml:153-158`)

**Wirkung:** Der aktuell lokal beobachtete grüne Status ist auf einem frischen Clone nicht reproduzierbar. Wer jetzt pusht, pusht die alte CI.

## 3.2 KRITISCH — Baseline-Profiling-Gate ist nur noch eine Warnung

- `app/src/release/generated/baselineProfiles/baseline-prof.txt` existiert weder auf Disk noch in `git ls-files` (nur ein leeres Verzeichnis).
- `ci.yml:143-151`: bei Fehlen wird `::warning::` ausgegeben und **`exit 0`** beendet. Im HEAD stand noch `::error::` (blockierend).
- Der Kommentar in `ci.yml:141-142` gesteht ein, dass „zurück auf `exit 1`" noch zu tun ist.
- `app/build.gradle.kts:161` verbindet Producer und Consumer (`baselineProfile(project(":benchmarks"))`) — **ohne Profil-Datei ist die Baseline-Profile-Verdrahtung faktisch wirkungslos.**

**Optionen:** (a) Profil auf einem Gerät erzeugen (`:benchmarks`, Runbook `docs/HARDWARE_TESTPLAN.md` Abschnitt B1) und einchecken; (b) Gate mit `TODO`-Issue und Datum weiterhin nicht blockierend lassen, aber dokumentieren, bis wann es blockieren soll.

## 3.3 KRITISCH — `app/lint.xml` und `verification-metadata.xml` untracked

Siehe Abschnitt 2.1.1. Beide Dateien werden von Dateien referenziert, die bereits modifiziert sind. **Ohne `git add` dieser beiden Dateien ist jeder spätere Commit inkonsistent** — `lintDebug` bricht im CI-Checkout ab, das Dependency-Pinning bleibt dekorativ.

## 3.4 HOCH — `continue-on-error: true` am gesamten Instrumentierungs-Job

**Beleg:** `.github/workflows/ci.yml:180` (Job `instrumented`, `connectedCheck`), Begründung in `ci.yml:170-175` („vorläufig nicht blockierend").

Alle instrumentierten Tests — inklusive `BleScanConnectInstrumentedTest`, `AudioFocusPermanentLossInstrumentedTest`, `AudioTimestampExtrapolationInstrumentedTest` — können dauerhaft rot sein, ohne dass der Master-Branch davon erfährt. Zusammen mit der extrem geringen Anzahl solcher Tests (4 Dateien, 5 Tests, siehe Abschnitt 8) ist das der **einzige echte blinde Fleck** der CI.

## 3.5 HOCH — Version hartkodiert

**Beleg:** `app/build.gradle.kts:30-31`: `versionCode = 1`, `versionName = "0.1.0"`.

Weder aus `gradle/libs.versions.toml` noch aus Umgebungsvariablen/CI ableitbar. Das widerspricht der eigenen Regel „Versionen ausschließlich im Katalog" (README, Abschnitt Richtlinien) und erschwert jeden Release-Pfad (Play verlangt steigende `versionCode`).

## 3.6 HOCH — Coverage-Floors teils bei null

**Beleg:** `build.gradle.kts:94-112`: `"domain:settings" to 0`, `"data:playback" to 24`, `"data:health" to 42`.

Bei `floor == 0` wird laut `build.gradle.kts:135-141` **keine** `verify`-Rule erzeugt. Für `:domain:settings` erzwingt `koverVerify` damit faktisch nichts — das Gate sieht aus, als würde es gelten, tut es aber nicht.

## 3.7 MITTEL — Ein einziger CI-Job bündelt alles

**Beleg:** `.github/workflows/ci.yml` (einzige Workflow-Datei), Jobs parallel nur zu `build` + `instrumented`; im `build`-Job laufen Screenshot-Verify, test, koverVerify, Doku-Check, Design-Check, spotless, lint, detekt, benchmarks-Assemble, assembleDebug, assembleRelease **sequenziell** in einem Job mit 45-Minuten-Timeout (`ci.yml:20`).

Ein Riss in *jedem* Gate (inklusive Formatierung) blockiert alle Folge-Gates. Keine Progressive-Enhancement-Struktur, keine parallele Sichtbarkeit der Ergebnisse.

## 3.8 MITTEL — Keine Convention Plugins (build-logic fehlt)

Weder `build-logic/` noch `buildSrc/` existieren. **28 Modul-`build.gradle.kts`** duplizieren identisch:

- `compileSdk`/`minSdk`-Getter, z. B. `app/build.gradle.kts:15-28`, `data/playback/build.gradle.kts:12-22`, `feature/workout/build.gradle.kts:24-35`, `core/designsystem/build.gradle.kts:17-27`, `benchmarks/build.gradle.kts:16-33`
- `compileOptions` Java 17 in jedem Modul (`app:86-89`, `data/playback:25-28`, `feature/workout:37-40`, `domain/audio:10-19` usw.)
- `testInstrumentationRunner` in ≥ 5 Modulen identisch

Bereits sichtbare Drift aus der Alt-Analyse: `isIncludeAndroidResources` fehlte in `feature/library` und `feature/player` (inzwischen dort ergänzt — aber genau das ist das Muster: 28 Stellen, an denen ein Fix 28 Mal nötig ist). Ein SDK-/AGP-Upgrade berührt 20+ Dateien.

## 3.9 MITTEL — Alpha-Versionen in der Toolchain

**Beleg:** `gradle/libs.versions.toml`

| Abhängigkeit | Version | Zeile | Risiko |
|---|---|---|---|
| `detekt` | `2.0.0-alpha.6` | 19 | Alpha in der CI-Kette; begründet (nur diese Reihe kann Kotlin 2.4.10/AGP 9.3.1, Kommentar Zeilen 16-18), trotzdem Bruchrisiko bei jedem Alpha-Patch |
| `benchmark` | `1.5.0-alpha01` | 46 | steuert `androidx-baselineprofile` (Zeile 154) **und** `:benchmarks` (Zeile 114) |

Keine RC-Versionen, sonst stabile Stände (AGP 9.3.1, Kotlin 2.4.10, Compose-BOM 2026.08.00). Das Verifikationsdatum im Header („27.07.2026", Zeile 4) ist ~2 Monate alt und sollte vor dem nächsten Upgrade neu gezogen werden.

## 3.10 MITTEL — signingConfig nur bedingt vorhanden

**Beleg:** `app/build.gradle.kts:61-82`: `keystore.properties` (lokal, via `.gitignore` Zeile 38 ignoriert) **oder** Env-Variablen; sonst bleibt release unsigned (ausdrücklich beabsichtigt, Kommentar Zeilen 57-60).

Für den CI-R8-Regressionstest unkritisch, aber es gibt **keinen Pfad zu einem Play-fähigen Artefakt** in der Pipeline. Das sollte zumindest dokumentiert sein (`docs/HARDWARE_TESTPLAN.md` oder Release-Checkliste).

## 3.11 NIEDRIG — Weitere Build-Befunde

| Befund | Beleg |
|---|---|
| Detekt-Baseline: **20** Einträge (Limit `MAX_ENTRIES = 23` in `tools/detekt_baseline_count.py`); CI-Kommentar nennt noch „23" — Ist ok, Kommentar leicht veraltet; Datei lokal modifiziert (`M`) | `config/detekt/baseline.xml`, `ci.yml:115-119` |
| `verify-signatures = false` in der Verifikationsmetadaten — nur sha256, keine Signaturprüfung | `gradle/verification-metadata.xml:5` |
| Doku-/Design-Gates laufen mit System-Python ohne versionierte Abhängigkeit — Spurbarkeit begrenzt | `ci.yml:94-101` |
| Submodule-Wächter in `settings.gradle.kts:45-48` bricht ohne `training-core` mit verständlicher Meldung (bewusst so, CI holt das Submodul) | `settings.gradle.kts` |
| `:benchmarks`-Modul: `checkTestedAppObfuscation` deaktiviert (dokumentiert) — ein deaktivierter Check | `benchmarks/build.gradle.kts:74-76` |
| Emulator-Job existiert („Ausbauplan C4"), aber ohne blockierende Wirkung (siehe 3.4) | `ci.yml:180` |

---

# 4. Audio, Playback, DSP, Timer <a name="4-audio-playback-dsp-timer"></a>

Geprüfte Module: `data/playback`, `data/audio`, `domain/audio`, `data/timer`, `domain/timer`, `feature/player`, `feature/audio`. Media3-Version: 1.11.0.

## 4.1 HOCH — 🆕 ARM/CANCEL_LANDING fehlen in `sessionCommands()`

**Beleg:** `SessionConnectionPolicy.kt:54-55` bewirbt nur `PLAY_SONG_AT` und `SET_SCRUBBING_MODE`; dispatcht werden aber auch ARM und CANCEL (`PlaybackService.kt:456, 475`), und das Repo sendet sie (`PlaybackRepositoryImpl.kt:223, 244`).

**Wirkung:** Je nach Media3-Enforcement sind die Kommandos für den eigenen Controller nicht verfügbar. Das Drop-Sync-Arming („Landung vorbereiten") kann dann **still fehlschlagen**, ohne dass ein Fehler ankommt. Das ist eine Kernfunktion der DropSync-Identität der App.

**Fix:** Zwei Zeilen in `sessionCommands()` ergänzen.

## 4.2 HOCH — 🆕 `handlePlaySongAt` meldet Erfolg bei Fehlschlag

**Beleg:** `PlaybackService.kt:327, 330` → stilles `?: return` bei Song-nicht-geloggt oder `player == null`; das `scope.future` in `:442-445` liefert trotzdem `RESULT_SUCCESS`.

**Kontrast:** `handleArmLanding` meldet korrekt `false` → `ERROR_UNKNOWN`.

**Wirkung:** Der Rufer (`DropSyncPlanner`/Koordinator) kann keinen Fehler unterscheiden und könnte eine fehlgeschlagene Landung als erfolgreich annehmen.

**Fix:** Failure-Pfad an `scope.future` durchreichen, analog zu `handleArmLanding`.

## 4.3 OFFEN — Crossfade/Preset-Fade ist weiterhin toter Code

**Beleg:** `MixPreset.kt:42-76`, `CrossfadeCurves.kt:19-22` — `fadeInGain`/`fadeOutGain` haben **keinen einzigen Produktionsaufrufer** (nur Selbsttest + `CrossfadeCurvesTest`).

Seit der ADR-Konsolidierung (Schritt 18) gibt es keinen Dual-Player mehr; Übergänge laufen als harter Wechsel. Die UI ist seit B-AUD-5 (Weg b, Nutzerentscheidung 2026-09-11) korrekt ausgegraut mit sichtbarem Hinweis „derzeit ohne Wirkung".

**Bewertung:** Die Ehrlichkeit im UI ist gelöst. Offen bleibt die strategische Entscheidung: **Feature fertigbauen (echter Crossfade über DSP oder Zweitspieler) oder die toten Kurven und Regler entfernen.** Halbzustand kostet Wartung und Nutzervertrauen.

## 4.4 MITTEL — 🆕 ReplayGain Out-of-order-Race

**Beleg:** `PlaybackService.kt:201-209` — pro Titelwechsel ein neuer `serviceScope.launch` ohne Serialisierung; ein Vorab-Clear gibt es nur bei `mediaId == null` (`:198`).

**Wirkung:** Bei schnellem Titelwechsel kann der ältere (langsamere) `observeAnalysis`-Lauf zuletzt schreiben und den Gain des **Vortitels** stehen lassen.

**Fix:** Beim Titelwechsel sofortigen Clear setzen und die Läufe serialisieren (z. B. ein einzelner `Job`, der beim nächsten Start abgebrochen wird).

## 4.5 MITTEL — 🆕 Bit-Perfect scheitert still

**Beleg:** `PlaybackService.kt:233` ignoriert die `Boolean`-Rückgabe von `applyPreferredMixerAttributes()`; `BitPerfectGateway.kt:116-126` schluckt `SecurityException` in `runCatching`. Der Service-Neustart läuft trotzdem.

**Kontext:** Der Alt-Befund „`setPreferredMixerAttributes` wird nie gerufen" ist **beobachtbar behoben** (jetzt `BitPerfectGateway.kt:105-127`, Aufruf `PlaybackService.kt:233`, `floatOutput` `:130`). Was fehlt, ist die **Fehlerbehandlung**: der Nutzer erfährt nicht, wenn die Mixer-Umstellung abgelehnt wird. Der UI-Text ist seit B-AUD-4 ehrlich — er sollte aber an den *tatsächlichen* Erfolg gekoppelt sein, nicht an die Absicht.

## 4.6 ⚠️🆕 — Listener-Re-Attach nur lazy und lückenhaft

**Behoben:** Identitäts-Check bei Reconnect — `PlaybackRepositoryImpl.kt:334-340` (`listenerPlayer === player`).
**Offene Lücken:**

- kein proaktiver Re-Attach beim Reconnect ohne Kommando,
- `armLanding`/`cancelLanding`/`setScrubbingMode` bauen neuen Controller **ohne** `attachListener` (`PlaybackRepositoryImpl.kt:209, 240, 293`; Attach nur unter `:160, 313`).

**Wirkung:** Nach diesen Aktionen kann der UI-Zustand bis zum nächsten `command()`/`snapshotNow()` eingefroren sein.

## 4.7 OFFEN — `EnergyAccumulator` als geboxte Liste

**Beleg:** `TrackAnalysisMath.kt:115` — `mutableListOf<Double>` statt `DoubleArray`/`ArrayList<Double>` mit Primitivboxung.

**Wirkung:** Bis ~600 KB Boxen je 10-Minuten-Track bei Profil `FULL`. Kein Test vorhanden, der das Allocation-Verhalten absichert.

## 4.8 OFFEN — Resampler-Hot-Loop mit Bounds-Checks

**Beleg:** `StreamingResampler.kt:126-133` — `getOrElse`-Funktionen und Tap-Loop-Check pro Koeffizient im Audiothread.

**Kontext:** Der Alt-Befund-B-„Puffer-Allokation" ist behoben; die pro-Tap-Checks sind es nicht. Kein Test vorhanden. Vor Refactoring: erst Heatmap/„Heatmap #7/#8 vor Performance-Refactoring" (Empfehlung der Teilanalyse).

## 4.9 OFFEN — Kein Test für `AudioPipeline`

**Beleg:** Glob `**/test/**/*AudioPipeline*` → leer.

Betroffen: Ducking-Mutex und Rampen — der B-AUD-3-Fix ist nur „durch Konstruktion" belegt, nicht durch Test.

## 4.10 OFFEN — Deprecated-API in der Renderer-Factory

**Beleg:** `DspRenderersFactory.kt:44` — `setEnableAudioTrackPlaybackParams`; Media3-Doku empfiehlt `setEnableAudioOutputPlaybackParameters`.

## 4.11 OFFEN — Drei parallele `evaluate()`-Ticker

**Beleg:** `TimerService.kt:166` (200 ms, Main-Thread `:64`), `TimerViewModel.kt:86` (250 ms), `TrainViewModel.kt:1025` (250 ms).

**Einordnung:** entkärmt — alle auf Main-Thread, die Engine ist idempotent (`deliveredCueIds` schützt vor Doppel-Cues), Übergänge sind gegen doppelten Abschluss geprüft. Es bleibt doppelte Arbeit im Vordergrund; entweder konsolidieren oder als bewusst dokumentiert abhaken.

## 4.12 NIEDRIG — Weitere Audio-Befunde

| Status | Befund | Beleg |
|---|---|---|
| 🆕 | Service-Neustart per `startService()` — bei Toggle aus Hintergrund (API 26+) potenziell `IllegalStateException`; Settings-UI ist i. d. R. Vordergrund → geringes Risiko | `PlaybackService.kt:241-242` |
| ⚠️ | True-Peak ohne 4×-Oversampling — bewusstes TODO bis Hardware-Messung | `TrackAnalysisMath.kt:149` |
| 🆕 | `planChain` lädt Songs per-ID in Schleife (kleines N+1), aber durch `MAX_PLANNED_SONGS = 2` auf max. 3 Queries begrenzt und dokumentiert → akzeptabel | `DropSyncPlanner.kt:153-159`, `DropChain.kt:96` |
| ⚠️ | Scopes ohne `close()` in Produktion: `DropRestSessionMonitor.kt:46`, `PlaybackRepositoryImpl.kt:45`; `DropSyncCoordinator.close()` (`:214-215`) wird nie aufgerufen. Alles `@Singleton` (Prozess-Lebensdauer) → unkritisch, aber inkonsistent | siehe links |
| ⚠️ | ReplayGain/Loudness nur analysiert, True-Peak ohne Oversampling | `DuckingMixer.kt:52-57` |
| ⚠️ | DSD/DoP nur Downconvert (ADR-0009, Folgeausbau) | ADR-0009 |

---

# 5. Training, Sensor, Fortschritt <a name="5-training-sensor-fortschritt"></a>

Geprüfte Module: `feature/workout`, `domain/sensor`, `data/sensor`, `training-core`, `domain/workout`, `data/workout`, `feature/progress`.

## 5.1 Behobene Alt-Befunde (Kurzbestätigung, Details in Abschnitt 9)

Dezimalkomma, Gewichtsschritt in Milli-kg, DropRest-Bus-Produzent, Waveform-Überflutung, zwei Sample-Collector → alles behoben (Belege in Abschnitt 9).

## 5.2 HOCH — Drei DB-Roundtrips je geloggtem Satz

**Beleg:** `TrainViewModel.kt:514-516` — nach erfolgreichem Log serial `loadLastSet` + `loadMaxVolume` + `loadRecentSets`; bei Undo dasselbe dreifach unter `:600-602`.

**Wirkung:** Dreiroundige Datenbankfahrt pro Satz, im Schlechtenfall auf Main-nahen Pfaden. Ein kombinierter Refetch (ein Methodenaufruf, eine Query oder ein UNION) würde genügen.

## 5.3 HOCH — „Alle Sätze"-Route ohne Limit

**Beleg:** `AllSetsViewModel` ruft `observeAllSets()` ohne Cap (`AllSetsScreen.kt:72`), Mapping aller Zeilen `:123`.

**Wirkung:** LazyColumn rendert lazy, aber Speicher und Mapping-Aufwand wachsen unbegrenzt mit der Historie. Nach zwei Jahren Training bei vielen Sets pro Session ein echtes Risiko.

**Fix:** Cursor/Paging oder harte Obergrenze mit „mehr laden".

## 5.4 HOCH — Restliche stumme Fehlerpfade

| Pfad | Beleg | Nutzerwirkung |
|---|---|---|
| `undoLastSet` Failure → `Unit` ohne Event | `TrainViewModel.kt:605-607` | Undo schweigt still |
| `loadLastSet`/`loadMaxVolume` schlucken `DatabaseFailure`, setzen still `null` | `TrainViewModel.kt:463, 472` | Nutzer sieht „kein letzter Satz" statt „Datenbankfehler" |
| `startCountedSet`: innere Silent-`return`-Guards | `TrainViewModel.kt:1069 ff.` | UI begründet den Start („kein Profil", `TrainScreen.kt:1877-1885`) — die **inneren** Gründe bleiben stumm (teilweise behoben) |
| `createExercise` etc. | Alt-Befund 3.3 | unbestätigt im Detail, folgt demselben Muster |

**Teilweise behoben:** `logSet` → `SetLogEvent.LogFailed` → Snackbar (`SetLogController.kt:74`, `TrainScreen.kt:1213`) plus `errorEvents`-Channel (`TrainViewModel.kt:629-630`) ✅ — das Muster existiert also, es muss nur auf die restlichen Pfade ausgeweitet werden.

## 5.5 HOCH — Race in `ActiveSetController.abort()`

**Beleg:** `ActiveSetController.kt` — `abort` (aufgerufen aus DISCONNECT-/Lifecycle-Pfaden `:237/:291/:373`) führt `cancelJobs()` **ohne Join** aus und leert danach `bufferedSamples` per plain `ArrayList.clear()` (`:395`), während ein laufender Sample-Collector noch `bufferedSamples.add()` (`:262`) ausführen kann.

**Wirkung:** Konfliktpotenzial für `ConcurrentModificationException` bzw. still verlorene Samples, wenn Fanout-Drain und `abort` auf verschiedenen Dispatchern laufen. `finishAndTakeTrace` hat einen Parallel-Guard (`:410-421`), `abort` nicht.

**Fix:** `abort` denselben Guard geben wie `finishAndTakeTrace`, oder alle Jobs vor dem Leeren joinen.

## 5.6 HOCH — Ground-Truth-Testdaten fehlen weiterhin

**Beleg:** Glob `domain/sensor/src/test/resources/**` → 0 Dateien.

Damit gelten weiterhin:

- Release-Gates **Precision ≥ 98 % / Recall ≥ 97 %** ohne Datengrundlage (siehe `docs/Kritische Befunde.md`, „Weiterhin offen").
- Gate 11b (fünf Hardware-Freigabeszenarien, Anleitung `tools/golden_shadow_corpus/README.md`) offen.
- Feature-Flags `orientationTrackingEnabled` bleiben `false` bis Gate 11b grün (ADR-0017); `accelEnabled` ist inzwischen profilgesteuert (`accelVotingAvailable`, `ActiveSetController.kt:186`), nicht mehr hart `false`.
- **Jede Genauigkeitsaussage nach außen ist synthetisch.**

Die Werkzeuge sind fertig (`tools/shadow_harness.py`, `tools/recofit_bootstrap.py`, `CorpusSweepHarness`, `CorpusRegressionGateTest`); es fehlen Aufnahmen mit echter Hardware.

**Gegenprobe positiv:** Exakte Count-Tests existieren inzwischen (`RepPipelineTest.kt:92` exakt 2, `:102` exakt 0 für Halb-Rep) — das `assertTrue(count >= 1)`-Muster ist verschwunden (0 Treffer im Repo).

## 5.7 HOCH — Session-/Cluster-API ohne UI

**Beleg:** `completeCluster`/`lastCompletedClusterPrefill` existieren in Repo + DAO + Tests; Grep in `feature/*` → **0 Treffer** außerhalb Tests/Fakes.

**Wirkung:** Routinen, Supersätze, „Letzte Session", Uebungstausch sind gebaut und getestet, **der Nutzer sieht nichts davon**. Das ist nutzlose Investition, bis die UI kommt — oder ein Kandidat zum bewussten Zurückstellen mit Doku.

## 5.8 MITTEL — Zwei PR-Quellen mit potenziell abweichendem Ergebnis

**Beleg:**

- Dashboard: `PrRecord` aus `PrCalculator` (`ProgressFeedUiState.kt:46, 63`)
- „Alle Sätze": eigene Definition `maxBy volumeKg take(3)` (`AllSetsScreen.kt:124-130`)

**Wirkung:** Zwei Quellen für „persönlicher Rekord" können sich widersprechen — der Nutzer sieht auf zwei Screens zwei verschiedene Rekorde.

**Fix:** AllSets auf `PrRecord` umstellen (die fachlich richtige `PrCalculator`-Logik ist bereits da; der Alt-Befund „echte PRs ohne UI" ist damit teilweise behoben, aber die doppelte Quelle bleibt).

## 5.9 NIEDRIG — Neu entdeckte Punkte

| Status | Befund | Beleg |
|---|---|---|
| 🆕 | `repEvents`-`tryEmit` wirft bei vollem Puffer (Kapazität 16) still weg — nur Kosmetik, strukturell verwandt mit dem stilen Verlust-Muster aus RC-10 | `ActiveSetController.kt:219` |
| 🆕 | Bus-Drop bei inaktivem Player: `DefaultDropRestRequestBus` nutzt `tryEmit` ohne Replay; dokumentiert als „fachlich unkritisch" | `DefaultDropRestRequestBus.kt:11-12` |
| ⚠️ | Kalibrierungs-Abort-Gründe (Disconnect, Device-Wechsel, Paketverlust) — im Umbauplan gefordert, Stabilität der Live-Pipeline inzwischen gut getestet | `docs/Kritische Befunde.md` Phase 5 |

---

# 6. Architektur, Daten, Persistenz <a name="6-architektur-daten-persistenz"></a>

Geprüfte Module: `core/*`, `data/library`, `domain/library`, `feature/library`, `feature/settings`, `data/settings`, `data/health`, `domain/health`, `app/`.

## 6.1 HOCH — Kein Paging

**Beleg:** Repo-weit **keine** `PagingSource`, kein `LazyPagingItems`, kein `collectAsLazyPagingItems`.

**Wirkung:** Große Bibliotheken (10k+ Titel) liegen vollständig im Speicher (`LibraryViewModel` `stateIn`-Flows). Zusammen mit dem Fehlen von Loading-States (Abschnitt 7) der größte funktionelle Risikopunkt der Bibliothek.

## 6.2 HOCH — Stile `AppResult`-Fehlerpfade in der Library

**Beleg:**

| Pfad | Stelle | Nutzerwirkung |
|---|---|---|
| `createPlaylist` fire-and-forget, `AppResult` ignoriert | `LibraryViewModel.kt:684` | **Namenskonflikt: Dialog schließt ohne jede Meldung** |
| `renamePlaylist` ebenso | `LibraryViewModel.kt:708` | gleiches Verhalten beim Umbenennen |
| `toggleFavorite` ignoriert | `LibraryViewModel.kt:520` | Favoriten-Toggle schlägt still fehl |
| `DropRestViewModel` `is AppResult.Failure -> return@launch` | `DropRestViewModel.kt:134` | Drop-Rest-Fehler unsichtbar |

**Duplikate beim Hinzufügen sind behoben** (`skipped` über `_duplicateSkips` `LibraryViewModel.kt:430-433, 156-157`, Toast `LibraryContent.kt:170`) — nur der Name des Songs wird noch geschluckt.

## 6.3 MITTEL — Room-Schemas liegen im Test-Sourceset

**Beleg:** `core/database/build.gradle.kts:42-44`: `schemaDirectory("$projectDir/src/test/assets")`. Kein `**/src/schemas/**` vorhanden (15 JSON-Assets unter `core/database/src/test/assets/...`).

Bewusst so kommentiert (`build.gradle.kts:38-41`), aber der Standard-/Release-Verifikationspfad (`exportSchema` → `src/schemas`) bleibt abweichend; die Schemaversion hängt am Test-Sourceset statt am Build-Vertrag.

## 6.4 MITTEL — Health nur lesend, Fehler teilweise generisch

| Prüfpunkt | Status | Beleg |
|---|---|---|
| Write-back nach Health Connect | ❌ offen — kein `writeExercise*`, nur `READ_HEART_RATE` | `data/health/AndroidManifest.xml:22` |
| Fehlerdifferenzierung | ⚠️ teilweise — `PermissionDenied` wird unterschieden, unbekannte Fehler landen in `AppError.Unknown(e.message)` | `HealthConnectHeartRateSource.kt:69, 77-80` |
| Resume-Refresh | ✅ behoben — `refreshHeartRate` vorhanden | — |
| UI-Ehrlichkeit „Puls nur während die App offen ist" | aus der Alt-Analyse empfohlen; bewusst kein Background-Read (Grundsatz, README) | README Herzfrequenz-Abschnitt |

## 6.5 MITTEL — Kein build-logic, Versions-/SDK-Duplikation

Siehe Abschnitt 3.8. Zusätzlich: `versionCode`/`versionName` hartkodiert (Abschnitt 3.5) — beides zusammen widerspricht der eigenen Katalog-Regel.

## 6.6 NIEDRIG — Weitere Daten-Befunde

| Status | Befund | Beleg |
|---|---|---|
| 🆕 | `observeArtists()` (`COUNT(DISTINCT album) GROUP BY artist`) ohne expliziten Index — Entities haben reichlich Indizes, diese Aggregate aber ggf. nicht indexgedeckt | `LibraryBrowseDaos.kt:75` |
| ⚠️ | `DpOffset(x = -136.dp)` in der Library (UI-seitig, siehe Abschnitt 7) | `LibraryHomeScreen.kt:128` |
| ✅ | Indizes in `WorkoutEntities`/`ScanEntities`/`LibraryEntities`/`EqPresetEntities` durchgängig inkl. Unique-Constraints; `Migrations.kt:191` leitet Index-Namen korrekt ab | Entity-Dateien |
| ✅ | Transaktionen sauber: `TransactionRunner.kt:17` via `withTransaction`, `@Transaction` in DAOs, `MarkerCandidateTransactionTest` vorhanden | — |
| ✅ | **Keine Geheimnisse im Repo** — kein `.jks`, `.keystore`, `google-services.json`, `.env`; Treffer nach API-Keys nur Testdaten | Glob/Grep |
| ✅ | Manifest: `allowBackup = false`; Berechtigungen modular und minimal; FGS-Typen korrekt (`mediaPlayback`, `specialUse`); `POST_NOTIFICATIONS` zur Laufzeit angefragt | `app/src/main/AndroidManifest.xml:17` |

---

# 7. UI/UX und Barrierefreiheit <a name="7-uiux-und-barrierefreiheit"></a>

Geprüfte Bereiche: `feature/*` (Compose-Screens), `core/designsystem`, `app/` (Navigation, Onboarding).

## 7.1 HOCH — Offene Alt-Befunde

### 7.1.1 Onboarding ohne Zurück-Navigation ⚠️

**Beleg:** `OnboardingScreen.kt:127-136` — nur „Weiter" und „Überspringen", `page++` vorwärts.

**Wirkung:** Fehlklick und TalkBack-Nutzer müssen bis zum Ende oder auf Skip; kein Schritt zurück.

### 7.1.2 Medienberechtigung fehlt im Onboarding-Text ⚠️

**Beleg:** `values/strings.xml:24` / `values-de/strings.xml:22` (`onboarding_music_desc`) nennen Konto und Netzwerk, aber **nicht** `READ_MEDIA_AUDIO`. „Berechtigung" kommt im Onboarding gar nicht vor (nur in `health_rationale_*`).

### 7.1.3 Onboarding nicht erneut aufrufbar ⚠️

**Beleg:** Einziger Aufruf `DropSyncApp.kt:161` (First-Run); Grep „onboarding" in `feature/settings` → 0 Treffer.

### 7.1.4 Loading-States fehlen fast überall 🆕

**Beleg:** `CircularProgressIndicator` nur in `AllSetsScreen.kt:154` und `ProgressDashboardScreen.kt:246`.

Ohne Ladezustand: Library-Listen (`LibraryLists.kt`), `ExerciseLibraryScreen`, Settings-Import/-Export. Wenn Paging kommt (Abschnitt 6.1), wird diese Lücke zur sichtbaren Regression — Skeletons/Indikatoren gehören dann zum selben Paket.

### 7.1.5 Roborazzi-Abdeckung sehr dünn 🆕

Vorhanden: `ComponentScreenshotsTest.kt` (Designsystem), `RestConsoleScreenshotsTest.kt`, `NowPlayingScreenshotsTest.kt` — **3 Tests, 10 Screens**.
**Keine** Screenshots für: Library-Home, Dashboard, Settings, Timer, Onboarding, Train, Audio. Die Kennzahl der Alt-Analyse („fünf größte Screens je Breakpoint") ist nicht erreicht.

## 7.2 MITTEL — Offene Alt-Befunde (Designkonsistenz und Skalierung)

| # | Befund | Beleg |
|---|---|---|
| 1 | **Drei konkurrierende Kartenradien**: 16 (`Spacing.kt:30-31` `radiusMedium`), 20 (`radiusCard`, MiniPlayer/NowPlayingCard), 24 (`Theme.kt:140` `shapes.medium`, `BrandCard.kt:32`); dazu `radiusHero`. Kein Token-Konsens | siehe links |
| 2 | **Feste Sheet-/Dialog-Höhen ohne `fontScale`**: 480 dp (`QueueSheet.kt:69`), 360/400 (`LibraryHomeDialogs.kt:68,133`), 360 (`PlaylistScreens.kt:338`, `SettingsScreen.kt:596`). Einzige fontScale-Adaption: Dashboard-Spaltenzahl (`ProgressDashboardScreen.kt:1248-1251`) | siehe links |
| 3 | **Feste `sp`-Literale umgehen die Typo-Skala**: 15 sp Zeitstempel (`NowPlayingScreen.kt:1167, 1434`), 12 sp Chart-Wert-Pille (`Charts.kt:75`) | siehe links |
| 4 | **`DpOffset(x = -136.dp)` hardcodiert** — bricht bei längeren Labels/anderer Sprache | `LibraryHomeScreen.kt:128` |
| 5 | **TempoSheet deutsche `contentDescription`** („Tempo …") auf EN-Geräten | `TempoSheet.kt:74` |
| 6 | **Reduced Motion: Waveform/Chart ohne Check** trotz vorhandenem `LocalReducedMotion` | `Waveform.kt:224,231,247-249,957`, `Charts.kt:63-67` (700-ms-Tween) |
| 7 | **Orphaned Komponenten**: `BrandCard` (`BrandCard.kt:22`), `BrandButtonSecondary` (`Buttons.kt:84`) — nur im Screenshot-Test, in keiner Route | siehe links |
| 8 | **Kein Haptik-Feedback** beim Queue-Umsortieren/Entfernen (Logging hat Haptik: `SetLogController.kt:69` → `AndroidSetLogHaptics`, Test `SetLogControllerTest.kt:35,48`) | `QueueSheet.kt:137-152` |
| 9 | **Top-Bars noch nicht komplett vereinheitlicht**: `FlowRepTopBar` (`FlowRepComponents.kt:118`, 48-dp-Back mit Role `:133-147`) in 11 Screens verdrahtet; offen: `CalibrationWizardScreen.kt:109-118` (eigene ArrowBack-Reihe), Settings (keine Bar), `CategoryHeader`-Subtyp (`CategoryScaffold.kt:52`) | siehe links |
| 10 | **A-Z-Scroller Treffer nur 32 dp breit** (Höhe 48 ok) — unter dem 48-dp-Richtwert | `LibraryLists.kt:433,444-450` |
| 11 | **`Spacer(Modifier.width)` in einer Column — wirkungslos** | `LibraryLists.kt:607` (in `CoverTile`-Column `:578`; die früheren Belege 297/405 haben sich verschoben; `:266` ist in einer Row → ok) |

## 7.3 Teilweise behobene UI-Befunde (Abrundung nötig)

| Alt-Befund | Status | Detail |
|---|---|---|
| Reduced Motion ignoriert | ⚠️🆕 Kern behoben | `LocalReducedMotion` existiert (`ReducedMotion.kt:20-28`), provider in `DropSyncApp.kt:144,153`, ausgewertet u. a. `DropSyncApp.kt:276,418,488-498`, `OnboardingScreen.kt:66`, `NowPlayingScreen.kt:1003`, `MiniPlayer.kt:304`, `LibraryContent.kt:274`, `ProgressDashboardScreen.kt:271`, `Buttons.kt:38`, `CountUpText.kt:31`, `ProgressRing.kt:51`. **Lücken:** Waveform, Charts (siehe 7.2/6) |
| Fünf Top-Bar-Muster | ⚠️🆕 von 5 auf ~2-3 | siehe 7.2/9 |
| Empty/Error uneinheitlich | ✅ im Wesentlichen | `FlowRepEmptyState`/`FlowRepErrorState` (`FlowRepComponents.kt:165,188`) verdrahtet in `ExerciseLibraryScreen.kt:120`, `LibraryLists.kt:643`, `AllSetsScreen.kt:159,201`, `ProgressDashboardScreen.kt:215`. **Loading fehlt** (7.1.4) |
| Bottom-Nav-Label 11 sp ohne Ellipsis | ✅ behoben | `DropSyncApp.kt:547-548`: `maxLines=1` + `TextOverflow.Ellipsis`; Selected mit `selected`+`stateDescription` (`:507-509`) |
| EQ-Slider ohne TalkBack | ✅ behoben (anders gelöst) | `QuickEqSheet`/`VerticalBandSlider` entfernt; jetzt `LabeledSlider` → M3-`Slider` (`AudioControls.kt:104,125`, `AudioEqSection.kt:67`) bringt `Role.Slider`/`setProgress` nativ mit; eigenes `setProgress` repo-weit nur noch Waveform-Seek (`Waveform.kt:692`) |
| Hardcodierte Deutsche Strings in LibraryHome | ✅ behoben | durchgängig `stringResource` (`LibraryHomeScreen.kt:111,221,238` u. a.) — **Ausnahme TempoSheet** (7.2/5) |
| POST_NOTIFICATIONS nur im Train-Tab | ✅ behoben | `TimerSection.kt:70-82` + Train mit Kontext `TrainScreen.kt:184-222` |
| Onboarding-Indikatoren ohne Semantik | ✅ behoben | „Seite x von y" (`OnboardingScreen.kt:104-111`) |
| Haptik beim Satz-Logging | ✅ behoben | `SetLogController.kt:69` → `AndroidSetHaptics`, DI `TimerDataModule.kt:156` |

## 7.4 Positiv (bewahren)

- Semantik ernst genommen: lokalisierte `stateDescription`, Live-Regions am Rep-Zähler, gemergte Satzzeilen, `progressBarRangeInfo` an der Waveform.
- Kontrastarbeit dokumentiert; `ensureContrast` auf 3:1 geprüft; `ThemeColorSnapshotTest` vorhanden.
- Umsortieren über sichtbare Pfeil-Buttons statt Drag-and-drop (bewusst barrierefrei), Undo statt Bestätigungsdialoge.
- 48-dp-Ziele an den meisten Stellen (Play-Button 74 dp `NowPlayingScreen.kt:117,1147`, MiniPlayer `:151,157`).
- Edge-to-edge mit themengekoppelten Systembar-Icons.
- Queue-Verwaltung mit Undo vollständig verdrahtet (`PlayerViewModel.kt:798,805,819`, `NowPlayingScreen.kt:531-537`, `MiniPlayer.kt:174-187`, Tests `PlaybackRepositoryImplTest.kt:171-179`).

---

# 8. Testabdeckung und QA <a name="8-testabdeckung-und-qa"></a>

## 8.1 Gesamtüberblick

**201 Testdateien, 1.326 `@Test`-Methoden** (197 Unit-Test-Dateien + 4 androidTest-Dateien), 30 Gradle-Module.

| Modul | Dateien | Tests | | Modul | Dateien | Tests |
|---|---:|---:|---|---|---:|---:|
| `:app` | 6* | 13 | | `:domain:audio` | 14 | 93 |
| `:core:common` | 2 | 13 | | `:domain:health` | 1 | 5 |
| `:core:database` | 3 | 23 | | `:domain:library` | 6 | 35 |
| `:core:designsystem` | 5 | 52 | | `:domain:playback` | 3 | 17 |
| `:core:model` | 2 | 12 | | `:domain:sensor` | 27 | **179** |
| `:core:testing` | 2 | 15 | | `:domain:settings` | 1 | 3 |
| `:data:audio` | 8 | 49 | | `:domain:timer` | 8 | 69 |
| `:data:health` | 2 | 10 | | `:domain:workout` | 5 | 37 |
| `:data:library` | 6 | 52 | | `:feature:audio` | 1 echte | 6 |
| `:data:playback` | 11 | 62 | | `:feature:library` | 7 | 34 |
| `:data:sensor` | 8 | 80 | | `:feature:player` | 13 | **127** |
| `:data:settings` | 4 | 11 | | `:feature:progress` | 9 | 55 |
| `:data:timer` | 10 | 41 | | `:feature:settings` | 3 | 12 |
| `:data:workout` | 5 | 51 | | `:feature:timer` | 4 | 16 |
| `:training-core` | 6 | 46 | | `:feature:workout` | 18 | **112** |
| **`:benchmarks`** | **0** | **0** | | | | |

\* `:app` = 2 Unit-Dateien (8 Tests) + 4 androidTest-Dateien (5 Tests; `HiltTestApplication.kt` ohne `@Test`).

## 8.2 KRITISCH — `PlaybackService` komplett ungetestet

**Beleg:** `data/playback/src/main/kotlin/.../PlaybackService.kt` (MediaLibraryService, ~240+ Zeilen) — keine eigene Testdatei; **0 androidTest-Dateien** in `:data:*`.

Die 11 Unit-Testdateien in `:data:playback` testen Repos/Stores; `PlaybackRepositoryImplTest.kt:34` sagt explizit, die Fakes bräuchten den „Service-/Session-Stack" nicht. Ungedeckt bleiben:

- MediaSession-Lifecycle und Custom-Command-Dispatch (der Bereich, in dem die Befunde 4.1/4.2 sitzen!)
- AudioFocus-Verlust (nur 1 Instrumentierungstest: `AudioFocusPermanentLossInstrumentedTest`)
- Notification-Verhalten, Browse-Baum für Android Auto, Package-Gating gegen fremde Controller

## 8.3 KRITISCH — Instrumentierungs-Tests praktisch wirkungslos

- Genau **4 Dateien / 5 `@Test`**, alle in `:app`: `BleScanConnectInstrumentedTest` (2), `AudioTimestampExtrapolationInstrumentedTest` (2), `AudioFocusPermanentLossInstrumentedTest` (1).
- **0** androidTest-Dateien in `feature/*`, `data/*`, `core/*`.
- Der einzige Lauf ist obendrein `continue-on-error: true` (`ci.yml:180`).

**Folge:** Compose-UI-Flows, Navigation, DB-Migrationen auf dem Gerät und der Playback-Service werden nie instrumentiert abgedeckt — und wenn, würde es niemand sehen.

## 8.4 HOCH — QA-Dokumente mit offenen Punkten

| Dokument | Offen |
|---|---|
| `docs/qa/UI_REVIEW.md` | **P1** (Zeile 105-107): Lokalisierungsbruch DE/EN. Eingeschränkt (92-101): reale Medienwiedergabe (P1 Geräteabnahme), BLE-Sensor (2 Tests `SKIPPED`, Zeile 98), Health Connect (P1). **P2** (111-115): überlange Settings, leere Musikzustände, Audio-Controls im Leerlauf ohne Erklärung, englische Back-/A11y-Labels, inkonsistente Dialogtexte. Empfehlungen 1-4 (127-130) nicht als umgesetzt belegt |
| `docs/qa/ACCESSIBILITY_ACCEPTANCE.md` | Zeile 3: physische **TalkBack-Abnahme ausstehend**; Rest-Risiko (98-103): Aussprache, Fokusreihenfolge OEM-TalkBack, 200-%-Layout offen bis Hardware-Lauf |
| `docs/Kritische Befunde.md` | „Weiterhin offen": Ground-Truth-Suite, Gate 11b, Precision-/Recall-Gates ohne Datengrundlage, `orientationTrackingEnabled` bleibt `false` (ADR-0017) |

## 8.5 HOCH — Dünne Testbereiche

| Bereich | Ist | Bewertung |
|---|---|---|
| Navigation | `NavigationRoutesTest.kt` — 5 Tests auf Routen-Verträgen (Doppelte, `calibrationRoute`-Format) | kein Test auf NavHost-Graph, Tab-Wechsel, Backstack-Verhalten |
| `LibraryViewModelTest` | 8 Tests | dünn gegenüber `PlayerViewModelTest` (30) und TrainViewModel (46 über 7 Dateien) |
| `TimerViewModelTest` | 8 Tests | dünn |
| `:benchmarks` | 0 Tests, wird in CI nur assembliert (`ci.yml:128`), nie ausgeführt | — |
| `:domain:settings` | 1 Datei / 3 Tests | Floor 0 in Kover (Abschnitt 3.6) |
| `:domain:health` | 1 / 5 | dünn |
| `:feature:audio` | 1 echte Testdatei / 6 Tests | dünn für die DSP-UI |

## 8.6 MITTEL — Schwache Assertions (Rest)

`assertTrue(count >= 1)` existiert **nicht mehr** (0 Treffer). Verblieben sind schwache Nachbarn:

- `ZuptDetectorTest.kt:36` — `confirmedAt >= 0` (nur Index-Existenz)
- `PeakDetectorTest.kt:46` — `durationMs >= 0`
- `ActiveSetControllerTest.kt:319` / `PeakDetectorTest.kt:45` — reine `isNotEmpty()`-Checks
- `CalibrationControllerWizardTest.kt:97` — `noiseFloor >= 0`

Überwiegen tun konkrete Schwellwerte (`> 0.95`, `in 0.4..0.6`, `> 0.55`) — insgesamt **kein** Blockierer, sondern Nachzieharbeit.

## 8.7 Migrationen — solide

`MigrationTest.kt` — 17 Tests, dazu `DropSyncDatabaseTest` (4) + `MarkerCandidateTransactionTest` (2, untracked). DB `version = 15` (`DropSyncDatabase.kt:96`), 14 Migrationen `MIGRATION_1_2`…`MIGRATION_14_15` (`Migrations.kt:16-314`), alle registriert (`:330-345`), 15 Schema-Assets `1.json`–`15.json` (**12-15 noch untracked!**, siehe 2.1.1).

---

# 9. Bestätigt behobene Befunde <a name="9-bestaetigt-behobene-befunde"></a>

Zur Vermeidung von Doppelarbeit in Folgesessions — diese Alt-Befunde sind gegen den Code geprüft und **nicht mehr zutreffend**:

## 9.1 Aus `docs/Kritische Befunde.md` (August-Review)

| Befund | Nachweis |
|---|---|
| Kalibrierung und Live-Erkennung verschiedene Signale | `SensorModels.kt` `RepSignalKind` + Profil; `CalibrationController` gibt nur `ChosenSignal.GP` frei |
| Gespeicherte Schwellen ≠ kalibrierte Schwelle | `detectionThreshold` direkt persistiert; `ExerciseEnginePipeline` verwendet ihn unverändert |
| Halbe Reps zählen als vollständig | `PhaseValidator`, `RepCountPlausibility`, `ZuptDetector`; exakter Gegen-Test `RepPipelineTest.kt:102` (Halb-Rep → 0) |
| Disconnect/Übungswechsel bricht Set nicht ab | `ActiveSetController` + `ActiveSetPhase` kapseln den Lifecycle |
| Profil wird nach späterem Verbinden nicht geladen | kombinierte Flows im `TrainViewModel` |
| BLE-Verbindung kann dauerhaft hängen | `BleSensorProvider` `GattOperationType`, `opInFlight`, Timeout je Operation, späte Callbacks verworfen |
| Remote-Disconnect räumt BLE-Zustand nicht auf | `cleanupConnection(DisconnectReason)`, 8 Aufrufstellen inkl. `REMOTE_DISCONNECT` |
| Paketverluste verfälschen zeitbasierte Erkennung | `SampleRateEstimator`, Timestamp-basierte Dauer |
| Lernpfad leert Daten zu früh | `SetTrace` als unveränderliche Kopie |
| Refiner lernt aus weniger Peaks als bestätigt | `ProfileLearningPolicy`, `CalibrationRefiner:133` revalidiert |
| Playback-Service gibt interne Kommandos frei | `PlaybackService:293` `controller.packageName == ownPackageName` + `SessionConnectionPolicy.kt:50-79` |
| Analysefehler dauerhaft gecacht | `TrackAnalysisRepositoryImpl:194` `isPermanentAnalysisFailure()`, `:206` `Result.retry()` |
| Ungültige Trainingsdaten speicherbar | `TrainViewModel:260-261` gegen `MAX_REASONABLE_WEIGHT_KG` / `MAX_REASONABLE_REPS` |
| Timer stellt abgebrochenen Timer wieder her | `TimerRecoveryStarter` + `RebootGuard` |
| `lintDebug` scheitert in sechs Modulen | `lintDebug` ist CI-Gate (`ci.yml:71` bzw. lokal 103-110) |
| Playback-Transition spielt zwei Titel gleichzeitig | **entfallen** — `CrossfadeController`/`TransitionManager` entfernt, harter Wechsel über einen Player |
| Tests erwarten `>= 1` statt exakter Counts | exakte Counts vorhanden (`RepPipelineTest.kt:92,102`); **Ground-Truth-Suite bleibt offen** (siehe 5.6) |

## 9.2 Aus `docs/VERBESSERUNGSANALYSE_2026-09.md` (13.09.)

| Abschnitt | Befund | Status |
|---|---|---|
| 3.1 | ~15 DataStores ohne `ReplaceFileCorruptionHandler` | ✅ `ResilientDataStore.kt:17-25` zentrale Factory, alle Produzenten nutzen sie (`LibraryDataModule.kt:64,81,92,153`, `PlaybackDataModule.kt:53`, `TimerDataModule.kt:114,126,138` + 13 direkte Stores) |
| 3.1 | Architekturtest prüft die Regeln wirklich | ✅ `ModuleDependencyRulesTest.kt` — 4 Regeln auf Gradle-Deklarations- **und** Kotlin-Import-Ebene inkl. transitiver Sichtbarkeit |
| 3.1 | FTS-Suche nicht verdrahtet, Index-Rebuild pro Suche | ✅ UI nutzt FTS (`CategoryScreens.kt:64`), Rebuild nur beim Scan in Transaktion (`LibraryRepositoryImpl.kt:101-107`), Query-Pfad `LibraryBrowseRepositoryImpl.kt:167→175→455` |
| 3.1 | App-Version hartkodiert | ⚠️ weiterhin hartkodiert (Abschnitt 3.5) — **nicht behoben** |
| 3.1 | Room-Schemas in `src/test/assets` | ⚠️ weiterhin (Abschnitt 6.3) — **nicht behoben** |
| 3.2 | Bit-Perfect ruft nie `setPreferredMixerAttributes` | ✅ gerufen (`BitPerfectGateway.kt:105-127`, `PlaybackService.kt:233`); ⚠️ Fehlerbehandlung fehlt (4.5) |
| 3.2 | `floatOutput` hart `false` | ✅ konfigurierbar (`PlaybackService.kt:130`, Default `DspRenderersFactory.kt:27`) |
| 3.2 | Listener geht bei Reconnect verloren | ⚠️ Identitäts-Check behoben, Attach-Lücken offen (4.6) |
| 3.2 | `AudioSessionId` nur einmal gelesen | ✅ Broadcast `PlaybackService.kt:182-187` + Initial `:213` |
| 3.2 | Timer schreibt ~5×/s in DataStore | ✅ Throttle auf Schlüssel `(remainingMs/1000, status)` ≈ 1×/s (`TimerService.kt`, Notification-Key `NOTIFY_TICK_MS` 200 ms `:377`) |
| 3.4 | N+1 im Wiedergabepfad (`RestMusicCoordinator`) | ✅ Klasse eliminiert; Batch-Queries `songsForLabelOnce` (`DropSyncPlanner.kt:317-320`), `getEnabledMarkersForSongs` (`:287-291,164-168`), Test `LibraryBrowseRepositoryTest.kt:351` |
| 3.4 | Queue-Verwaltung fehlt in der Bibliothek | ✅ `moveQueueItem`/`removeQueueItem`/`undoRemoveQueueItem` + UI (siehe 7.4) |
| 3.4 | Playlist-Duplikate erlaubt | ✅ `skipped`-Meldung (`LibraryViewModel.kt:430-433`) — ⚠️ Namenskonflikt bleibt (6.2) |
| 3.4 | Import-Verstöße nur als Zahl | ✅ `SettingsScreen.kt:313-335, 488-503` inkl. `MAX_SHOWN_VIOLATIONS` |
| 3.4 | SAF/M3U ohne UI-Einstieg | ✅ `LibraryContent.kt:421-428, 446` |
| 3.4 | POST_NOTIFICATIONS nur im Train-Tab | ✅ `TimerSection.kt:70-82` |
| 3.4 | Health ohne Resume-Refresh | ✅ `refreshHeartRate` vorhanden |
| 3.3 | Dezimalkomma blockiert das Loggen | ✅ `parseWeightMilliKg` nutzt `BigDecimal` mit `replace(',', '.')` (`TrainViewModel.kt:1214-1216`, `ExerciseLibraryScreen.kt:360`) |
| 3.3 | Gewichtsschritt ±2,5 kg String-Arithmetik | ✅ Milli-kg in `Long` (`TrainViewModel.kt:486-491`, UI `TrainScreen.kt:307-308`) |
| 3.3 | Drop-Auto ohne Bus-Produzent | ✅ `DefaultDropRestRequestBus.kt:14-21`, Konsument `DropSyncCoordinator.kt:199`, Produzent `TrainViewModel.kt:342/414` |
| 3.3 | Waveform ~50 Hz neues `FloatArray` | ✅ Ringpuffer + 33-ms-Throttle (`CalibrationViewModel.kt:139-144, 329-354`) |
| 3.3 | Zwei Sample-Collector, `tryEmit` verwirft still | ✅ `SensorSampleFanout` mit DROP_OLDEST + sichtbarem `droppedSamples`-Zähler (`SensorSampleFanout.kt:32-91`, `BleSensorProvider.kt:112,659`) |
| 4.1/1 | Reduced Motion: nur Dashboard | ⚠️ Kern behoben, Waveform/Chart offen (7.2/6) |
| 4.1/2 | A-Z-Scroller 24 dp unbedienbar | ⚠️ verbessert auf 32×48 dp, Breite noch unter 48 (7.2/10) |
| 4.1/3 | Custom-EQ-Slider ohne TalkBack | ✅ M3-`Slider` (7.3) |
| 4.1/4 | Hardcodierte deutsche Strings | ⚠️ Library behoben, TempoSheet offen (7.2/5) |
| 4.1/6 | Fünf Top-Bar-Muster | ⚠️ auf ~2-3 (7.2/9) |
| 4.2/7 | Empty/Error-States | ✅ `FlowRepEmptyState`/`FlowRepErrorState` — Loading offen (7.1.4) |
| 4.2/8 | Bottom-Nav-Label ohne Ellipsis | ✅ `DropSyncApp.kt:547-548` |
| 4.2/14 | Orphaned Komponenten | ⚠️ halb aufgeräumt: `LineChart`/`FlowRepMetricCard` gelöscht, `DropRestCard`→`NowPlayingScreen.kt:914`, `MiniWaveform`→`LibraryLists.kt:285`; offen: `BrandCard`, `BrandButtonSecondary` (7.2/7) |
| 4.2/15 | Keine Haptik beim Logging | ✅ `SetLogController.kt:69` — Queue-Haptik offen (7.2/8) |
| CI/1 | Baseline-Gate blockiert immer rot | ⚠️ auf Warnung abgeschwächt statt gelöst (3.2) |
| Tests | `:app` ohne `src/test` | ✅ 2 Dateien/8 Unit-Tests (untracked!, 2.1.1) |
| Tests | `:benchmarks` verrottet leise | ⚠️ kompiliert wieder (`junit4`+`androidx.test.ext.junit`, `benchmarks/build.gradle.kts:60-61`) und CI-Assemble-Gate vorhanden, aber 0 Tests (8.5) |

---

# 10. Priorisierte Umsetzungs-Roadmap <a name="10-priorisierte-umsetzungs-roadmap"></a>

Reihenfolge nach Risiko, nicht nach Bequemlichkeit. Jedes Paket ist ein eigener Commit-Strang.

## Paket 0 — Sicherung (halber Tag, vor allem anderen)

1. **Arbeitsstand committen** nach der Reihenfolge aus Abschnitt 2.1.3 (Doku → Build-Dateien → CI → Fachpakete).
2. Sicherstellen, dass `app/lint.xml`, `gradle/verification-metadata.xml`, `gradle/verification-keyring.gpg`, `app/src/test/`, `core/database/src/test/assets/…/12-15.json`, `docs/plans/`, `docs/adr/README.md` eingecheckt sind.
3. Nach jedem Commit: `./gradlew spotlessCheck test lintDebug detekt` lokal grün halten.

## Paket 1 — CI ehrlich machen (2-3 Tage)

4. `sessionCommands()` um `ARM_LANDING`/`CANCEL_LANDING` ergänzen (4.1, 1-Zeiler).
5. `handlePlaySongAt`-Failure-Pfad durchreichen (4.2).
6. `continue-on-error` am Instrumentierungs-Job und Baseline-Gate: entweder Frist + Tracking-Issue hinterlegen oder blockierend stellen, sobald das Profil existiert (3.2, 3.4).
7. Coverage-Floor `domain:settings` von 0 auf einen realen Wert (3.6).
8. Version aus dem Katalog/Env ableiten (3.5).

## Paket 2 — Stille Fehlerpfade und Datenintegrität (Woche)

9. `createPlaylist`/`renamePlaylist`: `AppResult.Failure` → sichtbare Meldung (Namenskonflikt) (6.2).
10. `toggleFavorite`, `DropRestViewModel` analog (6.2).
11. `undoLastSet`, `loadLastSet`, `loadMaxVolume` an `errorEvents`/Snackbar anschließen — bestehendes `SetLogEvent`-Muster ausweiten (5.4).
12. `ActiveSetController.abort()`-Race: Join oder Guard wie `finishAndTakeTrace` (5.5).
13. Drei DB-Refetches je Satz bündeln (5.2).
14. „Alle Sätze" limitieren/cursorn (5.3).
15. ReplayGain-Serialisierung + Mixer-Rückgabe loggen/koppeln (4.4, 4.5).

## Paket 3 — Trainerlebnis und Bibliothek (Woche)

16. Onboarding: Zurück-Button, Berechtigungstext, erneut aufrufbar aus Settings (7.1.1-7.1.3).
17. Loading-States für Library, ExerciseLibrary, Settings-Import (7.1.4) — idealerweise zusammen mit:
18. Paging für große Bibliotheken (6.1).
19. Cluster-/Routinen-UI zurückholen **oder** bewusst als „später" markieren (5.7) — aktuell arbeitet toter Code.
20. AllSets-PRs auf `PrRecord` umstellen (5.8).

## Paket 4 — Designkonsistenz (Woche)

21. Ein Radius-Token-Satz statt 16/20/24 (7.2/1).
22. Sheet-Höhen `fontScale`-fähig; `sp`-Literale auf die Typo-Skala (7.2/2,3).
23. `DpOffset(-136.dp)` entfernen (7.2/4).
24. TempoSheet-CD lokalisieren (7.2/5).
25. Waveform/Charts an `LocalReducedMotion` koppeln (7.2/6).
26. `BrandCard`/`BrandButtonSecondary` verdrahten oder entfernen (7.2/7).
27. Queue-Haptik (7.2/8), `CalibrationWizard`/Settings auf `FlowRepTopBar` (7.2/9), A-Z-Breite 48 dp (7.2/10), toten `Spacer` entfernen (7.2/11).

## Paket 5 — Tests und Geräteabnahme (Woche+, parallel)

28. `PlaybackService`-Tests (mindestens Custom-Command-Dispatch und Package-Gating — genau da sitzen 4.1/4.2) (8.2).
29. Roborazzi für die fünf größten Screens je Breakpoint: Library, Dashboard, Train, Settings, Timer (7.1.5).
30. NavHost-/Backstack-Tests in `:app` (8.5).
31. Instrumentierungs-Job blockierend machen, sobald genug Tests da (3.4).
32. TalkBack-Durchlauf + 200 %-Layout am Gerät, DE/EN-Lokalisierungsbruch aus `UI_REVIEW.md` P1 (8.4).

## Paket 6 — Release-blockierend (Strategie, Wochen)

33. **Ground-Truth-Traces mit echter Hardware aufnehmen** → `domain/sensor/src/test/resources/traces/` (5.6).
34. Gate 11b durchführen, dann Precision ≥ 98 % / Recall ≥ 97 % als echte CI-Gates aktivieren (8.4).
35. Feature-Flag `orientationTrackingEnabled` an Gate 11b koppeln (ADR-0017).
36. **Halbzustände entscheiden:** Crossfade fertigbauen oder entfernen (4.3); Bit-Perfect an tatsächlichen Mixer-Erfolg koppeln (4.5); Health Write-back als Roadmap-Entscheidung (6.4).
37. `build-logic`/Convention Plugins für 28 Modul-Builds (3.8); Detekt-Baseline unter 15; Alpha-Toolchain vor Upgrade neu verifizieren (3.9).

---

# 11. Kennzahlen zur Erfolgsmessung <a name="11-kennzahlen-zur-erfolgsmessung"></a>

| Bereich | Ziel | Aktuell (23.09.2026) |
|---|---|---|
| Repo-Sicherheit | 0 untracked Dateien, die der Build referenziert; Working Tree nach jeder Sitzung eingecheckt | ~120 untracked, 228 modifiziert |
| CI-Aussagekraft | Baseline-Gate mit echter Datei blockierend; Instrumentierungs-Job ohne `continue-on-error`; Coverage-Floors > 0 | Gate = Warnung; Instrumented = `continue-on-error`; ein Floor = 0 |
| Tests | `:app` > 0 ✅; NavHost-/Backstack-Tests; `PlaybackService`-Tests; Roborazzi je 5 größte Screens; Instrumentierung ≥ 10 % der Fälle | `:app` 8 Unit ✅; PlaybackService 0; Roborazzi 3 Tests; Instrumentierung 5 Tests |
| Sensor | Ground-Truth-Suite vorhanden; Precision ≥ 98 %, Recall ≥ 97 % als CI-Gate; Gate 11b grün | Traces fehlen, Gates ohne Datenbasis |
| UI-Konsistenz | 1 Radius-Token, 1 App-Bar-Muster, Loading/Empty/Error überall, 0 feste `sp`-Literale | 3 Radien, ~2-3 Bar-Muster, Loading in 2 Screens |
| A11y | 200 %-Lauf ohne Clipping, TalkBack-Durchlauf für Player/Timer/Charts/Scroller | Geräteabnahme ausstehend |
| Audio | Crossfade hörbar oder entfernt; Bit-Perfect nachweisbar (Mixer-Attribute gesetzt) oder ehrlich als „nur DSP-Bypass" textiert | Crossfade ausgegraut/tot; Mixer-Pfad ruft auf, Fehler aber still |
| Fehler-Sichtbarkeit | 0 still geschluckte `AppResult.Failure` in ViewModels | ≥ 6 bekannte stille Pfade |

---

## Anhang A: Datenbasis dieser Analyse

- Sechs parallele Teilanalysen (CI/Build, Audio/Playback, Training/Sensor, UI/UX, Architektur/Daten, Tests) mit Datei:Zeile-Verifikation gegen den Working Tree.
- Quellen: `README.md`, `docs/VERBESSERUNGSANALYSE_2026-09.md`, `docs/Kritische Befunde.md`, `docs/qa/UI_REVIEW.md`, `docs/qa/ACCESSIBILITY_ACCEPTANCE.md`, `docs/STATUS_FORTSCHRITT.md`, `.github/workflows/ci.yml`, `gradle/libs.versions.toml`, alle Modul-`build.gradle.kts`.
- Git: `git status --short`, `git diff --stat HEAD`, `git log --oneline -5` (HEAD = `6dcb382`).

## Anhang B: Wichtiger Hinweis zu Dokumentenstatus

- `docs/Kritische Befunde.md` ist ein **historisches** Review-Protokoll (12.08.2026) mit Ist-Stand-Nachtrag vom 30.08.2026 — die Tabelle im Kopf hat Vorrang darunter.
- `docs/VERBESSERUNGSANALYSE_2026-09.md` (13.09.) ist überholt, wo dieses Dokument Widriges nennt; die dortige Empfehlung „kein Code geändert" gilt für dieses Dokument **nicht mehr**: zwischen 13.09. und 23.09. wurde an 228 Dateien gearbeitet.
- Dieses Dokument ersetzt keine ADR; strategische Entscheidungen (Crossfade, Health Write-back, build-logic) brauchen eigene ADRs in `docs/adr/`.
