# Umbauplan: Rep-Zaehlung, Sprachansagen und Drop-Sync

**Stand:** 2026-09-04
**Grundlage:** `docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md`
(Code-Bestandsaufnahme + Literaturrecherche; jede Zahl hier ist dort belegt)
**Status:** Entwurf, keine Phase begonnen

---

## 1. Ziel

Drei Bereiche verbessern, in dieser Prioritaet:

1. **BLE/IMU-Rep-Zaehlung** (M5StickC Plus2) — von "zaehlt, ungemessen" zu
   "zaehlt, gemessen, mit belegten Parametern".
2. **Workout-Timer mit Sprachansagen**, ohne die Musik zu stoppen — die
   Anforderung ist fuer die *eigene* Musik erfuellt, fuer *fremde* Player
   strukturell nicht.
3. **Drop-Sync** — Countdown-Ende trifft den Drop; Fehlerbudget kennen,
   Streuung senken, Marker-Qualitaet heben.

## 2. Der Satz, der diesen Plan begruendet

**In allen drei Bereichen ist der Code weiter als die Messung.**

Drei Messvorschriften liegen fertig im Repo und sind nie gelaufen:

| Messung | Vorschrift | blockiert |
|---|---|---|
| Gate 11b (5 Szenarien x 3 Sessions) | `tools/golden_shadow_corpus/README.md:42-98` | Phasen 2, 6, 7, 9 |
| Test B1 (Drop-Fehler je Route) | `docs/HARDWARE_TESTPLAN.md:97-114` | Phasen 3, 5 |
| Test B9 (AudioFocus) | `docs/HARDWARE_TESTPLAN.md:188` | Phase 4 |

`docs/Kritische Befunde.md` benennt die Ursache selbst: *"Ground-Truth-
Trace-Suite mit echten Sensordaten... Die Werkzeuge sind fertig
(`tools/shadow_harness.py`, `tools/recofit_bootstrap.py`), es fehlen
Aufnahmen mit echter Hardware."*

Dieser Plan beginnt deshalb **nicht** mit Algorithmik.

## 3. Geltung und Verhaeltnis zu anderen Dokumenten

- **Vorrang:** Dieser Plan konkretisiert
  `docs/design/REP_ZAEHLUNG_UMBAUPLAN_2026-08-12.md` (dessen Punkte 1-9 sind
  abgeschlossen) und `docs/design/SHADOW_DIFF_HARNESS_PLAN.md` (Schritte 2/3
  offen). Er hebt nichts davon auf.
- **Aufgehoben wird nichts.** ADR-0012 (Drop-Landung), ADR-0014
  (Live-Zaehlung ohne 11b), ADR-0017 (Accel-Schwelle aus Kalibrierung)
  bleiben in Kraft. Phase 6 erfuellt die in ADR-0017 als offen benannte
  Bedingung; Phase 1 erfuellt das in ADR-0014 benannte Ziel.
