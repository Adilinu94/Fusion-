# RESEARCH: Rep-Zaehlung, TTS-Timer und Drop-Sync (Stand 2026-09-03)

> **Zweck.** Drei Bereiche gezielt verbessern:
> 1. BLE/IMU-basiertes automatisches Wiederholungszaehlen (Hardware: M5StickC Plus2)
> 2. Workout-Timer mit Sprachansagen (TTS), ohne die Musik zu stoppen
> 3. Drop-Sync-Modus: Countdown-Ende trifft den Drop eines lokal abgespielten Songs
>
> **Methode.** Erst vollstaendige Code-Bestandsaufnahme im Repo (Datei:Zeile),
> danach Recherche gegen Primaerquellen (Paper, Android-Doku, AOSP,
> Hardware-Datenblaetter). Alles Unbelegte ist als **Annahme** markiert.
>
> **Grundsatz dieses Dokuments.** Kein Vorschlag ohne Fundstelle im Code, die
> er verbessert, und kein Zielwert ohne Messvorschrift. Die bestehende
> Projektkrankheit — "codeseitig abgeschlossen, nie am Geraet gemessen"
> (`docs/HARDWARE_TESTPLAN.md:4-9`) — soll dieses Dokument nicht fortsetzen.

---

## Teil 0: Was heute wirklich im Repo steht

### 0.1 Rep-Zaehlung: die Kette

BLE-Paket -> Rep zaehlt, in der echten Reihenfolge:

| # | Stufe | Datei:Zeile | Was passiert |
|---|---|---|---|
| 1 | GATT-Notify / read()-Poll | `data/sensor/.../BleSensorProvider.kt:455`, `:512` | Notify ist Default, Poll ist Fallback nach 800 ms Probe (`:705`, `:708`) |
| 2 | Wire-Parse | `data/sensor/.../BleProtocolParser.kt:43` | 53 Byte (v2) oder 52 Byte (v1); 4 Samples je Batch, 12 Byte je Sample |
| 3 | Dedup + Gap-Zaehlung | `data/sensor/.../BatchDedupTracker.kt:51` | Doppel-Reads und fehlende Batches werden **sichtbar**, nicht verschluckt |
| 4 | Jitter-Buffer | `data/sensor/.../JitterBuffer.kt:74` | 12 Slots, 20-ms-Tick, Drop-Oldest |
| 5 | ZUPT (auf ROHEN Werten) | `domain/sensor/.../ExerciseEnginePipeline.kt:298` | Ruhefenster -> Bias-Nachfuehrung + Pending-Abbruch |
| 6 | SignalChain | `domain/sensor/.../SignalChain.kt:67` | Bias-Korrektur -> Achsprojektion -> One-Euro -> Envelope |
| 7 | PeakDetector | `domain/sensor/.../PeakDetector.kt:111` | Pan-Tompkins-artige Zustandsmaschine IDLE/RISING/FALLING |
| 8 | TemplateMatcher | `domain/sensor/.../TemplateMatcher.kt:84` | DTW (Default) gegen Pool von 5 Templates |
| 9 | PhaseValidator | `domain/sensor/.../PhaseValidator.kt:27` | Beide Halbwellen zwingend (keine halben Reps) |
| 10 | QualityScorer | `domain/sensor/.../QualityScorer.kt:34` | 4 gewichtete Teilscores, Schwelle 0.55 |
| 11 | Plausibilitaet (Set-Ende) | `domain/sensor/.../RepCountPlausibility.kt` | Autokorrelation als Zweitmeinung, **korrigiert nicht** |

### 0.2 Alle Parameter, die heute die Zaehlung bestimmen

**Wire/Transport**
- Sample-Intervall firmwareseitig garantiert 20 ms = **50 Hz nominal**
  (`BleProtocolParser.kt:34`, `docs/archive/flowrep-import/protocol.yaml:171`)
- Batch-Rate ehrlich **12,5 Hz** (4 x 20 ms), war in v1 "bursty ~50 Hz"
  (protocol.yaml:178-180)
- Gyro-Skala **0.02 deg/s/LSB** -> +/-655,34 deg/s (v1 war 0.01 -> clippte bei
  327,67; echte kraeftige Curls erreichen ~344, protocol.yaml:78-83)
- Accel-Skala **0.001 g/LSB**
- MTU angefragt **185** (nicht 512: HyperOS-517-off-by-one, `MtuNegotiator.kt:24`),
  Fallback 23, max 2 Retries, GATT-Status 133 = Retry (`:27-33`)
- Connection Priority HIGH wird angefordert (`BleSensorProvider.kt:387`)
- GATT-Operation-Timeout 3000 ms (`BleSensorProvider.kt:1111`)
- Health-Schwellen: **>=20 % Verlust oder >=500 ms Gap = UNRELIABLE**,
  >=5 % / >=200 ms = DEGRADED (`SensorHealth.kt:36-37`)

**Filter**
- One-Euro Gyro: minCutoff 1.0 Hz, beta 0.007 (`SignalChain.kt:22-23`)
- One-Euro Accel: minCutoff 2.0 Hz (`:27`)
- Envelope-Cutoff 3.0 Hz, Decay `exp(-1/(cutoff*rate))` (`:24`, `:245`)
- Einschwingen: 50 Samples (`:25`)
- Madgwick-Orientierung: beta 0.1, **abgeschaltet** (`OrientationTracker.kt:24`,
  `ActiveSetController.kt:121-123`)
- Abtastraten-Schaetzer: Median ueber 100 Deltas, ab 20 Deltas belastbar,
  plausibel 10..200 Hz, Delta-Deckel 150 ms (`SampleRateEstimator.kt:92-111`);
  Weitergabe erst ab 1,5 Hz Abweichung (`ExerciseEnginePipeline.kt:434`)

**Peak-Detection**
- theta: **kalibriert**, Default 32.5 deg/s (`PeakDetector.kt:35`)
- Falling-Ratio 0.5, Falling-Debounce 4 Samples (`:36-37`)
- Refraktaerzeit adaptiv: 30 % der erwarteten Rep-Dauer, geklemmt
  **100..2000 ms** (`:85-95`)
- Prominenz-Mindestwert: 20 % von SPK (`:40`, `:163`)
- SPK/NPK-EMA alpha 0.125 (`:166`, `:182`)

**Template-Matching**
- Laenge 64 Samples (= 1,28 s bei 50 Hz), Schwelle **0.7**, Pool **5**, Modus
  **DTW** mit Sakoe-Chiba-Band 8 (~12 % Zeitverzerrung), Aehnlichkeit
  `1 - meanCost` (`TemplateMatcher.kt:50-52`, `:111`, `:119`, `:216`)

**Phasen/Qualitaet**
- Dauer-Verhaeltnis erlaubt 0.15..0.85, min. 2 Samples je Phase
  (`PhaseValidator.kt:23-25`)
- Gewichte Korrelation 0.40 / ROM 0.25 / Tempo 0.20 / Symmetrie 0.15,
  Schwelle **0.55** (`QualityScorer.kt:23-27`)
- Pending-Fenster-Deckel: 2x erwartete Dauer, geklemmt 1200..6000 ms
  (`RepCounter.kt:160`)
- Accel-Vote-Fenster 800 ms (`RepCounter.kt:53`)
- Grosse Zeitluecke = **250 ms** -> Pending verwerfen + Filter-Reset
  (`ExerciseEnginePipeline.kt:428`)

**ZUPT**
- Gyro-Mittelwert-Grenze 8.0 deg/s, Accel-Abweichung 0.06 g, min. Ruhedauer
  400 ms, min. 15 Samples fuer Bias (`ZuptDetector.kt:191-206`)

**Kalibrierung (Guided Calibration 2.0)**
- Ruhe-Gate: >=2 s, Gyro-Mittel <=15 deg/s, Accel-Sigma <=0.05 g
  (`CalibrationThresholds`, `CalibrationController.kt:15-18`)
- Achse per **3x3-PCA** der Einzelrep (`CalibrationController.kt:464-473`)
- theta per **Sweep** ueber Anteile der Spanne (Baseline..P99), robust per
  `median - k*MAD` (`:352-366`, `:617`)
- Nur `ChosenSignal.GP` ist freigegeben (`docs/Kritische Befunde.md`, Zeile 16)
- Accel-Schwelle: 35 % des Medians der Accel-Spitzen an validierten
  Rep-Positionen, mind. 4x Ruherauschen, min. 3 Peaks
  (`CalibrationController.kt:756-771`, ADR-0017)
- Profil-Schema v5, Lernpfad: 3 Sets bis Promotion, Rollback bei Diff 3 in
  2 Sets (`ProfileLearningPolicy.kt:10-16`)

### 0.3 Timer + TTS: die Kette

- Zeitbasis: **monotone Uhr** (`elapsedRealtime`) fuer NORMAL/REST;
  `evaluate()` ist idempotent, `delay()` ist **nie** die Abschlussquelle
  (`TimerEngine.kt:16-18`)
- Notification-Tick 200 ms, Snapshot bei jeder Aenderung persistiert
  (`TimerService.kt:293`, `:161`)
- Foreground-Service-Typ **specialUse** (`data/timer/src/main/AndroidManifest.xml`)
- Cue-Plan fix: 180/120/60/30 s gesprochen, 10..1 s Haptik (+ Sprache ausser
  DROPSYNC), 0 s Haptik + Ton (`CuePlanner.kt:9-43`)
- Get-Ready 3-2-1: nur Haptik + Ton, **keine** Sprache (`CuePlanner.kt:51-57`)
- TTS-Attribute: `CONTENT_TYPE_SPEECH` + **`USAGE_ASSISTANCE_SONIFICATION`**
  (`TtsSpeaker.kt:45-47`)
- Ducking laeuft **nicht** ueber Player-Volume, sondern ueber den
  Preamp-Knoten der eigenen 64-Bit-DSP-Kette (`PlayerVolumeGateImpl.kt:9-12`,
  `AudioPipeline.kt:115-119`)
- Zwei Ducking-Quellen werden per `min()` kombiniert, nie additiv
  (`MasterDspProcessor.kt:142`, `DuckingMixer.kt:24`)
- Cue-Ducking Werte nur 0/50/100 % (`AndroidCueOutput.kt:10-16`,
  `DuckingController.kt:34`)
- Rest-Ducking: dB, Bereich -12..0, Rampe 2 Schritte Attack / 8 Schritte
  Release je 20 ms (`AudioPipeline.kt:147-148`, `:202-203`)
- Countdown-Beeps sind vorgerendertes PCM: 880 Hz/120 ms, Go 1760 Hz/400 ms,
  5 % Huellkurve, eigener AudioTrack mit
  `USAGE_ASSISTANCE_SONIFICATION` (`CountdownBeepPlayer.kt:66-71`, `:42-43`)
- **AudioFocus:** Media3 uebernimmt ihn fuer die Musik
  (`PlaybackService.kt:116-124`, `handleAudioFocus = true`). Der Timer/TTS
  fordert **keinen eigenen Focus** an. Im Produktionscode gibt es keinen
  einzigen `requestAudioFocus`-Aufruf; nur ein Instrumented-Test nennt ihn.

### 0.4 Drop-Sync: die Kette

Es gibt **zwei** Drop-Mechanismen, die leicht verwechselt werden:

**A) Drop-Rest ("DropSync"-Timer)** — Pause endet, wenn die laufende Musik
den Marker erreicht.
- Gate: laufende Wiedergabe + zukuenftiger Marker >= 5 s
  (`DropRest.kt:57-87`, `TimerEngine.kt:484`)
