# Bauplan — Tiefendokumentation der schwierigen Stellen (2026-09-20)

**Zweck:** Umsetzungsreife Detaildokumentation der Pakete, die im
`BAUPLAN_VERBESSERUNGEN_2026-09-20.md` als schwierig gelten (Signalmathematik,
Persistenz-Schema, Nebenlaeufigkeit, Zustandsmaschinen, Datenmodell).
Grundlage: fuenf Code-Tiefenrecherchen auf dem Arbeitsbaum nach P2-21
(P0-P2-21 sind uncommittet).

**Lesehinweis:** Alle Zeilennummern beziehen sich auf den Arbeitsbaum vom
20.09.2026 und koennen sich mit dem naechsten Commit verschieben. Die
Kapitel nennen jeweils Paket (A1, A3, B1-B6, C2, C13, D5) und Befund-ID
(T-/P-/S-/U-/I-Nummern) aus dem Bauplan.

**Entscheidungen vom 20.09.2026 (Runde 2):** Die offenen Produktfragen sind
beantwortet; die betroffenen Kapitel nennen die Entscheidung jeweils zu Beginn
ihrer "Offene Fragen" als "(entschieden: ...)". Uebersicht:
`BAUPLAN_VERBESSERUNGEN_2026-09-20.md` Abschnitt 5, Punkte 5.7-5.15.

**Kapiteluebersicht:**

| Kapitel | Paket | Thema |
|---|---|---|
| 1 | — | Querschnitt: Reihenfolge, Invarianten, Regressionsgates |
| 2 | B1 | Ermuedungsdrift im QualityScorer (RC-18) |
| 3 | B2 | Autokorrelation als Quelle (RC-19) |
| 4 | B6 | Template-Pool: Admission-Margin oder DBA (RC-21) |
| 5 | B3 | Profil-Schema v6 (RC-20) |
| 6 | B4 | Downbeat-Offset + Snap-Fenster (RC-22) |
| 7 | B5 | `stop()`/`finishAndTakeTrace()`: Main-Thread + Race (RC-1-Rest) |
| 8 | C13 | DROPSYNC-Recovery + PLAN_LOST (P3-28/MP-10) |
| 9 | C2 | Skip-Schutz/Override im Player (MP-7-Rest) |
| 10 | A3 | Sensor-Health-Erholung (S-2) |
| 11 | A1 | Satz-Undo + Haptik + PR-Kette (T-1/T-2) |
| 12 | D5 | N+1 und Indizes (P-2/S-8) |
| 13 | D1/A4/C9/C14/T13 | Weitere heikle Stellen (kurz) |

---

## 1. Querschnitt: Reihenfolge, Invarianten, Regressionsgates

**Gate-Reihenfolge in `RepCounter.decide()` ist Vertrag:**
Accel-Voting (`RepCounter.kt:185-192`) -> Template-Match auf `peak.window`
(`:196-204`) -> PhaseValidator auf dem erweiterten Fenster (`:206-214`) ->
Quality (`:216-231`) -> Count/Pool/Adaption (`:233-235`). Jeder Umbau, der
Quality (B1) oder Pool-Admission (B6) verschiebt, aendert alle nachgelagerten
Stufen.

**Empfohlene Reihenfolge der Umsetzung:**

1. **B5 zuerst** (Threading/Race) — B1/B2 rechnen sonst weiter auf Main und
   die neuen Tests sind nicht deterministisch.
2. **B3** (Schema v6) vor **B1/B2** — die Schwellen-/Politikfelder muessen
   replay-faehig sein, bevor Politik umgestellt wird.
3. **B1** vor **B2** — das Driftmodell definiert, welche Erwartung die
   Periode spaeter speist (Konflikt: `UMBAUPLAN` 2.3 vs. RC-19).
4. **B6** nach B1 — Admission-Margin braucht die neue Score-Semantik.
5. **A3** unabhaengig (klein, sofort); **A1** braucht eine Produktentscheidung
   (PR-Quelle); **C13/C2** teilen sich die Koordinator-Aenderungen und sollten
   zusammen geplant werden; **D5** parallel moeglich.

**Gemeinsame Fixtures:** `SyntheticCorpus.repCycle()` 0->60->-60->0 ueber 60
Samples (`SyntheticCorpus.kt:22-25`); `SyntheticCorpus.writeMiniCorpus`
(`:100-134`); `CorpusSweepHarness.replay` (`:484-516`) als gemeinsamer
Offline-Lauf; `ActiveSetControllerTest.controller()` mit
`UnconfinedTestDispatcher` (`ActiveSetControllerTest.kt:54-65`).

**Gemeinsame Regressionsgates (muessen Delta 0 halten oder bewusst
dokumentiert abweichen):**
`CorpusRegressionGateTest` Goldkorpus `[2,3,2]`, Deltas `[0,0,0]`,
`replayZuptAborted` Satz 2 = 1 (`:73-119`); `CorpusSweepHarnessTest.baseline`
`[2,3]` (`:62-74`); `CorpusLiveComparisonTest` Live-vs-Replay ohne Abweichung
(`:38-54`) inkl. Gegenbeweis (`:57-87`); `CorpusReplayDeterminismTest`
bitgleiche Events (`:57-109,112-175`).

**Dokumentationspflicht:** RC-18 braucht ADR-0020 (`UMBAUPLAN` :474, :664);
RC-19/RC-21 brauchen eine Entscheidung zur Quellenhierarchie
`Profil vs. gleitender Mittelwert vs. Periode vs. Driftmodell`.

---

## 2. B1 — Ermuedungsdrift im QualityScorer (RC-18)

**Warum schwierig:** Der gleitende Mittelwert ist gleichzeitig Gate
(Qualitaet), Refraktaerzeit und Pending-Deckel; eine Aenderung kann
Zaehlverhalten, Pool und Lernpfad gleichzeitig verschieben. Es gibt heute
keinen Drift im Korpus, also ist der Fix ohne neue Messfixture nicht belegbar.

### Ist-Zustand (verifiziert)

- **Adaption:** `RepCounter.trackForAdaptation()` (`RepCounter.kt:274-294`)
  haengt die aktuelle Rep an `recentDurationsMs`/`recentProminences`, kappt
  beide gemeinsam auf 10 (`:280-283`) und verteilt ab `size >= 3` (`:284`)
  die Mittelwerte: `qualityScorer.updateExpectations(...)` (`:285-290`) und
  `peakDetector.updateExpectedDurationMs(avgDurationMs)` (`:292`;
  `PeakDetector.kt:88-95` setzt daraus `refractoryMillis = duration * 0.3`
  in 100..2000 ms). Nur **akzeptierte** Reps zaehlen (`:233-235`).
- **Score:** `QualityScorer` (`QualityScorer.kt:20-73`): Gewichte 0.40
  Correlation / 0.25 ROM / 0.20 Tempo / 0.15 Symmetrie (`:23-27`), Formeln
  `romScore = 1 - |prominence/expected - 1|` (`:42-43`),
  `tempoScore = 1 - |duration/expected - 1|` (`:45-47`), `total >= 0.55`
  (`:51-58`). `updateExpectations` ersetzt beide Erwartungen (`:66-72`).
- **Kopplungen:** `expectedDurationMs` speist zusaetzlich die initiale
  Refraktaerzeit (`ExerciseEnginePipeline.kt:136-137`) und den Pending-Deckel
  `maxExtraPhaseMs = expectedDurationMs * 2` (`RepCounter.kt:166`).
- **Zweiter Drift-Gate im Detektor:** `minProminence = spk * 0.2`
  (`PeakDetector.kt:163-164`), `spk` per EMA aus bestaetigten Peaks
  (`:165-166`) — faellt die Amplitude stark, wird die Rep schon vor dem
  Scorer verworfen.
- **Korpus:** `SyntheticCorpus.repCycle()` konstante Amplitude 60 ueber 60
  Samples (`:22-25`); `setWindowLine` schreibt fix `prominence 1.0`,
  `durationMs 2000.0` (`:55-63`). `min_quality_margin` wird berechnet
  (`CorpusSweepHarness.kt:433-436`) und in die CSV geschrieben (`:95-108`),
  aber nirgends assertiert; pro Rep gibt es nur das Minimum, keine
  Erst-/Letzt-Rep-Marge (`:519-521`).
- **Accel-Falle:** `accelStream()` setzt Accel-Spitzen nur bei `gx > 5.0`
  (`SyntheticCorpus.kt:49-52`) — eine Driftkurve unter 5 deg/s verliert die
  Accel-Marks und konfundiert Drift mit Accel-Voting.

### Fallstricke

1. Der Mittelwert liegt bei monotonem Drift per Konstruktion **ueber** der
   aktuellen Rep; 45 % Gewicht bestrafen das Satzende (Research :326-339).
   Effekt ist Margen-Kollaps, kein Totalausfall.
2. Einseitige Scores invertieren die Richtungen unterschiedlich: kleinere
   Prominenz/laengere Dauer = Ermuedung (weite Toleranz), groesser/kuerzer =
   verdaechtig (`UMBAUPLAN` :541-581). Verwechslung faellt nicht auf.
3. Refraktaerzeit und Pending-Deckel **duerfen nicht mitwandern** — sie
   bleiben am Mittelwert (`UMBAUPLAN` :633-642); heute vermischt
   `trackForAdaptation` beides in einer Funktion.
4. Reps unter `currentThreshold` oder `spk*0.2` erreichen `decide()` nie —
   der Score-Umbau allein zaehlt sie nicht; der Driftkorpus muss oberhalb
   theta bleiben.
