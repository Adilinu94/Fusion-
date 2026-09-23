# ADR-Index (Architecture Decision Records)

Automatisch aus den ADR-Dateien gepflegt (D7, 2026-09-22): Titel, Datum und Status
stammen aus dem jeweiligen Kopf. Neue ADRs hier mit einer Zeile ergaenzen.

| Datei | Entscheidung | Datum | Status |
|---|---|---|---|
| [0001-baselineprofile-modul-in-schritt-13.md](0001-baselineprofile-modul-in-schritt-13.md) | ADR-0001: Modul `:baselineprofile` wird erst in Schritt 13 angelegt | 2026-07-27 | Akzeptiert |
| [0002-kein-boot-completed-receiver.md](0002-kein-boot-completed-receiver.md) | ADR-0002: Kein `BOOT_COMPLETED`-Receiver in Version 1 | 2026-07-27 | Akzeptiert |
| [0003-domain-library-modul.md](0003-domain-library-modul.md) | ADR-0003: Zusaetzliches Modul `:domain:library` fuer Bibliotheksvertraege | 2026-07-27 | Akzeptiert |
| [0004-domain-playback-modul.md](0004-domain-playback-modul.md) | ADR-0004: Zusaetzliches Modul `:domain:playback` fuer Wiedergabevertraege | 2026-07-27 | Akzeptiert |
| [0005-eigene-audio-pipeline.md](0005-eigene-audio-pipeline.md) | ADR-0005: Eigene Audio-Pipeline mit Float-Output und DSP-Kette | 2026-07-27 | Akzeptiert |
| [0006-ffmpeg-decoder-extension.md](0006-ffmpeg-decoder-extension.md) | ADR-0006: Selbstgebaute FFmpeg-Decoder-Extension | 2026-07-27 | Akzeptiert |
| [0007-crossfade-dual-player.md](0007-crossfade-dual-player.md) | ADR-0007: Crossfade ueber zwei ExoPlayer-Instanzen | 2026-07-27 | Akzeptiert |
| [0008-pro-ausgang-audioprofile.md](0008-pro-ausgang-audioprofile.md) | ADR-0008: Audioprofile pro Ausgabegeraet | 2026-07-27 | Akzeptiert |
| [0009-bit-perfect-modus.md](0009-bit-perfect-modus.md) | ADR-0009: Bit-Perfect-Modus (USB, Android 14+) | 2026-07-27 | Akzeptiert |
| [0010-audio-engine-ausbau-hebt-bauplan-1-2-auf.md](0010-audio-engine-ausbau-hebt-bauplan-1-2-auf.md) | ADR-0010: Audio-Engine-Ausbau hebt Bauplan-Abschnitt 1.2 (Equalizer/Crossfade) auf | 2026-07-28 | Akzeptiert |
| [0011-track-analyse-decoder-mediacodec.md](0011-track-analyse-decoder-mediacodec.md) | ADR-0011: Track-Analyse dekodiert direkt ueber MediaExtractor/MediaCodec | 2026-07-27 | Akzeptiert |
| [0012-drop-landung-work-vorlauf-trifft-pausenende.md](0012-drop-landung-work-vorlauf-trifft-pausenende.md) | ADR-0012: Drop-Landung — Work-Titel-Vorlauf trifft das Pausenende | 2026-07-27 | Akzeptiert |
| [0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md](0013-flowrep-design-plan-tab-feature-grundsaetze-aufgehoben.md) | ADR-0013: Fusionsdesign hebt Tab- und Feature-Grundsätze aus FLOWREP_DESIGN_PLAN.md auf | 2026-08-09 | Akzeptiert |
| [0014-neue-pipeline-zaehlt-live-ohne-11b-hardwarefreigabe.md](0014-neue-pipeline-zaehlt-live-ohne-11b-hardwarefreigabe.md) | ADR-0014: Neue Zaehl-Pipeline zaehlt live, ohne Abschnitt-11b-Hardwarefreigabe | 2026-08-12 | Akzeptiert |
| [0015-track-analyse-in-zwei-stufen-mit-getrennter-cache-versionierung.md](0015-track-analyse-in-zwei-stufen-mit-getrennter-cache-versionierung.md) | ADR-0015: Track-Analyse in zwei Stufen mit getrennter Cache-Versionierung | 2026-09-01 | akzeptiert |
| [0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md](0016-flowtimer-integration-hebt-fusionsdesign-punkte-auf.md) | ADR-0016: Flowtimer-Integration hebt Punkte des Fusionsdesigns auf | 2026-08-22 | Akzeptiert |
| [0017-accel-voting-aktiviert-sich-per-kalibrierter-schwelle.md](0017-accel-voting-aktiviert-sich-per-kalibrierter-schwelle.md) | ADR-0017: Accel-Voting aktiviert sich per kalibrierter Schwelle statt per Feature-Flag | 2026-08-29 | Akzeptiert |
| [0018-now-playing-folgt-dem-designsystem-statt-der-poweramp-referenz.md](0018-now-playing-folgt-dem-designsystem-statt-der-poweramp-referenz.md) | ADR-0018: Now-Playing folgt dem Designsystem statt der Poweramp-Referenz | 2026-08-30 | Akzeptiert |
| [0019-konfidenz-gate-fuer-bpm-und-tonart.md](0019-konfidenz-gate-fuer-bpm-und-tonart.md) | ADR-0019: Unsichere BPM/Key-Schaetzungen werden an der Leseseite verworfen | 2026-08-31 | akzeptiert |
| [0020-system-media-session-statt-media3-ui-compose.md](0020-system-media-session-statt-media3-ui-compose.md) | ADR-0020: System-Media ueber MediaSession und Notification-Fortschritt, kein media3-ui-compose | 2026-09-13 | Akzeptiert |
| [0021-kein-automatischer-datentransfer-in-v1.md](0021-kein-automatischer-datentransfer-in-v1.md) | ADR-0021: Kein automatischer Transfer, kein Cloud-Backup, kein verschluesselter Export in V1 | 2026-09-13 | Akzeptiert |
| [0022-crossfade-in-der-drop-landung.md](0022-crossfade-in-der-drop-landung.md) | ADR-0022: Crossfade in der Drop-Landung (Dual-Player, stufenweise) | 2026-09-19 | Akzeptiert (Nutzerentscheidung; Umsetzung stufenweise, Spike vor Bau) |
| [0023-sensordaten-nur-in-debug-builds.md](0023-sensordaten-nur-in-debug-builds.md) | ADR-0023: Sensordaten-Aufzeichnung nur in debuggable Builds | 2026-09-19 | Akzeptiert (Nutzerentscheidung, Entscheidung 3) |
| [0024-einseitige-qualitaetsbewertung.md](0024-einseitige-qualitaetsbewertung.md) | ADR-0024: Einseitige Qualitaetsbewertung (B1/RC-18) | 2026-09-21 | Akzeptiert (Umsetzung B1, Stufe 1; Nutzerentscheidung 5.13) |
| [0025-beat-raster-offset-fuer-marker-snap.md](0025-beat-raster-offset-fuer-marker-snap.md) | ADR-0025: Beat-Raster-Offset fuer das Marker-Snap (B4/RC-22) | 2026-09-21 | Akzeptiert (Umsetzung B4, Stufe 1; Nutzerentscheidungen 5.11/5.12) |
| [0026-health-write-back-aufgeschoben.md](0026-health-write-back-aufgeschoben.md) | ADR-0026: Health bleibt in V1 lesend, Write-back aufgeschoben | 2026-09-23 | Akzeptiert (Nutzerentscheidung im Analyse-Paket 6) |
| [0027-build-logic-vor-toolchain-upgrade.md](0027-build-logic-vor-toolchain-upgrade.md) | ADR-0027: build-logic als eigener Umbau vor dem naechsten Toolchain-Upgrade | 2026-09-23 | Akzeptiert (Nutzerentscheidung im Analyse-Paket 6) |
