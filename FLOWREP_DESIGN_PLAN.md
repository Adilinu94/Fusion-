# FlowRep – Design & Rebrand: Umsetzungsplan & Status

Umsetzung des Style-Guides `Design.txt` als vollständiges Designsystem plus Rebrand von "DropSync" auf **FlowRep**. Markenpalette (Schwarz `#0D0D0D` / Weiß `#FFFFFF` / Lime `#DFFF2F` / Grau) und Radien sind bereits in [Theme.kt](core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/theme/Theme.kt) umgesetzt; ergänzt werden Raleway-Typografie, Spacing-Tokens, Marken-Komponenten und ein Screen-Redesign.

Grundsätze: offline (Raleway als gebündelte OFL-TTF, kein Netz), keine neuen Fitness-Features, `applicationId`/`namespace` bleiben `com.dropsync.*`, 3-Tab-IA (MUSIC/TRAINING/SETTINGS) bleibt. User-facing Strings mit echten Umlauten; Code/Kommentare ASCII.

## Statustabelle

| Phase | Inhalt | Status |
| --- | --- | --- |
| 1 | FlowRep Design-Mockups (imagegen-frontend-mobile) | Zurückgestellt (Bilddienst 40500, wird nachgeholt) |
| 2 | Design-Tokens + Raleway-Typografie + Spacing (`:core:designsystem`) | Abgeschlossen |
| 3 | Marken-Komponenten (Buttons, BrandCard, ProgressRing) | Abgeschlossen |
| 4 | Rebrand FlowRep (app_name, Launcher-Icon) | Abgeschlossen |
| 5 | Screen-Redesign: Bibliothek (`:feature:library`) | Abgeschlossen |
| 6 | Screen-Redesign: Now-Playing + Mini-Player (`:feature:player`) | Abgeschlossen |
| 7 | Timer + Bottom-Nav + globaler Feinschliff | Abgeschlossen |

## Phase 2 – Design-Tokens + Typografie (Abgeschlossen)

- Neu [Type.kt](core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/theme/Type.kt): `BrandFontFamily` (gekapselter Raleway-Swap-Punkt) + `DropSyncTypography` mit der Skala aus `Design.txt` (Hero/Display eng mit negativem Tracking, Body luftig, Caps-Labels), tabellarische Ziffern für große Zahlen.
- Neu [Spacing.kt](core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/theme/Spacing.kt): `object Spacing` mit 4/8/12/16/24/32/48/64/80/96/120 dp.
- [Theme.kt](core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/theme/Theme.kt): `MaterialTheme(typography = DropSyncTypography, ...)`.
- Raleway als statische OFL-TTF (400/500/600/700/800) in `core/designsystem/src/main/res/font/` gebündelt und in `BrandFontFamily` verdrahtet (offline, kein Provider); OFL-Notice in `THIRD_PARTY_NOTICES.md`.

## Phase 3 – Marken-Komponenten (Abgeschlossen)

- Neu `component/Buttons.kt` (`BrandButtonPrimary/Secondary/Ghost`, Pill 56dp, Bold-Label, Press-Scale), `component/BrandCard.kt` (Radius 24, Hairline-`outline`, weiche Elevation), `component/ProgressRing.kt` (feder-animierter Lime-Ring mit zentralem Slot), `component/CountUpText.kt` (hochzählende Zahl für Peak-End).

## Phase 4 – Rebrand FlowRep (Abgeschlossen)

- `app_name` → „FlowRep"; Launcher-Hintergrund `#0D0D0D`; [ic_launcher_foreground.xml](app/src/main/res/drawable/ic_launcher_foreground.xml) als geometrisches Lime-„F"-Monogramm (dient auch als monochrome Variante). Projekt-/Paketname bleiben `com.dropsync`; README mit Marken-Hinweis ergänzt.

## Phase 5 – Screen-Redesign: Bibliothek (Abgeschlossen)

