# Test-Infrastruktur Umbauplan (Testpyramide + Plattform-Härtung)

**Datum:** 2026-08-10
**Status:** Entwurf / zur Freigabe
**Zweck:** Das fehlende instrumentierte Test-Grundgerüst (offener Punkt aus
`docs/STATUS_FORTSCHRITT.md` B, Abschnitt 11 "Instrumentiert") aufbauen und die
Plattform-Risiken aus Abschnitt 13 (HyperOS MTU, Xiaomi killt Service,
Latenz-Varianz) durch die richtige Testschicht je Problem **messbar und
durchrüstbar** machen statt ad-hoc. Umfasst auch die Doku-Hygiene
(Punkt 4: nummernbasierte Querverweise + CI-Check).

**Referenzen:**
- `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` → Abschnitt 11 (Tests und Gates), 11a, 13 (Risiken)
- `docs/STATUS_FORTSCHRITT.md` → B.2 (instrumentierte Tests fehlen)
- `docs/design/SHADOW_DIFF_HARNESS_PLAN.md` → Muster für Auswertungs-Harness, gleicher Stil
- `gradle/libs.versions.toml` → bereits deklarierte, ungenutzte Test-Deps
- `docs/adr/0001-baselineprofile-modul-in-schritt-13.md` → ADR-Muster für spätere Entscheidungen

---

## 1. Ausgangslage und Ist-Analyse

### 1.1 Was Abschnitt 11 fordert

Abschnitt 11 des Design-Dokuments listet zwei Test-Gates:

| Block | Inhalt |
|---|---|
| **Unit** | Planner, Crossfade, Codec-Roundtrip, Slugs, Repo-Migration, Generation Token, Ducking min(), RouteProfile, Cue Mode, Direct-Drop |
| **Instrumentiert** | JitterBuffer, Dedup, Scan, TimerService Foreground, Waveform Scrubbing, Marker Long Press, RestMusicCoordinator, AudioTimestamp-Extrapolation, Route-Wechsel während Countdown, Underrun-Monitoring, AudioFocus (LOSS_TRANSIENT duckt + Timer weiter, permanenter LOSS stoppt) |

Zusätzlich Abschnitt 11 "Golden Audio Fixtures für CI" (Sinus, Klickfolge, Bass-
Impuls, Stille→Impuls, zwei Sample-Rates, künstlicher Crossfade, absichtlicher
Underrun) mit Metriken: Gain-Kontinuität, Click-Metrik, True Peak/Clipping,
Equal-Power-Check, Drop-Alignment (mean/P50/P95).

### 1.2 Verifizierter Ist-Zustand im Repo

**Einordnung der Module (Basis für die Testschichten):**

| Modul-Typ | Beispiele | Testlauf heute | Robolectric möglich? |
|---|---|---|---|
| Reine JVM-Module | `:domain:*`, `:core:common`, `:core:model`, `:core:testing` | `test` (lokal, CI) | Nein (kein Android-Gradle-Plugin) |
| Android-Module | `:data:*`, `:feature:*`, `:core:database`, `:core:designsystem`, `:app` | `test` (lokal, CI) | **Ja** |

**Vorhandene Unit-Tests (verifiziert, `src/test`):** 58 Testdateien über alle
Module — darunter bereits Abdeckung für einen Großteil der Abschnitt-11-Liste,
z. B.:
- `data/sensor`: `JitterBufferTest`, `BatchDedupTrackerTest`, `BleProtocolParserTest`, `SwitchingSensorProviderTest`
- `feature/player`: `RestMusicCoordinatorTest`, `PlayerViewModelTest`
- `data/playback`: `CrossfadeControllerTest`, `PlayerStateStoreTest`
- `data/timer`: `DuckingControllerTest`
- `data/library`: `LibraryRepositoryImplTest`, `MarkerRepositoryImplTest`, `FolderScanAndCueTest`

**Festgestellte Lücken:**

1. **Kein einziges `src/androidTest/`-Verzeichnis** im gesamten Repo (verifiziert).
2. **`testInstrumentationRunner` ist in `app/build.gradle.kts:30` bereits gesetzt**
   und Espresso + `compose-ui-test-junit4` + `ui-test-manifest` sind als
   `androidTestImplementation`/`debugImplementation` verdrahtet — aber ohne
   Quelldateien trägt das nichts.