5. Ohne Politik in `ParameterSet.describe()` sind Sweep-Zeilen nicht
   unterscheidbar; `ExerciseEngineConfig` hat heute keine Politikfelder.

### Aenderungspunkte

1. **Einseitige Scores:** `QualityScorer` um `toleranceBelow = 0.45`,
   `toleranceAbove = 0.20` und `oneSidedScore(ratio, fatigueIncreasesRatio)`
   erweitern; Einsatz bei `romScore`/`tempoScore` (`QualityScorer.kt:43,47`),
   Richtung Prominenz invertiert zur Dauer; Gewichte/Symmetrie unveraendert.
2. **Driftmodell (Baustein B), neue Datei `DriftEstimator.kt`:**
   `expectedFor(repIndex)` + `observe(repIndex, value)`, Startwert aus dem
   Profil, Steigung ab Rep 3, `maxRelativeSlopePerRep = 0.08`, Klemme.
3. **Entkoppeln:** `trackForAdaptation` politisiert nur `:287-290`
   (Qualitaets-Erwartung); `:292` (`updateExpectedDurationMs`) bleibt
   Mittelwert; `maxExtraPhaseMs` bleibt.
4. **Config:** `ExerciseEngineConfig` um
   `expectationPolicy: ExpectationPolicy = MOVING_AVERAGE`,
   `toleranceBelow`, `toleranceAbove` erweitern; Durchreichen an
   `QualityScorer` (`ExerciseEnginePipeline.kt:116-121`).
5. **Optional Profil v6:** `prominenceSlopePerRep`/`durationSlopePerRep`
   (siehe Kapitel 5).
6. **Korpus:** `SyntheticCorpus.driftStream(reps, ampStart, ampEnd,
   durStart, durEnd)` + `repCycle(amplitude)`; `setWindowLine` mit
   `prominence`/`durationMs` als Parameter.
7. **Sweep:** `ParameterSet` um Politik/Toleranzen erweitern;
   CSV-Spalten `first_quality_margin`, `last_quality_margin`,
   `quality_margin_slope`; `ReplayOutcome`/`LiveVsReplayRow` um Min-Margen.

### Testplan

- `QualityScorerAsymmetryTest`: `-30 prozent prominence gibt hoeheren score
  als +30 prozent`; `beide extreme ausserhalb der toleranz geben 0`.
- `DriftEstimatorTest`: `monotone reihe wird extrapoliert`;
  `steigungsdeckel greift`; `unter 3 werten ist die steigung 0`.
- `RepCounterFatigueDriftTest`: 12 Reps, Prominenz 60 -> 42, Dauer
  1200 -> 1500 ms; Erwartung alle 12 akzeptiert; Referenzlauf mit
  `MOVING_AVERAGE` dokumentiert das Delta.
- Sweep: `min_quality_margin am satzende bleibt positiv`;
  `live-vs-replay meldet keine negativen margen im driftsatz`.
- Gruen bleiben muessen: `RepPipelineTest` (Score 1.0/Reject;
  `trackForAdaptation updates refractory`), `CorpusRegressionGateTest`,
  `CorpusSweepHarnessTest`, `CorpusLiveComparisonTest`,
  `CorpusReplayDeterminismTest`.

### Offene Fragen

**(entschieden 5.13: Stufe 1 sind einseitige Scores, kein Driftmodell; damit
entfallen die Fragen 1-3 zunaechst.)**

1. Startwert des Driftmodells: Profilwert oder Default 50/1000?
2. Slopes persistieren (v6) oder nur satzintern schaetzen?
3. Zaehlen abgelehnte Reps als Drift-Evidenz? (Heute nein.)
4. Wie wird die Pool-Wirkung isoliert (fixierter `templateThreshold` im
   Vor/Nach-Sweep)?
5. ADR-0020 muss die Refraktaerzeit-Ausnahme festschreiben.
6. Fuer "Satzende bleibt akzeptiert" braucht der Report Erst-/Letzt-Rep-Margen.

---

## 3. B2 — Autokorrelation als Quelle (RC-19)

**Warum schwierig:** Die Periode ist heute nur Zweitmeinung mit fein
kalibriertem Veto/UI-Hinweis; eine Speisung in Erwartungen kollidiert mit der
Mittelwert-Regel aus B1, und die Zeitbasis (Ring ohne Timestamps) ist fragil.

### Ist-Zustand (verifiziert)

- **Ring:** `signalRing: DoubleArray(3000)` (`ExerciseEnginePipeline.kt:199-201`),
  gefuellt nur mit `smoothedGp` **gesettelter** Frames (`:255-260,300-304`);
  keine Timestamps, keine Gap-Marker; `onLargeGap()` leert den Ring nicht
  (`:393-397`); `SIGNAL_RING_SIZE = 3_000` = ca. 60 s bei 50 Hz (`:481`).
- **Pruefung:** `RepCountPlausibility.check()` (`RepCountPlausibility.kt:72-116`):
  Lags 0.6-8.0 s (`:81-82,158-161`), Brute-Force-Dot (`:91-97`), Schwelle
  `MIN_PERIODICITY = 0.25` (`:98-100,167`), `periodSeconds = bestLag / rate`
  (`:102`), `durationSeconds = signal.size / rate` (`:103`),
  `estimated = round(duration / period)` (`:106`) — **Kommentar sagt
  "aufrunden", Code rundet kaufmaennisch** (`:104-105`).
- **Verbraucher:** `ActiveSetController.stop()` -> `_lastPlausibility`
  (`ActiveSetController.kt:254`), `SetTrace`/`SetDiagnostics`, Lern-Veto
  `plausibilityAllowsLearning` (`TrainViewModel.kt:519-534`, blockiert nur
  bei Abweichung > 2), UI-Hinweis nur bei `SUSPICIOUS` (`PlausibilityHint.kt:42-46`).
  **Zaehlung/Logging bleiben unberuehrt.**
- **Rate:** `appliedSampleRateHz` aus Median der Deltas
  (`SampleRateEstimator.kt:22-112`), Update nur bei Abweichung >= 1.5 Hz
  (`ExerciseEnginePipeline.kt:289-297,475`).

### Fallstricke

1. `periodSeconds` skaliert direkt mit der Rate; Lag-Quantisierung 1/rate
   (20 ms bei 50 Hz, 1-3,3 % Fehler).
2. Gaps verfaelschen Dauer und Periodizitaet (Ring ohne Marker).
3. `ceil` aendert `estimatedReps` und damit die SUSPICIOUS-Haeufigkeit ->
   Lern-Veto und UI-Hinweis koennen kippen (`MAX_PLAUSIBILITY_DEVIATION = 2`).
4. Drei konkurrierende Quellen fuer `expectedDurationMs` (Profil,
   Mittelwert, Periode) — echte ADR-Entscheidung.
5. Rollierender Aufruf ist O(N x Lags) (~1,2 Mio. Multiplikationen) und darf
   nicht auf Main (siehe B5).

### Aenderungspunkte

1. `ceil` statt `round` (`RepCountPlausibility.kt:104-106`) + Kommentar.
2. Optional parabolische Lag-Interpolation fuer Genauigkeit.
3. **Variante A (Set-Ende -> naechster Satz):** Periode in
   `expectedDurationMs` des naechsten Satzes speisen (via Profil/Refiner
   oder `updateThreshold(theta, expectedDurationMs)` — Signatur existiert
   `ExerciseEnginePipeline.kt:435-438`).
4. **Variante B (rollierend):** neue Methode
   `updateExpectedDurationFromPeriod()` im Worker; nicht auf Main.
5. Semantik `Result`/`Verdict` unveraendert lassen, Periode separat liefern.

### Testplan

- `RepCountPlausibilityTest`: `periode 7_4 sekunden wird aufgerundet`;
  `gap im signal liefert keine belastbare periode`.
- `ActiveSetControllerTest`: `naechster satz nutzt die gemessene periode`.
- Gruen bleiben: alle acht `RCPT`-Faelle, `TrainViewModelPlausibilityTest`
  (BORDERLINE/CONSISTENT/INCONCLUSIVE), `TrainViewModelLearningEventTest`
  (`SkippedImplausible`), AST-Zweitmeinungs-Tests.

### Offene Fragen

**(entschieden 5.14: nur die Qualitaetsbewertung; Refraktaerzeit und
Pending-Deckel bleiben am gleitenden Mittelwert.)**

1. Verbraucher: nur Quality, nur Refraktaerzeit oder auch Pending-Deckel?
   (`UMBAUPLAN` 2.3 vs. B2 widersprechen sich.)
2. Wann speisen: Set-Ende oder rollierend?
3. `ceil`-Wirkung auf das Lern-Veto vorher messen.
4. Gap-Markierung im Ring oder Periode nur in gap-freien Segmenten?
5. B5-Abhaengigkeit: nicht auf Main nachrechnen.

---

## 4. B6 — Template-Pool: Admission-Margin oder DBA (RC-21)

**Warum schwierig:** Der Pool ist ein Oder-Gate mit FIFO-Eviction; jede
Admission-Aenderung verschiebt die Golden-Corpus-Counts. Echtes DBA braucht
einen DTW-Pfad, den die heutige Implementierung wegoptimiert hat.

### Ist-Zustand (verifiziert)

- **Fuellung:** `RepCounter.decide()` -> `templateMatcher.addToPool(peak.window)`
  (`RepCounter.kt:234`) fuer jede akzeptierte Rep (auch Score 0.56);
  `setTemplate(profile.repTemplate)` liegt im **selben** Pool und wird per
  FIFO eviktiert (`TemplateMatcher.kt:60,69,79-82`).
