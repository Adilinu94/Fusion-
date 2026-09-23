# ADR-0012: Drop-Landung — Work-Titel-Vorlauf trifft das Pausenende

Datum: 2026-07-27
Status: Akzeptiert

## Problem

Die Musik-Workout-Kopplung soll die Trainingspause aktiv gestalten und —
optional — die "Drop-Landung" liefern ("DropSync rueckwaerts"): kurz vor
dem Ende des Pausen-Countdowns startet vorgezogen ein Titel aus dem
"Work"-Label, dessen Drop exakt mit dem Pausenende zusammenfaellt. Offen
war, wo die Berechnung lebt, wie die Kopplung ohne Feature-Import
funktioniert, wie der eigentliche Titelwechsel klingt und wie ehrlich die
Praezision benannt wird.

## Optionen

1. Logik im Feature/ViewModel — vermischt Zeitrechnung mit Android und ist
   schwer testbar.
2. Reine Domainfunktion in `:domain:timer` neben dem bestehenden `DropRest`
   (dort leben bereits `MarkerPoint`/`PlaybackSample`); ein Coordinator im
   `:feature:player` verdrahtet sie mit Timer und Playback.

## Entscheidung

Option 2.

- **Berechnung als reine Domainfunktion.** `DropLandingPlanner.plan` in
  `:domain:timer` (`DropLanding.kt`) rechnet ohne Android-, Room- oder
  Media3-Typen und ist voll unit-getestet. Gegeben Restzeit R und
  Drop-Position D: bei `D >= R` sofort starten und auf `D - R` vorspulen,
  bei `D < R` erst nach `R - D` warten und von vorn starten; unter
  `MIN_REST_MS` oder ohne Work-Drop gibt es `NotPossible`.
- **Kopplung ueber Beobachtung der einen `TimerEngine`.** Der
  `RestMusicCoordinator` (`:feature:player`, das einzige Feature, das
  Playback und Marker kennen darf) beobachtet `TimerEngine.state`
  (Modus REST + Restzeit) und `RestMusicSettingsRepository`. Kein Feature
  importiert ein anderes; die Kopplung laeuft nur ueber Domainvertraege
  (Muster: `DropRestRequestBus`).
- **Ein Verhaltensschalter.** `RestMusicBehavior { NORMAL, REST_PLAYLIST,
  DROP_LANDING }`; `NORMAL` (Default) greift nie ein — Queue/Shuffle laeuft
  unveraendert weiter.
- **Crossfade dienstseitig.** Ein echter Crossfade auf einen beliebig
  vorgespulten Titel braucht zwei ueberlagerte Quellen; die kann nur der
  `PlaybackService` erzeugen (ADR-0007). Der Coordinator loest den Wechsel
  ueber das Domainkommando `PlaybackRepository.crossfadeTo(song, position)`
  aus; die Implementierung sendet ein MediaSession-Custom-Kommando
  (`PlaybackCommands.ACTION_CROSSFADE_TO`) an den Service, der den
  vorhandenen `CrossfadeController` mit einer Startposition erweitert nutzt.
  Ist der Crossfade aus oder pausiert, erfolgt ein harter, aber vorgespulter
  Uebergang auf dem einen sessionfuehrenden Player.

## Folgen

- **Praezision ehrlich benannt.** Grundlage sind die monotone Startzeit der
  `TimerSession` (`elapsedRealtime`) und die Song-Drop-Position; realistisch
  sind ca. +/-100-200 ms (Audio-Puffer/Decoder-Latenz), kein sample-genaues
  Ausrichten.
- **Nutzer hat Vorrang.** Ein manueller Eingriff (pausierte Wiedergabe) und
  ein pausierter/abgebrochener Rest-Timer brechen die geplante Landung ab —
  analog `DropRestMonitor`.
- **Fallback-Kette.** Keine "Rest/Pause"-Playlist -> `NORMAL`-Verhalten
  (Musik laeuft weiter); `DROP_LANDING` ohne brauchbaren Work-Drop faellt
  auf `REST_PLAYLIST` zurueck (Work-Titel erst am Pausenende).
- **Queue-Modell.** Waehrend aktiver Automatik uebernimmt die App die Queue
  (Rest-Musik <-> Work-Musik); die vorherige Queue wird nicht
  wiederhergestellt. Bei `NORMAL` bleibt alles unveraendert.

## Nachtrag 2026-09-19

Drei Punkte stimmen nicht mehr mit dem Code ueberein bzw. wurden
praezisiert (Befund DOC-2 und Entscheidungen vom 19.09.2026):

1. **Crossfade-Pfad.** Dieser ADR beschreibt
   `PlaybackRepository.crossfadeTo` und `ACTION_CROSSFADE_TO`. Nach der
   ADR-Konsolidierung (README Schritt 18) existiert kein
   `CrossfadeController` mehr; der Code wechselt hart ueber
   `playSongAt`/`ACTION_PLAY_SONG_AT` (`PlaybackRepositoryImpl.kt:136`,
   `RestMusicCoordinator.kt:191`). **ADR-0022** beschreibt, wie der
   ueberlappende Crossfade stufenweise zurueckkommt (Stufe 1: Kurve um den
   harten Wechsel; Stufe 2: Dual-Player mit vorgeschaltetem Spike).
2. **Fallback-Kette.** "Keine 'Rest/Pause'-Playlist -> NORMAL-Verhalten"
   ist durch die Nutzerentscheidung vom 19.09.2026 (Entscheidung 7)
   bestaetigt. Die abweichende Formulierung im Fusionsdesign 7.1
   (Fallback 3: "nur Ducking ohne Queue-Wechsel") ist dort am 21.09.2026
   nachgezogen (A9): die Musik laeuft unveraendert weiter, kein
   Queue-Wechsel, kein Ducking.
3. **Vorrang des Nutzers.** Der ADR verlangt, dass ein manueller Eingriff
   **und** ein pausierter/abgebrochener Rest die Landung abbrechen. Der
   Code prueft heute nur `isPlaying` im Moment der Landung; Skip, Seek und
   Queue-Wechsel waehrend des Plans brechen ihn nicht ab. Das ist als
   **MP-5** im Verbesserungsplan erfasst und wird im DropSync-Koordinator
   (P1) behoben.