3. **`hilt-android-testing` liegt ungenutzt im Catalog** (deklariert, aber in
   keiner Modul-`dependencies`-Block-Benutzung) — für Hilt-instrumentierte Tests
   (`@HiltAndroidTest`, `HiltTestApplication`) fehlt es komplett.
4. **`:core:testing` ist ein JVM-Modul** (`kotlin.jvm`, verifiziert). Es kann
   Fakes/Dies/Koroutinen-Helfer teilen, aber **keine** Android-/Robolectric-
   Helfer (die brauchen das Android-Gradle-Plugin).
5. **Kein Robolectric-Einsatz**, obwohl `robolectric 4.16.1` im Catalog liegt.
6. **Keine `connectedCheck`-Konfiguration, kein `managedDevices`, kein
   Firebase-Test-Lab-Schritt** in `.github/workflows/ci.yml` (nur `test`,
   `spotlessCheck`, `assembleDebug`).
7. **Abschnitt-11-Einordnung ist teils falsch:** JitterBuffer/Dedup/
   RestMusicCoordinator sind bereits als reine JVM-Unit-Tests grün und gehören
   NICHT aufs Gerät. Umgekehrt fehlen echte Geräte-Tests für die Punkte, die
   echtes Android-Framework brauchen (AudioFocus, AudioTimestamp, Underrun, BLE).

### 1.3 Das eigentliche Kernproblem

> Es fehlt nicht "das androidTest-Grundgerüst" als Selbstzweck — es fehlt eine
> **Testpyramide mit klarer Schichtzuordnung**, in der jeder Abschnitt-11-Punkt
> in der günstigsten Schicht liegt, und die dazu passende **CI-Automatisierung**.

Das Design-Dokument sortiert nach "braucht Gerät oder nicht" — die Realität
ist: Die meisten Punkte brauchen **kein Gerät**, nur die richtige
Android-Framework-Simulation (Robolectric) oder Compose-UI-Test. Nur Audio-
und BLE-Kernpfade brauchen echte Hardware.

---

## 2. Zielbild

```
                         ┌─────────────────────────────┐
                         │   CI (.github/workflows)    │
                         │  test · spotlessCheck ·     │
                         │  assembleDebug ·            │
                         │  doku-links-check ·         │
                         │  (optional) connectedCheck  │
                         └────────────┬────────────────┘
                                      │
        ┌─────────────────────────────┼────────────────────────────┐
        │                             │                            │
        ▼                             ▼                            ▼
┌────────────────┐   ┌────────────────────┐   ┌──────────────────────────┐
│  1. JVM-Unit   │   │ 2. Robolectric     │   │ 3. Compose-UI-Test       │
│  (heute, 58    │   │  (Android-Frame-   │   │  (JVM-lokal ODER        │
│  Dateien)      │   │   work in JVM)     │   │   Emulator)              │
│ domain/data    │   │ data:, feature:,   │   │ feature: Screens,        │
│ Logik, Parser, │   │ core:database,     │   │ Waveform-Scrubbing,      │
│ Flows          │   │ Service-Lifecycle, │   │ Marker-Long-Press,       │
│                │   │ Notification,      │   │ Audio-Focus-UI           │
│                │   │ Foreground,        │   │                          │
│                │   │ AudioFocus-Ducking │   │                          │
└────────────────┘   └────────────────────┘   └────────────┬─────────────┘
                                                           │
                        ┌──────────────────────────────────┴──────────────┐
                        │  4. Echte instrumentierte Tests (MINIMAL)        │
                        │  nur: AudioTimestamp-Extrapolation, Underrun,    │
                        │  AudioFocus-Dauer-LOSS, BLE-Scan/Connect         │
                        │  Lauf: lokal/Gerät, CI-Emulator, Firebase Test   │
                        │  Lab (Xiaomi-Matrix)                              │
                        └──────────────────────────────────────────────────┘
```

**Prinzip:** Jeder Test in der günstigsten Schicht, die ihn zuverlässig
beantwortet. Geräte-Tests sind die teuerste Ressource und werden auf ein
Minimum begrenzt (echte Audio-/BLE-Hardware-Pfade).

---

