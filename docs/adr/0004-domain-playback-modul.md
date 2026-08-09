# ADR-0004: Zusaetzliches Modul `:domain:playback` fuer Wiedergabevertraege

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Bauplan 3.3 nennt den `PlaybackRepository` als einzigen App-Zugang zur
Wiedergabe, und Regel 3.2/4 verbietet Features jede Media3-Abhaengigkeit.
Der Modulbaum in Abschnitt 3.2 enthaelt aber kein Domain-Modul, in dem der
Media3-freie Wiedergabevertrag liegen koennte. Dasselbe Problem wurde fuer
die Bibliothek bereits in ADR-0003 entschieden.

## Optionen

1. Vertrag in `:core:model` legen — verletzt die Definition "reine
   Domain-Modelle und Value Objects".
2. Vertrag in `:domain:timer` legen — vermischt Wiedergabe- und
   Timerdomaene.
3. Ein Modul `:domain:playback` ergaenzen, das exakt denselben Regeln wie
   die anderen Domain-Module folgt (nur `:core:common`, `:core:model`;
   kein Android, Room oder ExoPlayer).

## Entscheidung

Option 3, konsistent mit ADR-0003. `:domain:playback` enthaelt
`PlaybackRepository`, `PlaybackState`, `RepeatMode` und den persistierten
Wiederherstellungszustand — alles ohne Media3-Typen.

## Folgen

- `settings.gradle.kts` und der Architekturtest kennen `:domain:playback`.
- `:data:playback` implementiert den Vertrag mit MediaController/ExoPlayer;
  `:feature:player` verwendet nur den Vertrag.
