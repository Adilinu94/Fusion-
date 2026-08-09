# ADR-0011: Track-Analyse dekodiert direkt ueber MediaExtractor/MediaCodec

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Die geteilte Analyse-Grundlage (Marker/Waveform-Plan Phase 2) braucht den
ganzen Track einmal als PCM (Mono-Downmix) fuer Waveform-Peaks und
Kurzzeit-Energie. Der Plan laesst den Decoder-Weg bewusst offen und nennt
zwei Optionen: Media3 `Transformer` mit eigenem `AudioProcessor` oder
eine zweite `ExoPlayer`-Instanz mit verwerfender `AudioSink`.

## Optionen

1. Media3 `Transformer`: neue Abhaengigkeit `media3-transformer` im
   Versionskatalog; die FFmpeg-Renderer-Abdeckung der Wiedergabe gilt
   dort nicht automatisch (Transformer nutzt einen eigenen AssetLoader,
   nicht die `DspRenderersFactory`).
2. Zweite `ExoPlayer`-Instanz mit verwerfender `AudioSink`: dieselbe
   Formatabdeckung wie die Wiedergabe, aber eine vollstaendige
   Player-Instanz nur zum Dekodieren; Geschwindigkeit haengt an der
   Sink-Uhr und erfordert zusaetzliche Eingriffe fuer
   schneller-als-Echtzeit.
3. Plattform-APIs `MediaExtractor` + `MediaCodec` direkt: keine neue
   Abhaengigkeit, synchroner Decode-Loop schneller als Echtzeit,
   deterministisch testbar; Formatabdeckung = Plattformdecoder.

## Entscheidung

Option 3. Die Analyse ist ein Sekundaerpfad: schlaegt sie fehl (z. B.
FFmpeg-exklusive Formate wie APE/TAK/TTA ohne Plattformdecoder), faellt
die Waveform-Anzeige laut Plan Phase 3 auf eine einfache
Fortschrittsleiste zurueck — die Wiedergabe selbst ist nie betroffen.
Dafuer eine `Transformer`-Abhaengigkeit einzufuehren oder eine zweite
Player-Instanz zu betreiben steht in keinem Verhaeltnis; das Repo
vermeidet neue Abhaengigkeiten konsequent.

## Folgen

- `TrackAnalyzerImpl` (`:data:audio`) dekodiert per
  `MediaExtractor`/`MediaCodec`; die Mathematik (Buckets, Energie) bleibt
  reine JVM-Logik in `:domain:audio`.
- Sollte die FFmpeg-Extension (ADR-0006) spaeter fuer die Analyse noetig
  werden, ist der Wechsel auf Option 2 hinter demselben
  `TrackAnalyzer`-Vertrag moeglich, ohne Aufrufer anzufassen.
- `analyzer_version` im `track_analysis`-Cache erlaubt eine spaetere
  Neuberechnung bei Algorithmus- oder Decoder-Wechsel.