## 3. Design-Entscheidungen

| # | Entscheidung | Begründung |
|---|---|---|
| T1 | **Robolectric als zweite Schicht** für alle Android-Module (data, feature, core:database, core:designsystem) | Dep liegt im Catalog; läuft auf JVM in Sekunden; deckt Service-Lifecycle, Notification, Foreground, AudioFocus-Ducking ohne Emulator ab. |
| T2 | **Compose-UI-Test (`createComposeRule`)** für Screens/Interaktionen (Waveform-Scrubbing, Marker-Long-Press) | `ui-test-junit4` + `ui-test-manifest` sind bereits verdrahtet; läuft lokal auf JVM via Robolectric ODER auf Emulator. |
| T3 | **Echte instrumentierte Tests nur für eine kleine, klar definierte Menge**: AudioTimestamp-Extrapolation, Underrun, AudioFocus-Dauer-LOSS, BLE-Scan/Connect | Nur diese brauchen echtes Android-Framework/echte Audio-Hardware; alles andere wäre teuer ohne Mehrwert. |
| T4 | **Hilt-Test-Setup** (`HiltTestApplication` + `@HiltAndroidTest` + `hilt-android-testing`) erst im `app`-Modul für die instrumentierten Kern-Tests | Hilt-Dep ist deklariert; Standard-Pattern von Hilt-Doku. |
| T5 | **CI bekommt einen Doku-Link/Anchor-Check** (Punkt 4): nummernunabhängige Anker + Validierung, dass Querverweise existieren | Verhindert die heute gefundene Regression (SHADOW-PLAN Zeile 279 verwies auf falschen Abschnitt); macht Doku-Widersprüche CI-sichtbar. |
| T6 | **Plattform-Härtung (Punkt 5) wird über Tests erzwungen**, nicht als Kommentar: Robolectric-Test für TimerService-Kill-Fallback + Rehydrierung; Timeout/Retry für MTU-Verhandlung; Latenz als gemessene Größe | Macht die ad-hoc-Lösungen reproduzierbar und schützt sie vor Regression. |
| T7 | `:core:testing` bleibt **JVM-only**; Android-spezifische Test-Helfer (Robolectric-Runner, Rule-Klassen) liegen in den jeweiligen Android-Modulen bzw. einem neuen kleinen Fixture-Modul nur falls nötig | Verhindert, dass ein JVM-Modul gegen Android compiliert; hält die Architektur sauber. |

---

## 4. Schritt-für-Schritt-Umsetzung

### Schritt 0 — Vorarbeit: Inventar & Baseline

**Ziel:** Vor jedem Umbau den Zustand einfrieren.

- [ ] CI-Status `git status` sauber, `./gradlew test spotlessCheck assembleDebug` grün (Baseline).
- [ ] `docs/STATUS_FORTSCHRITT.md` Zeile B.2 auf `[~] in Arbeit (Session: …)` setzen.
- [ ] Inventar-Abschnitt in diesem Plan (Abschnitt 5) mit den konkreten,
  real existierenden Testdateien gegenchecken.

**Verifikation:** Baseline-Build grün; Zeile in STATUS gesetzt.

---

### Schritt 1 — Robolectric-Grundgerüst für Android-Module

**Ziel:** Die 2. Schicht steht; Service-/Notification-/AudioFocus-Logik wird in
der JVM testbar.

**Konkret pro Android-Modul (Pilot: `:data:timer`):**

1. `data/timer/build.gradle.kts`: `testImplementation(libs.robolectric)` +
   `testImplementation(libs.androidx.test.ext.junit)` + (falls noch nicht da)
   `testImplementation(project(":core:testing"))`.
2. Test-Basisklasse im Test-Source-Set (z. B. `data/timer/src/test/kotlin/.../
   RobolectricTestCase.kt`):
   ```kotlin
   @RunWith(RobolectricTestRunner::class)
   @Config(sdk = [34])
   abstract class RobolectricTestCase
   ```
   `sdk=34` als Default-Konstante; pro Test per `@Config` überschreibbar.