- [LibraryLists.kt](feature/library/src/main/kotlin/com/dropsync/feature/library/LibraryLists.kt): Songzeilen als FlowRep-Zeilen (gerundete 52dp-Cover-Kachel mit Outline-Glyph, Raleway-Titel, Meta „Interpret | Dauer | Format", Favorit aktiv=Lime, Outline-Overflow); Sammlungszeilen mit gerundeter Icon-Kachel.
- [LibraryContent.kt](feature/library/src/main/kotlin/com/dropsync/feature/library/LibraryContent.kt): durchgehend Outline-Icons (Suche/Clear/Tune/Zurück/Pfeile), farbige Kategorie-Punkte auf den Ansicht-Chips (Wayfinding), Shuffle mit Outline-Icon.
- Hinweis: Album-Art-Laden bleibt ausserhalb des Feature-Moduls (keine Bild-Abhaengigkeit in `:feature:library`); die Cover-Kachel ist der gerundete Platzhalter-Slot.

## Phase 6 – Screen-Redesign: Now-Playing + Mini-Player (Abgeschlossen)

- [NowPlayingScreen.kt](feature/player/src/main/kotlin/com/dropsync/feature/player/NowPlayingScreen.kt): kreisrundes Cover (`CircleShape`), größerer Titel (headlineMedium), Outline-Icons (Zurück/Skip), Cover-Fallback als Outline-Glyph. Die vorhandene Lime-Waveform bleibt primäre Seekbar; der Slider-Fallback bleibt für Barrierefreiheit, wenn keine Waveform vorliegt.
- [MiniPlayer.kt](feature/player/src/main/kotlin/com/dropsync/feature/player/MiniPlayer.kt): dünne Lime-Fortschrittslinie (neue `positionMs`/`durationMs` im `MiniPlayerState`), gerundete Cover-Kachel, Outline-Icons.
- Hinweis: Der grosse Play-Kreis ist bereits die Lime-Primäraktion (`FilledIconButton` = primary). Der Titel bleibt kontraststark (kein Lime-Text), da Lime der Primäraktion vorbehalten ist und auf hellem Grund sonst die Lesbarkeit leidet.

## Phase 7 – Timer, Bottom-Nav, globaler Feinschliff (Abgeschlossen)

- [TimerSection.kt](feature/timer/src/main/kotlin/com/dropsync/feature/timer/TimerSection.kt): Lime-`ProgressRing` (200dp) um die große Restzeit, der sich mit der Restzeit leert; Rest-Presets als Lime-Pills (Start = Primäraktion); Peak-End: voller Lime-Ring mit Haken bei `COMPLETED`. Dafür `material-icons-extended` in `:feature:timer` ergänzt (`ProgressRing` kommt aus dem bereits verdrahteten `:core:designsystem`).
- [DropSyncApp.kt](app/src/main/kotlin/com/dropsync/app/DropSyncApp.kt): Bottom-Nav und Rail mit Outline-Icons; Active-State als Lime-Pill-Indicator (`indicatorColor = primary`, Icon `onPrimary` = Schwarz auf Lime, Kontrast in Light und Dark).
- Globaler Sweep Filled→Outlined: `QueueSheet`, `PlaylistScreens`, `AudioSettingsScreen`, `AudioEqSection` (Delete/Pfeile/Add/MoreVert/PlayArrow/ArrowBack). Bewusst Filled geblieben: Play/Pause-Glyphe im Lime-`FilledIconButton` (Primäraktion) und das aktive Favoriten-Herz (Zustand „aktiv").

## Nachtrag – Eigene Marken-Icons (Abgeschlossen)

- 61 gelieferte FlowRep-Icon-Drawables (Outline, 2px, 24dp-Grid, Laufzeit-Tint) nach `core/designsystem/src/main/res/drawable/` übernommen; zentraler Zugriff über [BrandIcons.kt](core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/icon/BrandIcons.kt).
- Verdrahtet: App-Shell-Navigation (`ic_nav_*`, aktiv = Lime-Pill mit schwarzem Icon), Mini-Player/Now-Playing/Queue (`ic_play/pause/skip_*/queue/delete/back`), Bibliothek (`ic_search/close/filter/back/more/favorite_*`, Kategorie-Kacheln `ic_artists/albums/genres/folder`, Playlists `ic_playlists/playlist_add/add/delete/play`), Audio-Sektionen (`ic_info/audio_dsp/bass_treble/equalizer/stereo_width/reverb/resampler/dvc/swap/bit_perfect/output_device` als 20dp-Titel-Icons), Training (`ic_repeat_session/exercise_library/routines/progress` auf den Einstiegs-Buttons, `ic_rest_dropsync` an der Drop-Rest-Karte, `ic_set_complete` als Peak-End-Haken im Timer).
- Farben: Icons folgen der Content-Farbe (`LocalContentColor`/explizites `onSurfaceVariant`), Lime nur für aktive Zustände (Favorit aktiv, Nav-Indicator, Timer-Haken) — wie in den Icon-Kommentaren vorgesehen.
- Ohne Marken-Ersatz belassen (kein passendes Icon im Satz): einzelne Auf-/Ab-Pfeile (`KeyboardArrowUp/Down`) und `Shuffle`.

## Verifikation je Phase

- `./gradlew spotlessApply` (separat) → betroffene Modul-Tests + `:app:assembleDebug` → `:core:testing:test` (Architektur-Wächter) + `spotlessCheck`.
