# ADR-0006: Selbstgebaute FFmpeg-Decoder-Extension

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Die Zielformate ALAC, AIFF, WMA, APE, TAK, TTA und DSD (DSF/DFF) werden
von den Android-Plattformdecodern nicht oder nicht ueberall unterstuetzt.
`media3-decoder-ffmpeg` wird von Google nicht als Maven-Artefakt
ausgeliefert und muss aus den androidx/media-Quellen mit einem eigenen
FFmpeg-Build kompiliert werden.

## Optionen

1. Nur Plattformdecoder — die Formatliste bleibt unvollstaendig.
2. Eigene JNI-Decoder je Format — hoher Pflegeaufwand, Lizenzflickwerk.
3. FFmpeg-Extension aus androidx/media bauen (NDK, nur Audio-Decoder:
   alac, aiff/pcm, wmav1/2/pro, ape, tak, tta, dsd_lsbf/msbf, wavpack).

## Entscheidung

Option 3. Der Build wird in `docs/ffmpeg-build.md` dokumentiert und als
lokales Modul `:libs:media3-ffmpeg` eingebunden. Die RenderersFactory nutzt
`EXTENSION_RENDERER_MODE_PREFER` nur fuer Formate ohne Plattformdecoder.
DSD wird zu PCM dekodiert; DoP bleibt Folgeausbau (ADR-0009).

## Folgen

- FFmpeg ist LGPL 2.1+: THIRD_PARTY_NOTICES erhaelt Lizenztext und
  Quellcode-Verweis; es wird dynamisch gelinkt.
- Der CI-/Entwickler-Build braucht das NDK; ohne das lokale Artefakt
  faellt die App kompilierbar auf Plattformdecoder zurueck (Gradle-Flag
  `dropsync.enableFfmpeg`).