3. **Erster Test — `TimerServiceForegroundTest`** (deckt Abschnitt-11-Punkt
   "TimerService Foreground" + Xiaomi-Fallback aus Abschnitt 13):
   - `Robolectric.buildService(TimerService::class.java).create().startCommand(...)`
   - Assertion: Service ist `START_STICKY`, Notification wird gezeigt,
     `evaluate()` treibt den Timer (mit `Robolectric`-Scheduler/`ShadowLooper`).
   - Fallback-Pfad: `POST_NOTIFICATIONS` entzogen → kein Crash, `stopSelf()`
     greift, Engine läuft weiter (Xiaomi-Szenario).
4. **Zweiter Test — `AudioFocusDuckingTest`** (Abschnitt-11-Punkt "AudioFocus"):
   LOSS_TRANSIENT → Ducking aktiv, Timer läuft weiter; permanenter LOSS →
   Stop. (Voraussetzung: ein `AudioFocusRequest`-Wert wird testbar injiziert —
   siehe Schritt 2 Refactoring-Hinweis.)

**Rollout auf weitere Android-Module** (gleiche Struktur, je nach Bedarf):
`:data:audio` (DSP-Fehlerpfade), `:data:sensor` (JitterBuffer/Dedup bleiben
JVM, aber der `BleGattClient`-Callback-Pfad kann hier Robolectric-frei bleiben),
`:feature:player`, `:core:designsystem` (Snapshot-Tests sind bereits JVM).

**Verifikation (je Modul):** `./gradlew :data:timer:test` grün; neue Tests
laufen < 10 s.

---

### Schritt 2 — Refactoring für Testbarkeit (nur wo nötig)

**Ziel:** Die Härtungslogik (Punkt 5) wird injizierbar, damit Robolectric sie
testen kann — ohne Verhalten zu ändern.

- [ ] **TimerService-Kill-Fallback (5b):** `TimerEngine`-Zustand wird bereits
  im Service gehalten. Für die Rehydrierung nach Xiaomi-Kill muss der Zustand
  persistiert werden: neue Methoden `snapshot()`/`restore()` an `TimerEngine`
  (domain, testbar) + DataStore/Room-Speicher in `:data:timer`. Ein
  `RestTimerRecovery`-Interface, das bei App-Start prüft: lief ein Timer und
  ist der Service weg → neu starten. **Testbar via Unit-Test (pure) + ein
  Robolectric-Integrationstest.**
- [ ] **MTU-Verhandlung (5a):** `BleSensorProvider` bekommt ein
  `MtuNegotiator`-Verhalten mit Timeout + Retry + Fallback auf MTU 23, und
  `onMtuChanged` mit `status != GATT_SUCCESS` wird als Fehlerpfad behandelt
  statt Endlos-Warten. Ausgelagert in eine pure, testbare Funktion
  `negotiateMtu(expected: Int, onResult: (Int) -> Unit)` im JVM-Teil von
  `:data:sensor`. **Unit-Testbar ohne BLE-Hardware.**
- [ ] **Latenz (5c):** Ein `AudioTimestampReader`-Interface (liefert
  `systemTimeNs`/`framePosition`), damit die Extrapolation in einem reinen
  JVM-Unit-Test geprüft werden kann; die echte Implementierung nutzt
  `AudioTrack.getTimestamp()`.

**Verifikation:** Bestehende Tests bleiben grün (kein Verhaltens-Change);
neue pure Tests für `negotiateMtu`/`snapshot`/`restore`/`extrapolate` grün.

---

### Schritt 3 — Compose-UI-Tests für Screens

**Ziel:** Abschnitt-11-Punkte "Waveform Scrubbing", "Marker Long Press",
"Route-Wechsel während Countdown" als UI-Tests.

- [ ] `:feature:player` und `:feature:workout` bekommen
   `androidTestImplementation(composeBom)` + `androidTestImplementation(
   libs.androidx.compose.ui.test.junit4)` + `debugImplementation(
   libs.androidx.compose.ui.test.manifest)`.
- [ ] Testklassen im `src/test` (läuft via Robolectric auf JVM) **oder**
   `src/androidTest` (Emulator): Entscheidung pro Test anhand der Abhängigkeit
   von echtem UI-Framework. Empfehlung: **erst `src/test` mit Robolectric**,
   damit die CI sie mitlaufen lässt; auf Emulator nur, wenn Robolectric nicht
   ausreicht (z. B. Canvas/GL-gestützte Waveform).