- Ueberwachung: **500-ms-Polling** von `snapshotNow()`, Seek-Toleranz 1500 ms
  (`DropRestViewModel.kt:184-185`, `DropRest.kt:110`)
- Cues kommen aus der Player-Timeline, nicht aus der Systemuhr
  (`DropRestViewModel.kt:148-157`)

**B) Drop-Landung ("DropSync rueckwaerts")** — Work-Titel wird so gestartet,
dass sein Drop das Pausenende trifft.
- Reine Domainfunktion (`DropLanding.kt:85`), zwei Strategien:
  - `D >= R`: DIRECT_TO_DROP, `startAfterDelayMs = R - L`, direkt zum Drop
  - `D <  R`: INTRO, `startAfterDelayMs = R - D - L - crossfade`
- Kandidatenwahl: kleinster `|D - R|`, bei Gleichstand `D <= R` bevorzugt
  (`:105-110`)
- Ausfuehrung: `delay(plan.startAfterDelayMs)` in einer Coroutine, danach
  `playSongAt` (`RestMusicCoordinator.kt:181-191`)
- Latenz L aus `RouteProfileStore`, **Tabellenwerte**: Speaker 40, Wired 25,
  BT-SBC 120, BT-AAC 80, BT-LDAC 150, USB 30 ms (`RouteProfileStore.kt:169-174`)
- Crossfade wird mit **0** uebergeben (`RestMusicCoordinator.kt:170`)

### 0.5 Drop-Erkennung (Marker-Quelle)

- Novelty = **positive RMS-Energie-Differenz** aufeinanderfolgender Fenster
  (`OnsetDetection.kt:60-67`)
- Fenster 25 ms, 256 Waveform-Buckets ueber den ganzen Track
  (`TrackAnalyzerImpl.kt:315`, `:318`)
- Peak-Picking: gleitendes Fenster +/-40 Fenster (~2 s Gesamtbreite),
  Schwelle `mean + 2.5 * stddev`, absoluter Mindestsprung 0.05,
  Mindestabstand 5000 ms, Top 5 (`OnsetDetection.kt:14-33`)
- Ergebnis immer `AUTO_DETECTED` + `isEnabled = false` — nie Automatik
  (README Marker-Phase 5)
- BPM: Onset-Intervall-Histogramm 60..200 BPM, Oktav-Faltung,
  Konfidenz = Anteil des staerksten Bins; Gate **0.25**
  (`MixAnalysis.kt:105-114`, `:389`)
- Tonart: Goertzel-Chroma, 1024 Samples, Dezimation 5, Konfidenz-Gate **0.70**
  (`MixAnalysis.kt:325-326`, `:398`)
- Beat-Snap fuer Marker: 250-ms-Fenster, **Beat-Grid startet bei 0** —
  Downbeat-Offset fehlt und ist im Code als Spekulation benannt
  (`MarkerSnapping.kt:14`, `:19-21`)

### 0.6 Die harten Wahrheiten, woertlich

- ADR-0012: "realistisch sind ca. +/-100-200 ms (Audio-Puffer/Decoder-Latenz),
  **kein sample-genaues Ausrichten**" (`docs/adr/0012-...md:55-58`)
- ADR-0014: die Pipeline zaehlt live, **ohne** dass eines der 5
  Hardware-Szenarien je gelaufen ist (`docs/adr/0014-...md:16-26`)
- `Kritische Befunde.md`: "Ground-Truth-Suite fehlt weiterhin —
  `domain/sensor/src/test/resources` existiert nicht"
- `Kritische Befunde.md`: Release-Gates "Precision >= 98 %, Recall >= 97 %" —
  "sie haben ohne Traces keine Datengrundlage"
- ADR-0017: Accel-Konstanten "sind an synthetischen Signalen gesetzt und an
  echten M5StickC-Traces noch nicht geprueft"
- `Media3AudioClock.kt:17-18`: "Media3 verwaltet den AudioTrack intern, daher
  ist ein echter `AudioTrack.getTimestamp()` hier nicht erreichbar"
- `AudioTrackTimestampReader.kt:17-22`: "Bewusst nicht in der DI verdrahtet"
- `ShadowSessionRecorder.kt:7-9`: "raw sensor samples are out of scope"
- `WAVEFORM_PERFORMANCE_UMBAU_PLAN` (README:100): "Der 1,5-s-Zielwert ist bis
  zur Geraetemessung eine Absicht, kein belegtes Ergebnis."

**Konsequenz:** In allen drei Bereichen ist der Code weiter als die Messung.
Der groesste Hebel ist deshalb nicht mehr Algorithmik, sondern Messbarkeit.

---

## Teil 1: BLE/IMU-Rep-Zaehlung

### 1.1 Wo die Pipeline im Vergleich zur Literatur steht

| Arbeit | Sensor/Position | Verfahren | Ergebnis |
|---|---|---|---|
| **Viecelli et al. 2020**, PLoS ONE | Smartphone **auf dem Gewichtsstapel** | Accel-Algorithmus, 9 Maschinen, 22 Probanden | **Fehlerrate 0,16 %**; Single-Rep-TUT LoA -0,3..+0,3 s (0,1 % des Mittels) |
| **Pernek et al. 2013**, Pers. Ubiq. Comp. | Smartphone am Koerper | **DTW**, 3.598 Reps, Gym + natuerliche Umgebung | **Miscount-Rate ~1 %**; zeitlicher Detektionsfehler ~11 % der Rep-Dauer |
| Skawinski et al. 2019 | Brust-Accel | CNN, 4 Uebungen | Zaehlung **97,9 %**, Erkennung 89,9 % |
| Morris et al., **RecoFit** (CHI 2014) | Arm-IMU | gelernte Segmentierung + Autokorrelation, False-Peak-Rejection | **+/-1 Rep in 93 %**; Segmentierung Precision/Recall > 95 %; 114 Probanden, 146 Sessions |
| Soro et al., **Sensors 19(3):714** (2019) | Handgelenk-IMU | 1D-CNN | Erkennung 99,96 %; Zaehlung **+/-1 Rep in 91 %** |
| Balestra et al. (2021, PMC8339513) | Handruecken | Accel vs. Accel+Gyro | **84,3 % -> 95,6 %** allein durch Hinzunahme des Gyroskops |
| **uLift** (Lim et al. 2024, IEEE Access) | ein Handgelenk-Accel | Autokorrelation als **Gate**, dann Peak-Filterung, dann DTW | **mittlerer Zaehlfehler 0,61**; 15 Uebungen, 35 Probanden, **kein Training** |
| Chang et al. 2007 (184 Zitate) | Handschuh + Huefte | Peak-Counting vs. Viterbi/HMM | **Miscount ~5 %** ueber 9 Uebungen |
| Zelman et al. 2020, J. Healthc. Eng. | Smartphone-Accel | Threshold / Threshold+Lowpass / FFT | **Threshold + Lowpass gewinnt** ueber 10 Uebungen ohne uebungsspezifisches Training |
| Yurtman & Barshan 2014 (77 Zitate) | Wearable-IMU | **Multi-Template Multi-Match DTW** | 93,46 % Klassifikation; **False-Alarm < 1 %** bei leave-one-exercise-out |
| **ClassRAC** (Zhang et al. 2024) | Video | Klassifikation zuerst, **dann Zaehl-Algorithmus pro Klasse optimiert** | **MAE 0,146**, OBO 0,781 — deutlich ueber vorherigem SOTA |
| Lim & Lee (arXiv 2410.00407, 2024) | ein IMU am Ohr | Siamese + Triplet-Loss, Few-Shot | 86,8 % fuer >=10 von ~15 Reps; **nur 12,9 % fehlerfrei**, 13,2 % > 5 Fehler |
| Brennan et al. 2025, **Sports Medicine** (Systematic Review, 44 Studien) | alle | Uebersicht | IMU ist die genaueste Technologie; **am Handgelenk exzellent, auch fuer Unterkoerper**; extern platzierte Geraete genauer, aber praktisch limitiert; **Aehnlichkeit der Uebungen bestimmt die Genauigkeit** |

**Die zwei Zahlen, die alles neu ordnen.**

1. **0,16 % Fehlerrate** (Viecelli et al. 2020) — mit einem Smartphone **auf
   dem Gewichtsstapel** statt am Koerper, ueber 9 gaengige Maschinen.
2. **~1 % Miscount** (Pernek et al. 2013) — mit **DTW**, im echten Gym, ueber
   3.598 Reps.

Beide sind eine Groessenordnung besser als alles am Handgelenk oder mit
Deep Learning Erreichte. Und beide passen genau zu diesem Projekt: FlowRep
nutzt DTW (`TemplateMatcher.kt:52`) und **vier von fuenf** bestaetigten
Uebungen sind Hammer-Strength-Maschinen mit Gewichtsstapel
(`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:34-38`).

**Die Zahl, die relativiert.** Das aktuellste Few-Shot-Paper erreicht nur
12,9 % **exakt** fehlerfreie Saetze. Das Repo-Gate fordert
"Exact-Match-Rate 100 %, MAE 0" ueber 5 Szenarien und 3 Sessions
(`tools/golden_shadow_corpus/README.md:36-40`).

Das ist kein Widerspruch, sondern ein anderer Aufgabenzuschnitt — und genau
darin liegt der Vorteil dieses Projekts:

| Dimension | Papers | FlowRep |
|---|---|---|
| Uebungen | 10-30, unbekannte inklusive | **5 bestaetigte**, namentlich fixiert |
| Uebungs-**Erkennung** | Teil des Problems (Hauptfehlerquelle laut Brennan et al.) | **entfaellt** — der Nutzer waehlt die Uebung |
| Nutzer | 11-114 Probanden | **1** (Solo-Projekt, ADR-0014) |
| Kalibrierung | keine oder 5 Reps | **Guided Calibration 2.0** mit PCA-Achse, theta-Sweep, Template |
| Sensorposition | variabel | fixiert, mit Platzierungs-Tutorial |

Der Wegfall der Uebungs-Erkennung ist mehr wert als es klingt. Brennan et al.
2025 nennen ausdruecklich: *"how similar the exercises were had a significant
impact on accuracy"*. FlowReps Uebungsliste enthaelt mit Bizeps-Curl und
Scott-Curl zwei fast identische Bewegungen — ein Klassifikator wuerde daran
scheitern. FlowRep muss sie nie unterscheiden.

Ein generisches Modell muss die Varianz ueber Nutzer, Uebungen und Positionen
abbilden. FlowRep muss das nicht. 100 % exakt sind fuer *einen* Nutzer, *fuenf*
Uebungen und *eine* Sensorposition plausibel — aber **nur mit Kalibrierung pro
Uebung** und nur, wenn man es messen kann.

### 1.2 Der belegte Konstruktionsfehler: Ermuedungsdrift gegen gleitenden Mittelwert

Das ist der einzige Punkt in diesem Dokument, an dem Literatur und Code
direkt kollidieren.

**Was die Literatur sagt.** Innerhalb eines Satzes fallen Geschwindigkeit und
Bewegungsamplitude systematisch und monoton:
- Sanchez-Medina & Gonzalez-Badillo (2011, 752 Zitate): Velocity Loss ist ein
  validierter Ermuedungsindikator, hoch korreliert mit Laktat (r = 0,93-0,97).
- Rodriguez-Rosell et al. (2020, 124 Zitate): der relative Velocity Loss im
  Satz korreliert mit dem Anteil ausgefuehrter Reps mit **R = 0,97** (Bankdruecken)
  bzw. **0,93** (Kniebeuge). Untersuchte Spanne: **15-65 % Velocity Loss**.
