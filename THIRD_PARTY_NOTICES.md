# Third-Party Notices

Verbindliches Lizenzinventar gemaess Bauplan Schritt 1.5. Vor jeder neuen
Fremdbibliothek wird ihre Lizenz hier erfasst; eine Aenderung dieser Datei
ohne Review blockiert CI (Schritt 13.7). Die finale Lizenz der eigenen App
ist noch nicht festgelegt; bis dahin wird keine Lizenzbehauptung im Code
gemacht.

Status-Werte: `FREIGEGEBEN` (Lizenz geprueft, kompatibel), `OFFEN` (in Pruefung).

| Name | Version | Lizenz | Zweck | Quelle | Freigabestatus |
|---|---|---|---|---|---|
| Kotlin (stdlib, Gradle-Plugins) | 2.4.10 | Apache-2.0 | Sprache, Compiler, Compose-/Serialization-Plugin | https://github.com/JetBrains/kotlin | FREIGEGEBEN |
| Android Gradle Plugin | 9.3.1 | Apache-2.0 | Build-Toolchain | https://developer.android.com/build | FREIGEGEBEN |
| KSP | 2.3.10 | Apache-2.0 | Annotation-Processing (Room, Hilt) | https://github.com/google/ksp | FREIGEGEBEN |
| androidx.core:core-ktx | 1.19.0 | Apache-2.0 | Android-Basis-APIs | https://developer.android.com/jetpack/androidx | FREIGEGEBEN |
| androidx.lifecycle (runtime, viewmodel-compose, runtime-compose) | 2.11.0 | Apache-2.0 | Lifecycle/ViewModel | https://developer.android.com/jetpack/androidx | FREIGEGEBEN |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | Compose-Host-Activity | https://developer.android.com/jetpack/androidx | FREIGEGEBEN |
| androidx.compose (BOM) | 2026.06.01 | Apache-2.0 | Deklarative UI, Material 3, Window Size Classes | https://developer.android.com/jetpack/compose | FREIGEGEBEN |
| androidx.navigation:navigation-compose | 2.9.8 | Apache-2.0 | Navigation | https://developer.android.com/jetpack/androidx | FREIGEGEBEN |
| androidx.room (runtime, ktx, compiler, testing) | 2.8.4 | Apache-2.0 | Lokale Datenbank | https://developer.android.com/jetpack/androidx/releases/room | FREIGEGEBEN |
| androidx.media3 (exoplayer, session, common, test-utils) | 1.10.1 | Apache-2.0 | Lokale Musikwiedergabe | https://developer.android.com/media/media3 | FREIGEGEBEN |
| androidx.work:work-runtime-ktx | 2.11.2 | Apache-2.0 | Aufschiebbare Hintergrundaufgaben (nie Timer) | https://developer.android.com/jetpack/androidx/releases/work | FREIGEGEBEN |
| androidx.datastore:datastore-preferences | 1.2.1 | Apache-2.0 | Kleine Zustandswerte (Scan-Generation u. a.) | https://developer.android.com/jetpack/androidx/releases/datastore | FREIGEGEBEN |
| androidx.hilt:hilt-navigation-compose | 1.4.0 | Apache-2.0 | Hilt-Integration fuer Navigation | https://developer.android.com/jetpack/androidx/releases/hilt | FREIGEGEBEN |
| Dagger Hilt (hilt-android, compiler, testing) | 2.60.1 | Apache-2.0 | Dependency Injection | https://github.com/google/dagger | FREIGEGEBEN |
| kotlinx-coroutines (core, android, guava, test) | 1.11.0 | Apache-2.0 | Nebenlaeufigkeit | https://github.com/Kotlin/kotlinx.coroutines | FREIGEGEBEN |
| kotlinx-serialization-json | 1.11.0 | Apache-2.0 | Markerimport-JSON, Seed-Daten | https://github.com/Kotlin/kotlinx.serialization | FREIGEGEBEN |
| JUnit 4 | 4.13.2 | EPL-1.0 | Unit-Tests (nur Testscope, keine Distribution in der App) | https://junit.org/junit4 | FREIGEGEBEN |
| Turbine | 1.2.1 | Apache-2.0 | Flow-Tests (nur Testscope) | https://github.com/cashapp/turbine | FREIGEGEBEN |
| Robolectric | 4.16.1 | MIT | JVM-Datenbank-/Android-Tests (nur Testscope) | https://github.com/robolectric/robolectric | FREIGEGEBEN |
| androidx.test (junit-ext, runner, espresso-core) | 1.3.0 / 1.7.0 / 3.7.0 | Apache-2.0 | Instrumentierte Tests (nur Testscope) | https://developer.android.com/testing | FREIGEGEBEN |
| Spotless (Gradle-Plugin) | 8.8.0 | Apache-2.0 | Formatierung/statische Analyse (nur Build, keine Distribution) | https://github.com/diffplug/spotless | FREIGEGEBEN |
| ktlint | 1.8.0 | MIT | Kotlin-Linting via Spotless (nur Build) | https://github.com/pinterest/ktlint | FREIGEGEBEN |
| FFmpeg (libavcodec, libavformat, libavutil) | 6.1+ | LGPL-2.1-or-later | Audiodecoder-Extension (ALAC/AIFF/WMA/APE/TAK/TTA/DSD), dynamisch gelinkt, aus androidx/media gebaut (ADR-0006) | https://ffmpeg.org | OFFEN |
| Raleway (Schriftfamilie) | static (wght) | OFL-1.1 | Marken-Typografie, gebuendelte TTF in `:core:designsystem` (`res/font`) | https://github.com/impallari/Raleway | FREIGEGEBEN |

## Raleway (SIL OFL 1.1)

Die Marken-Schriftfamilie Raleway wird unter der SIL Open Font License 1.1
als statische TTF (Gewichte 400/500/600/700/800) in `:core:designsystem`
(`src/main/res/font/`) gebuendelt und offline ausgeliefert - kein
Google-Fonts-Provider, kein Netzzugriff. Der vollstaendige Lizenztext liegt
unter `UI/Raleway/OFL.txt`. OFL erlaubt das Einbetten in Anwendungen; die
Fonts werden nicht separat verkauft und der Lizenztext begleitet die Dateien.

## FFmpeg (LGPL 2.1+)

Die optionale Decoder-Extension `:libs:media3-ffmpeg` linkt FFmpeg
**dynamisch** als Shared Libraries (`libavcodec`, `libavformat`,
`libavutil`). Damit bleibt die App LGPL-2.1-konform: Der FFmpeg-Quellcode
sowie die verwendeten Build-Flags werden gemaess `docs/ffmpeg-build.md`
bereitgestellt, und die `.so`-Dateien koennen durch eine eigene
kompatible FFmpeg-Version ersetzt werden. Es werden **keine** GPL-Bauteile
(z. B. `--enable-gpl`, `libx264`) einkompiliert. Bis das Artefakt gebaut
und eingebunden ist, faellt die App auf die Plattformdecoder zurueck
(Gradle-Flag `dropsync.enableFfmpeg`).

Hinweis: Es wird kein Quellcode aus Symphony, Booming Music, Tracker, liftapp
oder anderen Referenzprojekten verwendet (Bauplan Abschnitt 2.2).
