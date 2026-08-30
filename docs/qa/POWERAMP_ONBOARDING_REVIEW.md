# Poweramp Onboarding, Library & Live-Playing Review

**Geprüft am:** 2026-08-26
**App:** Poweramp (`com.maxmpz.audioplayer`)
**Gerät:** Flowtest / `emulator-5554`

## 1. Onboarding-Grundprinzip

Das Onboarding ist kein linearer Wizard mit vielen Weiter-Buttons. Es ist eine kontextbezogene Hilfeschicht über dem echten Player. Die App erklärt die Oberfläche dort, wo die jeweilige Funktion tatsächlich liegt, und lässt den Nutzer danach sofort weiterarbeiten.

Die Hilfe ist zusätzlich später über **Main Menu -> Help** erneut erreichbar. Dadurch ist ein verpasstes Onboarding kein dauerhafter Informationsverlust.

### Overlay-Aufbau

- Der echte Player bleibt im Hintergrund sichtbar.
- Ein abgedunkeltes Overlay hebt die Erklärungsebene hervor.
- Erklärungstexte liegen als Callouts nahe an den beschriebenen Zielbereichen.
- Mehrere Hinweise können gleichzeitig sichtbar sein, wenn sie unterschiedliche Regionen betreffen.
- Der Titel `Player Screen` befindet sich oben im Overlay.
- Ein einzelnes `Close`-Symbol oben rechts beendet die komplette Hilfe.
- Die Callouts greifen nicht in die eigentliche Bedienlogik ein.
- Hinweise verwenden kurze Handlungsanweisungen statt langer Produkttexte.
- Gesten werden direkt mit ihrer Wirkung erklärt: Swipe, Tap und Long Press.

## 2. Library-Durchlauf

### Library-Startseite

Poweramp verwendet eine eigene kategorisierte Library-Startseite mit folgenden Einstiegen:

- `All Songs`
- `Folders`
- `Folders Hierarchy`
- `Albums`
- `Artists`
- `Album Artists`
- `Genres`
- `Years`
- `Composers`
- `Playlists`
- `Streams`
- `Queue`
- `Bookmarks`

Die Kategorien sind als große, vertikal scrollbare Zeilen aufgebaut. Die Library-Navigation bleibt unten dauerhaft sichtbar. Die Inhalte wechseln, ohne dass die globale Navigationsleiste ihre Position oder Aufgabe verliert.

### All Songs

Der direkte Ablauf war:

1. `Library` öffnen.
2. `All Songs` auswählen.
3. Ein Titel per Einzel-Tap auswählen.
4. Der Titel startet sofort.
5. Der Live-Playing-Screen übernimmt Titel, Interpret und Fortschritt.

Die All-Songs-Ansicht enthält unmittelbar unter dem Titel:

- `Shuffle`
- `Play`
- `Search`
- `Select`
- `Menu`

Die Liste selbst ist dicht, aber dreistufig lesbar:

- große erste Zeile: Titel
- zweite Information: Dauer und Dateiformat, z. B. `3:41 | mp3`
- dritte Information: Interpret und Titel bzw. Albumkontext

Artwork bzw. Platzhalter liegen links, Text und Metadaten rechts. Die Reihenfolge bleibt beim Scrollen stabil. Ein A-Z-Scroller am rechten Rand erlaubt schnelle Sprünge durch lange Listen.

### Library-Overflow-Menü

Das kontextbezogene Menü der All-Songs-Ansicht enthält:

- `Select Folders`
- `Rescan`
- `Add to Launcher`
- `List Options`

Der Nutzer erhält dadurch sowohl Scan-/Quellenfunktionen als auch Darstellungsoptionen am Ort, an dem sie erwartet werden. Die Standardansicht bleibt frei von dauerhaften Zusatzbuttons.

### List Options

`List Options: All Songs` öffnet eine eigene Optionsansicht. Die Sortierung umfasst:

- By track number
- By disc and track number
- By title
- By filename
- By path
- By artist
- By album
- By year
- By year/album

Das ist ein gutes Muster für FlowRep: Sortierung, Gruppierung und Darstellung gehören in ein kontextbezogenes Menü pro Liste, nicht in globale Settings.

### Long Press auf einen Titel

Long Press öffnet den `Selection menu`-Modus. Dieser ist visuell und funktional klar von der normalen Liste getrennt.

Erfasste Funktionen:

- Mehrfachauswahl
- `All`
- `Range`
- Anzeige der Auswahl, z. B. `2 / 14`
- `Playlist`
- `Queue`
- `Play Next`
- `Delete`
- `Share`
- `Info/Tags`
- `Album Art`