- **Auswahl:** `match()` nimmt `templates.maxOf { dtwSimilarity(...) }`
  (`TemplateMatcher.kt:96-100`), `accepted = best >= threshold` (`:101`,
  Default 0.7 `:50`); `DTW_BAND = 8` (`:119-125`), `dtwSimilarity` ohne
  Warping-Pfad (zwei Zeilen DP, `:184-225`).
- **Bausteine:** `resample` (`:127-146`), `normalize` (`:149-157`),
  `crossCorrelate` (`:159-168`), `TemplateExtractor` (Median, `:13-22`,
  laut S-11 toter Code).
- **Kontrakte:** `RepPipelineTest.confirmed rep updates template pool`
  erwartet `poolCount` 1 -> 3 (`:124-155`); `TemplateMatcherTest` FIFO 5
  (`:95-106`).

### Fallstricke

1. Grenzwertige Reps erweitern die Akzeptanzflaeche dauerhaft.
2. Kalibrier-Template wird mit eviktiert.
3. `maxOf`-Schwelle 0.7 ist auf den Spitzenwert geeicht — ein Baryzentrum
   senkt den Spitzenwert; Schwelle muss im Sweep nachgezogen werden.
4. Replay ruft kein `setTemplate` (`CorpusSweepHarness.kt:484-516`); der Pool
   startet leer, `noTemplate` akzeptiert (`TemplateMatcher.kt:91`).
5. Schwellen sind nicht im Profil/JSONL (S-4) -> DBA-Ergebnis nicht
   replay-reproduzierbar.

### Aenderungspunkte

- **Variante 1 (empfohlen, klein):** Admission-Margin —
  `addToPool(rawWindow, qualityScore)` + `admissionMinScore` (z. B.
  `minQualityScore + Margin`); Durchreichen ueber Config und `ParameterSet`.
- **Variante 2 (DBA):** `dtwPath(...)` + `dba(windows, iterations, bandWidth)`;
  `match` gegen Baryzentrum (ggf. Uebergangsloesung
  `maxOf(barycentre, templates)`), deterministisch (keine Set-/Map-Iteration).
- **Variante 3:** Per-Index-Median via `TemplateExtractor` reaktivieren.

### Testplan

- `TemplateMatcherTest`: `dba template mittelt zwei verschobene fenster`;
  `dba ist robust gegen eine ausreisser-rep`;
  `pool admission rejects borderline rep`;
  `dtwPath respektiert das sakoe-chiba-band`.
- `RepPipelineTest`: `eine schlechte Rep kippt den Pool nicht`.
- Gruen bleiben: `TMT` FIFO/Short/Constant, `RPT` Peak-Window-Kontrakt,
  `CRGT`/`CSHT`/`CLCT`-Baselines, `CRDT` Determinismus.

### Offene Fragen

1. Erst Admission-Margin oder direkt DBA? (Auslaesekriterium: DTW-Streuung.)
2. Kalibrier-Template separat halten?
3. Schwellen-Nachzug im Sweep; S-4 blockiert Replay-Reproduzierbarkeit.
4. `poolCount`/`hasTemplate`-Semantik bei Baryzentrum.
5. Berechnungszeitpunkt (pro `addToPool` oder lazy im `match`)?
6. `TemplateExtractor` reaktivieren oder DBA neu bauen?

---

## 5. B3 — Profil-Schema v6 (RC-20)

**Warum schwierig:** Der Codec ist positionsbasiert und leitet die Version
aus der Feldzahl ab; ein falscher Default beim Schema-Bump veraendert still
das Zaehlverhalten aller Altprofile. Kalibrierung und Replay muessen die
neuen Felder durchgaengig tragen.

### Ist-Zustand (verifiziert)

- **Modell:** `CalibrationProfile` (`SensorModels.kt:78-118`),
  `PROFILE_SCHEMA_VERSION = 5` (`:133`); `templateThreshold`/`minQualityScore`/
  `dtwBand` fehlen.
- **Codec:** `encode()` 16 Felder (`DataStoreCalibrationProfileRepository.kt:283-325`);
  `decode()` akzeptiert 16 (v5) oder 15 (v4), leitet das Schema aus
  `parts.size` ab (`:334-346`), `accelThreshold` fehlt bei v4 -> 0.0
  (`:370-375`); Rueckbau setzt `schemaVersion = PROFILE_SCHEMA_VERSION`
  hart (`:388`). Re-Encode-Pfade: `save()` (`:104-111`),
  `noteValidatedSet()` (`:156-181`), `rollback()` (`:203-206`).
- **Verbrauch:** `ActiveSetController.start()` uebernimmt nur
  `rotationAxis/gyroBias/expectedProminence/expectedDurationMs/
  detectionThreshold/accel*` (`ActiveSetController.kt:123-143`) — die drei
  Schwellen laufen live immer als Code-Defaults (0.7/0.55/8).
  `CalibrationRefiner.revalidates()` (`CalibrationRefiner.kt:126-140`)
  spiegelt das. `CalibrationViewModel.confirmAndSave()` baut das Profil
  (`:287-306`).
- **JSONL/Replay:** `JsonlShadowSessionRecorder.profileJson()` schreibt nur
  `axis/bias/theta/prominence/durationMs/accelTheta/revision` (`:118-128`);
  `CorpusWindow`/`finishWindow` (`CorpusFiles.kt:70-85,218-234`);
  `CorpusSweepHarness.liveConfig()` nutzt `DEFAULT_*` (`:289-303,530-535`);
  `configFor()` (`:458-477`).
- **Tests:** v3-Legacy (`DataStoreCalibrationProfileRepositoryTest.kt:254-280`),
  Round-Trips; **kein v4-Lesetest** (S-10).

### Fallstricke

1. Neue Felder **hinten** anhaengen (Position 16-18), sonst bricht v5-Lesbarkeit.
2. Feldzahl-Weiche auf 19->6, 16->5, (15->4) erweitern; sonst wird ein
   v6-Blob als `null` verworfen ("recalibrate").
3. Defaults muessen exakt 0.7/0.55/8 sein, sonst stille Regression bei
   Altprofilen.
4. Nach dem ersten `save()` existieren fuer die Kombination keine
   16-Feld-Blobs mehr (bewusst).
5. Kalibrierung liefert die Werte heute nicht — v6 ist sonst nur ein
   Transportfeld mit Konstanten.
6. Validierung: `0..1` fuer Schwellen, `dtwBand 1..64`; `ExerciseEngineConfig`
   hat fuer beide heute kein `require`.

### Aenderungspunkte

1. `CalibrationProfile` um `templateThreshold = 0.7`, `minQualityScore = 0.55`,
   `dtwBand = 8` erweitern; `PROFILE_SCHEMA_VERSION = 6`.
2. `encode/decode` + `V6_FIELD_COUNT = 19`; v5/v4-Defaults; Validierung.
3. `ActiveSetController.start()` und `CalibrationRefiner.revalidates()` um
   die drei Zeilen ergaenzen.
4. `CalibrationViewModel.confirmAndSave()` setzt die Felder (Quelle:
   Defaults oder Kalibrier-Ergebnis, s. offene Fragen).
5. `JsonlShadowSessionRecorder.profileJson()` + `CorpusWindow`/
   `finishWindow`/`liveConfig`/`configFor` erweitern.
6. Optional `knownCountSweep`/`finalize` liefern die Werte.

### Testplan

- `v5-Blob wird gelesen und mit Default-Schwellen auf v6 gehoben`;
  `v4-Blob wird weiterhin gelesen` (schliesst S-10);
  `v6 round-trip erhaelt templateThreshold minQualityScore dtwBand`;
  `unbekanntes schema wird verworfen`; `v6-Blob mit ungueltigem dtwBand
  wird verworfen`.
- `CorpusSweepHarnessTest`: `liveConfig liest die v6-Schwellen aus dem
  Fenster`; `baseline-ParameterSet repliziert das Fenster`.
- `JsonlShadowSessionRecorderTest`: `set_window traegt die v6-Schwellen`;
  `set_window aus Altaufnahme bleibt lesbar`.
- `CalibrationViewModelTest`: `confirmAndSave persistiert die v6-Schwellen`.

### Offene Fragen

1. Schwellen im KNOWN_SET-Sweep lernen oder nur transportieren?
2. v4 weiter lesen oder auf v6+v5 verengen? (Bestandsinstallationen!)
3. `dtwBand` als Int (heute `ExerciseEngineConfig.dtwBand: Int`).
4. Soll `CalibrationRefiner` die drei Felder je Set nachfuehren?
5. Sweep-Report-Spalten fuer die neuen Werte?

---

## 6. B4 — Downbeat-Offset + Snap-Fenster (RC-22)

**Warum schwierig:** Es gibt heute kein Beat-Grid und keinen Downbeat in der
Analyse; das Snap-Feature ist toter Code, sein Fenster ist zu gross, und die
Phasenheuristik braucht BPM, das erst am Ende des Ein-Pass-Decodes feststeht.

### Ist-Zustand (verifiziert)

- `MarkerSnapping.snapToBeat(positionMs, bpm)` (`MarkerSnapping.kt:22-32`):
  `beatMs = 60_000/bpm`, `nearest = round(pos/beatMs)*beatMs`,
  `SNAP_WINDOW_MS = 250` (`:14`), Guard `bpm in 30..300` (`:26`).
  **Kein Produktionsaufrufer** (nur `MarkerSnappingTest`).
- **Analyse:** `TrackAnalysis` (`TrackAnalyzer.kt:104-127`) hat
  `bpm`/`bpmConfidence`/`onsetCandidatesMs`, aber **kein Beat-Grid/Offset**;
  `observeAnalysis` liefert `onsetCandidatesMs = emptyList()` immer
  (`TrackAnalysisRepositoryImpl.kt:96`); Onsets liegen als
  AUTO_DETECTED-Marker in der DB (`:342-378`).
