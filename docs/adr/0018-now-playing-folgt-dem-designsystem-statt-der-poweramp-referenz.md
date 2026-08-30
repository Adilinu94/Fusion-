# ADR-0018: Now-Playing folgt dem Designsystem statt der Poweramp-Referenz

Datum: 2026-08-30
Status: Akzeptiert

## Problem

`NowPlayingScreen.kt` war der einzige Screen im Projekt, der das
FlowRep-Designsystem nicht benutzt hat. Er setzte sechs feste Farben im
Kotlin-Code:

```
CYAN = 0xFF009FE3, WAVE_CYAN, WAVE_REFLECTION,
POWERAMP_TEXT = 0xFF424242, POWERAMP_MUTED, POWERAMP_INACTIVE
```

dazu ein `Modifier.background(Color.White)` und einen Verlauf, der fest auf
`Color.White` endete. Das hat drei Konsequenzen:

1. Im Dark-Mode — dem Standard des Designsystems (`DarkColors`,
   `BrandGround = 0xFF141414`) — blendete der Screen weiss auf. Wer nachts
   trainiert und die App sonst dunkel sieht, bekam beim Oeffnen des Players
   eine weisse Flaeche.
2. Die in den Einstellungen waehlbare Akzentfarbe (`AccentColor.LIME` bzw.
   `AccentColor.BLUE`, siehe `FlowRepTheme`) hatte hier keine Wirkung. Der
   Player blieb cyan, egal was der Nutzer gewaehlt hatte.
3. `PlayerArtworkColors` berechnet fuenf Werte (`scrim`, `content`,
   `contentMuted`, `accent`, `onAccent`) — verwendet wurde genau einer
   (`accent`), und auch der nur fuer den Hintergrundverlauf. Die uebrige
   Rechnung lief bei jedem Titelwechsel ins Leere.

Zusaetzlich entschied `colorsFromPixels` die Helligkeit des gesamten
Screens allein aus der Cover-Luminanz (`darkBackground = dominantLuminance <
0.55`). Ein dunkles Cover ergab also auch im Light-Mode einen dunklen
Player und umgekehrt — die Helligkeit sprang von Titel zu Titel.

## Optionen

1. Poweramp-Referenz beibehalten: der Player ist bewusst ein Fremdkoerper
   mit eigener Identitaet, wie in vielen Musik-Apps ueblich.
2. Designsystem durchsetzen: Theme bestimmt die Helligkeit, Cover den
   Farbton, `colorScheme.primary` den Akzent-Fallback.
3. Nutzer entscheiden lassen (Einstellung "Player-Design").

## Entscheidung

Option 2. Die Trennung lautet ab jetzt: **das Theme bestimmt die
Helligkeit, das Cover den Farbton.** `colorsFromPixels(pixels, darkTheme)`
zieht den dominanten Coverton je nach Theme Richtung Schwarz oder Weiss;
die Textfarben folgen dem Theme, nicht dem Bild. Fehlt ein saturiertes
Bucket (Graustufen-Cover), liefert die Funktion `accent = null` und der
Screen nimmt `MaterialTheme.colorScheme.primary` — damit wirkt die
gewaehlte Akzentfarbe endlich auch hier.

Option 1 wurde verworfen, weil der Widerspruch nicht gestalterisch
begruendet war: die festen Werte stammen aus einer Referenz-Vorlage, nicht
aus einer Designentscheidung, und der weisse Grund im Dark-Mode ist im
Trainingskontext ein konkreter Nachteil. Option 3 fuegt eine Einstellung
fuer ein Problem hinzu, das eine Entscheidung braucht.

Neu ist eine **Kontrastgarantie**: die aus dem Cover gewonnene Akzentfarbe
traegt im Player den Titel (30 sp) und fuellt den Play-Kreis. Ein
dunkelblauer Akzent auf dunklem Grund waere unlesbar. `ensureContrast`
verschiebt den Akzent daher schrittweise Richtung Theme-Gegenfarbe, bis das
WCAG-2.1-Verhaeltnis 3:1 erreicht ist (AA fuer grossen Text), und bricht
nach 12 Schritten ab, damit ein unerreichbares Ziel keine Endlosschleife
ergibt.

## Folgen

- Die Poweramp-Konstanten sind entfernt. Wer die Optik anpassen will,
  aendert das Theme oder die Akzent-Einstellung, nicht mehr diese Datei.
- `PlayerArtworkColors` hat jetzt einen `darkTheme`-Parameter, und
  `rememberArtworkColors` nimmt `isSystemInDarkTheme()` als zweiten
  `produceState`-Key auf: ein Theme-Wechsel bei laufender Wiedergabe rechnet
  die Farben neu.
- Die Luminanzrechnung nutzt WCAG-Linearisierung statt der frueheren
  gewichteten Summe. Das ist teurer (eine `Math.pow` je Kanal), laeuft aber
  nur einmal je Cover — nicht pro Frame.
- Neuer Test `PlayerArtworkColorsTest` (8 Faelle) haelt die Trennung fest,
  insbesondere "dunkles Cover ergibt im Light-Mode keinen dunklen Player"
  und die 3:1-Kontrastgarantie.
- Beim Umbau fiel eine Regression aus dem Song-Cache-Fix (P1 #13) auf:
  `nowPlaying`/`miniPlayer` kombinierten den Wiedergabezustand mit noch
  nicht geladenen Metadaten und veroeffentlichten dadurch bei jedem
  Titelwechsel kurz `isVisible = true` mit leerem Titel. `statesWithSong()`
  paart die Metadaten jetzt mit ihrer Song-ID und laesst nur
  zusammengehoerende Paare durch; die UI zeigt in dem Moment weiter den
  alten Titel statt einer leeren Zeile.
- Der `Poweramp`-Praefix an den privaten Composables (`PowerampCover`,
  `PowerampTitleRow`, ...) bleibt vorerst als Hinweis auf die
  Layout-Herkunft; das LAYOUT (Kreis-Cover, Waveform-Fenster mit
  zentriertem Play-Kreis) ist weiterhin an der Referenz orientiert und
  steht nicht zur Debatte. Nur die FARBEN kommen jetzt aus dem System.
