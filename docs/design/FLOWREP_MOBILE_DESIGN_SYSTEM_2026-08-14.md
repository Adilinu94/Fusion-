# FlowRep Mobile Design System

**Status:** verbindliche Ziel-Spezifikation fuer das visuelle Redesign
**Plattform:** Native Android, Kotlin, Jetpack Compose, Material 3 als technische Basis
**Geraete:** Android-Smartphones zuerst; Tablets/Foldables adaptiv per Navigation Rail
**Modus:** Dark-first; Light Mode ist kein Bestandteil des ersten Redesign-Releases
**Produktbedingungen:** vollstaendig offline, keine Analytics, keine Cloud-Abhaengigkeit

## 1. Zweck und Vorrang

Dieses Dokument definiert die visuelle Sprache, die Interaktion und die wiederverwendbaren Komponenten von FlowRep. Es ersetzt bei Konflikten die visuellen Entscheidungen aus `FLOWREP_DESIGN_PLAN.md`. Fachliche, Architektur- und Accessibility-Vorgaben aus `UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md` bleiben verbindlich.

Die Screenshots des Nutzers sind Referenzen fuer die Richtung, nicht fuer eine pixelgenaue Kopie. FlowRep verbindet deren Performance-Fitness-Aesthetik, glaserne Musiknavigation, waveform-zentrierten Player und reduzierten Timer mit Android-konformer Bedienung.

## 2. Designhaltung

**FlowRep ist eine fokussierte Trainingskonsole mit Musikintelligenz.** Die UI muss in einer Sekunde beantworten, was gerade laeuft und was die naechste Aktion ist.

- Schwarz schafft Fokus und spart auf OLED-Displays Energie.
- Lime steht fuer Energie, Fortschritt und die eine primaere Aktion im aktuellen Zustand.
- Glas ist Navigation und Kontext, nie Dekoration auf jeder Karte.
- Fotos und Cover bringen Emotion; UI-Flaechen bleiben ruhig und funktional.
- Musik ist sichtbar, darf den Trainingsfluss aber nie dominieren.
- Details erscheinen erst, wenn sie fuer die aktuelle Aufgabe relevant sind.

### Verbotene Muster

- Mehrere gleichwertige Lime-CTAs auf einem Screen.
- Neon-Lime fuer Fliesstext oder sekundaere Metadaten.
- Transparente Glasflaechen hinter langem Text oder kritischen Daten.
- Kleine Icon-only-Aktionen ohne mindestens 48dp Touch-Ziel und TalkBack-Label.
- Versteckte Geste ohne sichtbaren Alternativweg.
- Lange, nicht virtualisierte Musiklisten.
- Eine generische Material-3-Optik ohne FlowRep-Tokens.

## 3. Mobile Commitment

| Entscheidung | Festlegung |
| --- | --- |
| Plattform | Android, native Jetpack Compose |
| Navigation | Vier sichtbare Hauptziele: Train, Music, Verlauf, Einstellungen |
| Navigation auf Telefonen | Schwebende Glass-Bottom-Bar mit sichtbaren Labels |
| Navigation ab Medium Width | Navigation Rail, nicht Glass-Bottom-Bar |
| Offline | Kernfunktionen, Bibliothek, Timer, Musik, Waveform und Training funktionieren vollstaendig lokal |
| Primaerer Kontext | Einhaendige Nutzung im Studio, bei Bewegung, schwachem Empfang und hellem Licht |
| Kein Default-Muster | Keine Kartenwand; jede Ansicht hat eine dominante Aufgabe und klare Prioritaet |
| Spezifischer Performance-Fokus | Waveform, lokale Musiklisten, Cover-Decoding und Animationsbudget |

## 4. Foundations

### 4.1 Farbrollen

Die konkreten Werte werden ausschliesslich in `:core:designsystem` definiert. Features verwenden nur semantische Rollen, nie Hex-Werte.

