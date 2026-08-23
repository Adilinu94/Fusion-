# Technische Best Practices für Android-Musik-/Audio-Apps (Stand: August 2026)

> **Scope:** Technische Recherche (2025–2026) aus Primärquellen (developer.android.com, androidx-Releases, Android-Developers-Blog, offizielle GitHub-Repos) für **DropSync** – Kotlin/Compose/Multi-Module-App für lokale Musikwiedergabe mit eigener DSP-Pipeline (AudioTrack + Teile von FFmpeg; EQ, Tempo/Pitch, Crossfade/Mix-Übergänge, Marker), Waveform-Rendering und Workout/Herzfrequenz-Kopplung.
> **Nicht Teil dieses Dokuments:** UI/UX-Design → siehe `docs/research/RESEARCH_MUSIC_UIUX_2026.md`.
> **Kennzeichnung:** Alles Unbestätigte/Unsichere ist explizit mit ⚠️ markiert.

---

## 1. androidx.media3 – Stand August 2026

### 1.1 Versionen & Release-Kadenz

| Version | Datum | Status |
|---|---|---|
| 1.8.0 | 30. Juli 2025 | stable |
| 1.9.0 | 17. Dezember 2025 | stable |
| 1.10.0 / 1.10.1 | März 2026 / 12. Mai 2026 | stable |
| **1.11.0** | **5. August 2026** | **aktuellste stable** |