- Chung (2026, Applied Sciences): mittlere konzentrische Geschwindigkeit faellt
  im 5er-Satz bei 60 % 1RM von 0,787 auf 0,664 m/s = **-15,6 % ueber 5 Reps**.
- Pareja-Blanco et al. (2017, 451 Zitate): VL20 und VL40 sind gaengige
  Trainingsvorgaben — **20 bzw. 40 % Geschwindigkeitsverlust** sind also der
  Normalfall, nicht der Ausnahmefall.
- Munoz-Lopez et al. (2021): alle Variablen fallen ueber die Reps, aber mit
  unterschiedlicher Groesse: **Geschwindigkeit < Beschleunigung < Leistung**.
  Die Winkelgeschwindigkeit ist damit der *stabilste* Kanal — gut fuer
  FlowReps Gyro-Primaerkanal.
- Moyen-Sylvestre et al. (2022, Sensors): Cohens d war fuer
  Winkelgeschwindigkeit **systematisch groesser** als fuer Beschleunigung;
  *"Angular velocity may be more efficient to assess fatigue than
  acceleration"*.

**Was der Code macht.** `RepCounter.trackForAdaptation()`
(`RepCounter.kt:264-284`):

```
recentDurationsMs.add(durationMs)     // FIFO, max 10
recentProminences.add(prominence)
if (size >= 3) {
    qualityScorer.updateExpectations(
        expectedDurationMs  = recentDurationsMs.average(),
        expectedProminence  = recentProminences.average(),
    )
    peakDetector.updateExpectedDurationMs(avgDurationMs)
}
```

Und `QualityScorer.score()` (`QualityScorer.kt:42-47`):

```
romRatio   = prominence / expectedProminence
romScore   = 1 - |romRatio - 1|            // 25 % Gewicht
tempoRatio = durationMs / expectedDurationMs
tempoScore = 1 - |tempoRatio - 1|          // 20 % Gewicht
```

**Drei Konsequenzen, in aufsteigender Schwere:**

1. **Die kalibrierten Erwartungswerte sind Dekoration.** Ab der dritten Rep
   jedes Satzes ueberschreibt der gleitende Mittelwert
   `profile.expectedProminence` und `profile.expectedDurationMs`
   (`ActiveSetController.kt:110-111`). Die Guided Calibration bestimmt diese
   Werte also nur fuer Rep 1 und 2.

2. **`romScore` und `tempoScore` messen nicht Qualitaet, sondern Konsistenz
   mit der unmittelbaren Vergangenheit.** Bei monoton fallender Amplitude ist
   der Mittelwert der letzten Reps per Konstruktion *ueber* dem aktuellen
   Wert — eine saubere, aber ermuedete Rep sieht schlecht aus, eine
   geschludert-schnelle Rep sieht gut aus. 45 % des Qualitaetsgewichts
   (25 + 20) haengen an dieser Groesse.

3. **Der Druck wirkt genau am Satzende.** Der `QualityScorer` ist ein Gate
   (`accepted = total >= 0.55`, `QualityScorer.kt:58`). Bei realistischen
   20-40 % Drift verliert der Score grob 0,05-0,10 Punkte — bei gutem
   DTW-Match bleibt er mit ~0,8 komfortabel ueber der Schwelle, aber die
   Sicherheitsmarge schrumpft ausgerechnet bei den letzten, haertesten Reps.
   Und eine dort verlorene Rep ist die, die dem Trainierenden am meisten
   auffaellt.

**Was daraus folgt** (keine Vermutung, sondern direkte Ableitung):
- Erwartungswerte sollten **nicht** aus dem gleitenden Mittelwert, sondern aus
  einem **Drift-Modell** kommen: entweder der Wert der *ersten* Reps als
  Referenz plus erlaubter Drift-Korridor, oder eine lineare Extrapolation
  der Trendrichtung.
- Alternativ: `romScore`/`tempoScore` in **einseitige** Scores umbauen. Eine
  Rep, die *langsamer* und *kleiner* ist als erwartet, ist bei Ermuedung
  normal; eine, die *schneller* und *groesser* ist als jede vorherige, ist
  verdaechtig (Schwung, Erschuetterung). Der aktuelle Betragsabstand behandelt
  beide Richtungen gleich.
- Beides ist ohne Golden Corpus nicht entscheidbar. Der Corpus liefert die
  gemessene Driftkurve je Uebung — genau die Groesse, die das Modell braucht.

### 1.3 Sensorposition: der 0,16-Prozent-Befund

Viecelli et al. (2020, PLoS ONE) erreichten mit einem Smartphone **auf dem
Gewichtsstapel** eine Rep-Detektions-Fehlerrate von **0,16 %** ueber 9
Maschinen und 22 Probanden — und Time-under-Tension je Einzelrep mit
Limits of Agreement von -0,3 bis +0,3 s (0,1 % des Mittels), ICC > 0,99.
Brennan et al. (2025) bestaetigen die Richtung: extern platzierte Geraete
sind genauer, haben aber *"practical limitations that may compromise their
feasibility"*.

**Warum das fuer FlowRep konkret relevant ist.** Vier der fuenf bestaetigten
Uebungen sind **Hammer Strength Iso-Lateral**-Maschinen
(`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:34-38`). Iso-Lateral bedeutet
unabhaengige **Hebelarme** — also eine echte Rotation um eine feste,
maschinenseitige Achse. Genau das Signal, fuer das FlowReps Pipeline gebaut
ist (signierte Gyro-Projektion auf eine kalibrierte Rotationsachse,
`SignalChain.kt:96`).

Ein Gyro am Hebelarm hat gegenueber dem Handgelenk drei strukturelle Vorteile:
- **Feste Achse.** Die PCA-Achse aus der Kalibrierung waere die
  Maschinenachse und wuerde sich nie verdrehen — der Grund, warum
  `orientationTrackingEnabled` existiert, entfaellt.
- **Keine Koerpersegment-Kopplung.** Kein Handgelenks-Flex, kein
  Griffwechsel, keine Ausgleichsbewegung im Signal.
- **Kein Erschuetterungs-Rauschen** vom Absetzen/Umgreifen.

Nachteile, die ehrlich dagegenstehen:
- Umbau pro Uebung (Magnetmontage), also ein Handgriff mehr je Geraet.
- Das Kalibrierungsprofil waere **pro Maschine**, nicht pro Koerper — das
  Datenmodell haelt das aus (`CalibrationProfile(exerciseId, deviceId)`,
  `SensorModels.kt:79-80`), aber die Semantik von `deviceId` verschiebt sich.
- Bizeps-Curl ist Freihantel (`priors:34`) und bleibt am Koerper.
- Die M5-Taste (BtnA = Zaehlen starten / Satz beenden,
  `protocol.yaml:135-137`) wird schlechter erreichbar.

**Empfehlung:** kein Redesign, aber **eine Messsession** im Golden Corpus mit
identischer Uebung, einmal Handgelenk und einmal Hebelarm. Das ist der
guenstigste Weg, einen potenziellen Faktor-10-Gewinn zu pruefen oder
auszuschliessen.

### 1.4 Autokorrelation gehoert nach vorne, nicht nach hinten

**uLift** (Lim et al. 2024, IEEE Access) hat praktisch FlowReps Architektur —
aber in anderer Reihenfolge:

| Stufe | uLift | FlowRep |
|---|---|---|
| 1 | **Autokorrelation** als binaeres Workout-Gate | Countdown + Nutzer-Tap (`ActiveSetController.kt:140`) |
| 2 | Peak-Counting mit Filterung unerwuenschter Peaks | `PeakDetector` + Prominenz-Filter |
| 3 | DTW-Template fuer Klassifikation | DTW-Pool fuer Verifikation |
| 4 | Form-Score aus Rep-Konsistenz | `QualityScorer` |
| — | Autokorrelation ist **Eingang** | Autokorrelation ist **Nachtrag** (`RepCountPlausibility`) |

Ergebnis von uLift: **mittlerer Zaehlfehler 0,61** ueber 15 Uebungen und 35
Probanden — **ohne jedes Training**. RecoFit (Morris et al. 2014) nutzt
Autokorrelation ebenfalls im Zaehlpfad und erreicht +/-1 Rep in 93 %.