Zusätzliche Hinweise erklären:

- Long Press eines Eintrags öffnet die Auswahl.
- Long Press bietet weitere Optionen.
- In Listen mit Dragging können mehrere markierte Einträge gemeinsam verschoben werden.
- `Range` wählt den Bereich zwischen bereits markierten Einträgen.

Übertragbares Muster: Die normale Liste bleibt auf die häufigste Aufgabe fokussiert. Batch-Aktionen und seltene Funktionen erscheinen erst nach bewusster Auswahl.

## 3. Live-Playing-Screen

### Aufbau von oben nach unten

Der Live-Player ist vertikal in klar getrennte Zonen gegliedert:

1. Status-/Topbereich mit Playerzustand.
2. große Artwork-/Visualisierungsfläche.
3. Titel und Interpret.
4. Like-/Unlike-Aktionen und Player-Menü.
5. sekundäre Control-Leiste.
6. Fortschrittsfläche mit Zeitangaben und Transportsteuerung.
7. Metadaten-/Ausgabeinformation.
8. persistente globale Navigation.

### Artwork und Visualisierung

Die mittlere Fläche ist eine große eigenständige Touch-Zone, nicht nur ein passives Bild. Sie unterstützt:

- Swipe links/rechts zum Titelwechsel.
- je nach Wischdistanz bzw. Geste den Wechsel der Kategorie.
- weitere kontextabhängige Playeraktionen.
- Long Press zum Öffnen der Visualisierungsoptionen.

Beim getesteten horizontalen Swipe wechselte der Titel von `adore u` zu `I Don't Wanna Stop`, während die Wiedergabe aktiv blieb. Der Wechsel wirkte wie ein direkter Playerübergang und nicht wie eine Rückkehr zur Liste.

Die Visualisierungsseite enthält eigene Einstellungen, unter anderem:

- `Preset Duration`
- Seekbar für die Dauer
- `Top Visualization Panel Opacity`
- `Faded Controls Opacity`
- `UI Timeout`

Das zeigt: Der Player kann die Sichtbarkeit der Controls dynamisch reduzieren, damit die Visualisierung bzw. Artwork-Fläche im laufenden Zustand dominanter wird.

### Titelinformationen

Titel und Interpret sind zentral und groß gesetzt. Der Metadatenbereich unterhalb der Transportsteuerung zeigt die aktuelle Ausgabekette, z. B.:

`OPENSL ES OUTPUT 16 BIT 48 KHZ`

Die Metadatenzone reagiert auf Tap und Long Press:

- Tap wechselt die sichtbare Information.
- Long Press öffnet Audio Info.

### Favorit

Der Like-Bereich besitzt zwei getrennte Accessibility-Zustände:

- `Like`
- `Unlike`

Der Zustand wird nicht nur farblich, sondern auch semantisch über `checked=true` abgebildet. Das ist für FlowRep wichtig: Zustände müssen für Accessibility und visuelle Nutzer gleichermaßen erkennbar sein.

### Player-Menü

Das Player-Menü sitzt in der Titel-/Metadatenzone rechts und ist per Tap beziehungsweise Long Press erreichbar. Es bündelt seltenere titelbezogene Aktionen, ohne die Hauptsteuerung zu überladen.

### Sekundäre Controls

Die Reihenfolge der unteren Control-Leiste:

- links: `Visualization`
- daneben: `Sleep Timer`
- rechts der Mitte: `Repeat`
- ganz rechts: `Shuffle`

Die Funktionen sind durch große Touch-Zonen erreichbar, auch wenn die sichtbaren Icons kompakter sind. Repeat und Shuffle zeigen den aktiven Zustand hauptsächlich visuell; ihre sichtbaren Markierungen ändern sich beim Umschalten.

`Sleep Timer` öffnet eine eigene Auswahl-/Einstellungsansicht. Das vermeidet verschachtelte kleine Popups im laufenden Player.

### Transportsteuerung

Die zentrale Anordnung ist zeitlich und räumlich logisch:

- `Previous category`
- `Previous track`
- großer zentraler `Play`-/`Pause`-Button
- `Next track`
- `Next category`

Der Play-/Pause-Button ist deutlich größer als die Nachbaraktionen. Der getestete Zustand wechselte zwischen `Play` und `Pause`, während die Position weiterlief bzw. pausierte.

Die Fortschrittszone ist eine große eigene Touch-Fläche. Zeit links und rechts bleiben sichtbar. Seek kann über die horizontale Fortschrittsfläche erfolgen.

