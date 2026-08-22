# Recherche: Mobile Music-App UI/UX Best Practices (2025–2026)

- **Stand:** 21.08.2026
- **Fokus:** Lokale Musikwiedergabe (Offline-Player), DSP (EQ, Tempo, Crossfade/Mix), Waveform/Marker-Editing, Fitness-/Herzfrequenz-Kopplung
- **Ziel:** Fundierte UI/UX-Empfehlungen für **DropSync** (Android, Kotlin, Jetpack Compose, Material 3)
- **Methode:** Web-Recherche mit Priorität auf Primärquellen (m3.material.io, developer.android.com, Android Developers Blog, Apple HIG/Newsroom, Spotify Newsroom, androidx-Release-Notes). Sekundärquellen sind als solche gekennzeichnet.
- **Legende:** Quellen in Klammern hinter jeder Aussage. Mit *(unsicher)* bzw. *(Sekundärquelle)* markierte Aussagen wurden nicht direkt aus einer Primärquelle verifiziert oder stammen aus Blog-/Community-Quellen.

---

## 1. Material 3 Expressive (2025/2026)

### 1.1 Was ist M3 Expressive und was hat sich geändert?

- Material 3 Expressive wurde auf der **Google I/O 2025** angekündigt und rollt zusammen mit **Android 16** und **Wear OS 6** aus. Laut Google basiert es auf der „größten Design-Studie in der Geschichte des Unternehmens" und ist das größte Redesign von Android/Wear OS seit Jahren (https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/).
- Kernpfeiler laut offizieller Übersicht: **lebhafte Farben (vibrant colors), intuitive Bewegung (Motion), adaptive Typografie, kontrastierende Shapes** (https://m3.material.io/).
- **Motion:** Übergang von Tween/Easing zu **physikbasierten Spring-Animationen** für Komponenten-States; Google nennt federnde Animationen als zentrales Merkmal des Android-16-Looks (https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/).
- **Shapes:** Shape-Morphing ist ein Systemmittel — Buttons ändern z. B. beim Pressen ihre Form (z. B. Pille → Kreis), laut offizieller Material-Doku (https://m3.material.io/blog/building-with-m3-expressive, Übersicht; Komponenten-Spezifikation siehe Release Notes unten). Sekundärquellen sprechen von „35 neuen Shapes" *(Sekundärquelle: https://supercharge.design/blog/material-3-expressive, Zahl nicht primär verifiziert)*.
- **Buttons/Progress:** Neue Komponenten: **Split Buttons, Loading Indicator (neu statt CircularProgressIndicator), Wavy Progress Indicators** (Wellen-Progress, auch determinate), Floating Toolbars, FAB-Menüs, Button Groups (https://m3.material.io/; API-Details siehe 1.2).
- **Slider:** M3-Expressive-Slider haben **drei Varianten (Standard, Centered, Range), fünf Größen (XS–XL) und unterstützen erstmals vertikale Orientierung** (https://m3.material.io/components/sliders/overview, https://m3.material.io/components/sliders/specs).
- **Typografie:** variable Fonts / „adaptive typography" mit einstellbaren Achsen (Gewicht, Weite) — u. a. auf Wear OS empfohlen (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
- **Farbe:** erweiterte Farbschemata mit mehr Kontrast-Optionen („more color contrast options" für Dynamic Color) (https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/).
- **Tiefe/Blur:** Google nutzt in Android 16 gezielt **Blur als Tiefenhinweis** (z. B. Hintergrund-Blur beim Öffnen von Snapshots) (https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/).
- **Live Updates:** Neues Progress-Notification-Format (mit Fortschrittsbalken) in Android 16 — Media-Notifications profitieren automatisch (siehe Abschnitt 4.4).
- Weitere Design-Research-Details bei Google Design: https://design.google/library/expressive-material-design-google-research
- **Compose-first:** Material kündigte an, dass Android-Material nun „Compose-first" weiterentwickelt wird (https://m3.material.io/blog/material-is-compose-first).

### 1.2 Compose-APIs und Versionen (Stand August 2026)

Quelle: offizielle Release Notes (https://developer.android.com/jetpack/androidx/releases/compose-material3):

- **1.4.0-alpha:** Einführung von **MotionScheme** (Varianten `standard`/`expressive`, Spring-Physik für Komponenten), **SplitButton**, **LoadingIndicator**, **WavyProgressIndicator** (indeterminate + determinate, konfigurierbare Wellenlänge/Amplitude), **FAB Menu**, **Shape-Morphing beim Pressen für Buttons/IconButtons**, **FloatingToolbar/FlexibleBottomAppBar**, **ButtonGroup**, **WideNavigationRail**.
- **1.4.0 stabil (24.09.2025):** Material-Symbols ersetzen die alte Icons-Bibliothek; Komponenten nutzen jetzt das MotionScheme; **neue SearchBar-APIs**; `TextAutoSize`; u. a. `HorizontalCenteredHeroCarousel`.
- **1.5.0-alpha (aktuell):** expressive Varianten für TimePicker, Menüs, ListItems; **`materialExpressiveTheme()` / `expressiveLightColorScheme()` / `expressiveShapes()`** (befördert in alpha18); **`SplitButton` stabil** (alpha20); ToggleButtons stabil; `WavyProgressIndicator` in den stabilen Bestand befördert; `LoadingIndicator` + `FlexibleBottomSheet` in die Core-Bibliothek verschoben; **`motionScheme()` aus dem Experimental-Status genommen** (alpha15); vereinheitlichtes `rememberBottomSheetState`.
- Empfohlenes Setup:

```kotlin
MaterialExpressiveTheme {
    // ...
}
// bzw. MotionScheme gezielt:
MaterialTheme(motionScheme = MotionScheme.expressive())
```

(https://developer.android.com/jetpack/androidx/releases/compose-material3; allgemeine M3-in-Compose-Doku: https://developer.android.com/develop/ui/compose/designsystems/material3)

### 1.3 Wear OS (relevant für Fitness-Uhr)

- M3 Expressive für Wear OS (August 2025): runde Displays als First-Class — **„edge-hugging" Buttons mit gebogenem Rand**, **Shape-/Size-Morphing als State-Kontrast („play" vs. „pause")**, erweiterte Farben (tertiär, dynamisch von der Watchface), variable Fonts (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
- **Glanceability-Prinzipien:** nicht scrollende Templates „optimiert für Blickerfassung und Fokus", eine Aufgabe pro Bildschirm, Breakpoints/Pagination; Googles eigene Media-Controls-Kachel zeigt den Fortschritt laufender Wiedergabe (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
- Entwickler-Empfehlung: `TransformingLazyColumn` für flüssiges Scrollen, 3-Slot-`PrimaryLayout` für Tiles (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
- Wear OS 6 + M3 Expressive bringen laut Google **~10 % Batterielaufzeit-Gewinn** durch Effizienz-Design (https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/).

### 1.4 Was heißt das für Medien-Apps?

Google selbst gibt (Stand dieser Recherche) **keine dedizierte „M3-Expressive-for-Media-Apps"-Richtlinie** heraus; die Empfehlung ergibt sich aus den generischen Komponenten plus den offiziellen Media3-Compose-Komponenten (Abschnitt 3/4). Die offiziellen M3-Expressive-Taktiken (Shapes, Springs, Wavy Progress, Split Buttons, flexible Bottom Sheets) sind direkt auf Player-UIs übertragbar (https://m3.material.io/blog/building-with-m3-expressive, https://m3.material.io/).

---

## 2. Apple HIG / iOS 26 „Liquid Glass" (Branchen-Benchmark)

- **Liquid Glass** ist eine transluzente Material-Metapher, die sich wie echtes Glas verhält und ihre Farbe aus dem dahinterliegenden Inhalt zieht; sie wurde mit iOS 26 (Juni 2025) quer über alle Apple-Plattformen eingeführt (https://www.apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/, https://developer.apple.com/documentation/technologyoverviews/liquid-glass).
- **Zwei Varianten: „regular" und „clear".** Apple empfiehlt ausdrücklich: **Für Medien-Steuerflächen („in media viewing apps, like the TV app") die klare (clear) Variante verwenden, weil sie für Medien-Controls optimiert ist** (https://developer.apple.com/design/human-interface-guidelines/materials).
- Zweck: Controls/Navigation präsentieren, **ohne den Inhalt zu verdecken** — exakt das Problem von Now-Playing-Screens über Artwork (https://developer.apple.com/design/human-interface-guidelines/materials).
- **Edge Effects** (Lichtbrechung an Rändern) lenken die Aufmerksamkeit gezielt auf Elemente; bei Drag-Gesten zeigt die Kante, wohin sich ein Element bewegt (https://developer.apple.com/design/human-interface-guidelines/materials).
- Offizielle Adoptions-Empfehlungen: Glass-Flächen **nicht stapeln**, für Controls in Bars/Rails automatisch vom Framework übernehmen lassen, Scroll-Edge-Effects ergänzen, Lichtbrechung nie selbst faken,(bar-typische „impact"-Haptik) — Details in der HIG (https://developer.apple.com/design/human-interface-guidelines/materials; WWDC-Sessions: https://developer.apple.com/videos/play/wwdc2025/219/, https://developer.apple.com/videos/play/wwdc2025/356/).
- **Apple Music in iOS 26:** u. a. **AutoMix** („wie ein DJ in Apple Music" — Übergänge zwischen Songs ohne Stille, standardmäßig aktiviert), **Lyrics Translation & Pronunciation** (Übersetzung/Lautschrift in der Lyrics-Ansicht), „Sing" mit iPhone als Mikrofon (https://artists.apple.com/support/5550-ios-26-whats-new).
- Rezeption: Kritik an Lesbarkeit/Auffälligkeit von Liquid Glass; Apple hat in Folge-Updates Anpassungsoptionen (Transparenz/Tinting) nachgereicht *(Sekundärquellen: https://www.reddit.com/r/apple/comments/1njqlo5/ios_26s_liquid_glass_design_draws_criticism_from/; https://www.macrumors.com/2026/01/ — Verfeinerungen in iOS 26.2 wurden berichtet, aber nicht primär verifiziert)*.

**Übertragbarer Kern für Android:** halbtransparente Steuerflächen über Artwork (in Compose: `Modifier.blur`/Haze-artige Effekte, GraphicsLayer), Farbaufnahme aus dem Inhalt, klare Hierarchie „Inhalt groß, Controls unverdeckt", DJ-artige Übergänge als erstklassiges Feature (AutoMix) — nicht als versteckte Einstellung.

---

## 3. Now-Playing / Full-Player-Screen

### 3.1 Aktuelle Referenz-Redesigns (2025)

**YouTube Music — großes Now-Playing-Redesign (Rollout September 2025, Test seit November 2024)** (https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/):

- Song/Video-Umschalter aus der oberen Leiste entfernt; Cast bleibt neben dem Overflow-Menü.
- **Steuerleiste direkt unter Titel/Interpret** (statt am unteren Rand) — Haupt-Controls wandern nach oben, erreichbarer für Daumenbedienung.
- **Neuer „boxiger" Scrubber ohne sichtbaren Playhead; wird bei Interaktion dicker** (Touch-Feedback auf dem Progress-Slider).
- **Aktions-Carousel statt fester Icon-Reihe:** Daumen hoch/runter, Kommentare, Speichern, Lyrics, Song/Video-Switch, Teilen, Download, Radio horizontal scrollbar unter den Controls.
- **„Up Next" als Bottom-Sheet mit Drag-Handle** (Antippen oder Hochziehen), **Dual-Pane-Ansicht**: kompakt ~4 Songs sichtbar, ausklappbar zur Vollansicht — nicht mehr als persistenter Tab.
- Lyrics/verwandte Inhalte ebenfalls als **voll expandierbare Sheets** ohne Eigen-Theming; oberste Zeile zeigt aktuellen Song + Play/Pause.
- Rezeption gemischt *(Sekundärquelle: https://www.reddit.com/r/YoutubeMusic/comments/1nep8eq/new_ui_really_personally_this_is_worse/)*.

**Spotify — „User Controls"-Update (September 2025)** (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/; Analyse: https://techcrunch.com/2025/09/08/spotifys-new-smart-filters-let-you-screen-library-content-by-activity-genre-or-mood/):

- **Redesignte Queue** mit upgraded Steuerungen für **Shuffle, Smart Shuffle, Repeat und Sleep Timer**; am Queue-Ende Empfehlungen, die behalten oder verworfen werden können.
- **Song in Playlist ausblenden** („Hide in this playlist") direkt über das Drei-Punkte-Menü der Now-Playing-Ansicht.
- Playlist-Werkzeuge: verbessertes **Hinzufügen, Sortieren, Editieren** von Track-Reihenfolgen und Covern.

### 3.2 Sleep Timer

- Spotify bietet im Now-Playing (Uhr-Icon) Durations **5 Minuten bis 1 Stunde plus „End of track"** (Stopp am Ende des laufenden Songs) *(Sekundärquelle: https://www.androidpolice.com/spotify-tricks-worth-knowing/)*.
- Das Queue-Redesign 2025 hat die Sleep-Timer-Steuerung in die Queue-Oberfläche integriert (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).

### 3.3 Scrubber / Fortschritt / Playback-Speed

- **Offizielle Media3-Compose-Komponenten:** `ProgressSlider` (Fortschritt anzeigen + **Seek per Ziehen und Antippen**; Issue #2288) und `PlaybackSpeedControl` / `PlaybackSpeedToggleButton` in `media3-ui-compose-material3` (Release Notes: https://developer.android.com/jetpack/androidx/releases/media3; Einführungs-Blog: https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html und https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html).
- Media3 1.11 fügt der `PlaybackSpeedState` API für **Long-Press-Vorspulen/Zeitlupe** hinzu (Demo „ShortFormPlayerScreen") (https://developer.android.com/jetpack/androidx/releases/media3).
- **Tempo/Pitch:** `PlaybackParameters` wrappen Speed + Pitch; **Pitch-Korrektur ist Standard (pitch=1.0, Zeitdehnung)**; mit `withPitch()` kopierbar; Absichtlicher Chipmunk-Effekt via `PlaybackParameters(speed, speed)` (API-Referenz: https://developer.android.com/reference/kotlin/androidx/media3/common/PlaybackParameters; ExoPlayer-Artikel: https://medium.com/google-exoplayer/variable-speed-playback-with-exoplayer-e6e6a71e0343).
- **Vorsicht bei Extremwerten:** Speeds außerhalb des Gerätebereichs werfen `IllegalArgumentException` (z. B. speed=2.45 auf Android 14) — sichere Bereiche verwenden (https://github.com/androidx/media/issues/1101).
- Media3 1.11: **Pitch-Erhalt beim Time-Stretching** über `EditedMediaItem.Builder#setSpeed(SpeedParameters)` (Export/Transformer) (https://developer.android.com/jetpack/androidx/releases/media3).

### 3.4 Lyrics & Übergänge

- Apple: Lyrics-Übersetzung + Aussprache in der Lyrics-Ansicht (Auto-erscheinen, wenn Original-Lyrics vorliegen) (https://artists.apple.com/support/5550-ios-26-whats-new).
- **AutoMix** (Apple Music, iOS 26): DJ-artige, nahtlose Songübergänge **standardmäßig an** — Beleg, dass Crossfade/Mix inzwischen Erwartungsstatus und kein Nischen-DSP-Feature ist (https://artists.apple.com/support/5550-ios-26-whats-new).
- Spotify-Queue-Redesign zeigt den Trend „Sichtbare Kontrolle über Auto-Features“: Empfehlungen am Queue-Ende behalten/verwerfen, Autoplay & Smart Shuffle abschaltbar (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).

### 3.5 Gesten (Stand der Quellen)

- **Swipe-to-dismiss** (Player als Sheet mit Drag-Handle) und **Queue als hochziehbares Sheet** sind die dominanten Muster in YTM 2025 und Apples Sheet-basiertem Design (https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/; Sheets allgemein: https://developer.apple.com/design/human-interface-guidelines/materials).
- Eine dedizierte, aktuelle Primärquelle nur für „Swipe im Player = Queue/Dismiss"-Gesten existiert in dieser Recherche nicht; das Muster ist aber über die Redesign-Analysen belegt *(einschränkung: keine Google/Apple-Richtlinie verifiziert)*.

---

## 4. Mini-Player, Queue-Management, Systemflächen

### 4.1 Mini-Player

- **Offizielle Komponente seit Media3 1.11.0 (Aug 2026): `MiniController`** in `media3-ui-compose-material3` — kompakte Playback-Leiste mit Titel, Interpret, Artwork und Fortschritt; Material3-Dynamic-Color-Support wird im Blog explizit genannt (https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html; https://developer.android.com/media/media3/ui/compose; https://developer.android.com/jetpack/androidx/releases/media3).
- Architektur-Empfehlung von Google: **`media3-ui-compose` (Basisbausteine + State-Holder wie `PlayPauseButtonState`, `CurrentMediaItemState`) für eigene Designsysteme; `media3-ui-compose-material3` für fertige M3-UI** (`Player`, `MiniController`, `PlayPauseButton`, `SeekBackButton`, `PositionAndDurationText`, `ErrorText`) (https://developer.android.com/media/media3/ui/compose).
- Bekannte Muster (tap = Vollplayer, persistent am unteren Rand über/unter der Navigation) sind Community-/Produktbeobachtung *(Sekundärquelle, z. B. https://www.reddit.com/r/UI_Design/comments/1r4tgd0/)*; als Komponenten-Referenz dient der offizielle `MiniController`.

### 4.2 Queue-Management

- YTM 2025: Queue als **Sheet mit Kompakt-Ansicht (~4 Tracks) + ausklappbarer Dual-Pane-Vollansicht**, Drag-Handle statt Tab (https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/).
- Spotify 2025: Queue-Redesign mit Shuffle/Repeat/Sleep-Timer-Kontrollen und **End-of-Queue-Empfehlungen (behalten/verwerfen)** (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).
- **Drag-&-Drop-Sortieren in Compose:** Es gibt **keine offizielle Reorder-API für LazyColumn** (Stand 2026); De-facto-Community-Standard ist **Calvin-LL/Reorderable** (LazyColumn/Row/Grids, Compose Multiplatform) (https://github.com/Calvin-LL/Reorderable; Einordnung: https://stackoverflow.com/questions/64913067/reorder-lazycolumn-items-with-drag-drop).

### 4.3 System-Medienflächen (Lockscreen, Quick Settings, Output-Switcher)

- Mit **Media3 `MediaSessionService`** erscheinen die System-Mediencontrols automatisch (Lockscreen, Quick Settings, Bluetooth/Headset) (https://developer.android.com/media/media3/session/background-playback).
- **Android 13+:** Das System **leitet die Action-Buttons aus `PlaybackState` ab** — bis zu 5 Action-Slots (davon 3 sichtbar), bis zu 5 Custom Actions mit Prioritäts-Ranking plus Close-Icon; Apps sollen ihre wichtigsten Aktionen priorisieren (https://developer.android.com/about/versions/13/behavior-changes-13).
- **Custom Buttons via Media3:** `MediaSession.Builder.setMediaButtonPreferences(...)` / dynamisch überschreibbar (https://developer.android.com/media/media3/session/control-playback). Seit **Media3 1.9.0 (Dez 2025)** genügen Standard-Player-Commands (z. B. `COMMAND_SEEK_FORWARD`) statt voller Custom-Command-Handler (https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html).
- **Achtung Android 16:** Mit Media3 1.6.0 gab es regressionsartige Probleme mit Custom-Buttons in den neu gestalteten Android-16-Media-Controls (GitHub-Issue dokumentiert) (https://github.com/androidx/media/issues/2292). Der Output-Switcher („Auf diesem Gerät abspielen") wurde in Android 16 optisch neu gestaltet *(Sekundärquelle: https://www.androidheadlines.com/2025/04/android-16-beta-transforms-the-media-output-switcher-ui.html)*.
- **Android 16 „Live Updates":** MediaStyle-Notifications mit `ProgressBar` im RemoteViews animieren als Live-Updates-Muster; **auf Media-Notifications wird das automatisch angewendet** (https://developer.android.com/about/versions/16/behavior-changes-16).
- **Persistente Controls seit Android 11:** Media-Controls bleiben nach dem Schließen der App in Quick Settings sichtbar und erlauben Resume — App muss `setSessionActivity` setzen, damit die Wiedergabe-Position erhalten bleibt (https://developer.android.com/about/versions/11/custom-media-controls; Blog: https://android-developers.googleblog.com/2020/08/playing-nicely-with-media-controls.html).

---

## 5. Bibliothek & Suche

- **Smart Filters (Spotify, Sept 2025):** Bibliothek filterbar nach **Aktivität, Stimmung, Genre** — z. B. „Lauf-Playlist" finden; Filter-Icon oben links in „Deine Bibliothek", Auswahl aktualisiert die Ansicht sofort (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/; https://techcrunch.com/2025/09/08/spotifys-new-smart-filters-let-you-screen-library-content-by-activity-genre-or-mood/).
- **Playlist-Editing:** verbesserte Add/Sort/Edit-Werkzeuge für Track-Reihenfolgen, einfachere Titel- und Cover-Änderung (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).
- **Suche:** Compose Material 3 brachte in **1.4.0 neue SearchBar-APIs** (https://developer.android.com/jetpack/androidx/releases/compose-material3). Eine eigene, aktuelle „Search-UX für Musik-Apps"-Primärrichtlinie wurde nicht gefunden *(einschränkung)*.
- **Liste vs. Grid:** Für Android Auto gelten offizielle **Content-Style-Hints** (listable/browsable Items, Grid vs. Liste via `ContentStyle`-Extras) — ein Indiz, dass Grid für Artwork-lastige Inhalte, Listen für Track-Listen als Systemkonvention gilt (https://developer.android.com/training/cars/media).
- Batch-Aktionen/Multi-Select: keine 2025/26-Primärquelle verifiziert; M3-Listen + Long-Press-Multiselektion sind der etablierte Plattform-Konsens *(einschränkung)*.

---

## 6. Motion & Feedback

### 6.1 Spring-Physik & MotionScheme

- M3 Expressive: Spring-basierte Bewegung als Default für Komponenten-States; in Compose via `MaterialExpressiveTheme` bzw. `MaterialTheme(motionScheme = MotionScheme.expressive())` (https://m3.material.io/; https://developer.android.com/jetpack/androidx/releases/compose-material3).

### 6.2 Shared-Element-Transitions (Compose)

Offizielle Doku (zuletzt aktualisiert 15.07.2026; https://developer.android.com/develop/ui/compose/animation/shared-elements):

- Kern-APIs: `SharedTransitionLayout` (liefert `SharedTransitionScope`), `Modifier.sharedElement()` (identischer Inhalt), `Modifier.sharedBounds()` (Container-Transform, visuell unterschiedlicher Inhalt, `ResizeMode.scaleToBounds()`), `rememberSharedContentState(key)`.
- Kombination mit `AnimatedContent`/`AnimatedVisibility`/**NavHost (navigation-compose liefert `AnimatedContentScope`)**; für verschachtelte Scopes **CompositionLocal** statt Parameter.
- **Praxisregeln:** Modifier-Reihenfolge ist entscheidend (Size-Modifier nach dem Shared-Element-Modifier); **Keys als data classes mit ID**, nicht simple Strings; ohne AnimatedContentScope `sharedElementWithCallerManagedVisibility` nutzen.
- **Limitierungen:** kein View↔Compose-Interop (auch nicht in `Dialog`/`ModalBottomSheet`!), `ContentScale` wird nicht animiert, **kein eingebautes Shape-Morphing während der Transition** (Workaround: `sharedBounds()` + `animateEnterExit`).
- Klassischer Anwendungsfall: Artwork fliegt von Listen-Zelle in den Full-Player — genau das Mini-Player→Player-Muster.

### 6.3 Haptik (offizielle Android-Prinzipien)

(https://developer.android.com/develop/ui/views/haptics/haptics-principles; Übersicht: https://developer.android.com/develop/ui/views/haptics)

- Drei Einsatzfälle: **Benachrichtigen**, **Zustandswechsel bestätigen** (Button-Press), **Delight** (z. B. „Slider, der einrastet", Scroll-Feedback).
- Kategorien: **Clear** (präzise, `HapticFeedbackConstants`), **Rich** (Komposition aus Primitives, breiterer Aktuator nötig — **Fallback-Strategie definieren!**), **Buzzy** (vermeiden, nur aufmerksamkeitskritische Events).
- Konkrete Media-Relevanz: **Crescendo-Effekte** (Amplitude steigt, je näher der Drag einem Snap-Ziel kommt), **sehr subtile Haptik bei hochfrequenten Interaktionen** (Scrollen, Marker-Drag), Haptik mit Audio/Visual **synchron** designen — unsynchronisierte Haptik wirkt wie ein Defekt.
- Legacy-APIs (`Vibrator.vibrate(long)`, `createOneShot`) vermeiden; korrekte Key-Press-Haptik dauert 10–20 ms (https://developer.android.com/develop/ui/views/haptics/haptics-principles).

### 6.4 Waveform-/Progress-Visualisierungen & Marquee

- **WavyProgressIndicator** (M3 Expressive) als „lebendige" Fortschrittsanzeige, determinate und indeterminate, Wellenlänge/Amplitude konfigurierbar (https://developer.android.com/jetpack/androidx/releases/compose-material3).
- Lauftext für lange Titel: **`Modifier.basicMarquee()`** (offiziell in `androidx.compose.foundation`; scrollt nur, wenn Inhalt zu breit) (https://developer.android.com/develop/ui/compose/text/style-text).
- Spezielle „Waveform-Editor-UX"-Richtlinien von Google/Apple existieren nicht; mobile Audio-Editoren (Hokusai/Ferrite) und Figma-/Dribbble-Referenzen sind die üblichen Anhalts­punkte *(Sekundärquellen: https://www.figma.com/community/file/1558787161255194657/wave-player-modern-ui-ux-design, https://dribbble.com/search/audio-waveform)*. Ableitbare Konventionen (48-dp-Griffe, Pinch-Zoom, Snap-Haptik) siehe Abschnitt 8 (Touch-Targets) und 6.3.

### 6.5 Artwork-Farben (Palette)

- Offizielle **Palette-API** (`androidx.palette`) extrahiert dominante/vibrierende Farben aus Bitmaps (https://developer.android.com/develop/ui/views/graphics/palette-colors).
- Compose-Portierung/Wrapper: **kmpalette** (Compose Multiplatform, generiert Material-Schemes aus Bildern) (https://github.com/jordond/kmpalette); Anleitung Palette→Compose-Colors: *(Sekundärquelle: https://medium.com/tech-takeaways/dynamically-match-jetpack-compose-ui-colors-automatically-to-an-image-c945894aa496)*.

---

## 7. Dark Mode / Theming für Musik-Apps

- **Energie:** Pure Blacks (#000000) erlauben auf OLED komplettes Abschalten der Pixel — NN/g misst im Schnitt **~67 % Energieeinsparung** bei hoher Helligkeit; dunkle Grautöne sparen ebenfalls erheblich (https://www.nngroup.com/articles/dark-mode-users-issues/;*XDA-Einordnung:* https://www.xda-developers.com/amoled-black-vs-gray-dark-mode/).
- **Material-Konvention:** dunkles Grau (**#121212**) statt Rein-Schwarz als Standard-Dark-Theme; vermeidet Ermüdung/Halation (https://atmos.style/blog/dark-mode-ui-best-practices — zitiert Googles Dark-Theme-Empfehlung; *Sekundärquelle*).
- **„Black smearing":** Bei Animationen über rein schwarzen Flächen kann OLED-Ghosting auftreten — dunkles Grau bevorzugen, wenn viel animiert wird (z. B. Artwork-Übergänge, Waveform-Animation) (https://ux.stackexchange.com/questions/140958/can-i-avoid-using-complete-pure-black-background-color-for-dark-mode).
- **Nachfrage nach AMOLED-Black:** User fordern bei Spotify und Deezer unverändert echte AMOLED-Themes (Community-Belege: https://www.reddit.com/r/truespotify/comments/1m6d245/why_doesnt_spotify_give_us_amoled_black_or_even_a/, https://en.deezercommunity.com/ideas/pure-black-black-amoled-from-dark-mode-59586) — Beleg, dass ein **Zwei-Wege-Angebot** (Dark Gray + AMOLED-Option) der beste Kompromiss ist.
- **Dynamic Color:** Android-System-Dynamic-Color (ab Android 12) ist der Plattform-Standard (https://developer.android.com/develop/ui/compose/designsystems/material3); für Musik-Apps ist zusätzlich das **artwork-adaptive Theme** (Palette, Abschnitt 6.5) Branchen-Usus — Media3s neue Compose-Komponenten unterstützen Material3 Dynamic Color explizit (https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html).

---

## 8. Accessibility für Medien-Apps

- **Touch-Targets: mindestes 48×48 dp** für jede interaktive Fläche — offizielle Android-Empfehlung; größer ist besser (https://developer.android.com/guide/topics/ui/accessibility/apps). Google Accessibility Help: 48 dp ≈ **9 mm physisch**, empfohlen 7–10 mm (https://support.google.com/accessibility/android/answer/7101858). Compose: Minimum-Größen von 48 dp einhalten (https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- **Screen-Reader:** Jede Steuerfläche braucht eine aussagekräftige `contentDescription` (z. B. „Play", „Pause", „Titel X abspielen") — allgemeine Anforderung der Accessibility-Principles-Seiten (https://developer.android.com/guide/topics/ui/accessibility/apps).
- **Reduced Motion:** Keine Android-spezifische Media-Primärrichtlinie in dieser Recherche verifiziert; generell empfohlen (und von M3-Expressive-Springs betroffen): Animationen dezent halten und vom System-Setting „Animationen entfernen/dauerhaft skalieren" respektieren *(einschränkung: nur generische Principles-Seite verifiziert, https://developer.android.com/guide/topics/ui/accessibility/principles)*.
- Für Lyrics, Waveform etc. gelten dieselben Regeln: Statusänderungen (Play/Pause, Marker-Position) müssen für TalkBack ankündbar sein (Semantik/Zustände) (https://developer.android.com/guide/topics/ui/accessibility/apps).

---

## 9. Fitness-Musik-Hybrid-UX

- **Spotify Running Mode (Launch 30.07.2026)** — der wichtigste Branchen-Beleg für DropSyncs Kernidee:
  - Verwandelt Playlists in **geführte Lauf-Sessions**, „maßgeschneidert an Ziele, Musikgeschmack und Tempo"; **beats werden an das gewählte Tempo (BPM) angepasst**, Tracks gehen nahtlos ineinander über (https://newsroom.spotify.com/2026-07-30/running-mode-playlist/).
  - **25 kuratierte Presets**, konfigurierbar nach **Workout-Typ (Intervall, steady, Pyramide), Dauer, Ziel-BPM, Genre/Stimmung** (https://newsroom.spotify.com/2026-07-30/running-mode-playlist/).
  - Ort: **Fitness-Hub** in der Spotify-App; optionale **Coaching-Audio-Cues** („wie ein Freund, der alongside läuft, ohne der Musik im Weg zu sein"); Start: iOS, Premium, ausgewählte Märkte (https://newsroom.spotify.com/2026-07-30/running-mode-playlist/; https://www.theverge.com/entertainment/973002/spotify-running-mode-launch-bpm-playlists).
  - **Wichtig für DropSync:** Laut Spotify-Newsroom passiert das BPM-Matching über **Track-Auswahl + nahtlose Übergänge**, nicht über hörbares Tempo-Strecken; Hands-on-Berichte erwähnen eine zusätzliche Option „Song-Geschwindigkeit an BPM anpassen" *(Sekundärquelle, nicht primär verifiziert: https://lifehacker.com/health/spotify-new-running-mode-impressions)* — DropSyncs Tempo-Strecken (Pitch-Korrektur per `PlaybackParameters`) ist damit ein Differenzierungsmerkmal.
- **Glanceability (Wear OS):** eine Aufgabe pro Screen, nicht-scrollende Templates für Media, Media-Controls-Kachel mit Fortschritt (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
- **Lockscreen/Headset:** System-Media-Controls via `MediaSessionService` sind die primäre „Bedenienung ohne Entsperren"-Fläche (https://developer.android.com/media/media3/session/background-playback; Android-11-Resume-Verhalten: https://developer.android.com/about/versions/11/custom-media-controls).
- Fitness-App-UX-Prinzipien (große Ziele, minimale Ablenkung, Musik-Integration als Kernelement) finden sich in Branchenblogs *(Sekundärquellen: https://stormotion.io/blog/fitness-app-ux/, https://axicube.io/blog/how-to-create-a-superb-fitness-app-design)* — plausibel, aber nicht empirisch belegt.

---

## 10. Trends 2026 (AI, Social, Spatial/Lossless, Large Screens, Wear, Auto)

- **AI-Personalisierung:** Running Mode (s. o.), Spotify DJ mit Sprach-Requests („tap and hold the DJ button") (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/), Discover-Weekly-Refresh nach Genre (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).
- **Social/„Listening together":** Spotify **Jam** — gemeinsame, synchrone Sessions mit geteilter Queue; Start erfordert Premium, Beitreten kostenlos (https://support.spotify.com/us/article/jam/). Januar 2026: **„Listening Activity"** (opt-in, zeigt in Messages live, was man hört) und **„Request to Jam"** direkt aus Messages (https://newsroom.spotify.com/2026-01-07/listening-activity-request-to-jam-messages-updates/).
- **Spatial/Lossless-Indikatoren:** Apple zeigt Lossless/Hi-Res/Dolby-Atmos-Badges in Album-/Track-/Now-Playing-Ansichten; bekanntes UX-Problem: **inkonsistente Anzeige** (Settings-abhängig, teils fehlend) (https://support.distrokid.com/hc/en-us/articles/4408827366675-Audio-Badges-in-Apple-Music-and-How-To-Get-Them; https://discussions.apple.com/thread/255116568). Für DropSync: Formate/Bitraten **konsistent** und nur bei Relevanz anzeigen.
- **Large Screens/Foldables:** offizielle kanonische M3-Adaptive-Layouts — **list-detail, supporting pane, navigation** für compact/medium/expanded; in Compose `NavigableListDetailPaneScaffold` (https://developer.android.com/develop/adaptive-apps/guides/list-detail; https://codelabs.developers.google.com/jetpack-compose-adaptability; stabil seit Sept 2024: https://android-developers.googleblog.com/2024/09/jetpack-compose-apis-for-building-adaptive-layouts-material-guidance-now-stable.html); **WideNavigationRail** als M3-Expressive-Komponente für expanded (https://developer.android.com/jetpack/androidx/releases/compose-material3).
- **Wear OS:** M3 Expressive für Wear OS mit Media-Controls-Kachel, Glanceability-Templates (s. Abschnitt 1.3/9).
- **Android Auto:** offizielle Auto-Media-Guidelines: Browse-Tree (`MediaLibraryService`), Content-Style-Hints (Liste/Grid), Ablenkungs-Optimierung (`DISTRACTION_OPTIMIZED`), Voice-Actions (https://developer.android.com/training/cars/media).
- **Design-Trends 2026** (alles *Sekundärquellen*): Liquid-Glass-/„adaptive Transparenz"-Welle (https://www.orizon.co/blog/10-ui-ux-trends-that-will-shape-2026), „Calm UI"/geringere kognitive Last (https://elements.envato.com/learn/ux-ui-design-trends), funktionale Muster statt Deko (https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/), multimodal/AI-getrieben (https://uxdesign.cc/the-most-popular-experience-design-trends-of-2026-3ca85c8a3e3d).

---

## 11. Konkrete Empfehlungen für DropSync

> Android, Kotlin, Jetpack Compose, Material 3; lokale Wiedergabe, DSP (EQ/Tempo/Crossfade), Waveform+Marker, Workout/HR-Kopplung.

### A) Design-System-Basis

1. **Auf `MaterialExpressiveTheme` migrieren** (compose-material3 1.4.x stabil, 1.5.0-alpha für expressive Einzelteile) mit `MotionScheme.expressive()` — Spring-Physik überall statt mancher Tween-Animationen (https://developer.android.com/jetpack/androidx/releases/compose-material3).
2. **Shape-Morphing für Play/Pause:** Play→Pause als sichtbarer Shape-/Größenwechsel (M3 macht das ab Werk für Buttons) — erhöht den State-Kontrast, gerade auf dem Workout-Screen aus der Ferne ablesbar (Vorbild Wear OS: „visual contrast between play and pause", https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).
3. **Temporäre/sekundäre Aktionen als SplitButton** (z. B. „Workout starten" + Tempo-Presets), **FloatingToolbar** für kontextuelle DSP-Aktionen (https://developer.android.com/jetpack/androidx/releases/compose-material3).
4. **Bibliotheksversionen:** `media3` 1.11.0, `compose-material3` 1.4.x (stabil) bzw. gezielt 1.5.0-alpha für `FlexibleBottomSheet`/`SplitButton` (https://developer.android.com/jetpack/androidx/releases/media3, https://developer.android.com/jetpack/androidx/releases/compose-material3).

### B) Now-Playing-Screen

5. **Layout nach YTM-2025-Muster:** großes Artwork oben, Titel/Interpret direkt darunter, **Haupt-Controls früh (obere Bildschirmhälfte)** für Einhandbedienung, darunter horizontales **Aktions-Carousel** (EQ, Tempo, Marker, Lyrics/Info, Teilen), Progress-Scruber über den Controls (https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/).
6. **Scrubber:** `ProgressSlider` aus `media3-ui-compose-material3` (Seek per Drag **und** Tap) oder eigener M3-Slider; **bei Interaktion dicker werden lassen** (YTM-Pattern); Zeitlabels beidseitig; bei aktivem Tempo ≠ 1.0 **Restzeit tempo-korrigiert** anzeigen (https://developer.android.com/media/media3/ui/compose, https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/).
7. **Player als Bottom-Sheet / Swipe-down zum Schließen**; **Shared-Element-Transition Artwork aus Listen-Zelle in den Player** via `SharedTransitionLayout` + `sharedElement(key)`; Keys als data classes; nicht in `ModalBottomSheet` erwarten (kein Interop!) — für Sheets `sharedBounds` + eigene Sheet-Implementation nutzen (https://developer.android.com/develop/ui/compose/animation/shared-elements).
8. **Artwork-adaptives Theme:** Palette-API/kmpalette → dominante/vibrierende Farbe → abgedunkelte Surface-Farben im Player; Text-Kontrast prüfen; System-Dynamic-Color als Option daneben (https://developer.android.com/develop/ui/views/graphics/palette-colors, https://github.com/jordond/kmpalette).
9. **Transluzente Controls über/vor Artwork** (Liquid-Glass-Lehre: Controls unverdeckt über Inhalt, Farbe aus dem Inhalt) — in Compose mit `Modifier.blur`/GraphicsLayer + abgeleiteter Tönung; sparsam einsetzen (Performanz, Lesbarkeit) (https://developer.apple.com/design/human-interface-guidelines/materials).
10. **Crossfade/Mix sichtbar machen:** AutoMix (Apple) macht Übergänge zum Erstklassigen-Feature — DropSync sollte einen **Mix/AutoMix-Status-Chip** im Player zeigen (an/aus, Übergangsdauer) und den **nächsten Übergang im Scrubber/Waveform als Overlay** markieren (https://artists.apple.com/support/5550-ios-26-whats-new).
11. **Sleep Timer:** Sheet mit 5–60 min, „Ende des Titels", „Ende der Queue" (Spotify-Pattern; https://www.androidpolice.com/spotify-tricks-worth-knowing/).

### C) Mini-Player & Queue

12. **Mini-Player:** offiziellen `MiniController` (media3 1.11, M3 Dynamic Color) als Basis nehmen und dropSync-spezifisch erweitern (BPM/Tempo-Badge, Waveform-Mini-Anzeige); Tap öffnet Player per Shared-Element (https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html, https://developer.android.com/media/media3/ui/compose).
13. **Queue als hochziehbares Sheet** (Drag-Handle, Kompaktansicht ~4 Tracks, ausklappbar) statt separatem Tab; **Reihenfolge per Drag** mit Calvin-LL/Reorderable; Swipe-to-remove mit `SwipeToDismissBox`; am Queue-Ende **lokale Vorschläge** (ähnliche BPM/Genre aus eigener Bibliothek) behalten/verwerfen (https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/, https://github.com/Calvin-LL/Reorderable, https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).

### D) Workout-Screen (Fitness-Hybrid)

14. **Glanceability:** eine Hauptinformation pro Blick — große HR/BPM/Pace-Anzeige, Media-Controls kompakt aber ≥48 dp (besser 56–64 dp) erreichbar im Daumenbereich unten; „eine Aufgabe pro Screen" als Wear-Prinzip auch auf dem Phone anwenden (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html, https://developer.android.com/guide/topics/ui/accessibility/apps).
15. **BPM-Kopplung als Party-Leistung präsentieren:** Presets nach **Workout-Typ (Intervall/steady/Pyramide), Ziel-BPM, Genre, Dauer** — exakt das Spotify-Running-Mode-Konfigurationsschema; DropSync kann hier **echtes Tempo-Strecken mit Pitch-Korrektur** (`PlaybackParameters`, https://developer.android.com/reference/kotlin/androidx/media3/common/PlaybackParameters) + Crossfade anbieten, was Spotify (laut Primärquelle) nur über Track-Auswahl löst (https://newsroom.spotify.com/2026-07-30/running-mode-playlist/).
16. **Optionale Coaching-Audio-Cues** (Interval-Ansagen über den Mix, ohne Musik zu dominieren) — Vorbild Spotify Running (https://newsroom.spotify.com/2026-07-30/running-mode-playlist/).
17. **Safe speed range:** Tempo-Regler auf ~0.5–2.0x begrenzen (Geräte-Exceptions bei Extremwerten, https://github.com/androidx/media/issues/1101); Presets ±0.05–0.1x Schritte; aktueller Faktor als **Chip/Badge** überall sichtbar (Mini-Player, Notification, Watch).

### E) Waveform & Marker

18. **Renderer:** Waveform als downgesampelte Min/Max-Peaks, **einmal berechnen und cachen** (je Track), zeichnen in `Canvas`/`drawBehind` mit GraphicsLayer; kein Neu-Recomposen pro Frame; Progressive-Detail beim Zoomen (Peak-Levels mehrfach auflösen). *(Ableitung aus Plattform-Performance-Praxis; keine offizielle Waveform-Richtlinie existiert — Canvas-/DrawScope-Basis: https://developer.android.com/develop/ui/compose/graphics/draw/overview.)*
19. **Marker-Touch-Targets:** Griffe optisch klein, **interaktiv ≥48 dp** (unsichtbare Treiberfläche um den Marker) (https://developer.android.com/guide/topics/ui/accessibility/apps); **Snap-Haptik mit Crescendo** (Amplitude steigt beim Annähern an Beat-/Grid-Ziele) und **sehr subtile Haptik** beim Dauer-Drag (https://developer.android.com/develop/ui/views/haptics/haptics-principles).
20. **Gesten:** Pinch = Zoom (sekunden→samples), Drag = Scrub, Long-Press + Drag = feines Positionieren (Slow-Drag-Multiplikator), Double-Tap = Marker setzen/entfernen; Marker-Labels mit `basicMarquee()` (https://developer.android.com/develop/ui/compose/text/style-text).
21. **TalkBack für Marker:** Marker als eigene Semantik-Knoten mit Position („Marker 2 bei 1:23") ankündigen (https://developer.android.com/guide/topics/ui/accessibility/apps).

### F) EQ & DSP-UI

22. **EQ-Slider vertikal** nutzen (M3 Expressive Slider: vertikale Orientierung, 5 Größen, Centered-Variante für ±dB ideal) (https://m3.material.io/components/sliders/overview); **Preset-Chips + Reset + A/B-Bypass**; Snap bei 0 dB mit Haptik (https://developer.android.com/develop/ui/views/haptics/haptics-principles).
23. **Tempo-Control** als `PlaybackSpeedControl`/`PlaybackSpeedToggleButton` (media3) oder eigener M3-Chip mit Long-Press-Feinjustierung (Vorbild: Media3 `PlaybackSpeedState` Long-Press-API) (https://developer.android.com/jetpack/androidx/releases/media3).

### G) Bibliothek & Suche

24. **Filter-Chips nach Aktivität/BPM/Genre/Stimmung** (Spotify Smart Filters als Muster; bei DropSync: **BPM-Bereiche** als Killer-Filter für Workout-Playlists) (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/).
25. **Grid für Alben/Playlists, Liste für Tracks** (Auto-Content-Style-Konvention als Beleg: https://developer.android.com/training/cars/media); **Suchleiste nach neuen M3-1.4-SearchBar-APIs**, Suche + Filter kombinierbar (https://developer.android.com/jetpack/androidx/releases/compose-material3).
26. **Playlist-Editor:** Multi-Select (Long-Press) + Batch-Aktionen (zur Queue, BPM-Filter anwenden), Drag-Sortieren, „in dieser Playlist ausblenden" statt hartem Löschen (Spotify „Hide in this playlist") (https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/, https://github.com/Calvin-LL/Reorderable).

### H) Dark Mode & Theming

27. **Drei Stufen anbieten:** Dark (Standard, dunkles Grau ~#121212), **AMOLED-Black (Option)**, Light; Dark als Default für eine Musik-/Fitness-App (Umgebung: Studio, Abend, Outdoor) (https://www.nngroup.com/articles/dark-mode-users-issues/, https://atmos.style/blog/dark-mode-ui-best-practices, https://www.reddit.com/r/truespotify/comments/1m6d245/).
28. **AMOLED-Modus animierungsarm halten** (Black-Smearing-Gefahr bei Artwork-Fades/Waveform) oder Fades in Grau ausführen (https://ux.stackexchange.com/questions/140958/can-i-avoid-using-complete-pure-black-background-color-for-dark-mode).

### I) Motion & Feedback

29. **Springs als Default** (`MotionScheme.expressive()`), Shape-Morphing für zentrale States (Play/Pause, Record/Pause, Workout aktiv) (https://developer.android.com/jetpack/androidx/releases/compose-material3).
30. **WavyProgressIndicator** für „lebendige“, dekorative Fortschritte (z. B. Workout-Intervall-Fortschritt, laufender Mix); **LoadingIndicator** statt altem Spinner (https://developer.android.com/jetpack/androidx/releases/compose-material3).
31. **Haptik-Set:** Play/Pause = klarer Click-Confirm; Scrub-Snap & Marker-Snap = Crescendo; Track-Wechsel im Workout = dezenter Confirm; Drag-Texturen (Waveform) nur wenn breiter Aktuator + Fallback (https://developer.android.com/develop/ui/views/haptics/haptics-principles).
32. **Live Updates:** Fortschritt in der Media-Notification wird unter Android 16 automatisch als Live-Update-ProgressBar animiert — MediaStyle-Notification korrekt mit Fortschritt versorgen (https://developer.android.com/about/versions/16/behavior-changes-16).

### J) Systemflächen & Verteilung

33. **`MediaSessionService` + `setMediaButtonPreferences`**: max. 3 sichtbare Aktionen priorisieren (z. B. Vorheriger, Play/Pause, Nächster; bei aktivem Workout ggf. „Intervall überspringen"); seit Media3 1.9.0 mit Standard-Player-Commands; Android-16-Regressionen (1.6.0) beachten → aktuelle media3-Version verwenden (https://developer.android.com/media/media3/session/control-playback, https://developer.android.com/about/versions/13/behavior-changes-13, https://github.com/androidx/media/issues/2292).
34. **`setSessionActivity`** setzen, damit Resume aus den persistenten System-Controls die Position erhält (https://developer.android.com/about/versions/11/custom-media-controls).
35. **Large Screens/Foldables:** `NavigableListDetailPaneScaffold` (Bibliothek ↔ Detail/Player) und `WideNavigationRail` ab expanded; auf Foldables Player auf der halboffenen rechten Hälfte (https://developer.android.com/develop/adaptive-apps/guides/list-detail, https://developer.android.com/jetpack/androidx/releases/compose-material3).
36. **Later: Wear-OS-Kompanion** mit M3-Expressive-Wear-Komponenten (edge-hugging Buttons, Media-Template mit Fortschritt) (https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html).

### K) Quick Wins (Priorisierung)

1. Media3 auf 1.11.0 heben und `MiniController`/`ProgressSlider`/`PlaybackSpeedControl` übernehmen.
2. `MaterialExpressiveTheme` + `motionScheme` + Play/Pause-Shape-Morph.
3. Sleep-Timer-Sheet (5–60 min / Ende Titels / Ende Queue).
4. Queue-Sheet mit Reorder + Swipe-Remove.
5. AMOLED-Black-Theme-Option.
6. BPM-Filter-Chips in der Bibliothek.
7. MediaButtonPreferences für Lockscreen/Quick Settings sauber priorisieren.

---

## 12. Quellen

**Google / Material / Android (primär):**
- https://m3.material.io/
- https://m3.material.io/blog/building-with-m3-expressive
- https://m3.material.io/components/sliders/overview
- https://m3.material.io/components/sliders/specs
- https://m3.material.io/blog/material-is-compose-first
- https://design.google/library/expressive-material-design-google-research
- https://blog.google/products-and-platforms/platforms/android/material-3-expressive-android-wearos-launch/
- https://android-developers.googleblog.com/2025/08/introducing-material-3-expressive-for-wear-os.html
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/media/media3
- https://developer.android.com/media/media3/ui/compose
- https://developer.android.com/media/media3/session/background-playback
- https://developer.android.com/media/media3/session/control-playback
- https://developer.android.com/media/implement/surfaces/mobile
- https://developer.android.com/media/guides
- https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html
- https://android-developers.googleblog.com/2026/08/media3-1-11-whats-new.html
- https://developer.android.com/reference/kotlin/androidx/media3/common/PlaybackParameters
- https://medium.com/google-exoplayer/variable-speed-playback-with-exoplayer-e6e6a71e0343
- https://developer.android.com/about/versions/13/behavior-changes-13
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/11/custom-media-controls
- https://android-developers.googleblog.com/2020/08/playing-nicely-with-media-controls.html
- https://developer.android.com/training/cars/media
- https://developer.android.com/develop/ui/compose/animation/shared-elements
- https://developer.android.com/develop/ui/compose/graphics/draw/overview
- https://developer.android.com/develop/ui/views/haptics
- https://developer.android.com/develop/ui/views/haptics/haptics-principles
- https://developer.android.com/develop/ui/views/graphics/palette-colors
- https://developer.android.com/develop/ui/compose/text/style-text
- https://developer.android.com/guide/topics/ui/accessibility/apps
- https://developer.android.com/guide/topics/ui/accessibility/principles
- https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- https://support.google.com/accessibility/android/answer/7101858
- https://developer.android.com/develop/adaptive-apps/guides/list-detail
- https://codelabs.developers.google.com/jetpack-compose-adaptability
- https://android-developers.googleblog.com/2024/09/jetpack-compose-apis-for-building-adaptive-layouts-material-guidance-now-stable.html
- https://github.com/androidx/media/issues/1101
- https://github.com/androidx/media/issues/2292
- https://github.com/androidx/media/releases

**Apple (primär):**
- https://developer.apple.com/documentation/technologyoverviews/liquid-glass
- https://developer.apple.com/design/human-interface-guidelines/materials
- https://www.apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/
- https://developer.apple.com/videos/play/wwdc2025/219/
- https://developer.apple.com/videos/play/wwdc2025/356/
- https://artists.apple.com/support/5550-ios-26-whats-new

**Spotify (primär):**
- https://newsroom.spotify.com/2025-09-05/new-user-controls-personalize-listening/
- https://newsroom.spotify.com/2026-07-30/running-mode-playlist/
- https://newsroom.spotify.com/2026-01-07/listening-activity-request-to-jam-messages-updates/
- https://support.spotify.com/us/article/jam/

**Redesign-Analysen (Sekundär, aber etablierte Tech-Presse):**
- https://9to5google.com/2025/09/11/youtube-now-playing-2025-redesign/
- https://techcrunch.com/2025/09/08/spotifys-new-smart-filters-let-you-screen-library-content-by-activity-genre-or-mood/
- https://www.theverge.com/entertainment/973002/spotify-running-mode-launch-bpm-playlists
- https://lifehacker.com/health/spotify-new-running-mode-impressions
- https://www.androidpolice.com/spotify-tricks-worth-knowing/
- https://www.androidheadlines.com/2025/04/android-16-beta-transforms-the-media-output-switcher-ui.html
- https://9to5google.com/2025/11/17/google-material-3-expressive-redesign/

**UX-/Design-Quellen (Sekundär):**
- https://www.nngroup.com/articles/dark-mode-users-issues/
- https://www.xda-developers.com/amoled-black-vs-gray-dark-mode/
- https://atmos.style/blog/dark-mode-ui-best-practices
- https://ux.stackexchange.com/questions/140958/can-i-avoid-using-complete-pure-black-background-color-for-dark-mode
- https://www.reddit.com/r/truespotify/comments/1m6d245/why_doesnt_spotify_give_us_amoled_black_or_even_a/
- https://en.deezercommunity.com/ideas/pure-black-black-amoled-from-dark-mode-59586
- https://github.com/Calvin-LL/Reorderable
- https://stackoverflow.com/questions/64913067/reorder-lazycolumn-items-with-drag-drop
- https://github.com/jordond/kmpalette
- https://medium.com/tech-takeaways/dynamically-match-jetpack-compose-ui-colors-automatically-to-an-image-c945894aa496
- https://support.distrokid.com/hc/en-us/articles/4408827366675-Audio-Badges-in-Apple-Music-and-How-To-Get-Them
- https://discussions.apple.com/thread/255116568
- https://www.reddit.com/r/YoutubeMusic/comments/1nep8eq/new_ui_really_personally_this_is_worse/
- https://www.reddit.com/r/apple/comments/1njqlo5/ios_26s_liquid_glass_design_draws_criticism_from/
- https://stormotion.io/blog/fitness-app-ux/
- https://axicube.io/blog/how-to-create-a-superb-fitness-app-design/
- https://www.orizon.co/blog/10-ui-ux-trends-that-will-shape-2026
- https://elements.envato.com/learn/ux-ui-design-trends
- https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/
- https://uxdesign.cc/the-most-popular-experience-design-trends-of-2026-3ca85c8a3e3d
- https://supercharge.design/blog/material-3-expressive
- https://dribbble.com/search/audio-waveform
- https://www.figma.com/community/file/1558787161255194657/wave-player-modern-ui-ux-design

---

*Hinweis: Aussagen ohne Primärquellen-Beleg sind im Text gekennzeichnet. Für Implementierungsdetails (API-Beispiele) gelten die verlinkten Release Notes/Dokus als maßgeblich — insbesondere die schnell rotierenden compose-material3-1.5.x- und media3-Versionen.*
