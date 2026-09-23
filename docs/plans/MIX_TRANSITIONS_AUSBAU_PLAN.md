# DropSync/FlowRep Mix-Uebergaenge — Ausbauplan (korrigierte Fassung, Entwurf)

Stand: 28.07.2026, Repo-Stand `1bf1a94` (FlowRep Design Phase 6).
Status: Entwurf, noch nicht umgesetzt, zur Freigabe. Diese Fassung
ersetzt den urspruenglichen Entwurf vom selben Tag; die Aenderungen
gegenueber dem Original sind im Abschnitt "Review-Ergebnis" belegt.

**Update (28.07.2026, nach Freigabe): Phasen 2 und 3 sind umgesetzt.**
Projektinhaber-Entscheidung bei der Freigabe: Die Steuerung liegt
**global in den Einstellungen** (an/aus, Uebergangsstil, Dauer) statt
pro Playlist. Damit entfaellt die geplante Spalte
`playlists.mix_preset`; das Preset ist Teil der `DspConfig`
(DataStore `audio_dsp`, Schluessel `mix_preset`, stabiler Enum-Name)
und wandert ueber den bestehenden Konfigurationsfluss
(`AudioPipeline.currentConfig`) in den `CrossfadeController`. An/aus
ist `crossfadeSeconds > 0` (Einschalten setzt 6 s Standard). Phase 1
(BPM/Tonart-Analyse) und die BPM/Key-Badges aus Phase 3 bleiben
Entwurf; die zugehoerige Migration reduziert sich auf die zwei
`track_analysis`-Spalten.

Basiert auf tatsaechlich gelesenem Code (CrossfadeController.kt,
CrossfadeCurves.kt, MasterDspProcessor.kt, AudioPipeline.kt,
DspRenderersFactory.kt, PlaybackService.kt, DropRestRequestBus.kt,
Migrations.kt, TrackAnalyzer.kt, TrackAnalysisMath.kt,
TrackAnalyzerImpl.kt, TrackAnalysisDao.kt, TrackAnalysisRepositoryImpl.kt,
TrackAnalysisEntity.kt, RestMusicCoordinator.kt, PlayerViewModel.kt,
NowPlayingScreen.kt), nicht nur auf der README-Statustabelle.

## Review-Ergebnis (28.07.2026, Verifikation gegen `1bf1a94`)

Alle sieben Architekturbefunde des Originals wurden am Code bestaetigt.
Fuenf Punkte des Originals waren fehlerhaft oder unvollstaendig und sind
in dieser Fassung korrigiert:

1. **Equal-Power-Regression behoben:** Das Original bildete den
   Fade-Out als lineares Komplement `1 - fadeInGain(t)` — das verletzt
   die Equal-Power-Invariante `fadeIn^2 + fadeOut^2 = 1` aus
   `CrossfadeCurves` und haette beim Default-Preset FADE einen
   hoerbaren Pegel-Einbruch (~2.3 dB bei t = 0.5) eingefuehrt. Neu:
   generisches Equal-Power-Komplement `sqrt(1 - fadeIn(t)^2)` (siehe
   Phase 2); fuer FADE reproduziert das exakt den bestehenden Cosinus.
2. **Drei identische Presets behoben:** FADE, BLEND und WAVE waren im
   Original wortgleich dieselbe Sinuskurve. Neu: sechs tatsaechlich
   verschiedene Fade-In-Kurven (Phase 2).
3. **Analyse-Ausloeser ergaenzt:** Das Original behauptete, die Analyse
   werde vom SAF-Bibliotheksscan angestossen. Tatsaechlich ruft nur der
   Now-Playing-Screen `requestAnalysis` pro geoeffnetem Song auf — die
   BPM/Key-Badges (Phase 3) blieben sonst fuer fast alle Tracks leer.
   Neu: Batch-Trigger beim Aktivieren des Mix-Schalters (Phase 3).
