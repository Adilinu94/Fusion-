# ADR-0028: Zaehlrobustheit der Rep-Pipeline und Nachzaehlung am Satzende

Datum: 2026-09-28
Status: Akzeptiert (Bericht 2026-09-28; nur auf synthetischen Signalen belegt, Gate 11b steht aus)

## Problem

Drei Fehlerklassen der Live-Zaehlung, jeweils mit einem Test reproduziert:

1. `RepCounter` schloss das Pending-Fenster beim ersten negativen Sample, sobald die Flanke
   am Nulldurchgang flacher als 5 % des Peaks pro Sample war. Bei kontrolliertem Tempo
   (> ca. 2 s je Rep) lehnte `PhaseValidator` dann jede Rep als "Negative Phase zu kurz" ab.
2. Ruhe >= 400 ms (ZUPT) verwarf den offenen Pending-Rep: Halte-Phasen von Pause-Reps und
   Tempo-Training zaehlten als "echte Ruhe".
3. BLE-Luecken >= 250 ms verwarfen die laufende Rep und sperrten das Zaehlen 50 Samples
   (~1 s, Befund 5.4 der Gesamtanalyse). Pakete tragen keine Sequenznummer, Verluste sind
   nur als Zeitluecke sichtbar und nicht nachforderbar.

## Entscheidung

- `RepCounter`: der Vollzyklus gilt erst, wenn die exzentrische Phase mindestens 15 % des
  Peak-Betrags erreicht hat (`MIN_NEGATIVE_DEPTH_FRACTION`).
- ZUPT verwirft einen Pending erst nach `max(400 ms, 0,5 x erwartete Rep-Dauer)` Ruhe
  (`zuptAbortQuietFraction`; 0.0 = altes Verhalten). Die Bias-Bestaetigung bleibt bei 400 ms.
- Luecken bis 500 ms werden linear ueberbrueckt (`gapInterpolateMaxMs`; 0 = altes Verhalten),
  zaehlen aber weiter in `largeGapCount`. Laengere verwerfen wie bisher, die Sperre danach
  betraegt 8 statt 50 Samples.
- `SetRecount`: Nachzaehlung des abgeschlossenen Satzes aus den Rohsamples (Nullphasen-Filter,
  satzrelative Peak-Hoehe, Autokorrelations-Periode). **Nur ein Vorschlag**: sichtbar bei
  HIGH-Confidence und abweichender Anzahl, nie automatisch uebernommen. Erst der Tipp der
  Person ist eine aktive Korrektur (D3, ADR-0014); eine uebernommene Analyse zaehlt im
  Shadow-Harness nicht als unabhaengige Wahrheit (`recountAdopted`).

## Verworfen

- Stilles Ueberschreiben der Live-Zahl durch die Nachzaehlung: ein zweiter Zaehler, der
  ebenfalls irren kann, darf die Zahl nicht ohne die Person aendern.
- Sequenznummern/Nachforderung im BLE-Protokoll: braucht Firmware-Aenderung am M5Stick,
  die Firmware liegt nicht in diesem Repo.

## Konsequenzen

- Alle drei Schwellen (15 %, 0,5 x Dauer, 500 ms) sind Startwerte aus synthetischen
  Signalen, keine Messung an echten Saetzen. Der Korpus fuer Gate 11b (Debug-Recorder, ADR-0023)
  muss sie bestaetigen; `CorpusRegressionGateTest` bleibt der Anker (Fixture-Ruheschwanz
  von 40 auf 120 Samples, weil der ZUPT-Verwurf nun lange Ruhe braucht).
- Die Interpolation kann nie einen Peak ERZEUGEN, nur einen Scheitel in der Luecke
  abflachen: im schlimmsten Fall geht die Rep wie bisher verloren.
