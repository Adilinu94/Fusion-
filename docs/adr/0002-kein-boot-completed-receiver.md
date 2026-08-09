# ADR-0002: Kein `BOOT_COMPLETED`-Receiver in Version 1

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Bauplan Schritt 7.9 erlaubt optional einen `BOOT_COMPLETED`-Receiver als
zusaetzliche fruehe Bereinigung verwaister Timer nach einem Geraeteneustart.
Dieser Receiver benoetigt die Manifest-Berechtigung `RECEIVE_BOOT_COMPLETED`.
Die verbindliche Berechtigungstabelle in Abschnitt 4 enthaelt diese
Berechtigung nicht, und Schritt 14.1 verlangt, jede dort nicht begruendete
Berechtigung zu entfernen.

## Optionen

1. Receiver implementieren und die Berechtigungstabelle erweitern
   (Berechtigung ohne Nutzerwert, da die Monotonie-Pruefung ohnehin
   verpflichtend ist).
2. Nur die verpflichtende Monotonie-Pruefung aus Schritt 7.9 implementieren:
   Beim Start der Timer-Infrastruktur wird `elapsedRealtime()` mit dem
   gespeicherten letzten monotonen Zeitwert verglichen; bei Inkonsistenz wird
   der Timer als `CANCELLED` mit Grund `DEVICE_REBOOT_OR_UNKNOWN_CLOCK`
   markiert.

## Entscheidung

Option 2. Der optionale Receiver entfaellt in Version 1. Schritt 7.9 nennt ihn
ausdruecklich als nicht einzige Absicherung; die Pflichtabsicherung ist die
Monotonie-Pruefung, die vollstaendig implementiert wird.

## Folgen

- Das Manifest bleibt deckungsgleich mit der Berechtigungstabelle in
  Abschnitt 4.
- Verwaiste Timer werden beim naechsten Start der Timer-Infrastruktur
  bereinigt, nicht bereits beim Boot. Das ist fachlich folgenlos, weil ohne
  laufende App keine Cues ausgegeben werden.