4. **Preset-Persistenz festgelegt:** Das Original liess offen, wo das
   pro Playlist gewaehlte Preset gespeichert wird. Neu: additive Spalte
   `playlists.mix_preset` in derselben Migration v4->v5 (Phase 1).
5. **FlowRep-Design verbindlich:** Seit dem Original sind fuenf
   FlowRep-Design-Commits (Phasen 2-6, Rebrand, Raleway, Tokens,
   Marken-Komponenten) gelandet. Phase 3 nutzt verbindlich die neuen
   Tokens/Komponenten aus `FLOWREP_DESIGN_PLAN.md` und ist mit dem
   Design-Strang zu koordinieren (dort ist Phase 7 noch offen).

## Ausgangslage und Nicht-Ziel

Anstoss war eine Recherche zu Spotifys "Mix"-Feature (seit 08/2025,
Desktop seit 05/2026): BPM/Key-Anzeige pro Track, Presets Fade/Rise/
Blend/Wave/Melt/Slam, editierbare Volume-/EQ-/Effect-Kurven,
Wellenform-Editor mit Beat-Markern, Smart Reorder.

Nicht-Ziel: keine Spotify-Konto-/API-Anbindung, keine Cloud-Analyse.
Die App bleibt lokal-first und komplett offline (Bauplan-Regel "kein
Netzwerk/Analytics"). Uebernommen wird nur das Konzept: BPM-/tonart-
bewusste Uebergaenge zwischen lokalen Tracks, mit benannten Presets
statt eines einzigen Equal-Power-Fades.

## Ziel

`CrossfadeController`/`CrossfadeCurves` von einer festen Equal-Power-
Rampe zu mehreren waehlbaren Uebergangs-Presets erweitern, gestuetzt auf
eine lokale BPM-/Tonart-Analyse pro Track, die additiv an den bereits
bestehenden Analyse-Cache (`track_analysis`, Marker/Waveform-Plan
Phase 2) andockt statt einen zweiten Cache/Decoder-Pfad zu bauen.
Optional (spaete Phase) Anbindung an den bestehenden `crossfadeTo`/
`RestMusicCoordinator`-Pfad (Musik-Workout-Plan Phase 4) fuer die
Drop-Landung.

## Zentrale Architekturbefunde (verifiziert gegen `1bf1a94`)

1. `CrossfadeController` kennt genau eine Kurve (`CrossfadeCurves`,
   Sinus/Cosinus, Equal-Power). Fortschritt `t` (0..1) wird pro Schritt
   berechnet (`STEP_MS = 50`) und nur auf `mainPlayer.volume`/
   `secondary.volume` angewendet — ein einzelner Float pro Spieler.
2. Der Zweitspieler bekommt in `PlaybackService.kt` (Z. 144-160) keine
   `DspRenderersFactory` und keine `audioProcessors` — reiner
   `ExoPlayer.Builder` mit AudioAttributes. Lautstaerke-Uebergaenge
   funktionieren architektonisch schon heute; EQ-/Filter-Uebergaenge
   braeuchten eine zweite DSP-Kette (echte Abweichung vom
   Ein-Ketten-Design aus ADR-0005, nur mit ADR-0013, Phase 5).
3. Analyse-Cache `track_analysis` (DB v3, ADR-0011) existiert mit
   `TrackAnalyzer`-Vertrag, `WaveformAccumulator`/`EnergyAccumulator`
   und dedupliziertem `TrackAnalysisWorker` — aber ohne BPM/Tonart.
   BPM/Key werden additiv in denselben Cache und denselben
   Decode-Durchgang eingehaengt ("ein Durchgang, nicht zwei
   Decoder-Pfade").
4. `shouldCrossfade()` (Album-/CUE-Gate) bleibt die einzige Instanz, ob
   ueberhaupt geblendet wird. Mix-Presets liegen auf diesem Gate.
5. `CrossfadeController.crossfadeTo()` existiert als oeffentliche
   Methode neben `beginFade()`; einziger Drop-Landungs-Aufrufer ist
   `RestMusicCoordinator.onRestBegin` (Z. 162). Das ist der
   Anknuepfungspunkt fuer Phase 6 — nicht `DropRestRequestBus`.
6. `requestAnalysis` wird ausschliesslich vom Now-Playing-Screen
   aufgerufen (on-demand pro Song), nicht vom Bibliotheksscan.
7. Aktuelle DB-Version: 4 (v2 `exercise_rest_prefs`, v3
   `track_analysis`, v4 Playlist-Labels). Additive Spalten brauchen
   Migration v4->v5. Hoechste vergebene ADR-Nummer: 0012.

## Architekturentscheidungen

- **Volume zuerst, DSP spaeter getrennt.** Phasen 1-4 liefern alle
  sechs Presets nur als Volume-Kurven-Varianten — keine Aenderung an
  `MasterDspProcessor`, keine neue ADR, bleibt in `:domain:audio`/
  `:data:playback`. Echte EQ-/Filter-Automation ist Phase 5, explizit
  optional und ADR-0013-pflichtig.
- **Equal-Power ist invariant:** Jedes Preset (ausser SLAM) leitet
  seinen Fade-Out als `sqrt(1 - fadeIn(t)^2)` ab. Damit gilt die
  dokumentierte Invariante aus `CrossfadeCurves` fuer jede Kurvenform,
  und FADE bleibt bitidentisch zum heutigen Verhalten (kein
  Regressionsrisiko fuer bestehende Tests).
- BPM/Key-Analyse: reines Kotlin, on-device, im bestehenden
  MediaExtractor/MediaCodec-Durchgang (ADR-0011). Bewusst keine native
  Zusatzabhaengigkeit (Superpowered SDK o. ae.) — dieselbe
  NDK-Problematik wie bei der FFmpeg-Extension. Kompromiss: geringere
  Genauigkeit, vertretbar fuer Sortierung/Anzeige, nicht fuer
  beat-exaktes DJ-Mixing.
- **`track_analysis` erweitern statt neue Tabelle**: additive Spalten
  `bpm`/`camelot_key`; zusaetzlich `playlists.mix_preset` (TEXT,
  nullable; `NULL` = Mix aus, sonst stabiler Preset-Name — Regel
  "Enums als stabile Strings"). Eine Migration v4->v5 fuer alle drei
  Spalten.
- Cache-Invalidierung ueber `ANALYZER_VERSION` 1 -> 2. Folge: auch
  alle Waveforms gelten als veraltet. Abfederung: `observeAnalysis`
  liefert eine veraltete Version weiterhin als Anzeige-Fallback aus,
  solange die Neuberechnung laeuft (`requestAnalysis` stoesst sie an);
  nur BPM/Key bleiben bis dahin `null`. So verschwinden keine
  Waveforms beim Update.
- Kein neues Feature-Modul. UI in `:feature:library` (Playlist-Detail:
  Badge, Mix-Umschalter, Preset-Wahl) und `:feature:player` (Anzeige
  des aktiven Uebergangs), mit FlowRep-Tokens (`Spacing`, `Type`,
  `BrandCard`, Chips gemaess `FLOWREP_DESIGN_PLAN.md`).
- Preset-Verdrahtung UI -> Service nutzt denselben Pfad wie
  `crossfadeSeconds`: DSP-/Playback-Konfiguration, die der Service
  bereits per `audioPipeline.currentConfig.collect` beobachtet — kein
  neuer Kommunikationskanal.
- **`crossfadeTo`-Anbindung = Phase 6, explizit optional.** Beruehrt
  den aktiv entwickelten Musik-Workout-Pfad — Koordination Pflicht.

## Phasen und Status

| Phase | Inhalt | Status |
|---|---|---|
| 1 | BPM/Key-Analyse: `TrackAnalyzer`/`TrackAnalysisRepositoryImpl` additiv erweitern (bpm/camelotKey), Migration v4->v5 (nur noch `track_analysis.bpm`/`camelot_key`), laeuft im vorhandenen Decode-Durchgang mit | Entwurf |
| 2 | Preset-Modell: `MixPreset`-Enum mit sechs unterscheidbaren Kurven + generischem Equal-Power-Komplement, `CrossfadeController` (`beginFade` + `crossfadeTo`) auf Strategie umgestellt, SLAM-Mikro-Rampe (`smoothedGain`), `DspConfig.mixPreset` inkl. Persistenz + Profil-Codec | Umgesetzt |
| 3 | UI: Abschnitt "Mix-Uebergaenge" in den Einstellungen (an/aus, sechs Preset-Chips mit Erklaertext, Dauer-Slider 1-12 s, Bit-Perfect-Hinweis), Strings DE/EN | Umgesetzt (globale Einstellungen statt pro Playlist, Nutzerentscheidung; BPM/Key-Badge + Batch-Analyse folgen mit Phase 1) |
| 4 | Tests: `MixPresetTest` (Equal-Power-Eigenschaftstest, FADE bitidentisch, Monotonie, paarweise Verschiedenheit), `CrossfadeControllerTest` (SLAM-Mikro-Rampe, Glaettung stetiger Kurven), `DspConfigCodecTest` (Roundtrip, Rueckfall, defekter Wert) | Umgesetzt (Analyzer-/Migrationstests folgen mit Phase 1) |
| 5 (optional) | Zweite DSP-Kette fuer Zweitspieler -> echte EQ-/Filter-Uebergaenge; braucht ADR-0013 | Nicht geplant, nur beschrieben |
| 6 (optional) | `PlaybackRepository.crossfadeTo`/`RestMusicCoordinator` um optionalen `MixPreset` erweitern (Drop-Landung mit Preset) | Nicht geplant, nur beschrieben |

---

## Phase 1 — BPM/Key-Analyse (additiv auf bestehendem TrackAnalyzer)

**Domain (`:domain:audio`), `TrackAnalyzer.kt`: bestehende
`TrackAnalysis`-Datenklasse bekommt zwei neue, nullable Felder:**

```kotlin
data class TrackAnalysis(
    val waveformBuckets: List<WaveformBucket>,
    val onsetCandidatesMs: List<Long>,
    /** null, solange die Tempo-Erkennung nicht sicher genug ist. */
    val bpm: Float? = null,
    /** Camelot-Notation, z. B. "8A"; null wenn nicht sicher bestimmbar. */
    val camelotKey: String? = null,
)
```

`interface TrackAnalyzer`/`TrackAnalysisRepository` bleiben
unveraendert; dieselbe `analyze(song)`-Signatur liefert zwei
zusaetzliche Werte im vorhandenen Ergebnistyp.

**Domain-Mathematik (`TrackAnalysisMath.kt`), zwei neue Akkumulatoren
nach dem Muster `WaveformAccumulator`/`EnergyAccumulator`:**

- `TempoAccumulator(sampleRateHz)`: Tempo aus Inter-Onset-Intervallen
  (wiederverwendet die Novelty-Logik aus `OnsetDetection`): Histogramm
  der Intervalle zwischen Energie-Anstiegen, staerkster Bin im Fenster
  60-200 BPM. Bekannte Schwaeche Oktavfehler (Halb-/Doppeltempo): das
  Fenster faltet 2x/0.5x-Harmonische hinein; die Tests in Phase 4
  akzeptieren gefaltete Harmonische explizit als korrekt.
- `ChromaAccumulator(sampleRateHz)`: 12-Bin-Chromagramm (Goertzel je
  Halbton ueber mehrere Oktaven, fensterweise) + Korrelation gegen
  Krumhansl-Schmuckler-Dur-/Moll-Profile, Ergebnis als
  Camelot-Notation. Deutlich rechenintensiver als die
  Peak-Akkumulation — deshalb bekommt Phase 4 einen
  Durchsatz-Waechter (Analyse eines 4-min-Tracks muss schneller als
  Echtzeit bleiben, analog `DspPerformanceTest`-Prinzip).

(Konkrete Algorithmen sind ein Vorschlag; vor Umsetzung an
Referenztracks mit bekanntem BPM/Key pruefen, siehe Phase 4.)

**`TrackAnalyzerImpl.decodeAndAccumulate` (`:data:audio`): nur die
Zeilen mit den neuen Akkumulatoren sind neu, der MediaExtractor/
MediaCodec-Loop bleibt unveraendert** (`tempo.accept(sample)`/
`chroma.accept(sample)` im bestehenden Sample-Loop, am Ende
`bpm = tempo.finish()`, `camelotKey = chroma.finish()`).

**Persistenz — `TrackAnalysisEntity` additiv um `bpm` (REAL, nullable)
und `camelot_key` (TEXT, nullable) erweitern; `equals()`-Sonderfall
fuer das ByteArray bleibt.**

**Migration `MIGRATION_4_5`, additiv:**

```kotlin
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `bpm` REAL")
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `camelot_key` TEXT")
            // Mix-Einstellung pro Playlist: NULL = aus, sonst stabiler
            // MixPreset-Name (Regel "Enums als stabile Strings").
            db.execSQL("ALTER TABLE `playlists` ADD COLUMN `mix_preset` TEXT")
        }
    }
```

`DROPSYNC_MIGRATIONS` ergaenzen, `version = 5`, Schema-Export `5.json`.

**Cache-Invalidierung: `WaveformCodec.ANALYZER_VERSION` 1 -> 2**, mit
der oben beschriebenen Abfederung: veraltete Eintraege bleiben als
Anzeige-Fallback sichtbar (Waveform), loesen aber bei `requestAnalysis`
eine Neuberechnung aus; `TrackAnalysisWorker` schreibt BPM/Key im
selben Upsert mit.

## Phase 2 — Preset-Modell (Volume-Kurven, Equal-Power-invariant)

**`CrossfadeCurves.kt` erweitern statt ersetzen** (bestehende
`fadeInGain`/`fadeOutGain` bleiben unveraendert und sind der
FADE-Default):

```kotlin
/** Benannte Uebergangs-Presets (Mix-Ausbau Phase 2, Volume-Ebene). */
enum class MixPreset {
    FADE, RISE, BLEND, WAVE, MELT, SLAM;

    /** Fortschritt 0..1 -> Lautstaerke des startenden Titels. */
    fun fadeInGain(progress: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        return when (this) {
            // Bestand: Sinus-Viertelperiode (Equal-Power-Klassiker).
            FADE -> CrossfadeCurves.fadeInGain(t)
            // Spaeter Einsatz, steiles Ende ("aufsteigend").
            RISE -> t * t
            // Lineare Amplitude: neutraler, gleichmaessiger Anstieg.
            BLEND -> t
            // Wellenfoermiges Anschwellen: Fortschritt mit einer
            // Sinuswelle moduliert (monoton, aber atmend).
            WAVE -> CrossfadeCurves.fadeInGain((t - 0.10 * sin(2.0 * PI * t)).coerceIn(0.0, 1.0))
            // Frueher Einsatz, langes gemeinsames Ausklingen.
            MELT -> sqrt(t)
            // Harter Schnitt in der Mitte; Mikro-Rampe siehe unten.
            SLAM -> if (t < 0.5) 0.0 else 1.0
        }
    }

    /**
     * Equal-Power-Komplement: fadeIn^2 + fadeOut^2 = 1 fuer jede
     * Kurvenform (fuer FADE ergibt das exakt den bestehenden Cosinus).
     * SLAM ist bewusst die Ausnahme (Schnitt statt Blende).
     */
    fun fadeOutGain(progress: Double): Double {
        if (this == SLAM) return if (progress < 0.5) 1.0 else 0.0
        val g = fadeInGain(progress)
        return sqrt((1.0 - g * g).coerceAtLeast(0.0))
    }
}
```

(Kurvenformen fuer RISE/BLEND/WAVE/MELT sind ein Hoervorschlag, kein
Nachbau von Spotify-Code — zur Probe hoeren und nachjustieren. Die
Equal-Power-Invariante ist dagegen nicht verhandelbar.)

**SLAM-Klickschutz:** Der 0<->1-Sprung wird nicht in einem einzelnen
50-ms-Schritt vollzogen, sondern ueber zwei `STEP_MS`-Schritte linear
gerampt (100 ms Mikro-Rampe um t = 0.5), um Knackser zu vermeiden.
Das ist Implementierungsdetail des Controllers, nicht der Kurve.

**`CrossfadeController` auf Strategie-Parameter umstellen:** Feld
`sessionPreset: MixPreset = MixPreset.FADE` + `setPreset()`; in
`beginFade()` und `crossfadeTo()` ersetzen genau die zwei Zeilen
`CrossfadeCurves.fadeXGain(t)` durch `sessionPreset.fadeXGain(t)`.
Polling, Zweitspieler-Lifecycle und Gapless-Gate bleiben unveraendert.
Der Preset-Wert erreicht den Service ueber denselben Konfigurationsfluss
wie `crossfadeSeconds` (`audioPipeline.currentConfig.collect` in
`PlaybackService`); Quelle ist das aktive Playlist-`mix_preset`
(NULL -> FADE-Verhalten des Bestands, Mix-Schalter aus).

## Phase 3 — UI (FlowRep-Design, kein Wellenform-Editor)

- Bibliotheks-/Playlist-Liste: kleines Badge "128 BPM / 8A" pro Track
  (aus `track_analysis`; `null` -> kein Badge statt Platzhalter).
  Gestaltung mit FlowRep-Tokens (`Spacing`, Caps-Label-Typo aus
  `Type.kt`), analog zu den bestehenden Meta-Zeilen aus
  Design-Phase 5.
- Playlist-Detail: Umschalter "Mix" (an/aus) + Preset-Auswahl als
  Chip-Reihe (sechs Namen), persistiert in `playlists.mix_preset`.
  Wirkt nur, wenn `shouldCrossfade()` ohnehin zulaesst.
- **Batch-Analyse-Trigger:** Beim Aktivieren des Mix-Schalters werden
  alle Songs der Playlist per `requestAnalysis` eingereiht (der
  bestehende Worker dedupliziert ueber `track_analysis_<songId>`);
  Badges erscheinen fortlaufend, sobald Analysen fertig sind.
  Zusaetzlich bleibt der bestehende On-Demand-Pfad im Now-Playing.
- Koordination: `:feature:library`/`:feature:player` sind zugleich
  Gegenstand des FlowRep-Design-Strangs (dort Phase 7 offen) — vor
  Umsetzung kurz abstimmen, um parallele Aenderungen an denselben
  Screens zu vermeiden.
- Bewusst kein Sortier-Algorithmus ("Smart Reorder") — siehe "Nicht
  Teil dieses Plans".

## Phase 4 — Tests

- `TempoAccumulatorTest`/`ChromaAccumulatorTest` gegen synthetische
  Signale und 5-10 Referenztracks mit bekanntem BPM/Key. Toleranz
  +-2 BPM; **2x/0.5x-Oktav-Harmonische gelten als bestanden**, wenn
  sie ins 60-200-Fenster gefaltet dem Referenzwert entsprechen.
- **Analyse-Durchsatz-Waechter:** kompletter Analysedurchgang (inkl.
  Chroma) eines 4-min-Referenzsignals schneller als Echtzeit auf der
  JVM — verhindert, dass die Chroma-Berechnung den Hintergrund-Worker
  praktisch unbrauchbar macht.
- Bestehende `TrackAnalyzerImpl`-/Repository-Tests um die neuen Felder
  erweitern — keine Regression bei Waveform/Onset (geteilter
  Durchgang); zusaetzlich Test fuer den Anzeige-Fallback veralteter
  Analysen (alte Version sichtbar, Neuberechnung angestossen).
- `CrossfadeControllerTest` je `MixPreset` fuer `beginFade` UND
  `crossfadeTo`; Eigenschaftstest der Equal-Power-Invariante
  (`fadeIn^2 + fadeOut^2 == 1` fuer alle Presets ausser SLAM, ueber
  das ganze t-Raster); FADE bitidentisch zum Bestand; keine
  Regressionen an Gapless-/CUE-Gate oder Drop-Landung.
- `MigrationTest` 4 -> 5 gegen exportiertes `5.json` (alle drei neuen
  Spalten).
- Architektur-Test und `spotlessCheck` gruen.

## Phase 5 (optional, nicht geplant) — echte EQ-/Filter-Uebergaenge

Zweitspieler bekaeme eigene `DspRenderersFactory` +
`MasterDspProcessor`-Instanz fuer Bass-Swap/Filter-Sweeps pro Stream.
Kosten: zweite 64-Bit-DSP-Kette parallel waehrend jeder Ueberblendung
(CPU-Budget pruefen, `DspPerformanceTest`-Grenzwert sinngemaess),
echte Abweichung vom Ein-Ketten-Designziel aus ADR-0005 -> braucht
ADR-0013 nach dem Muster von ADR-0007/ADR-0010. Hier bewusst nur
beschrieben.

## Phase 6 (optional, nicht geplant) — Kopplung an die Drop-Landung

`CrossfadeController.crossfadeTo(next, startPositionMs)` und
`PlaybackRepository.crossfadeTo(song, startPositionMs)` um einen
optionalen Parameter `preset: MixPreset = MixPreset.FADE` erweitern
(Default = bisheriges Verhalten; alle bestehenden Aufrufer inkl.
Test-Fakes bleiben unveraendert lauffaehig). `RestMusicCoordinator.
onRestBegin` (einziger Drop-Landungs-Aufrufer) koennte dann z. B.
`MixPreset.RISE` fuer den vorgezogenen Work-Titel-Start uebergeben.
Kein Anfassen von `DropRestRequestBus`, `TimerEngine` oder
`RestMusicSettingsRepository`. `RestMusicCoordinator` wird aktiv von
einem anderen Agenten weiterentwickelt — vor dieser Phase abstimmen,
nicht vor Phasen 2-4 beginnen.

## Nicht Teil dieses Plans

- **Smart Reorder** (automatische Playlist-Umsortierung nach BPM/Key):
  kein direkter Nutzen ohne Wellenform-Editor, deutlicher
  Zusatzaufwand — ausgeklammert. Nicht zu verwechseln mit dem
  bestehenden `SmartShuffle` (`:domain:library`, Musik-Workout-Plan
  A5), der nach Play-Count/Favoriten gewichtet.
- **Wellenform-Editor mit verschiebbaren Beat-Markern:** groesster
  UI-Aufwand, kleinster Beitrag zum Kernnutzen — ausgeklammert.
- Jede Form von Spotify-Konto-/API-Anbindung.

## Offene Entscheidungen vor Start

1. ~~PCM-Zugriff~~ — beantwortet durch ADR-0011 (MediaExtractor/
   MediaCodec, derselbe Decoder-Loop).
2. ~~Eigen vs. Bibliothek~~ — beantwortet: reine Kotlin/JVM-Mathematik
   wie der bestehende Analyzer; die Genauigkeit sichern die
   Referenztrack-Tests in Phase 4.
3. ~~Persistenz des Playlist-Presets~~ — entschieden: additive Spalte
   `playlists.mix_preset` in Migration v4->v5 (diese Fassung).
4. Bauplan-Schritt-Nummer: kein neuer Schritt; Praezedenzfall
   Marker/Waveform- und Musik-Workout-Plan (eigener benannter Plan +
   ADRs, ohne Schritt-Nummer). Eigene ADR erst noetig, wenn Phase 5
   (zweite DSP-Kette) beauftragt wird (dann ADR-0013).
5. Koordination: `RestMusicCoordinator`/`crossfadeTo` (Phase 6) und
   die FlowRep-Design-Arbeit an `:feature:library`/`:feature:player`
   (Phase 3) sind aktiv in fremder Bearbeitung — vor diesen Phasen
   jeweils kurz abstimmen.
