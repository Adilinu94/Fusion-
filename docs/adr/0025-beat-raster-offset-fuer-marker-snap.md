# ADR-0025: Beat-Raster-Offset fuer das Marker-Snap (B4/RC-22)

Datum: 2026-09-21
Status: Akzeptiert (Umsetzung B4, Stufe 1; Nutzerentscheidungen 5.11/5.12)

## Problem

`MarkerSnapping.snapToBeat()` rastete auf ein Beat-Raster, das bei 0 ms
beginnt — der Code nannte das selbst Spekulation. Bei 128 BPM ist ein
halber Beat 234 ms; das Snap-Fenster war 250 ms. Der Snap konnte eine
Markerposition also um bis zu einen halben Beat verschieben, mehr als das
gesamte Audio-Latenzbudget der Drop-Landung (25-150 ms je Route). Das
Feature konnte die Praezision damit verschlechtern statt verbessern
(Research Abschnitt 3.4/D2, Umbauplan Phase 3).

## Optionen

1. Status quo: 0-ms-Raster, Fenster 250 ms.
2. Onset-Kreuzkorrelation (Umbauplan Phase 3.2): Phase ueber die Distanz
   aller Onsets zum naechsten Rasterpunkt, 48 Schritte.
3. Low-Band-Phasenheuristik (Research 2026-09-19): tiefe Bandenergie
   phasen-falten, staerkste Phase gewinnt.
4. Neuronales Downbeat-Modell: verworfen — Bruch mit dem Grundsatz
   "bewusst klassische Signalverarbeitung, kein ML" (OnsetDetection.kt),
   und der SMC-Befund (arXiv 2605.12287) zeigt "confident-but-wrong"-
   Aktivierungen selbst bei modernen Trackern.

## Entscheidung

**Option 3**, umgesetzt als `DownbeatAccumulator` in `:domain:audio`:

- Low-Pass 120 Hz (Biquad, Q 0.707), Huellkurve in 10-ms-Fenstern,
  gepuffert bis zum finalen BPM des Ein-Pass-Decodes.
- Phasen-Faltung mit **48 Kandidaten** je Beat-Intervall. **Abweichung
  von der Research-Skizze (4 Phasen):** vier Bins haetten den Snap um bis
  zu einer Viertel-Beatperiode (117 ms bei 128 BPM) verschoben und damit
  das Abnahmekriterium "ein Marker, der ohne Snap richtig lag, wird nicht
  verschlechtert" verletzt. 48 Schritte entsprechen der Onset-Variante
  (Umbauplan 3.2) und passen zur 10-ms-Huellkurve.
- **Semantik:** Das ist die **Beat**-Phase, kein musikalischer
  Taktanfang. Fuer das Snap genuegt sie (Umbauplan 3.2); die Feldnamen
  `downbeatOffsetMs`/`downbeat_confidence` folgen dem Bauplan B4.
- **Signal-Anwesenheit:** Ohne messbaren Low-Band-Anteil (≥ 5 % der
  Gesamtenergie) gibt es keine Aussage — ein Track ohne Bass/Kick hat
  kein tiefes Raster (Stille vor Vermutung).
- **Konfidenz:** relative Ueberlegenheit der besten Phase gegenueber der
  besten Phase ausserhalb ±4 Bins; das Gate
  (`DownbeatConfidence.MIN_SNAP_CONFIDENCE = 0.2`) sitzt an der
  **Leseseite** (Muster `MixConfidence`): Rohwerte bleiben in der DB,
  spaetere Schwellen-Kalibrierung braucht keine Neuanalyse.
- **Persistenz:** `track_analysis.downbeat_offset_ms`/`downbeat_confidence`
  (Migration v12→v13, additiv nullable) + `MIX_ANALYZER_VERSION` 1→2 —
  nur die Metadatenstufe wird neu berechnet, die Waveform bleibt gueltig
  (ADR-0015).
- **Snap-Verhalten:** `snapToBeat(pos, bpm, offset)` rastet **nur mit
  gemessenem Offset**; das Fenster ist auf `min(250 ms, beatMs/2)`
  geklemmt. Das Sheet rastet automatisch beim Oeffnen (5.11), die
  Originalposition bleibt fuer "Zurueck auf Original" erhalten, danach
  ist die Korrektur frei.

## Folgen

- Ohne Analyse (oder bei Altbestand NULL) rastet nichts mehr — bewusst:
  kein Snap ist besser als ein falsches Snap.
- Systematischer Restversatz ≤ halbe Bin-Breite (≈ 10 ms bei 128 BPM)
  plus Filter-/Fensterverzug; die bindende Kalibrierung folgt mit echten
  Titeln (Gate 11b, Hoerprobe).
- **Offene Schwaeche:** Die Low-Band-Energie folgt Kick UND Bass. Spielt
  der Bass betont auf den Off-Beats (House), kann die Phase kippen; das
  Konfidenz-Gate faengt nur unklare Faelle. Kalibrierung/Sweep auf dem
  echten Corpus entscheidet, ob die Schwelle reicht.
- Der Offset ist pro Track gespeichert; Marker teilen das Raster des
  Songs (kein Marker-Feld).
