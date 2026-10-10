# ADR-0029: Timing-Konfidenz "exakt" nur bei eingestellter Latenz

Datum: 2026-09-28
Status: Akzeptiert (Bericht 2026-09-28)

## Problem

`DropSyncPlanner` setzte `TimingConfidence.EXACT`, sobald irgendein Latenzwert vorlag - das
ist wegen der Tabelle (Lautsprecher 40, Kabel 25, SBC 120, AAC 80, LDAC 150 ms) immer der
Fall. Die Latenz eines A2DP-Geraets streut je Geraet und Codec um 100 ms und mehr; "Timing
stable" behauptete damit eine Genauigkeit, die die App nicht kennt. Ausser dem Test-Fake
schrieb niemand ein gemessenes Profil (`RouteProfileRepository.upsert` hatte keinen
produktiven Aufrufer).

## Entscheidung

- `timingConfidenceFor()`: `EXACT` nur bei `AudioRouteProfile.Confidence.CALIBRATED`; ein
  Tabellenwert (`ESTIMATED`), `STALE` und ein fehlendes Profil ergeben `DEGRADED`.
- Einstellung: Settings > "Drop-Timing dieser Ausgabe", Regler 0-400 ms in 10-ms-Schritten
  je Route (Drop zu spaet -> Wert erhoehen), gespeichert beim Loslassen ueber `upsert`
  (`CALIBRATED`), Reset ueber das neue `RouteProfileRepository.clearCalibration()`.

## Verworfen

- Automatischer Tap- oder Mikrofon-Test: braucht Audio-Ausgabe, und die Reaktionszeit der
  Person (20-50 ms, systematisch vorauseilend) beziehungsweise ein Kopfhoerer-Mikrofon-
  Pfad verfaelscht das Ergebnis. Ohne Geraet nicht pruefbar. Folgearbeit, falls der
  Regler im Alltag zu umstaendlich ist.

## Konsequenzen

- Auf jeder noch nie eingestellten Route zeigt die Pausen-Konsole nicht mehr "stabil".
  Die Landung selbst aendert sich nicht: sie rechnet weiter mit dem Tabellenwert.