### Globale Navigation

Die untere Leiste bleibt auch im Live-Player konstant:

- `Library`
- `Equ/Effects`
- `Search`
- `Main Menu`

Die Zonen sind breit genug für eine sichere Bedienung und besitzen klare Content Descriptions. Der Wechsel zur Library erfolgt ohne Verlust des laufenden Playback-Zustands.

## 4. Mikroanimationen und Zustandswechsel

Während des Durchlaufs waren folgende dynamische Effekte bzw. Zustandsänderungen erkennbar:

- Titelwechsel durch Artwork-Swipe bei weiterlaufender Wiedergabe.
- laufende Zeitposition, z. B. `0:05 -> 0:29 -> 0:30`.
- Play/Pause-Symbolwechsel in derselben zentralen Touch-Zone.
- visuelle Markierung bei Like, Repeat und Shuffle.
- adaptive Positionen und Größen von Player Controls je nach aktuellem Zustand.
- Visualisierungsfläche als dynamischer Bereich, der Accessibility-Automation teilweise am Idle-Zustand hindern kann.
- Player-/Listenübergänge behalten den Playback-Zustand bei.
- Auswahl- und Hilfeebenen liegen über der echten Oberfläche und werden separat geschlossen.

Wichtig für FlowRep: Animationen dürfen die Bedienbarkeit nicht blockieren. Die laufende Visualisierung darf weder Back-Navigation noch Accessibility-Erfassung und automatisierte Tests dauerhaft am Idle-Zustand hindern.

## 5. Gestenmodell

| Geste | Poweramp-Funktion | Übernahme für FlowRep |
|---|---|---|
| Einzel-Tap auf Titel | Titel sofort abspielen | Für Songlisten und Trainingsauswahl übernehmen |
| Swipe auf Artwork/Playerfläche | Titel wechseln | Für Player übernehmen, mit sichtbarem Richtungsfeedback |
| stärkerer/längerer Swipe | Kategorie wechseln | Nur verwenden, wenn die Geste zusätzlich sichtbar erklärt wird |
| Tap auf Fortschrittsfläche | Seek/Position ändern | Große Touch-Zone statt kleiner Seekbar |
| Long Press auf Titel | Auswahlmodus | Für Batch-Aktionen übernehmen |
| Long Press auf Metadaten | Audio Info | Für technische Detailinformationen übernehmen |
| Long Press auf Visualisierung | Visualizer-Einstellungen | Für Darstellungsoptionen verwenden |
| Tap auf Like | Favorit umschalten | Semantischen checked-Zustand ausgeben |
| Back/Close | Overlay oder Detailansicht verlassen | Jede sekundäre Ebene muss eindeutig schließbar sein |

## 6. Konkrete FlowRep-Learnings

### Priorität P0: Speicher-/Bibliotheksflow

1. Einen eigenen Ordnerverwaltungs-Screen nach dem Muster `Folders Selection` verwenden.
2. `Speicher oder Ordner hinzufügen` als primäre Aktion anbieten.
3. Android `ACTION_OPEN_DOCUMENT_TREE` starten.
4. Persistente Tree-URIs mit `takePersistableUriPermission` speichern.
5. Jede Quelle als eigene Zeile anzeigen: Name/Pfad, Status, Entfernen.
6. Vollständige Ordnerbäume inklusive leerer Ordner aus SAF beziehen.
7. MediaStore als ergänzende Quelle für indexierte Audiodateien verwenden.
8. `Speichern und scannen` als explizite Abschlussaktion verwenden.
9. Scanfortschritt und Scan-Ergebnis anzeigen.
10. Ein leerer Baum ist ein gültiger Zustand und darf nicht als fehlgeschlagene Aktualisierung beschrieben werden.

### Priorität P0: Hilfe-System

1. Ein generisches `ContextualHelpOverlay` für komplexe Screens einführen.
2. Hilfe über ein Info-/Help-Symbol erneut öffnen können.
3. Hilfe beim ersten Öffnen eines komplexen Screens optional automatisch anbieten.
4. Ein einziges Schließen-Symbol verwenden.
5. Hinweise an echten Bedienelementen verankern.
6. Jede Erklärung mit konkreter Aktion und Ergebnis formulieren.
7. Overlay-Zustand pro Screen speichern.
8. Hilfe über Einstellungen zurücksetzen können.

### Priorität P1: Library