- **Research:** `docs/research/2026-09-19-downbeat-offset.md:35-48` schlaegt
  die Low-Band-Phasenheuristik vor (Bandenergie 40-120 Hz je Beat-Phase
  summieren, hoechste mittlere Energie = Downbeat, Konfidenz = Verhaeltnis
  beste/zweitbeste, unter Schwelle kein Snap).
- **UI-Ort:** `MarkerSheet`-Feinjustierung (`MarkerSheet.kt:106-131`),
  `onAdjust` rein additiv (`NowPlayingScreen.kt:579-583`),
  `MarkerEditState.adjustedBy` (`MarkerEditState.kt:16-23`); `trackBpm`
  liegt bereits vor (`PlayerViewModel.kt:260-270`).

### Fallstricke

1. Ab 120 BPM ist ein halber Beat <= 250 ms -> Snap wird erzwungen statt
   optional; Fenster auf `min(SNAP_WINDOW_MS, beatMs/2)` klemmen.
2. Offset-Formel: `nearest = round((pos - offset)/beatMs)*beatMs + offset`,
   Ergebnis auf 0 klemmen.
3. BPM steht erst am Ende des Decodes fest — Bandenergie puffern
   (~12.000 Doubles/5 min) und in `finalize` auswerten.
4. `MarkerSnapping` hat keinen Analyse-Kontext; Offset muss als Parameter
   fliessen (PlayerViewModel -> Screen -> Sheet).
5. Persistenz: additive DB-Spalte (Migration 11->12, Schema-Export,
   Migrationstest) vs. In-Memory-Cache (nicht reproduzierbar).
6. Konfidenzregel: unter Schwelle kein Snap; `TrackAnalysis` hat kein
   Guetemass-Feld.
7. 4/4-Annahme; Nicht-4/4/Half-Time nur ueber Konfidenz/Gate absichern.

### Aenderungspunkte

1. `snapToBeat(positionMs, bpm, downbeatOffsetMs = 0L)` + Fenster-Klemme;
   optional Konfidenz-Parameter.
2. `TrackAnalysis`/Entity/DAO/Persister/`observeAnalysis` um
   `downbeatOffsetMs` (+ `downbeatConfidence`) erweitern; Low-Band-Akkumulator
   in `TrackAnalyzerImpl` (Feed + `finalize` mit finalem BPM).
3. Migration `MIGRATION_11_12` + `DROPSYNC_MIGRATIONS` + DB-Version 12.
4. `PlayerViewModel`: `trackDownbeatOffsetMs` analog `trackBpm`.
5. `MarkerSheet`: optionaler Button "Auf Beat einrasten"; neue Methode
   `MarkerEditState.snappedTo(targetMs)` (Original bleibt fuer Revert).

### Testplan

- `MarkerSnappingTest`: `Downbeat-Offset verschiebt das Raster`;
  `Fenster ist hoechstens ein halber Beat`; `negativer Offset wird auf 0
  geklemmt`; bestehende Faelle bleiben.
- Neuer `DownbeatOffsetTest`: `vier-vier-klick mit bassdrum auf schlag eins
  liefert phase eins`; `ohne bass keine aussage`; `gegenprobe offset null
  ist schlechter`.
- `PlayerViewModelTest`: `trackDownbeatOffset kommt aus der Analyse`.

### Offene Fragen

**(entschieden 5.11/5.12: Snap automatisch beim Oeffnen des Marker-Sheets (nur
bei erkannter Beat-Position mit Konfidenz); Downbeat dauerhaft in der DB
(Migration v11->v12).)**

1. DB-Spalte oder In-Memory?
2. Im `MIX_METADATA`- oder Waveform-Lauf berechnen?
3. Konfidenz-Schwelle (Doc nennt keine Zahl).
4. Snap als Button oder automatisch?
5. Half-Time/Oktavfehler behandeln?
6. Adis Hoerprobe fuer echte Tracks (sonst nur Offline-Korpus).

---

## 7. B5 — `stop()`/`finishAndTakeTrace()`: Main-Thread + Race (RC-1-Rest)

**Warum schwierig:** `cancel()` joint nicht; Main liest Puffer und Engine,
waehrend der Worker weiterschreibt. `onCleared` kann nicht auf
`viewModelScope` joinen (bereits gecancelt).

### Ist-Zustand (verifiziert)

- Threading: `scope` (Produktiv `viewModelScope` = Main.immediate),
  `workerDispatcher = Dispatchers.Default` (`ActiveSetController.kt:34-49`);
  nur `sampleJob` laeuft off-main (`:167-170`); `eventJob`, `healthJob`,
  `connectionJob`, `countdownJob` auf Main (`:164,171-198`).
- Geteilte Felder: `bufferedSamples`/`bufferedRepEvents`
  (`:85-86`, geschrieben `:205`/`:164`, gelesen `:339-340`), `engine`-Zustand
  (`signalRing` `ExerciseEnginePipeline.kt:199-201`, `repCounter`).
- `stop()` (`:252-262`): `cancelJobs()` (ohne join, `:358-369`), dann
  `checkPlausibility()` (`:254`) + `diagnosticsSnapshot()` (`:257`) +
  `_phase = IDLE` **danach** (`:259`) -> Worker darf waehrend der Lesephase
  weiterrechnen.
- `finishAndTakeTrace()` (`:318-351`): `checkPlausibility` (`:328`),
  `bufferedSamples.toList()` (`:339`), `abort(CLEARED)` (`:349`) — `add`
  nach `clear` moeglich.
- Aufrufer: `TrainViewModel.stopCountedSet()` (`:859-875`, Main-UI),
  `logSet()` (`:338-347`), `abortActiveSet` (`:882-887`),
  `onCleared()` (`:932-934`).
- Tests nutzen `UnconfinedTestDispatcher` (`ActiveSetControllerTest.kt:54-65`)
  und maskieren Races; Dispatcher-Assert existiert nur fuer den
  Sample-Collector (`:452-499`).

### Fallstricke

1. `cancel()` ist kooperativ; kein happens-before.
2. `ArrayList` ohne Synchronisation -> Teilkopie/Cme/verlorene Samples.
3. Phasen-Fenster: `_phase` erst nach den Leseoperationen -> `onSample`
   arbeitet weiter.
4. `engine = null` + `clear()` in `abort()` vs. lokale Worker-Referenz.
5. `checkPlausibility` O(N x Lags) auf Main.
6. Doppelte `stop`/`abort`-Aufrufe (Disconnect vs. UI) ohne Mutex.
7. `eventJob`-Cancel kann die letzten Rep-Events verwerfen.
8. `onCleared`: `viewModelScope` ist bereits gecancelt — suspend/join dort
   wirkungslos; eigener Scope noetig oder `abort` bewusst synchron lassen.
9. Test-Dispatcher maskiert Races; Race-Test braucht echten Executor.

### Aenderungspunkte (Variante B5-A empfohlen)

1. `cancelJobsAndJoin()` (Reihenfolge: sampleJob zuerst), `stop()` suspend:
   `_phase = IDLE` **vor** den Leseoperationen, Plausi via
   `withContext(workerDispatcher)`, dann Snapshot/Count.
2. `finishAndTakeTrace()` suspend: Join **vor** `toList()`.
3. `abort()` synchron lassen; fuer `onCleared` dokumentieren (oder eigener
   `CoroutineScope(SupervisorJob()+workerDispatcher)` fuer ein
   Hintergrund-Join; **kein** `runBlocking` auf Main).
4. `TrainViewModel.stopCountedSet()` behaelt die UI-Fassade und laeuft
   intern in `viewModelScope.launch`; Report-Emission wandert in die Coroutine.

### Testplan

- `stop joint den sample-collector bevor die plausibilitaet liest`;
  `finishAndTakeTrace friert exakt die samples vor dem stop ein`;
  `stop rechnet die plausibilitaet auf dem worker dispatcher` (Thread-Name);
  `abort und stop gleichzeitig lassen den zaehlerstand konsistent`;
  `finishAndTakeTrace ohne stop liefert dieselbe sample-menge`.
- `TrainViewModelSetReportTest`: `stopCountedSet wartet auf das ende des
  workers bevor der report kommt`.
- Bestehende AST/TVM/TVMPT-Tests bleiben gruen.

### Offene Fragen

1. `abort()` ebenfalls suspend/joinend (onCleared!)?
2. `eventJob` leerlaufen lassen (letzte Events in den Trace)?
3. `_phase = IDLE` vor oder nach Join? (Empfehlung: davor + Join.)
4. `stop(): Int` als Rueckgabe behalten?
5. `Mutex` gegen parallele `stop`/`abort`?
6. UI-Fassade synchron mit internem launch (Report dann asynchron)?

---

## 8. C13 — DROPSYNC-Recovery + PLAN_LOST (P3-28/MP-10)

**Warum schwierig:** DROPSYNC wird bewusst nicht persistiert; "frisch" vs.
"rekonstruiert" ist aus dem Snapshot nicht unterscheidbar, und der heutige
Recovery-Pfad setzt die Rest-Queue per `setQueue(0)` still neu auf.

### Ist-Zustand (verifiziert)

- **Persistenz:** `TimerService.persistSnapshot` schreibt bei
  PREPARING/RUNNING/PAUSED (`TimerService.kt:190-210,387-388`);
  `TimerEngine.snapshot()` liefert fuer DROPSYNC **null** (`:396-423`, KDoc
  `:387-399`); `DataStoreTimerSnapshotStore.serialize` schreibt
  `markerPositionMs` **nicht** und setzt es beim Lesen auf null (`:36-59,88`).