| Rolle | Token | Wert | Verwendung |
| --- | --- | --- | --- |
| OLED-Hintergrund | `background` | `#101010` | App-Grund, grosse freie Flaechen |
| Vertiefte Flaeche | `surfaceLow` | `#151515` | Medienflaechen, ruhige Hintergrundbereiche |
| Standard-Surface | `surface` | `#1D1D1D` | Karten, Listen, Sheets |
| Erhoehte Surface | `surfaceHigh` | `#252525` | Mini-Player, selektierte Elemente |
| Hairline | `outline` | `#353535` | Konturen, Trennlinien, Glasrand |
| Haupttext | `onSurface` | `#F7FBFF` | Titel, Zahlen, wichtige Inhalte |
| Sekundaertext | `onSurfaceVariant` | `#B7B7B7` | Interpret, Dauer, Erklaerungen |
| Deaktiviert | `onSurfaceDisabled` | `#727272` | nicht verfuegbare Aktionen |
| Flow-Lime | `primary` | `#DFFF2F` | eine Hauptaktion, aktiv, Fortschritt, bestaetigt |
| Auf Lime | `onPrimary` | `#101010` | Icons und Text auf Lime |
| Warnung | `warning` | `#FFB74D` | Best Effort, Aufmerksamkeit erforderlich |
| Fehler | `error` | `#FF6B6B` | Fehler und destruktive Zustaende |
| Info | `info` | `#78C8FF` | neutrale technische Information |

Lime ist **keine** Erfolgsfarbe im fachlichen Sinn. Es steht fuer Fokus oder Aktivitaet. Erfolg, Warnung und Fehler erhalten immer zusatzlich Text und Icon.

### 4.2 Typografie

**Markenschrift:** Poppins, gebuendelt als OFL-Schrift in Regular (400), Medium (500), SemiBold (600) und Bold (700). System-Fallback: `sans-serif`. Diese Entscheidung ersetzt Raleway fuer die neue UI.

| Rolle | Groesse | Gewicht | Zeilenhoehe | Einsatz |
| --- | ---: | ---: | ---: | --- |
| Display | 48sp | 700 | 52sp | Timer, zentrale Kennzahlen |
| Hero | 36sp | 700 | 42sp | Screen- und Workout-Hero |
| Headline | 28sp | 600 | 34sp | Seitentitel |
| Title | 20sp | 600 | 26sp | Karten und Sektionen |
| Body | 16sp | 400 | 24sp | Standardinhalt |
| Meta | 14sp | 400 | 20sp | Interpret, Dauer, Status |
| Label | 12sp | 600 | 16sp | Chips, Navigation, Status |

- Text verwendet immer `sp`, nie `dp`.
- Body bleibt mindestens 16sp; reine Metadaten mindestens 12sp.
- Grosse Zahlen nutzen tabellarische Ziffern.
- Alle Screens muessen mit Android-Schriftgroesse 200 Prozent ohne abgeschnittene kritische Inhalte bedienbar bleiben.

### 4.3 Spacing und Form

Das 8dp-Raster bleibt erhalten. Es wird um klare Komponententokens ergaenzt:

| Token | Wert | Verwendung |
| --- | ---: | --- |
| `space4` / `space8` / `space12` | 4 / 8 / 12dp | kompakte Beziehungen |
| `space16` | 16dp | Seitenrand auf Telefonen, Standard-Gap |
| `space24` | 24dp | Kartenpadding, Abschnittsbeziehung |
| `space32` | 32dp | grosse Abschnittstrennung |
| `radiusSmall` | 12dp | Chips, kleine Kacheln |
| `radiusCard` | 20dp | Standardkarten |
| `radiusHero` | 28dp | Hero-Karten und grosse Sheets |
| `radiusPill` | 999dp | Buttons, Filter, Navigation-Indicator |
| `touchTarget` | 48dp | Minimum fuer jede interaktive Flaeche |
| `primaryControlHeight` | 56dp | primare Buttons und +/- Kontrollen |

## 5. Interaktion, Feedback und Motion

### 5.1 Touch

