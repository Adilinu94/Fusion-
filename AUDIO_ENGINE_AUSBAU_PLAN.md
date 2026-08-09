# DropSync Audio-Engine-Ausbau — Plan (rekonstruiert)

Stand: 28.07.2026. Der urspruengliche Plan wurde nie committed; diese
Fassung ist eine Rekonstruktion ausschliesslich aus belegten Quellen:
README-Statustabelle, ADR-0005 bis ADR-0009 und stichprobenartig
gelesenem Code. Nicht belegbare Punkte sind am Ende explizit als solche
markiert. Verbindlichkeit: Bauplan Abschnitt 3.2 (Architekturregeln)
bleibt uneingeschraenkt gueltig; die Aufhebung von Bauplan 1.2 fuer
Equalizer/Crossfade ist in ADR-0010 dokumentiert.

## Ziel

DropSync von einem funktionalen lokalen Player zu einer audiophilen
Wiedergabe-Engine erweitern, ohne die Architekturregeln aus Bauplan 3.2
zu verletzen (ein Player, eine MediaSession, Feature-Module kennen kein
ExoPlayer/Room direkt).

## Phasen

| Phase (Schritt) | Inhalt | Kernentscheidung | Beleg |
|---|---|---|---|
| 1 (15) | Float-Output, 64-Bit-DSP-Fundament, Preamp/Limiter, Audioinformationen | Media3s `DefaultAudioSink` mit eigener `RenderersFactory` statt eigenem Audiostack (Oboe/AAudio) oder Systemequalizer — verifiziert am Media3-Quellcode, dass Prozessoren vor der Float-/Int16-Konvertierung laufen | ADR-0005 |
| 2 (16) | 32-Band-EQ, Klangregler, Stereo Expansion, Reverb, Dither, Resampler, DVC, Presets | `MasterDspProcessor`-Kette, jede Stufe einzeln bypassbar | ADR-0005, README |
| 3 (17) | FFmpeg-Formate (ALAC/AIFF/WMA/APE/TAK/TTA/DSD), CUE/M3U-Parser, SAF-Ordnerscan | Selbstgebaute `media3-decoder-ffmpeg`-Extension statt Systemdecoder — dynamisch gelinkt, LGPL-konform, optional per Gradle-Flag | ADR-0006 |
| 4 (18) | Crossfade (Dual-Player), Gapless-Absicherung, Auto-Resume, MusicFX | Zwei ExoPlayer-Instanzen mit Equal-Power-Rampe statt Prozessor-Mixer (ein Prozessor sieht nur den aktuellen Titel) — unter Verweis auf ein offenes androidx/media-Issue zur fehlenden nativen Crossfade-Unterstuetzung | ADR-0007 |
| 5 (19) | Pro-Ausgang-Audioprofile, Bit-Perfect (USB, Android 14+) | Profile in Room statt globalem Settings-Set; Bit-Perfect ueber `AudioMixerAttributes` (API 34+) statt eigenem USB-Host-Treiber | ADR-0008, ADR-0009 |
| 6 (20) | Bibliothek: Kuenstler/Alben/Genres/Ordner, Stats, Favoriten, Playlisten, Volltextsuche, Queue-Editor, Android-Auto-Browse-Baum | Room mit FTS4 fuer Suche, `LibraryBrowseRepository` als zentraler Zugriffspunkt | README (keine eigene ADR — unter der Architekturregel aus Bauplan 3.2 subsumiert) |
| 7 (21) | Barrierefreiheit, Performance-Waechter, Akku-Hinweise, DE/EN-Strings | `DspPerformanceTest` (32 Baender/48 kHz muss >2x Echtzeit laufen, keine NaN/Inf) als automatisierter Stabilitaetstest | README |

## FFmpeg-Artefakt (Phase 3): strukturelle Einordnung

Die Code-Seite ist vollstaendig und verifiziert: `gradle.properties`
liefert mit `dropsync.enableFfmpeg=false` einen sicheren Default;
`settings.gradle.kts` bindet `:libs:media3-ffmpeg` nur ein, wenn das
Flag gesetzt ist UND das Artefakt existiert (kein kaputter Build
moeglich); `DspRenderersFactory` nutzt `EXTENSION_RENDERER_MODE_PREFER`
gemaess Media3-Doku; `THIRD_PARTY_NOTICES.md` erfasst FFmpeg als
LGPL-2.1-or-later mit dokumentiertem dynamischem Linken und Verzicht
auf `--enable-gpl`/`--enable-nonfree`.

Das kompilierte native Artefakt selbst erfordert einen Entwickler-Host
mit NDK r27 (Cross-Compile fuer drei ABIs, siehe
`docs/ffmpeg-build.md`). Das ist kein Projektrueckstand, sondern eine
strukturelle Voraussetzung nativer Android-Builds; in einer
CI-/KI-Sandbox ist dieser Build grundsaetzlich nicht moeglich.

## Nicht aus den Quellen rekonstruierbar

Falls im Original vorhanden, bitte ergaenzen:

- urspruengliche Priorisierungsgruende fuer die Reihenfolge der sieben
  Phasen,
- etwaige explizite Abnahmekriterien pro Phase ueber die
  README-Statustexte hinaus,
- geplante naechste Phasen ueber Phase 7 hinaus.