- **Recovery:** `DropSyncApplication.onCreate` startet Koordinator (`:45`)
  vor `runTimerRecovery()` (`:48`); `TimerRecoveryStarter.start()` mit
  `RebootGuard` (`:39-52`); `DefaultRestTimerRecovery.recover` verbraucht den
  Snapshot (`:15-22`); `TimerEngine.restore` lehnt DROPSYNC ab (`:441-480`).
- **Koordinator:** `start()` nur Subscriptions (`DropSyncCoordinator.kt:134-151`);
  `beginSession` (`:255-281`) -> `ensureRestQueue` (`:288-305`) mit
  `setQueue(restSongs, startIndex = 0, playWhenReady = true)` (`:299`);
  `pendingDropAuto` ist In-Memory (`:107`); `restQueueSongIds` In-Memory
  (`:117-118`); `generation` wird nur in `ensureRestQueue` erhoeht (`:298`);
  kein `close()`/Cancel (`:75-76`).
- **Fehlermodus:** `DropSyncFailureReason` hat kein `PLAN_LOST`
  (`DropSyncState.kt:52-64`); `Failed` ist in Statuszeile und Badge stumm
  (`DropStatusLine.kt:66-68`, `PlayerViewModel.kt:333-339`); manueller
  DropRest ist nach Kill spurlos weg.

### Fallstricke

1. `setQueue(0)` reisst aus dem laufenden Titel (Beleg `PlaybackRepositoryImpl.kt:74`);
   Gegenmuster existiert: `PlaybackService.LibrarySessionCallback.onPlaybackResumption`
   (`:494-518`, Index/Position aus `queueSongIds`).
2. Frisch vs. rekonstruiert nicht unterscheidbar (Snapshot ohne
   DropSync-Flag) -> zusaetzliches Signal noetig (Recovery-Callback oder
   `DropSyncPlanStore`).
3. Race App-Start: Koordinator-Subscribe vs. Recovery-Reihenfolge (StateFlow
   liefert nach -> beide Wege landen im selben Pfad).
4. PAUSED-Recovery plant nichts (`onPaused` `:329-341`).
5. `setQueue`-Result wird nicht geprueft (P-5).
6. Fehlerzustaende stumm; PLAN_LOST braucht Kanal/Einmalzustand.
7. `generation`-Doku behauptet Skip/Route-Wechsel, real nur `ensureRestQueue`.
8. Zwei Wiederherstellungspfade (PlayerStateStore vs. Koordinator).

### Aenderungspunkte

1. `DropSyncFailureReason.PLAN_LOST`.
2. `DropSyncStateSource`: `acknowledgePlanLost()` und/oder
   `replanAfterRecovery()`; Fakes nachziehen.
3. `DropSyncCoordinator`: `recoverSessionOnStart()`, `recoveredSessionId`,
   `planLostReported`, `close()`.
4. Recovery-Signal: `TimerRecoveryListener` (domain:timer) oder
   `DropSyncPlanStore` (DataStore); Modulgrenze beachten.
5. Recovery ohne Queue-Reset: `restQueueSongIds` fuellen, `controlling`,
   Ducking, Queue/Position aus `lastPersistedState()` belassen.
6. `ensureRestQueue`: `setQueue`-Result pruefen -> `Failed(PLAYBACK_ERROR)`.
7. Statuszeile `PlanLost` + Strings + ggf. Badge.

### Testplan

- `TimerEngineSnapshotTest`: `restore lehnt konstruierten dropsync-snapshot
  ab`.
- `TimerRecoveryStarterTest`: `restaurierter REST meldet recovery an den
  listener`; `reboot meldet keine recovery`.
- `DropSyncCoordinatorTest`: `rekonstruierter REST plant neu ohne
  Queue-Reset`; `rekonstruierter REST ohne Work-Drop zeigt PLAN_LOST`;
  `frische REST-Sitzung zeigt kein PLAN_LOST`; `rekonstruierter REST
  behaelt den laufenden Titel`; `Failed(PLAYBACK_ERROR) wenn setQueue
  fehlschlaegt`.
- `NowPlayingDropStatusTest`: `Failed(PLAN_LOST) liefert PlanLost-Zeile`.

### Offene Fragen

**(entschieden 5.8/5.9: erst neu planen, wenn moeglich; sonst Meldung "Plan
verloren". Ein manueller DropRest wird nicht wiederhergestellt, aber sichtbar
gemeldet.)**

1. Lebensdauer/Quittierung des PLAN_LOST-Zustands.
2. Technische Trennung frisch/rekonstruiert.
3. Recovery-Politik (neu planen vs. anzeigen) und Schwelle.
4. Queue ueberhaupt anfassen, wenn der PlayerStateStore sie schon
   wiederherstellt?
5. PLAN_LOST auch nach Reboot?
6. Manueller DropRest: `markerPositionMs` mitpersistieren oder bewusst
   ausgenommen?

---

## 9. C2 — Skip-Schutz/Override im Player (MP-7-Rest)

**Warum schwierig:** Die UI-Sperre ist kosmetisch; Skip kommt ueber
Queue-Sheet/Bluetooth am Koordinator vorbei, und die Override-Erkennung hat
belegte Luecken (Skip innerhalb der Rest-Queue wird nicht erkannt).

### Ist-Zustand (verifiziert)

- **Sperre:** nur Now-Playing: `hasNext = ... && dropSyncState !is Armed`
  (`NowPlayingScreen.kt:405-408`); Mini-Player hat keinen Next-Button
  (`MiniPlayer.kt:138-153`); `PlayerViewModel.skipToNext()` ohne Guard
  (`:372-374`).
- **Skip-Wege:** Queue-Sheet -> `skipToQueueIndex` (`PlaybackRepositoryImpl.kt:90-93`);
  Bluetooth/Notification direkt auf Media3 (`SessionConnectionPolicy.kt:64-79`
  behaelt `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`), Spiegelung ueber
  `EVENT_MEDIA_ITEM_TRANSITION` (`PlaybackRepositoryImpl.kt:342-364`).
- **Override-Erkennung:** `onPlaybackState` (`DropSyncCoordinator.kt:495-530`)
  nur bei `Armed`; eigene Landung ausgenommen (`:500-504`); Pause via
  `sawPlaying` (`:124,505-509`); fremder Titel (`:510-514`); fremde Queue
  (`:515-518`); Seek-Toleranz 1500 ms (`:519-528,610`).
  **Luecken:** Skip innerhalb der Rest-Queue (weder Titel- noch
  Queue-Check greift, Seek verlangt gleichen Song) -> Plan bleibt Armed;
  Skip waehrend `Planned` unbeobachtet; Pause ohne vorheriges
  `isPlaying`-Sample verpasst.
- **Zustand/Undo:** `OverrideReason` (`DropSyncState.kt:44-49`) ohne
  `SKIPPED`; `DropSyncStateSource` nur `state`/`cancelPlan()`
  (`DropSyncStateSource.kt:13-23`); Snackbar-Host der Shell
  (`DropSyncApp.kt:230-236`), Undo-Muster in `MiniPlayer.kt:166-181` und
  `NowPlayingScreen.kt:244-263`.

### Fallstricke

1. UI-Sperre umgehbar (Queue-Sheet, BT).
2. `restQueueSongIds` nach Recovery leer -> Erkennung deaktiviert.
3. `sawPlaying`-Heuristik kann Pause verpassen; `Missed(OVERRIDDEN)` wird
   pauschal auf `PAUSED` gemappt (`:456-461`).
4. `generation` wird bei Skip nicht erhoeht (Doku-Drift).
5. Undo kann `NotPossible` liefern (Restzeit zu kurz) -> UI muss das zeigen.
6. Snackbar bei Hintergrund-Skip puffern (Channel(BUFFERED)).
7. Erkennung gehoert in den Koordinator (eine Wahrheit), nicht in die UI.

### Aenderungspunkte

1. `OverrideReason.SKIPPED`.
2. `onPlaybackState`: Titelwechsel innerhalb der Queue als Skip werten
   (`previous?.songId != songId` vor der Seek-Pruefung).
3. `DropSyncStateSource.replanAfterOverride(): Boolean` + Fakes.
4. `PlayerViewModel`: Einmal-Event-Kanal fuer `Armed -> Overridden`.
5. UI: `PowerampModeRow` "Details statt Next" bei Armed; Mini-Player
   Details-Einstieg; Undo-Snackbar ueber den Shell-Host.

### Testplan

- `Skip innerhalb der Rest-Queue waehrend Armed ergibt
  Overridden(SKIPPED)` (heute rot); `Queue-Sheet-Sprung ... ergibt
  Overridden`; `Bluetooth-Skip ... ergibt Overridden` (gleicher Pfad);
  `Undo nach Override armierrt neu und setzt Armed`; `Undo ohne Session
  oder mit zu kurzer Restzeit ist ein No-op`.
- `PlayerViewModelTest`: `skipToNext waehrend Armed emittiert Skip-Event`;
  `miniPlayer zeigt OVERRIDDEN-Badge`.
- Compose (D4): `PowerampModeRow zeigt Details statt Next bei Armed`.

### Offene Fragen

**(entschieden 5.10: Skip nur mit Doppelbestaetigung — erster Druck armirt und
zeigt den Hinweis, zweiter Druck loest Skip + Undo aus; die harte UI-Sperre
entfaellt.)**