Im Repo laeuft die Autokorrelation nur am Set-Ende, **korrigiert nicht** und
landet ausschliesslich im Log (`ExerciseEnginePipeline.kt:332-347`,
Kommentar: *"Die Pruefung KORRIGIERT nicht — sie liefert nur eine
unabhaengige Zweitmeinung, die geloggt und (spaeter) dem Nutzer angezeigt
werden kann"*). Das "spaeter" ist nicht eingetreten.

Zwei ableitbare Verbesserungen, beide klein:
- **Zweitmeinung sichtbar machen.** "Gezaehlt 12, Periodik sagt 11 —
  pruefen?" ist eine Zeile UI und nutzt eine bereits berechnete Groesse.
  Zusammen mit der D3-Regel (`confirmedRepsEdited`) verbessert das sogar die
  Ground-Truth-Qualitaet des Corpus, weil der Nutzer gezielt zum Nachzaehlen
  aufgefordert wird.
- **Periodizitaet als Plausibilitaets-Gate fuer die Refraktaerzeit.** Die
  Autokorrelation liefert eine Periodenschaetzung, die unabhaengig vom
  gleitenden Mittelwert aus 1.2 ist. Sie waere die ehrlichere Quelle fuer
  `updateExpectedDurationMs()`.

Die Parametergrenzen der Pruefung sind allerdings eng: min. 150 Samples (3 s),
Rep-Dauer 0,6-8,0 s, min. Periodizitaet 0,25
(`RepCountPlausibility.kt:135-147`). Ein 3er-Satz mit 1-s-Reps liegt genau an
der Untergrenze — fuer kurze schwere Saetze ist die Zweitmeinung stumm.

### 1.5 Parameter pro Uebung, nicht global

**ClassRAC** (Zhang et al. 2024) formuliert die Erkenntnis explizit:
*"separating the training and validation datasets by action classes and
conducting individual training and validation for each class can
significantly enhance model performance."* Ergebnis: **MAE 0,146** und
OBO-Genauigkeit 0,781 — deutlich ueber dem vorherigen Stand der Technik.
Yurtman & Barshan (2014) erreichen mit **Multi-Template Multi-Match DTW** eine
False-Alarm-Rate unter 1 %.

FlowRep macht das **teilweise**. Pro Uebung kalibriert werden:

| Kalibriert pro Uebung | Fundstelle |
|---|---|
| `rotationAxis` (PCA) | `SensorModels.kt:82` |
| `gyroBias` | `:84` |
| `repTemplate` | `:85` |
| `expectedProminence` | `:87` |
| `detectionThreshold` (theta) | `:97` |
| `noiseFloor` | `:99` |
| `expectedDurationMs` | `:101` |
| `accelThreshold` | `:117` |

**Global fest verdrahtet** bleiben dagegen:

| Global | Wert | Fundstelle |
|---|---|---|
| DTW-Schwelle | 0.7 | `ExerciseEngineConfig.kt:18` |
| Template-Pool | 5 | `:37` |
| DTW-Band | 8 von 64 | `TemplateMatcher.kt:119` |
| Qualitaets-Schwelle | 0.55 | `ExerciseEngineConfig.kt:19` |
| Score-Gewichte | 40/25/20/15 | `QualityScorer.kt:23-26` |
| Phasen-Verhaeltnis | 0.15..0.85 | `PhaseValidator.kt:23-24` |
| Refraktaer-Anteil | 0.30 | `PeakDetector.kt:41` |
| Falling-Ratio / Debounce | 0.5 / 4 | `PeakDetector.kt:36-37` |
| Pending-Deckel | 2x, 1200..6000 ms | `RepCounter.kt:160` |

Das biomechanische Vorwissen im Repo begruendet selbst, warum diese
Trennung nicht sauber ist: Scott-Curls haben laut
`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:155` *"vermutlich saubereres
Signal als beim Standard-Curl"* (Oberarm fixiert, kein Schwung moeglich),
waehrend der Lat-Pulldown eine andere Bewegungsebene hat (`:141`). Eine
Uebung mit saubererem Signal koennte eine **strengere** DTW-Schwelle tragen;
eine mit mehr Variabilitaet braucht eine **weichere**. Eine Konstante fuer
beide ist ein Kompromiss, der bei keiner der beiden optimal ist.

**Konkreter Vorschlag:** DTW-Schwelle und Qualitaets-Schwelle in das
Profil-Schema aufnehmen (v5 -> v6) und im Sweep der Guided Calibration
mitbestimmen — dieselbe Mechanik, die theta schon bestimmt
(`CalibrationController.kt:545` `knownCountSweep`). Der Sweep hat die Daten
bereits: er kennt den KNOWN_SET mit bekannter Rep-Zahl und koennte die
Schwellen so waehlen, dass der Count exakt stimmt und der Abstand zur
Fehlentscheidung maximal ist.

### 1.6 Template-Pool: DBA statt FIFO

`TemplateMatcher.addToPool()` (`TemplateMatcher.kt:70-77`) haelt die letzten
5 bestaetigten Rep-Fenster als **rohe, resamplete Einzelkurven** und nimmt
das Maximum ueber alle Vergleiche (`:93`).

Filippou et al. (2023, Sensors) zeigen den besseren Weg: **DTW Barycentre
Averaging (DBA)** erzeugt aus mehreren Beispielen *ein* gemitteltes Template,
das individuelle Variation abbildet statt sie zu vervielfachen. RMSE 2
Schritte bei gesundem Gang gegen 12 bei simuliert pathologischem — und es
schlug die Vergleichsalgorithmen gerade bei der schwierigen Gruppe.

Der Vorteil gegenueber dem FIFO-Pool ist strukturell: Ein Pool aus 5 rohen
Fenstern mit `maxOf` ist ein **Oder** — es genuegt, dass *irgendeine* der
letzten 5 Reps aehnlich war. Bei Formdrift wandert der Pool langsam mit, was
gewollt ist, aber er wandert auch bei einer schlechten Rep mit, weil jede
bestaetigte Rep aufgenommen wird (`RepCounter.kt:224`). Ein DBA-Template
mittelt Ausreisser heraus, statt sie als eigene Akzeptanzflaeche zu fuehren.

Der Code hat bereits alle Bausteine: `resample()` (`:121`), `normalize()`
(`:143`), `dtwSimilarity()` (`:178`). DBA ist im Kern eine Iteration aus
DTW-Alignment und Mittelung entlang des Warping-Pfads. **Aufwand gering,
Nutzen unbelegt bis zur Messung am Corpus** — deshalb nachrangig hinter
1.2 und der Corpus-Arbeit.

### 1.7 Der Accel-Kanal ist der groesste fertig-liegende Hebel

Balestra et al. quantifizieren genau den Kanal, der hier **aus** ist:
Gyro-Hinzunahme brachte +11,3 Prozentpunkte. Umgekehrt gilt dasselbe fuer
Accel als Zweitkanal — die Recherchenotiz in ADR-0017 nennt 95,6 % mit
Accel+Gyro gegen ~90 % mit Gyro allein.

Im Code ist der Kanal **vollstaendig implementiert**:
`SignalChain`-Accel-Zweig (`SignalChain.kt:99-101`), zweiter `PeakDetector`
(`ExerciseEnginePipeline.kt:136-141`), Voting im `RepCounter`
(`RepCounter.kt:123-126`).

Er ist aus, weil `accelThreshold` bis ADR-0017 eine geratene Konstante war
(0.1625 = 32.5/200 — eine Zahlenoperation ohne physikalischen Bezug, weil
Gyro in deg/s und Accel in g gemessen wird). ADR-0017 hat das behoben: die
Schwelle wird jetzt in `CalibrationController.finalize()` gemessen und
`accelEnabled = profile.accelVotingAvailable` (`ActiveSetController.kt:119`).

**Was bleibt:** Die drei Konstanten der Accel-Kalibrierung sind laut ADR-0017
"an synthetischen Signalen gesetzt und an echten M5StickC-Traces noch nicht
geprueft":

| Konstante | Wert | Fundstelle | Risiko bei falschem Wert |
|---|---|---|---|
| `ACCEL_PEAK_FRACTION` | 0.35 | `CalibrationController.kt:762` | zu tief -> Voting laesst alles durch; zu hoch -> jede Rep verworfen |
| `ACCEL_NOISE_MARGIN` | 4.0 | `:765` | zu klein -> Rauschen peakt mit |
| `ACCEL_PEAK_WINDOW_S` | 0.4 | `:759` | zu kurz -> echte Spitze verpasst |

Das Fehlverhalten ist asymmetrisch brutal: bei zu hoher Schwelle faellt die
Live-Zaehlung auf **0**, nicht auf "etwas schlechter". Deshalb gehoert der
Kanal zwingend hinter eine Golden-Corpus-Messung — er ist kein Kandidat fuer
"einfach mal einschalten".

### 1.8 Abtastrate: 50 Hz sind fuer Krafttraining ausreichend belegt

Fan et al. (2025, PMC11991382): ausreichende IMU-Rate fuer **Gehen 100 Hz**,
Laufen 200 Hz, hochfrequente zyklische Bewegungen 400 Hz — aber Sensoren an
der Huefte lieferten bei **50 Hz** die besten Ergebnisse.
Villa et al. (2025, MDPI Sensors 26(1):162): "sampling rates above
approximately 50-100 Hz yield marginal or negligible improvements".
Phan et al. (2023, TechRxiv): Uebungsmonitoring liess sich auf **20 Hz**
reduzieren, ohne Genauigkeit zu verlieren.

**Bewertung fuer FlowRep.** Krafttrainings-Reps liegen bei 1-4 s (das Repo
rechnet mit `expectedDurationMs = 1000` Default, `ExerciseEngineConfig.kt:24`),
also 0,25-1 Hz Grundfrequenz. 50 Hz gibt ~50 Samples je Rep und 50x
Oversampling gegen Nyquist. **Die Abtastrate ist nicht der Flaschenhals.**

Der Flaschenhals ist die **Zuverlaessigkeit** der 50 Hz: 12,5 Batches/s bei
53 Byte, jeder verlorene Batch = 80 ms Luecke, und ab 250 ms wird der komplette
Filterzustand verworfen (`ExerciseEnginePipeline.kt:428`). Drei
aufeinanderfolgende verlorene Batches killen also die laufende Rep.

### 1.9 BLE: wo Reserven liegen und wo nicht

**Hardware-Grenze.** M5StickC Plus2 = ESP32-PICO-V3-02, **Bluetooth 4.2**
(Mouser-Specsheet, m5-docs). Damit:
- **kein LE 2M PHY** (Bluetooth 5.0-Feature) -> 1M PHY bleibt die Obergrenze
- DLE (251-Byte-PDU) ist ein 4.2-Feature und **verfuegbar**
- ESP-FAQ: BLE-Durchsatz ESP32-zu-ESP32 bis ~700 kbps (1M PHY), mit 2M PHY
  bis 1,4 Mbps — letzteres ist hier nicht erreichbar

**Bedarf.** 53 Byte / 80 ms = **~5,3 kbit/s**. Das ist etwa 0,8 % der
1M-PHY-Praxisgrenze. Bandbreite ist ueberhaupt kein Thema.

**Was tatsaechlich limitiert:** das Connection Interval. BLE-Minimum 7,5 ms
(6 x 1,25 ms), Android laesst es nicht direkt setzen —
`requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` fuehrt typisch zu
11,25-15 ms. Das ruft der Code schon auf (`BleSensorProvider.kt:387`).
**Der einzige verbleibende Hebel liegt in der Firmware:** das Peripheral kann
Connection Parameters vorgeben, die Android beim Verbindungsaufbau
uebernimmt (Stack-Overflow-Konsens, Punchthrough-Guide).

**Praxiserfahrung aus einem 100-Hz-IMU-BLE-Produkt** (Oxeltech-Fallstudie,
Dez 2025) — direkt uebertragbar:
- **Timestamp-Strategie:** getestet wurden (1) absoluter Timestamp je Sample,
  (2) relativer 2-Byte-Timestamp je Sample, (3) nur erster + letzter
  Timestamp absolut. Variante 3 spart 22 Byte je Paket; Variante 2 ist die
  Wahl, wenn man **beweisen** will, dass jedes Sample im Raster liegt.
  FlowRep nutzt heute Variante 3 in Reinform — **ein** Timestamp je Batch,
  Rest per `i * 20 ms` interpoliert (`BleProtocolParser.kt:83`). Damit ist die
  20-ms-Garantie eine **Firmware-Zusage**, die die App nicht pruefen kann.
- **Dual-Buffer:** Sensor-Callback schreibt in Buffer A, BLE-Callback liest
  Buffer B, Swap ausserhalb der Interrupts. Ohne das korrumpieren
  20-50 ms BLE-Bloecke laufende Sample-Writes.
- **Verbindungsaufbau ist ein kritischer Zustand:** 200-500 ms, in denen
  Sensor-Callbacks pausiert werden muessen — "better to lose 500 ms of data
  than corrupt the whole buffer".
- **Watchdog zustandsabhaengig:** normal 5 s, waehrend Connect deaktiviert,
  bei grossen Sends 15 s.
- **MTU kann spontan zurueckfallen** (247 -> 23). Der App-Code faengt das ab
  (`MtuNegotiator.FALLBACK_MTU = 23`), aber bei MTU 23 passen 53 Byte nicht —
  dann kommen **keine** Samples mehr.

**Uhren-Drift.** Der Wire-Timestamp ist `millis()` seit Boot auf dem ESP32.
Der APB-Takt hat <=+/-10 ppm (ESP-IDF-Doku), also ~36 ms Drift pro Stunde.
Fuer die Rep-Dauer irrelevant. Relevant wird es nur, wenn man Sensor- und
Audio-Zeitbasis gegeneinander stellen will (Teil 3.5).

### 1.10 Restliche algorithmische Befunde

**DTW ist die richtige Wahl und ist schon drin.** Die Code-Begruendung
(`TemplateMatcher.kt:22-30`) ist korrekt und mehrfach belegt: NCC resampled auf
feste Laenge und vergleicht Index gegen Index, bestraft also Tempo-Variation
**innerhalb** einer Rep — genau das, was bei Ermuedung passiert. Pernek et al.
(2013) erreichen mit DTW auf Krafttraining **~1 % Miscount**, Dzaja et al.
(2022) 85,7 % Klassifikation mit DTW nach Frequenzspektrum-Segmentierung,
uLift (2024) 0,61 mittlerer Zaehlfehler mit DTW-Templates.

**Einfache Verfahren sind ueberraschend stark.** Zelman et al. (2020)
verglichen Threshold-Crossing, Threshold-Crossing mit Lowpass und
Fourier-Transformation ueber 10 Uebungen **ohne uebungsspezifisches Training**
— **Threshold + Lowpass gewann**. Genau das ist FlowReps Kern
(One-Euro-Lowpass -> `PeakDetector`-Threshold). Chang et al. (2007) fanden
Peak-Counting und Viterbi/HMM etwa gleichwertig bei ~5 % Miscount. Es gibt
keinen Hinweis, dass ein Wechsel des Grundverfahrens etwas bringt.

**Few-Shot / Metric Learning ist fuer dieses Projekt der falsche Weg.**
Das Verfahren loest "unbekannte Uebung ohne Kalibrierung". FlowRep hat 5
bekannte Uebungen **mit** Kalibrierung, und der Nutzer waehlt die Uebung
selbst. Der Aufwand (Siamese-Netz, Triplet Loss, Trainingskorpus,
TFLite-Integration) steht in keinem Verhaeltnis; Lim & Lee (2024) erreichen
damit nur 12,9 % exakt fehlerfreie Saetze — schlechter als die 0,16 % bzw.
1 % Fehlerrate der klassischen, gut platzierten Verfahren.

**Was PersonalPT (2024) beilaeufig bestaetigt:** One-Shot-Segmentierung aus
**einem einzigen** Trainingsbeispiel ist ein etablierter, funktionierender
Ansatz. FlowReps Kalibrierungsstufe `SINGLE_REP`
(`CalibrationController.kt:73`) folgt damit dem Stand der Technik, nicht einer
Notloesung.

### 1.11 Die eigentliche Luecke: Ground Truth

`Kritische Befunde.md` ist eindeutig: "Ground-Truth-Trace-Suite mit echten
Sensordaten (Umbauplan Phase 8). Die Werkzeuge sind fertig
(`tools/shadow_harness.py`, `tools/recofit_bootstrap.py`), es fehlen Aufnahmen
mit echter Hardware."

**Der blockierende Detailbefund.** Der Recorder schreibt Set-Events, aber
**keine Rohsamples** (`ShadowSessionRecorder.kt:7-9`: "raw sensor samples are
out of scope for this first slice"). Das Manifest-Schema hat dafuer schon ein
Feld: `"samples_recorded": false`
(`tools/golden_shadow_corpus/README.md:24`).

Ohne Rohsamples ist der Corpus nur fuer **eine** Frage nutzbar: "hat die
Pipeline in dieser Session richtig gezaehlt?" Nicht nutzbar fuer:
- Parameter-Sweeps offline (theta, DTW-Schwelle, Accel-Konstanten)
- Regressionstests nach Code-Aenderungen
- Reproduzierbarkeit ("derselbe Trace, anderer Algorithmus")

Das ist der Unterschied zwischen einem Abnahmeprotokoll und einem
Regressionskorpus. Fuer Parameter-Tuning braucht es zweiteres.

**Datenmenge.** 50 Hz x 6 Kanaele x 8 Byte (Double als Text ~15 Byte) — als
JSONL grob 5-8 KB/s, also **~0,4 MB je Minute**. Eine 45-Minuten-Session
liegt bei ~18 MB. Das ist auf einem Telefon vollkommen unproblematisch.
Die Zurueckhaltung im Code ist nicht durch Speicher begruendet.

### 1.12 Priorisierte Empfehlungen (Rep-Zaehlung)

Reihenfolge ist bindend: R1 ist Voraussetzung fuer alles, was danach kommt.

| # | Massnahme | Begruendung mit Beleg | Aufwand | Risiko |
|---|---|---|---|---|
| **R1** | **Rohsample-Aufzeichnung** in den JSONL-Recorder, `samples_recorded: true` | ohne sie kein Parameter-Tuning, keine Regression, keine Driftmessung; blockiert R2-R8. ~0,4 MB/min ist unkritisch | mittel | gering (nur Schreiben) |
| **R2** | **Gate 11b durchfuehren** (5 Szenarien x 3 Sessions) | ADR-0014 nennt es "das eigentliche Ziel"; alle Zielwerte (Precision 98 %, Recall 97 %) sind ohne Traces unbelegt | hoch (Zeit am Geraet) | keins |
| **R3** | **Ermuedungsdrift-Modell** statt gleitender Mittelwert in `trackForAdaptation` | 1.2: Velocity Loss 15-65 % ist der Normalfall (Rodriguez-Rosell R=0,97); der Mittelwert liegt per Konstruktion falsch und 45 % des Qualitaetsgewichts haengen daran | mittel | gering — Korridor lockern ist sicherer als straffen |
| **R4** | **Offline-Sweep-Harness** gegen den Corpus (theta, DTW-Schwelle, Accel-Konstanten, Driftkorridor) | ersetzt Raten durch Messen; ADR-0017 fordert das fuer Accel woertlich | mittel | gering |
| **R5** | **Accel-Voting freischalten**, nachdem R4 die drei Konstanten belegt hat | Balestra et al.: 84,3 -> 95,6 % durch den Zweitkanal; Code liegt vollstaendig fertig | gering | **hoch ohne R4** — Zaehlung kann auf 0 fallen (ADR-0017) |
| **R6** | **Autokorrelations-Zweitmeinung sichtbar machen** + als Quelle fuer `expectedDurationMs` | uLift/RecoFit nutzen Autokorrelation im Zaehlpfad (0,61 bzw. +/-1 Rep in 93 %); hier verpufft sie im Log | gering | gering; verbessert zugleich die Ground-Truth-Qualitaet (D3) |
| **R7** | **Messsession Hebelarm vs. Handgelenk** in den Corpus | Viecelli et al. 0,16 % am Gewichtsstapel; 4 von 5 Uebungen sind Hebelarm-Maschinen mit fester Rotationsachse | gering (1 Session) | keins — reine Messung |
| **R8** | **DTW- und Qualitaets-Schwelle pro Uebung** kalibrieren (Schema v5 -> v6) | ClassRAC: Optimierung je Klasse hebt MAE auf 0,146; Scott-Curl hat laut Repo-Priors "saubereres Signal" als Standard-Curl | mittel | gering (additive Spalten) |
| R9 | **Firmware: Connection Parameters vorgeben** + Dual-Buffer + Sample-Pause beim Connect | einziger Weg zu kuerzerem Connection Interval; Dual-Buffer verhindert Pufferkorruption (Oxeltech) | mittel (Firmware) | mittel (Flash noetig) |
| R10 | **DBA-Template** statt FIFO-Pool aus 5 Rohfenstern | Filippou et al.: DBA schlug Vergleichsalgorithmen gerade bei schwieriger Gruppe; `maxOf` ueber 5 Rohfenster ist ein Oder-Gate | gering | gering, Nutzen unbelegt bis Messung |
| R11 | **Relativer Timestamp je Sample** (2 Byte, 53 -> 61 Byte, Protokoll v3) | macht die 20-ms-Firmware-Garantie pruefbar statt geglaubt (Oxeltech-Variante 2) | gering | gering |
| R12 | Madgwick-Orientierungstracking freischalten | faengt Sensor-Verdrehung ab; Code fertig. **Entfaellt bei Hebelarm-Montage** (feste Achse) | gering | mittel — erst nach R2 |

**Was NICHT empfohlen wird und warum:**
- **Few-Shot / Deep Learning** (1.10): loest ein Problem, das FlowRep nicht hat
  (unbekannte Uebungen), und erreicht dabei schlechtere exakte Genauigkeit.
- **Hoehere Abtastrate** (1.8): 50 Hz sind mehrfach als ausreichend belegt;
  der Flaschenhals ist Paketverlust, nicht Auflaesung.
- **Wechsel des Grundverfahrens** (1.10): Threshold+Lowpass und DTW sind in
  Vergleichsstudien die Gewinner, nicht die Verlierer.
- **Uebungsklassifikation nachbauen** (1.1): der Nutzer waehlt die Uebung —
  damit entfaellt die laut Brennan et al. groesste Fehlerquelle des Felds.

---

## Teil 2: TTS-Sprachansagen ohne die Musik zu stoppen

### 2.1 Der entscheidende Befund: die Anforderung ist bereits erfuellt — aber aus dem falschen Grund

Die Frage "TTS ohne die Musik zu stoppen" hat auf Android zwei voellig
verschiedene Antworten, je nachdem, **wessen** Musik gemeint ist.

**Fall A — eigene Musik (heutiger Zustand).** Musik laeuft im eigenen
`PlaybackService` (`PlaybackService.kt:116-124`). Es gibt keinen
AudioFocus-Konflikt, weil beides derselbe Prozess ist. Die App senkt ihre
eigene Musik am Preamp-Knoten der eigenen DSP-Kette ab
(`PlayerVolumeGateImpl.kt:9-12`). Das ist die technisch saubere Loesung und
funktioniert unabhaengig von Systemverhalten.

**Fall B — fremde Musik (Spotify, YouTube Music, Poweramp).** Hier greift
AudioFocus. Und hier ist der aktuelle Code **strukturell blind**: es gibt
keinen einzigen `requestAudioFocus`-Aufruf im Produktionscode (nur in
`app/src/androidTest/.../AudioFocusPermanentLossInstrumentedTest.kt`).

Was heute in Fall B passiert:
- `TtsSpeaker` setzt `USAGE_ASSISTANCE_SONIFICATION` (`TtsSpeaker.kt:46`)
- `CountdownBeepPlayer` setzt `USAGE_ASSISTANCE_SONIFICATION` +
  `CONTENT_TYPE_SONIFICATION` (`CountdownBeepPlayer.kt:42-43`)
- **Kein** Focus-Request -> die fremde Musik erfaehrt nichts -> sie duckt nicht
- Ergebnis: Ansage und Beeps laufen **parallel zur ungedaempften** fremden
  Musik. Die Musik stoppt zwar nicht (Ziel erreicht), aber die Ansage ist in
  einem Gym mit lauter Musik im Kopfhoerer schlecht verstaendlich.

Der bestehende Ducking-Pfad hilft in Fall B nicht: `PlayerVolumeGateImpl`
setzt den Gain der **eigenen** DSP-Kette, durch die fremde Musik nie laeuft.

### 2.2 Was die Plattform dafuer vorsieht

Android-Doku (`developer.android.com/media/optimize/audio-focus`):
- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` ist genau der Fall "kurze Ansage,
  andere App soll leiser werden, nicht stoppen" — die Doku nennt als Beispiel
  explizit hoerbare Navigationsanweisungen: *"Ducking is particularly suitable
  for apps that use the audio stream intermittently, such as for audible
  driving directions."*
- **Seit Android 8.0 (API 26) duckt das System selbst**: *"the system can duck
  and restore the volume without invoking the app's callback"* — und *"By
  having the system implement ducking, you don't have to implement ducking in
  your app."* Bei minSdk 26 gilt das fuer **alle** unterstuetzten Geraete.
- Der Halter des Fokus bekommt bei MAY_DUCK typisch **keinen**
  `onAudioFocusChange`-Callback. Ausnahme: er hat
  `setWillPauseWhenDucked(true)` gesetzt, dann pausiert er.
- Konvention aus dem offiziellen Blog: eine MAY_DUCK-Anfrage sollte das
  Audiosystem **nicht laenger als 15 Sekunden** halten. Ansagen wie
  "3 Minuten" oder "30 Sekunden" liegen weit darunter.

Grenzen, die man kennen muss:
- Ducking ist eine **Bitte**. Es gibt Apps und Nutzereinstellungen, die es
  ignorieren oder abschalten (Reddit/Spotify-Community, mehrere Threads).
- Die Absenkungstiefe ist Systemsache, nicht app-konfigurierbar. Ein
  belastbarer dB-Wert liess sich nicht aus einer Primaerquelle belegen —
  **Annahme, nicht messbar von der App aus.**
- `USAGE_ASSISTANCE_SONIFICATION` ist fuer "Sonifikation" gedacht (UI-Toene).
  Fuer Sprachansagen ist `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` oder
  `USAGE_ASSISTANT` die semantisch passendere Wahl; die Bauplan-Regel
  (`TtsSpeaker.kt:15-16`: "nie VOICE_COMMUNICATION") bleibt davon unberuehrt.
  Beide Alternativen sind Kandidaten, aber **welche das System guenstiger
  behandelt, ist nicht dokumentiert und muss gemessen werden.**

### 2.3 TTS-Latenz: das unterschaetzte Timing-Problem

Der Timer ist millisekundengenau (monotone Uhr, `TimerEngine.kt:16-18`).
Die Ansage ist es nicht.

Belegt:
- Android-Doku zu `TextToSpeech`: *"There might be a finite lag..."* vor dem
  Sprechbeginn.
- Wear-OS-Blogpost (Google, Maerz 2024): die Synthese-Engine ist nach dem Boot
  erst **nach ~10 Sekunden** bereit.
- Stack Overflow (73947681): TTS-Aeusserungen werden bei ausgeschaltetem
  Bildschirm **zufaellig verzoegert**.
- Picovoice (Dez 2025): "First audio latency" ist die entscheidende Metrik;
  On-Device-TTS beginnt sofort mit der Synthese, Cloud-Engines addieren
  Hunderte bis Tausende Millisekunden.

Im Code ist die Konsequenz schon gezogen, aber nur halb:
- Der Design-Plan sagt: *"Countdown-Piep-Toene statt TTS-Stimme:
  vorgerenderte Clips ... Go ist ein echtes Audio-Event"* und *"Haptik: Go-Zeit
  ist autoritativ, Haptik nicht auf TTS/onStart warten"*
  (`FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md:263-264`)
- Umgesetzt: 3-2-1 und Go sind vorgerenderte PCM-Clips
  (`CountdownBeepPlayer.kt`), DROPSYNC spricht 10..1 gar nicht
  (`CuePlanner.kt:30`)
- **Nicht** umgesetzt: die Ansagen bei 180/120/60/30 s laufen ueber TTS. Dort
  ist Timing-Praezision aber auch irrelevant — 200 ms Verzoegerung bei
  "3 Minuten" merkt niemand. **Das ist konsistent und richtig.**

Der praktisch relevante Restpunkt: TTS wird in `TtsSpeaker.initialize()`
nicht-blockierend initialisiert (`:28`), aber es gibt **keinen Warm-up**. Der
erste Cue einer Session traegt damit die volle Initialisierungslatenz.

### 2.4 Kollisionen im Ducking-Pfad

Sauber geloest:
- `min()` statt Addition (`MasterDspProcessor.kt:142`, `DuckingMixer.kt:24`) —
  Rest-Ducking und Cue-Ducking verstaerken sich nie gegenseitig
- Session-gebundene Cue-IDs: eine veraltete TTS-Antwort kann die Lautstaerke
  einer neuen Session nicht mehr veraendern (`DuckingController.kt:58-59`)
- Nutzeraenderung waehrend einer Ansage wird als neue Basis uebernommen
  (`DuckingController.kt:46-52`)
- Ducking auf dem Preamp-Knoten kollidiert nicht mit DVC
  (`PlayerVolumeGateImpl.kt:10-12`)

Zwei ungenutzte Bausteine:
- `DuckingRamp` (Attack 40 ms / Release 200 ms, `DuckingMixer.kt:65-67`) hat
  **keinen Aufrufer** im Produktionscode. Die Rampe laeuft stattdessen als
  fest verdrahtete Schleife in `AudioPipeline.rampDuck()` mit 2 Schritten
  Attack / 8 Schritten Release je 20 ms (`AudioPipeline.kt:147-148`) — also
  40 ms / 160 ms. Zwei Implementierungen derselben Sache, eine davon toter Code.
- Das **Cue**-Ducking hat gar keine Rampe: `setDuckingGain()` wirkt sofort
  (`AudioPipeline.kt:115-119`). Design Phase 7 verlangt Attack 20-50 ms /
  Release 150-300 ms; das gilt heute nur fuer Rest-Ducking.

Ein weiterer Design-Punkt ist unumgesetzt: *"Coordinator pausiert TTS waehrend
Crossfade End Phase 500ms fuer sauberen Drop"*
(`FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md:257`). Eine TTS-Ansage, die
zufaellig in die Drop-Landung faellt, duckt heute genau in dem Moment, in dem
der Drop voll knallen soll.

### 2.5 Ducking-Prozentwerte: 0/50/100 ist zu grob

`DuckingPercent` erlaubt nur 0, 50, 100 (`AndroidCueOutput.kt:14`,
`DuckingController.kt:34`). Umgerechnet: 50 % Gain = **-6,0 dB**, 100 % =
Stille. Rest-Ducking dagegen arbeitet in dB mit Default **-8 dB**
(`DuckingMixer.kt:21`) und feiner Auflaesung im Bereich -12..0
(`AudioPipeline.kt:202-203`).

Zwei Skalen fuer dieselbe physikalische Groesse, davon eine mit drei Stufen.
Bei aktivem Rest-Ducking (-8 dB) und Cue-Ducking 50 % (-6 dB) gewinnt per
`min()` das Rest-Ducking — die Ansage wird also **nicht** zusaetzlich
hervorgehoben, obwohl sie der Anlass war. Das ist ein logischer Bruch, kein
Bug: `min()` ist als "staerkstes Ducking gewinnt" definiert, aber -6 dB fuer
eine Sprachansage ist schwaecher als das Dauer-Ducking der Pausenmusik.

### 2.6 Priorisierte Empfehlungen (TTS/Ducking)

| # | Massnahme | Warum | Aufwand | Risiko |
|---|---|---|---|---|
| T1 | **AudioFocus `GAIN_TRANSIENT_MAY_DUCK`** um Ansagen + Beeps legen, mit `setWillPauseWhenDucked(false)` | einzige Moeglichkeit, fremde Musik zu ducken statt zu stoppen; System uebernimmt das Ducking ab API 26 selbst | gering | gering — muss aber gegen Spotify/YTM/Poweramp geprueft werden |
| T2 | **Cue-Ducking in dB** ueberfuehren, gemeinsame Skala mit Rest-Ducking | 3 Stufen vs. feine dB-Skala ist inkonsistent; -6 dB fuer Sprache ist zu schwach neben -8 dB Dauer-Ducking | gering | Migration bestehender Praeferenzwerte |
| T3 | **Rampe fuer Cue-Ducking** einfuehren (Design: Attack 20-50 / Release 150-300 ms) | heute schaltet Cue-Ducking hart; Design Phase 7 verlangt Rampen | gering | gering |
| T4 | `DuckingRamp` **entweder verdrahten oder loeschen** | zwei Implementierungen derselben Rampe, eine ungenutzt | gering | keins |
| T5 | **TTS-Warm-up** vor Sessionstart (leere Aeusserung mit Volume 0) | erster Cue traegt sonst die Init-Latenz; Wear-OS-Blog belegt ~10 s Kaltstart | gering | gering |
| T6 | **TTS-Sperre in den letzten ~500 ms vor der Drop-Landung** | Design 257 fordert es; sonst duckt eine Ansage genau im Drop | gering | gering |
| T7 | `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` **gegen** SONIFICATION messen | semantisch passender; Verhaltensunterschied ist nicht dokumentiert | gering | gering, aber **nur mit Messung entscheiden** |
| T8 | Fokus-Verlust-Verhalten fuer den Timer definieren (Design 7.1b) | Design hat die Tabelle, Code hat keinen Focus-Listener | mittel | gering |

**Was NICHT empfohlen wird:** eigene TTS-Engine, Cloud-TTS (Offline-Grundsatz
im README), TTS fuer die letzten 10 Sekunden (Design-Entscheidung, korrekt).

---

## Teil 3: Drop-Sync — Countdown-Ende trifft den Drop

### 3.1 Das Fehlerbudget, ehrlich addiert

ADR-0012 nennt "+/-100-200 ms". Diese Zahl ist eine Schaetzung ohne Herleitung.
Hier die Terme, die tatsaechlich eingehen:

| Term | Groesse | Quelle | heute kompensiert? |
|---|---|---|---|
| Timer-Zeitbasis (monoton) | < 1 ms | `elapsedRealtime`, `TimerEngine.kt:16` | entfaellt |
| `delay(startAfterDelayMs)`-Genauigkeit | ~1-15 ms typ., unter Doze deutlich mehr | Coroutine-`delay` ist kein Echtzeit-Timer | **nein** |
| Decoder-/Seek-Einstieg (MP3/AAC) | bis zu einem Frame-Raster | Design 7.1c, `seekPrerollFrame` | **nein** — Feld existiert nicht im Code |
| AudioTrack-Pufferfuellung | ~20-100 ms, geraeteabhaengig | Android-CDD: `low_latency` = <= 45 ms **kontinuierlich**; Kaltstart deutlich mehr | **nein** |
| Route-Latenz (Ausgabekette) | 25-150 ms je Route | `RouteProfileStore.kt:169-174`, Tabellenwerte | **ja**, per Tabelle |
| BT-Codec-Streuung | real 120-250 ms bei SBC | RTINGS: "Typical SBC latency ranges between 150 to 250 ms" | teilweise — Tabelle sagt 120 ms |
| Marker-Position selbst | Fenstergroesse 25 ms | `TrackAnalyzerImpl.kt:318` | strukturell begrenzt |
| Beat-Grid-Offset | unbekannt, Grid startet bei 0 | `MarkerSnapping.kt:19-21` (im Code als Spekulation benannt) | **nein** |

**Der wichtigste Einzelbefund:** Die BT-SBC-Tabellenzeile (120 ms) liegt
**unter** dem von RTINGS gemessenen typischen Bereich (150-250 ms). Wenn das
zutrifft, landet der Drop bei Bluetooth-SBC systematisch **30-130 ms zu
frueh** — und zwar reproduzierbar, also korrigierbar, sobald einmal gemessen
wird. `docs/HARDWARE_TESTPLAN.md:97-114` (Test B1) beschreibt die Messung
bereits vollstaendig; sie ist nur nie gelaufen.

### 3.2 Warum die Landung heute nicht praeziser sein kann

Drei strukturelle Gruende, alle im Code belegt:

**1. `delay()` statt Audio-Uhr.** Die Landung wird als
`delay(plan.startAfterDelayMs)` in einer Coroutine terminiert
(`RestMusicCoordinator.kt:181`). Damit ist die Genauigkeit an den
Dispatcher gebunden, nicht an die Audio-Frame-Uhr. Media3 bietet fuer genau
diesen Fall `ExoPlayer.createMessage(...).setPosition(...)` — ein
`PlayerMessage`, das der Player auf dem Playback-Thread **an einer
Wiedergabeposition** ausfuehrt (Android-Doku "Player events"). Das ist die
richtige Primitive und wird im Repo nirgends verwendet (Grep ueber alle
`.kt`: keine Treffer ausser dem Kommentar in `TimerEngine.kt:20`, der sie
bereits als Soll beschreibt).

**2. Die Audio-Uhr ist bewusst BEST_EFFORT.** `Media3AudioClock` interpoliert
die Player-Position ueber die Systemzeit und meldet konstant
`Mode.BEST_EFFORT`; `Mode.EXACT` ist als "spaeter" markiert
(`Media3AudioClock.kt:27-29`). Der echte Pfad existiert schon —
`AudioTrackTimestampReader` (`AudioTrack.getTimestamp()`) plus
`AudioTimestampExtrapolator` mit der Formel
`audibleFrame = framePosition + (nowNs - tsNano) * rate` — ist aber
**nicht in der DI verdrahtet**, mit korrekter Begruendung:
*"Die App besitzt keinen eigenen AudioTrack (Media3 verwaltet seinen Sink
intern)"* (`AudioTrackTimestampReader.kt:17-22`).

Der nift4-Deep-Dive in den AOSP-Audiostack (2025-08) bestaetigt das
Grundproblem und zeigt zugleich den Ausweg: `DefaultAudioSink` erzeugt den
`AudioTrack` selbst (`DefaultAudioSink.java`), aber ExoPlayer laesst eine
eigene `AudioSink`-Implementierung zu — und `DspRenderersFactory`
(`DspRenderersFactory.kt:36-46`) baut den Sink im Repo **bereits selbst**.
Der Zugriffspunkt auf den echten `AudioTrack` ist also eine Ableitung von
`DefaultAudioSink` entfernt, nicht eine Plattformgrenze.

**3. Kein Seek-Preroll.** Design 7.1c verlangt pro Marker zwei Werte:
`markerFrame` (hoerbarer Impact) und `seekPrerollFrame` (Decoder-Einstieg),
*"damit der Direct-Drop-Sprung nicht am falschen Punkt landet"*. Das Marker-
Modell hat nur `positionMs` (`core/model/.../Library.kt:25`). Bei
`DIRECT_TO_DROP` springt `playSongAt(workSong, plan.startAtPositionMs)`
(`RestMusicCoordinator.kt:191`) auf die Marker-Position; wo der Decoder
tatsaechlich einsetzt, ist ungeprueft. Die Media3-Doku zur Fehlersuche
beschreibt genau diesen Effekt fuer exaktes Seeking.

### 3.3 Was Praezision ueberhaupt wert ist

Bevor Aufwand in Millisekunden fliesst, die Frage nach der Wahrnehmung:

- **JND fuer Audio-Latenz** (Regensburg, ACM MuC 2024, `dl.acm.org/doi/10.1145/3678299.3678331`):
  mittlere gerade wahrnehmbare Differenz **49 ms** bei 0 ms Basislatenz,
  **27 ms** bei 64 ms Basis, **77 ms** bei 512 ms Basis. Die Schwelle ist
  nichtlinear und im mittleren Bereich am *empfindlichsten*.
- **Audiovisuelle Synchronitaet zu Musik** (PMC6711538): JND rund **60 ms**.
- **Zeitverschiebung eines Tons in einer metrischen Sequenz** (Friberg &
  Sundberg): rund **10 ms** fuer kurze Toene — das ist die strengste
  gefundene Schwelle und gilt fuer isolierte Klicks, nicht fuer einen Drop
  in dichter Musik.

**Ableitung.** Ein Zielfenster von **+/-25 ms** liegt unter jeder der
genannten Schwellen fuer den relevanten Fall (Drop in dichter Musik, Basislatenz
> 0). **+/-50 ms** ist an der Grenze und in einem Fitnessstudio mit
Kopfhoerern realistisch nicht mehr unterscheidbar. Ein Anspruch auf
sample-genaue Ausrichtung waere Verschwendung; das Repo formuliert das in
ADR-0012 bereits richtig.

Das eigentliche Qualitaetsziel ist deshalb nicht der Mittelwert, sondern die
**Streuung**: ein konstanter Offset von 80 ms ist unhoerbar (der Nutzer kennt
den Bezugspunkt nicht), ein zwischen -50 und +150 ms schwankender Offset ist
sofort als "unzuverlaessig" erlebbar. `AudioRouteProfile` hat dafuer bereits
die richtigen Felder: `p50ErrorMs` und `p95ErrorMs`
(`AudioClock.kt:62-63`) — beide werden nirgends geschrieben (Grep auf
`upsert(`: nur DAO-Treffer und der Store selbst).

### 3.4 Marker-Qualitaet: die unterschaetzte Fehlerquelle

Ein perfekt terminierter Sprung auf einen falsch gesetzten Marker ist
wertlos. Die aktuelle Onset-Erkennung nutzt **RMS-Energie-Differenz** als
Novelty (`OnsetDetection.kt:60-67`). Die Literatur ist an diesem Punkt
eindeutig:

- Mueller & Zalkow (TISMIR 2024, "A Basic Tutorial on Novelty and Activation
  Functions"): *"In many situations, spectral flux fails to reliably detect
  note onsets"* — und Spectral Flux ist schon die **staerkere** Variante.
  Reine Energie-Novelty liegt darunter.
- MIR-Praxis (musicinformationretrieval.com, Essentia-Referenz): Spectral
  Flux erfasst zusaetzlich **Frequenzaenderungen**, nicht nur
  Amplitudenspruenge. Ein EDM-Drop aendert typisch Bass-Einsatz und
  Spektralverteilung, oft ohne dramatischen RMS-Sprung (weil der Build-up
  bereits laut war).
- Boecks **SuperFlux/ComplexFlux** waren 2016 MIREX-Spitze.

Fuer die spezifische Aufgabe "Drop finden" gibt es zudem eigene Arbeiten:
- **Yadati et al., ISMIR 2014** ("Detecting Drops in Electronic Dance
  Music"): zweistufig — erst Klangcharakteristik waehrend des Drop-Ereignisses
  modellieren, dann **zeitliche Struktur** einbeziehen. Genau die zweite
  Stufe fehlt hier: `OnsetDetection` kennt keine Phrasen- oder Taktstruktur.
- **Zehren et al.** (Computer Music Journal 46(3), 2022): automatische
  Cue-Point-Erkennung, *"about 90 percent of the points generated can be
  reliably used in the context of a DJ mix"*; die arXiv-Vorversion (2007.08411)
  nennt 96 % und Precision > 85 %. Kernbestandteil: **Downbeat-Erkennung**
  plus musikalisch informierte Regeln.
- **CUE-DETR** (Arguello et al., arXiv 2407.06823, 2024): Cue-Point-Erkennung
  als Objekterkennung auf dem Spektrogramm, hoehere Precision als
  Vorgaengerverfahren. Zeigt die Richtung, ist aber ein neuronales Modell und
  widerspricht dem Offline-/Nachvollziehbarkeits-Grundsatz des Repos
  (`OnsetDetection.kt:6-11`: *"bewusst klassische Signalverarbeitung, kein ML"*).
- **EDMFormer** (arXiv 2603.08759): Annotationen mit **+/-0,5 s Praezision**
  gelten in der Forschung als Referenzqualitaet fuer EDM-Strukturgrenzen.
  Das relativiert den Anspruch: wenn die *Grundwahrheit* eines Drops nur auf
  eine halbe Sekunde bestimmt ist, ist die Marker-Position und nicht die
  Audio-Latenz der dominierende Fehlerterm.

**Der wichtigste unmittelbar nutzbare Punkt** ist der Downbeat. `MarkerSnapping`
snapt auf ein Beat-Raster, das bei 0 ms beginnt — der Code nennt das selbst
Spekulation (`:19-21`). Ohne Downbeat-Offset rastet der Snap auf ein um bis zu
einen halben Beat verschobenes Gitter. Bei 128 BPM ist ein Beat 469 ms, ein
halber Beat 234 ms — **eine Groessenordnung mehr als das gesamte
Audio-Latenzbudget**. Das Beat-Snap kann heute also mehr Fehler einbringen als
alle Latenzterme zusammen.

Zusaetzlich vorhanden, aber ungenutzt: die Analyse berechnet bereits
**LUFS und True Peak** (`TrackAnalyzerImpl.kt:103`, `LoudnessAccumulator`).
Kurzzeit-LUFS ueber ein 3-s-Fenster (EBU R128) ist ein perzeptiv gewichtetes
Energiemass und damit naeher am "gefuehlten Drop" als reine RMS-Differenz.

### 3.5 Zwei Zeitbasen, die niemand verbindet

Ein Nebenbefund mit Konsequenz fuer Drop-Rest (Mechanismus A):
`DropRestViewModel.monitor()` pollt `snapshotNow()` alle **500 ms**
(`DropRestViewModel.kt:185`) und leitet daraus Cue-Grenzwerte und die
Rest-Projektion ab. Die Sensor-Pipeline arbeitet dagegen mit
Firmware-Timestamps im 20-ms-Raster, und die Audio-Position kommt aus einer
dritten Uhr. Drei Zeitbasen, keine gemeinsame Referenz.

Fuer das Zaehlen ist das irrelevant (die Rep-Dauer nutzt konsequent
Firmware-Timestamps, `RepCounter.kt:253-262`). Fuer die 0-s-Grenze des
Drop-Rest bedeutet 500-ms-Polling aber eine Unsicherheit, die **zwanzigmal
groesser** ist als das Zielfenster der Drop-Landung. Das ist kein Fehler —
Mechanismus A soll nur "Pause endet am Marker" liefern — aber es erklaert,
warum sich Drop-Rest und Drop-Landung unterschiedlich praezise anfuehlen
werden.

### 3.6 Priorisierte Empfehlungen (Drop-Sync)

| # | Massnahme | Begruendung mit Beleg | Aufwand | Risiko |
|---|---|---|---|---|
| **D1** | **Test B1 durchfuehren** und `p50ErrorMs`/`p95ErrorMs` je Route schreiben | Messvorschrift steht fertig in `HARDWARE_TESTPLAN.md:97-114`; die Felder existieren und werden nie gefuellt; RTINGS legt nahe, dass die SBC-Zeile 30-130 ms zu niedrig ist | gering (Zeit) | keins |
| **D2** | **Downbeat-Offset** bestimmen und in `MarkerSnapping` einsetzen | ohne Offset kann der Snap bis zu einen halben Beat (234 ms bei 128 BPM) verschieben — mehr als das gesamte Latenzbudget; Zehren et al. nennen Downbeat-Erkennung als Kernbestandteil | mittel | gering; Snap bleibt optional |
| **D3** | **`PlayerMessage` statt `delay()`** fuer die Landung | Media3 fuehrt es auf dem Playback-Thread an einer Wiedergabeposition aus; `delay()` haengt am Dispatcher | gering | gering |
| **D4** | **Spectral Flux statt RMS-Novelty** in `OnsetDetection` | Mueller/Zalkow und MIREX-Historie sind eindeutig; ein Drop aendert Spektrum, nicht nur Amplitude. Kurzzeit-LUFS ist als Zwischenschritt schon vorhanden | mittel | mittel — `ANALYZER_VERSION` steigt, Re-Analyse der Bibliothek |
| **D5** | **Zielfenster und Streuung explizit festlegen**: +/-25 ms Anspruch, +/-50 ms Akzeptanz, P95 als Kennzahl | JND 27-49 ms je Basislatenz (ACM MuC 2024); ein konstanter Offset ist unhoerbar, ein schwankender nicht | gering (Doku) | keins |
| D6 | **`seekPrerollFrame` je Marker** aufnehmen | Design 7.1c fordert es; ohne den Wert ist der Einstiegspunkt bei DIRECT_TO_DROP ungeprueft | mittel | gering (additiv) |
| D7 | **`AudioClock.Mode.EXACT`** ueber eine eigene `AudioSink`-Ableitung erschliessen | der Extrapolator ist fertig und getestet; `DspRenderersFactory` baut den Sink bereits selbst | hoch | mittel — greift in die Audio-Kette ein |
| D8 | **Underrun-Ueberwachung** verdrahten (`onAudioUnderrun` via `AnalyticsListener`) | Design 265 fordert es; `AudioInfoListener` existiert und hoert nur `onAudioTrackInitialized` | gering | keins |
| D9 | **Crossfade in der Landung nutzen** statt fest `0L` | `RestMusicCoordinator.kt:170` uebergibt konstant 0; der Planner kann Crossfade und der Deckel "Crossfade endet vor dem Drop" ist implementiert | gering | gering |

**Was NICHT empfohlen wird:**
- **Zwei parallele ExoPlayer** (Design 7.1a MVP): wurde bewusst entfernt
  (README Schritt 18), ADR-Konsolidierung. Ohne belegte Notwendigkeit nicht
  zurueckholen.
- **Neuronale Drop-Erkennung** (CUE-DETR): widerspricht dem
  Offline-/Nachvollziehbarkeitsgrundsatz und loest ein Problem, das mit
  Spectral Flux + Downbeat + manueller Bestaetigung gut genug loesbar ist.
- **Sample-genaue Ausrichtung**: unter der Wahrnehmungsschwelle, ADR-0012
  benennt es zu Recht als nicht erreichbar.

---

## Teil 4: Umsetzung

### 4.1 Der eine Satz, auf den alles hinauslaeuft

Alle drei Bereiche haben denselben Zustand: **der Code ist weiter als die
Messung.** Nichts in diesem Dokument empfiehlt, mehr Algorithmik zu bauen,
bevor die vorhandene messbar ist. Die drei Messvorschriften existieren schon
fertig im Repo und sind nie gelaufen:

| Messung | Vorschrift | blockiert |
|---|---|---|
| Gate 11b, 5 Szenarien x 3 Sessions | `tools/golden_shadow_corpus/README.md:42-98` | R3-R8, R12 |
| Test B1, Drop-Fehler je Route | `docs/HARDWARE_TESTPLAN.md:97-114` | D2-D9 |
| Test B9, AudioFocus | `docs/HARDWARE_TESTPLAN.md:188` | T1, T7, T8 |

### 4.2 Reihenfolge

**Stufe 0 — messbar machen (Voraussetzung fuer alles).**
R1 (Rohsamples in den Recorder). Ohne Rohsamples ist der Corpus ein
Abnahmeprotokoll, kein Regressionskorpus; Parameter-Sweeps, Driftmessung und
Regressionstests sind alle unmoeglich.

**Stufe 1 — messen (Zeit am Geraet, kein Code).**
R2 (Gate 11b) + D1 (Test B1) + T-Messungen (B9, plus der
`USAGE_*`-Vergleich aus T7). Ergebnis: die erste belastbare Zahl fuer
Zaehlgenauigkeit und Drop-Fehler in der Projektgeschichte.

**Stufe 2 — die belegten Defekte beheben.**
R3 (Ermuedungsdrift, 1.2) und D2 (Downbeat-Offset, 3.4). Beide sind keine
Verbesserungsideen, sondern Konstruktionsfehler mit Literaturbeleg und
Code-Fundstelle. Beide koennen mehr Fehler verursachen als alles, was in
Stufe 3 optimiert wuerde.

**Stufe 3 — Reserven heben, in dieser Reihenfolge.**
T1 (AudioFocus-Ducking — die einzige nicht erfuellte Anforderung des
Auftrags), D3 (`PlayerMessage`), R4+R5 (Sweep, dann Accel-Voting),
R6 (Zweitmeinung), R7 (Hebelarm-Messung), D9 (Crossfade), T2-T6.

**Stufe 4 — nur mit belegtem Bedarf.**
R8 (Parameter pro Uebung), R9 (Firmware), R10 (DBA), R11 (Protokoll v3),
R12 (Madgwick), D4 (Spectral Flux), D6 (Preroll), D7 (`Mode.EXACT`).

### 4.3 Zielwerte mit Messvorschrift

Kein Zielwert ohne die Angabe, wie er geprueft wird.

| Groesse | Zielwert | Messvorschrift |
|---|---|---|
| Rep-Zaehlung, exakt | 100 % ueber 5 Szenarien x 3 Sessions | Handzaehlung direkt nach jedem Satz, `shadow_harness.py` gegen `known_active_reps` |
| Rep-Zaehlung, +/-1 | 100 % | dieselbe Quelle; Vergleichswert Literatur: 91-93 % |
| Drop-Landung, P50 | \|Fehler\| <= 25 ms je Route | Video 120 fps, Abstand Go-Beep-Ende zu hoerbarem Drop, >= 3 Laeufe je Route (B1) |
| Drop-Landung, P95 | \|Fehler\| <= 50 ms je Route | dieselbe Messung; **Streuung ist das Ziel, nicht der Mittelwert** (3.3) |
| Route-Profil | `p50ErrorMs`/`p95ErrorMs` je Route gesetzt, `confidence = CALIBRATED` | `RouteProfileStore.upsert()` nach B1 |
| TTS-Ducking bei Fremdmusik | Musik hoerbar gedaempft, nie gestoppt | B9 gegen Spotify, YT Music, Poweramp; je Player protokollieren |
| TTS-Erstansage | < 300 ms nach Grenzwert | Logcat-Zeitstempel Grenzwert vs. `onStart`, 10 Sessions |
| Sensor-Stream | Paketverlust < 5 %, groesster Gap < 200 ms | `SensorHealth` waehrend Gate 11b mitloggen (Schwellen: `SensorHealth.kt:36-37`) |

Alle Zielwerte sind vor der ersten Messung **Absichten**. Der Corpus
entscheidet, welche davon realistisch sind — dieselbe Ehrlichkeit, die
`README.md:100` fuer den 1,5-s-Analysezielwert schon anwendet.

### 4.4 Was dieses Dokument bewusst nicht anfasst

- Herzfrequenz / Health Connect (eigener Plan, Phase 3 offen)
- Waveform-Performance (eigener Plan, ADR-0015)
- Mix-Uebergaenge jenseits der Drop-Landung
- UI/UX — ausser wo eine Anzeige die Messqualitaet verbessert (R6)
- Bit-Perfect / USB-DAC (ADR-0009, unabhaengig)

<a name="quellen"></a>
### 4.5 Quellen

**Rep-Zaehlung (Consensus / peer-reviewed).**
- Viecelli et al. 2020, PLoS ONE — Smartphone am Gewichtsstapel, 0,16 %
  Fehlerrate, TUT-Validierung.
- Pernek et al. 2013, Personal and Ubiquitous Computing — DTW, 3.598 Reps,
  ~1 % Miscount.
- Morris et al. 2014, CHI (RecoFit, 242 Zitate) — +/-1 Rep in 93 %,
  114 Probanden.
- Soro et al. 2019, Sensors 19(3):714 (131 Zitate) — CNN, +/-1 Rep in 91 %.
- Balestra et al. 2021, Digital Biomarkers / PMC8339513 — Accel 84,3 % ->
  Accel+Gyro 95,6 %.
- Lim et al. 2024, IEEE Access (uLift) — Autokorrelation als Gate, mittlerer
  Zaehlfehler 0,61, 35 Probanden.
- Zelman et al. 2020, J. Healthcare Engineering — Threshold+Lowpass gewinnt.
- Chang et al. 2007 (184 Zitate) — Peak vs. Viterbi/HMM, ~5 % Miscount.
- Yurtman & Barshan 2014 (77 Zitate) — MTMM-DTW, False Alarm < 1 %.
- Zhang et al. 2024, ICCSNT (ClassRAC) — Optimierung je Klasse, MAE 0,146.
- Filippou et al. 2023, Sensors — DTW Barycentre Averaging.
- Postlmayr et al. 2024, Smart Health (PersonalPT) — One-Shot-Segmentierung.
- Lim & Lee 2024, arXiv 2410.00407 — Few-Shot, 12,9 % exakt fehlerfrei.
- Nishino et al. 2022, Frontiers in Computer Science — Few-Shot, weakly
  supervised.
- Brennan et al. 2025, Sports Medicine — Systematic Review, 44 Studien.
- Weakley et al. 2021, Sports Medicine (153 Zitate) — Validitaet
  kommerzieller Geraete.

**Ermuedung / Velocity Loss.**
- Sanchez-Medina & Gonzalez-Badillo 2011, MSSE (752 Zitate).
- Pareja-Blanco et al. 2017, Scand J Med Sci Sports (451 Zitate) — VL20/VL40.
- Rodriguez-Rosell et al. 2020, JSCR (124 Zitate) — R = 0,97 / 0,93.
- Jukic et al. 2022, Sports Medicine (128 Zitate) — Meta-Analyse.
- Chung 2026, Applied Sciences — -15,6 % MCV ueber 5 Reps.
- Munoz-Lopez et al. 2021, J Biomechanics — Geschwindigkeit < Beschleunigung
  < Leistung.
- Moyen-Sylvestre et al. 2022, Sensors — Winkelgeschwindigkeit > Accel fuer
  Ermuedung.

**Abtastrate.**
- Fan et al. 2025, PMC11991382 — 100 Hz Gehen, 200 Hz Laufen, 50 Hz Huefte
  optimal.
- Villa et al. 2025, MDPI Sensors 26(1):162 — > 50-100 Hz bringt marginal.
- Phan et al. 2023, TechRxiv — 20 Hz reichten fuer Uebungsmonitoring.

**Audio / Android (Primaerquellen).**
- developer.android.com/media/optimize/audio-focus — MAY_DUCK,
  System-Ducking ab API 26, `setWillPauseWhenDucked`.
- developer.android.com/media/media3/exoplayer/listening-to-player-events —
  `createMessage` / `setPosition`.
- developer.android.com/ndk/guides/audio/audio-latency + AOSP CDD 5.6 —
  `low_latency` = <= 45 ms kontinuierlich.
- nift4.org/2025/08/09/android-audio-stack-music-player — AudioTrack-,
  AudioFlinger- und AudioSink-Pfade, ExoPlayer-1.8-Verhalten.
- android-developers.googleblog.com (Wear-OS-TTS, 2024-03) — ~10 s
  Kaltstart der Synthese-Engine.
- rtings.com — SBC-Latenz typisch 150-250 ms.

**Perzeption.**
- ACM MuC 2024, `10.1145/3678299.3678331` — JND 49/27/77 ms je Basislatenz.
- PMC6711538 — audiovisuelle Synchronitaet zu Musik, JND ~60 ms.
- Friberg & Sundberg — ~10 ms fuer kurze Toene in metrischer Sequenz.

**Drop- / Cue-Point-Erkennung.**
- Yadati et al., ISMIR 2014 — zweistufige Drop-Erkennung in EDM.
- Zehren et al., Computer Music Journal 46(3) 2022 / arXiv 2007.08411 —
  Cue-Point-Erkennung, 90-96 % brauchbar, Downbeat als Kern.
- Arguello et al., arXiv 2407.06823 (CUE-DETR) 2024.
- arXiv 2603.08759 (EDMFormer) — Referenzannotation +/-0,5 s.
- Mueller & Zalkow, TISMIR 2024 — Novelty-Funktionen, Grenzen von Spectral
  Flux.
- Boeck, SuperFlux/ComplexFlux — MIREX-Spitze 2016.

**BLE / Hardware.**
- ESP-FAQ (Espressif) — BLE-Durchsatz ~700 kbps auf 1M PHY, 1,4 Mbps mit
  2M PHY.
- ESP-IDF System Time — APB-Takt <= +/-10 ppm.
- Mouser-Specsheet M5StickC PLUS2 + docs.m5stack.com — ESP32-PICO-V3-02,
  Bluetooth 4.2, 200 mAh, MPU6886 via `M5Unified IMU_Class`.
- Punchthrough (BLE Connection Interval / ATT MTU / DLE).
- Oxeltech, 2025-12 — 100-Hz-IMU ueber BLE: Timestamp-Strategien,
  Dual-Buffer, Connect als kritischer Zustand, Watchdog, MTU-Rueckfall.
