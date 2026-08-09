# ADR-0001: Modul `:baselineprofile` wird erst in Schritt 13 angelegt

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Der Bauplan nennt in Abschnitt 3.2 das Modul `:baselineprofile` als Teil des
Modulbaums, und Schritt 2 verlangt, alle Module aus Abschnitt 3.2 anzulegen.
Gleichzeitig legt Schritt 13.6 fest, dass ein Baseline Profile erst **nach
funktionaler Stabilitaet** erzeugt wird. Ein `com.android.test`-Modul mit
Macrobenchmark-Abhaengigkeiten haette in Schritt 2 keinen Inhalt, wuerde aber
eine zusaetzliche Plugin- und Bibliothekslizenzpruefung sowie eine
Geraete-/Emulator-Konfiguration erfordern, die erst in Schritt 13 gebraucht wird.

## Optionen

1. Leeres `com.android.test`-Modul sofort anlegen und bis Schritt 13 ungenutzt
   mitschleppen (zusaetzliche Abhaengigkeiten ohne Funktion).
2. Modul erst in Schritt 13 anlegen, wenn Macrobenchmark und Profilerzeugung
   tatsaechlich implementiert werden.

## Entscheidung

Option 2. Das Modul `:baselineprofile` wird zusammen mit den
Performance-Aufgaben in Schritt 13 angelegt. Der uebrige Modulbaum aus
Abschnitt 3.2 wird vollstaendig in Schritt 2 umgesetzt.

## Folgen

- `settings.gradle.kts` enthaelt einen Verweis auf diese ADR.
- Die Macrobenchmark-Bibliothek wird erst bei Anlage in
  `THIRD_PARTY_NOTICES.md` erfasst.
- Kein Einfluss auf Architekturregeln: `:baselineprofile` haette ohnehin keine
  Abhaengigkeitsbeziehung zu Fach- oder Feature-Modulen.