1. Skip waehrend `Planned` auch Override?
2. UI-Sperre behalten oder durch "Skip erlaubt + Undo" ersetzen?
3. Undo-Umfang (nur Plan oder auch Queue/Ducking)?
4. Snackbar bei BT-Skip im Hintergrund?
5. `generation` bei Skip erhoehen?
6. Braucht der Mini-Player einen Next-Button?

---

## 10. A3 — Sensor-Health-Erholung (S-2)

**Warum schwierig:** `largestGapMs` ist monoton und nur an Reconnect
gekoppelt; ein einzelner 500-ms-Gap deaktiviert dauerhaft sowohl die
Live-Zaehlung als auch den echten Disconnect-Abbruch.

### Ist-Zustand (verifiziert)

- **Formel:** `SensorHealth.quality` (`SensorHealth.kt:58-69`):
  nicht STREAMING -> UNRELIABLE; `recentPacketLossRate >= 0.20 ||
  largestGapMs >= 500` -> UNRELIABLE; `>= 0.05 || >= 200` -> DEGRADED.
- **Entstehung:** `BatchDedupTracker.shouldSkip` (`:51-75`): `missed =
  round(elapsed/80) - 1`, `pushRecent(false)` je verpasstem Batch,
  `largestGapMs` nur erhoeht (`:69`); Rate rollt ueber 50 Batches
  (`:41-48,77-84`); `reset()` nur bei Stream-Neustart/Disconnect
  (`BleSensorProvider.kt:430,638`).
- **Wirkung:** `ActiveSetController` (`:171-183,227-236`):
  `streamProvenGood` wird true bei STREAMING/quality != UNRELIABLE; sonst
  `abort(DISCONNECT)` — aber nur `if (streamProvenGood)`.
- **Folge (Schritt fuer Schritt):** 560-ms-Gap -> UNRELIABLE -> Abort des
  laufenden Sets; Health bleibt UNRELIABLE bis Reconnect; damit kann
  `streamProvenGood` nie wieder true werden -> der echte
  Disconnect-Abbruch ist deaktiviert (Set friert ein); Lernen verwirft
  UNRELIABLE-Traces (`TrainViewModel.kt:454-457`).

### Fallstricke

1. Fenster-Asymmetrie (Rate rollt, Gap nicht).
2. `elapsed >= 80 s` gilt als Resync und zaehlt nicht (`:64`) — Grenze
   bewusst dokumentieren.
3. `streamProvenGood` koppelt Abbruchfaehigkeit an eine gute Phase.
4. Health kennt weder `jitterBufferDrops` noch `samplesDropped`.
5. Kein Provider-Test; `updateHealth` ist Android-gebunden.
6. Diagnose-Label in `SettingsScreen.kt:1140-1143` muss mitziehen.

### Aenderungspunkte

1. **Gleitendes Gap-Fenster** analog `recentWindow`:
   `gapWindow: ArrayDeque<Long?>`, `largestRecentGapMs`, Fenstergroesse als
   Parameter (Vorschlag 63 Batches = 5 s), `reset()` leert.
2. `SensorHealth`/`updateHealth` auf die neue Property umstellen (Feldname
   bewusst waehlen); Label/KStrings nachziehen.
3. Abbruchschwelle >= 500 ms bleibt; optional Disconnect-Abbruch von
   `streamProvenGood` entkoppeln.

### Testplan

- `BatchDedupTrackerTest`: `gap faellt nach dem fenster heraus`;
  `500-ms-gap danach 5 s sauber ergibt GOOD`; `gap im fenster bleibt
  UNRELIABLE`; `gap groesser 80 s wird als resync nicht gezaehlt`.
- Neuer `SensorHealthTest`: Qualitaetsmatrix.
- `ActiveSetControllerTest`: `Gap im Fenster bricht ein laufendes Set ab`;
  `erholter Health laesst ein neues Set weiterlaufen`; `echter Disconnect
  bricht weiter ab`.

### Offene Fragen

1. Fenstergroesse 50 oder 63 Batches?
2. Semantik/Label "groesster Gap im Fenster"?
3. `quality` um Jitter/Drops erweitern?
4. Schwelle + Mindestanzahl schlechter Batches?
5. Disconnect-Abbruch unabhaengig von `streamProvenGood`?

---

## 11. A1 — Satz-Undo + Haptik + PR-Kette (T-1/T-2)

**Warum schwierig:** Es gibt zwei Datenmodelle (produktives `flat_sets` vs.
toten Session-/Cluster-Pfad), die FK von `personal_records` verlangt eine
Session, und Undo/PR-Neuberechnung braucht eine Grundsatzentscheidung, die
die bisherige E5-Festlegung ("nichts anschliessen") aufhebt.

### Ist-Zustand (verifiziert)

- **Loggen:** nur `flat_sets`: `TrainScreen.kt:1372-1378` -> `TrainViewModel.logSet`
  (`:325-409`) -> `FlatSetRepository.logSet` (`FlatSetRepository.kt:44-48`,
  Impl `FlatSetRepositoryImpl.kt:46-81`); Tabelle
  `flat_sets(exercise_id, weight_milli_kg, reps, logged_at_epoch_ms)`
  (`FlatSetEntity.kt:13-40`).
- **Loeschen:** `FlatSetRepository.deleteSet` existiert (`:50-51`, Impl
  `:83-93`) — **ohne Produktionsaufrufer**; `workout_undo`-String ungenutzt.
- **Cluster-Pfad:** `completeCluster`/`undoCompleteCluster`
  (`WorkoutRepository.kt:69-85`, Impl `:206-268,270-291`) produktiv tot
  (nur Tests/Fakes); `recomputeInTransaction` (`:712-743`).
- **PRs:** `PrCalculator` (training-core `PrCalculator.kt:15-114`):
  HIGHEST_LOAD / HIGHEST_SESSION_VOLUME / MOST_REPS_AT_LOAD;
  `personal_records.achieved_session_id` ist **nicht nullable** mit FK
  (`WorkoutEntities.kt:319-324,340-341`) — Flat-Sets haben keine Session.
- **Progress:** Dashboard liest echte PRs (`WorkoutRepositoryImpl.kt:647-661`,
  `ProgressDashboardScreen.kt:124,267-278`); `AllSetsScreen` nutzt eine
  Volumen-Naeherung (`AllSetsScreen.kt:72-79`); Train zeigt
  `MAX(weight*reps)` (`FlatSetDao.kt:36-40`, `TrainScreen.kt:1394-1402`).
- **Haptik:** `HapticsAdapter.tick()/completion()` (`data/timer`, `:29-41`);
  Domain-Port `CueOutput.haptic` (`CueOutput.kt:17-18`); `hapticsEnabled`
  ist eine Attrappe (`AndroidCueOutput.kt:66-67`, DB-Spalte ohne Leser);
  Train injiziert heute keinen Haptik-Port.
- **Snackbar:** Shell-Host (`DropSyncApp.kt:230-236`); Train-Events
  (`TrainScreen.kt:895-974`) ohne Undo-Aktion; Report laeuft `Long` und wird
  **vor** dem Log emittiert (`TrainViewModel.kt:869-874`) -> Queue-Konflikt.
- **Groesse:** `TrainViewModel` 595 nicht-leere Zeilen (LargeClass 600);
  Extraktionskandidaten: `logSet`, `_logError`, `loadLastSet`,
  `loadMaxVolume`, `loadRecentSets`, `parseWeightMilliKg`,
  `formatMilliKgForInput`, Eingabefelder (Liste im Kapitel).

### Fallstricke

1. Zwei Datenmodelle: Flat-Pfad PR-faehig machen oder Cluster-Pfad
   anschliessen (E5 wird faktisch aufgehoben).
2. FK-Bremse `achieved_session_id`: Migration v11->v12, synthetische
   Session oder getrennte Flat-PR-Tabelle.
3. Zwei PR-Quellen wuerden sich per `deleteAll + recompute` gegenseitig
   ueberschreiben -> `source`-Spalte oder eine Herkunft.
4. Zwei Gewichtskonventionen (x1000 Gramm vs. x1_000_000 milli-kg).
5. `hapticsEnabled` nicht durchsetzbar; Feature darf `HapticsAdapter` nicht
   importieren (Architekturtest) -> Domain-Port oder `CueOutput`.
6. Snackbar-Queue: Report vor Log, 10 s -> Undo verzoegert; ggf.
   zusammenfassen.
7. Undo-Lebensdauer: `setId` + `exerciseId` halten, bei `selectExercise`/
   `finishExercise`/`onCleared` verwerfen.
8. Doppel-Tap-Risiko: `logSet` asynchron, Button bis Recomposition aktiv.
9. `WorkoutRepositoryImpl` ist Baseline-LargeClass; neuer Code besser in
   eigenem Baustein.
10. Kein `androidTest` in `feature:workout` (T-11/D4).

### Aenderungspunkte

- **Variante A (Flat-Pfad PR-faehig):** `FlatSetRepository.recomputeRecords`
  + `observePersonalRecords`; Impl mit `TransactionRunner` + `PrCalculator`
  (`QualifiedSegment(sessionId = ?, clusterId = setId, ...)`); Schema
  v11->v12 oder `flat_personal_records`.
- **Variante B (Cluster-Pfad anschliessen):** `logFlatSetAsCluster` +
  Session-API; `recomputeInTransaction` wiederverwendbar.
- **SetLogController** (`feature/workout`, JVM): `SetLogEvent`
  (Logged/Undone/LogFailed), `Channel(BUFFERED)`, Haptik nur nach
  DAO-Erfolg, Undo-Zustand `(setId, exerciseId)`.
