# ADR-0024: Einseitige Qualitaetsbewertung (B1/RC-18)

Datum: 2026-09-21
Status: Akzeptiert (Umsetzung B1, Stufe 1; Nutzerentscheidung 5.13)

## Problem

`QualityScorer` bewertete ROM (25 %) und Tempo (20 %) symmetrisch ueber den
Betragsabstand: `score = 1 - |ratio - 1|` (`QualityScorer.kt`). Der gleitende
Mittelwert in `RepCounter.trackForAdaptation()` liegt bei monotoner
Ermuedung per Konstruktion neben der aktuellen Rep (Prominenz faellt, Dauer
steigt), sodass 45 % des Qualitaetsgewichts ausgerechnet die letzten,
haertesten Wiederholungen bestrafen (Research 1.2, RC-18). Der Effekt war
ein Margen-Kollaps, kein Totalausfall — eine verlorene Rep am Satzende
faellt dem Trainierenden aber am meisten auf.

## Optionen

1. Status quo (symmetrischer Betragsabstand, Toleranz 1.0 beidseitig).
2. Einseitige Scores: Ermuedungsrichtung weit, verdaechtige Richtung eng.
3. Driftmodell als Erwartungsquelle (lineare Extrapolation, Baustein B).

## Entscheidung

**Option 2 als Stufe 1** (Entscheidung 5.13: "einseitige Scores, kein
Driftmodell"). Das Driftmodell bleibt Stufe 2 und braucht den Golden Corpus.

Umgesetzt als `QualityScorer.oneSidedScore(ratio, fatigueIncreasesRatio)`:
- Prominenz: Ermuedung senkt das Verhaeltnis (fatigueIncreasesRatio = false).
- Dauer: Ermuedung hebt es (fatigueIncreasesRatio = true) — die Zuordnung
  ist invertiert und im Code kommentiert, weil eine Verwechslung nicht
  auffaellt.
- Toleranzen aus `ExerciseEngineConfig.fatigueTolerance`/`suspiciousTolerance`
  (sweepbar); Gewichte und Symmetrie unveraendert.

**Abweichung von der Plan-Skizze (wichtig):** Der Plan nennt 0.45/0.20 als
Toleranzen. Woertlich gelesen waere das in BEIDEN Richtungen strenger als
der Ist-Zustand (1.0) gewesen, haette 19 Gates gebrochen (RepPipeline,
Isolation, Refiner, Golden-Corpus, Sweep, Live-vs-Replay) und widerspraeche
dem Planziel "lockert in der Ermuedungsrichtung, strafft nur gegen oben".
Die Werte sind deshalb als DELTAS zur alten 1.0 gelesen: **1.45**
(Ermuedung, +0.45) und **0.80** (verdaechtig, -0.20). Damit gilt:
- 40 % Velocity-Loss (Normalfall, Pareja-Blanco et al. 2017) traegt weiter
  (Score 0.72 statt 0.60),
- ein Schwung-Spike verliert deutlich frueher (bei +80 % ist der Beitrag 0),
- die verdaechtige Seite kann 0 erreichen, die Ermuedungsseite nicht
  (bei ratio 0 bleibt 0.31) — genau die gewuenschte Asymmetrie.

## Folgen

- `RepCounterFatigueDriftTest` (12 Reps, -30 % Prominenz, +25 % Dauer)
  zaehlt vollstaendig; der Referenzlauf mit 1.0/1.0 dokumentiert, dass der
  Satzende-Score unter der einseitigen Bewertung nicht sinkt.
- **Refraktaerzeit-Ausnahme:** `PeakDetector.updateExpectedDurationMs`
  bleibt am gleitenden Mittelwert (`RepCounter.trackForAdaptation`), ebenso
  der Pending-Deckel (`maxExtraPhaseMs`). Die einseitige Politik wirkt NUR
  im `QualityScorer`. Diese Unterscheidung darf ein spaeterer Refactor
  nicht einziehen (sonst koppelt die Ermuedungstoleranz die Doppelzaehlung).
- `ExerciseEngineConfig` traegt `fatigueTolerance`/`suspiciousTolerance`
  (Defaults 1.45/0.80); der Offline-Sweep kann sie variieren
  (`ParameterSet`), das Profil (Schema v6) noch nicht.
- Kein Golden-Corpus-Delta: die synthetischen Gates bleiben bei 0; die
  bindende Corpus-Messung folgt mit den Phase-1-Aufnahmen (Gate 11b).
