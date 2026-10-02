# ADR-0030: Drop-Erkennung ueber Bass-Rueckkehr statt nur Fullband-RMS

Datum: 2026-09-28
Status: Akzeptiert (Bericht 2026-09-28; nur auf synthetischem Audio belegt)

## Problem

`OnsetDetection` wertet positive Spruenge der Fullband-RMS. (1) In einem gleichmaessigen
Groove feuert die Novelty bei jedem Kick; welche drei Kicks in die Top 3 kommen, ist fast
zufaellig. (2) Drops nach einem Bass-Break mit Riser/Pad - Gesamtlautstaerke steht schon
oben, der Tiefbass kehrt zurueck - fallen durch.

## Entscheidung

- `BassEnergyAccumulator`: RMS des Bassbandes (zwei Butterworth-Biquads, 150 Hz, 24 dB/Okt.)
  in denselben 25-ms-Fenstern wie `EnergyAccumulator`.
- `DropDetection`: Bass-Rueckkehr zuerst (Bass 4 s davor <= 40 % des Referenzpegels, 2 s
  danach >= 70 %, mindestens Faktor 2,5), Position auf das Fenster mit dem staerksten Bass-
  Einsatz, optional auf ein `BeatGrid` gerastet (Toleranz 60 ms). Fullband-Kandidaten
  fuellen auf. Ohne Bass-Energie identisch zum bisherigen Verhalten.
- `TrackAnalyzerImpl` nutzt `DropDetection.candidatePositions`. Weiter nur Kandidaten
  (`AUTO_DETECTED`, `isEnabled=false`), die Person bestaetigt.
- Klassische Signalverarbeitung bleibt der Grundsatz (kein ML, vgl. ADR-0025).

## Konsequenzen

- Schwellen sind Startwerte; belegt nur durch `DropDetectionTest` (Kick/Bass/Hats/Pad/Riser).
  Ein Satz selbst gelabelter echter Tracks (z. B. 20, Treffer +-1 Beat) ist der naechste Beleg.
- Kein Analyzer-Version-Bump: vorhandene Kandidaten bleiben, bis "Drops automatisch erkennen"
  erneut laeuft. Die Beat-Raster-Rastung ist im Detektor getestet, im Analyzer aber nicht
  verdrahtet: die Onset-Stufe berechnet kein Tempo (nur die Mix-Stufe).
- Build-up-Vorlauf der Landung (`DropLandingPlanner.plan(leadInMs)`): implementiert und
  getestet, `DropSyncPlanner.LEAD_IN_MS = 0` (aus), bis ein Hoertest vorliegt.
