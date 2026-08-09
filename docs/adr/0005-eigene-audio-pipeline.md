# ADR-0005: Eigene Audio-Pipeline mit Float-Output und DSP-Kette

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Der Audio-Engine-Ausbau (Plan "DropSync Audio-Engine-Ausbau", Phasen 1-7)
verlangt Hi-Res-Wiedergabe, 64-Bit-Verarbeitung, EQ, Dithering und weitere
DSP-Stufen. Der Standardaufbau aus Schritt 5 (ExoPlayer.Builder ohne eigene
RenderersFactory) bietet weder Float-Output noch eine eigene Prozessorkette.

## Optionen

1. `android.media.audiofx` (Equalizer, BassBoost) am Audio-Session-Objekt —
   maximal 5-6 Baender, 16-Bit-Systempfad, geraeteabhaengige Qualitaet.
2. Eigener Audiostack (Oboe/AAudio + eigene Decoder) — maximale Kontrolle,
   aber Verlust von MediaSession-Integration, Gapless und Formatbreite.
3. Media3 behalten und `DefaultAudioSink` ueber eine eigene
   `RenderersFactory` konfigurieren: `setEnableFloatOutput(true)` plus
   eigene `AudioProcessor`-Kette via `setAudioProcessors(...)`.

## Entscheidung

Option 3. Verifiziert am Media3-Quellcode (DefaultAudioSink.configure):
die via Builder gesetzten Prozessoren laufen VOR der Float-/Int16-
Konvertierung und damit auch im Hi-Res-Float-Pfad; nur die
`AudioProcessorChain` (Speed/Pitch) entfaellt im Float-Pfad. Jede
DSP-Stufe konvertiert intern nach 64-Bit-Double (`:domain:audio`),
gibt 32-Bit-Float aus und ist einzeln bypassbar.

## Folgen

- Neue Module `:domain:audio` (reine JVM-DSP-Mathematik, testbar) und
  `:data:audio` (AudioProcessor-Implementierungen, Settings, Factory).
- `PlaybackService` baut den Player ueber die `DspRenderersFactory` aus
  `:data:audio`; die Regel "genau ein Player im Service" bleibt.
- Offload/Passthrough (Bit-Perfect, ADR-0009) umgeht die Kette per Design;
  das UI kommuniziert diese Exklusivitaet.