- [ ] Konkrete Tests:
   - `WaveformScrubbingTest`: `Waveform`-Composable rendern, Drag-Geste über
     `performTouchInput`, Assertion auf `onScrubPosition`/Seek-Position.
   - `MarkerLongPressTest`: Marker-Geste (`performTouchInput { longClick() }`),
     Assertion auf `onMarkerDrag`/`onMarkerContextMenu`.
   - `CountdownRouteSwitchTest` (PlayerViewModel-Layer): Route-Wechsel während
     Countdown → Generation Token invalidiert alte Events (Abschnitt 11 Unit).
- [ ] Compose-BOM-Version aus Catalog verwenden (bereits gesetzt).

**Verifikation:** `./gradlew :feature:player:test` grün; Waveform-Test läuft.

---

### Schritt 4 — Hilt-Test-Setup + echte instrumentierte Kern-Tests

**Ziel (T3/T4):** Die kleine Menge wirklich geräteabhängiger Tests mit Hilt.

1. **`app/build.gradle.kts`:**
   - `androidTestImplementation(libs.hilt.android.testing)` + `ksp(
     libs.hilt.compiler)` (oder `androidTestImplementation`-Variante je nach
     Catalog).
   - `testApplicationId = "com.dropsync.app.debug"` (sichtbar, eindeutig).
2. **Test-Application:**
   `app/src/androidTest/kotlin/.../HiltTestApplication.kt`
   (`@HiltAndroidApp`), registriert im
   `app/src/debug/AndroidManifest.xml` als `testInstrumentationRunner`
   Argument `customTestApplicationClass`.
3. **Runner:** `testInstrumentationRunner` bleibt `AndroidJUnitRunner`; in
   `defaultConfig` ein `testInstrumentationRunnerArguments` mit der
   `HiltTestApplication`.
4. **Tests (Instrumentiert, `src/androidTest`):**
   - `AudioTimestampExtrapolationInstrumentedTest`: `AudioTrack.getTimestamp()`
     zwei Samples → extrapoliertes Delta innerhalb Toleranz.
   - `UnderrunMonitorInstrumentedTest`: synthetische Stille→Impuls-Sequenz →
     Underrun erkannt (nutzt Golden-Audio-Fixture, Abschnitt 11).
   - `AudioFocusPermanentLossInstrumentedTest`: permanenter LOSS → Player
     stoppt; transient → duckt + Timer weiter.
   - `BleScanConnectInstrumentedTest`: (optional, braucht echtes BLE-Gerät;
     auf Emulator nur Smoke via `BluetoothAdapter` fake-fähig) — als
     **manuell markierter** Test mit `@Ignore`-Standard, damit CI nicht kippt.
5. **Fakes/DI:** `@HiltAndroidTest` + `@UninstallModules` für die betroffenen
   Module; `FakeSensorProvider`/`FakeTimerEngine` aus `:core:testing` bzw.
   moduleigenen Fakes wiederverwenden (bereits vorhanden).

**Verifikation:** `./gradlew :app:assembleDebugAndroidTest` kompiliert;
`connectedCheck` auf einem lokalen Gerät läuft für die 3 nicht-BLE-Tests.

---

### Schritt 5 — CI-Integration (Emulator + Firebase Test Lab)

**Ziel (T5):** Die Testpyramide wird automatisch gefahren; Doku-Links werden
geprüft.

1. **Neuer CI-Job "Instrumented"** in `.github/workflows/ci.yml`:
   - `reactivecircus/android-emulator-runner@v2` auf `ubuntu-latest`
     (größere Linux-Runner mit KVM):
     ```yaml
     - name: Enable KVM
       run: |
         echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
         sudo udevadm control --reload-rules
         sudo udevadm trigger --name-match=kvm
     - name: run connected tests
       uses: reactivecircus/android-emulator-runner@v2
       with:
         api-level: 34
         arch: x86_64
         script: ./gradlew connectedCheck
     ```
   - Matrix für API 29 + 34 (Breite ohne großen Aufwand).