- Jede Aktion hat eine Hit-Area von mindestens 48 x 48dp; benachbarte Ziele sind mindestens 8dp getrennt.
- Primaere Aktionen liegen im unteren Drittel oder im natuerlichen Daumenbereich.
- Destruktive Aktionen liegen im Overflow oder benoetigen eine explizite Bestaetigung.
- Alle Taps zeigen sofort Ripple und eine leichte Press-Scale-Animation. Die visuelle Rueckmeldung beginnt unter 50ms.
- Long Press, Drag und Swipe bleiben Beschleuniger, niemals der einzige Bedienweg.

### 5.2 Haptik

| Ereignis | Android-Haptik |
| --- | --- |
| Tab, Chip, Toggle | `CLICK` oder `TICK` |
| Satz gespeichert | `HEAVY_CLICK` |
| Marker-Snap auf Waveform | `TICK` |
| DropSync armed / Go | `DOUBLE_CLICK` bzw. deutliche Geraete-Haptik |
| Ungueltige Aktion | `REJECT` |

Keine sekundenweise Haptik im Timer und keine Haptik fuer passives Scrollen.

### 5.3 Motion

- Standardinteraktion: 160-220ms, `FastOutSlowIn` bzw. Compose-Standardfeder.
- Screen- und Sheet-Transition: maximal 300ms.
- Es werden primar nur `alpha`, `scale`, `translation` und `rotation` animiert.
- Waveform-Fortschritt darf den Zeichenpfad nicht pro Frame neu erzeugen.
- Bei reduzierter Android-Animationsdauer werden dekorative Animationen deaktiviert; Zustandswechsel bleiben sofort sichtbar.

## 6. Navigation und Shell

### 6.1 Informationsarchitektur

Die sichtbaren Hauptziele sind verbindlich:

```text
Train | Music | Verlauf | Einstellungen
```

- Train ist der operative Workout-Flow.
- Music ist Bibliothek, Queue, Playlisten, Marker Review und Now Playing.
- Verlauf ist die Auswertung von Saetzen, Volumen und PRs.
- Einstellungen enthalten nur dauerhafte Praeferenzen und Expertenoptionen.

Jedes Hauptziel behaelt seinen Navigationszustand beim Wechsel. Android-System-Back und Predictive Back poppen immer die Hierarchie und werden nie fuer andere Aktionen verwendet.

### 6.2 FlowRep Glass Bottom Bar

Die Glass-Bottom-Bar ist die mobile Hauptnavigation, nicht ein allgemeines Kartenmuster.

| Eigenschaft | Vertrag |
| --- | --- |
| Platzierung | 16dp links/rechts, 12dp oberhalb der System-Navigationsleiste |
| Hoehe | mindestens 72dp plus System-Inset |
| Hintergrund | `surface` mit 86-92 Prozent Deckkraft; Blur nur wenn technisch stabil |
| Kontur | 1dp `outline` mit niedriger Deckkraft |
| Radius | `radiusHero` |
| Schatten | sehr weich, nur zur Trennung vom Content; kein harter Material-Schatten |
| Ziele | vier gleich breite Ziele mit Icon und dauerhaft sichtbarem Label |
| Aktiv | Lime-Indicator-Pill, dunkles gefuelltes Icon, Label Lime oder Haupttext |
| Inaktiv | Outline-Icon, Sekundaertext |

Falls echter Backdrop Blur auf einem Zielgeraet Frame-Drops verursacht, wird er ohne Funktionsverlust durch eine stark deckende, semitransparente Surface ersetzt. Die Lesbarkeit und 60fps haben Vorrang vor dem Effekt.

Der Mini-Player sitzt oberhalb der Navigation. Now Playing ist chromelos und blendet Mini-Player sowie Navigation aus.

## 7. Komponentenvertrag

### 7.1 Foundation-Komponenten

