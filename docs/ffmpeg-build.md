# FFmpeg-Decoder-Extension bauen (`:libs:media3-ffmpeg`)

Anleitung zum Bau der optionalen Media3-FFmpeg-Audiodecoder-Extension
(Plan Phase 3, ADR-0006). Google liefert `media3-decoder-ffmpeg` **nicht**
als Maven-Artefakt; die Extension wird aus den `androidx/media`-Quellen mit
einem eigenen, rein auf Audio reduzierten FFmpeg-Build kompiliert und als
lokales Gradle-Modul eingebunden.

> Der Bau ist der Risikotreiber der Phase und benoetigt NDK + Host-Toolchain.
> Ohne das Artefakt bleibt die App voll funktionsfaehig und faellt auf die
> Plattformdecoder zurueck (Gradle-Flag `dropsync.enableFfmpeg=false`, Default).

> **Schnellstart auf einem Linux-PC:** die vereinfachte
> Schritt-fuer-Schritt-Anleitung steht in `docs/ffmpeg-build-linux.md`.

## Ziel-Formate

Nur Audio-Decoder, keine Encoder, kein Muxing, kein GPL:

```
alac, aiff (pcm_s16be/pcm_s24be), wmav1, wmav2, wmapro,
ape, tak, tta, dsd_lsbf, dsd_msbf, dsd_lsbf_planar, dsd_msbf_planar, wavpack
```

DSD (DSF/DFF) wird zu PCM dekodiert; natives DoP ist Folgeausbau (ADR-0009).

## Voraussetzungen

- Android NDK **r28+** (empfohlen: r28 aligniert nativ auf 16-KB-Pages;
  r27 nur mit explizitem Linker-Flag `-Wl,-z,max-page-size=16384`)
- FFmpeg 6.1+ Quellen (`git clone https://git.ffmpeg.org/ffmpeg.git`)
- `androidx/media` Quellen passend zur genutzten Media3-Version (1.10.1)
- Host: Linux/macOS mit `make`, `clang`, `bash` (Windows via WSL)

> **16-KB-Page-Size-Pflicht:** Google Play verlangt fuer Apps mit
> targetSdk 35+ 16-KB-kompatible native Libraries
> (https://developer.android.com/guide/practices/page-sizes). FFmpeg wird
> von media3 1.10.1 **statisch** in `libffmpegJNI.so` gelinkt; das
> Alignment bestimmt daher der NDK-Build der Extension (Schritt 3), nicht
> der FFmpeg-Configure. Mit NDK r28+ ist nichts weiter zu tun. Verifikation
> nach dem Bau (jede LOAD-Zeile muss `align 2**14` bzw. `0x4000` zeigen):
>
> ```bash
> llvm-readelf -l libffmpegJNI.so | grep LOAD
> ```
>
> Fertige Wrapper wie `ffmpeg-kit` (auch der 16-KB-Fork
> JamaisMagic/ffmpeg-kit-16KB) sind **kein** Ersatz: sie kapseln das
> FFmpeg-CLI fuer Transcoding-Kommandos und koennen sich nicht als
> Decoder in die ExoPlayer-Renderer-Pipeline einklinken (LGPL v3,
> Projekt offiziell eingestellt).

## Schritte

**Schnellweg:** Das Skript `scripts/build-ffmpeg.sh` automatisiert die
Schritte 1-4 (Quellen holen, LGPL-konform bauen, Modul anlegen). Danach
nur noch `dropsync.enableFfmpeg=true` setzen:

```bash
ANDROID_NDK_HOME=/pfad/zum/ndk scripts/build-ffmpeg.sh
```

Die manuellen Einzelschritte (falls das Skript angepasst werden soll):

1. **Media3-Quellen holen** und in das Extension-Verzeichnis wechseln:
   ```bash
   git clone https://github.com/androidx/media.git
   cd media/libraries/decoder_ffmpeg/src/main
   ```
2. **FFmpeg-Symlink** setzen und Build-Skript aufrufen (baut nur die oben
   gelisteten Decoder, dynamisch, ohne GPL):
   ```bash
   FFMPEG_MODULE_PATH="$(pwd)/jni"
   ln -s "<pfad-zu-ffmpeg>" "$FFMPEG_MODULE_PATH/ffmpeg"
   ENABLED_DECODERS=(alac aiff wmav1 wmav2 wmapro ape tak tta \
     dsd_lsbf dsd_msbf dsd_lsbf_planar dsd_msbf_planar wavpack)
   ./build_ffmpeg.sh "$FFMPEG_MODULE_PATH" "<ndk-pfad>" "android-24" \
     "${ENABLED_DECODERS[@]}"
   ```
   Wichtig: **`--enable-gpl` und `--enable-nonfree` NICHT** setzen
   (LGPL-Konformitaet, siehe THIRD_PARTY_NOTICES).
3. **AAR bauen**:
   ```bash
   ./gradlew :lib-decoder-ffmpeg:assembleRelease
   ```
4. **Artefakt einbinden**: Die entstandene `.aar` (bzw. die `jni/`-`.so`-
   Dateien) nach `libs/media3-ffmpeg/` im DropSync-Repo kopieren (das
   Skript erledigt dies und legt auch `libs/media3-ffmpeg/build.gradle.kts`
   an). `settings.gradle.kts` registriert `:libs:media3-ffmpeg`
   **automatisch**, sobald das Modulverzeichnis vorliegt und das Flag
   gesetzt ist.
5. **Aktivieren**: In `gradle.properties` `dropsync.enableFfmpeg=true`
   setzen. Dann nimmt `:data:audio` die Extension automatisch als
   `implementation(project(":libs:media3-ffmpeg"))` auf; die
   `DspRenderersFactory` bevorzugt sie bereits via
   `EXTENSION_RENDERER_MODE_PREFER`.

## Lizenz

FFmpeg ist LGPL-2.1-or-later. **Achtung:** media3 1.10.1 baut FFmpeg mit
`--enable-static --disable-shared` und linkt es **statisch** in
`libffmpegJNI.so` (zudem `--disable-avformat` — nur libavcodec/libavutil/
libswresample). LGPL-Konformitaet bei statischer Linkung verlangt, dass
Nutzer die FFmpeg-Teile ersetzen koennen: Quellcode, exakte Version und
Build-Flags werden bereitgestellt, und dieses Skript/diese Anleitung
erlaubt den Rebuild von `libffmpegJNI.so` mit eigener FFmpeg-Version.
`THIRD_PARTY_NOTICES.md` ist beim Einbinden des Artefakts entsprechend zu
praezisieren. Details in `THIRD_PARTY_NOTICES.md`.
