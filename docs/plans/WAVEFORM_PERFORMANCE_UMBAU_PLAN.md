# DropSync Waveform-/Analyse-Performance — Umbauplan

Stand: 21.08.2026; Messnachtrag 31.08.2026. Basis ist eine vollstaendige Code-Analyse der
Analyse-Pipeline (`TrackAnalyzerImpl`, `TrackAnalysisRepositoryImpl`,
`TrackAnalysisWorker`, `TrackAnalysisMath`/`MixAnalysis`,
`PlayerViewModel`, `Waveform.kt`, DI in `data/audio`) plus die Recherche
`docs/research/RESEARCH_MUSIC_TECHNIK_2026.md` (Abschnitte Waveform,
Compose-Performance, Media3) und `docs/research/RESEARCH_MUSIC_UIUX_2026.md`
(nur Randberuehrung: Ladezustand der Waveform ist UI-wirksam). Die
Architekturregeln aus Bauplan Abschnitt 3.2 sowie ADR-0005 (Modulgrenzen)
und ADR-0011 (Decode via MediaExtractor/MediaCodec) bleiben
uneingeschraenkt gueltig.

## Ziel (messbar)

- Cache-Miss bis sichtbarer Waveform im Now-Playing: heute mehrere
  Sekunden bis ueber zehn (WorkManager-Dispatch + Vollanalyse); Ziel
  **unter 1,5 s** fuer einen typischen 4-Minuten-Track auf einem
  Mittelklasse-Geraet. Verbindlich wird der Zielwert nach der
  Baseline-Messung aus Phase 0 festgelegt und hier nachgetragen.
- Die teuren Metadaten (BPM, Camelot-Key, LUFS, True-Peak) entstehen
  ungefragt im Hintergrund nach — der Nutzer wartet darauf nicht.
- Kein CPU-Wettstreit: die Analyse des aktuellen Titels gewinnt gegen
  haeufiges Wischen; ueberholte Laeufe brechen ab.
- Kein Re-Analyse-Sturm nach App-Update: Cache-Invalidierung nur, wenn
  sich Ausgabewerte tatsaechlich aendern.

## Ist-Zustand: die fuenf Flaschenhaelse (mit Beleg)