2. **Optional (später, vor Release): Firebase Test Lab** für echte Geräte-
   Matrix inkl. Xiaomi/Pixel (nutzt `gcloud firebase test android run`).
   Voraussetzung: Firebase-Projekt + Service-Account in CI-Secrets.
   Ziel: die 3-4 instrumentierten Kern-Tests + ein Smoke-Flow auf
   Xiaomi-Geräten gegen Punkt-5b-Risiko.
3. **Doku-Link-Check (Punkt 4):** kleines Python-Skript
   `tools/doku_links_check.py`:
   - Extrahiert alle `docs/design/*.md`-Verweise im Format `Abschnitt <n>`
     und `Siehe <datei>#<abschnitt>`.
   - Prüft gegen einen Registry von stabilen, benannten Ankern (Tabelle in
     `docs/design/README.md` oder `docs/ANCHORS.md`).
   - Ersetzt im Plan selbst die Zahl-Verweise nach und nach durch benannte
     Anker (z. B. `[→ Shadow-DoD](#11b)`), damit Zahlenwechsel nie wieder
     still brechen.
   - CI-Schritt: `python3 tools/doku_links_check.py` → bricht bei toten
     Verweisen.
4. **Golden-Audio-Fixtures** (Abschnitt 11): `test-fixtures/audio/` im
   `:data:audio`-Test-Source-Set oder als `src/test/resources`; synthetisch
   generiert durch ein kleines Python-Tool `tools/audio_fixtures.py`
   (Sinus, Klickfolge, Bass-Impuls, Stille→Impuls, zwei Sample-Rates,
   künstlicher Crossfade, absichtlicher Underrun) — kompakt im Repo (klein
   genug, um committed zu werden).

**Verifikation:** `doku_links_check.py` findet die bewusst eingebaute
Kaputt-Zeile (Negativtest); CI-Job läuft grün; Fixtures vorhanden.

---

### Schritt 6 — Plattform-Härtung abschließen und dokumentieren

**Ziel:** Punkt 5 als durchgerüstet statt ad-hoc.

- [ ] **5a MTU:** `negotiateMtu` pure Funktion + Tests (Schritt 2) in Code;
   Diagnose-Felder (`lastNegotiatedMtu`, `parseErrors`, `duplicateReads`)
   in einen Debug-Log/Debug-Screen (Abschnitt 11 "Diagnose").
- [ ] **5b Xiaomi:** `RestTimerRecovery` bei App-Start (Rehydrierung +
   Watchdog); Robolectric-Test für "Service gekillt → Recovery startet neu";
   Hinweis-UI (App-Lock/Autostart/Batterie) als Teil von Phase 10
   (Berechtigungen + First Start).
- [ ] **5c Latenz:** `AudioTimestampReader` + Extrapolations-Unit-Test;
   Route-Profil lädt beim `AudioDeviceCallback`-Wechsel (Phase 6); UI zeigt
   Toleranzband, nie fixen ms-Wert.
- [ ] **Design-Doku (Punkt 4):** Abschnitt 9 und 12 kollidierende Pläne
   vereinen — Einzelschritte je Phase nach `docs/plans/` verschieben,
   Abschnitt 9 auf Verweis reduzieren; Abschnitt 12 bleibt Reihenfolge-Tabelle.
   Nummern-Verweise in bestehenden Dateien durch benannte Anker ersetzen
   (Check aus Schritt 5 erzwingt das künftig).

**Verifikation:** Neue Unit-Tests grün; Robolectric-Test grün;
`doku_links_check.py` grün; Design-Doku ohne doppelten Plan.

---

## 5. Mapping: Abschnitt-11-Punkte → Testschicht