Quellen: [Media3 Jetpack-Releases](https://developer.android.com/jetpack/androidx/releases/media3), [GitHub-Releases androidx/media](https://github.com/androidx/media/releases), [Blog „Media3 1.10 is out"](https://android-developers.googleblog.com/2026/03/media3-110-is-out.html), [Blog „Media3 1.11 – What's new"](https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html), [Blog „Media3 1.9.0 – What's new"](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html).

Für DropSync heißt das: Media3 ist als **Session-/Integrations-Schicht** aktuell und pflegeaktiv, auch wenn der eigene Player nicht ExoPlayer ist.

### 1.2 MediaSessionService & Hintergrund-Wiedergabe

- `MediaSessionService` übernimmt das Hochstufen in einen Vordergrund-Dienst automatisch beim Start der Wiedergabe. Anforderungen: `android:foregroundServiceType="mediaPlayback"` im Manifest + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`-Permission (targetSdk 34+) ([Doku Background playback](https://developer.android.com/media/media3/session/background-playback)).
- **1.11.0:** Neues `MediaSession.Callback.onConnectAsync()` erlaubt asynchrone Controller-Verbindungen (z. B. Autorisierungsprüfungen) via `ListenableFuture`. **Wichtig:** Ohne `onConnect`/`onConnectAsync`-Override werden Session-Daten jetzt **nicht mehr mit untrusted Controllern** (Drittanbieter-Apps ohne Notification-Access) geteilt – sicherer Default ([Blog 1.11](https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html)).
- Positions-Updates für UI (Fortschrittsbalken): offizielle Empfehlung ist **Polling des Players in angemessenen Intervallen**, nicht lesender Zugriff pro Frame ([Player-Interface-Doku](https://developer.android.com/media/media3/session/player)).

### 1.3 Custom DSP: AudioProcessor / AudioSink / DefaultRenderersFactory

- Offizieller Weg für eigene DSP-Einheiten in ExoPlayer: `AudioProcessor`-Interface (`androidx.media3.common.audio`), i. d. R. via `BaseAudioProcessor` ableiten; Injection über `DefaultAudioSink.Builder#setAudioProcessors()` bzw. Override von `DefaultRenderersFactory#buildAudioSink()` ([AudioProcessor-Referenz](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor), [DefaultRenderersFactory-Referenz](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory)).
- Öffentlich verfügbare (wiederverwendbare) Processor-Klassen: `SonicAudioProcessor` (Tempo/Pitch, Grundlage von PlaybackSpeed), `ChannelMixingAudioProcessor`/`ChannelMixingMatrix`, `ToFloatPcmAudioProcessor`, `GainProcessor`, `BaseAudioProcessor` ([DefaultRenderersFactory-Referenz](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory); ChannelMixing seit ExoPlayer 2.19 in Media3 enthalten, [Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
- Referenzimplementierungen für eigene Effekte: GitHub-Issues #1281 (Passthrough-/Buffering-Verhalten) und #1822 (JNI-Bridge, `Sonic` als Vorbild) im androidx/media-Repo ([Issue #1281](https://github.com/androidx/media/issues/1281), [Issue #1822](https://github.com/androidx/media/issues/1822)).
- **Wichtig für Custom-Player wie DropSync – media3 1.9.0** hat bisher interne Utility-Klassen als öffentliche API publiziert: `WakeLockManager`, `WifiLockManager`, `AudioFocusManager`, `AudioBecomingNoisyManager`, `StuckPlayerDetector` (löst `StuckPlayerException` aus, wenn der Player hängt); außerdem ist **Wake-Lock-Handling jetzt default aktiviert** ([Release-Notes](https://developer.android.com/jetpack/androidx/releases/media3), [Blog 1.9.0](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html)). Diese Bausteine sind auch außerhalb eines ExoPlayer nutzbar bzw. als Blaupause für die eigene Pipeline.
- **Underrun-Handling:** Seit 1.8.0 nutzt `AudioTrackPositionTracker` `AudioTrack#getUnderrunCount()` zur echten Underrun-Erkennung statt Schätzung; danach wurde eine **100-ms-Gnadenfrist** beim Ready→Not-Ready-Übergang eingeführt, um transiente Underruns zu entprellen und unerwartete Rebuffers zu vermeiden (Release-Notes; Ursache war Rebuffer-Häufung nach Underrun-Detection, [Issue #3210](https://github.com/androidx/media/issues/3210), [Releases](https://developer.android.com/jetpack/androidx/releases/media3)). Exakt dieses Muster (Underrun-Zähler + Entprellzeit) sollte DropSync in der eigenen Pipeline nachbilden.

### 1.4 Crossfade / Gapless

- **Gapless:** von Media3 nativ unterstützt (u. a. via eingebettete Gapless-Metadaten; Compressed-Offload unterstützt gapless Opus u. a., [Qualcomm-Blog zu Offload](https://www.qualcomm.com/developer/blog/2023/08/audio-offload-support-exoplayer), [Release-Notes](https://developer.android.com/jetpack/androidx/releases/media3)).
- **Crossfade (zwei überlappende Tracks) ist bis heute NICHT nativ in Media3 enthalten** – älteste offene Feature-Request des Repos ([Issue #2 „Fade and crossfade audio"](https://github.com/androidx/media/issues/2)). Workaround in der Community: eigener `AudioProcessor`/zweiter Player bzw. `AudioSink`-Logik, oder Offload deaktivieren (Offload-DSP kann keine Überblendung, siehe 1.5). **Validiert damit DropSyncs Entscheidung, Crossfade/Mix in der eigenen Pipeline zu implementieren.**

### 1.5 Audio Offload

- Offload verlagert Audio-Verarbeitung von der CPU auf einen dedizierten Signalprozessor → Akkuvorteil bei **langer Wiedergabe mit ausgeschaltetem Display** ([Doku Battery consumption](https://developer.android.com/media/media3/exoplayer/battery-consumption), zuletzt aktualisiert 2026-03-13).
- **Einschränkungen:** Offload limitiert Audioeffekte, explizit inkl. **Geschwindigkeitsänderungen und Silence-Skipping**; Geräte-/Format-Support variiert → auf Zielgeräten testen ([Battery consumption](https://developer.android.com/media/media3/exoplayer/battery-consumption), [Track-Selection-Guide](https://developer.android.com/media/media3/exoplayer/track-selection#audioOffload)).
- **Android 16 (API 36) fügt PCM-Offload hinzu** („native PCM offload" für Apps mit targetSdk 36; Dekodierung wie üblich, PCM geht dann an den DSP) – in Media3 noch nicht fertig implementiert, Diskussion läuft in [Issue #2451](https://github.com/androidx/media/issues/2451); im Framework „largely undocumented" ([nift4-Analyse](https://nift4.org/2025/08/09/android-audio-stack-music-player/), [Now in Android #117](https://medium.com/androiddevelopers/now-in-android-117-google-i-o-2025-part-i-fd20a09a2299)).
- **Für DropSync:** Offload ist mit eigener DSP-Pipeline (EQ/Tempo/Crossfade) grundsätzlich inkompatibel – dort, wo DropSync „straight-through" spielt (kein EQ, 1.0x, kein Crossfade), könnte Offload theoretisch Akku sparen; praktisch ist das bei einem Custom-AudioTrack-Player mit eigenen PCM-Writes kaum zugänglich (siehe ⚠️ Abschnitt 4: direkter Offload-Track braucht Undocumented-Flags).

### 1.6 Offizielle Compose-UI-Artefakte

- Zwei Artefakte: `media3-ui-compose` (Basis-State-Holder) und `media3-ui-compose-material3` (Material3-Komponenten) ([Release-Notes](https://developer.android.com/jetpack/androidx/releases/media3)).
- **1.10.0:** `ProgressSlider`-Composable in `media3-ui-compose-material3` – Fortschritt anzeigen + Seeks via Drag & Tap (#2288); dazu Player-Composable mit top/center/bottom-Controls und `PlaybackSpeedControl` ([Blog 1.10](https://android-developers.googleblog.com/2026/03/media3-110-is-out.html), [Releases](https://github.com/androidx/media/releases)).
- **1.11.0:** `MiniController` (kompakte Playback-Bar mit Titel/Artist/Artwork/Progress + Play/Pause, Material3 Dynamic Color), `PlayerDefaults`, neue State-Holder `rememberCurrentMediaItemState`, `rememberPlaylistState`, `rememberErrorState` (+ `ErrorText`), `PlaybackSpeedState` (Long-Press-Fastforward/Slow-Motion), `PlayerPool`/`rememberPooledPlayer` ([Blog 1.11](https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html)).
- **Reife:** Die Komponenten entwickeln sich schnell (jede Minor-Version bringt neue), sind noch jung. Für rein **lokale** Player ohne ExoPlayer-`Player`-Interface sind sie nur nach Implementierung des `Player`-Interfaces nutzbar. ⚠️ Ob die APIs als „stabil" deklariert sind, ist den Notes nicht eindeutig zu entnehmen – vor Adoption Changelog prüfen.

---

## 2. Low-Level-Audio: AAudio / Oboe / AudioTrack

### 2.1 Grundsatz: Für Musikwiedergabe ist AudioTrack i. d. R. ausreichend

- **Alle** Java/NDK-Ausgabe-APIs (`AudioTrack.java`, OpenSL ES, AAudio) wrappen dasselbe C++-`AudioTrack.cpp` (Ausnahme: AAudio-MMAP-Modus) – d. h. ein Java-`AudioTrack` verliert gegenüber AAudio nichts an Pfadqualität ([nift4: „The Android audio stack from a music player's perspective", Aug 2025](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- Oboe (C++-Wrapper, wählt AAudio ab 8.0, sonst OpenSL ES) ist primär für **interaktive/niedriglatente** Anwendungen (Games, Synths) gedacht ([Oboe-Doku](https://developer.android.com/games/sdk/oboe)); ein Musik-Player braucht das nicht zwingend.

### 2.2 Performance-Modi & Puffer (AAudio-Doku, übertragbar auf AudioTrack)

- `PERFORMANCE_MODE_POWER_SAVING` ist der empfohlene Modus für **Wiedergabe vorproduzierter Musik** (größere interne Puffer, Latenz gegen Akku getauscht); `LOW_LATENCY` für interaktive Apps; `NONE` ist der balancierende Default ([AAudio-Guide](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)).
- Sample-Rate/Format: **nicht spezifizieren** (`AAUDIO_UNSPECIFIED`) und das gewählte Optimum nach dem Öffnen abfragen, statt ein Format zu erzwingen ([AAudio-Guide](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)) – entspricht auf Java-Seite `AudioTrack`-Verhandlungslogik mit `AudioAttributes`.
- Puffergröße als **Vielfaches der Burst-Größe** (`getFramesPerBurst`) wählen; adaptive Strategie: bei steigendem `getXRunCount()`/`getUnderrunCount()` Puffer um je einen Burst vergrößern ([AAudio-Guide mit Codebeispiel](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)).
- Disconnect-Behandlung (Kopfhörer abgezogen etc.): Error-Callback registrieren, Stream **auf einem anderen Thread** stoppen/schließen (Deadlock-Risiko), neu geöffneter Stream kann andere Parameter haben ([AAudio-Guide](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)).
- `AudioTrack`-Java-API bietet dieselben Hebel: `PERFORMANCE_MODE_LOW_LATENCY/NONE/POWER_SAVING`, `getUnderrunCount()` (Underrun = Buffer leer → Knackser) ([AudioTrack-Referenz](https://developer.android.com/reference/android/media/AudioTrack), [Framework-Quelle](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioTrack.java)).

### 2.3 Sample-Rate-Verhandlung & Resampling (wichtig für DropSync)

- AudioFlinger wählt pro Track einen Mix-Port: **BitPerfectThread > DirectOutputThread > OffloadThread > MixerThread** ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Gotcha:** Nicht-direkte Mixer-Ports öffnen mit der **höchsten** vom HAL deklarierten Rate und **rekonfigurieren nie** – eine 48-kHz-Datei auf einem 96-kHz-DAC wird deshalb *hoch*resampled. „Alles läuft auf 48 kHz" ist ein Mythos; die Output-Rate hängt vom HAL ab ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- Volume/Mixing läuft immer, ist bei Solo-Track und 100 % Lautstärke aber mathematisch transparent ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Empfehlung für DropSync:** Native Rate des Datei-Materials an `AudioTrack` übergeben (Framework-Resampling ist hochwertig, aber vermeidbar), dynamische Rekonfiguration der Pipeline bei Trackwechsel mit abweichender Rate einplanen (oder einmalig auf eine feste Arbeitsrate entscheiden und intern resampeln – z. B. via FFmpeg `swr`/`soxr`).

### 2.4 USB/DAC

- Für **direkte** USB-Ausgabe (Umgehung des Mixers) gibt es **keine öffentliche API** – nur `isDirectPlaybackSupported()` (ab Android 10) als Check; echte 384/768 kHz erfordern Hacks oder eigene USB-Treiber (UAPP, Neutron, HiBy …) ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- Bit-Perfect-Thread benötigt den USB-AIDL-HAL (z. Zt. kaum Geräte) + „preferred mixer attributes" (öffentlich ab Android 14, nur USB; am Pixel 7a buggy: DAC schaltet z. B. von 88,2 kHz nicht korrekt herunter) ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/), Community-Diskussion [Auxio #1210](https://github.com/OxygenCobalt/Auxio/issues/1210)).
- ⚠️ Praktische Konsequenz: Volle Bit-Perfect-Garantie ist auf Android (Stand 2026) für normale Apps **nicht erreichbar**; realistisches Ziel: richtige Rate/Bit-Tiefe bis zum HAL liefern und dem Nutzer transparent anzeigen.

---

## 3. Bluetooth 2026 (LE Audio / Auracast / Codecs / Output-Switcher)

### 3.1 LE Audio & Auracast

- LE Audio ist **ab Android 13 (API 33) eingebaut**: `BluetoothLeAudio`-Profil, `AudioDeviceInfo.TYPE_BLE_HEADSET`, `isLeAudioSupported()`, `isLeAudioBroadcastSourceSupported()` ([BLE-Audio-Übersicht](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)).
- Medien-Apps müssen **nichts Spezielles tun**: Routing zwischen Classic (A2DP) und LE Audio (LC3) macht die Plattform; optional `setPreferredDevice()` – die Nutzerwahl in den Systemeinstellungen gewinnt immer ([BLE-Audio-Übersicht](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)).
- **Auracast/Audio Sharing** wurde mit Android 16 zum Nutzer-Feature (Ausgabe über den System-Output-Switcher; Hardware vorausgesetzt, i. d. R. Pixel 8+/aktuelle Samsung-Flaggschiffe) ([Google-Blog „LE Audio Auracast support expands"](https://blog.google/products-and-platforms/platforms/android/le-audio-auracast-support/), [developer.android.com BLE-Audio](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)).
- Dual-Mode-Headsets (Classic + LE) sind 2026 noch die Norm; LC3 ersetzt SBC/mSBC im LE-Pfad ([BLE-Audio-Übersicht](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)).
- Vorteil für Workout-Szenarien: LE Audio erlaubt **gleichzeitiges Mikrofon + HiFi-Wiedergabe**, ohne Qualitätsverlust wie bei Classic BT ([BLE-Audio-Übersicht](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview)).

### 3.2 LDAC/aptX/Codec-Transparenz

- Der A2DP-HAL **ignoriert** von der App angeforderte Stream-Parameter; Codec-Steuerung (LDAC/aptX-Qualität etc.) läuft heute über Entwickleroptionen oder Companion-Device-Zuordnung – Apps haben darauf kaum Einfluss ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- Deklarierte devicePort-Audio-Profile sind rein informativ und häufig falsch (z. B. BT-Port deklariert int16, LDAC nutzt intern int32) – für Codec-Anzeigen im UI also **nicht vertrauen** ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Nutzer-Transparenz:** Ehrliche Anzeige = „was die Datei ist" (Rate/Tiefe) + „was der aktuelle Sink vermutlich erhält" (Bluetooth-Codec via `BluetoothA2dp`-Status nur eingeschränkt lesbar). ⚠️ Es gibt keine saubere öffentliche API, den aktiven BT-Codec/Bitraten-Modus abzufragen; Apps, die das anzeigen, nutzen inoffizielle Wege.

### 3.3 Output-Switcher / Routing

- Der Route-Button in der **Media-Notification** öffnet seit **Android 11** den System-Output-Switcher; Defaults: Lautsprecher + alle verbundenen BT-Geräte; Apps können via **AndroidX MediaRouter** Routen filtern/hinzufügen, OEMs via `MediaRouteProvider` ([Routing-Doku](https://developer.android.com/media/routing)).
- Voraussetzung für den Notification-Button in der Praxis: aktive Media-Notification mit gültigem `MediaSession`-Token (MediaStyle) ([Media-Controls-Doku](https://developer.android.com/media/implement/surfaces/mobile)).
- `MediaRouter2` (Plattform, `RoutingController` etc.) für Routing-Sessions ([Referenz](https://developer.android.com/reference/android/media/MediaRouter2)); Jetpack `androidx.mediarouter` für Kompatibilität ([Releases](https://developer.android.com/jetpack/androidx/releases/mediarouter)). ⚠️ In den Android-36.1-API-Diffs tauchen neue Methoden `setDeviceSuggestions(...)`/`showSystemOutputSwitcher(Token)` auf ([API-Diff](https://developer.android.com/sdk/api_diff/36.1/changes/android.media.MediaRouter2)) – Neuheit, noch nicht weiter dokumentiert.
- **Media3 1.11:** `CastParams` kann den Cast-Button den **nativen SystemUI-Output-Switcher** öffnen lassen ([Blog 1.11](https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html)) – Bestätigung der Richtung „System-UI statt eigenem Routing-UI".
- Auf Audio-Gerätewechsel reagieren: offizieller Guide „Handling changes in audio output" (`AudioDeviceCallback`, `becoming noisy`) ([Doku](https://developer.android.com/media/platform/output)).
- **Für DropSync:** Kein eigenes Output-Picker-UI bauen; MediaSession sauber exportieren (→ System-Switcher, Wear, Auto) + `AudioBecomingNoisyManager`-Muster (Pause bei Kopfhörer-Verlust; seit media3 1.9.0 als Klasse öffentlich, siehe 1.3).

---

## 4. Lossless / Hi-Res / Bit-Perfect

- **Container/Decoder:** WAV & FLAC unterstützen float (24+ Bit verlustfreie Präzision) und bis 192 kHz; erlaubte Hi-Res-Raten: 88,2/96/176,4/192 kHz (AOSP-Hi-Res-Doku, Android 10) ([source.android.com](https://source.android.com/docs/core/audio/highres-effects)). MediaCodec decodiert offiziell 192 kHz/24-Bit WAV+FLAC ab Android 10; experimentell funktionieren auch 384 kHz ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Float- vs. Int-Pfad:** Seit Android 9 läuft die Effekt-Pipeline intern multichannel-float; Legacy-Effekte werden per Adapter nach int16 konvertiert ([AOSP-Hi-Res](https://source.android.com/docs/core/audio/highres-effects)). ExoPlayer konvertiert ohne Float-Output alles auf int16 (bis zu 16 Bit Verlust via `ToInt16PcmAudioProcessor`); mit Float-Output wird int24 verlustfrei nach float32 gehoben (int32: bis zu 8 Bit Quantisierungsfehler, praktisch irrelevant) ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Framework-Cap:** harte Obergrenze 192 kHz im normalen Pfad; darüber → Zwang in direkte Outputs ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- **Empfehlung für DropSync (eigene Pipeline):**
  - Intern in **float32** mischen/DSP rechnen (EQ/Crossfade/Tempo in float, kein Clipping durch Zwischen-Quantisierung); am Ende einmalig auf das Zielformat des `AudioTrack` quantisieren (mit Dither ⚠️ optional, Android machts intern ohne).
  - `ENCODING_PCM_FLOAT` verwenden, wenn der HAL-Sink es akzeptiert; int24 nicht selbst nach int16 degradieren.
  - Tatsächliches Ausgabeformat dem Nutzer anzeigen (Rate/Tiefe/Codec) – aber deklarierte Ports nicht als Quelle der Wahrheit nehmen (siehe 3.2).
- **EQ & Hi-Res:** eigener float-EQ im eigenen DSP ist die zuverlässigste Qualität; Plattform-`android.media.audiofx.Equalizer` ist int16-orientiert/legacy ([AOSP-Hi-Res](https://source.android.com/docs/core/audio/highres-effects)) – weiteres Argument für DropSyncs Eigenbau.

---

## 5. Compose-Performance für Media-UIs

### 5.1 Versionen & Compiler

- Aktuell: **Compose BOM 2026.08.00 → Compose 1.12** (12. Aug 2026); 1.12 bringt u. a. neue **Test-APIs gegen Flakiness/lange Laufzeiten beim State-Sampling** ([Compose-Releases](https://developer.android.com/jetpack/androidx/releases/compose), [Blog August '26](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html), [BOM-Mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)).
- **Strong Skipping ist seit Kotlin 2.0.20 default an** (instabile Parameter ⇒ trotzdem skippable; Lambdas in Composables werden auto-gemerkt) ([Strong-Skipping-Doku](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping), [Codelab](https://developer.android.com/codelabs/jetpack-compose-performance)). Manuelles `@Stable`-Annotation-Farming ist weitgehend obsolet.
- **Baseline Profiles** bringen bis zu ~40 % Start-/Laufzeitgewinn und sind für Compose-Apps (JIT-lastig) der billigste Hebel ([Performance-Best-Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices), [Codelab](https://developer.android.com/codelabs/jetpack-compose-performance)). ⚠️ Die 40 %-Zahl kursiert in Googles Vorträgen/Blog; im Codelab-Kontext bestätigt.

### 5.2 Listen (Bibliothek mit vielen Tracks)

- LazyColumn/Row: `key` pro Item, keine 0-Pixel-Items, keine gleichgerichteten scrollbaren Nested-Layouts, mehrere Elemente pro Item nur bewusst ([Lazy-Layouts-Doku](https://developer.android.com/develop/ui/compose/lists), [Best-Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)).
- Große Bibliotheken: **Paging 3 + Room-PagingSource** statt `Flow<List<Song>>` (Load-in-Pages, Prefetch) ([Paging-Overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), siehe auch Abschnitt 8).

### 5.3 Fortschrittsbalken/Position ohne Recomposition-Sturm

- Offizielle Media3-Empfehlung: Position **pollen** (Intervall), nicht pro Frame lesen ([Player-Doku](https://developer.android.com/media/media3/session/player)).
- State-Reads **deferred** so tief wie möglich im Baum ziehen (draw-Phase statt Composition): `Modifier.drawBehind`/`Canvas`-Lambda liest den Fortschritt ⇒ nur Re-Draw, keine Rekomposition der Umgebung ([Best-Practices „defer reads"](https://developer.android.com/develop/ui/compose/performance/bestpractices)).
- `withFrameNanos` (frame-genau, `MonotonicFrameClock`) für frame-synchronisierte Updates nutzen, aber State-Writes drosseln (z. B. nur bei Sekundenwechsel) bzw. rein in die Draw-Phase verlagern ([withFrameNanos-Referenz](https://composables.com/jetpack/androidx.compose.runtime/runtime/functions/withFrameNanos/api), [Community-Consensus](https://tanishranjan.medium.com/compose-ui-performance-secrets-part-2-5-advanced-techniques-for-ultra-smooth-apps-3dd7d65311c4)).
- Konkretes Muster für DropSync: Fortschritt/Waveform-Playhead als `State<Long>` nur im `Canvas`/`drawBehind`-Lambda lesen; Zeit-Label separat mit 1-Hz-Update.

### 5.4 Waveform-Rendering (graphicsLayer/Canvas)

- Hohe Update-Frequenzen (Waveform, Playhead) → **Canvas/DrawScope statt vieler Composables**; SO-Konsens bei 10k-Items-Realtime-Rendering: Canvas ([Stack Overflow](https://stackoverflow.com/questions/79513525/performance-issues-in-jetpack-compose-lazyrow-with-10-000-items-and-frequent-fr)).
- `graphicsLayer` nutzen, um Zeichnen/Alpha-Animationen von Layout/Composition zu entkoppeln ([Best-Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)).
- Für sehr teure Shader-Effekte: AGSL/RuntimeShader als Compose-Option ([Community-Vergleich Canvas vs. AGSL](https://proandroiddev.com/pde-based-wave-simulation-in-jetpack-compose-canvas-vs-agsl-58d52a88f22f)); extremster Fall (Pixel-Perf.) → GPU-Engine wie Filament ([Reddit-Report „8x performance"](https://www.reddit.com/r/androiddev/comments/1v5a23f/reaching_the_limits_of_jetpack_compose_canvas/)) – für statische Peak-Waveforms unnötig.

---

## 6. Waveform / Visualisierung

### 6.1 Peak-Extraktion & Persistenz

- **Offline-Decoding** (MediaCodec/MediaExtractor oder – bei DropSync ohnehin vorhanden – FFmpeg) einmalig pro Track → **min/max-Peaks pro Bucket** (z. B. 100–1000 Buckets/Sekunde bzw. pro Pixel-Spalte) berechnen und persistieren; davon werden bei Zoomstufen beliebig weitere Buckets aggregiert. Genau dieses Muster (offline extraction statt Live-Visualizer) wird auch in der media3-Community als Alternative ohne Mikrofon-Permission diskutiert ([androidx/media #3028](https://github.com/androidx/media/issues/3028)).
- Persistenz-Format: kompakte Binärdatei (Float-/Int16-Array min/max pro Bucket) neben der Track-Row in Room (BLOB oder Datei im app-spezifischen Verzeichnis); Versionierung des Formats einplanen (Bucket-Größe als Header). ⚠️ Kein offizieller Android-Standard für Waveform-Caches – Praxis-Empfehlung aus Playern/Open-Source-Bibliotheken (Referenz-Implementationen: [karya-inc/Waveform (Compose, KMP)](https://klibs.io/project/karya-inc/Waveform), [linc-Software/AudioWaveform](https://github.com/linc-Software/AudioWaveform) – Java-Tooling generiert Peaks serverseitig).
- Extraktion sollte inkrementell/resumable und im Hintergrund (WorkManager) laufen; für große Bibliothekenlazy (on-demand beim ersten Öffnen des Tracks).

### 6.2 Live-Tap vs. Visualizer-API

- `android.media.audiofx.Visualizer` benötigt **`android.permission.RECORD_AUDIO`** (Runtime-Permission!) und attached auf eine Audio-Session – für Musik-Apps ein erheblicher Privacy-Friction-Punkt ([Framework-Quelle](https://android.googlesource.com/platform/frameworks/base/+/5ac72a2/media/java/android/media/audiofx/Visualizer.java), [MS-Learn-Spiegel der Referenz](https://learn.microsoft.com/en-us/dotnet/api/android.media.audiofx.visualizer?view=net-android-35.0), [androidx/media #3028](https://github.com/androidx/media/issues/3028)).
- Es gibt **keine offizielle Media3-Waveform-API** (Anfrage in [#3028](https://github.com/androidx/media/issues/3028) ohne dokumentierte Lösung; der übliche Weg ist ein `AudioProcessor`-Tap im Player-Prozess).
- **Für DropSync klarer Sieg des DSP-Taps:** Die eigene Pipeline führt sowieso jedes PCM-Sample vorbei – ein Tap am DSP-Output (post-Mix, post-EQ) liefert exakte Live-Daten ohne Permission, ohne Latenz-Problem und funktioniert auch mit Crossfade-Mix (zwei Tracks überlagert). Visualizer nur als Fallback für „was tatsächlich rausgeht" irrelevant.

### 6.3 Rendering

- Statische Peaks + Live-Playhead: ein `Canvas` (DrawScope), Peaks als vorberechnetes `Path`/FloatArray; Re-Draw nur bei Playhead/Zoom-Änderung (draw-Phase-Read, siehe 5.3/5.4) ([Compose-Graphics-Doku](https://developer.android.com/develop/ui/compose/graphics/draw/overview), [Best-Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)).
- Marker/Segmente als eigene, state-gesteuerte Layer über dem Canvas; Hit-Testing über `pointerInput` mit Abstandstoleranz.

---

## 7. Hintergrund-Wiedergabe & Akku (Android 14–16)

### 7.1 Foreground-Service-Regeln

- **Android 14 (API 34):** FGS muss einen Typ deklarieren (`mediaPlayback`) + Permissions `FOREGROUND_SERVICE` und `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; Play-Console-Deklaration (inkl. ggf. Video) nötig; Verstoß ⇒ `MissingForegroundServiceTypeException`/`SecurityException` ([FGS-Types-Änderungen Android 14](https://developer.android.com/about/versions/14/changes/fgs-types-required), [Play-Policy](https://support.google.com/googleplay/android-developer/answer/13392821)).
- **Android 15 (API 35):** Start eines mediaPlayback-FGS aus `BOOT_COMPLETED` heraus verboten (Wiedergabe muss nutzerinitiiert sein); neuer Typ `mediaProcessing` (Transcoding etc.) mit 6h/24h-Quote – `mediaPlayback` bleibt **ohne Zeitlimit**, solange aktiv gespielt + persistente Notification ([FGS-Änderungen Android 15](https://developer.android.com/about/versions/15/changes/foreground-service-types)).
- **Android 16 (API 36):** Weiter verschärfte FGS-/Job-Governance (Jobs während FGS zählen zur Jobquote); `mediaPlayback` bleibt bei legitimer, nutzsichtbarer Wiedergabe unbegrenzt ([Behavior changes 16](https://developer.android.com/about/versions/16/behavior-changes-16)). Start aus vollständigem Hintergrund kann `ForegroundServiceStartNotAllowedException` auslösen – Wiedergabe aus sichtbarer App/Notification/Session-Callback starten ([SO #77847645](https://stackoverflow.com/questions/77847645/playing-media-item-with-app-in-the-background-triggers-foregroundservicestartnot)).
- **Media3-Checkliste:** Service mit `mediaPlayback`-Typ + Permission deklarieren; `MediaSessionService` übernimmt FGS-Promotion ([Background-playback-Doku](https://developer.android.com/media/media3/session/background-playback)).

### 7.2 MediaStyle-Notification & Session-Oberflächen

- Seit **Android 13** rendert das System die Medien-Controls (Quick-Settings-Carousel) aus der Session; App braucht MediaStyle-Notification mit gültigem MediaSession-Token; Notifications sind seit 13 **immer dismissable** (Session-Teardown-Handling beachten, [androidx/media #211](https://github.com/androidx/media/issues/211)) ([Media-Controls-Doku](https://developer.android.com/media/implement/surfaces/mobile), [Blog „Playing nicely with media controls"](https://android-developers.googleblog.com/2020/08/playing-nicely-with-media-controls.html)).
- Dieselbe Session verteilt die Steueroberflächen systemweit (System-Media-Player, Output-Switcher, Wear OS, Auto) – Verteilung der „Media-Session-Surfaces" kommt aus einer gepflegten Session + Metadata/Artwork, nicht aus Custom-Notifications ([Routing-Doku](https://developer.android.com/media/routing), [Media-Controls](https://developer.android.com/media/implement/surfaces/mobile)).

### 7.3 Akku

- Audio-Offload nur bei langer Bildschirm-aus-Wiedergabe; offload schließt Speed-Changes/Effekte aus ([Battery-Doku](https://developer.android.com/media/media3/exoplayer/battery-consumption)) – für DropSync (DSP immer an) nicht relevant, aber dokumentierbarer Trade-off.
- Wake-Lock: media3 aktiviert `setWakeMode(C.WAKE_MODE_LOCAL)` inzwischen **default** ([Blog 1.9.0](https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html)); DropSync sollte für die eigene Pipeline einen partiellen Wake-Lock während aktiver DSP-Wiedergabe halten (Muster: media3 `WakeLockManager`, seit 1.9.0 öffentlich, [Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
- Battery-Hygiene: Positionspolling der UI stoppen, wenn die App nicht sichtbar ist; Sensoren (HR) nur während aktivem Workout sammeln.

---

## 8. Musikbibliothek: MediaStore vs. SAF, Scanning, Room, Tagging

### 8.1 Dateizugriff

- **MediaStore** ist der geführte Weg für Musik in Shared Storage (scoped storage): Queries liefern Title/Artist/Album oft schon befüllt; eigene Scans via `MediaScannerConnection`/MediaStore-Insert ([Shared-Media-Doku](https://developer.android.com/training/data-storage/shared/media)).
- **SAF** (`ACTION_OPEN_DOCUMENT_TREE`) für nutzergewählte Ordner außerhalb der Standard-Medienordner; Achtung: Limit von **128 persistierten URI-Permissions** ([Community-Hinweis](https://www.reddit.com/r/androiddev/comments/jib3l7/android_11_scoped_storage_mediastore_can_create/), [SO](https://stackoverflow.com/questions/56062168/media-scanner-for-secondary-storage-on-android-q)).
- Legacy-Scan-Broadcasts funktionieren ab API 29 nicht mehr ([Ultrasonic #435](https://github.com/ultrasonic/ultrasonic/issues/435)).
- **Android 16:** `MediaStore#getVersion()` ist jetzt pro App eindeutig (Fingerprinting-Gegenmaßnahme) ([Behavior changes 16](https://developer.android.com/about/versions/16/behavior-changes-16)).
- **Empfehlung DropSync:** Primär MediaStore-Query als Kandidatenliste (inkl. `DATE_MODIFIED` für Delta-Scans), optional SAF für Nutzerverzeichnisse; eigene Tag-Anreicherung in Room cachen (Key: Pfad/URI + Last-Modified).

### 8.2 Metadaten/Tagging

- `MediaMetadataRetriever` ist für Bulk-Scans **zu langsam/unzuverlässig** (Sekunden pro Datei; spinnt MediaExtractor-Pipeline hoch; herstellerabhängige Bugs) ([Reddit-Diskussion](https://www.reddit.com/r/androiddev/comments/1an0k8h/optimizing_mediametadataretriever/), [SO](https://stackoverflow.com/questions/41557384/android-mediametadataretriever-fails-to-load-metadata-on-old-samsung-devices)).
- Schneller & robuster: **TagLib** (C++, nur Tag-Header; via JNI) oder **JAudiotagger** (pure Java; Android-Fork [AdrienPoupa/jaudiotagger](https://github.com/AdrienPoupa/jaudiotagger) via JitPack, von Vinyl Music Player genutzt) ([taglib.org](https://taglib.org), [RouHim/jaudiotagger](https://github.com/RouHim/jaudiotagger)).
- **DropSync-spezifisch:** Da FFmpeg bereits Teil des Stacks ist, ist `libavformat`-Metadaten-Lesen (nur Header-Parsen) die naheliegendste, schnellste Lösung ohne zusätzliche Abhängigkeit – gleiche Technik, die Community für „schneller als MediaMetadataRetriever" empfiehlt ([Reddit](https://www.reddit.com/r/androiddev/comments/1an0k8h/optimizing_mediametadataretriever/)).

### 8.3 Room at Scale

- Indizes auf alle Sortier-/Filter-Spalten (title/artist/album/addedDate…), `@Transaction` für Relations-Joins, gezielte Projektionen statt ganzer Entities in Listen-Queries ([SQLite-Performance-Best-Practices](https://developer.android.com/topic/performance/sqlite-performance-best-practices)).
- Große Libraries (100k+): **Paging 3** (`PagingSource` direkt aus Room DAO) – nie komplette Listen hydratisieren; bei Suche direkt auf DB-Projektionen arbeiten ([Paging-Overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), [Praxis-Report 110k+ Rows](https://www.reddit.com/r/androiddev/comments/17qb9mf/extremely_slow_room_db_search_with_110k_records/)).
- Full-Text-Suche: Room unterstützt `FTS4/FTS5`-Virtual-Tables für Suche ⚠️ (in der Room-Doku dokumentiert; für DropSync-Suche prüfen).

---

## 9. Wear OS (Companion-Media-Control & Workout-Kopplung)

- **Companion-Ansatz (Empfehlung für DropSync):** Die Watch-App verbindet sich als `MediaController`/`MediaBrowser` auf die **MediaSession des Handys** (Session-Distribution über das System) – Steuern ohne eigene Playback-Logik auf der Watch ([Connect-to-a-media-app-Doku](https://developer.android.com/media/media3/session/connect-to-media-app)).
- **Wear Media Toolkit** (Teil von Horologist, Compose for Wear OS + Media3): fertige Player-UIs (`backend-media3`), `AudioOffloadManager`, Download-Service auf Basis von Media3 `DownloadManager` + WorkManager; ein Media3-**Extension dekoratiert den Player und stoppt versehentliche Wiedergabe über den Watch-Lautsprecher**, bevor Ton ausgegeben wird (nur BT-Ausgabe) ([Wear-OS-Media-Doku](https://developer.android.com/media/implement/surfaces/wear-os), [Horologist-Blog](https://medium.com/androiddevelopers/ease-the-development-of-media-apps-for-wear-os-with-the-media-toolkit-1b7ea06e07e5), [github.com/google/horologist](https://github.com/google/horologist)).
- Ongoing-Activity (Wear OS 3+): Media3 erzeugt automatisch die Ongoing-Activity-Notification mit Intent zum Player ([Wear-OS-Media-Doku](https://developer.android.com/media/implement/surfaces/wear-os)).
- **Workout/HR-Kopplung:** Health Services auf Wear OS: `ExerciseClient` für aktive Workouts (Ziele, Live-Updates), `PassiveClient` für Hintergrund-Metriken; `HEART_RATE_5_SECONDS` nur wenn nötig (Akku!) ([Health-Services-Doku](https://developer.android.com/health-and-fitness/health-services), [Android-Developers-Blog](https://medium.com/androiddevelopers/wear-os-home-workouts-with-health-services-b9951fa9e0dc)). Bekannter Bug: HR-Updates via ExerciseClient können bei ausgeschaltetem Display stoppen ([Issue Tracker #253543751](https://issuetracker.google.com/issues/253543751)). Für Tempo-an-HR-Kopplung (DropSync-Feature): HR-Events vom Phone- oder Watch-Sensor via **Health Connect** bzw. Health Services abonnieren und als Control-Größe in den DSP-Tempo-Regler einspeisen; Puffer/Glättung einplanen (HR ist träge, 3–10 s Latenz typisch ⚠️ Erfahrungswert, nicht offiziell dokumentiert).

---

## 10. Testing von Audio-Pipelines

- **Pyramide (Now-in-Arch-Referenz):** Unit/Robolectric (JVM, schnell) → Screenshot-Tests (Roborazzi/Paparazzi/Compose-Preview-Screenshot-Testing) → instrumentierte Tests auf Gerät ([NIA-Testing-Wiki](https://github.com/android/nowinandroid/wiki/Testing-strategy-and-how-to-test), [Robolectric-Strategies](https://developer.android.com/training/testing/local-tests/robolectric)).
- **Audio-spezifisch:** Robolectric-Audio-Shadows sind begrenzt; echte AudioTrack/MediaCodec-/DSP-Verifikation braucht **instrumentierte Tests auf Gerät/Emulator** – Vorbild: Googles eigene CTS-Tests `AudioTrackTest` ([CTS-Quelle](https://android.googlesource.com/platform/cts/+/b36beae93fd074e908d74acca86940186066c2a0/tests/tests/media/src/android/media/cts/AudioTrackTest.java)). Konsequenz: Pipeline in (a) reine DSP-Mathe (JVM-testbar: Filter, Crossfade-Kurven, Peak-Extraktion – deterministisch mit Float-Arrays/Golden-Files) und (b) dünne AudioTrack-/Device-Schicht (instrumented) teilen.
- **Media3-Test-Utilities:** eigene Artefakte `androidx.media3:media3-test-utils` und `media3-test-utils-robolectric` (z. B. `CapturingRenderersFactory`, `TestExoPlayerBuilder`, `advance(player).untilPositionAtLeast`) ([Maven](https://mvnrepository.com/artifact/androidx.media3/media3-test-utils-robolectric), [Release-Notes](https://developer.android.com/jetpack/androidx/releases/media3)).
- **Compose-UI-Tests:** Compose-Testing-APIs + Robolectric für schnelle JVM-Tests ([Community-Guide](https://medium.com/@sebaslogen/blazing-fast-compose-tests-with-robolectric-b059f5471495), [Robolectric-Blog sharedTest](https://robolectric.org/blog/2021/10/06/sharedTest/)); **Compose Preview Screenshot Testing** ist die offizielle, niedrigschwellige Screenshot-Lösung direkt aus `@Preview` ([Studio-Doku](https://developer.android.com/studio/preview/compose-screenshot-testing)); Compose 1.12 ergänzt Test-APIs gegen Flakiness/State-Sampling-Laufzeiten ([Blog Aug '26](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html)).
- Robolectric-Screenshots nur, wenn Pixelgenauigkeit unkritisch ([Robolectric-Strategies](https://developer.android.com/training/testing/local-tests/robolectric)).
- **Golden-File-Tests fürs DSP:** Ausgabe-Segmente (EQ-Kurve, Crossfade-Rampen, Tempo) als PCM-Referenzdateien asserten (Toleranz-SNR) – entspricht dem, was CTS mit Audio-Referenzdaten macht ([CTS](https://android.googlesource.com/platform/cts/+/b36beae93fd074e908d74acca86940186066c2a0/tests/tests/media/src/android/media/cts/AudioTrackTest.java)). ⚠️ Konvention/Empfehlung, kein offizielles Google-Dokument.

---

## Konkrete technische Empfehlungen für DropSync

*(Kotlin, Compose, Multi-Modul; eigene AudioTrack+FFmpeg-Pipeline; Room; EQ/Tempo-Pitch/Crossfade/Marker/Waveform; Workout/HR)*

1. **Session-Schicht auf Media3 1.11.0 aufsetzen** (auch ohne ExoPlayer): eigener `Player`-Wrapper vor die Custom-Pipeline legen → MediaSession/MediaSessionService, System-Media-Controls, Output-Switcher, Wear-Companion und media3-Compose-Komponenten (MiniController/ProgressSlider) funktionieren gratis ([Releases](https://developer.android.com/jetpack/androidx/releases/media3), [Background-Doku](https://developer.android.com/media/media3/session/background-playback)). `onConnectAsync` + neue Safe-Defaults beachten ([Blog 1.11](https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html)).
2. **media3-Utilities übernehmen statt neu erfinden:** `AudioFocusManager`, `AudioBecomingNoisyManager`, `WakeLockManager`, `StuckPlayerDetector` sind seit 1.9.0 öffentlich – direkte Bausteine für den Custom-Player ([Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
3. **Underrun-Handling wie media3:** `AudioTrack.getUnderrunCount()` pollen, Puffer adaptiv in Burst-Schritten vergrößern, transiente Underruns mit ~100 ms entprellen, bevor ein Rebuffer/State-Wechsel passiert ([AAudio-Guide](https://developer.android.com/ndk/guides/audio/aaudio/aaudio), [Issue #3210](https://github.com/androidx/media/issues/3210), [Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
4. **Performance-Modus:** `PERFORMANCE_MODE_POWER_SAVING` (bzw. Default/NONE) für Musikwiedergabe; LOW_LATENCY nur für interaktive Preview-Fälle ([AAudio-Guide](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)).
5. **Float32 als internes DSP-Format** (EQ/Crossfade/Tempo in float rechnen, einmalig am Ende quantisieren); Track-Rate beibehalten und `AudioTrack` die Verhandlung machen lassen; **kein** int16-Zwischenformat für >16-Bit-Material ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/), [AOSP-Hi-Res](https://source.android.com/docs/core/audio/highres-effects)).
6. **Crossfade/Mix bleibt Eigenleistung** – Media3 bietet dafür bis heute nichts Natives ([Issue #2](https://github.com/androidx/media/issues/2)); zwei Dekoder-Instanzen in einen float-Mischer speisen, der auf demselben AudioTrack-Stream landet (ein Track! kein zweiter AudioTrack, sonst zwei HAL-Streams/Latenz-Drift ⚠️ Praxis-Empfehlung, nicht offiziell dokumentiert).
7. **Kein eigener Output-Picker:** System-Output-Switcher über saubere MediaSession nutzen; auf Device-Wechsel via `AudioDeviceCallback` + Becoming-Noisy reagieren ([Routing](https://developer.android.com/media/routing), [Output-Changes](https://developer.android.com/media/platform/output)).
8. **Transparenz-Anzeige ehrlich halten:** Datei-Format (Rate/Tiefe/Codec) anzeigen; BT-Codec/direkte Ausgabe nicht aus devicePort-Profilen ableiten (unzuverlässig, [nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)); Bit-Perfect nicht versprechen.
9. **Waveform-Peaks offline extrahieren & persistieren** (FFmpeg-Decoding, min/max-Buckets, Binärformat + Version-Header, on-demand/WorkManager); **Live-Tap im eigenen DSP** statt `Visualizer` (kein RECORD_AUDIO nötig, funktioniert mit Crossfade) ([#3028](https://github.com/androidx/media/issues/3028), [Visualizer-Permission](https://android.googlesource.com/platform/frameworks/base/+/5ac72a2/media/java/android/media/audiofx/Visualizer.java)).
10. **Compose-Rendering:** Playhead/Waveform in **Canvas/drawBehind mit deferred State-Reads**; Positions-State max. ~10–30 Hz bzw. nur bei sichtbarer Sekunde ändern; Listen via Paging 3 + keys; Baseline Profile ausliefern; Strong Skipping ist default (Kotlin ≥ 2.0.20) ([Best-Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices), [Strong Skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping), [Paging](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)).
11. **FGS korrekt deklarieren:** `mediaPlayback`-Typ + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + Play-Console-Deklaration; nie aus BOOT_COMPLETED starten; beim App-Start aus Background `ForegroundServiceStartNotAllowedException` beachten ([14](https://developer.android.com/about/versions/14/changes/fgs-types-required), [15](https://developer.android.com/about/versions/15/changes/foreground-service-types), [16](https://developer.android.com/about/versions/16/behavior-changes-16)).
12. **Bibliothek:** MediaStore als Kandidaten-Quelle + Delta-Scan über `DATE_MODIFIED`; Tagging über FFmpeg/libavformat (Header-only, schnell) oder TagLib/JAudiotagger statt `MediaMetadataRetriever`; Room mit Indizes + FTS + Paging ([Shared-Media](https://developer.android.com/training/data-storage/shared/media), [SQLite-BP](https://developer.android.com/topic/performance/sqlite-performance-best-practices)).
13. **Wear-Companion:** Watch-App als MediaController auf die Phone-Session (Media Toolkit/Horologist als UI-Blaupause); HR-Daten über Health Services (`ExerciseClient` beim Workout; HEART_RATE_5_SECONDS sparsam) oder Health Connect; HR-Signal glätten und als Tempo-Controller mit Hysterese koppeln ([Wear-OS-Media](https://developer.android.com/media/implement/surfaces/wear-os), [Health Services](https://developer.android.com/health-and-fitness/health-services)).
14. **Tests:** DSP-Mathe als reine JVM-Unit-Tests mit Golden-File-PCM; AudioTrack/Device-Schicht instrumented (CTS-AudioTrackTest als Vorbild); UI via Robolectric/Compose-Tests + Compose-Preview-Screenshot-Testing; media3-test-utils(-robolectric) einbinden, sobald ExoPlayer-Teile genutzt werden ([CTS](https://android.googlesource.com/platform/cts/+/b36beae93fd074e908d74acca86940186066c2a0/tests/tests/media/src/android/media/cts/AudioTrackTest.java), [NIA-Wiki](https://github.com/android/nowinandroid/wiki/Testing-strategy-and-how-to-test), [Screenshot-Testing](https://developer.android.com/studio/preview/compose-screenshot-testing)).

---

## Unsichere / offene Punkte (explizit)

- ⚠️ **Media3-Compose-Artefakte:** Komponenten sind jung und ändern sich pro Minor-Release; ob Google sie als API-stabil kennzeichnet, ist den Notes nicht explizit zu entnehmen → vor Adoption Changelog prüfen ([Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
- ⚠️ **100-ms-Grace-Period:** aus den offiziellen Release-Notes-Snippets belegt, exakte Version (1.8.x vs. 1.9.x) habe ich nicht zweifelsfrei zugeordnet ([Releases](https://developer.android.com/jetpack/androidx/releases/media3)).
- ⚠️ **PCM-Offload (Android 16):** als Plattform-Feature belegt, aber in Media3 noch nicht implementiert und im Framework kaum dokumentiert ([#2451](https://github.com/androidx/media/issues/2451), [Now in Android #117](https://medium.com/androiddevelopers/now-in-android-117-google-i-o-2025-part-i-fd20a09a2299)).
- ⚠️ **BT-Codec-Abfrage:** keine saubere öffentliche API für aktiven LDAC/aptX-Modus/Bitrate; Anzeigen anderer Apps beruhen auf inoffiziellen Wegen.
- ⚠️ **Bit-Perfect/Preferred-Mixer-Attributes:** öffentlich ab Android 14, aber laut Testbericht am Pixel 7a buggy; kaum Geräte mit USB-AIDL-HAL ([nift4](https://nift4.org/2025/08/09/android-audio-stack-music-player/)).
- ⚠️ **MediaRouter2-Neuerungen in 36.1** (`setDeviceSuggestions`, `showSystemOutputSwitcher`) nur aus API-Diff bekannt ([Diff](https://developer.android.com/sdk/api_diff/36.1/changes/android.media.MediaRouter2)).
- ⚠️ **HR→Tempo-Latenz/Glättung:** Erfahrungswerte, nicht offiziell; Health-Services-Bug bei ausgeschaltetem Display bekannt ([#253543751](https://issuetracker.google.com/issues/253543751)).

---

## Quellen

**Offiziell (Primärquellen):**
- Media3 Jetpack-Releases: https://developer.android.com/jetpack/androidx/releases/media3
- androidx/media GitHub-Releases: https://github.com/androidx/media/releases
- Blog Media3 1.11 (Aug 2026): https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html
- Blog Media3 1.10 (Mär 2026): https://android-developers.googleblog.com/2026/03/media3-110-is-out.html
- Blog Media3 1.9.0 (Dez 2025): https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html
- Media3 Background playback: https://developer.android.com/media/media3/session/background-playback
- Media3 Player-Interface (Positionspolling): https://developer.android.com/media/media3/session/player
- Media3 Battery consumption: https://developer.android.com/media/media3/exoplayer/battery-consumption
- AudioProcessor-Referenz: https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor
- DefaultRenderersFactory-Referenz: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory
- Media3-Testutils (Maven): https://mvnrepository.com/artifact/androidx.media3/media3-test-utils-robolectric
- AAudio-Guide: https://developer.android.com/ndk/guides/audio/aaudio/aaudio
- Oboe: https://developer.android.com/games/sdk/oboe und https://developer.android.com/games/sdk/oboe/low-latency-audio
- AudioTrack-Referenz: https://developer.android.com/reference/android/media/AudioTrack ; Quelle: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/media/java/android/media/AudioTrack.java
- Visualizer-Quelle (RECORD_AUDIO): https://android.googlesource.com/platform/frameworks/base/+/5ac72a2/media/java/android/media/audiofx/Visualizer.java
- AOSP Hi-Res Audio: https://source.android.com/docs/core/audio/highres-effects
- BLE-Audio-Übersicht: https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview
- Auracast-Ankündigung: https://blog.google/products-and-platforms/platforms/android/le-audio-auracast-support/
- Media Routing / Output Switcher: https://developer.android.com/media/routing
- MediaRouter2: https://developer.android.com/reference/android/media/MediaRouter2 ; API-Diff 36.1: https://developer.android.com/sdk/api_diff/36.1/changes/android.media.MediaRouter2
- androidx.mediarouter-Releases: https://developer.android.com/jetpack/androidx/releases/mediarouter
- Audio-Output-Änderungen: https://developer.android.com/media/platform/output
- FGS Android 14: https://developer.android.com/about/versions/14/changes/fgs-types-required
- FGS Android 15: https://developer.android.com/about/versions/15/changes/foreground-service-types
- Behavior changes Android 16: https://developer.android.com/about/versions/16/behavior-changes-16
- Media Controls / MediaStyle: https://developer.android.com/media/implement/surfaces/mobile
- Compose-Releases/BOM: https://developer.android.com/jetpack/androidx/releases/compose ; BOM-Mapping: https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Compose August '26 (1.12): https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html
- Compose Performance Best Practices: https://developer.android.com/develop/ui/compose/performance/bestpractices
- Strong Skipping: https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Lazy Layouts: https://developer.android.com/develop/ui/compose/lists
- Compose Performance-Codelab: https://developer.android.com/codelabs/jetpack-compose-performance
- Compose Graphics: https://developer.android.com/develop/ui/compose/graphics/draw/overview
- Paging 3: https://developer.android.com/topic/libraries/architecture/paging/v3-overview
- SQLite-Performance: https://developer.android.com/topic/performance/sqlite-performance-best-practices
- Shared Storage/MediaStore: https://developer.android.com/training/data-storage/shared/media
- Wear-OS-Media: https://developer.android.com/media/implement/surfaces/wear-os
- MediaController verbinden: https://developer.android.com/media/media3/session/connect-to-media-app
- Horologist: https://github.com/google/horologist ; Toolkit-Blog: https://medium.com/androiddevelopers/ease-the-development-of-media-apps-for-wear-os-with-the-media-toolkit-1b7ea06e07e5
- Health Services: https://developer.android.com/health-and-fitness/health-services ; Workout-Blog: https://medium.com/androiddevelopers/wear-os-home-workouts-with-health-services-b9951fa9e0dc ; Issue: https://issuetracker.google.com/issues/253543751
- Robolectric-Strategies: https://developer.android.com/training/testing/local-tests/robolectric
- Compose Preview Screenshot Testing: https://developer.android.com/studio/preview/compose-screenshot-testing
- Now in Android Testing-Wiki: https://github.com/android/nowinandroid/wiki/Testing-strategy-and-how-to-test
- CTS AudioTrackTest: https://android.googlesource.com/platform/cts/+/b36beae93fd074e908d74acca86940186066c2a0/tests/tests/media/src/android/media/cts/AudioTrackTest.java
- Play-Policy FGS: https://support.google.com/googleplay/android-developer/answer/13392821

**Offizielle Issues im androidx/media-Repo:**
- #2 Crossfade/Fade: https://github.com/androidx/media/issues/2
- #1281 Custom AudioProcessor Buffering: https://github.com/androidx/media/issues/1281
- #1822 Custom AudioProcessor JNI: https://github.com/androidx/media/issues/1822
- #211 Dismissible Notifications (A13): https://github.com/androidx/media/issues/211
- #2451 PCM-Offload: https://github.com/androidx/media/issues/2451
- #3028 Waveform-Daten: https://github.com/androidx/media/issues/3028
- #3210 Underrun/Rebuffer: https://github.com/androidx/media/issues/3210

**Sekundärquellen (technisch fundiert, nicht offiziell):**
- nift4 – Android-Audio-Stack aus Musikplayer-Sicht (Aug 2025): https://nift4.org/2025/08/09/android-audio-stack-music-player/
- Qualcomm – Audio Offload für ExoPlayer: https://www.qualcomm.com/developer/blog/2023/08/audio-offload-support-exoplayer
- Now in Android #117 (I/O 2025): https://medium.com/androiddevelopers/now-in-android-117-google-i-o-2025-part-i-fd20a09a2299
- Auxio Bit-Perfect-Diskussion: https://github.com/OxygenCobalt/Auxio/issues/1210
- TagLib: https://taglib.org ; JAudiotagger: https://github.com/RouHim/jaudiotagger ; Android-Fork: https://github.com/AdrienPoupa/jaudiotagger
- Waveform-Referenz (Compose/KMP): https://klibs.io/project/karya-inc/Waveform ; AudioWaveform: https://github.com/linc-Software/AudioWaveform
- MediaMetadataRetriever-Performance: https://www.reddit.com/r/androiddev/comments/1an0k8h/optimizing_mediametadataretriever/
- Canvas-vs-LazyRow-Performance: https://stackoverflow.com/questions/79513525/performance-issues-in-jetpack-compose-lazyrow-with-10-000-items-and-frequent-fr
- Room-Performance-Praxis (110k Rows): https://www.reddit.com/r/androiddev/comments/17qb9mf/extremely_slow_room_db_search_with_110k_records/