| Komponente | Zweck | Verbindliche Eigenschaften |
| --- | --- | --- |
| `FlowRepTheme` | Semantische Tokens | keine Feature-Hexwerte, Dark-first |
| `FlowRepSurface` | Standardcontainer | Surface-Rolle, Radius, optionale Hairline |
| `FlowRepMetricCard` | kompakte Kennzahl | Titel, Wert, optionales Statusicon; keine primaere CTA |
| `FlowRepPrimaryButton` | wichtigste Aktion | Lime, 56dp, dunkler Kontrasttext, Vollbreite wenn workflowkritisch |
| `FlowRepSecondaryButton` | zweitrangige Aktion | Surface/Outline, keine Lime-Flaeche |
| `FlowRepIconButton` | kompakte Aktion | 24dp Icon in 48dp Hit-Area, Content Description |
| `FlowRepChip` | Filter und Status | maximal eine Chipzeile; ausgewaehlt Lime oder hohe Surface |
| `FlowRepGlassNavigation` | Top-Level-Navigation | nur Smartphone-Shell |
| `FlowRepMiniPlayer` | kontextuebergreifende Wiedergabe | Titel, Cover, Play/Pause, sicherer DropSync-Zustand |

### 7.2 Medienkomponenten

| Komponente | Vertrag |
| --- | --- |
| `FlowRepWaveform` | Canvas mit vorverarbeiteter Geometrie; Tap seeked, Drag zeigt lokale Vorschau und seeked erst beim Loslassen |
| `WaveformTransportButton` | runder Lime-Play/Pause-Button, mittig auf der Waveform; 64dp visuell und mindestens 72dp Hit-Area |
| `MarkerLegend` | bestaetigt, Vorschlag und aktives Ziel unterscheiden sich durch Form, Label und Farbe |
| `FlowRepTrackRow` | Cover, Titel, Interpret, Dauer/Format; Tap-Bereich ist gesamte Zeile; Overflow separat 48dp |
| `FlowRepMiniPlayer` | zeigt bei DropSync `DROP READY` und versteckt direkten Skip-Next-Shortcut |

### 7.3 Trainingskomponenten

| Komponente | Vertrag |
| --- | --- |
| `WorkoutConsoleHeader` | aktive Uebung, Satzfortschritt, optionale Sensor-/Musikstatuszeile |
| `SetEntryHero` | Gewicht, Reps, +/- Stepper und genau eine Aktion `SATZ FERTIG` |
| `RestConsole` | verbindet Resttimer, DropSync-Status, Zielmusik und Aktionen in einem Hero |
| `FlowRepTimerPicker` | drei Wheel-Spalten fuer Stunden/Minuten/Sekunden, aktive Reihe Lime, Nachbarn gedimmt |
| `SessionMusicStrip` | kompakter Musikstatus im Training; oeffnet Music/Now Playing, konkurriert nicht mit Satzabschluss |

## 8. Screen-Vertraege

### 8.1 Train

Train ist keine Dashboard-Kartenwand, sondern eine kontextabhaengige Konsole.

**Idle:** kurze Begruessung, Tagesfortschritt als heroische Kennzahl, zuletzt/geplant und ein klarer Startpunkt. KPI-Karten (z. B. Herzfrequenz, Trainingszeit) bleiben kompakt und sind keine Interaktionskonkurrenz.

**Satz-Eingabe:**

```text
UEBUNGSNAME                         Satz 3 von 4

Gewicht
[ 80,0 kg ]

Reps
[ - ]              8              [ + ]

[ SATZ FERTIG ]
```

- Reps-Stepper haben 56dp Tasten.
- Sensorherkunft wird als lesbarer Text plus Icon angezeigt.
- Nach Satzabschluss: Heavy-Haptik, Undo-Snackbar, Rest Console wird dominant.

**Rest:** Der Timer verdrangt die Satz-Eingabe visuell. `RestConsole` ist die einzige grosse Flaeche und zeigt Restzeit, naechsten Satz, DropSync-Status und die Aktionen Pause/Resume, +15s und Abbrechen.

### 8.2 Music und Bibliothek

Music beginnt mit einer task-orientierten Uebersicht: Now Playing, Work-Playlisten, Rest-Playlisten, Marker Review und zuletzt gespielt. Die vollstaendige Bibliothek bleibt eine Drill-down-Ansicht.

Bibliotheksvertrag:

