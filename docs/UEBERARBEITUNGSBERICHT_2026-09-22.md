# Ueberarbeitungsbericht 2026-09-22

Anlass: Tiefen-Audit nach Abschluss der Tranchen D1-D7 und D5 A5-A8
("pruefe sehr genau, ob es noch etwas zu verbessern gibt"), danach
Abarbeitung aller machbaren Punkte in zwei Runden. Methode: Coverage-
Inventar je Modul (Kover LINE, bereinigt um generierten Code),
Klassen-Sichtung der 0-%-Kandidaten, TODO/`@Suppress`/`GlobalScope`/
`!!`-Sichtung, Detekt-Baseline, Deprecation-Sichtung und Abgleich der
offenen Punkte aus `STATUS_FORTSCHRITT.md` / Verbesserungsanalyse.

## 1. Erledigt

### Runde 1 (Messung + Stores)

- **Kover-Messung bereinigt:** Generierter Hilt-/Dagger-Code
  (`*_Factory`, `*_MembersInjector`, `*_GeneratedInjector`, `Hilt_*`,
  `hilt_aggregated_deps`) verfaelschte die Linien-Coverage mit 0-%-Klassen
  je Injektionspunkt (in `data:playback` allein ~17 % der Zeilen). Fuenf
  Filter-Patterns in der Root-`build.gradle.kts`, am Report verifiziert.
  Fallstricke dokumentiert: Kover-0.9-Filter nutzen Punkt-Notation,
  `*` matcht auch Punkte, der Gradle-Build-Cache liefert sonst veraltete
  Reports (Filter sind kein Task-Input).
- **35 Tests:** `PlaybackSettingsStoreTest` (2), `RestMusicSettingsStoreTest` (3),
  `RouteProfileStoreTest` (3), `RestTimerPreferencesStoreTest` (4),
  `DataStoreDropSyncPlanStoreTest` (6), `WorkoutGoalPreferencesStoreTest` (3),
  `BleErrorMapperTest` (14).
- **Coverage-Ratsche angehoben** (nur steigende Untergrenzen, `koverVerify` gruen).

### Runde 2 (Ports + Kernluecken)

- **`LandingPlayer`-Port + `ExoLandingPlayer`:** `DropLandingArmer` haengt
  nicht mehr am `ExoPlayer`, sondern an einem schmalen Port (Position,
  Index, Zustand, Volume, positionsgebundene Nachricht, Titelwechsel).
  `PlaybackService` bindet den Media3-Adapter. **9 neue Tests**: Zielposition
  (Position + Delay), Landed mit Delta, Watchdog OVERRIDDEN/WATCHDOG,
  Cancel, Ersetzen durch neue Armierung, PLAYER_ERROR, detach, Fade-Rampe
  vor dem Wechsel.
- **`DuckingTarget`-Port:** `PlayerVolumeGateImpl`/`RestDuckingGateImpl`
  laufen ueber den Preamp-Port statt direkt auf der `AudioPipeline`;
  DI liefert `AudioPipelineDuckingTarget`. **4 Tests** (Gain-Roundtrip,
  Rest-Ducking aus der Config, 0 dB inaktiv).
- **`AudioTrackTimestampReader`: 2 Tests** — Robolectric reicht
  `(systemTimeNs, framePosition)` durch; das Warm-up-Gate ist unter dem
  Schatten nicht simulierbar (dokumentiert).
- **`data:workout`-Repository-Pfade: 11 Tests** — `getSessionMusic`
  (Reihenfolge + Titel-Fallback auf Dateinamen), `getExerciseDetail`
  (lokalisierter Name, Muskel-Sortierung, Fehlerfall),
  `createCustomExercise` (de/en-Pflicht, Muskel-Validierung, Slug-Anlage),
  `TargetRepositoryValidationTest` (fuenf Validierungsfaelle + Roundtrip).
- **`HapticsAdapter`: 2 Tests** (Vibration erreicht den System-Vibrator,
  Abschluss-Ton startet).
- **Detekt-Baseline 23 -> 19:** vier `LoopWithTooManyJumpStatements`
  verhaltensgleich refactored (`FolderHierarchy.childSegmentOf`,
  `LibraryRepositoryImpl.importCueDocument`, `M3uPlaylistParser.consumeLine`,
  `StreamingResampler.interpolate`).
- **`:app`: +1 Test** (Icon/Label jedes Hauptziels) — die Route-Vertraege
  deckte `NavigationRoutesTest` bereits ab.
