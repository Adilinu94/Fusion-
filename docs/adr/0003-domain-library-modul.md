# ADR-0003: Zusaetzliches Modul `:domain:library` fuer Bibliotheksvertraege

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Regel 3.2/3 verlangt, dass `:data:*` Repository-Interfaces "aus dem
jeweiligen Domain-Modul" implementiert, und Regel 3.2/4 erlaubt Features nur
Domain-Use-Cases. Der Modulbaum in Abschnitt 3.2 enthaelt aber nur
`:domain:timer` und `:domain:workout`. Fuer `:data:library` (Songs, Marker,
Scan) existiert kein Domain-Modul, in dem die Vertraege liegen koennten.

## Optionen

1. Vertraege in `:core:model` legen — verletzt die Definition "reine
   Domain-Modelle und Value Objects".
2. Vertraege in `:domain:timer` legen — vermischt Bibliotheksdomaene mit
   Timerdomaene und erzwingt unnoetige Abhaengigkeiten.
3. Ein Modul `:domain:library` ergaenzen, das exakt denselben Regeln wie die
   anderen Domain-Module folgt (nur `:core:common`, `:core:model`; kein
   Android/Room/Player).

## Entscheidung

Option 3. `:domain:library` enthaelt die Repository-Interfaces und
Use-Cases der Musikbibliothek und des Markerimports. Der
Modulabhaengigkeitstest prueft es automatisch mit denselben Regeln.

## Folgen

- `settings.gradle.kts` und der Architekturtest kennen `:domain:library`.
- `:data:library` implementiert diese Vertraege; `:feature:library` und
  `:feature:settings` verwenden nur die Use-Cases.
