# Fusion Foundation Plan (Phase 0)

**Datum:** 2026-08-09
**Ziel:** DropSync bleibt Basis, FlowRep Archive wird lesbar, Build grün.
**Referenz:** `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` Phase 0

---

## Aufgaben

| # | Aufgabe | Dateien | Status |
|---|---------|---------|--------|
| 1 | Branch `fusion/foundation` im DropSync-Repo erstellen | - | ✅ (bereits im Clone) |
| 2 | FlowRep-relevante Ordner als `docs/archive/flowrep-import` spiegeln | Parser, Engine, Screens, Docs | ⬜ |
| 3 | `gradle/libs.versions.toml` als alleinige Versionsquelle bestätigen | `gradle/libs.versions.toml` | ✅ (bereits korrekt) |
| 4 | AndroidManifest: Paket `com.dropsync` bestätigen, Label `FlowRep` setzen, Launcher Icon Lime | `app/src/main/AndroidManifest.xml` | ⬜ |
| 5 | CI grün: `./gradlew test` + `spotlessCheck` + `assembleDebug` | alle | ⬜ |
| 6 | App startet mit 4 leeren Tabs (Train, Music, Verlauf, Einstellungen) | `app/src/main/...` | ⬜ |

---

## Phase 0 Details

### 1. Branch `fusion/foundation`

Im DropSync-Repo (`dropsync-timer/`) einen neuen Branch `fusion/foundation` von `main` erstellen.

### 2. FlowRep-Archiv einbinden

Aus dem FlowRep-Flutter-Repo (älterer Stand) folgende Ordner/Dateien als Referenz in `docs/archive/flowrep-import/` kopieren:

- `app/lib/domain/workout_engine.dart`
- `app/lib/data/ble_sensor_provider.dart`
- `app/lib/data/parser.dart`
- `app/lib/presentation/screens/` (home, calibration, history, settings)
- `docs/reference/protocol.yaml`
- `docs/design/` (ausgewählte Dokumente)

Diese Dateien sind **Referenz**, nicht Teil des Builds. Sie dienen als Vorlage für die Kotlin-Portierung in späteren Phasen.

### 3. Versionskatalog bestätigen

`gradle/libs.versions.toml` ist bereits die alleinige Quelle für alle Versionen. Keine Änderung nötig.

### 4. AndroidManifest anpassen

- `android:label` auf `"FlowRep"` setzen (oder `@string/app_name` mit Wert `FlowRep`)
- `android:icon` auf provisorisches Lime-auf-Schwarz-Icon setzen
- Paket bleibt `com.dropsync.app` (keine Migration)

### 5. CI-Checks

```bash
./gradlew test
./gradlew spotlessCheck
./gradlew assembleDebug
```

Alle drei müssen grün sein.

### 6. 4 leere Tabs implementieren

In `app/src/main/kotlin/com/dropsync/app/`:

- `MainActivity.kt` mit `Scaffold` + `NavigationBar`
- 4 Tabs: Train (Start), Music, Verlauf, Einstellungen
- Jeder Tab zeigt einen leeren Screen mit Titel
- Navigation per `NavigationSuiteScaffold` oder `NavigationBar`

---

## Verifikation

- [ ] Build und Tests grün
- [ ] App startet mit 4 leeren Tabs
- [ ] Label ist "FlowRep"
- [ ] Kein Onboarding, direkt Train Tab