| Abschnitt-11-Punkt | Heutige Abdeckung | Ziel-Schicht | Neuer Test |
|---|---|---|---|
| JitterBuffer | `JitterBufferTest` (Unit, grün) | 1 (bleibt) | — |
| Dedup | `BatchDedupTrackerTest` (Unit, grün) | 1 (bleibt) | — |
| Scan | `LibraryRepositoryImplTest` (Unit, grün) | 1 (bleibt) | — |
| TimerService Foreground | — | 2 (Robolectric) | `TimerServiceForegroundTest` |
| AudioFocus Ducking | `DuckingControllerTest` (Unit, grün) | 2 + 4 | `AudioFocusDuckingTest` (2), `AudioFocusPermanentLossInstrumentedTest` (4) |
| AudioTimestamp-Extrapolation | — | 1 (pure) + 4 | `AudioTimestampExtrapolationTest` (1), `AudioTimestampExtrapolationInstrumentedTest` (4) |
| Underrun-Monitoring | — | 1 + 4 | `UnderrunDetectionTest` (1), `UnderrunMonitorInstrumentedTest` (4) |
| Waveform Scrubbing | — | 3 | `WaveformScrubbingTest` |
| Marker Long Press | — | 3 | `MarkerLongPressTest` |
| RestMusicCoordinator | `RestMusicCoordinatorTest` (Unit, grün) | 1 (bleibt) | — |
| Route-Wechsel während Countdown | — | 1 (Generation Token) | `CountdownRouteSwitchTest` |
| Golden Audio Fixtures | — | Fixtures + 1/4 | `audio_fixtures.py` + DSP-Tests |

---

## 6. Neue/geänderte Dateien (Übersicht)

**Robolectric / Tests:**
- `data/timer/build.gradle.kts` (+ `:data:audio`, `:feature:player` … je nach Schritt 1)
- `data/timer/src/test/.../RobolectricTestCase.kt`
- `data/timer/src/test/.../TimerServiceForegroundTest.kt`
- `data/timer/src/test/.../AudioFocusDuckingTest.kt`
- `feature/player/src/androidTest/.../WaveformScrubbingTest.kt`
- `feature/player/src/androidTest/.../MarkerLongPressTest.kt`

**Härtung (Punkt 5):**
- `domain/timer/.../TimerEngine.kt` (+ `snapshot`/`restore`)
- `data/timer/.../RestTimerRecovery.kt`
- `data/sensor/.../MtuNegotiator.kt` (pure)
- `domain/audio/.../AudioTimestampReader.kt` (+ Extrapolator)
- `data/audio/.../AudioTimestampReaderImpl.kt`

**Hilt / instrumentiert:**
- `app/build.gradle.kts` (+ `hilt-android-testing`)
- `app/src/androidTest/.../HiltTestApplication.kt`
- `app/src/debug/AndroidManifest.xml`
- `app/src/androidTest/.../AudioFocusPermanentLossInstrumentedTest.kt`
- `app/src/androidTest/.../AudioTimestampExtrapolationInstrumentedTest.kt`
- `app/src/androidTest/.../UnderrunMonitorInstrumentedTest.kt`
- `app/src/androidTest/.../BleScanConnectInstrumentedTest.kt` (`@Ignore`)