- **Neue ADRs** sind an drei Stellen Pflicht, siehe
  [Architekturentscheidungen](#architekturentscheidungen).
- **Nicht Teil dieses Plans:** Herzfrequenz/Health Connect
  (`HERZFREQUENZ_HEALTH_CONNECT_PLAN.md`), Waveform-Performance (ADR-0015),
  Bit-Perfect (ADR-0009), Mix-Uebergaenge jenseits der Drop-Landung.

## 4. Grundregeln (nicht verhandelbar)

1. **Kein neuer Parameter ohne Messvorschrift.** Wer eine Konstante
   einfuehrt, benennt im selben Commit, welcher Test sie belegt. Die drei
   Accel-Konstanten aus ADR-0017 sind das Negativbeispiel: *"an synthetischen
   Signalen gesetzt und an echten M5StickC-Traces noch nicht geprueft"*.
2. **Kein Zielwert ohne Messvorschrift.** Vor der ersten Messung sind alle
   Zielwerte **Absichten**, nicht Ergebnisse — genauso wie `README.md:100` es
   fuer den 1,5-s-Analysezielwert schon formuliert.
3. **Modulgrenzen bleiben.** `:domain:*` kennt kein Android, kein Room, kein
   Media3. Signalmathematik bleibt in `:domain:sensor` bzw. `:domain:audio`
   (ADR-0005). Kein Feature importiert ein anderes.
4. **Additiv, nie destruktiv.** Keine Spalte loeschen, kein Enum-Wert
   entfernen, kein Praeferenzschluessel umbenennen. Schema-Versionen steigen,
   Altdaten bleiben lesbar.
5. **Nach jeder Phase:** `./gradlew spotlessApply`, betroffene Unit-Tests,
   `./gradlew detekt lintDebug`, `:app:assembleDebug`. Ohne gruenen Lauf
   kein Fortschritt in die naechste Phase.
6. **Jede Phase traegt ihren Statuseintrag** in `docs/STATUS_FORTSCHRITT.md`
   und, wo der Status sich aendert, in `README.md`.
7. **Bei Widerspruch zwischen Plan und Code gewinnt die Messung**, nicht das
   Dokument.

<a name="architekturentscheidungen"></a>
## 5. Architekturentscheidungen

### 5.1 Entscheidungen ohne ADR-Pflicht (additiv, im Rahmen bestehender ADRs)

| Entscheidung | Begruendung |
|---|---|
| Rohsamples aus dem **bereits vorhandenen** `SetTrace.samples` schreiben, keine neue Erfassung | `SetTrace.kt:17` haelt die unveraenderliche Sample-Kopie schon; sie wird beim Bau des `ShadowDiffEvent` verworfen |
| Sample-JSONL im **bereits vom Harness gelesenen** Format `{"t":"sample","ts","gx","gy","gz"}` | `tools/shadow_harness.py:106` filtert schon auf `t == "sample"`, `:71-77` liest `ts`/`gx`/`gy`/`gz`. Kein Formatdesign noetig |
| Offline-Sweep als **JVM-Test** in `:domain:sensor`, nicht als Python-Skript | die Pipeline ist Kotlin; ein Python-Nachbau waere eine zweite Wahrheit (genau der Fehler, den `protocol.yaml:5-6` fuer das Wire-Format verbietet) |
| AudioFocus im **Cue-Pfad** (`:data:timer`), nicht im Playback-Service | der Playback-Service haelt bereits den Media-Focus (`PlaybackService.kt:116-124`); der Cue-Focus ist ein zweiter, transienter Request mit anderer Semantik |

### 5.2 ADR-pflichtig

**ADR-0020 — Erwartungswerte der Qualitaetsbewertung folgen einem
Driftmodell statt einem gleitenden Mittelwert.**
Betrifft `RepCounter.trackForAdaptation()` und `QualityScorer`. Hebt eine
implizite Annahme des Umbauplans 2026-08-12 (Punkt 6: "adaptive
Refraktaerzeit aus der mittleren Rep-Dauer") fuer die *Qualitaets*-Erwartungen
auf — die Refraktaerzeit selbst bleibt davon unberuehrt. Begruendung siehe
Phase 2.

**ADR-0021 — Cue-Ansagen fordern transienten AudioFocus mit Ducking an.**
Betrifft das Verhaeltnis zu fremden Playern. Der Bauplan-Grundsatz
"es wird nur der EIGENE Player veraendert, nie die Systemlautstaerke"
(`DuckingController.kt:10-11`) bleibt gueltig — der Focus-Request veraendert
keine Lautstaerke, er teilt dem System eine Absicht mit. Das ist eine
Erweiterung, kein Widerspruch, muss aber dokumentiert werden, weil es die
erste Stelle ist, an der die App auf fremde Audio-Sessions wirkt.

**ADR-0022 — Drop-Landung wird an einer Wiedergabeposition terminiert, nicht
per `delay()`.**
Betrifft `RestMusicCoordinator`. Praezisiert ADR-0012, hebt es nicht auf: die
dort genannte Groessenordnung "+/-100-200 ms" bleibt die ehrliche Aussage, bis
Test B1 gemessen hat. Die Entscheidung betrifft nur die *Terminierungs-
Primitive*.

**ADR-0023 (bedingt) — Sensorposition am Maschinen-Hebelarm.**
Nur schreiben, wenn Phase 8 einen belastbaren Vorteil zeigt. Beruehrt die
Semantik von `CalibrationProfile.deviceId` (`SensorModels.kt:80`) und das
Platzierungs-Tutorial.

## 6. Datenmodell-Aenderungen (Uebersicht)

Alle Aenderungen sind additiv.

| Was | Wo | Phase | Migration |
|---|---|---|---|
| JSONL-Eventtyp `"t":"sample"` | `ShadowSessionRecorder` (kein DB-Schema) | 0 | keine |
| JSONL-Eventtyp `"t":"set_window"` (Satzgrenzen in Sample-Zeit) | dito | 0 | keine |
| Manifest-Feld `samples_recorded: true` | `tools/golden_shadow_corpus/` | 0 | keine (Feld existiert im Schema) |
| `CalibrationProfile.driftModel` (2 Werte: Prominenz- und Dauer-Steigung je Rep) | `SensorModels.kt`, Schema v5 -> v6 | 2 | v5-Profile: Steigung 0, Verhalten wie heute |
| `AudioRouteProfile.p50ErrorMs` / `p95ErrorMs` **schreiben** | `RouteProfileStore` | 1 | Felder existieren (`AudioClock.kt:62-63`) |
| `Marker.beatGridOffsetMs` (nullable) | `core/model/.../Library.kt`, DB-Migration | 3 | null = heutiges Verhalten |
| `DuckingDb` statt `DuckingPercent` (dB-Skala) | `core/model/.../Enums.kt:111` | 4 | 0/50/100 % -> 0/-6/-96 dB, alter Schluessel bleibt lesbar |
| `CalibrationProfile.templateThreshold` / `minQualityScore` | `SensorModels.kt`, v6 -> v7 | 9 | Nullwerte fallen auf die globalen Defaults zurueck |
| `Marker.seekPrerollMs` (nullable) | `core/model/.../Library.kt` | 9 | null = direkt auf `positionMs` |

## 7. Phasenuebersicht

| Phase | Inhalt | Blockiert von | Aufwand | Status |
|---|---|---|---|---|
| **0** | Rohsamples aufzeichnen (messbar machen) | — | mittel | **Erledigt** (2026-09-04) |
| **1** | Messen: Gate 11b, Test B1, Test B9 | 0 | hoch (Geraetezeit) | Offen |
| **2** | Ermuedungsdrift-Modell (belegter Defekt) | 1 | mittel | Offen |
| **3** | Downbeat-Offset fuer Beat-Snap (belegter Defekt) | 1 | mittel | Offen |
| **4** | AudioFocus-Ducking + Ducking-Konsolidierung | 1 | gering | Offen |
| **5** | Drop-Landung an Wiedergabeposition + Underrun + Crossfade | 1 | gering | Offen |
| **6** | Offline-Sweep-Harness, danach Accel-Voting scharf | 0, 1 | mittel | Offen |
| **7** | Autokorrelations-Zweitmeinung sichtbar | 1 | gering | **Schritt A erledigt** (2026-09-04) |
| **8** | Messsession Hebelarm vs. Handgelenk | 0, 1 | gering | Offen |
| **9** | Nachrangiges (nur mit belegtem Bedarf) | 1-8 | variabel | Offen |

---

## Phase 0 — Rohsamples aufzeichnen

**Ziel:** Der Golden Corpus wird von einem Abnahmeprotokoll zu einem
Regressionskorpus. Ohne diese Phase sind Phase 2, 6, 7, 8 und 9 nicht
durchfuehrbar, weil kein Offline-Sweep und keine Driftmessung moeglich ist.

### 0.1 Warum das kleiner ist, als es klingt

Die Samples existieren bereits als unveraenderliche Kopie:

```kotlin
// SetTrace.kt:16-17
/** Unveraenderliche Sample-Kopie (Learn-Loop-Grundlage). */
val samples: List<SensorSample>,
```

`ActiveSetController.finishAndTakeTrace()` friert sie ein
(`ActiveSetController.kt:261`), `TrainViewModel.logSet()` holt sie ab
(`TrainViewModel.kt:276`) — und wirft sie weg, weil `ShadowDiffEvent` nur
Zaehlerstaende traegt (`ShadowSessionRecorder.kt:43-50`).

Das Zielformat ist ebenfalls bereits festgelegt, und zwar von der
Konsumseite: `tools/shadow_harness.py:106` filtert auf `t == "sample"`,
`:71-77` liest `ts`, `gx`, `gy`, `gz`. Das ist Absicht — die
Referenz-Korpus-Auswertung (RecoFit/MM-Fit) nutzt es schon.

Phase 0 ist damit **kein Formatdesign und keine neue Erfassung**, sondern
Schreibarbeit gegen ein existierendes, bereits gelesenes Format.

### 0.2 Aenderungen

**`feature/workout/.../shadow/ShadowSessionRecorder.kt`**

Vertrag erweitern (additiv, `NoOpShadowSessionRecorder` bleibt gueltig):

```kotlin
interface ShadowSessionRecorder {
    fun startSession(sessionId: String)
    fun recordSet(event: ShadowDiffEvent)

    /**
     * Schreibt die Rohsamples eines abgeschlossenen Sets plus die
     * Satzgrenze. Format wie von tools/shadow_harness.py gelesen
     * (t="sample" mit ts/gx/gy/gz, zusaetzlich ax/ay/az).
     * Wird NACH recordSet aufgerufen, damit die Satzgrenze auf ein
     * bereits geschriebenes set-Event verweist.
     */
    fun recordSamples(window: SampleWindow)

    fun endSession()
}

/**
 * Ein Satzfenster in Sample-Zeit. [setIndex] ist der 0-basierte Index des
 * Sets innerhalb der Session und stellt die Zuordnung zu
 * manifest.known_active_reps[i] her.
 */
data class SampleWindow(
    val setIndex: Int,
    val exerciseId: Long,
    val measuredSampleRateHz: Double,
    val samples: List<SensorSample>,
)
```

**`feature/workout/.../shadow/JsonlShadowSessionRecorder.kt`**

- `recordSamples` schreibt zuerst eine Fensterzeile, dann je Sample eine
  Zeile:

```
{"t":"set_window","setIndex":0,"exerciseId":7,"rateHz":49.8,"n":612,
 "tsFirst":12340,"tsLast":24560}
{"t":"sample","setIndex":0,"ts":12340,"ax":0.02,"ay":-0.98,"az":0.11,
 "gx":1.4,"gy":-0.6,"gz":0.2}
...
```

- **`setIndex` ist Pflicht in jeder Sample-Zeile.** Ohne ihn kann der
  Harness Samples nicht dem Satz zuordnen, dessen Wahrheit im Manifest
  steht — und ein Corpus ohne Zuordnung ist wieder nur ein Protokoll.
- **Ein Zaehler im Recorder** haelt `setIndex`; er wird in `startSession()`
  auf 0 gesetzt und in `recordSet()` inkrementiert. Damit tragen set-Event
  und Sample-Fenster denselben Index ohne Absprache mit dem Aufrufer.
- **Gepuffertes Schreiben.** `FileWriter` mit `BufferedWriter` umwickeln und
  **nur einmal am Fensterende** flushen, nicht je Zeile. Der heutige Code
  flusht nach jeder Zeile (`:43`); bei ~600 Samples je Satz waeren das 600
  Flushes.
- **Kein `flush()` je Sample, aber `flush()` am Fensterende.** Ein
  App-Absturz mitten im Satz verliert dann hoechstens dieses Fenster, nicht
  die Session.

**`feature/workout/.../TrainViewModel.kt` (um Zeile 281-290)**

Nach `shadowSessionRecorder.recordSet(...)` und **vor** `learnFromTrace`:

```kotlin
if (trace != null) {
    shadowSessionRecorder.recordSamples(
        SampleWindow(
            setIndex = -1, // vom Recorder gesetzt, siehe 0.2
            exerciseId = exercise.id,
            measuredSampleRateHz = trace.measuredSampleRateHz,
            samples = trace.samples,
        ),
    )
    learnFromTrace(trace, reps)
}
```

Reihenfolge ist wichtig: `recordSet` schreibt die bestaetigten Reps, danach
gehoeren die Samples desselben Satzes dazu. `learnFromTrace` bleibt letzter
Schritt, damit ein Fehler im Lernpfad die Aufnahme nicht verhindert.

**`tools/golden_shadow_corpus/README.md`**

- Abschnitt "Rohdaten-Schalter" (Zeile 63-65) korrigieren: der Recorder
  schreibt jetzt Rohdaten, `samples_recorded` ist `true`.
- Erwartete Dateigroesse dokumentieren: **~0,4 MB je Minute** aktiver
  Zaehlung (50 Hz x 6 Kanaele als JSONL). Eine 45-Minuten-Session mit
  ~15 Minuten Zaehlzeit liegt bei ~6 MB.

**`tools/shadow_harness.py`**

- `evaluate_session` um eine Sample-Konsistenzpruefung erweitern: fuer jedes
  `set`-Event mit passendem `set_window` pruefen, dass
  `n >= 150` (Untergrenze von `RepCountPlausibility.MIN_SAMPLES`) und dass
  `(tsLast - tsFirst) / (n - 1)` innerhalb 15-25 ms liegt. Abweichung
  bedeutet: die 20-ms-Firmware-Garantie hat in dieser Session nicht
  gehalten, und das Set ist fuer Parameter-Sweeps unbrauchbar.
- Der Report nennt je Session die gemessene Rate und die Anzahl Saetze mit
  Samples.

### 0.3 Tests

| Test | Modul | Prueft |
|---|---|---|
| `JsonlShadowSessionRecorderTest` (Robolectric) | `:feature:workout` | Datei entsteht, `setIndex` stimmt zwischen `set` und `set_window`, Sample-Zeilen sind gueltiges JSON, Flush am Fensterende |
| Erweiterung `TrainViewModelTest` | `:feature:workout` | `recordSamples` wird genau einmal je geloggtem Satz mit `trace.samples` aufgerufen; nicht bei `trace == null` |
| `shadow_harness.py --smoke-test` | Python | Sample-Pfad wird mit synthetischen Daten durchlaufen, Konsistenzpruefung greift bei absichtlich falschem Raster |

### 0.4 Abnahme

- Ein Debug-Build schreibt nach einem 3-Satz-Trockendurchlauf eine JSONL mit
  `session_start`, 3x (`set` + `set_window` + n x `sample`), `session_end`.
- `python3 tools/shadow_harness.py --corpus-dir <dir>` liest die Datei ohne
  Warnung und nennt die gemessene Abtastrate.
- Dateigroesse liegt in der dokumentierten Groessenordnung.

### 0.5 Risiko

Gering. Die Aenderung schreibt nur; kein Pfad der Zaehlung wird beruehrt.
Einziges reales Risiko ist Speicherdruck bei sehr langen Sessions — deshalb
die Groessenordnung dokumentieren und in Phase 1 gegenpruefen.

### 0.6 Umsetzung (2026-09-04)

Umgesetzt wie geplant, mit drei Abweichungen:

1. **`SampleWindow` traegt keinen `setIndex`.** Der Plan sah `setIndex = -1`
   als Platzhalter vom Aufrufer vor. Ein Feld, das der Aufrufer setzen muss
   und der Empfaenger sofort ueberschreibt, ist eine Einladung zum Fehler —
   das Feld ist deshalb ganz aus dem Datentyp entfernt. Der Recorder haelt
   `nextSetIndex` und leitet den Index aus dem Zaehler der `set`-Events ab;
   die Aufrufreihenfolge (`recordSet` vor `recordSamples`) steht als Vertrag
   im KDoc und wird im `TrainViewModelTest` ueber ein Aufrufprotokoll
   geprueft, nicht nur ueber die Nutzlast.
2. **Zwei zusaetzliche Abwehrfaelle im Recorder:** `recordSamples` ohne
   vorangegangenes `recordSet` (setIndex waere -1) und ein leeres
   Sample-Fenster schreiben nichts. Beides waere im Corpus als "Satz ohne
   Rohdaten" bzw. als Fenster ohne Satz erschienen und haette den Harness
   erst auf dem Rechner scheitern lassen.
3. **`:feature:workout` bekommt Robolectric.** Der Schreibpfad haengt an
   `Context.getExternalFilesDir`; ohne `isIncludeAndroidResources = true`
   und `libs.robolectric` ist das Format nicht testbar. Das Modul hatte
   bisher nur `isReturnDefaultValues`.

Die Harness-Pruefung ist bewusst **Diagnose, nicht Abnahme**: ein grobes
Sample-Raster macht das Set fuer Sweeps unbrauchbar, sagt aber nichts
darueber, ob die Zaehlung in diesem Satz richtig war. Sie steht deshalb im
Report und nicht im PASS/FAIL-Urteil.

**Verifikation:** `:feature:workout:testDebugUnitTest` 24 Tests gruen
(8 neue im `JsonlShadowSessionRecorderTest`, 14 im `TrainViewModelTest`
inkl. 2 neue), `spotlessCheck` und `detekt` gruen,
`python tools/shadow_harness.py --smoke-test` PASS mit vier neuen
Zusicherungen (gutes Raster ohne Meldung, grobes Raster gemeldet, zu wenige
Samples gemeldet, Fenster ohne Satz gemeldet).

Offen bleibt die Abnahme 0.4 — sie braucht einen Geraetelauf und faellt
damit in Phase 1.

---

## Phase 1 — Messen

**Ziel:** Die erste belastbare Zahl fuer Zaehlgenauigkeit, Drop-Fehler und
Ducking-Verhalten in der Projektgeschichte. **Kein Code in dieser Phase**
ausser dem Nachtragen der Messwerte.

### 1.1 Gate 11b — Rep-Zaehlung

Ablauf exakt nach `tools/golden_shadow_corpus/README.md:52-98`. Die fuenf
Szenarien:

| # | Szenario | Manifest-`scenario` | Erwartung |
|---|---|---|---|
| 1 | 5 Uebungen ohne Profil | `no_template` | Delta = 0 je Satz |
| 2 | 5 Uebungen nach echter Kalibrierung | `calibrated` | Delta = 0 je Satz |
| 3 | Langsame Wiederholungen (3-4 s Exzentrik) | `slow` | Delta = 0, keine vorzeitige Ablehnung |
| 4 | Sensor-Reconnect mitten in der Session | `reconnect` | Delta = 0 ab `reconnect_before_set` |
| 5 | Uebungswechsel mit verschiedenen Profilen | `exercise_switch` | Delta = 0, kein Template-Uebersprechen |

Die fuenf Uebungen sind namentlich festgelegt
(`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:34-38`): Bizeps-Curl,
Iso-Lateral Front Lat Pulldown, Iso-Lateral Incline Press, Plate Loaded
Iso-Lateral Row, Scott/Preacher Curls.

**Zusaetzlich zu protokollieren** (neu gegenueber dem bestehenden Testplan,
weil Phase 2 diese Daten braucht):

- `SensorHealth` je Satz: `recentPacketLossRate`, `largestGapMs`,
  `estimatedMissedBatches`, `jitterBufferDrops`. Schwellen zur Einordnung:
  UNRELIABLE ab 20 % Verlust oder 500 ms Gap, DEGRADED ab 5 % / 200 ms
  (`SensorHealth.kt:36-37`).
- `measuredSampleRateHz` je Satz (steht im `set_window`).
- `plausibility` je Satz (Autokorrelations-Zweitmeinung, `SetTrace.kt:35`) —
  gegen die Handzaehlung. Das ist die Datengrundlage fuer Phase 7.
- **Satz 3 des Szenarios `slow` als Driftreferenz**: Prominenz und Dauer je
  Rep aus den `repEvents`. Das ist die Messgroesse fuer Phase 2.

**Kritische Disziplinregel** (aus dem Corpus-README, Lehre aus dem leeren
Vorgaengerkorpus): Handzaehlung **direkt nach jedem Satz** notieren, nie
spaeter. Und: `known_active_reps` nur eintragen, wenn tatsaechlich von Hand
gezaehlt wurde — die D3-Regel (`confirmedRepsEdited`) ist das Sicherheitsnetz
gegen stille Selbstbestaetigung und darf nicht durch bequeme
Manifest-Eintraege umgangen werden.

### 1.2 Test B1 — Drop-Fehler je Route

Ablauf nach `docs/HARDWARE_TESTPLAN.md:97-114`. Je Route mindestens 3 Laeufe,
Messung per Video mit 120 fps: Abstand zwischen hoerbarem Go-Beep-Ende und
hoerbarem Drop.

Routen: interner Lautsprecher, Kabel-Kopfhoerer, Bluetooth SBC, Bluetooth
AAC/LDAC (falls verfuegbar), USB-DAC (falls vorhanden).

**Der Verdacht, der geprueft wird:** Die Tabellenzeile fuer BT-SBC ist
`LATENCY_BT_SBC_MS = 120L` (`RouteProfileStore.kt:171`). RTINGS gibt fuer
SBC typisch **150-250 ms** an. Trifft das zu, landet der Drop bei SBC
systematisch **30-130 ms zu frueh** — reproduzierbar und damit korrigierbar.

**Ergebnis eintragen** (das ist der einzige Code-Anteil dieser Phase):
- Gemessenen Median als `estimatedLatencyMs` je Route.
- `p50ErrorMs` und `p95ErrorMs` (Restfehler nach Korrektur).
- `confidence = CALIBRATED`.
- Ueber `RouteProfileStore.upsert()` (`:66`) — die Methode existiert und hat
  bisher **keinen Aufrufer** ausser Tests.

Bei systematischer Abweichung ausserdem die Tabellenkonstanten
(`RouteProfileStore.kt:169-174`) anpassen, damit ein frisches Geraet ohne
Kalibrierung nicht mit dem falschen Startwert beginnt.

### 1.3 Test B9 — AudioFocus und Ducking

Erweiterte Fassung von `docs/HARDWARE_TESTPLAN.md:188`. Fuer **jeden**
Fremdplayer getrennt protokollieren:

| Player | Ansage hoerbar? | Musik gedaempft? | Musik gestoppt? | Nach Ansage wieder laut? |
|---|---|---|---|---|
| Spotify | | | | |
| YouTube Music | | | | |
| Poweramp | | | | |
| eigener Player (Referenz) | | | | |

Zusaetzlich, weil daran Phase 4 haengt:
- **Ohne** Focus-Request (heutiger Zustand) messen, dann in Phase 4 erneut.
  Nur so ist der Effekt belegt statt behauptet.
- `USAGE_ASSISTANCE_SONIFICATION` gegen
  `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` vergleichen — der
  Verhaltensunterschied ist nicht dokumentiert und muss gemessen werden.
- **TTS-Erstansage-Latenz**: Logcat-Zeitstempel des Grenzwerts gegen
  `UtteranceProgressListener.onStart`. 10 Sessions, davon mindestens 3 nach
  einem Geraeteneustart (der Wear-OS-Blogpost nennt ~10 s bis die
  Synthese-Engine bereit ist).

### 1.4 Abnahme

Ein Freigabe-Protokoll in `docs/STATUS_FORTSCHRITT.md` mit:
- Harness-Report (Exact-Match-Rate, MAE je Szenario)
- Messtabelle je Audio-Route (Median, P50, P95)
- Ducking-Matrix je Fremdplayer, einmal ohne und (nach Phase 4) einmal mit
  Focus-Request
- Liste aller Abweichungen, jede einzeln von Adi freigegeben oder als
  Folgeaufgabe notiert

### 1.5 Abbruchkriterien fuer die Folgephasen

| Beobachtung | Konsequenz |
|---|---|
| Gate 11b bestanden (Exact 100 %, MAE 0) | Phase 6 (Accel-Voting) wird optional statt notwendig |
| Delta systematisch **negativ** (zu wenig gezaehlt) am Satzende | Phase 2 ist bestaetigt und wird vorgezogen |
| Delta systematisch **positiv** (zu viel gezaehlt) | Refraktaerzeit prueft (Phase 9), nicht Drift |
| Paketverlust > 5 % in mehreren Sessions | Phase 9 R9 (Firmware) wird vorgezogen |
| Drop-Fehler P95 > 100 ms auf Kabel/Speaker | Phase 5 wird vorgezogen |
| Drop-Fehler streut stark bei gleicher Route | Underrun-Ueberwachung (Phase 5) zuerst |
| Beat-Snap verschlechtert die Marker-Position spuerbar | Phase 3 wird vorgezogen |

### 1.6 Risiko

Keins technisch. Das Risiko ist organisatorisch: diese Phase kostet
Geraetezeit und liefert kein sichtbares Feature. Sie ist trotzdem die
Voraussetzung dafuer, dass alles Folgende nicht wieder "codeseitig
abgeschlossen, nie gemessen" endet.

---

## Phase 2 — Ermuedungsdrift statt gleitender Mittelwert

**ADR-Pflicht: ADR-0020.**

### 2.1 Der Defekt

`RepCounter.trackForAdaptation()` (`RepCounter.kt:264-284`) setzt die
Erwartungswerte der Qualitaetsbewertung auf den **gleitenden Mittelwert der
letzten (max. 10) Reps**:

```kotlin
recentDurationsMs.add(durationMs)
recentProminences.add(prominence)
if (recentDurationsMs.size > 10) { removeAt(0); ... }
if (recentDurationsMs.size >= 3) {
    qualityScorer.updateExpectations(
        expectedDurationMs = recentDurationsMs.average(),
        expectedProminence = recentProminences.average(),
    )
    peakDetector.updateExpectedDurationMs(avgDurationMs)
}
```

`QualityScorer.score()` (`QualityScorer.kt:42-47`) bildet daraus zwei von
vier Teilscores mit zusammen **45 % Gewicht**:

```kotlin
romRatio   = prominence / expectedProminence
romScore   = (1.0 - abs(romRatio - 1.0)).coerceIn(0.0, 1.0)    // 25 %
tempoRatio = durationMs / expectedDurationMs
tempoScore = (1.0 - abs(tempoRatio - 1.0)).coerceIn(0.0, 1.0)  // 20 %
```

**Was die Literatur dazu sagt** (Belege im Research-Dokument 1.2):
Geschwindigkeit und Amplitude fallen innerhalb eines Satzes **monoton**.
Sanchez-Medina & Gonzalez-Badillo 2011 (752 Zitate) validieren Velocity Loss
als Ermuedungsmass; Rodriguez-Rosell et al. 2020 finden R = 0,97
(Bankdruecken) bzw. 0,93 (Kniebeuge) zwischen relativem Velocity Loss und
Anteil ausgefuehrter Reps, untersucht ueber **15-65 % Verlust**.
Pareja-Blanco et al. 2017 behandeln 20 % und 40 % Verlust als gaengige
Trainingsvorgaben — Drift ist der Normalfall, nicht die Ausnahme.

**Drei Konsequenzen:**

1. **Die kalibrierten Profilwerte sind Dekoration.** Ab Rep 3 jedes Satzes
   ueberschreibt der Mittelwert `profile.expectedProminence` und
   `profile.expectedDurationMs` (uebergeben in
   `ActiveSetController.kt:110-111`). Die Guided Calibration bestimmt diese
   Werte damit nur fuer Rep 1 und 2.
2. **Die Teilscores messen Konsistenz, nicht Qualitaet.** Bei monoton
   fallender Amplitude liegt der Mittelwert per Konstruktion *ueber* dem
   aktuellen Wert. Eine saubere, aber ermuedete Rep sieht schlecht aus; eine
   geschludert-schnelle Rep sieht gut aus.
3. **Der Druck wirkt am Satzende.** Der Scorer ist ein Gate
   (`accepted = total >= 0.55`, `QualityScorer.kt:58`). Bei 20-40 % Drift
   verliert der Score grob 0,05-0,10 Punkte. Bei gutem DTW-Match bleibt er
   mit ~0,8 ueber der Schwelle — die Marge schrumpft aber ausgerechnet bei den
   letzten, haertesten Reps, und eine dort verlorene Rep faellt am meisten
   auf.

**Ehrliche Einordnung:** Punkt 3 ist eine Margen-, keine Ausfallaussage. Mit
realistischen Zahlen bricht die Zaehlung nicht. Punkt 1 und 2 sind dagegen
eindeutige Konstruktionsfehler: ein kalibrierter Wert, der nach zwei Reps
verworfen wird, und ein Qualitaetsmass, das Ermuedung als Formfehler liest.

### 2.2 Loesung

**Zwei Bausteine, unabhaengig voneinander wirksam.**

**Baustein A — einseitige Bewertung.** Eine Rep, die *langsamer* und
*kleiner* ist als erwartet, ist bei Ermuedung normal. Eine, die *schneller*
und *groesser* ist als jede vorherige, ist verdaechtig (Schwung,
Erschuetterung, Fremdbewegung). Der aktuelle Betragsabstand behandelt beide
Richtungen gleich.

`QualityScorer` erhaelt eine Toleranzasymmetrie:

```kotlin
class QualityScorer(
    // ...
    /**
     * Toleranz nach UNTEN (kleinere Prominenz / laengere Dauer). Ermuedung
     * bewegt beide Groessen in diese Richtung; Velocity-Loss-Vorgaben von
     * 20-40 % sind Normalfall (Pareja-Blanco et al. 2017), deshalb ist der
     * Korridor hier weit.
     */
    private val toleranceBelow: Double = 0.45,
    /**
     * Toleranz nach OBEN. Eine Rep, die deutlich groesser/schneller ist als
     * die Erwartung, ist kein Ermuedungseffekt, sondern ein Hinweis auf
     * Schwung oder eine Fremdbewegung.
     */
    private val toleranceAbove: Double = 0.20,
)
```

und die Scores werden

```kotlin
private fun oneSidedScore(ratio: Double): Double {
    val deviation = ratio - 1.0
    val tolerance = if (deviation < 0) toleranceBelow else toleranceAbove
    return (1.0 - abs(deviation) / tolerance).coerceIn(0.0, 1.0)
}
```

Fuer `tempoScore` ist die Richtung invertiert (laengere Dauer = kleinere
Geschwindigkeit = Ermuedungsrichtung), das muss der Aufrufer korrekt
zuordnen — hier lohnt ein Kommentar im Code, weil die Verwechslung nicht
auffaellt.

**Baustein B — Driftmodell als Erwartungsquelle.** Statt des Mittelwerts eine
**lineare Extrapolation** aus den bisherigen Reps des Satzes:

```kotlin
/**
 * Erwartungswert fuer die naechste Rep aus Startwert und beobachteter
 * Steigung je Rep. Bewusst linear: die Literatur beschreibt den
 * Velocity-Loss innerhalb eines Satzes als naeherungsweise monoton, und
 * ein hoehergradiges Modell waere bei 5-15 Reps ueberparametrisiert.
 */
class DriftEstimator(
    private val initial: Double,
    private val maxRelativeSlopePerRep: Double = 0.08,
) {
    fun expectedFor(repIndex: Int): Double
    fun observe(repIndex: Int, value: Double)
}
```

- Der **Startwert** kommt aus dem Profil (`profile.expectedProminence`,
  `profile.expectedDurationMs`) — damit ist die Kalibrierung wieder wirksam,
  nicht nur fuer 2 Reps.
- Die **Steigung** wird ab Rep 3 als robuste lineare Regression (oder
  einfacher: Median der Rep-zu-Rep-Differenzen) geschaetzt und auf
  `maxRelativeSlopePerRep` geklemmt, damit ein Ausreisser das Modell nicht
  kippt.
- Bei weniger als 3 Reps ist die Steigung 0 — Verhalten wie heute.

**Profil-Erweiterung (Schema v5 -> v6):**

```kotlin
data class CalibrationProfile(
    // ...
    /**
     * Beobachtete relative Aenderung der Peak-Prominenz je Rep innerhalb
     * eines Satzes (negativ = faellt). 0.0 = kein Modell (v5-Profile).
     */
    val prominenceSlopePerRep: Double = 0.0,
    /** Analog fuer die Rep-Dauer (positiv = wird laenger). */
    val durationSlopePerRep: Double = 0.0,
)
```

Gefuellt wird das aus dem Golden Corpus (Phase 1, Szenario `slow` und
`calibrated`) und danach vom Lernpfad nachgefuehrt
(`ProfileLearningPolicy`: 3 Sets bis Promotion, Rollback bei Diff 3 in
2 Sets — die Mechanik existiert und muss nicht angefasst werden).

### 2.3 Was ausdruecklich NICHT geaendert wird

- **Die Refraktaerzeit** bleibt am gleitenden Mittelwert
  (`PeakDetector.updateExpectedDurationMs`, `PeakDetector.kt:88-95`). Sie
  ist ein *unterer* Schutz gegen Doppelzaehlung; ein zu kleiner Wert ist
  hier die sichere Richtung, weil er nur mehr Kandidaten zulaesst, die die
  nachfolgenden Gates noch passieren muessen. ADR-0020 muss diese
  Unterscheidung explizit festhalten, sonst wird sie beim naechsten Refactor
  wieder eingezogen.
- **Der Pending-Fenster-Deckel** (2x erwartete Dauer, geklemmt
  1200..6000 ms, `RepCounter.kt:160`) bleibt. 2x ist bei realistischer Drift
  ausreichend generoes; eine Aenderung ohne Messung waere Raten.
- **Die Gewichte** 40/25/20/15 bleiben. Sie in derselben Phase zu aendern
  wuerde die Wirkung der Asymmetrie nicht mehr isolierbar machen.

### 2.4 Tests

| Test | Modul | Prueft |
|---|---|---|
| `QualityScorerAsymmetryTest` | `:domain:sensor` | -30 % Prominenz gibt hoeheren Score als +30 %; beide Extreme ausserhalb der Toleranz geben 0 |
| `DriftEstimatorTest` | `:domain:sensor` | monotone Reihe wird extrapoliert; Steigungsdeckel greift; < 3 Werte -> Steigung 0 |
| `RepCounterFatigueDriftTest` | `:domain:sensor` | synthetischer 12-Rep-Satz mit -30 % Prominenz und +25 % Dauer ueber den Satz: **alle 12 Reps** werden akzeptiert. Mit dem heutigen Mittelwert-Verhalten als Referenzlauf (Regressionsnachweis) |
| `ProfileCodecV6Test` | `:domain:sensor` / `:data:sensor` | v5-Profil laedt mit Steigung 0; v6-Roundtrip verlustfrei |
| Offline-Replay | via Phase 6 | derselbe Corpus-Trace mit alter und neuer Bewertung; Delta je Satz protokolliert |

### 2.5 Abnahme

- Der synthetische Driftsatz zaehlt vollstaendig, vorher und nachher
  dokumentiert.
- Auf dem Corpus aus Phase 1: **kein Satz verschlechtert sich**. Das ist die
  bindende Bedingung — eine Verbesserung im Mittel bei einzelnen
  Verschlechterungen ist nicht akzeptabel, weil eine verlorene Rep
  auffaelliger ist als eine gewonnene.
- ADR-0020 geschrieben, inklusive der Refraktaerzeit-Ausnahme aus 2.3.

### 2.6 Risiko

**Mittel.** Der Eingriff sitzt im Akzeptanz-Gate. Zwei Absicherungen:
- Die Aenderung **lockert** in der Ermuedungsrichtung und **straffft** nur
  gegen oben. Die haeufigere Fehlerrichtung (Rep verloren) wird dadurch
  seltener, nicht haeufiger.
- Der Referenzlauf gegen den Corpus macht jede Verschlechterung sichtbar,
  bevor sie im Gym auffaellt. Ohne Phase 0 und 1 ist diese Absicherung nicht
  verfuegbar — deshalb die Blockierreihenfolge.

---

## Phase 3 — Downbeat-Offset fuer das Beat-Snap

### 3.1 Der Defekt

`MarkerSnapping.snapToBeat()` (`MarkerSnapping.kt:22-32`) rastet
Marker-Positionen auf ein Beat-Raster, **das bei 0 ms beginnt**. Der Code
benennt das selbst als Spekulation:

```kotlin
// MarkerSnapping.kt:19-21
* Naechste Beat-Position zu [positionMs] bei [bpm], wenn sie innerhalb
* von [SNAP_WINDOW_MS] liegt; sonst null (kein Snap). Beat-Grid startet
* bei 0 — eine volle Offset-Kalibrierung waere genauer, ist aber ohne
* Downbeat-Analyse Spekulation.
```

**Die Groessenordnung macht es zum Defekt, nicht zur Unschoenheit.** Bei
128 BPM ist ein Beat 469 ms lang, ein halber Beat 234 ms. Das Snap-Fenster
ist 250 ms (`:14`). Ohne Offset kann der Snap die Marker-Position also um bis
zu einen halben Beat **verschieben** — mehr als das gesamte
Audio-Latenzbudget der Drop-Landung (25-150 ms je Route,
`RouteProfileStore.kt:169-174`).

Anders gesagt: das Beat-Snap kann heute mehr Fehler einbringen als alle
Latenzterme zusammen. Es ist ein Feature, das die Praezision *verschlechtern*
kann.

Zusatzbefund: `TempoAccumulator` (`MixAnalysis.kt`) schaetzt BPM aus einem
Onset-Intervall-Histogramm mit Oktav-Faltung und einem Konfidenz-Gate von
0,25 (`MixAnalysis.kt:389`). Ein falsch gefalteter BPM-Wert (halb oder
doppelt) erzeugt ein Raster mit falscher Teilung — der Snap rastet dann
konsistent auf Zwischenpositionen.

### 3.2 Loesung

**Baustein A — Phase des Beat-Rasters schaetzen.** Die Analyse hat die
Onset-Positionen bereits (`TempoAccumulator` sammelt sie fuer das
Intervall-Histogramm). Aus BPM und Onset-Liste laesst sich die Phase
bestimmen, ohne neue Signalverarbeitung:

```kotlin
// :domain:audio, neue reine Funktion
/**
 * Schaetzt den Offset des Beat-Rasters gegen 0 ms.
 *
 * Verfahren: fuer jede Kandidaten-Phase (Raster in N Schritten ueber ein
 * Beat-Intervall verschoben) die Summe der Abstaende aller Onsets zum
 * naechsten Rasterpunkt bilden; die Phase mit der kleinsten Summe gewinnt.
 * Das ist eine Kreuzkorrelation zwischen Onset-Impulsfolge und Beat-Kamm
 * und braucht kein Downbeat-Modell — nur die Beat-Phase, nicht den
 * musikalischen Taktanfang.
 *
 * [confidence] ist 1 - (bester Abstand / Abstand bei Gleichverteilung).
 * Unter [MIN_PHASE_CONFIDENCE] liefert die Funktion null, und das Snap
 * bleibt aus.
 */
object BeatPhaseEstimator {
    const val PHASE_STEPS: Int = 48
    const val MIN_PHASE_CONFIDENCE: Float = 0.35f

    fun estimate(onsetPositionsMs: List<Long>, bpm: Float): BeatPhase?
}

data class BeatPhase(val offsetMs: Long, val confidence: Float)
```

**Wichtige Abgrenzung, die im Code stehen muss:** Das ist eine
**Beat**-Phase, kein **Downbeat**. Ein Downbeat (Taktanfang) braucht
metrische Analyse; Zehren et al. (Computer Music Journal 46(3), 2022) nennen
Downbeat-Erkennung als Kernbestandteil ihrer Cue-Point-Erkennung und
erreichen damit *"about 90 percent of the points... reliably used in the
context of a DJ mix"*. Fuer das Snap genuegt aber die Beat-Phase: der Marker
soll auf *einen* Beat rasten, nicht auf den ersten Beat eines Takts. Die
Namensgebung im Code muss diesen Unterschied tragen, sonst wird spaeter mehr
erwartet, als die Funktion leistet.

**Baustein B — persistieren.**

```kotlin
// core/model/.../Library.kt
data class Marker(
    // ...
    /**
     * Offset des Beat-Rasters gegen 0 ms, aus der Track-Analyse. null =
     * unbekannt, dann rastet das Snap nicht (statt auf ein Raster zu
     * rasten, dessen Phase geraten ist).
     */
    val beatGridOffsetMs: Long? = null,
)
```

Genauer: der Offset gehoert **an den Track**, nicht an den Marker — alle
Marker eines Songs teilen ein Raster. Richtiger Ort ist deshalb
`track_analysis` neben `bpm` und `camelot_key`, mit einer DB-Migration
analog zu MIGRATION_9_10 und einer Erhoehung von
`MIX_ANALYZER_VERSION` (`TrackAnalyzer.kt:155`, aktuell 1) — **nicht**
`ANALYZER_VERSION` (aktuell 4), damit die Waveform-Buckets nicht neu
berechnet werden muessen. Die Zwei-Stufen-Trennung aus ADR-0015 macht genau
das moeglich.

**Baustein C — Verhalten aendern.**

```kotlin
fun snapToBeat(positionMs: Long, bpm: Float?, gridOffsetMs: Long?): Long? {
    if (bpm == null || bpm !in 30f..300f) return null
    // Ohne bekannte Phase nicht rasten: ein Raster mit geratener Phase
    // kann die Position um bis zu einen halben Beat verschieben (234 ms
    // bei 128 BPM) und damit mehr Fehler einbringen als die gesamte
    // Audio-Latenzkette.
    val offset = gridOffsetMs ?: return null
    // ...
}
```

Das ist eine **Verhaltensaenderung**: bis die Analyse den Offset geliefert
hat, rastet das Snap nicht mehr. Das ist beabsichtigt und die sichere
Richtung — kein Snap ist besser als ein falsches Snap. Die Haptik beim
Einrasten (`MarkerSnapping`-Aufrufer in der Waveform) bleibt unveraendert,
sie feuert dann eben seltener.

### 3.3 Warum die Onset-Erkennung selbst hier NICHT angefasst wird

Die aktuelle Novelty-Funktion ist die positive RMS-Energie-Differenz
(`OnsetDetection.kt:60-67`). Die Literatur ist eindeutig, dass Spectral Flux
besser ist — Mueller & Zalkow (TISMIR 2024) sagen sogar ueber Spectral Flux:
*"In many situations, spectral flux fails to reliably detect note onsets"*,
und reine Energie-Novelty liegt darunter.

**Trotzdem gehoert der Wechsel nicht in diese Phase**, aus drei Gruenden:
1. Er erhoeht `ANALYZER_VERSION` und erzwingt eine Re-Analyse der gesamten
   Bibliothek — ein eigener Umbau mit eigener Performance-Betrachtung
   (ADR-0015-Umfeld).
2. Der Offset-Fehler (bis 234 ms) ist groesser als der zu erwartende Gewinn
   an Onset-Genauigkeit (Fenstergroesse ist 25 ms,
   `TrackAnalyzerImpl.kt:318`).
3. Fuer die *Phasenschaetzung* genuegen die vorhandenen Onsets: eine
   Kreuzkorrelation ueber viele Onsets ist gegen einzelne Fehldetektionen
   robust.

Spectral Flux bleibt Phase 9 (D4). Als Zwischenschritt existiert bereits ein
besseres Mass im Code, das nichts kostet: `LoudnessAccumulator`
(`TrackAnalyzerImpl.kt:103`) berechnet LUFS. Kurzzeit-LUFS ueber 3 s
(EBU R128) ist perzeptiv gewichtet und damit naeher am gefuehlten Drop als
RMS — das ist in Phase 9 zu pruefen, nicht hier.

### 3.4 Tests

| Test | Modul | Prueft |
|---|---|---|
| `BeatPhaseEstimatorTest` | `:domain:audio` | synthetische Onset-Folge bei 120 BPM mit bekanntem Offset 137 ms wird auf +/- ein Phasenschritt genau geschaetzt; gleichverteilte Onsets liefern null (Konfidenz zu niedrig); leere Liste liefert null |
| `MarkerSnappingTest` (erweitern) | `:feature:player` | ohne Offset kein Snap; mit Offset rastet auf offset + n * beatMs; Fenster 250 ms bleibt |
| `MixAnalysisBeatPhaseTest` | `:domain:audio` | Phase wird nur bei ausreichender BPM-Konfidenz (>= 0,25) ueberhaupt berechnet |
| Migrationstest | `:core:database` | `MIGRATION_<n>_<n+1>` gegen das exportierte JSON-Schema; Altzeilen haben `beat_grid_offset_ms = NULL` |

### 3.5 Abnahme

- Fuer 5 Songs mit manuell gesetzten Markern: die geschaetzte Phase stimmt
  mit dem gehoerten Beat ueberein (Hoertest, keine automatische Pruefung
  moeglich).
- Ein Marker, der ohne Snap richtig lag, wird durch das Snap **nicht**
  verschlechtert. Vorher-Nachher je Marker protokollieren.
- Ohne Offset (Altdaten) verhaelt sich die App wie vor der Aenderung, minus
  Snap.

### 3.6 Risiko

**Gering bis mittel.** Die Analyse-Erweiterung ist additiv und laeuft in der
Metadatenstufe (`MIX_METADATA`), also nicht im UI-kritischen Pfad
(ADR-0015). Das Risiko liegt in der DB-Migration und darin, dass das Snap
zunaechst haeufiger *nicht* greift — was erklaerungsbedaerftig ist, aber
korrekt.

---

## Phase 4 — Sprachansagen gegen fremde Musik

**ADR-Pflicht: ADR-0021.**

### 4.1 Der Befund

Die Anforderung "Sprachansagen, ohne die Musik zu stoppen" hat auf Android
**zwei** Antworten, und nur eine ist erfuellt.

**Fall A — eigene Musik: erfuellt und sauber gebaut.** Musik laeuft im
eigenen `PlaybackService`; die Ansage senkt sie am Preamp-Knoten der eigenen
64-Bit-DSP-Kette ab (`PlayerVolumeGateImpl.kt:9-12` ->
`AudioPipeline.setDuckingGain` -> `MasterDspProcessor`). Kein
Focus-Konflikt, weil derselbe Prozess. Kollisionsfrei zu DVC und
Nutzerlautstaerke. Das bleibt unveraendert.

**Fall B — fremde Musik (Spotify, YT Music, Poweramp): strukturell blind.**
Es gibt **keinen einzigen** `requestAudioFocus`-Aufruf im Produktionscode;
der einzige Treffer im Repo ist
`app/src/androidTest/.../AudioFocusPermanentLossInstrumentedTest.kt`.

Heutiges Verhalten in Fall B:
- `TtsSpeaker` setzt `USAGE_ASSISTANCE_SONIFICATION` (`TtsSpeaker.kt:46`)
- `CountdownBeepPlayer` setzt `USAGE_ASSISTANCE_SONIFICATION` +
  `CONTENT_TYPE_SONIFICATION` (`CountdownBeepPlayer.kt:42-43`)
- Kein Focus-Request -> die fremde App erfaehrt nichts -> sie duckt nicht
- Ergebnis: Ansage und Beeps laufen **parallel zur ungedaempften** fremden
  Musik. Die Musik stoppt nicht (Teilziel erfuellt), aber die Ansage ist im
  Gym mit Kopfhoerern schlecht verstaendlich (Hauptziel verfehlt).

Der bestehende Ducking-Pfad hilft hier prinzipiell nicht: er setzt den Gain
der **eigenen** DSP-Kette, durch die fremde Musik nie laeuft.

### 4.2 Was die Plattform vorsieht

Aus `developer.android.com/media/optimize/audio-focus` (Primaerquelle):

- `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` ist genau dieser Fall. Die Doku nennt
  als Beispiel ausdruecklich hoerbare Navigationsanweisungen: *"Ducking is
  particularly suitable for apps that use the audio stream intermittently,
  such as for audible driving directions."*
- **Seit Android 8.0 (API 26) duckt das System selbst**: *"the system can
  duck and restore the volume without invoking the app's callback"* und
  *"By having the system implement ducking, you don't have to implement
  ducking in your app."* Bei `minSdk 26` gilt das fuer alle unterstuetzten
  Geraete.
- Der Fokushalter erhaelt bei MAY_DUCK typisch **keinen** Callback — ausser
  er hat `setWillPauseWhenDucked(true)` gesetzt, dann pausiert er.
- Konvention aus dem offiziellen Blog: eine MAY_DUCK-Anfrage sollte das
  Audiosystem **nicht laenger als 15 Sekunden** halten. Die laengste Ansage
  ist "3 Minuten" — weit darunter.

**Grenzen, die im ADR stehen muessen:**
- Ducking ist eine **Bitte**. Es gibt Apps und Nutzereinstellungen, die sie
  ignorieren. Test B9 muss das je Player belegen.
- Die Absenkungstiefe ist Systemsache, nicht app-konfigurierbar. Ein
  belastbarer dB-Wert liess sich aus keiner Primaerquelle belegen —
  **Annahme, von der App aus nicht messbar.**
- `setWillPauseWhenDucked(false)` ist der Default; explizit setzen, damit die
  Absicht im Code steht.

### 4.3 Aenderungen

**Neu: `data/timer/.../CueAudioFocusGate.kt`**

```kotlin
/**
 * Transienter AudioFocus fuer Cue-Ausgaben (ADR-0021).
 *
 * Zweck ist ausschliesslich, FREMDEN Playern mitzuteilen, dass sie fuer die
 * Dauer einer Ansage leiser werden sollen. Die EIGENE Musik wird davon nicht
 * beruehrt — sie laeuft im selben Prozess und wird weiterhin ueber den
 * Preamp-Knoten geduckt (Bauplan 5.3, DuckingController).
 *
 * Deshalb: kein Verzicht auf den bestehenden Ducking-Pfad, sondern ein
 * zweiter, unabhaengiger Kanal fuer fremde Sessions.
 *
 * Der Request ist an die Cue-Session gebunden — dieselbe Regel wie im
 * DuckingController: eine veraltete Antwort darf den Fokus einer neuen
 * Session nicht freigeben.
 */
class CueAudioFocusGate(context: Context) {
    /** true, wenn der Fokus gewaehrt wurde (oder verzoegert gewaehrt wird). */
    fun request(cueSessionId: String): Boolean
    fun abandon(cueSessionId: String)
}
```

Aufbau des Requests:

```kotlin
AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    .setAudioAttributes(cueAttributes)
    .setWillPauseWhenDucked(false)   // System duckt, wir pausieren nicht
    .setOnAudioFocusChangeListener(listener)
    .build()
```

**`data/timer/.../AndroidCueOutput.kt`**

Der Focus umschliesst die gesamte Cue-Ausgabe, nicht nur die Sprache — Beeps
und Go-Ton gehoeren dazu:

```kotlin
override fun speak(cueSessionId: String, secondsRemaining: Int) {
    if (!settings.ttsEnabled || !tts.isAvailable()) return
    val text = formatter.format(secondsRemaining)
    scope.launch {
        focusGate.request(cueSessionId)          // NEU
        val ducked = ducking.beginCue(cueSessionId, settings.duckingDb)
        val spoken = tts.speak(cueSessionId, text)
        if (!spoken) {
            if (ducked) ducking.endCue(cueSessionId)
            focusGate.abandon(cueSessionId)      // NEU
        }
        // Erfolgreiche Ansagen geben Fokus im onCueFinished-Pfad frei.
    }
}
```

und in `stopAll` sowie im `onCueFinished`-Pfad (`TtsSpeaker`-Callback ->
`DuckingController.endCue`) muss `abandon` mitgezogen werden. **Kritisch:**
jeder Pfad, der heute `ducking.endCue`/`ducking.abort` ruft, braucht ein
`focusGate.abandon`. Ein nicht freigegebener transienter Fokus laesst fremde
Musik dauerhaft geduckt — das ist die schlimmste moegliche Fehlwirkung dieser
Phase und gehoert in den Test.

**`data/timer/.../CountdownBeepPlayer.kt`**

Der Go-Beep ist der zeitkritische Punkt. Der Focus-Request muss **vor** dem
Countdown liegen, nicht beim Beep — ein Request kostet einen Binder-Roundtrip
und wuerde den Go verspaeten. Vorschlag: der Focus wird beim 3-2-1-Vorlauf
angefordert und erst nach dem Go-Beep freigegeben (Gesamtdauer ~3,5 s, weit
unter 15 s).

**`core/model/.../Enums.kt` — Ducking auf dB umstellen**

Heute: `DuckingPercent { NONE=0, HALF=50, FULL=100 }` (`Enums.kt:111-119`),
validiert in `DuckingController.kt:34`. Umgerechnet ist HALF = **-6,0 dB**,
FULL = Stille.

Das kollidiert mit dem Rest-Ducking, das in dB arbeitet mit Default **-8 dB**
und feiner Auflaesung -12..0 (`DuckingMixer.kt:21`,
`AudioPipeline.kt:202-203`). Da beide per `min()` kombiniert werden
(`MasterDspProcessor.kt:142`), gewinnt bei aktivem Rest-Ducking das
Rest-Ducking — die Ansage wird also **nicht** zusaetzlich hervorgehoben,
obwohl sie der Anlass war. Kein Bug, aber ein logischer Bruch.

```kotlin
/**
 * Ducking-Tiefe fuer Cue-Ansagen in dB. Ersetzt DuckingPercent
 * (0/50/100 %), damit Cue- und Rest-Ducking auf derselben Skala liegen und
 * die min()-Kombination eine sinnvolle Rangfolge ergibt.
 *
 * Migration: 0 % -> 0 dB, 50 % -> -6 dB, 100 % -> -96 dB (praktisch Stille).
 * Der alte Praeferenzschluessel bleibt lesbar; beim ersten Schreiben wird
 * der neue gesetzt.
 */
object DuckingDb {
    const val MIN_DB = -24.0
    const val MAX_DB = 0.0
    const val DEFAULT_DB = -12.0   // staerker als Rest-Ducking (-8 dB)
    fun isValid(db: Double): Boolean = db in MIN_DB..MAX_DB
}
```

Der Default -12 dB ist bewusst **staerker** als das Rest-Ducking, damit eine
Ansage waehrend der Pausenmusik hoerbar wird. Der Wert ist eine Absicht und
gehoert in Test B9 gegengehoert.

**Ducking-Rampe konsolidieren (T3/T4)**

Zwei Implementierungen derselben Sache:
- `DuckingRamp` (`DuckingMixer.kt:65-82`, Attack 40 ms / Release 200 ms) hat
  **keinen Aufrufer** im Produktionscode.
- `AudioPipeline.rampDuck()` (`:142-158`) ist eine fest verdrahtete Schleife:
  2 Schritte Attack / 8 Schritte Release je 20 ms = 40 / 160 ms.

Und: das **Cue**-Ducking hat gar keine Rampe — `setDuckingGain()` wirkt
sofort (`AudioPipeline.kt:115-119`), obwohl Design Phase 7 fuer beide
Attack 20-50 ms / Release 150-300 ms verlangt.

Vorgehen: `DuckingRamp` wird die **eine** Quelle, `rampDuck()` nutzt sie,
Cue-Ducking bekommt sie ebenfalls. Damit verschwindet der toten Code und die
Design-Vorgabe gilt fuer beide Quellen.

**TTS-Warm-up (T5)**

`TtsSpeaker.initialize()` ist nicht-blockierend (`:28`), aber es gibt keinen
Warm-up. Der erste Cue einer Session traegt damit die volle
Initialisierungslatenz — der Wear-OS-Blogpost von Google (2024-03) nennt
~10 s bis die Synthese-Engine nach einem Boot bereit ist.

Loesung: beim Sessionstart eine leere/kurze Aeusserung mit
`KEY_PARAM_VOLUME = 0` in die Queue geben. Kein hoerbarer Effekt, aber die
Engine ist geladen.

**TTS-Sperre vor der Drop-Landung (T6)**

Design 257 verlangt: *"Coordinator pausiert TTS waehrend Crossfade End Phase
500ms fuer sauberen Drop."* Nicht umgesetzt. Eine Ansage, die zufaellig in die
Landung faellt, duckt genau in dem Moment, in dem der Drop voll knallen soll.

Umsetzung: `AndroidCueOutput.speak()` prueft ein Flag, das der
`RestMusicCoordinator` ueber einen Domainvertrag setzt (analog
`RestDuckingGate`) — Feature importiert kein Feature, die Kopplung laeuft
ueber `:domain:playback`.

### 4.4 Tests

| Test | Modul | Prueft |
|---|---|---|
| `CueAudioFocusGateTest` (Robolectric) | `:data:timer` | Request/Abandon paarweise; fremde Session-ID gibt nicht frei; doppeltes Abandon ist harmlos |
| `AndroidCueOutputFocusTest` | `:data:timer` | **jeder** Pfad, der Ducking beendet, gibt auch Fokus frei: erfolgreiche Ansage, fehlgeschlagene Ansage, `stopAll`, Fehler-Callback |
| `DuckingDbMigrationTest` | `:data:timer` | 0/50/100 -> 0/-6/-96 dB; unbekannter Altwert faellt auf Default |
| `DuckingMixerTest` (erweitern) | `:domain:playback` | Cue -12 dB gewinnt gegen Rest -8 dB; `min()` bleibt, nie additiv |
| `DuckingRampTest` | `:domain:playback` | Attack schneller als Release; monoton; Endwert exakt erreicht |
| Test B9 (Geraet) | — | Matrix je Fremdplayer, einmal ohne und einmal mit Focus |

### 4.5 Abnahme

- **Vorher/Nachher-Matrix** aus Test B9 je Fremdplayer. Das ist die
  Kernabnahme: die Anforderung "ohne die Musik zu stoppen" ist erst belegt,
  wenn fuer jeden getesteten Player "gedaempft: ja / gestoppt: nein" steht.
- Keine dauerhaft geduckte Fremdmusik nach 20 Cue-Zyklen (Fokus-Leak-Test am
  Geraet).
- Countdown-Beeps bleiben zeitlich unveraendert (der Go darf durch den
  Focus-Request nicht spaeter kommen) — mit Video 120 fps gegen einen Lauf
  vor der Aenderung.
- ADR-0021 geschrieben, mit der Grenze "Ducking ist eine Bitte" und dem
  gemessenen Verhalten je Player.

### 4.6 Risiko

**Gering technisch, mittel im Verhalten.** Der gefaehrlichste Fehlerfall ist
ein **nicht freigegebener Fokus** — dann bleibt fremde Musik dauerhaft leise,
und der Nutzer wird die App dafuer verantwortlich machen (zu Recht). Deshalb
deckt `AndroidCueOutputFocusTest` alle Beendigungspfade ab, nicht nur den
Erfolgsfall.

---

## Phase 5 — Drop-Landung an einer Wiedergabeposition

**ADR-Pflicht: ADR-0022.**

### 5.1 Der Defekt

Die Drop-Landung wird per Coroutine-`delay` terminiert
(`RestMusicCoordinator.kt:177-197`):

```kotlin
landingJob = scope.launch {
    delay(plan.startAfterDelayMs)
    // ... Generation/Snapshot pruefen ...
    playbackRepository.playSongAt(workSong, plan.startAtPositionMs)
}
```

Damit haengt die Genauigkeit am Coroutine-Dispatcher, nicht an der
Audio-Frame-Uhr. Media3 bietet fuer genau diesen Fall die richtige
Primitive — `ExoPlayer.createMessage(target).setPosition(positionMs)` liefert
ein `PlayerMessage`, das der Player **auf dem Playback-Thread an einer
Wiedergabeposition** ausfuehrt (Android-Doku "Player events"). Grep ueber alle
`.kt`: **kein Treffer** ausser dem Kommentar in `TimerEngine.kt:20`, der die
Primitive bereits als Soll beschreibt.

### 5.2 Das Fehlerbudget, das dahinter steht

| Term | Groesse | heute kompensiert? |
|---|---|---|
| Timer-Zeitbasis (monoton) | < 1 ms | entfaellt |
| `delay()`-Genauigkeit | ~1-15 ms typ., unter Doze mehr | **nein** |
| Decoder-/Seek-Einstieg | bis zu einem Frame-Raster | **nein** (kein `seekPreroll`) |
| AudioTrack-Pufferfuellung | ~20-100 ms | **nein** |
| Route-Latenz | 25-150 ms | **ja**, per Tabelle |
| BT-Codec-Streuung | real 120-250 ms bei SBC | teilweise (Tabelle sagt 120) |
| Marker-Position | Fenster 25 ms | strukturell begrenzt |
| Beat-Grid-Offset | bis 234 ms bei 128 BPM | **nein** -> Phase 3 |

Android-CDD 5.6 definiert `low_latency` als **kontinuierliche** Ausgabelatenz
<= 45 ms; die *kalte* Latenz (erste Frames nach Leerlauf) ist ausdruecklich
eine eigene, hoehere Groesse. Die Drop-Landung ist genau der kalte Fall, wenn
zwischen Rest-Musik und Work-Titel ein harter Wechsel liegt.

### 5.3 Was Praezision wert ist (Zielwertbegruendung)

- **JND fuer Audio-Latenz** (ACM MuC 2024, `10.1145/3678299.3678331`):
  mittlere gerade wahrnehmbare Differenz **49 ms** bei 0 ms Basislatenz,
  **27 ms** bei 64 ms Basis, **77 ms** bei 512 ms Basis. Nichtlinear, im
  mittleren Bereich am empfindlichsten.
- **Audiovisuelle Synchronitaet zu Musik** (PMC6711538): JND ~**60 ms**.
- **Zeitverschiebung eines Tons in metrischer Sequenz** (Friberg &
  Sundberg): ~**10 ms** — strengste gefundene Schwelle, gilt fuer isolierte
  Klicks, nicht fuer einen Drop in dichter Musik.

**Daraus die Zielwerte:** Anspruch **P50 <= 25 ms**, Akzeptanz
**P95 <= 50 ms**. Sample-genaue Ausrichtung waere Verschwendung; ADR-0012
formuliert das bereits richtig.

**Das eigentliche Qualitaetsziel ist die Streuung, nicht der Mittelwert.** Ein
konstanter Offset von 80 ms ist unhoerbar — der Nutzer kennt den Bezugspunkt
nicht. Ein zwischen -50 und +150 ms schwankender Offset ist sofort als
"unzuverlaessig" erlebbar. `AudioRouteProfile` hat dafuer die richtigen
Felder (`p50ErrorMs`, `p95ErrorMs`, `AudioClock.kt:62-63`) — beide werden
nirgends geschrieben, was Phase 1 behebt.

### 5.4 Aenderungen

**`domain/playback/.../PlaybackRepository.kt` — neuer Vertrag**

```kotlin
/**
 * Terminiert einen Titelwechsel an einer Wiedergabeposition des LAUFENDEN
 * Titels, statt ihn per Wanduhr zu verzoegern (ADR-0022).
 *
 * Die Ausfuehrung liegt beim Player (Media3 PlayerMessage) und damit auf
 * der Audio-Zeitbasis. [generation] entwertet veraltete Auftraege, wenn
 * Skip, Satzwechsel oder Route-Wechsel dazwischenkommen.
 *
 * Rueckgabe ist ein Handle zum Abbrechen; ein nicht abgebrochener Auftrag
 * feuert genau einmal.
 */
suspend fun scheduleSongAt(
    triggerPositionMs: Long,
    song: Song,
    startAtPositionMs: Long,
    generation: PlaybackGeneration,
): AppResult<ScheduledLanding>
```

**`data/playback/.../PlaybackService.kt`** — Custom-Kommando analog
`ACTION_PLAY_SONG_AT` (`PlaybackCommands.kt`), das im Service

```kotlin
exoPlayer.createMessage { _, _ -> /* playSongAt-Logik */ }
    .setPosition(triggerPositionMs)
    .setDeleteAfterDelivery(true)
    .send()
```

aufsetzt. Wichtig: die Ausfuehrung laeuft auf dem Playback-Thread — der
Handler darf dort **nicht** blockieren und nichts allokieren, was den
Audio-Pfad stoert.

**`feature/player/.../RestMusicCoordinator.kt`** — `delay()` ersetzen. Die
Trigger-Position ist die aktuelle Position der Rest-Musik plus
`startAfterDelayMs`; die Umrechnung gehoert in den Coordinator, weil nur er
beide Groessen kennt.

Die bestehenden Absicherungen bleiben **unveraendert**: Generation-Token
(`:182`), Nutzer-Vorrang bei pausierter Wiedergabe (`:185-190`),
Abbruch bei Satzende/Abbruch (`:122-123`). Sie sind korrekt und der Grund,
warum diese Aenderung ueberhaupt risikoarm ist.

**Crossfade nutzen (D9)**

`RestMusicCoordinator.kt:170` uebergibt konstant `crossfadeMs = 0L`. Der
Planner kann Crossfade (`DropLanding.kt:133`: `rest - drop - latency -
crossfade`) und haelt die Invariante "Crossfade endet vor dem Restende"
(Design 7.1a) ein. Der Wert soll aus der DSP-Konfiguration kommen
(`DspConfig.crossfadeSeconds`, `DspSettingsStore.kt:58`), nicht aus einer
neuen Einstellung.

**Underrun-Ueberwachung (D8)**

Design 265 verlangt Underrun-Monitoring. `AudioInfoListener`
(`PlaybackService.kt:518`) implementiert heute nur `onAudioTrackInitialized`
(`:537`). Erweitern um `onAudioUnderrun` aus `AnalyticsListener`; das Signal
geht an `Media3AudioClock`, das bereits ein Feld dafuer hat
(`recentUnderrunAtElapsedMs`, `:49`, Fenster 2000 ms, `:126`) — heute nur von
`onPlayerError` gesetzt (`:118-122`), was der Kommentar selbst als
"konservativ" benennt.

Wirkung: eine Landung, deren Fenster einen Underrun enthaelt, wird als
`DEGRADED` markiert statt als sauber gezaehlt. Ohne das verfaelschen
Underruns die P95-Messung aus Phase 1.

### 5.5 Tests

| Test | Modul | Prueft |
|---|---|---|
| `ScheduledLandingTest` (JVM, Fake-Player) | `:feature:player` | Auftrag feuert genau einmal; Generation-Wechsel entwertet; Abbruch verhindert Feuern |
| `RestMusicCoordinatorTest` (erweitern) | `:feature:player` | pausierte Wiedergabe bricht ab (bestehend); Crossfade wird aus der Config uebernommen statt 0 |
| `DropLandingPlannerTest` (erweitern) | `:domain:timer` | mit `crossfadeMs > 0` endet der Crossfade vor dem Restende; `startAfterDelayMs` wird nie negativ |
| Instrumentiert: `PlayerMessagePositionTest` | `:app` | Message feuert innerhalb eines Toleranzfensters um die Zielposition (Delta-Messung, kein Absolutwert — Regel aus `AudioTimestampExtrapolator`) |
| `AudioUnderrunListenerTest` (Robolectric) | `:data:playback` | `onAudioUnderrun` setzt das Fenster; nach 2000 ms wieder false |
| Test B1 (Geraet) | — | P50/P95 je Route, vor und nach der Aenderung |

### 5.6 Abnahme

- P50/P95 je Route **vor und nach** der Aenderung dokumentiert. Ohne
  Vorher-Wert ist die Verbesserung nicht belegt — deshalb Phase 1 zwingend
  zuerst.
- Zielwert P50 <= 25 ms auf Kabel und Lautsprecher. Bei Bluetooth ist der
  Zielwert die **Streuung** (P95 - P50 <= 30 ms), nicht der Absolutwert:
  die Codec-Latenz ist nicht app-seitig kontrollierbar.
- Kein Regressionsfall: Skip, Satzwechsel und manuelles Pausieren brechen die
  Landung weiterhin ab.
- ADR-0022 geschrieben, mit dem ausdruecklichen Hinweis, dass ADR-0012s
  "+/-100-200 ms" bis zur Messung die ehrliche Aussage bleibt.

### 5.7 Risiko

**Gering.** Der Eingriff ersetzt eine Terminierungsprimitive; alle
Abbruchpfade bleiben. Ein Restrisiko liegt im Playback-Thread: der
`PlayerMessage`-Handler darf dort nicht blockieren. Deshalb macht er nichts
ausser das bestehende `ACTION_PLAY_SONG_AT` auszuloesen.

---

## Phase 6 — Offline-Sweep, danach Accel-Voting

### 6.1 Warum in dieser Reihenfolge

ADR-0017 hat den Accel-Kanal an eine gemessene Schwelle gebunden und benennt
selbst die verbleibende Luecke:

> Die Konstanten der Accel-Kalibrierung (`ACCEL_PEAK_FRACTION`,
> `ACCEL_NOISE_MARGIN`, `ACCEL_PEAK_WINDOW_S`) sind an synthetischen
> Signalen gesetzt und an echten M5StickC-Traces noch nicht geprueft.

Das Fehlverhalten ist asymmetrisch brutal (ADR-0017): zu tiefe Schwelle ->
der Kanal peakt auf Rauschen und das Voting laesst alles durch; zu hohe
Schwelle -> der Kanal peakt nie und das Voting verwirft **jede**
Wiederholung, die Live-Zaehlung faellt auf **0**.

Deshalb: erst Sweep-Werkzeug, dann Freischaltung. Nicht umgekehrt.

**Der Gewinn, der das rechtfertigt:** Balestra et al. (2021) messen genau
diesen Effekt in der anderen Richtung — die Hinzunahme des Gyroskops zum
Accel-Sensor hob die Zaehlgenauigkeit von **84,3 % auf 95,6 %**. Ein zweiter
unabhaengiger Kanal ist der groesste im Repo fertig herumliegende Hebel.

### 6.2 Das Sweep-Werkzeug

**Als JVM-Test in `:domain:sensor`, nicht als Python-Skript.** Begruendung:
die Pipeline ist Kotlin; ein Python-Nachbau waere eine zweite Wahrheit —
genau der Fehler, den `protocol.yaml:5-6` fuer das Wire-Format ausdruecklich
verbietet (*"nicht in Prosa an zwei Stellen getrennt beschreiben"*).

```kotlin
/**
 * Offline-Replay des Golden Corpus gegen variierte Pipeline-Parameter.
 *
 * Liest die JSONL-Sample-Fenster aus Phase 0, baut je Parametersatz eine
 * frische ExerciseEnginePipeline, spielt die Samples durch und vergleicht
 * den Count mit manifest.known_active_reps.
 *
 * Kein Test im Sinne von "gruen/rot", sondern ein Messwerkzeug: er schreibt
 * eine CSV mit (Parametersatz, Szenario, Satz, erwartet, gezaehlt, delta).
 * Als @Test markiert, damit er ohne eigene Toolchain laeuft; per
 * @Ignore/Property-Flag nur auf Anforderung.
 */
class CorpusSweepHarness
```

**Zu sweepende Parameter, mit Begruendung je Eintrag:**

| Parameter | Heute | Sweep-Bereich | Warum |
|---|---|---|---|
| `ACCEL_PEAK_FRACTION` | 0.35 | 0.20-0.60 | ADR-0017: an synthetischen Signalen gesetzt |
| `ACCEL_NOISE_MARGIN` | 4.0 | 2.0-8.0 | dito |
| `ACCEL_PEAK_WINDOW_S` | 0.4 | 0.2-0.8 | dito |
| `accelVoteWindowMs` | 800 | 400-1200 | `RepCounter.kt:53`, nie gemessen |
| `templateThreshold` (DTW) | 0.7 | 0.55-0.85 | global, obwohl Uebungen unterschiedlich sauber sind (Phase 9 R8) |
| `minQualityScore` | 0.55 | 0.45-0.70 | dito |
| `toleranceBelow`/`Above` | Phase 2 | 0.25-0.60 / 0.10-0.35 | Phase 2 setzt Startwerte, der Sweep belegt sie |
| `DTW_BAND` | 8/64 | 4-16 | ~12 % Zeitverzerrung ist eine Annahme (`TemplateMatcher.kt:113-118`) |

**Bewertungsmetrik.** Nicht nur "Delta = 0 maximieren", sondern
**Abstand zur Fehlentscheidung**: fuer jeden Parametersatz mit
Exact-Match 100 % zusaetzlich den minimalen Score-Abstand zur Schwelle
protokollieren. Ein Parametersatz, der knapp passt, ist schlechter als einer,
der komfortabel passt — dieselbe Logik wie im bestehenden theta-Sweep
(`CalibrationController.kt:599`: `margin = theta - (baseline + 3 * sigma)`).

### 6.3 Accel-Voting scharfschalten

Erst wenn 6.2 fuer die drei Accel-Konstanten Werte mit belegtem Abstand
liefert. Die Kopplung im Code bleibt wie in ADR-0017:

```kotlin
// ExerciseEngineConfig.kt:57-59 — bleibt unveraendert
require(!accelEnabled || (accelThreshold.isFinite() && accelThreshold > 0.0)) {
    "accelEnabled requires a calibrated accelThreshold > 0"
}
```

Zu aendern ist nur `CalibrationController`s Konstantenblock
(`:756-771`), plus ein Statuseintrag, der die Messgrundlage nennt.

**Abnahmebedingung mit Fallback:** Wenn der Sweep zeigt, dass das
Accel-Voting den Count auf dem Corpus **nicht verbessert**, bleibt der Kanal
aus und das wird als Ergebnis dokumentiert. Ein negatives Messergebnis ist
ein Ergebnis; die Konstanten dann trotzdem "besser zu raten" waere ein
Rueckfall in genau das Muster, das ADR-0017 beendet hat.

### 6.4 Tests

| Test | Modul | Prueft |
|---|---|---|
| `CorpusSweepHarnessTest` | `:domain:sensor` | Harness laeuft gegen einen eingebetteten Mini-Corpus (2 Saetze, synthetisch) und erzeugt die CSV |
| `CorpusReplayDeterminismTest` | `:domain:sensor` | derselbe Trace, zweimal gespielt, gibt bitgleiche Counts (keine Zeitabhaengigkeit im Replay) |
| `RepCounterTest` (bestehend) | `:domain:sensor` | Voting-Tests bleiben gruen; sie geben die Schwelle fest vor (`accelThreshold = 0.1625`, ADR-0017) und pruefen das Voting, nicht die Kalibrierung |

**Wichtig zum Determinismus:** Das Replay muss `ProcessedFrame`-Timestamps
aus der JSONL nehmen, nicht aus einer Uhr. `ExerciseEnginePipeline` ist dafuer
vorbereitet (`processSample(timestampMs, ...)`, `:213`) — aber
`SampleRateEstimator` und `LARGE_GAP_MS` reagieren auf die Timestamps, also
muss das Replay die echten Luecken mitspielen und nicht glaetten. Sonst
misst der Sweep eine Pipeline, die es live nicht gibt.

### 6.5 Abnahme

- CSV mit allen Parametersaetzen im Repo unter
  `tools/golden_shadow_corpus/sweep_<datum>.csv`.
- Fuer jede geaenderte Konstante: alter Wert, neuer Wert, Begruendung mit
  Zeile aus der CSV.
- ADR-0017 um einen Abschnitt "gemessen am" ergaenzt (kein neues ADR, das
  bestehende wird vervollstaendigt).

### 6.6 Risiko

**Gering fuer den Sweep, hoch fuer die Freischaltung ohne Sweep.** Genau
deshalb sind sie in einer Phase zusammengefasst: der Sweep ist die
Voraussetzung, und beide getrennt zu planen wuerde die Versuchung erzeugen,
die Freischaltung vorzuziehen.

---

## Phase 7 — Autokorrelations-Zweitmeinung sichtbar machen

### 7.1 Der Befund

Die Autokorrelations-Pruefung ist vollstaendig implementiert
(`RepCountPlausibility`), laeuft am Set-Ende
(`ExerciseEnginePipeline.checkPlausibility()`, `:342`), landet im
`SetTrace` (`SetTrace.kt:35`) — und wird dem Nutzer nie gezeigt. Der
Code-Kommentar formuliert die Absicht und ihr Ausbleiben in einem Satz:

> Die Pruefung KORRIGIERT nicht — sie liefert nur eine unabhaengige
> Zweitmeinung, die geloggt und (spaeter) dem Nutzer angezeigt werden kann.

Das "spaeter" ist nicht eingetreten.

**Was die Literatur dazu sagt:** uLift (Lim et al. 2024, IEEE Access) nutzt
Autokorrelation als **Eingangs-Gate** und erreicht damit einen mittleren
Zaehlfehler von **0,61** ueber 15 Uebungen und 35 Probanden — **ohne jedes
Training**. RecoFit (Morris et al. 2014, 242 Zitate) nutzt sie im Zaehlpfad
und erreicht +/-1 Rep in 93 % ueber 114 Probanden. Beide behandeln
Periodizitaet als Erstklass-Information, nicht als Nachtrag.

### 7.2 Loesung — in zwei Schritten, der zweite optional

**Schritt A (empfohlen): sichtbar machen.**

Wenn `plausibility` eine abweichende Rep-Zahl nennt, zeigt der Train-Tab beim
Satzabschluss eine dezente Zeile: *"Gezaehlt 12 — Periodik sagt 11. Stimmt
die Zahl?"*

Der Nutzen ist doppelt:
1. Der Nutzer bekommt eine unabhaengige Warnung genau dann, wenn eine
   Abweichung wahrscheinlich ist.
2. **Die Ground-Truth-Qualitaet des Corpus steigt.** Die D3-Regel
   (`confirmedRepsEdited`, ADR-0014) zaehlt nur *aktiv editierte* Werte als
   unabhaengige Wahrheit. Eine gezielte Nachfrage erhoeht die Rate echter
   Editierungen — genau dort, wo sie am wertvollsten ist. Damit verbessert
   diese Phase rueckwirkend die Aussagekraft von Phase 1 und 6.

**Schritt B (nur mit Belegen aus Phase 1): als Erwartungsquelle nutzen.**

Die Autokorrelation liefert eine Periodenschaetzung, die **unabhaengig** vom
gleitenden Mittelwert aus Phase 2 ist. Sie waere die ehrlichere Quelle fuer
`peakDetector.updateExpectedDurationMs()`.

Voraussetzung: Phase 1 hat `plausibility` je Satz gegen die Handzaehlung
protokolliert (siehe 1.1). Erst wenn diese Daten zeigen, dass die
Periodenschaetzung verlaesslicher ist als der Mittelwert, lohnt der Umbau.

### 7.3 Die Grenzen, die in die UI muessen

Die Pruefung ist bei kurzen Saetzen stumm
(`RepCountPlausibility.kt:135-147`):

| Grenze | Wert | Bedeutung |
|---|---|---|
| `MIN_SAMPLES` | 150 | 3 s bei 50 Hz — ein 3er-Satz mit 1-s-Reps liegt genau an der Grenze |
| `MIN_REP_SECONDS` | 0.6 | schnellere Reps sind nicht plausibel |
| `MAX_REP_SECONDS` | 8.0 | langsamere ebenfalls nicht |
| `MIN_PERIODICITY` | 0.25 | darunter ist das Signal zu unregelmaessig fuer eine Aussage |

Die UI darf daraus **nie** "alles in Ordnung" ableiten, wenn die Pruefung
stumm bleibt. Kein Hinweis heisst "keine Aussage", nicht "bestaetigt". Das ist
der haeufigste Fehler bei solchen Anzeigen und gehoert explizit in den
Kommentar der UI-Funktion.

### 7.4 Tests

| Test | Modul | Prueft |
|---|---|---|
| `RepCountPlausibilityTest` (bestehend, erweitern) | `:domain:sensor` | unter `MIN_SAMPLES` -> kein Ergebnis; irregulaeres Signal -> kein Ergebnis; sauberer 10-Rep-Satz -> 10 +/- 1 |
| `TrainViewModelPlausibilityTest` | `:feature:workout` | Hinweis erscheint nur bei Abweichung und nur bei vorhandenem Ergebnis; nicht bei stummer Pruefung |
| UI-Snapshot | `:feature:workout` | Hinweiszeile bricht das Layout nicht; DE/EN vorhanden |

### 7.5 Abnahme

- Der Hinweis erscheint bei einem absichtlich falsch gezaehlten Satz
  (Sensor waehrend eines Satzes bewusst schuetteln) und nicht bei einem
  normalen Satz.
- Bei einem 3er-Satz mit schnellen Reps bleibt er aus, ohne dass die UI
  Sicherheit suggeriert.

<a name="risiko-schwelle"></a>
### 7.6 Risiko

**Gering.** Rein additive Anzeige einer bereits berechneten Groesse. Das
einzige Risiko ist ein zu haeufiger Hinweis, der abstumpft — deshalb ist die
Schwelle "Abweichung > 0" und nicht "Abweichung >= 1 %".

### 7.7 Umsetzung Schritt A (2026-09-04)

Schritt A umgesetzt, Schritt B unveraendert offen (er braucht die Daten aus
Phase 1). Vier Abweichungen von diesem Plan:

**1. Die Anzeigeschwelle ist SUSPICIOUS, nicht "Abweichung > 0".**
Der [Risiko-Abschnitt](#risiko-schwelle) nennt "Abweichung > 0" — und
begruendet die Schwelle im selben
Satz damit, Abstumpfung zu vermeiden. Das widerspricht sich: "> 0" schliesst
`BORDERLINE` (genau eine Rep) ein, und eine Rep Abweichung ist am Satzende der
**Normalfall**, nicht der Verdachtsfall. `RepCountPlausibility` rundet die
Schaetzung genau deshalb (`Math.round`, Kommentar: *"die letzte Wiederholung
ist am Set-Ende meist nicht vollstaendig im Fenster"*). Gezeigt wird deshalb
nur `SUSPICIOUS`, also ab zwei Reps Abweichung. Die Abbildungsregel liegt in
`PlausibilityHint.kt` und wird dort direkt getestet, nicht nur ueber einen
Pipelinelauf — sie ist die eine Designentscheidung dieser Phase.

**2. `RepCountPlausibility.Result` traegt jetzt `countedReps`.**
Der Hinweis nennt beide Zahlen. Holte die UI den Zaehlerstand aus einer
zweiten Quelle, koennte er sich zwischen Pruefung und Anzeige geaendert haben
— der Nutzer darf die Rep-Zahl vor dem Loggen korrigieren. Die Anzeige
behauptete dann eine Aussage, die die Pruefung nie gemacht hat. Das Feld ist
auch bei `INCONCLUSIVE` gesetzt, damit am Ergebnis ablesbar bleibt, worauf
sich das Schweigen bezieht.

**3. `ActiveSetController.abort()` raeumt `_lastPlausibility` mit ab.**
Vorher blieb die Zweitmeinung beim Abbruch stehen, waehrend `_countedReps` auf
0 fiel. Das war ein latenter Fehler ohne Wirkung: solange der Wert nur im Log
landete, war er harmlos. Sobald er sichtbar ist, zeigt die App eine Aussage
ueber einen Satz an, den es nicht mehr gibt. Drei Tests halten den
Lebenszyklus fest (stop setzt, abort raeumt, finishAndTakeTrace raeumt mit).

**4. `SharingStarted.Eagerly` statt `WhileSubscribed`.**
Durch einen fehlgeschlagenen Test aufgefallen: ohne Abonnenten liefert ein
lazy geteilter Flow nur seinen Initialwert, `.value` waere also `null`,
obwohl eine Aussage vorliegt. Bei einem Zustand, dessen ganzer Sinn ein kurzes
Zeitfenster ist (zwischen `stopCountedSet` und `logSet`), ist ein luegendes
`.value` eine Falle. Die Kosten sind vernachlaessigbar — der Flow verknuepft
zwei StateFlows ohne eigene Arbeit.

**Kein "Pruefung bestanden"-Zustand.** `PlausibilityHint?` ist `null` fuer
*keine Aussage* und fuer nichts anderes. Ein eigener Zustand fuer
`INCONCLUSIVE` wuerde in der UI unweigerlich als "geprueft und in Ordnung"
gelesen — genau der Fehler, den 7.3 benennt. Die Begruendung steht im KDoc
von `PlausibilityHint`, damit sie einen Refactor uebersteht.

**Nicht geliefert:**
- **UI-Snapshot-Test (7.4) ist nicht durchfuehrbar.** Es gibt kein
  Snapshot-Framework im Projekt (weder Paparazzi noch Roborazzi im
  Versionskatalog). Dass die Hinweiszeile das Layout nicht bricht, ist
  **unbelegt**. Ein Framework dafuer einzufuehren waere eine eigene
  Entscheidung mit eigener Begruendung, nicht ein Nebenschritt dieser Phase.
- **Abnahme 7.5** braucht ein Geraet und faellt damit wie 0.4 in Phase 1.

**Verifikation:** `:domain:sensor:test` 128 Tests gruen (2 neue in
`RepCountPlausibilityTest`, 3 neue in `ActiveSetControllerTest`),
`:feature:workout:testDebugUnitTest` 32 gruen (8 neue in
`TrainViewModelPlausibilityTest`), `detekt` gruen. `spotlessCheck` ist rot,
aber an fremden Stellen (`docs/STATUS_FORTSCHRITT.md` ohne
Schluss-Newline, committet in `04e1a63`; `MasterDspProcessor.kt:274/289`
Zeilenlaenge, uncommittete B-AUD-1-Arbeit). Beides bewusst nicht angefasst.

---

## Phase 8 — Messsession Hebelarm gegen Handgelenk

### 8.1 Warum das eine eigene Phase ist

Viecelli et al. (2020, PLoS ONE) erreichten mit einem Smartphone **auf dem
Gewichtsstapel** eine Rep-Detektions-Fehlerrate von **0,16 %** ueber 9
Maschinen und 22 Probanden — und Time-under-Tension je Einzelrep mit Limits
of Agreement von -0,3 bis +0,3 s (0,1 % des Mittels), ICC > 0,99. Brennan et
al. (2025, Sports Medicine, Systematic Review ueber 44 Studien) bestaetigen
die Richtung: extern platzierte Geraete sind genauer, haben aber
*"practical limitations that may compromise their feasibility"*.

Das ist eine Groessenordnung besser als alles, was am Koerper gemessen wurde
(1 % bei Pernek et al., 5 % bei Chang et al., +/-1 Rep in 91-93 % bei
Soro/Morris).

**Und es passt strukturell zu FlowRep.** Vier der fuenf bestaetigten Uebungen
sind **Hammer Strength Iso-Lateral**-Maschinen
(`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:34-38`). Iso-Lateral bedeutet
unabhaengige **Hebelarme** — eine echte Rotation um eine feste,
maschinenseitige Achse. Genau das Signal, fuer das die Pipeline gebaut ist
(signierte Gyro-Projektion auf eine kalibrierte Rotationsachse,
`SignalChain.kt:96`).

Drei strukturelle Vorteile gegenueber dem Handgelenk:
- **Feste Achse.** Die PCA-Achse waere die Maschinenachse und verdreht sich
  nie. Der Grund, warum `orientationTrackingEnabled` existiert, entfaellt.
- **Keine Koerpersegment-Kopplung.** Kein Handgelenks-Flex, kein
  Griffwechsel, keine Ausgleichsbewegung im Signal.
- **Kein Erschuetterungs-Rauschen** vom Absetzen und Umgreifen — genau die
  Stoerung, gegen die das Accel-Voting (Phase 6) gebaut wurde.

### 8.2 Was ehrlich dagegensteht

- **Umbau pro Uebung.** Magnetmontage, ein Handgriff je Geraet.
- **Kalibrierungsprofil pro Maschine, nicht pro Koerper.** Das Datenmodell
  haelt das aus (`CalibrationProfile(exerciseId, deviceId)`,
  `SensorModels.kt:79-80`), aber die Semantik von `deviceId` verschiebt sich
  — deshalb ADR-0023, falls die Messung ueberzeugt.
- **Bizeps-Curl bleibt am Koerper** (Freihantel, `priors:34`). Es waere also
  ein gemischtes System, kein einheitliches.
- **Die M5-Taste wird schwer erreichbar.** BtnA startet das Zaehlen und
  beendet den Satz (`protocol.yaml:135-137`,
  `docs/archive/.../16_M5_BUTTON_COUNT_CONTROL.md`). Am Hebelarm einer
  Presse ist sie waehrend des Satzes nicht bedienbar.
- **Fremdes Geraet im Studio.** Ein magnetisch montierter Stick an einer
  Maschine ist gesellschaftlich und rechtlich etwas anderes als ein Wearable
  am eigenen Handgelenk.

### 8.3 Die Messung

Kein Redesign, **eine Session**, nach dem Corpus-Protokoll aus Phase 0/1:

1. Eine Uebung waehlen, die eindeutig Hebelarm ist (Iso-Lateral Incline Press).
2. Kalibrierung am Handgelenk, 3 Saetze, Handzaehlung, Corpus-Aufnahme.
3. Kalibrierung am Hebelarm (dieselbe App, `deviceId` bleibt der Stick,
   `exerciseId` neu als Variante), 3 Saetze, Handzaehlung, Corpus-Aufnahme.
4. Beide Aufnahmen durch den Sweep-Harness aus Phase 6.

**Zu vergleichende Groessen:**

| Groesse | Quelle |
|---|---|
| Exact-Match-Rate, MAE | `shadow_harness.py` |
| Peak-Prominenz (Median, Streuung) | `repEvents` im Trace |
| DTW-Korrelation (Median) | `repEvents.correlation` |
| Qualitaets-Score (Median, Minimum) | `repEvents.qualityScore` |
| Ermuedungsdrift (Steigung je Rep) | Phase-2-Modell auf beiden Traces |
| Signalqualitaet | `SensorHealth` je Satz |

### 8.4 Entscheidungsregel (vorab festlegen, nicht nachtraeglich)

| Ergebnis | Konsequenz |
|---|---|
| Hebelarm hat hoehere DTW-Korrelation **und** hoeheren minimalen Qualitaetsscore | ADR-0023 schreiben, Hebelarm als Option fuer Maschinenuebungen aufnehmen (nicht als Zwang) |
| Kein messbarer Unterschied | Ergebnis dokumentieren, Handgelenk bleibt, Thema geschlossen |
| Hebelarm schlechter | dito, und die Begruendung notieren (vermutlich: Montagelockerheit) |

Die Regel muss **vor** der Messung stehen, damit das Ergebnis nicht
nachtraeglich passend interpretiert wird.

### 8.5 Risiko

**Keins** — reine Messung, kein Codeeingriff. Das Risiko liegt darin, die
Messung *nicht* zu machen und stattdessen an der Algorithmik zu feilen,
waehrend ein Faktor-10-Effekt in der Sensorposition ungeprueft bleibt.

---

## Phase 9 — Nachrangiges

Alles hier ist **nur mit belegtem Bedarf** aus Phase 1-8 zu bauen. Jeder
Eintrag nennt sein Ausloesekriterium.

### 9.1 Parameter pro Uebung (Schema v6 -> v7)

**Auslaesekriterium:** Der Sweep aus Phase 6 zeigt, dass verschiedene
Uebungen unterschiedliche Optima fuer `templateThreshold` oder
`minQualityScore` haben.

**Belege:** ClassRAC (Zhang et al. 2024) formuliert es explizit —
*"separating the training and validation datasets by action classes and
conducting individual training and validation for each class can
significantly enhance model performance"*; Ergebnis MAE 0,146 gegen
vorherigen SOTA. Das Repo-eigene biomechanische Vorwissen sagt dasselbe:
Scott-Curls haben *"vermutlich saubereres Signal als beim Standard-Curl"*
(`EXERCISE_BIOMECHANICAL_PRIORS_2026-07-28.md:155`), der Lat-Pulldown eine
andere Bewegungsebene (`:141`).

**Umsetzung:** `templateThreshold` und `minQualityScore` ins Profil, im
`knownCountSweep` mitbestimmt (`CalibrationController.kt:545`). Der Sweep hat
die Daten schon: er kennt den KNOWN_SET mit bekannter Rep-Zahl und kann die
Schwellen so waehlen, dass der Count exakt stimmt **und** der Abstand zur
Fehlentscheidung maximal ist — dieselbe Logik wie beim theta-Sweep.

**Nullwerte fallen auf die globalen Defaults zurueck**, damit v6-Profile
unveraendert funktionieren.

### 9.2 Firmware: Connection Parameters, Dual-Buffer, Watchdog

**Auslaesekriterium:** Phase 1 zeigt Paketverlust > 5 % oder Gaps > 200 ms in
mehreren Sessions.

**Belege und Grenzen:**
- Hardware ist **Bluetooth 4.2** (ESP32-PICO-V3-02, Mouser-Specsheet). Damit
  **kein LE 2M PHY** — die 1M-PHY-Grenze (~700 kbps laut ESP-FAQ) bleibt.
- Der Bedarf ist 53 Byte / 80 ms = **~5,3 kbit/s**, also **0,8 %** der
  Praxisgrenze. **Bandbreite ist kein Thema.**
- Limitierend ist das Connection Interval. BLE-Minimum 7,5 ms; Android laesst
  es nicht direkt setzen, `requestConnectionPriority(HIGH)` fuehrt typisch zu
  11,25-15 ms und wird schon aufgerufen (`BleSensorProvider.kt:387`).
- **Der einzige verbleibende Hebel liegt in der Firmware:** das Peripheral
  kann Connection Parameters vorgeben, die Android beim Verbindungsaufbau
  uebernimmt.

**Praxiserfahrung aus einem 100-Hz-IMU-BLE-Produkt** (Oxeltech-Fallstudie,
Dez 2025), direkt uebertragbar:
- **Dual-Buffer:** Sensor-Callback schreibt Buffer A, BLE-Callback liest
  Buffer B, Swap ausserhalb der Interrupts. Ohne das korrumpieren 20-50 ms
  BLE-Bloecke laufende Sample-Writes.
- **Verbindungsaufbau ist ein kritischer Zustand:** 200-500 ms, in denen
  Sensor-Callbacks pausiert werden muessen — *"better to lose 500 ms of data
  than corrupt the whole buffer"*.
- **Watchdog zustandsabhaengig:** normal 5 s, waehrend Connect deaktiviert,
  bei grossen Sends 15 s.
- **MTU kann spontan zurueckfallen** (247 -> 23). Der App-Code faengt das ab
  (`MtuNegotiator.FALLBACK_MTU = 23`), aber bei MTU 23 passen 53 Byte nicht —
  dann kommen **keine** Samples mehr. Das muss die App als eigenen
  Fehlerzustand melden, nicht als stilles Schweigen.

**Achtung:** Die Firmware liegt nicht in diesem Repo (kein `firmware/`-
Verzeichnis; die Referenz ist
`docs/archive/.../16_M5_BUTTON_COUNT_CONTROL.md:49`, das auf
`firmware/src/main.cpp` in FlowRep verweist). Jede Firmware-Aenderung braucht
einen Flash und eine erneute Protokoll-Verifikation gegen
`protocol.yaml`.

### 9.3 Relativer Timestamp je Sample (Protokoll v3)

**Auslaesekriterium:** Die Sample-Konsistenzpruefung aus Phase 0.2 zeigt, dass
das 20-ms-Raster in der Praxis nicht haelt.

Heute traegt nur `samples[0]` einen echten Timestamp; `samples[1..3]` werden
per `i * 20 ms` abgeleitet (`BleProtocolParser.kt:83`). Die 20-ms-Garantie ist
damit eine **Firmware-Zusage, die die App nicht pruefen kann** —
`protocol.yaml:170-180` formuliert sie ausdruecklich als Garantie, nicht als
Annahme, aber verifizierbar ist sie nur am Geraet.

Die Oxeltech-Fallstudie hat genau diese Abwaegung getestet und drei Varianten
verglichen: absoluter Timestamp je Sample (teuer), **relativer 2-Byte-
Timestamp je Sample** (gewaehlt, wenn man Beweis will), nur erster + letzter
Timestamp (bandbreitensparend, was FlowRep heute macht).

Kosten: 53 -> 61 Byte, also 8 Byte je Batch = **+0,8 kbit/s**. Bei 0,8 %
Auslastung irrelevant. Das ist ein reiner Protokoll-Versionssprung mit
Firmware-Flash.

### 9.4 DBA-Template statt FIFO-Pool

**Auslaesekriterium:** Der Sweep zeigt, dass die DTW-Korrelation innerhalb
eines Satzes stark streut (Hinweis auf Pool-Verwaesserung).

`TemplateMatcher.addToPool()` (`:70-77`) haelt die letzten 5 bestaetigten
Fenster als **rohe** Kurven und nimmt `maxOf` ueber alle Vergleiche (`:93`).
Das ist ein **Oder**: es genuegt, dass *irgendeine* der letzten 5 Reps
aehnlich war. Bei Formdrift wandert der Pool mit, was gewollt ist — aber er
wandert auch bei einer schlechten Rep mit, weil **jede** bestaetigte Rep
aufgenommen wird (`RepCounter.kt:224`).

Filippou et al. (2023, Sensors) zeigen den besseren Weg: **DTW Barycentre
Averaging** erzeugt aus mehreren Beispielen *ein* gemitteltes Template.
RMSE 2 Schritte bei gesundem Gang gegen 12 bei simuliert pathologischem — und
es schlug die Vergleichsalgorithmen gerade bei der schwierigen Gruppe.
Yurtman & Barshan (2014, 77 Zitate) erreichen mit Multi-Template-DTW eine
False-Alarm-Rate unter 1 %.

Der Code hat alle Bausteine: `resample()` (`:121`), `normalize()` (`:143`),
`dtwSimilarity()` (`:178`). DBA ist im Kern eine Iteration aus
DTW-Alignment und Mittelung entlang des Warping-Pfads.

### 9.5 Spectral Flux statt RMS-Novelty

**Auslaesekriterium:** Nach Phase 3 zeigt sich, dass die *Marker-Positionen*
selbst (nicht das Raster) der dominierende Fehlerterm sind.

Die Literatur ist eindeutig: Mueller & Zalkow (TISMIR 2024) sagen ueber
Spectral Flux — die **staerkere** Variante — *"In many situations, spectral
flux fails to reliably detect note onsets"*; reine Energie-Novelty liegt
darunter. Boecks SuperFlux/ComplexFlux waren 2016 MIREX-Spitze. Ein
EDM-Drop aendert typisch Bass-Einsatz und Spektralverteilung, oft **ohne**
dramatischen RMS-Sprung, weil der Build-up bereits laut war.

**Zwischenschritt, der nichts kostet:** `LoudnessAccumulator`
(`TrackAnalyzerImpl.kt:103`) berechnet bereits LUFS. Kurzzeit-LUFS ueber ein
3-s-Fenster (EBU R128) ist perzeptiv gewichtet und damit naeher am gefuehlten
Drop als RMS. Erst wenn das nicht genuegt, lohnt Spectral Flux.

**Kosten:** `ANALYZER_VERSION` steigt von 4, das erzwingt eine Re-Analyse der
gesamten Bibliothek. Das ist ein eigener Umbau mit eigener
Performance-Betrachtung (ADR-0015-Umfeld) — deshalb nachrangig.

**Realitaetsabgleich:** EDMFormer (arXiv 2603.08759) nennt fuer
Referenzannotationen von EDM-Strukturgrenzen eine Praezision von
**+/-0,5 s**. Wenn die *Grundwahrheit* eines Drops nur auf eine halbe Sekunde
bestimmt ist, ist die Marker-Position der dominierende Fehlerterm, nicht die
Audio-Latenz — aber auch der Verbesserungsspielraum ist dann begrenzt.

### 9.6 `seekPrerollMs` je Marker

**Auslaesekriterium:** Test B1 zeigt, dass `DIRECT_TO_DROP` systematisch
schlechter trifft als `INTRO`.

Design 7.1c verlangt pro Marker zwei Werte: `markerFrame` (hoerbarer Impact)
und `seekPrerollFrame` (Decoder-Einstieg), *"damit der Direct-Drop-Sprung
nicht am falschen Punkt landet"*. Das Marker-Modell hat nur `positionMs`
(`core/model/.../Library.kt:25`). Bei `DIRECT_TO_DROP` springt
`playSongAt(workSong, plan.startAtPositionMs)`
(`RestMusicCoordinator.kt:191`) direkt auf die Marker-Position; wo der
Decoder tatsaechlich einsetzt, ist ungeprueft. Die Media3-Troubleshooting-Doku
beschreibt genau diesen Effekt.

### 9.7 `AudioClock.Mode.EXACT`

**Auslaesekriterium:** Phase 5 erreicht die P95-Zielwerte nicht.

`Media3AudioClock` interpoliert die Player-Position ueber die Systemzeit und
meldet konstant `BEST_EFFORT`; `EXACT` ist als "spaeter" markiert
(`Media3AudioClock.kt:27-29`). Der echte Pfad existiert —
`AudioTrackTimestampReader` (`AudioTrack.getTimestamp()`) plus
`AudioTimestampExtrapolator` mit
`audibleFrame = framePosition + (nowNs - tsNano) * rate` — ist aber **nicht
in der DI verdrahtet**, mit korrekter Begruendung:
*"Die App besitzt keinen eigenen AudioTrack (Media3 verwaltet seinen Sink
intern)"* (`AudioTrackTimestampReader.kt:17-22`).

**Der Ausweg ist naeher als der Kommentar vermuten laesst.** Der
nift4-Deep-Dive in den AOSP-Audiostack (2025-08) bestaetigt das Problem und
zeigt zugleich: `DefaultAudioSink` erzeugt den `AudioTrack` selbst, aber
ExoPlayer laesst eine eigene `AudioSink`-Implementierung zu — und
`DspRenderersFactory` (`:36-46`) baut den Sink im Repo **bereits selbst**.
Der Zugriffspunkt ist also eine Ableitung von `DefaultAudioSink` entfernt,
nicht eine Plattformgrenze.

**Trotzdem hoher Aufwand und mittleres Risiko:** der Eingriff sitzt in der
Audio-Kette, die laut ADR-0005 die eigene DSP-Pipeline traegt. Nur mit
belegter Notwendigkeit.

### 9.8 Madgwick-Orientierungstracking freischalten

**Auslaesekriterium:** Phase 1 zeigt Abweichungen, die mit Sensorverdrehung
korrelieren (z. B. Szenario `exercise_switch` schlechter als `calibrated`).

Der Filter ist implementiert und getestet (`OrientationTracker.kt`,
beta 0.1), bleibt aber aus, bis Gate 11b gruen ist
(`ActiveSetController.kt:121-123`, Umbauplan Punkt 8). Wichtiger Hinweis im
Code, der leicht uebersehen wird: der Tracker bekommt die **bias-korrigierten**
Raten (`SignalChain.kt:82-88`) — mit rohen Werten integriert ein konstanter
Gyro-Bias zu monoton wachsender Drift und die nachgefuehrte Achse wandert
**weg** von der echten Rotationsachse, was den Zweck umkehrt.

**Entfaellt vollstaendig**, wenn Phase 8 zur Hebelarm-Montage fuehrt: eine
maschinenfeste Achse verdreht sich nicht.

### 9.9 Restliche TTS-Punkte

| Punkt | Auslaesekriterium |
|---|---|
| `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` statt SONIFICATION | Test B9 zeigt einen Verhaltensunterschied |
| Fokus-Verlust-Verhalten fuer den Timer (Design 7.1b) | ein realer Fall tritt auf (Anruf waehrend Pause) |

Design 7.1b hat die Tabelle bereits vollstaendig (LOSS_TRANSIENT_CAN_DUCK ->
ducken, Timer weiter, Drop-Plan weiter; permanenter LOSS -> stoppen, Zustand
speichern, Plan abbrechen). Der Code hat keinen Focus-Listener. Nach Phase 4
existiert einer — dann ist die Tabelle mit geringem Aufwand umsetzbar.

---

## 10. Verifikation

### 10.1 Nach jeder Phase

```bash
./gradlew spotlessApply
./gradlew <betroffene :module:testDebugUnitTest / :domain:*:test>
./gradlew detekt lintDebug
./gradlew :app:assembleDebug
```

Windows-Hinweis (README:135-146): liegt das JDK unter einem Pfad mit
Leerzeichen, schlagen alle Unit-Tests mit "Hauptklasse Files konnte nicht
gefunden werden" fehl. Abhilfe ist eine Junction ohne Leerzeichen, eingetragen
**maschinenlokal** in `~/.gradle/gradle.properties` — nicht im Projekt, weil
ein Windows-Pfad dort die Linux-CI bricht.

### 10.2 Geraetegebunden (nicht automatisierbar)

```bash
./gradlew installDebug
adb logcat -d > logs/<datum>_<test-id>.txt
adb pull /sdcard/Android/data/com.dropsync/files/recordings/
python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus
python3 tools/shadow_harness.py --smoke-test
```

Regel aus `HARDWARE_TESTPLAN.md:29-30`: **Ein Lauf zaehlt nur, wenn der
Sensor-Zustand STREAMING zeigt** — nicht nur CONNECTED.

### 10.3 Zielwerte mit Messvorschrift

| Groesse | Zielwert | Messvorschrift |
|---|---|---|
| Rep-Zaehlung, exakt | 100 % ueber 5 Szenarien x 3 Sessions | Handzaehlung direkt nach jedem Satz, `shadow_harness.py` gegen `known_active_reps` |
| Rep-Zaehlung, +/-1 | 100 % | dieselbe Quelle; Literaturvergleich 91-93 % |
| Drop-Landung P50 | \|Fehler\| <= 25 ms je Route | Video 120 fps, Go-Beep-Ende zu hoerbarem Drop, >= 3 Laeufe je Route (B1) |
| Drop-Landung P95 | \|Fehler\| <= 50 ms je Route | dieselbe Messung; **Streuung ist das Ziel**, nicht der Mittelwert |
| Drop-Landung BT | P95 - P50 <= 30 ms | Codec-Absolutlatenz ist nicht app-seitig kontrollierbar |
| Route-Profil | `p50ErrorMs`/`p95ErrorMs` gesetzt, `confidence = CALIBRATED` | `RouteProfileStore.upsert()` nach B1 |
| TTS-Ducking Fremdmusik | gedaempft: ja / gestoppt: nein, je Player | B9-Matrix gegen Spotify, YT Music, Poweramp |
| TTS-Erstansage | < 300 ms nach Grenzwert | Logcat Grenzwert vs. `onStart`, 10 Sessions, davon 3 nach Boot |
| Sensor-Stream | Verlust < 5 %, groesster Gap < 200 ms | `SensorHealth` waehrend Gate 11b (Schwellen `SensorHealth.kt:36-37`) |
| Sample-Raster | mittleres Delta 15-25 ms je Fenster | Konsistenzpruefung im Harness (Phase 0.2) |

**Alle Zielwerte sind vor der ersten Messung Absichten.** Der Corpus
entscheidet, welche realistisch sind — dieselbe Ehrlichkeit, die
`README.md:100` fuer den 1,5-s-Analysezielwert schon anwendet.

## 11. Abbruch- und Umkehrkriterien

Wann eine Phase abgebrochen und der Vorzustand wiederhergestellt wird:

| Phase | Abbruch, wenn |
|---|---|
| 0 | Aufnahme kostet > 5 % Batterie je 10 Minuten oder verursacht messbaren Jank im Train-Tab |
| 2 | ein Satz im Corpus verschlechtert sich (nicht: der Mittelwert wird schlechter — **ein einzelner Satz** genuegt) |
| 3 | ein Marker, der ohne Snap richtig lag, wird durch das Snap verschoben |
| 4 | fremde Musik bleibt nach einem Cue-Zyklus geduckt, oder der Go-Beep kommt spaeter als vor der Aenderung |
| 5 | P95 verschlechtert sich auf einer Route, oder eine Landung feuert doppelt |
| 6 | Accel-Voting senkt den Count auf dem Corpus (dann bleibt der Kanal aus — Ergebnis dokumentieren) |
| 7 | der Hinweis erscheint bei > 30 % normaler Saetze (abstumpfender Fehlalarm) |

## 12. Annahmen und offene Punkte

**Annahmen, die dieser Plan macht** (jede muss durch Messung ersetzt werden):

1. Die Ermuedungsdrift in FlowReps fuenf Uebungen liegt in der Groessenordnung
   der Literaturwerte (15-40 %). Gemessen wird das in Phase 1 (Szenario
   `slow`).
2. Die Beat-Phase ist per Kreuzkorrelation aus den vorhandenen Onsets
   schaetzbar. Falls die Onset-Qualitaet dafuer nicht reicht, wird Phase 3 zu
   Phase 9.5 (Spectral Flux) verschoben — nicht mit geratener Phase gebaut.
3. Ducking-Tiefe -12 dB fuer Cue-Ansagen ist hoerbar besser als -6 dB. Test
   B9 (Hoertest) entscheidet.
4. Die drei getesteten Fremdplayer verhalten sich repraesentativ. Das ist eine
   Stichprobe, keine Garantie — die Doku muss das so sagen.
5. Der Coroutine-`delay` ist tatsaechlich eine relevante Fehlerquelle. Falls
   Test B1 zeigt, dass der Fehler von der Route dominiert wird, ist Phase 5
   kosmetisch und Phase 3 wichtiger.

**Offene Punkte, die dieser Plan nicht loest:**

- **Die Firmware liegt nicht in diesem Repo.** Es gibt kein
  `firmware/`-Verzeichnis; die einzige Referenz ist
  `docs/archive/.../16_M5_BUTTON_COUNT_CONTROL.md:49`. Jede Aenderung an
  Protokoll oder Connection Parameters (Phase 9.2, 9.3) braucht Zugriff auf
  das FlowRep-Firmware-Repo, einen Flash und eine Neuverifikation gegen
  `protocol.yaml`.
- **Ducking-Tiefe des Systems ist nicht messbar.** Wie stark Android bei
  MAY_DUCK absenkt, liess sich aus keiner Primaerquelle belegen und ist von
  der App aus nicht abfragbar. Bleibt eine Blackbox.
- **`ANALYZER_VERSION`-Sprung** (Phase 9.5) erzwingt eine Re-Analyse der
  gesamten Bibliothek. Der Waveform-Performance-Plan (ADR-0015) hat dafuer die
  Zwei-Stufen-Trennung gebaut, aber die Kosten sind nicht gemessen — der
  1,5-s-Zielwert ist laut `README.md:100` selbst noch eine Absicht.
- **Xiaomi/HyperOS-Verhalten.** Der bestehende Code hat mehrere
  HyperOS-Workarounds (MTU 185 statt 512, `MtuNegotiator.kt:19-23`;
  Notify-Probe mit Poll-Fallback, `BleSensorProvider.kt:415-448`;
  250-ms-DeviceEvent-Poll). Aggressives Background-Killing bleibt ein
  bekanntes Risiko fuer den Timer, das der `specialUse`-Foreground-Service und
  die Snapshot-Persistenz (`TimerService.kt:161`) abfedern, aber nicht loesen.

## 13. Was dieser Plan bewusst nicht anfasst

- **Herzfrequenz / Health Connect** — eigener Plan, Phase 3 offen
- **Waveform-Performance** — eigener Plan, ADR-0015; Phase 3 nutzt bewusst
  `MIX_ANALYZER_VERSION` statt `ANALYZER_VERSION`, um dort nicht
  hineinzugreifen
- **Mix-Uebergaenge** jenseits der Drop-Landung
- **Bit-Perfect / USB-DAC** — ADR-0009, unabhaengig
- **Zwei parallele ExoPlayer** (Design 7.1a MVP) — wurde bewusst entfernt
  (README Schritt 18); ohne belegte Notwendigkeit nicht zurueckholen
- **Neuronale Drop-Erkennung** (CUE-DETR, arXiv 2407.06823) — widerspricht dem
  Offline-/Nachvollziehbarkeitsgrundsatz (`OnsetDetection.kt:6-11`:
  *"bewusst klassische Signalverarbeitung, kein ML"*)
- **Few-Shot / Deep Learning fuer die Rep-Zaehlung** — loest ein Problem, das
  FlowRep nicht hat (unbekannte Uebungen), und erreicht dabei schlechtere
  exakte Genauigkeit (12,9 % fehlerfreie Saetze bei Lim & Lee 2024 gegen
  0,16-1 % Fehlerrate klassischer, gut platzierter Verfahren)
- **Uebungsklassifikation** — der Nutzer waehlt die Uebung; damit entfaellt
  die laut Brennan et al. (2025) groesste Fehlerquelle des Felds. Sie
  nachzubauen wuerde Fehler einfuehren, nicht beseitigen.
- **Hoehere Abtastrate** — 50 Hz sind mehrfach belegt ausreichend (Fan et al.
  2025, Villa et al. 2025, Phan et al. 2023); der Flaschenhals ist
  Paketverlust, nicht Auflaesung

## 14. Quellen

Die vollstaendige, kommentierte Liste steht in der
[Quellenuebersicht](../research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md#quellen)
des Rechercheberichts. Die fuer diesen Plan tragenden Arbeiten:

**Rep-Zaehlung.** Viecelli et al. 2020 (PLoS ONE, 0,16 % am Gewichtsstapel) ·
Pernek et al. 2013 (Pers. Ubiq. Comp., DTW, ~1 % Miscount ueber 3.598 Reps) ·
Morris et al. 2014 (CHI, RecoFit, +/-1 Rep in 93 %, 114 Probanden) ·
Soro et al. 2019 (Sensors 19(3):714, CNN, +/-1 Rep in 91 %) ·
Balestra et al. 2021 (PMC8339513, 84,3 % -> 95,6 % durch Gyro) ·
Lim et al. 2024 (IEEE Access, uLift, Zaehlfehler 0,61, Autokorrelation als
Gate) · Zelman et al. 2020 (J. Healthc. Eng., Threshold+Lowpass gewinnt) ·
Chang et al. 2007 (Peak vs. Viterbi, ~5 % Miscount) ·
Yurtman & Barshan 2014 (MTMM-DTW, False Alarm < 1 %) ·
Zhang et al. 2024 (ClassRAC, MAE 0,146 durch Optimierung je Klasse) ·
Filippou et al. 2023 (Sensors, DTW Barycentre Averaging) ·
Brennan et al. 2025 (Sports Medicine, Systematic Review, 44 Studien).

**Ermuedung.** Sanchez-Medina & Gonzalez-Badillo 2011 (MSSE, 752 Zitate) ·
Pareja-Blanco et al. 2017 (VL20/VL40) · Rodriguez-Rosell et al. 2020
(R = 0,97 / 0,93) · Jukic et al. 2022 (Sports Medicine, Meta-Analyse) ·
Chung 2026 (-15,6 % MCV ueber 5 Reps) · Munoz-Lopez et al. 2021
(Geschwindigkeit < Beschleunigung < Leistung) · Moyen-Sylvestre et al. 2022
(Winkelgeschwindigkeit > Accel fuer Ermuedung).

**Audio/Android (Primaerquellen).**
`developer.android.com/media/optimize/audio-focus` (MAY_DUCK,
System-Ducking ab API 26) ·
`developer.android.com/media/media3/exoplayer/listening-to-player-events`
(`createMessage`/`setPosition`) ·
`developer.android.com/ndk/guides/audio/audio-latency` + AOSP CDD 5.6
(`low_latency` <= 45 ms kontinuierlich) ·
`nift4.org/2025/08/09/android-audio-stack-music-player` (AudioTrack-,
AudioFlinger-, AudioSink-Pfade) · Google Wear-OS-TTS-Blog 2024-03 (~10 s
Kaltstart) · RTINGS (SBC 150-250 ms).

**Perzeption.** ACM MuC 2024, `10.1145/3678299.3678331` (JND 49/27/77 ms je
Basislatenz) · PMC6711538 (audiovisuelle Synchronitaet, JND ~60 ms) ·
Friberg & Sundberg (~10 ms fuer kurze Toene).

**Drop-/Cue-Point-Erkennung.** Yadati et al. ISMIR 2014 (zweistufige
Drop-Erkennung) · Zehren et al. CMJ 46(3) 2022 / arXiv 2007.08411
(Cue-Points, 90-96 %, Downbeat als Kern) · Arguello et al. arXiv 2407.06823
(CUE-DETR) · arXiv 2603.08759 (EDMFormer, +/-0,5 s Referenzannotation) ·
Mueller & Zalkow TISMIR 2024 (Novelty-Funktionen).

**BLE/Hardware.** ESP-FAQ (Espressif, ~700 kbps auf 1M PHY) · ESP-IDF
System Time (APB <= +/-10 ppm) · Mouser-Specsheet M5StickC PLUS2 +
`docs.m5stack.com` (ESP32-PICO-V3-02, Bluetooth 4.2, 200 mAh, MPU6886 via
`M5Unified IMU_Class`) · Punchthrough (Connection Interval, ATT MTU, DLE) ·
Oxeltech 2025-12 (100-Hz-IMU ueber BLE: Timestamp-Strategien, Dual-Buffer,
Connect als kritischer Zustand, Watchdog, MTU-Rueckfall).
