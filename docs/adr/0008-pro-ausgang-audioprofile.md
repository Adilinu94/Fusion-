# ADR-0008: Audioprofile pro Ausgabegeraet

Datum: 2026-07-27
Status: Akzeptiert

## Problem

EQ, Preamp, Crossfade, Resampler und DVC sollen je Ausgang (Lautsprecher,
Kabel, konkretes BT-Geraet, USB-DAC) getrennt einstellbar sein und beim
Geraetewechsel automatisch umschalten.

## Optionen

1. Ein globales Settings-Set — erfuellt die Anforderung nicht.
2. Profile in Room — Overkill fuer Key-Value-Daten, Migrationothek waechst.
3. DataStore-Preferences mit Profilschluessel aus `AudioDeviceInfo`:
   Typklasse (SPEAKER/WIRED/A2DP/USB) plus Adresse, sofern vorhanden.

## Entscheidung

Option 3. `AudioDeviceCallback` liefert Wechselereignisse; der aktive
Profilschluessel wird als Flow veroeffentlicht und die DSP-Konfiguration
atomar umgeschaltet. Unbekannte Geraete erben das Typklassen-Profil.

## Folgen

- `DspSettingsStore` speichert alle Werte unter `profil:praefix`-Keys.
- Das UI zeigt das aktive Profil an; Bearbeitung wirkt immer auf das
  gerade aktive Profil (kein verstecktes Fernkonfigurieren).