**CI / Doku:**
- `.github/workflows/ci.yml` (+ instrumented-Job, + doku-links-check)
- `tools/doku_links_check.py`
- `tools/audio_fixtures.py`
- `test-fixtures/audio/` (generierte Fixtures)
- `docs/ANCHORS.md` (Registry stabiler Anker)
- `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (Abschnitt 9/12 entzerrt, Anker-Verweise)
- `docs/STATUS_FORTSCHRITT.md` (Fortschritt)

---

## 7. Abnahmekriterien (DoD für den Umbauplan)

- [ ] `./gradlew test spotlessCheck assembleDebug` grün.
- [ ] Neue Robolectric-Tests laufen in der JVM-CI mit (kein Emulator nötig).
- [ ] `app:assembleDebugAndroidTest` kompiliert; `connectedCheck` lokal grün
  (ohne den `@Ignore`-BLE-Test).
- [ ] `tools/doku_links_check.py` bricht bei toten Verweisen (Negativtest rot).
- [ ] `RestTimerRecovery`-Robolectric-Test grün (Xiaomi-Kill-Fallback belegt).
- [ ] `negotiateMtu`-Unit-Tests grün (Timeout/Retry/Fallback auf 23).
- [ ] `AudioTimestampExtrapolationTest` grün (Latenz messbar).
- [ ] Design-Doku hat genau einen Phasenplan (Abschnitt 12) + `docs/plans/`
  je Phase; Abschnitt 9 ist Verweis.
- [ ] `docs/STATUS_FORTSCHRITT.md` B.2 auf `[x]` (mit Session-Kennung).

---

## 8. Umsetzungsreihenfolge (Priorität)

| Prio | Schritt | Aufwand (rel.) | Grund |
|---|---|---|---|
| 1 | Schritt 1: Robolectric (TimerService, AudioFocus-Ducking) | mittel | Größter Hebel, Deps vorhanden, deckt 2 Abschnitt-11-Punkte |
| 2 | Schritt 6-Härtung 5b: `RestTimerRecovery` + Robolectric-Test | mittel | Punkt-5-Risiko wird testbar; Phase 10-Vorlauf |
| 3 | Schritt 5.3: `doku_links_check.py` + Anker-Refactor | klein | Verhindert Doku-Regressionen sofort |
| 4 | Schritt 3: Compose-UI-Tests (Scrubbing, Long-Press) | mittel | Deckt weitere Abschnitt-11-Punkte |
| 5 | Schritt 4: Hilt + instrumentierte Kern-Tests | mittel | Geräte-Tests, erst wenn 2. Schicht steht |
| 6 | Schritt 5.1/5.2: CI-Emulator + Firebase Test Lab | mittel | Automatisierung, optional bis Release |
| 7 | Schritt 5.4: Golden-Audio-Fixtures | klein | Fixtures für Schritt 4 nötig |
| 8 | Schritt 6-Doku: Abschnitt 9/12 entzerren | klein | Doku-Hygiene, jederzeit |

**Pilot zuerst (bewährtes Muster aus SHADOW-PLAN):** Mit Schritt 1 am
`:data:timer`-Modul eine komplette Robolectric-Testkette einmal durchlaufen,
bevor auf andere Module gerollt wird.

---

## 9. Risiken und offene Punkte

| Risiko | Antwort |
|---|---|
| Robolectric deckt `AudioTrack`/echtes Audio nicht | Genau dafür bleiben die 4 instrumentierten Tests (echtes Framework). Robolectric prüft die Logik, Gerät prüft die Hardware. |
| `hilt-android-testing` braucht angepasstes Manifest | Standard-Pattern: `debug`-Manifest mit `HiltTestApplication`; im Plan Schritt 4 explizit. |
| Instrumentierte Tests machen CI langsam | T3 begrenzt sie auf ein Minimum; Emulator-Runner nutzt KVM (schnell). Nicht-BLE-Tests laufen lokal auf Gerät. |
| Abschnitt-9/12-Entzerrung ist heikel (A1-Kollision) | Nur Reduktion auf Verweis, keine inhaltliche Neuschreibung; `doku_links_check` erzwingt danach Konsistenz. |
| `:core:testing` kann keine Android-Helfer teilen | T7: Helfer liegen je Android-Modul; ein Fixture-Modul nur falls Duplikat-Ebene erreicht wird. |
| BLE-Test auf Emulator nutzlos | `BleScanConnectInstrumentedTest` bleibt `@Ignore` + manuell markiert; CI fährt ihn nicht. |
| Firebase Test Lab braucht Projekt + Secrets | Optional bis Release; CI-Schritt bricht bei fehlenden Secrets sauber ab (`if: secrets.X`). |

---

## 10. Bewusst nicht in diesem Plan

- Den vollen Abschnitt-11-Funktionsumfang selbst implementieren (nur testbar
  machen). Funktionsbau bleibt in den jeweiligen Phasen.
- Golden-Audio-Fixtures inklusive Hörtest — Metriken prüfen, Hörtest bleibt
  manuell (Abschnitt 11 sagt das selbst).
- BaselineProfile (ADR-0001, Schritt 13) — hier nur der Verweis.
- Ein Debug-Screen für BLE-Diagnose — als eigener kleiner Schritt in Phase 6.

---

## 11. Nutzen-Zusammenfassung

Mit diesem Plan wird der offene Punkt B.2 geschlossen und die Abschnitt-11-
Liste über eine **Testpyramide** abgedeckt: 80 % auf der JVM (Unit +
Robolectric + Compose-UI), nur Audio-/BLE-Kernpfade als echte Geräte-Tests,
CI automatisiert inklusive Doku-Link-Check. Die ad-hoc-Plattform-Risiken aus
Abschnitt 13 (MTU, Xiaomi-Kill, Latenz) bekommen Tests, die sie reproduzierbar
machen und vor Regression schützen — und die kollidierende Design-Doku
(Abschnitt 9 vs. 12) wird auf eine Single-Source-of-Truth zurückgeführt.