- **UI:** `TrainEventSnackbars` um Undo erweitern (Muster
  `NowPlayingScreen.kt:248-263`); Strings `workout_set_saved`/`workout_undo`
  existieren.
- **DI:** Haptik-Port in `domain` + Binding in `data:timer`; Controller via
  `@Provides`.

### Testplan

- `data/workout`: `deleteSet loescht den flachen Satz und berechnet PRs aus
  der Resthistorie neu`; `deleteSet ohne weitere saetze entfernt alle PRs`;
  `flat log erzeugt die drei PR-Arten mit Gleichstandsregel`;
  `PR-Neuberechnung ist transaktional`; Migrationstest v11->v12.
- `feature/workout`: `logSet emittiert Logged mit der set-id`;
  `Undo nach Log loescht den Satz`; `Haptik feuert genau einmal nach
  erfolgreichem Log`; `Log-Fehler emittiert LogFailed und keine Haptik`;
  `Event-Kanal puffert ein Log ohne Collector`; `selectExercise verwirft
  ein offenes Undo`; `doppelter logSet-Aufruf ... Undo loescht nur das
  letzte`.
- `feature/progress`: `echte PRs haben Vorrang vor dem volumenbasierten
  Bestwert`.

### Offene Fragen

**(entschieden 5.7/5.15: Variante A — der Flat-Pfad wird PR-faehig
(Bestleistungen aus dem Training); Haptik als kurzer Impuls `tick()`.)**

1. PR-Quelle: Variante A oder B?
2. FK-Aufloesung fuer Flat-PRs.
3. `source`-Spalte gegen gegenseitiges Ueberschreiben?
4. Haptik-Intensitaet: `tick()` oder `completion()`?
5. `hapticsEnabled`-Quelle (echte Einstellung bauen?).
6. Undo-Fenster und Snackbar-Konsolidierung.
7. Undo-Umfang (auch Eingabestatus/`_lastSet`/`_recentSets`?).
8. Train-PR-Anzeige auf echte PRs umstellen?
9. Session-Volumen auf Kalendertage abbilden?
10. API-Benennung `FlatSetRepository.deleteSet` vs. neue
    `WorkoutRepository.deleteSet`.

---

## 12. D5 — N+1 und Indizes (P-2/S-8)

**Warum schwierig:** Mehrere Schleifen erzeugen je Element eine Query; die
wichtigste Indexluecke (`song_markers(source, is_enabled)`) betrifft die
Pending-Marker-Pfade, und EXPLAIN-Tests brauchen Daten + `ANALYZE` plus
Negativkontrolle, sonst sind sie gruen ohne Aussage.

### Ist-Zustand (verifiziert)

- **Shuffle:** `shuffleCandidates` (`LibraryBrowseRepositoryImpl.kt:112-139`):
  Favoriten-Join + N `getStat`-Queries (`:123-132`); kein `getStats(ids)`.
- **Playlist-Renumber:** `removeFromPlaylist` (`:300-325`) und
  `moveInPlaylist` (`:327-353`) je Item ein `updateItemPosition`
  (`:311-317,341-345`); `addToPlaylist`/M3U sind bereits gebuendelt.
- **Onset:** `writeOnsetCandidates` (`TrackAnalysisRepositoryImpl.kt:342-378`):
  1 DELETE + 2 INSERTs je Kandidat, **ohne Transaktion**; Worker-EntryPoint
  ohne `TransactionRunner` (`:274-286`); Fehler -> kein Retry, halbe
  Ersetzung moeglich; Clock zweimal je Kandidat (`:366,374`).
- **Planner:** `workCandidates` (`DropSyncPlanner.kt:118-148`): je Work-Song
  `getEnabledMarkersForSong` (`:126-128`) + `targets.first()` DataStore
  (`:122`); `songsForLabel` (`:150-155`) je Playlist eine Query; Aufruf je
  Pause/Replan/Resume/+15 s/Drop-Auto/Pausenende.
- **Gate:** `DropRestViewModel.eligibility` alle 500 ms mit
  `snapshotNow` + `getEnabledMarkersForSong` (`:56-66,112-130`).
- **Indizes:** `song_markers` nur `source_fingerprint` (`LibraryEntities.kt:80`);
  `workout_sessions.status` fehlt (`WorkoutDaos.kt:214,325-329`);
  `playlist_items(playlist_id, position)` fehlt (`LibraryBrowseDaos.kt:248-262`).
- **EXPLAIN-Muster:** `MigrationTest.kt:270-342`: v10->v11, 500 Zeilen via
  CTE, `ANALYZE`, `plan(sql)`, Negativkontrolle, Positivfaelle als Map,
  Index-Existenz ueber `sqlite_master` (`:240-260`).

### Fallstricke

1. `Flow.first()` ist keine Cache-Schicht; Flow-Cache braucht Invalidierung
   und Cancel-Pfad (P-4).
2. Mehrere Playlists pro Label multiplizieren die Queries.
3. Fachlogik im Planner (Filter/Sortierung/Ziel-Vorrang) muss in der
   Batch-Query erhalten bleiben.
4. `FakeTransactionRunner` ist ein No-op — Atomaritaet nur mit echter Room-DB
   testbar.
5. `TrackAnalysisWorker` ist ueber statischen Hilt-EntryPoint schlecht
   injizierbar -> DAO-`@Transaction` oder Helfer extrahieren.
6. Replan-Haeufung bei Uhren-Jitter (200-ms-Tick, 750-ms-Schwelle).
7. EXPLAIN-Tests brauchen Daten + `ANALYZE` + Negativkontrolle.
8. Index-Namen sind Vertrag (`index_<tabelle>_<spalten>`), sonst wirft
   `runMigrationsAndValidate`.
9. Fingerprint-Duplikat Worker vs. Bibliothek (Beruehrungspunkt).

### Aenderungspunkte

- **A1** `PlayStatDao.getStats(ids)` (IN-Query); optional
  `FavoriteDao.getFavoriteSongIds()`.
- **A2** `PlaylistDao.updateItems(items)` (Batch-Update) statt Schleife.
- **A3** Onset-Ersetzung in `@Transaction`-DAO-Methode oder
  `TransactionRunner` im Worker; Clock einmal vor der Schleife.
- **A4** Index `song_markers(source, is_enabled)` + Migration 11->12 +
  `DROPSYNC_MIGRATIONS` + DB-Version 12 + Schema 12.json.
- **A5/A6** `workout_sessions(status)` und `playlist_items(playlist_id,
  position)` nach EXPLAIN-Messung.
- **A7** `getEnabledMarkersForSongs(songIds)` + kombinierte
  `getSongsForLabelOnce(label)`; Planner in-memory gruppieren.
- **A8** `observeEnabledMarkersForSong(songId)` als Flow; Gate-Polling
  entkoppeln (oder Eligibility aus dem Monitor ableiten).

### Testplan

- `shuffleCandidates laedt statistiken in einer IN-abfrage`
  (`getStatsCalls == 1`, `getStatCalls == 0`).
- `moveInPlaylist nummeriert in einem batch neu`;
  `removeFromPlaylist nummeriert in einem batch neu`.
- `onset-ersetzung ersetzt kandidaten in einer transaktion`;
  `onset-ersetzung rollt bei link-fehler zurueck`;
  `nur bestaetigte marker ersetzen nichts`.
- `migration 11 auf 12 legt song_markers-quellen-index an und die
  pending-queries nutzen ihn` (EXPLAIN + Negativkontrolle);
  `workout-status-queries nutzen den status-index`;
  `playlist-position-queries nutzen den zusammengesetzten index`.
- `Pausenbeginn laedt marker je work-titel genau einmal`
  (`enabledMarkersCalls == listOf(20L, 21L)`);
  `Resume-Replan laedt keine rest-queue neu`;
  `plus 15 Sekunden laedt Work-Kandidaten erneut`;
  `Gate-Polling fragt marker nicht im 500-ms-takt ab`;
  `nowPlayingMarkers laedt je songwechsel genau einmal`.
- Zaehler-Konvention: Listen statt Summen; Vorbilder `RecordingScheduler`,
  `CoordinatorPlaybackRepository.armCalls`.

### Offene Fragen

1. Batch-Query oder Flow-Cache fuer den Planner?
2. Form der kombinierten Work-Query (eine vs. zwei Batch-Queries)?
3. Indexreihenfolge `(source, is_enabled)` vs. `(is_enabled, source)`?
4. Workout-Status-Index ueberhaupt (erst messen)?
5. `playlist_items`-Index bei realistischen Groessen noetig?
6. Gate-Polling ganz abschaffen?
7. Transaktionsgrenze im Onset-Lauf (`persistSuccess` mitrollen)?
8. Fingerprint zentralisieren?
9. Testbarkeit des Workers (Helfer extrahieren)?
10. Migrationsnummer/Export (12.json) und Reihenfolge der Kette.
11. Veraltete Doku-Referenzen (MP-8 nennt `RestMusicCoordinator`).

---

## 13. Weitere heikle Stellen (kurz)

### 13.1 D1 — Screenshot-Gate echt machen (I-1)

- **Ist:** CI faehrt `./gradlew test` (`ci.yml:59-60`) vor
  `:core:designsystem:verifyRoborazziDebug` (`:97-98`). Roborazzi 1.74
  Default-CaptureType ist `Dump`; damit schreibt der `test`-Lauf die
  Referenz-PNGs und `verify` vergleicht gegen die gerade erzeugten Dateien —
  das Gate kann nie fehlschlagen.
- **Fix:** CaptureType explizit (`record` nur lokal, `verify` in CI) oder
  Designsystem-Tests vom `test`-Task ausnehmen und `verify` vor `test`
  fahren; Feature-Screenshots (Train-Konsole, NowPlaying) ergaenzen.