1. **All-or-nothing-Analyse.** `TrackAnalyzerImpl.kt:85-89` konstruiert
   Tempo-, Chroma- und Loudness-Akkumulator bei jedem Lauf; der
   Nur-Waveform-Pfad existiert nicht. Die Interface-Doku
   (`TrackAnalyzer.kt:17-26`) verspricht ihn ("Ohne detectOnsets wird nur
   die Waveform berechnet"), und das Enum `AnalysisProfile`
   (`TrackAnalyzer.kt:106-111`, mit `WAVEFORM_ONLY`, `MIX_METADATA`,
   `FULL`) ist bereits angelegt — **beides wird von der Implementierung
   nicht eingeloesst/benutzt.** Der Ergebnis-DB-Eintrag wird erst nach
   ALLEN Akkumulatoren geschrieben (`TrackAnalysisWorker`, single
   Upsert): die sichtbare Waveform ist Geisel der Tonart-Erkennung.
2. **Per-Sample-Kotlin-Schleife.** `TrackAnalyzerImpl.kt:187-207`: pro
   Frame zwei indizierte `FloatBuffer.get(frame*ch+ch)` (Bounds-Check +
   Indexarithmetik je Sample), eine Downmix-Division und 4-5 virtuelle
   `accept(Double)`-Aufrufe. Fuer 4 min / 44,1 kHz / Stereo sind das
   ~10,6 M Frames. Kein Bulk-Read, keine Blockverarbeitung —
   klassischer Faktor 3-5 gegenueber Array-Verarbeitung.
3. **Chroma-/Tempo-Detailkosten.** `MixAnalysis.kt:151-163` rechnet pro
   Sample ein `Long`-Modulo (`sampleCount % decimation`) statt zu
   zaehlen; `MixAnalysis.kt:208-236` berechnet pro 1024er-Fenster 36
   Goertzel-Laufe und den `cos()`-Koeffizienten **pro Fenster neu**
   (~74 000 `cos()`-Aufrufe fuer 4 min), obwohl er nur von der Tonhoehe
   abhaengt und einmal pro Track vorberechnet werden koennte.
4. **WorkManager-Dispatch-Latenz vor jedem Analysestart.**
   `TrackAnalysisRepositoryImpl.kt:93-110`: jeder UI-seitige Cache-Miss
   wandert durch `enqueueUniqueWork` + Expedited-Quota (nicht garantiert,
   Rueckfall auf normale Latenz — eigene Doku in Zeile 98-100). Fuer den
   titelwechselkritischen Pfad ist WorkManager die falsche Lane; richtig
   ist er fuer Import-Bulk, Onset-Detection und Retry.
5. **Kein Prewarming, keine Prioritaet.** Die Analyse startet erst, wenn
   ein Titel aktuell wird (`PlayerViewModel.kt:100-106`), und Wisch-
   Sequenzen statieren je Song einen eigenen Unique-Worker ohne Abbruch
   (KEEP-Politik) — der aktuelle Titel konkurriert mit frueher
   ausgeloesten Laeufen um CPU.

**Kein Problem (bewusst nicht angefasst):** das Rendering
(`core/designsystem/.../Waveform.kt`: Geometrie per `derivedStateOf`
gecacht, vorbereitete Balken-Arrays, allokerungsfreier Zeichenpfad —
Abschnitt O in STATUS_FORTSCHRITT), das Bucket-Format (256 Bucklets,
2 Bytes je Bucket via `WaveformCodec`) und der 200-ms-Positions-Ticker.

## Nicht-Ziele

- Kein UI-Redesign des Now-Playing-Screens (dafuer
  `docs/research/RESEARCH_MUSIC_UIUX_2026.md`, Abschnitt 11).
- Keine neuen Analyse-Merkmale (keine zusaetzlichen Metadaten).
- Kein Dekoder-Wechsel in den Pflichtphasen; FFmpeg nativ ist nur
  optionale Phase 6.
- Keine Aenderungen am Wiedergabe-/DSP-Pfad (AudioTrack, Offload,
  Underruns — separate Thematik des Technik-Recherchedokuments).

## Grundregeln

- Nach jeder Phase: `spotlessApply`, betroffene Unit-Tests,
  `:app:assembleDebug`, README-Statustabelle pflegen, Eintrag in
  `docs/STATUS_FORTSCHRITT.md`, committen.
- Reine Signalmathematik bleibt in `:domain:audio` (ADR-0005,
  Modulregel 3.2); `:data:audio` beschafft nur PCM.
- **Ausgabewerte duerfen sich nicht aendern** (Byte-identische
  Waveform-Buckets; BPM/Key/LUFS innerhalb der bisherigen Testtoleranz).
  Nur so bleibt `ANALYZER_VERSION = 4` gueltig und ein Update loest
  keinen Re-Analyse-Sturm ueber die ganze Bibliothek aus. Jede Phase
  verifiziert das gegen Fixtures der Altimplementierung.
- Jede Decoder-Schleife prueft Abbruchkooperation (`ensureActive()` je
  Output-Buffer), damit ein ueberholter Lauf sofort endet.

## Architekturentscheidungen

- **E1 — Zwei-Stufen-Modell.** Stufe 1 = `WAVEFORM_ONLY` (Decode +
  Waveform-Akkumulator + Peak, sofortiger partieller DB-Write, UI zeigt
  Waveform). Stufe 2 = Metadaten (`MIX_METADATA`/`FULL`) als eigener
  Hintergrunddurchgang, der dieselbe Zeile per UPDATE anreichert. Ein
  zweiter Decode fuer Stufe 2 ist bewusst in Kauf genommen: Er ist
  unabhaengig versionierbar, nie UI-kritisch und liefert die Waveform
  (falls noch fehlend) gleich mit.
- **E2 — Getrennte Cache-Versionierung.** Neue Spalte
  `mixAnalyzerVersion INTEGER NOT NULL DEFAULT 0` in `track_analysis`
  (DB v9 -> v10, `MIGRATION_9_10`). `analyzerVersion` gilt weiter
  ausschliesslich fuer Waveform/Peaks; BPM/Key/LUFS-Spalten sind nur
  gueltig, wenn `mixAnalyzerVersion == WaveformCodec.MIX_ANALYZER_VERSION`.
  Das ersetzt das heutige `current`-Semantikspiel in
  `observeAnalysis` (`TrackAnalysisRepositoryImpl.kt:45-69`) durch eine
  explizite, einzeln invalidierbare Version.
- **E3 — In-Process-Prioritaetspfad.** `TrackAnalysisRepositoryImpl`
  erhaelt einen application-weiten CoroutineScope (Muster existiert in
  `data/audio/di`, `SupervisorJob + dispatchers.default`) und eine
  `activeJobs`-Map songId -> Job. Der Lauf zum aktuellen Titel ersetzt
  einen aelteren laufenden (cancel + dedup); ein `Semaphore(2)` begrenzt
  Parallelitaet. WorkManager bleibt fuer `requestAnalysisForNewSongs`
  (Import-Bulk), `requestOnsetDetection` und Retry zustaendig.
- **E4 — Block-API statt Sample-API.** Alle Akkumulatoren erhalten
  `fun acceptBlock(samples: FloatArray, fromIndex: Int, toIndex: Int)`
  (Mono, Float). Der Analyzer liest je Output-Buffer einmalig bulk in ein
  wiederverwendetes FloatArray (`asFloatBuffer().get(...)` bzw.
  `asShortBuffer().get(...)` + Skalierung blockweise) und mischt
  blockweise down. Die einzelnen `accept`-Methoden entfallen; Tests
  werden auf die Block-API migriert (Werte unveraendert). Chroma:
  Zaehler statt Modulo, Goertzel-Koeffizienten einmal pro Track
  vorberechnen.
- **E5 — Queue-Prewarming.** Nach Stufe-1-Fertigstellung des aktuellen
  Titels werden die naechsten K = 2 Queue-Titel per WorkManager
  (non-expedited) zur Stufe-1-Analyse angestossen. Ab dem zweiten Titel
  einer Session ist die Waveform damit praktisch immer sofort da —
  unabhaengig davon, wie schnell die Engine ist.
- **E6 — Messung vor Optimierung.** Stopuhren je Abschnitt (Decode,
  Stufe-1-Akkumulation, Stufe-2-Akkumulation) mit eigenem Logcat-Tag;
  einmalige Baseline-Messung auf dem Referenzgeraet, dokumentiert in
  `docs/research/` oder hier. Ein Analyzer-Macrobenchmark im
  bestehenden `benchmarks/`-Modell haelt die Regressionsueberwachung
  am Leben.

## Datenmodell-Aenderungen

| Aenderung | Ort | Phase |
|---|---|---|
| Spalte `mixAnalyzerVersion INTEGER NOT NULL DEFAULT 0` | `TrackAnalysisEntity`, `DropSyncDatabase` v9 -> v10, `Migrations.kt` (`MIGRATION_9_10`), `MigrationTest` | 2 (umgesetzt) |
| `WaveformCodec.MIX_ANALYZER_VERSION` (Startwert 1) neben `ANALYZER_VERSION` | `TrackAnalyzer.kt` | 2 (umgesetzt) |
| `TrackAnalysis` um `profile`-Rueckgabe/Teilbarkeit erweitern? **Nein** — das UI-Modell bleibt wie es ist; Teilbarkeit entsteht allein durch nullable Spalten + getrennte Versionen | — | — |

## Phasen und Status

### Phase-0-Messnachtrag (31.08.2026)

Die JVM-Baseline fuer die reine Signalmathematik steht
(`TrackAnalysisBaselineTest`, 4 min, 44,1 kHz, musikaehnliches Mono-Signal,
aufgewaermte JIT):

| Abschnitt | Zeit Desktop-JVM | Anteil/Einordnung |
|---|---:|---|
| Waveform + Peak | 227-288 ms | Stufe 1, UI-kritisch |
| Energy/RMS | 37-39 ms | klein |
| Tempo | 63-83 ms | klein |
| Chroma/Goertzel | 397-446 ms | groesster Akkumulator |
| Loudness | 34-48 ms | klein |
| heutiger kombinierter Pfad | 592-745 ms | Untergrenze ohne Decode |
| nur Waveform | 183-227 ms | Untergrenze Stufe 1 |

Die Stufentrennung spart auf der JVM **410-518 ms bzw. 69-70 %** der
Akkumulatorzeit des UI-kritischen Laufs, ohne eine einzige innere Schleife
zu optimieren. Das ist deutlich mehr und risikoaermer als der zuerst geplante
Block-API-Umbau.

Flaschenhals-Hypothese 3 wurde dabei getrennt gemessen: 74.412 `cos()`-Aufrufe
kosten zusammen nur **3,5 ms**. Die Vorberechnung der Goertzel-Koeffizienten
ist korrektes Aufraeumen, aber kein relevanter Performancehebel. Die Zeit
steckt in ca. **76,2 Mio. inneren Goertzel-Schleifendurchlaeufen**. Der Plan
darf den `cos()`-Teil deshalb nicht mehr als Begruendung fuer Phase 1 fuehren.

**Konsequenz fuer die Reihenfolge:** Phase 2 (Profile/Stufentrennung) vor
Phase 1 (Block-API). Die Block-API wird erst gebaut, wenn die noch ausstehende
Geraetemessung zeigt, dass Waveform + Decode das 1,5-s-Ziel verfehlen. Eine
Optimierung der Metadaten-Akkumulatoren verbessert die sichtbare Waveform nach
der Trennung nicht mehr.

**Was weiterhin fehlt:** MediaCodec-Decode und WorkManager-Dispatch laufen
nur auf Android. Erst drei Cold-Cache-Laeufe auf einem Mittelklasse-Geraet
koennen Abbruchkriterium A1 (>80 % Decode) und den verbindlichen 1,5-s-Zielwert
entscheiden. Die JVM-Zahlen sind eine Untergrenze, keine Geraeteprognose.

### Phase-2-Umsetzungsnachtrag (31.08.2026)

Umgesetzt wie geplant, mit einer Abweichung und zwei Praezisierungen.

**Abweichung von E1.** Der Plan sah "Stufe 2 dekodiert erneut und liefert die
Waveform notfalls gleich mit" vor. Umgesetzt ist stattdessen
`MIX_METADATA` = **reiner Metadatenlauf ohne Waveform-Akkumulator**, der die
Zeile per `updateMixMetadata` (echtes SQL-UPDATE) anreichert. Grund: ein
Zweitlauf, der auch Waveform-Bytes schreibt, kann eine bereits sichtbare
Waveform mit einem anderen Ergebnis ueberschreiben — genau das Flackern, das
Stufe 1 verhindern soll. Findet das UPDATE keine Zeile (Stufe 1 noch nicht
fertig oder Cache geloescht), gibt der Worker `Result.retry()` zurueck statt
eine Zeile ohne Waveform anzulegen.

**Reihenfolge ueber WorkManager-Verkettung.** `requestAnalysis` prueft beide
Versionen getrennt und stellt die passenden Auftraege:

- Waveform veraltet -> `beginUniqueWork("track_analysis_<id>")` mit
  `WAVEFORM_ONLY` (expedited), verkettet per `.then(...)` mit `MIX_METADATA`
  (non-expedited). Die Kette garantiert, dass Stufe 2 nie vor Stufe 1 laeuft.
- Nur Metadaten veraltet -> eigener Unique-Work `mix_analysis_<id>`. Ein
  eigener Name ist notwendig: unter `track_analysis_<id>` haette
  `ExistingWorkPolicy.KEEP` den Metadatenlauf verworfen, solange dort noch
  ein Eintrag existiert.
- Beides aktuell -> kein Auftrag.

**Fehlerpfad getrennt.** Ein permanenter Fehler in Stufe 2 schreibt nur
Null-Metadaten mit aktueller `mixAnalyzerVersion` (kein endloser Retry, aber
Waveform bleibt). Ein permanenter Fehler in Stufe 1 schreibt weiter den leeren
Bucket-Eintrag, uebernimmt dabei aber die vorhandenen Metadatenfelder statt
sie zu verlieren.

`requestOnsetDetection` benutzt `FULL`, weil der Nutzer dort explizit einen
Volldurchgang anstoesst.

**Nicht Teil dieser Phase:** die Zeitmessung, ob der Titelwechsel real unter
1,5 s bleibt. Die Kette verkuerzt die Arbeit vor dem ersten DB-Write, aber die
WorkManager-Dispatch-Latenz (Flaschenhals 4) steht unveraendert davor — das
loest erst Phase 3.

### Phase-3-Umsetzungsnachtrag (01.09.2026)

Der UI-kritische Waveform-Lauf hat WorkManager verlassen. `requestAnalysis`
startet Stufe 1 sofort in einem anwendungsweiten Scope
(`SupervisorJob + dispatchers.default`); nur Mix-Metadaten, Import-Bulk und
Onset-Erkennung bleiben aufschiebbar. Damit ist Flaschenhals 4 (Dispatch- und
Expedited-Quota-Latenz vor jedem Analysestart) fuer den sichtbaren Titel
beseitigt — `setExpedited` entfaellt komplett, weil nichts mehr beschleunigt
werden muss, was ohnehin sofort laeuft.

**Cancel-und-Ueberholen statt Warteschlange.** `activeJobs` haelt einen Job
pro Song. Jeder `requestAnalysis`-Aufruf bricht Laeufe zu *anderen* Songs ab
(auch im Cache-Hit-Fall — der Nutzer sieht ja diesen Titel). Ein zweiter
Aufruf zum selben Song startet nichts Neues. `Semaphore(2)` begrenzt
gleichzeitige Decoder: MediaCodec-Instanzen sind knapp, und mehr
Parallelitaet macht den sichtbaren Titel langsamer, nicht schneller.

**Kein Mutex fuer `activeJobs`.** Aufgeraeumt wird in
`Job.invokeOnCompletion`, das nicht suspendieren darf. Ein `Mutex.withLock`
dort wuerde nach einem Abbruch sofort erneut abbrechen und den Eintrag fuer
immer stehen lassen — ein schleichendes Leck, das den Dedup-Check dauerhaft
verfaelscht. Stattdessen `synchronized`; die Kritischen Abschnitte sind reine
Map-Operationen ohne I/O.

**Zwei Extraktionen, beide notwendig geworden:**

- `TrackAnalysisPersister` — die Versions- und Feld-Uebernahmeregeln werden
  jetzt von zwei Aufrufern gebraucht (In-Process-Lauf und Worker). Doppelt
  gepflegt waeren sie die naechste Quelle fuer Zeilen, die `observeAnalysis`
  nie als aktuell akzeptiert.
- `DeferredAnalysisScheduler` — ohne diesen Schnitt ist der
  Prioritaetspfad nicht testbar: jeder Testfall scheiterte an
  `WorkManager.getInstance()` in Robolectric. Die Alternative (WorkManager je
  Test hochziehen) haette Latenz und Nebenlaeufigkeit ins Testbild geholt,
  die fuer die geprueften Regeln irrelevant sind. Sieben Faelle in
  `TrackAnalysisPriorityPathTest` decken Dedup, Abbruch, Profilwahl und beide
  Fehlerarten ab.

**Fehlerbehandlung weicht bewusst vom Worker ab.** Ein voruebergehender
Fehler im In-Process-Pfad schreibt *nichts* und plant *keinen* Retry. Anlass
ist immer eine Nutzeraktion; der naechste Aufruf versucht es erneut. Ein
Backoff-Retry waere hier Ballast — im Worker bleibt er, weil dort niemand
zuschaut.

**Nicht geloest:** der Zielwert selbst. Ohne die Geraetemessung aus Phase 0
ist unbekannt, ob Decode + Stufe 1 unter 1,5 s liegen. Diese Phase entfernt
die Wartezeit *vor* dem Start, nicht die Decode-Dauer.

### Phase-4-Umsetzungsnachtrag (01.09.2026)

`requestAnalysisPrewarm(songs, limit = 2)` bereitet die naechsten Queue-Titel
vor. Der Anstoss sitzt im `PlayerViewModel` und haengt an einer Bedingung, die
den Nutzen erst entstehen laesst: **erst wenn `waveform` fuer den laufenden
Titel `Ready` meldet.** Frueher angestossen konkurrieren die Prewarm-Decodes
mit dem einen Lauf, auf den der Nutzer gerade wartet — Prewarming waere dann
messbar schaedlich statt nuetzlich.

**Nur Waveform, keine Mix-Metadaten.** Eigene Scheduler-Methode
(`schedulePrewarmWaveform`) statt `scheduleWaveformThenMix`: Prewarming
bereitet die Anzeige vor, nicht die Bibliothek. Ein Metadatenlauf je
vorbereitetem Titel waere ein voller zweiter Decode fuer Werte, die noch
niemand sehen will; sie folgen beim echten Titelwechsel ueber
`requestAnalysis`.

**Warum `limit = 2`.** Bei sequenzieller Wiedergabe ist der naechste Titel
immer dabei, auch wenn der Nutzer einmal ueberspringt. Jeder weitere Titel
kostet einen vollen Decode fuer etwas, das moeglicherweise nie erreicht wird.

**Dedup ueber denselben Work-Namen.** `schedulePrewarmWaveform` benutzt
`track_analysis_<id>` mit `ExistingWorkPolicy.KEEP` — laeuft fuer den Titel
schon eine Analyse, wird der Prewarm verworfen. Genau richtig: ein Prewarm hat
nie Vorrang.

**Virtuelle CUE-Tracks werden uebersprungen** (`QueueItem.songId == null`):
ohne MediaStore-ID gibt es keinen Analyse-Cache.

`distinctUntilChanged` auf der Liste der naechsten IDs verhindert eine
Neuplanung bei jedem Queue-Update — Position, Shuffle-Flag und Repeat-Modus
aendern die Nachfolgerliste nicht.

**Was Prewarming nicht kann:** die erste Waveform einer Session. Beim ersten
Titel gibt es keinen Vorgaenger, der ihn vorbereitet haette — dort zaehlt
weiterhin allein die Geschwindigkeit von Decode + Stufe 1.

| Phase | Inhalt | Kernentscheidung | Status |
|---|---|---|---|
| 0 | Messinfrastruktur + Baseline: Timing je Pipeline-Abschnitt (Logcat-Tag `TrackAnalysisTiming`), Baseline-Protokoll Referenztrack (4 min, 44,1 kHz, Cold Cache, 3 Laeufe), optional Macrobenchmark in `benchmarks/` | JVM-Akkumulator-Baseline steht; MediaCodec-/Dispatch-Anteil und verbindlicher Geraetezielwert fehlen | **teilweise** |
| 2 (vorgezogen) | Profile aktivieren + zwei Stufen: `analyze(song, profile: AnalysisProfile)`; Worker mit `KEY_PROFILE`; Stufe-1-Upsert (waveformData, bucketCount, peakLinear, analyzerVersion) sofort, Stufe-2-UPDATE (bpm, camelotKey, Konfidenzen, LUFS, True-Peak, mixAnalyzerVersion) danach; `MIGRATION_9_10` + MigrationTest; `requestAnalysis` prueft Waveform- und Metadaten-Cache getrennt; `observeAnalysis` mappt `mixAnalyzerVersion` | Messung: 69-70 % weniger Akkumulatorarbeit im UI-kritischen Lauf; hoechster Nutzen bei kleinerem Algorithmusrisiko | **umgesetzt** |
| 1 (nach Phase 2) | Block-API + Float in `:domain:audio`: `WaveformAccumulator`, `EnergyAccumulator`, `LoudnessAccumulator`, `TempoAccumulator` (Delegation), `ChromaAccumulator` (Zaehl-Decimation); `TrackAnalyzerImpl`: Bulk-Decode + blockweiser Mono-Downmix in wiederverwendeten Arrays | Nur bauen, falls Geraetemessung nach Stufentrennung das 1,5-s-Ziel verfehlt; `cos()`-Vorbereitung allein spart gemessen nur 3,5 ms | offen |
| 3 | In-Process-Prioritaetspfad: application-weiter Scope + `activeJobs`/`Semaphore` im Repository; `requestAnalysis` laeuft sofort in-process, `requestAnalysisForNewSongs`/`requestOnsetDetection` bleiben aufschiebbar | Cancel-und-Ueberholen statt KEEP-Warteschlange; Prozess-Tod ist unkritisch (Ergebnis lebt nur im DB-Cache, Lauf idempotent wiederholbar) | **umgesetzt** |
| 4 | Queue-Prewarming: Repository-Funktion `requestAnalysisPrewarm(songs: List<Song>, limit = 2)`; Anstoss aus `PlayerViewModel`, sobald Stufe 1 des aktuellen Titels bereit ist; non-expedited, dedupliziert | Versteckt die Restlatenz ab dem zweiten Titel komplett | **umgesetzt** |
| 5 | (optional) Decode/Analyse-Overlap: MediaCodec-Async-Mode oder Producer-Thread -> bounded Channel -> Akkumulator-Konsument | Nur bauen, falls Phase-0/2-Messung dem Decode nennenswerten Anteil jenseits der Akkumulatoren gibt (Abbruchkriterium A1) | offen |
| 6 | (optional, spaeter) Native Peak-Extraktion ueber FFmpeg-JNI (Anschluss an `AUDIO_ENGINE_AUSBAU_PLAN.md` und `docs/ffmpeg-build*.md`): Decode + Min/Max-Bucketing in C, Kotlin-Pfad als Fallback | Loest nebenbei "Formate ohne Plattformdecoder schlagen fehl" (`TrackAnalyzerImpl.kt:27-29`); eigener ADR noetig | offen |
| 7 | Doku-Abschluss: README-Statustabelle, STATUS_FORTSCHRITT, ADR-0015 (Zwei-Stufen-Analyse + getrennte Cache-Versionierung) | — | **umgesetzt** |

## ADR-0015 (geschrieben, Phase 7)

`docs/adr/0015-track-analyse-in-zwei-stufen-mit-getrennter-cache-versionierung.md`
— "Track-Analyse in zwei Stufen mit getrennter Cache-Versionierung":
Stufe 1 (Waveform) ist UI-kritisch und laeuft in-process priorisiert;
Stufe 2 (Mix-Metadaten) ist aufschiebbar (WorkManager) und versioniert
die Metadaten-Spalten eigenstaendig (`mixAnalyzerVersion`), damit
Algorithmus-Aenderungen an BPM/Key/LUFS nie die Waveform-Caches der
ganzen Bibliothek invalidieren.

Das ADR haelt zusaetzlich fest, was dieser Plan zunaechst falsch annahm:
die `cos()`-Vorberechnung als Flaschenhals (gemessen 3,5 ms) und Stufe 2
als Waveform-Mitschreiber (verursacht Flackern). Fuenf Alternativen sind
mit ihrem jeweiligen Nachteil dokumentiert.

## Offener Kernpunkt: die Geraetemessung

Alle Pflichtphasen sind umgesetzt (2, 3, 4, 7); Phase 1 ist konditional
zurueckgestellt, 5 und 6 sind optional. **Der verbindliche Zielwert ist
dennoch unbelegt.** Er blockiert drei Entscheidungen gleichzeitig:

| Offene Frage | Entscheidet ueber |
|---|---|
| Liegen Decode + Stufe 1 unter 1,5 s? | ob der Zielwert erreicht ist oder Phase 1 gebaut wird |
| Liegen > 80 % der Zeit im MediaCodec-Decode (Abbruchkriterium A1)? | Phase 1 gegen Phase 5/6 |
| Wie gross ist der WorkManager-Rest fuer Stufe 2? | ob die Metadaten spuerbar nachlaufen |

Vorgehen: `TrackAnalysisTiming` in Logcat filtern, ein Titel von ~4 min /
44,1 kHz, Cache vorher leeren, drei Laeufe, Median je Abschnitt. Die
JVM-Zahlen aus dem Phase-0-Nachtrag sind eine **Untergrenze**, keine
Geraeteprognose: MediaCodec-Decode und Dispatch fehlen darin vollstaendig.

Solange diese Messung fehlt, ist jede weitere Optimierung Raten — deshalb
sind Phase 1, 5 und 6 bewusst nicht angefangen.

## Verifikation

Pro Phase (PowerShell, `JAVA_HOME` auf das Android-Studio-JBR):

    ./gradlew :domain:audio:test :data:audio:testDebugUnitTest :app:assembleDebug
    ./gradlew :core:database:testDebugUnitTest        # ab Phase 2 (Migration)
    ./gradlew lintDebug --continue                     # ab Phase 2

Zusaetzlich pro Phase:

- Phase 1/2: "Identische Ausgaben"-Tests — dieselben PCM-Fixtures durch
  Alt- und Neuimplementierung (Alt-Klassen bleiben bis Testende als
  `@Deprecated`-Referenz im Testquelltext oder werden als Golden Files
  eingecheckt). Waveform-Buckets byte-identisch, BPM/Key/LUFS wie bisher
  tolerant.
- Phase 2: MigrationTest-Fall "alte Zeile ohne mixAnalyzerVersion ->
  DEFAULT 0, Metadaten gelten als veraltet, Waveform bleibt gueltig".
- Phase 3: Fake-Analyzer-Tests (Dedup, Cancel-und-Ueberholen, keine
  Doppellaeufe); manuell: schnelles Durchwischen durch 10 Titel, Logcat
  zeigt Abbruch der ueberholten Laeufe.
- Phase 4: manuell: erste Wiedergabe eines Titels, naechster Titel
  sofort Waveform (Cache-Hit im Log).
- Messprotokoll je Phase gegenueber Phase-0-Baseline (gleicher
  Referenztrack, Cold Cache, 3 Laeufe, Median).

## Risiken und Eskalationen

- **Float-Umstellung veraendert Metadaten ausserhalb der Toleranz** ->
  Eskalation: `MIX_ANALYZER_VERSION` bzw. `ANALYZER_VERSION` bumpen und
  Re-Analyse bewusst akzeptieren; dann Prewarm-Storm nach Update
  begrenzen (z. B. Metadaten-Stufe nur im Laden/WiFi-Ladezustand).
- **Migration 8 -> 9** ist klein (eine DEFAULT-0-Spalalte) und ueber den
  bestehenden MigrationTest-Mechanismus abgesichert.
- **In-Process-Analyse bei Prozess-Tod**: kein Verlust moeglich (Cache
  ist DB, Lauf idempotent); WorkManager-Retry bleibt als Netz fuer
  dauerhafte Faelle.
- **Abbruchkriterium A1**: Zeigt die Phase-0-Messung, dass > 80 % der
  Zeit im MediaCodec-Decode (nicht in den Akkumulatoren) liegt, Phase 1
  auf die Bulk-Decode-Haelfte reduzieren und stattdessen Phase 5/6
  vorziehen.

## Verbindungen zu anderen Plaenen/Dokumenten

- `MIX_TRANSITIONS_AUSBAU_PLAN.md`: BPM/Key-Spalten stammen aus dessen
  Phase 1 — Stufe 2 versioniert sie kuenftig unabhaengig
  (`mixAnalyzerVersion`).
- `AUDIO_ENGINE_AUSBAU_PLAN.md` / `OFFTRACK_AUDIO_UMBAUHANDBUCH.md` /
  `docs/ffmpeg-build*.md`: Phase 6 (FFmpeg-JNI) dockt an die dortige
  Build-Infrastruktur an.
- `docs/research/RESEARCH_MUSIC_TECHNIK_2026.md`: Belege fuer
  Peak-Extraktion offline + persistieren (bereits so), Vermeiden der
  `Visualizer`-API (RECORD_AUDIO-Pflicht — wird nicht verwendet),
  Positions-State nur in der Draw-Phase lesen (Rendering bereits so).
- `docs/research/RESEARCH_MUSIC_UIUX_2026.md` Abschnitt 11/E+K: Waveform
  soll beim Titelwechsel sofort da sein (hier: E1/E5) — UI-seitige
  Aufruestung (ProgressSlider, Shared Elements) ist separates Thema.