1. Library in klare Kategorien gliedern.
2. Häufige Aktionen direkt unter dem Titel anbieten: Play, Shuffle, Search, Select.
3. Sortierung und Darstellung in ein kontextbezogenes `List Options`-Menü verschieben.
4. Long Press für Mehrfachauswahl und Batch-Aktionen verwenden.
5. Bereichsauswahl und Auswahlzähler unterstützen.
6. A-Z-Sprung bei langen Listen anbieten.
7. Titel, Metadaten und Artwork in einer stabilen Zeilenstruktur halten.
8. Globale Navigation während aller Detail- und Playerzustände sichtbar halten.

### Priorität P1: Player und Training

1. Primäre Aktion zentral und deutlich größer gestalten.
2. Vorherige Aktionen links, nächste Aktionen rechts anordnen.
3. Sekundäre Aktionen an Außenbereiche verschieben.
4. Erweiterte Einstellungen per Long Press oder Overflow zugänglich machen.
5. Touch-Zonen stabil dimensionieren, unabhängig von dynamischen visuellen Zuständen.
6. Icon-only Controls mit verständlichen Content Descriptions versehen.
7. Titelwechsel mit kurzer, nicht blockierender Übergangsanimation darstellen.
8. Playback beim Wechsel zwischen Library und Player erhalten.

### Priorität P1: Kontrast

- Text und Text-Buttons mit dunkler Markenfarbe oder kontrastgeprüfter `onSurface`-Farbe darstellen.
- Lime/Blue ausschließlich für Akzente, aktive Zustände und geeignete Aktionsflächen verwenden.
- Kleine Texte niemals in einer kontrastarmen Akzentfarbe auf hellem Hintergrund anzeigen.
- Callouts und Overlay-Flächen in Hell- und Dunkelmodus separat prüfen.

## 7. Nicht ungeprüft übernehmen

- Poweramp nutzt viele Gesten und Long Press-Aktionen. FlowRep sollte diese nur einsetzen, wenn die Funktion zusätzlich sichtbar auffindbar ist.
- Viele gleichzeitige Callouts können auf kleinen Displays überladen wirken. Für FlowRep sollten pro Screen höchstens drei bis fünf Kernhinweise sichtbar sein.
- Adaptive Player-Layouts dürfen keine Touch-Zonen verschieben, wenn der Nutzer gerade eine Aktion erwartet.
- Dynamische Visualisierungen dürfen UI-Automation, Back-Navigation und Accessibility nicht blockieren.
- Trainingsscreens brauchen zusätzlich jederzeit sichtbare Satz-, Sensor- und Timerzustände.
- Android-Systemdialoge bleiben systemsprachabhängig. Eigene FlowRep-Texte müssen vollständig lokalisiert werden.

## 8. Referenzartefakte

Die Aufnahmesitzung lag unter `ui-test/` und ist bewusst nicht versioniert
(siehe `.gitignore`). Dauerhaft erhalten sind die sieben Player-Referenzbilder,
auf die sich das Now-Playing-Handoff stuetzt — kuratiert unter
`../design/reference/`:

- [`poweramp_live_09_player_started.png`](../design/reference/poweramp_live_09_player_started.png): Live-Player nach Titelstart
- [`poweramp_live_12_player_progress.png`](../design/reference/poweramp_live_12_player_progress.png): laufender Fortschritt
- [`poweramp_live_13_player_paused.png`](../design/reference/poweramp_live_13_player_paused.png): pausierter Zustand
- [`poweramp_live_14_player_seek.png`](../design/reference/poweramp_live_14_player_seek.png): Seek-Geste
- [`poweramp_live_15_player_next.png`](../design/reference/poweramp_live_15_player_next.png): naechster Titel
- [`poweramp_live_20_player_baseline.png`](../design/reference/poweramp_live_20_player_baseline.png): Player-Baseline
- [`poweramp_live_31_artwork_swipe_left.png`](../design/reference/poweramp_live_31_artwork_swipe_left.png): Titelwechsel per Artwork-Swipe

Die uebrigen Aufnahmen der Sitzung (Onboarding-Hilfeschicht, Library-Menues,
Sortieroptionen, Auswahlmodus, Favorit/Repeat/Shuffle, Sleep-Timer- und
Visualizer-Kontext, Library-Einstieg) sowie alle `poweramp_*.xml`-UI-Dumps
sind nicht erhalten. Ihre Auswertung steht in den Abschnitten 1 bis 7 dieses
Dokuments; die Belege fuer die Waveform-Interna liegen unabhaengig davon in
den Smali-Artefakten unter `D:\rev-tools\poweramp_offline_triage` und in
`../design/WISSEN_POWERAMP_OFFTRACK_2026-08-07.md`.