- Grosser Seitentitel, darunter Filter/Ansicht und sichtbare Suchaktion.
- Track-Zeilen nutzen 56dp Cover, Title/Artist/Meta in drei lesbaren Ebenen.
- Lange Listen verwenden `LazyColumn`; keine vollstaendige `Column` oder unbounded ScrollView.
- Cover werden auf Anzeigeaufloesung decodiert/gecacht, keine Originalgroesse im Speicher.
- Die Mini-Player-Zeile bleibt oberhalb der Glass-Bottom-Bar sichtbar.

### 8.3 Now Playing

Reihenfolge und Hierarchie:

```text
Zurueck / Titelkontext / Mehr
Grosses Cover
Titel und Interpret
Kontext: Work / Rest / Playlist
Waveform mit zentralem Play/Pause
Zeit: aktuell links, gesamt rechts
Sekundaere Transport- und Modusaktionen
Markerstatus und DropSync-Aktionen
```

- Das Cover ist rund oder stark gerundet, darf bei aktiver Markerarbeit kleiner werden als die Waveform.
- Die Waveform ersetzt den linearen Fortschrittsbalken vollstaendig, nicht nur dekorativ.
- Gespielte Bars sind Lime; ungepielte Bars sind `onSurfaceVariant` mit reduzierter Alpha. Das aktive Ziel benutzt zusaetzliche Form/Label.
- Play/Pause liegt geometrisch und optisch exakt im Zentrum der Waveform, nie darunter.
- Drag zeigt Cursor und Zeitblase; die Wiedergabe springt erst beim Loslassen.
- Ein sichtbarer Slider- bzw. Zeitsteuerungs-Fallback bleibt fuer TalkBack und praezise Bedienung vorhanden.

### 8.4 Timer

Der Standalone-Timer uebernimmt die reduzierte Referenzlogik, nicht deren helle Farbwelt.

- Drei vertikale Zahlenpicker: Stunden, Minuten, Sekunden.
- Ausgewaehlte Reihe: `onSurface` mit Lime auf der aktuell veraenderten Spalte.
- Nachbarwerte sind gedimmt, aber noch lesbar.
- Start/Pause ist ein breiter, zentrierter Pill-Button im unteren Daumenbereich.
- Presets werden nur im Idle sichtbar. Ein aktiver Timer zeigt keine konkurrierende Presetwand.
- Dauer und Status sind per `stateDescription` fuer TalkBack verfuegbar.

### 8.5 Verlauf und Einstellungen

- Verlauf priorisiert Today, Woche, Monat, PRs und Uebungsverlauf vor komplexen Filtern.
- Einstellungen folgen klaren Gruppen: Training & Pausen, Musik & Uebergaenge, DropSync, Audio Experten, Darstellung, Daten & Marker, Datenschutz.
- Expertenparameter liegen maximal zwei Taps tief und erklaeren Nutzen sowie Nebenwirkung in Alltagssprache.

## 9. Zustaende und Fehlermeldungen

Jede daten- oder geraeteabhaengige Komponente besitzt mindestens: Loading, Ready, Empty, Unavailable, Error und Retry.

- Offline ist kein Fehlerzustand: lokale Musik, lokale Datenbank, Timer und Workout bleiben bedienbar.
- Fehlende Analyse zeigt eine dauerhafte, kurze Erklaerung und einen sichtbaren Start/Retry-Weg.
- Bluetooth-/Bit-Perfect-Einschraenkungen werden als `BEST EFFORT` mit Ursache und Nutzerentscheidung dargestellt.
- Ein manueller Eingriff in Musik bricht einen Drop-Plan sichtbar ab; nie stillschweigend.

## 10. Accessibility und Qualitaetsgates

- Alle Steuerungen: mindestens 48dp, Content Description, korrekte Rolle und Statusansage.
- Kontrast: Text mindestens WCAG AA; kritische Daten sollen AAA anstreben.
- Farbe wird nie als einziger Statuskanal genutzt.
- Waveform: semantische Beschreibung, sichtbare Zeit, Slider-Fallback und textliche Marker-Alternative.
- Animationen respektieren die Android-Systemeinstellung fuer reduzierte Bewegung.
- Testumfang: TalkBack, 200 Prozent Schriftgroesse, helles Studiolicht, einhaendige Bedienung auf grossem Smartphone, Release-Build auf Mittelklassegeraet.

