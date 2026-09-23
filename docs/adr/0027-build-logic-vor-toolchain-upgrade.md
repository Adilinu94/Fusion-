# ADR-0027: build-logic-Convention-Plugins als eigener Umbau vor dem naechsten Toolchain-Upgrade

Datum: 2026-09-23
Status: Akzeptiert (Nutzerentscheidung im Analyse-Paket 6)

## Problem

Die Analyse vom 23.09.2026 (Befund 3.8, Paket 6 Punkt 37) bestaetigt:
28 Modul-`build.gradle.kts` duplizieren `compileSdk`/`minSdk`-Getter,
`compileOptions` (Java 17) und `testInstrumentationRunner` identisch.
Bereits beobachtete Drift: `isIncludeAndroidResources` musste
nachtraeglich in `feature/library` und `feature/player` einzeln ergaenzt
werden — genau das Muster, das Convention Plugins verhindern wuerden.

Ein SDK-/AGP-/Kotlin-Upgrade (Befund 3.9, Alpha-Toolchain) beruehrt heute
20+ Dateien.

## Optionen

1. **Jetzt migrieren:** `build-logic/` (included build) mit
   Convention Plugins (`dropsync.android.library`, `dropsync.hilt`,
   `dropsync.roborazzi` ...) in dieser Serie aufbauen.
2. **Als eigener Umbau planen:** Migration vor dem naechsten
   Toolchain-Upgrade als eigenes Paket mit eigenem Verify-Zyklus.

## Entscheidung

**Option 2 — eigener Umbau, gebuendelt mit dem Toolchain-Upgrade.**
Begruendung:

- Die Migration ist rein mechanisch, aber breit: 28 Module in einem
  Commit-Bereich; jeder Halb-Zustand waere ein funktionierender, aber
  halb duplizierter Build. In dieser Fehlerbehebungs-Serie waere das der
  groesste Diff mit dem kleinsten Nutzerwert.
- Der echte Nutzen zahlt beim naechsten Upgrade aus (SDK/AGP/Kotlin in
  einer Datei statt 20+). Der richtige Zeitpunkt ist also genau vor
  diesem Upgrade, nicht davor.
- CI und Dependency-Verification laufen aktuell stabil; eine Build-Logik-
  Migration ist der hoechste Verifikationsaufwand pro Zeile Nutzwert.

## Folgen

- Befund 3.8 bleibt offen und ist terminiert: vor dem naechsten
  Toolchain-Upgrade (Befund 3.9), als eigenes Paket.
- Bis dahin gilt die Disziplinregel: neue Module kopieren das naechste
  Module-Buildfile (Stand heute), keine weiteren Ad-hoc-Abweichungen.
- Das Upgrade-Paket umfasst dann: build-logic anlegen, 28 Module
  umstellen, Toolchain-Hebung, vollstaendiger Verify (Build, Tests,
  Roborazzi, Lint, Baseline-Warnung).
