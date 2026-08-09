# FlowRep-Import (Referenz für die Fusion)

Gespiegelt aus [Adilinu94/flowrep](https://github.com/Adilinu94/flowrep) für die
Fusion mit DropSync-Timer (siehe
`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md`, Phase 0).

**Quelle:** Commit `a14f2f179dcaca8945dabcddf4f5d32fde294890` (2026-08-07)
**Gespiegelt:** 2026-08-08

Reines Referenzmaterial für den Flutter-zu-Kotlin-Port. Kein Build-Bestandteil
dieses Repos (kein Gradle-Modul, wird nicht kompiliert, nicht in settings.gradle.kts
eingebunden).

## Inhalt

| Ordner | Quelle in FlowRep | Zweck |
|---|---|---|
| `parser/` | `app/lib/data/protocol/` + `app/test/ble_protocol_parser_test.dart` | BLE-Protokoll-Parsing. Die Testdatei liegt bei, weil laut Design-Dokument (Abschnitt 12, Risiken) die Parser-Tests 1:1 gegen dieselben Fixtures im Kotlin-Port laufen sollen. |
| `engine/` | `app/lib/domain/` | Kalibrierung, Erkennung, Filter, Metriken, Workout-/Exercise-Engine |
| `screens/` | `app/lib/presentation/screens/` | Flutter-UI-Screens als Referenz für die Kotlin/Compose-Screens |
| `docs/` | `docs/Version1.0/`, `docs/reference/`, `docs/design/` | Nur die Ordner, die FlowReps eigenes `docs/README.md` als "AKTIV" markiert |

## Bewusst ausgelassen

- `docs/archive/` — laut FlowReps eigener Regel "nicht als aktuellen Stand zitieren, nur Hintergrund"
- `docs/hardware/` — Firmware-/Hardware-Testpläne und Session-Evidence, betrifft die App-Fusion nicht direkt (mit Abstand größter Einzelordner, ~1,5 MB)
- alles außerhalb von `app/lib/{data/protocol, domain, presentation/screens}` (providers, repositories, services, widgets, main.dart) und `app/test/` außer der einen Parser-Testdatei — nicht Teil der in Phase 0 genannten vier Kategorien (Parser/Engine/Screens/Docs)

Wenn mehr gebraucht wird: gezielt einzeln nachziehen, nicht pauschal den Rest kopieren.
