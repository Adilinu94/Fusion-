# ADR-0007: Crossfade ueber zwei ExoPlayer-Instanzen

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Crossfade zwischen Titeln ist in ExoPlayer/Media3 nicht enthalten
(androidx/media Issue #2, seit 2021 offen). Ein einzelner Player kann
nicht gleichzeitig zwei Quellen mischen.

## Optionen

1. Eigener Mixer im AudioProcessor — der Prozessor sieht nur den Stream
   des aktuellen Titels, der naechste Titel ist dort nicht verfuegbar.
2. Warten auf Media3 (CompositionPlayer ist experimentell und fuer
   Editing gedacht, nicht fuer MediaSession-Wiedergabe).
3. Zweiter, vorgepufferter ExoPlayer im PlaybackService; Uebergang ueber
   equal-power-Volumenrampen; die MediaSession bleibt am Hauptplayer.

## Entscheidung

Option 3. Der Zweitplayer existiert nur waehrend eines aktiven Crossfades
(0-12 s, Standard aus) und wird danach sofort freigegeben. Kein Crossfade
bei Gapless-Album-Uebergaengen (abschaltbare Heuristik), nie bei
Bit-Perfect. Bei Ressourcenmangel harter Uebergang statt Absturz.

## Folgen

- Die Serviceregel aus Schritt 5 wird praezisiert: genau ein
  *sessionfuehrender* Player; der Fade-Player ist ein internes, kurz
  lebendes Hilfsobjekt und haelt nie die MediaSession.
- Cue-Ducking (Schritt 8) wirkt waehrend eines Fades auf beide Player.