### 10.1 Umgesetzte A11y-Arbeiten (2026-08-21, Verbesserungsplan 6.2)

- Live-Zaehler im TrainScreen als polite liveRegion (TalkBack sagt jede Rep
  ohne Fokusklau an); DEGRADED-Signalwarnung ebenfalls liveRegion.
- HistoryScreen: Satzzeilen via `mergeDescendants` als ein TalkBack-Element.
- Kontrast-Gate als Unit-Test (`ThemeColorSnapshotTest`): Lime auf Schwarz
  erfuellt WCAG AA (>= 4.5:1), onPrimary bleibt Schwarz gegen Lime.
- M3 Expressive (Plan 6.1) evaluiert: `MaterialExpressiveTheme` ist in der
  stabilen material3 1.4.0 noch internal (oeffentlich ab 1.5.0-alpha); die
  springbasierte Motion-Sprache ist bereits ueber Navigation-Pill und
  Brand-Buttons abgedeckt. Wiedervorlage mit material3 1.5 stable
  (siehe Theme.kt-Kommentar).

## 11. Performance- und Akkuvertrag

- Ziel: kontinuierlich 60fps bei Scroll, Waveform-Scrub, Glass-Navigation und Timer.
- Canvas zeichnet nur vorbereitete Waveform-Geometrie. Keine Datenbankzugriffe, Audioanalyse oder neue Collection-Allokationen im Draw-Pfad.
- Timer aktualisiert nur die minimale notwendige State-Flaeche.
- Blur ist optional und muss auf realen Mittelklassegeraeten profiliert werden; bei Jank wird der Effekt durch Alpha-Surface ersetzt.
- Listen sind immer lazy und mit stabilen Schluesseln aufgebaut.
- Bilder und Cover werden begrenzt gecacht und nahe der Darstellungsaufloesung geladen.
- Keine dauerhaften Animationen ausser sinnvoller Wiedergabe-/Timeranzeige; Animationen pausieren ausserhalb sichtbarer Screens.

## 12. Umsetzungsreihenfolge

1. `:core:designsystem`: Poppins, Farbrollen, Form-/Touch-/Motion-Tokens, semantische Statusfarben.
2. App-Shell: Glass-Bottom-Bar fuer Compact Width, Rail fuer Medium/Expanded, Mini-Player-Abstand und Insets.
3. Medienbasis: Waveform, zentraler Transportbutton, Marker-Legende und Mini-Player-Zustaende.
4. Train: Workout Console, Set Entry Hero, Rest Console, Session Music Strip.
5. Music: Music Home, Bibliothek, Playlisten, Marker Review und Now Playing.
6. Timer, Verlauf und Einstellungen nach dem gleichen System nachziehen.
7. Realgeraete-QA und Accessibility-/Performance-Abnahme vor Release.

## 13. Akzeptanzkriterien

- Ein Nutzer erkennt die aktuelle primaere Aktion innerhalb einer Sekunde.
- Lime erscheint pro Kontext nur als Hauptaktion oder aktiver Fortschritt.
- Der Play/Pause-Button sitzt zentriert auf der interaktiven Waveform.
- Die mobile Hauptnavigation ist eine gut lesbare Glass-Bottom-Bar mit vier Labels.
- Training, Music, Bibliothek, Timer, Verlauf und Einstellungen verwenden identische Tokens statt lokaler Stilwerte.
- Alle kritischen Interaktionen sind einhaendig erreichbar und mindestens 48dp gross.
- Der neue Stil funktioniert offline, bei 200 Prozent Schriftgroesse und mit TalkBack.
- Glass und Waveform halten auf einem realen Mittelklasse-Android-Geraet 60fps oder fallen kontrolliert auf die definierte, nicht-geblurte Variante zurueck.
