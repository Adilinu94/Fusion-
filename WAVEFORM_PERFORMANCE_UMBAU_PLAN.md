# DropSync Waveform-/Analyse-Performance — Umbauplan

Stand: 21.08.2026. Basis ist eine vollstaendige Code-Analyse der
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
  (DB v8 -> v9, `MIGRATION_8_9`). `analyzerVersion` gilt weiter
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
| Spalte `mixAnalyzerVersion INTEGER NOT NULL DEFAULT 0` | `TrackAnalysisEntity`, `DropSyncDatabase` v8 -> v9, `Migrations.kt` (`MIGRATION_8_9`), `MigrationTest` | 2 |
| `WaveformCodec.MIX_ANALYZER_VERSION` (Startwert 1) neben `ANALYZER_VERSION` | `TrackAnalyzer.kt` | 2 |
| `TrackAnalysis` um `profile`-Rueckgabe/Teilbarkeit erweitern? **Nein** — das UI-Modell bleibt wie es ist; Teilbarkeit entsteht allein durch nullable Spalten + getrennte Versionen | — | — |

## Phasen und Status

| Phase | Inhalt | Kernentscheidung | Status |
|---|---|---|---|
| 0 | Messinfrastruktur + Baseline: Timing je Pipeline-Abschnitt (Logcat-Tag `TrackAnalysisTiming`), Baseline-Protokoll Referenztrack (4 min, 44,1 kHz, Cold Cache, 3 Laeufe), optional Macrobenchmark in `benchmarks/` | Zielwert (<= 1,5 s?) wird hieraus verbindlich festgelegt; Abbruchkriterium A1 geprueft | offen |
| 1 | Block-API + Float in `:domain:audio`: `WaveformAccumulator`, `EnergyAccumulator`, `LoudnessAccumulator`, `TempoAccumulator` (Delegation), `ChromaAccumulator` (Zaehl-Decimation, vorberechnete Goertzel-Koeffizienten); `TrackAnalyzerImpl`: Bulk-Decode + blockweiser Mono-Downmix in wiederverwendeten Arrays | Ausgaben byte-/wertidentisch zur Altimplementierung (Fixtures); kein `ANALYZER_VERSION`-Bump | offen |
| 2 | Profile aktivieren + zwei Stufen: `analyze(song, profile: AnalysisProfile)`; Worker mit `KEY_PROFILE`; Stufe-1-Upsert (waveformData, bucketCount, peakLinear, analyzerVersion) sofort, Stufe-2-UPDATE (bpm, camelotKey, Konfidenzen, LUFS, True-Peak, mixAnalyzerVersion) danach; `MIGRATION_8_9` + MigrationTest; `requestAnalysis` prueft Waveform- und Metadaten-Cache getrennt; `observeAnalysis` mappt `mixAnalyzerVersion` | `AnalysisProfile` (bereits vorhanden) wird endlich benutzt; Interface-Doku wird wahr | offen |
| 3 | In-Process-Prioritaetspfad: DI-Scope + `activeJobs`/`Semaphore` im Repository; `ensureActive()` je Buffer im Drain; `requestAnalysis` laeuft sofort in-process, `requestAnalysisForNewSongs`/`requestOnsetDetection` bleiben auf WorkManager | Cancel-und-Ueberholen statt KEEP-Warteschlange; Prozess-Tod ist unkritisch (Ergebnis lebt nur im DB-Cache, Lauf idempotent wiederholbar) | offen |
| 4 | Queue-Prewarming: Repository-Funktion `requestAnalysisPrewarm(songs: List<Song>, limit = 2)`; Anstoss aus `PlayerViewModel`, sobald Stufe 1 des aktuellen Titels bereit ist; non-expedited, dedupliziert | Versteckt die Restlatenz ab dem zweiten Titel komplett | offen |
| 5 | (optional) Decode/Analyse-Overlap: MediaCodec-Async-Mode oder Producer-Thread -> bounded Channel -> Akkumulator-Konsument | Nur bauen, falls Phase-0/2-Messung dem Decode nennenswerten Anteil jenseits der Akkumulatoren gibt (Abbruchkriterium A1) | offen |
| 6 | (optional, spaeter) Native Peak-Extraktion ueber FFmpeg-JNI (Anschluss an `AUDIO_ENGINE_AUSBAU_PLAN.md` und `docs/ffmpeg-build*.md`): Decode + Min/Max-Bucketing in C, Kotlin-Pfad als Fallback | Loest nebenbei "Formate ohne Plattformdecoder schlagen fehl" (`TrackAnalyzerImpl.kt:27-29`); eigener ADR noetig | offen |
| 7 | Doku-Abschluss: README-Statustabelle, STATUS_FORTSCHRITT, ADR-0015 (Zwei-Stufen-Analyse + getrennte Cache-Versionierung) | — | offen |

## ADR-0015 (in Phase 7 zu schreiben; vorlaeufiger Titel)

"Track-Analyse in zwei Stufen mit getrennter Cache-Versionierung":
Stufe 1 (Waveform) ist UI-kritisch und laeuft in-process priorisiert;
Stufe 2 (Mix-Metadaten) ist aufschiebbar (WorkManager) und versioniert
die Metadaten-Spalten eigenstaendig (`mixAnalyzerVersion`), damit
Algorithmus-Aenderungen an BPM/Key/LUFS nie die Waveform-Caches der
ganzen Bibliothek invalidieren. Alternativen (ein gemeinsamer Lauf wie
heute; zwei Entitaeten statt Spaltenversionen) und ihre Nachteile werden
im ADR festgehalten.

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
