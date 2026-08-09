# DropSync Marker- und Waveform-Ausbau — Plan (rekonstruiert)

Stand: 28.07.2026. Der urspruengliche Plan wurde nie committed (die
README referenziert ihn als `MARKER_UND_WAVEFORM_AUSBAU_PLAN.md`);
diese Fassung ist eine Rekonstruktion ausschliesslich aus belegten
Quellen: README-Statustabelle, ADR-0011 und gelesenem Code
(TrackAnalyzer.kt, TrackAnalysisMath.kt, TrackAnalyzerImpl.kt,
TrackAnalysisRepositoryImpl.kt, OnsetDetection.kt, NowPlayingScreen.kt,
PlayerViewModel.kt, Waveform.kt, MarkerRepositoryImpl.kt,
Migrations.kt). Nicht belegbare Punkte sind am Ende explizit markiert.
Analog zu `AUDIO_ENGINE_AUSBAU_PLAN.md`; die Architekturregeln aus
Bauplan Abschnitt 3.2 bleiben uneingeschraenkt gueltig.

## Ziel

Den Player um einen vollstaendigen Now-Playing-Screen mit Waveform-
Scrubbing sowie um manuelles und automatisch vorgeschlagenes
Drop-Marker-Setzen erweitern — als lokale Grundlage fuer die
markerbasierten DropSync-Timer, ohne neue Abhaengigkeit und ohne
zweiten Decoder-Pfad.

## Phasen (alle umgesetzt, README-Statustabelle)

| Phase | Inhalt | Kernentscheidung | Beleg |
|---|---|---|---|
| 1 | Now-Playing-Screen: Cover, Positions-Ticker, Transportsteuerung, Route `now_playing` vom Mini-Player | Cover via `MediaMetadataRetriever.embeddedPicture` (minSdk 26 < API 29 fuer `loadThumbnail()`); Ticker 200 ms `snapshotNow()` nur bei sichtbarem Screen | README, NowPlayingScreen.kt |
| 2 | Geteilte Analyse-Grundlage: `TrackAnalyzer`/`TrackAnalysisRepository` (`:domain:audio`), `track_analysis`-Cache (DB v3, `MIGRATION_2_3`), `TrackAnalyzerImpl` (`:data:audio`) | Ein einziger Analysedurchgang liefert Waveform-Peaks und Onset-Kandidaten (Streaming-Akkumulatoren, "nicht zwei getrennte Decoder-Pfade"); Decode via MediaExtractor/MediaCodec statt Transformer oder Zweit-Player; aufschiebbarer `OneTimeWorkRequest`, dedupliziert ueber `track_analysis_<songId>`; `analyzer_version` als Cache-Invalidierung | ADR-0011, TrackAnalyzerImpl.kt, Migrations.kt |
| 3 | Waveform-Anzeige mit Scrubbing: `Waveform`/`WaveformPlaceholder` in `:core:designsystem` (Compose-Canvas, Lime fuer den gespielten Anteil); Tap = Sprung, Drag = Live-Vorschau | Fehlerfall wird persistiert (`bucket_count = 0`) und faellt dauerhaft auf die Zeitleiste zurueck — die Wiedergabe ist nie von der Analyse abhaengig | README, ADR-0011 (Sekundaerpfad-Prinzip) |
| 4 | Manuelles Marker-Setzen: `createManualMarker`/`deleteMarker` in `MarkerRepository`, Bestaetigungsdialog (Standard-Label "Drop"), Marker-Ticks auf der Waveform, Long-Press setzt/loescht | MANUAL-Marker + Song-Link in einer Transaktion; Fingerprint aus Songfeldern wiederverwendet (Bauplan-Mechanik der Marker-Zuordnung) | README, MarkerRepositoryImpl.kt |
| 5 | On-Device-Onset-Erkennung: `OnsetDetection` (`:domain:audio`), Trigger im Song-Kontextmenue (Worker `onset_detection_<songId>`), Review-Liste in den Einstellungen | Novelty = positive RMS-Differenz; Peak-Picking ueber gleitendem Schwellwert (Mittel + k*Stdabw + absoluter Mindestsprung), Mindestabstand 5 s, Top 5; Kandidaten immer `AUTO_DETECTED`/`isEnabled=false` — nie Automatik, Nutzer bestaetigt (`confirmMarker`) oder verwirft; erneuter Lauf ersetzt nur unbestaetigte Kandidaten | README, OnsetDetection.kt |

## Verbindungen zu anderen Plaenen

- Der `track_analysis`-Cache aus Phase 2 ist die vorgesehene
  Andockstelle fuer die BPM-/Tonart-Analyse des Mix-Uebergaenge-Plans
  (`MIX_TRANSITIONS_AUSBAU_PLAN.md`, Phase 1) — additive Spalten im
  selben Decode-Durchgang statt eines zweiten Analyzers.
- Die bestaetigten Drop-Marker sind die Grundlage der Drop-Landung im
  Musik-Workout-Plan (`MUSIK_WORKOUT_KOPPLUNG_AUSBAU_PLAN.md`,
  ADR-0012).
- Die Waveform-Komponente wurde im FlowRep-Redesign (Design-Phase 6)
  als primaere Seekbar des Now-Playing-Screens beibehalten; der
  Slider-Fallback bleibt fuer Barrierefreiheit.

## Nicht aus den Quellen rekonstruierbar

Falls im Original vorhanden, bitte ergaenzen:

- urspruengliche Abwaegung der Bucket-Anzahl/Fenstergroessen der
  Akkumulatoren,
- etwaige explizite Abnahmekriterien pro Phase ueber die
  README-Statustexte hinaus,
- ob weitere Phasen (z. B. Beat-Grid statt einzelner Onsets) geplant
  waren.