- **Verifikation:** absichtlich geaenderter Screenshot muss CI rot machen.

### 13.2 A4 — Shadow-Recorder: Lebenszyklus + IO (T-4/T-5/S-12)

- **Ist:** `startSession` nur im `init` (`TrainViewModel.kt:786`),
  `endSession` bei `finishExercise` (`:227`) und `disconnectSensor` (`:646`)
  -> nach dem ersten Abschluss/Disconnect wird nichts mehr aufgezeichnet;
  Recorder schreibt synchron auf Main (`JsonlShadowSessionRecorder.kt:59-89`,
  Aufruf `TrainViewModel.kt:375-387`).
- **Fix:** Session bei `selectExercise`/`connectSensor` neu aufsetzen (oder
  `endSession` nur in `onCleared`); Writer auf IO/Queue mit Flush am
  Satzende. **Tests:** `zwei saetze nach finishExercise liegen im jsonl`;
  Writer-Dispatcher-Assert.

### 13.3 C9 — Waveform-A11y: Slider-Fallback (P-8)

- **Ist:** `RunningWaveform` setzt nur `progressBarRangeInfo`/
  `stateDescription` (`Waveform.kt:676-691`), kein `setProgress`; Marker sind
  per TalkBack nicht erreichbar; DoD "Slider-Fallback" offen.
- **Fix:** `setProgress`-Semantik (Seek per TalkBack) plus Marker-Textliste
  (LazyColumn nur fuer A11y/Detailmodus, UI-Handbuch 19.4).
- **Tests:** Semantik-Assert Seek; Liste enthaelt Marker.

### 13.4 C14 — Peak-Blitz an Engine-Events (P2-22)

- **Ist:** `TrainViewModel` setzt `_lastPeakMs` aus einer eigenen
  Flankenheuristik (`:814-816`: `mag - prevMag > PEAK_DELTA_G`), nicht aus
  den Rep-Events der Engine; Anzeige und Zaehler koennen sich widersprechen.
- **Fix:** an den Rep-/Peak-Event-Kanal des `ActiveSetController` binden
  (existiert fuer die Live-Zahl), Heuristik entfernen.
- **Test:** Blitz nur bei echtem Rep-Event.

### 13.5 T13 — `markRunning`-Vertrag (P3-31)

- **Ist:** `TimerEngine.markRunning` setzt nur den Status
  (`TimerEngine.kt:96-102`); `startedElapsedRealtimeMs` bleibt bei DROPSYNC
  null (`TimerModels.kt:25`).
- **Fix:** Startzeit setzen oder KDoc schaerfen (klein, Doku-Paket A9).

---

## 14. C16 — Geplante Ueberleitungskette (Entwurf, entschieden 5.16)

**Status:** Design-Entwurf (kein Code-Detailaudit); Design-Fragen entschieden
21.09.2026 (5.17-5.22). Grundlage: Ist-Architektur der Landung (Kapitel 7/8)
und die Produktentscheidung 5.16.

**Ziel:** Fuer die Countdown-/Pausenzeit berechnet die App eine Folge von
Uebergaengen ("aktueller Song spielt noch X, dann Wechsel zu Y, ...") und
landet am Ende exakt auf einem Drop.

### Ist-Architektur (verifiziert)

- `DropLandingPlanner.plan(...)` (`DropLanding.kt:80-160`) plant genau EINEN
  Work-Titel: `INTRO` (Start R-D-L-Crossfade vor dem Go) oder `DIRECT_TO_DROP`
  (Sprung beim Go). `MIN_REST_MS = 5_000`.
- `DropSyncCoordinator.planLanding` (`DropSyncCoordinator.kt:347-390`) armt
  diesen einen Plan (`armLanding`), Fallback per Deadline-Schleife.
- `ensureRestQueue` (`:288-305`) setzt die Rest-Playlist per
  `setQueue(startIndex = 0)` — der laufende Titel wird ersetzt (C13).
- Crossfade: Stufe 1 (Volumen-Rampen, `crossfadeMs` aus der DSP-Konfiguration);
  Stufe 2 (Dual-Player) ist ADR-0022-pflichtig.

### Entwurf (Design-Fragen entschieden 21.09.2026, s. 5.17-5.22)

1. **Datenmodell (`:domain:timer`):**
   `DropChain(segments: List<ChainSegment>)`,
   `ChainSegment(songId, startAtPositionMs, playForMs, transitionAtMs, kind)`,
   `kind: FILLER | LANDING`.
2. **Planner (`DropChainPlanner`), reine Mathematik:**
   `plan(remainingRestMs, currentSong: PlayingSong?, queue: List<SongCandidate>,
   workDrops: List<WorkSongDrop>, latencyMs, crossfadeMs, minSegmentMs,
   maxPlannedSongs = 2)`.
   - Startet mit der Restzeit des laufenden Titels (nicht mit `setQueue(0)`);
     hat der laufende Titel einen Drop im Zielzeitfenster, bleibt er und ist
     selbst die Landung (kein Wechsel, 5.18).
   - Fuellt die verbleibende Zeit mit Titeln der **aktuellen
     Wiedergabe-Liste** (Queue) in Reihenfolge (5.17); hoechstens
     `maxPlannedSongs` geplante Songs nach dem laufenden — bei 1-5 Min Pause
     praktisch 2-3 Lieder (5.19); ein Segment wird verworfen, wenn es kuerzer
     als `minSegmentMs` waere (kein Fragment-Springen).
   - Zwischenuebergaenge liegen auf dem Drop des Fuellers, wenn er im
     erlaubten Zeitfenster liegt; sonst Segmentende (5.20).
   - Das **letzte** Segment ist die Landung und nutzt unveraendert
     `DropLandingPlanner` (inkl. Latenz/Crossfade).
   - Crossfade-Zeit wird je Uebergang von der Fuellzeit abgezogen.
3. **Koordinator:** je Uebergang ein deadline-basierter Job (Muster
   `startFallbackLanding`, `DropSyncCoordinator.kt:396-430`); bei Replan
   (+15 s, Resume, Drop-Auto) wird die **ganze Kette** neu gebaut.
   `generation`/Watchdog entwerten veraltete Uebergaenge.
4. **Player-Port:** `transitionTo(song, positionMs, fadeMs)` — Stufe 1
   (Rampen) reicht fuer v1 (5.21); ein Klick-Risiko bei harten Wechseln bleibt
   und ist der Grund, Stufe 2 (ADR-0022) als Option zu pruefen.
5. **UI:** Kette in der Rest-Konsole (C15) **und im Mini-Player** als Zeile
   "A -> B -> Drop in 1:27" (5.22); bei Replan sichtbar aktualisieren.
6. **Mindestdauer:** < 60 s keine Kette (C15); Planner liefert dann
   `NotPossible(REST_TOO_SHORT)`.

### Fallstricke

- **Praezision nur am Ende:** Nur die letzte Landung braucht die
  Latenzkorrektur; Zwischenuebergaenge duerfen grob sein (sonst kumulieren
  Fehler).
- **Replan-Kosten:** Jede Restzeit-Aenderung baut die Kette neu; die
  Kandidatenlast (D5) darf dabei nicht erneut N+1 laden.
- **Queue-Semantik:** C13 muss zuerst die `setQueue(0)`-Falle beseitigen,
  sonst startet die Kette immer beim ersten Queue-Titel.
- **Landung ohne Wechsel:** Ist der laufende Titel die Landung, darf ein
  Replan ihn nicht neu starten (Position beibehalten).
- **Kurze Titel / markerlose Titel:** Fueller brauchen keine Marker; ein
  Titel ohne Drop kann nie Landung sein.
- **Crossfade-Stufe:** Ohne Stufe 2 kann ein Uebergang hoerbar klicken; das
  ist eine bewusste Stufe-1-Grenze (dokumentieren).
- **Determinismus:** Kettenwahl muss deterministisch sein (gleiche Eingaben ->
  gleiche Kette), sonst flackert die UI bei jedem Replan.

### Testplan (Entwurf)

- `DropChainPlannerTest`: `fuellt die restzeit mit der queue`;
  `kein segment kuerzer als minSegmentMs`; `hoechstens zwei geplante songs`;
  `letztes segment landet auf dem drop`; `laufender titel bleibt als landung`;
  `fueller-wechsel liegt auf dem drop`; `kurze pausen ergeben keine kette`;
  `deterministisch bei gleichen eingaben`.
- `DropSyncCoordinatorTest`: `kette terminiert zwei uebergaenge`;
  `replan baut die kette neu`; `generation entwertet alte uebergaenge`.
- UI (D4): Kette wird in Konsole und Mini-Player angezeigt und aktualisiert.

### Offene Fragen

**(entschieden 21.09.2026, s. 5.17-5.22)**

1. Kandidatenquelle: **aktuelle Wiedergabe-Liste** (Queue des Players).
2. Laufender Titel als Landung: **ja**, wenn sein Drop im Zielzeitfenster
   liegt (Landung ohne Wechsel).
3. Kettenlaenge: **max. 2 geplante Songs** nach dem laufenden Titel; Pausen
   sind 1-5 Min, dadurch praktisch nie mehr als 2-3 Lieder.
4. Fueller-Wechsel: **ja, auf den Drop** des Fuellers, wenn er im erlaubten
   Zeitfenster liegt.
5. Stufe 2: **fuer v1 nicht noetig**; Stufe 1 (Rampen) reicht, Stufe 2 bleibt
   optionale ADR-0022-Frage.
6. Anzeige: **Pausen-Konsole + Mini-Player** (nicht Now-Playing).
