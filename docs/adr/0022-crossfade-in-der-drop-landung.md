# ADR-0022: Crossfade in der Drop-Landung (Dual-Player, stufenweise)

Datum: 2026-09-19
Status: Akzeptiert (Nutzerentscheidung; Umsetzung stufenweise, Spike vor Bau)

## Problem

Die Drop-Landung wechselt heute hart: `RestMusicCoordinator` uebergibt dem
Planner `crossfadeMs = 0L` und ruft `playSongAt` auf
(`RestMusicCoordinator.kt:170`, `:191`). `MixPreset` und `CrossfadeCurves`
liegen fertig und getestet vor, haben aber seit der ADR-Konsolidierung
(README Schritt 18, Entfernen des `CrossfadeController`) **keinen
Produktivkonsumenten**; die Bedienflaechen sind seit B-AUD-5 ausgegraut.
ADR-0012 beschreibt dagegen noch `crossfadeTo`/`ACTION_CROSSFADE_TO` — der
Code kennt nur `playSongAt`/`ACTION_PLAY_SONG_AT` (DOC-2).

Die Tiefenrecherche (`docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md`,
Abschnitt 3.6) empfiehlt ausdruecklich **keinen** Dual-Player:
"wurde bewusst entfernt; ohne belegte Notwendigkeit nicht zurueckholen".
Adi hat am 19.09.2026 im Entscheidungs-Wizard den **echten Crossfade**
gewaehlt (Entscheidung 4). Dieser ADR setzt die Nutzerentscheidung um und
adressiert die Gegenargumente.

## Optionen

1. Harter Wechsel mit Mikro-Rampe bleiben (D9: nur die Lautstaerke-Kurve
   um den Wechsel, ein Player, kein Ueberlappen).
2. Echter Crossfade mit zwei ueberlagerten Playern im `PlaybackService`,
   vorgeladen ueber die Media3-1.11-Preloading-APIs.
3. Crossfade streichen und die ausgegrauten Panels entfernen.

## Entscheidung

**Option 2, stufenweise und mit vorgeschaltetem Spike.**

**Stufe 1 (sofort, kein Dual-Player):** Der Planner-Crossfade wird
verdrahtet (D9) und als Aus-/Einblendung um den harten Wechsel angewendet,
zusaetzlich die Mikro-Rampe. Das nutzt die vorhandenen Kurven, beseitigt
Knacksen und macht die Panels wieder ehrlich — aber es ueberlappt nicht.

**Stufe 2 (das eigentliche Ziel):** Ein zweiter ExoPlayer wird **nur fuer
die Landung** vorbereitet (nicht dauerhaft betrieben), vorgeladen mit
`ExoPlayer.setPreloadConfiguration` / `PlayerPool` (Media3 1.11), und die
Equal-Power-Kurven aus `MixPreset`/`CrossfadeCurves` werden auf die beiden
Player-Volumes angewendet. Der Wechsel laeuft auf dem Playback-Thread
(keine `delay()`-Terminierung; siehe ADR-0012-Nachtrag und MP-3).

**Vorbedingung: Spike vor Bau.** Der Spike muss drei Fragen beantworten,
sonst bleibt es bei Stufe 1:

1. **DSP-Kette:** Die DSP-Kette haengt am Sink des sessionfuehrenden
   Players. Fuer ein Ueberlappen braucht der zweite Player entweder Zugang
   zu derselben Kette oder eine eigene. Der Spike klaert, welcher Weg ohne
   doppelte DSP-Instanz und ohne Sample-Rate-Konflikt funktioniert.
2. **Sample-Rate-Wechsel:** Zwei Titel mit unterschiedlichen Raten duerfen
   beim Ueberlappen keinen Aussetzer erzeugen; messen, nicht annehmen.
3. **AudioFocus und Bit-Perfect:** Verhalten bei Fokusverlust und im
   Bit-Perfect-Modus (der Crossfade umgeht DSP; die Kombination muss
   definiert sein, nicht zufaellig).

**Gegenargumente der Tiefenrecherche und ihre Antwort:**

- *"Bewusst entfernt"*: Die Konsolidierung entfernte einen dauerhaft
  laufenden Dual-Player-Pfad. Hier wird der zweite Player nur fuer die
  Landung aufgebaut und danach wieder freigegeben — ein anderer Kostenbild.
- *"Keine belegte Notwendigkeit"*: Die Notwendigkeit ist jetzt eine
  Produktentscheidung (Adi), kein technisches Argument. Der Spike liefert
  die fehlende Belegbarkeit fuer die technische Seite.
- *"Decoder-Last"*: Media3 1.11 Preloading/PlayerPool existiert genau fuer
  diesen Fall; der Spike misst den realen Aufwand.

## Folgen

- `HARDWARE_TESTPLAN.md` B5 ("Crossfade") wird wieder sinnvoll; die
  Testbeschreibung dort setzt ihn bereits voraus.
- Die ausgegrauten Panels (`settings_mix_no_effect`,
  `audio_crossfade_no_effect`) koennen nach Stufe 2 reaktiviert werden; bis
  dahin bleiben sie korrekt ausgegraut.
- ADR-0012 erhaelt einen Nachtrag: der dort beschriebene
  `crossfadeTo`-Pfad wird durch Stufe 2 wieder hergestellt (oder bewusst
  verworfen, wenn der Spike scheitert).
- Der Landing-Code muss zwischen "harter Wechsel" (DIRECT_TO_DROP, der
  Sprung soll sitzen) und "Ueberlappen" (INTRO-Landung) unterscheiden.
- Kein Code durch diesen ADR selbst; Stufe 1 ist Paket P1-7 des
  Verbesserungsplans, Stufe 2 Paket P2-27 inkl. Spike.
