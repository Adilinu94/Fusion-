# ADR-0009: Bit-Perfect-Modus (USB, Android 14+)

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Bitgenaue Wiedergabe verlangt, dass Samples den DAC unveraendert
erreichen. Androids Mixer resampelt normalerweise; Bluetooth (A2DP)
kodiert immer verlustbehaftet und kann nie bit-perfect sein.

## Optionen

1. Eigener USB-Audio-Treiber (USB Host API) — maximale Kontrolle, aber
   ein eigenes Treiberprojekt und Verlust aller Systemintegration.
2. Android-14-API `AudioManager.setPreferredMixerAttributes` fuer
   USB-Geraete (offizieller Bit-Perfect-Pfad, von Google fuer
   Audiophile-Apps eingefuehrt).

## Entscheidung

Option 2. Bit-Perfect ist nur fuer USB-Ausgaenge ab Android 14 aktivierbar.
Bei Aktivierung gilt: DSP-Kette komplett Bypass, kein Float-Output-Umweg,
keine Lautstaerke-/Ducking-Eingriffe (Cue-Ducking pausiert die Musik
stattdessen kurz), Quellrate wird unveraendert ausgegeben. LDAC/aptX HD
bleiben Sache des BT-Stacks: die App zeigt den aktiven Ausgabepfad an und
verlinkt fuer die Codec-Wahl in die Systemeinstellungen.

## Folgen

- UI kommuniziert die Exklusivitaet (Bit-Perfect vs. DSP) explizit.
- Auf Geraeten < Android 14 oder ohne USB-DAC ist der Schalter mit
  Begruendung deaktiviert.
- DSD-ueber-DoP wird erst nach stabilem PCM-Bit-Perfect-Pfad geprueft.