- **Coverage nach Runde 2:** `data:playback` 14,3 -> **25,6 %**,
  `data:workout` 63,9 -> **80,0 %**, `data:timer` 50,7 -> **54,4 %**;
  Floors angehoben (data:playback 13->24, data:workout 62->78,
  data:timer 49->53), `koverVerify` gruen.

## 2. Offene Punkte (Stand nach beiden Runden)

### 2.1 Hoch: Media3-Session-Schicht in `data:playback`

| Klasse | Zeilen | Warum offen |
| --- | --- | --- |
| `PlaybackRepositoryImpl` | 89 | braucht `PlayerConnection`/Stores-Fakes; der Port existiert (`PlayerConnection`), ein Fake waere machbar — naechster Kandidat |
| `PlaybackService` + `LibrarySessionCallback` | 208 | Media3-`MediaLibrarySession`-Callbacks, nur mit Service-/Session-Stack sinnvoll |
| `Media3AudioClock` | 40 | Positions-/Elapsed-Ableitung aus Media3-Events |
| `MediaControllerConnection` | 18 | duenner Verbindungs-Cache (18 Z.); Verhalten nur mit echtem Service pruefbar |

Empfehlung: `PlaybackRepositoryImpl` zuerst (Fake-`PlayerConnection` +
Fake-`PlayerStateStore` nach dem `LandingPlayer`-Muster), danach
`Media3AudioClock` ueber einen Event-Port.

### 2.2 Mittel: Restliche ungetestete Klassen

- `data:timer`: `TtsSpeaker` (32 Z.), `CountdownBeepPlayer` (33),
  `AndroidCueOutput` (18) — Robolectric-Schatten fuer `TextToSpeech`/
  `AudioTrack` sind unzuverlaessig; sinnvoll erst mit Port-Extraktion.
- `data:sensor`: `BleGattClient` (64 Z.) — braucht BLE-Stack; nur mit
  Fakes/Refactoring testbar.

### 2.3 Mittel: Detekt-Baseline (19 Eintraege, Bremse 23)

Verbleibend: 8x `LongMethod` (fast alle Composables), 7x
`CyclomaticComplexMethod`, 3x `LargeClass`, 1x
`LoopWithTooManyJumpStatements` (`CalibrationController.knownCountSweep` —
verschachtelte Sweep-Kernlogik mit Live-Hardware-Verifikation, bewusst
nicht angetastet). Naechster sinnvoller Schritt: die `LargeClass`-Faelle
(`TrainViewModel`, `DropSyncCoordinator`, `WorkoutRepositoryImpl`) mit
eigenen ADRs zerlegen.

### 2.4 Mittel: `:app`-Testausbau

`:app` hat nur JVM-Test-Abhaengigkeiten (JUnit4 + Coroutines). Compose-
Tests fuer `OnboardingScreen`, `HealthRationaleActivity` und den
`DropSyncApp`-NavHost brauchen Robolectric + Compose-Test-Artefakte als
`testImplementation` und `isIncludeAndroidResources`; die Libs sind im
Katalog/der Verifikationsmetadaten bereits vorhanden. Eigenes kleines
Paket.

### 2.5 Mittel: Paket 4.19 aus der Verbesserungsanalyse

- build-logic-Convention-Plugins (Konfiguration liegt heute in der
  Root-`build.gradle.kts`),
- Doku-Konsolidierung,
- 2.9 Crossfade (geparkt, E2), 3.14-Rest, 4.17 Material 3 Expressive
  (wartet auf stabiles 1.5.0).

### 2.6 Niedrig: Hygiene (geprueft, unkritisch)

- 13 Deprecation-Suppresses (BLE-Legacy, MediaStore-Spalten, Vibrator) —
  kompatibilitaetsbedingt.
- 16 `!!` im Prod-Code — `runCatching`-geschuetzt oder invariantenbasiert.
- 1 TODO-Kommentar, kein `println`/`GlobalScope` im Prod-Code.

### 2.7 Bewusst offen / blockiert

- Gate-11b (Adis Sensor-Aufnahmen), B4-Hoerprobe, E2/E3, Geraeteabnahmen.
- `domain:settings`: Contract-Modul ohne messbare Zeilen (Floor 0 korrekt).
- Kover-Modul-Artefakt bei Datenklassen (nur modul-lokale Messung; eine
  Report-Aggregation waere eine eigene Entscheidung).

## 3. Verifikation (Stand Ende Runde 2)

`spotlessCheck`, `detekt`, `koverVerify` (alle Floors), Modul-Tests
(data:playback, data:workout, data:timer, data:sensor, data:settings,
domain:library/audio, data:library, feature:library, :app), Python-Gates
(Detekt-Baseline 19/23, Doku-Links 82 Dateien, Design) und CRLF-Checks
gruen.
