# Handoff: NowPlaying an Poweramp angleichen

**Datum:** 2026-08-26
**Projekt:** FlowRep / DropSync Fusion
**Arbeitsverzeichnis:** `C:\Users\adini\Desktop\flowrepProjekt\Fusion`
**Ziel:** Eine andere KI soll den NowPlaying-Screen visuell und funktional sehr nah an den beobachteten Poweramp-Screen angleichen.

## 1. Aktueller Status

Der aktuelle Screen ist funktional benutzbar, trifft das Poweramp-Design aber noch nicht exakt genug. Die letzte Umsetzung wurde zu früh als abgeschlossen bezeichnet. Der Nutzer hat ausdrücklich klargestellt, dass das Design weiterhin nicht stimmt.

In dieser Übergabe wurde **kein Produktcode verändert**. Es wurde nur dieses Handoff angelegt. Die bestehende Arbeitskopie enthält jedoch viele vorherige, nicht zwingend zusammengehörige Änderungen. Nichts davon pauschal zurücksetzen.

## 2. Nutzerziel

Der NowPlaying-Screen soll sich an dem vom Nutzer angehängten Poweramp-Screenshot orientieren. Gewünscht ist keine freie Interpretation und kein modernes Karten-/Glass-Redesign, sondern eine möglichst genaue Nachbildung von Aufbau, Größen, Abständen, Farben, Waveform und Interaktionen.

Ausdrücklich nicht anzeigen:

- Sleep Timer
- Visualizer

Die restlichen relevanten Player-Funktionen sollen erhalten bleiben.

## 3. Maßgebliche visuelle Referenz

Referenzgerät aus den bisherigen Tests: Android Emulator, ungefähr `1080 x 2400`, Poweramp-Screenshot mit weißem Hintergrund.

### Sichtbare vertikale Struktur

Von oben nach unten:

1. Native Android Statusleiste auf Weiß.
2. Sehr großes kreisförmiges Albumcover.
3. Großer cyanblauer Songtitel, horizontal zentriert.
4. Graue Metadatenzeile darunter: Interpret, Titel/Track-Kontext und Jahr, sofern verfügbar.
5. Kleiner runder hellgrauer Overflow-Button rechts im Titelbereich, mit drei cyanblauen vertikalen Punkten.
6. Eine Reihe mit vier großen, dünnen Player-Optionen ohne Card-Container.
7. Große Waveform-/Seek-Fläche.
8. Zentraler runder Play-/Pause-Button, über der Waveform.
9. Zeit links und rechts unter der Waveform.
10. Vier cyanblaue Icons in der unteren App-Navigation.
11. Native Android System-Navigationsleiste auf Weiß.

### Referenzgeometrie aus dem beobachteten Poweramp-Screen

Die Werte sind Pixelwerte des Referenzscreens und müssen auf die tatsächliche Displaydichte/Viewportgröße sinnvoll skaliert werden. Nicht blind als dp übernehmen.

- Statusleiste: ca. 75 px.
- Cover: ca. `x=68`, `y=140`, `784 x 784` px bei einem ca. 921 px breiten Inhaltsbereich.
- Cover exakt kreisförmig maskiert, ohne sichtbaren Rand.
- Unter dem Cover ungefähr 80 bis 90 px Abstand bis zum Titel.
- Titel um ca. 45 bis 48 px im Screenshot, normal bis leicht dünn, Cyan ungefähr `#009FE3`.
- Metadaten um ca. 25 bis 27 px, Grau ungefähr `#777777` bis `#888888`.
- Overflow-Kreis ungefähr 68 px Durchmesser, Hintergrund ungefähr `#F3F3F3`.
- Optionsreihe ungefähr um y=1280 px.
- Waveform ungefähr x=160 bis 895 px und y=1370 bis 1690 px.
- Waveform-Balken: dünn, vertikal, ungefähr 4 bis 6 px breit, 8 bis 12 px Abstand.
- Maximalhöhe einzelner oberer Balken ungefähr 150 bis 170 px.
- Play-Kreis ungefähr 155 bis 165 px Durchmesser.
- Zeittexte ungefähr 23 bis 25 px, Grau.
- Untere Navigation ungefähr um y=1800 px.

### Referenzfarben

- Hintergrund: `#FFFFFF`
- Hauptakzent/Titel/aktiver Zustand: `#009FE3`
- Obere Waveform: ungefähr `#65C0E4`
- Waveform-Spiegelung unten: ungefähr `#D9F0F8`
- Sekundärtext: `#777777` bis `#888888`
- Inaktive Optionsicons: ungefähr `#C8C8C8`
- Overflow-Hintergrund: ungefähr `#F3F3F3`
- Play-Icon: Weiß

Es gibt in der Referenz keine großen Cards, keine dunkle Playerfläche, keinen sichtbaren Scrim und keine dominanten Schatten. Das Cover kann einen sehr subtilen Schatten haben.

## 4. Waveform-Anforderung

Der Nutzer hat den wichtigsten funktionalen/visuellen Fehler mehrfach beschrieben:

> Es darf nicht die komplette Waveform des Songs gleichzeitig wie eine Übersicht dargestellt werden. Die Waveform soll wie bei Poweramp mitlaufen: Es ist ein sichtbarer Ausschnitt um die aktuelle Wiedergabeposition, und die Anzeige bewegt sich während der Wiedergabe weiter.

Die sichtbare Referenz zeigt:

- eine feste sichtbare Waveform-Zone;
- dünne, regelmäßige vertikale Balken;
- obere kräftigere cyanblaue Balken;
- untere sehr helle gespiegelte Balken;
- einen zentralen Play-/Pause-Kreis über der Waveform;
- Zeit links/rechts unterhalb der Waveform;
- keine klassische durchgehende Seekbar;
- keine riesige Liste sämtlicher Track-Buckets als statische Übersicht.

Wichtig: Die Form soll musikalisch stabil bleiben und sich nicht bei jedem Fortschrittsschritt in eine komplett andere zufällige oder neu gesampelte Form verwandeln.

## 5. Relevante Poweramp-Artefakte

Offline-Verzeichnis, nur lesen:

`D:\rev-tools\poweramp_offline_triage`

Relevante Dateien:

- `smali-3.0.9/com/maxmpz/widget/player/Waveseek.smali`
- `smali-3.0.9/com/maxmpz/widget/player/Seek.smali`
- `smali-3.0.9/com/maxmpz/widget/player/q1.smali`
- `smali-3.0.9/com/maxmpz/audioplayer/player/f0.smali`
- `smali-3.0.9/com/maxmpz/widget/base/i5.smali`
- `resource-inventory.txt`
- `static-keyword-report.txt`
- `lib/arm64-v8a/libpowerampcore.so`

### Belastbare Befunde

1. `f0.smali` besitzt `r:[F`. `Waveseek.x(f0)` liest dieses vorbereitete Float-Array direkt aus dem Playerzustand.
2. Wenn kein Array vorhanden ist, baut `Waveseek` nur einen kleinen synthetischen Fallback aus einer Sinusfolge. Das ist ein Fallback, nicht die echte Audioanalyse.
3. `q1.smali` verwendet `Choreographer.postFrameCallback`.
4. `q1.doFrame()` liest die autoritative Playback-Position und interpoliert zwischen Updates zeitbasiert über Nanosekunden. Die Fortschrittsposition wird als primitiver Float an das Widget gemeldet.
5. Kleine Positionsänderungen unter ungefähr `0.02` werden nicht unnötig als sichtbare neue Position behandelt.
6. `Seek.smali` trennt `DOWN`, `MOVE`, `UP` und `CANCEL`, verwendet die Positionsauflösung `0..10000` und mappt Touch-X innerhalb der gepaddeten Widgetbreite.
7. Scrubbing, laufender Fortschritt und Cancel sind getrennte Zustände.
8. `milk`-Shader und Blur-Assets gehören zur Visualizer-/Rendering-Schicht und beweisen nicht die NowPlaying-Waveform.
9. Die AAPT-Tree-Reports waren leer. Exakte XML-Maße können daraus nicht sicher abgeleitet werden.

### Nicht behaupten

Aus den Smali-Dateien allein sind nicht sicher ableitbar:

- die exakte Peak-Anzahl;
- die genaue Fensterbreite;
- die komplette Farbe-/Alpha-Reihenfolge;
- die exakten Layout-Dimensionen des Referenzscreens;
- dass die `milk`-Shader für den weißen NowPlaying-Screen verwendet werden.

Für diese Punkte sind der echte Poweramp-Screenshot und Emulator-Vergleiche maßgeblich.

Die ausführliche Analyse steht in:

- `docs/design/WISSEN_POWERAMP_OFFTRACK_2026-08-07.md`, Abschnitt 49
- `docs/STATUS_FORTSCHRITT.md`, Abschnitt P1
- `ui-test/POWERAMP_ONBOARDING_REVIEW.md`, Abschnitt 3 — inzwischen
  [`qa/POWERAMP_ONBOARDING_REVIEW.md`](../qa/POWERAMP_ONBOARDING_REVIEW.md)

## 6. Betroffene FlowRep-Dateien

Primär:

- `feature/player/src/main/kotlin/com/dropsync/feature/player/NowPlayingScreen.kt`
- `core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/chart/Waveform.kt`
- `app/src/main/kotlin/com/dropsync/app/DropSyncApp.kt`
- `feature/player/src/main/kotlin/com/dropsync/feature/player/PlayerViewModel.kt`

Unterstützend:

- `core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/component/CoverArtLoader.kt`
- `core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/component/CoverImage.kt`
- `feature/player/src/main/res/values/strings.xml`
- `feature/player/src/main/res/values-de/strings.xml`
- [`design/reference/poweramp_live_09_player_started.png`](../design/reference/poweramp_live_09_player_started.png)
- [`design/reference/poweramp_live_12_player_progress.png`](../design/reference/poweramp_live_12_player_progress.png)
- [`design/reference/poweramp_live_13_player_paused.png`](../design/reference/poweramp_live_13_player_paused.png)
- [`design/reference/poweramp_live_14_player_seek.png`](../design/reference/poweramp_live_14_player_seek.png)
- [`design/reference/poweramp_live_15_player_next.png`](../design/reference/poweramp_live_15_player_next.png)
- [`design/reference/poweramp_live_20_player_baseline.png`](../design/reference/poweramp_live_20_player_baseline.png)
- [`design/reference/poweramp_live_31_artwork_swipe_left.png`](../design/reference/poweramp_live_31_artwork_swipe_left.png)
- [`design/reference/nowplaying_poweramp_calibrated.png`](../design/reference/nowplaying_poweramp_calibrated.png)
- [`design/reference/current_nowplaying_2026-08-26.png`](../design/reference/current_nowplaying_2026-08-26.png) — Stand von FlowRep zum Zeitpunkt dieses Handoffs

Die Referenzbilder lagen ursprünglich unter `ui-test/screenshots/`. Dieses
Verzeichnis ist seit 2026-08-30 nicht mehr versioniert (42 MB Emulator-
Diagnostik, siehe `.gitignore`); die für dieses Handoff nötigen Bilder sind
nach `docs/design/reference/` kuratiert worden. Die Auswertungen der
damaligen Sitzung liegen als Dokumente unter `qa/`.

Die aktuellen Produktänderungen sind nicht auf diese Dateien begrenzt. `git status` zeigte zusätzlich Änderungen an mehreren Gradle-Dateien, `PlayerViewModel`, Ressourcen, Tests sowie untracked `ui-test/`-Artefakte. Diese Änderungen stammen aus dem bisherigen Arbeitsstand und dürfen nicht pauschal gelöscht werden.

## 7. Aktuelle problematische Implementierung

### `NowPlayingScreen.kt`

Die aktuelle Implementierung enthält zwar:

- weiße Oberfläche;
- kreisförmiges Cover;
- Blur-Hintergrund;
- Titel und Artist;
- Overflow-Menü;
- Repeat/Shuffle/Queue-Optionen;
- zentrale Play-/Pause-Taste;
- laufende Waveform-Komponente;
- Artwork-Pager;
- vertikales Dismiss;
- Marker-Dialoge.

Sie weicht aber weiterhin von der Referenz ab oder muss kritisch überprüft werden:

1. Der Blur-Hintergrund ist trotz weißem Referenzscreen als sichtbare Fläche aktiv. Der Nutzer wollte eine unscharfe Version des Coverbildes als Hintergrund, aber der Effekt darf die fast weiße Poweramp-Komposition nicht in eine getönte Fläche verwandeln.
2. Die Shell-/Navigation-Integration wurde mehrfach verändert. Es muss geprüft werden, ob die untere Navigation im richtigen Bereich liegt und nicht Waveform, Zeiten oder Systemleiste überdeckt.
3. Die Optionsreihe hat aktuell drei sichtbare Controls (`Queue`, `Repeat`, `Shuffle`), während der beobachtete Poweramp-Screen vier Positionsslots hatte. Sleep Timer und Visualizer bleiben aus dem Produktumfang entfernt. Die vierte sichtbare Funktion muss mit dem Nutzerziel abgeglichen werden, darf nicht einfach als Sleep Timer/Visualizer zurückkommen.
4. Der Titel-/Metadatenblock und das Overflow-Menü müssen anhand eines aktuellen Screenshot-Vergleichs kalibriert werden, nicht nur anhand von dp-Schätzungen.
5. Die untere Navigation verwendet teilweise vorhandene FlowRep-Icons und muss visuell mit den vier Poweramp-ähnlichen Slots verglichen werden.
6. Die aktuelle Layoutstruktur verwendet `Column` und feste dp-Höhen. Auf dem Referenzgerät muss per Screenshot geprüft werden, ob Cover, Titel, Optionen, Waveform, Zeiten und Bottom-Navigation exakt gleichzeitig sichtbar sind.
7. Es gibt weiterhin funktionale Zusatzlogik für Marker, Queue und Menüs. Diese darf die Referenzoptik nicht durch sichtbare Extra-Labels oder Cards verändern.

### `Waveform.kt`

Die aktuelle `RunningWaveform`:

- nimmt `buckets` und einen `progressFraction`;
- berechnet beim Zeichnen ein Fenster `start..end`;
- sampelt für jeden sichtbaren Balken erneut einen globalen Bucket-Index;
- zeichnet obere Balken plus Spiegelung darunter;
- unterstützt Tap, Drag, Long Press und Marker.

Das ist näher an der Anforderung als die frühere Vollansicht, entspricht aber noch nicht sicher dem Poweramp-Prinzip aus `Waveseek`: vorbereitete Track-Geometrie plus laufender Positionsoffset. Prüfen/verbessern:

- Geometrie aus Trackdaten einmal vorbereiten;
- sichtbaren Bereich per stabiler Geometrie, Offset und Clip darstellen;
- keine komplett neue Abtastung/Erzeugung der sichtbaren Form bei jeder Recomposition;
- Position unabhängig von der Geometrie frame-synchron aktualisieren;
- Scrub-Vorschau beim Drag sofort anzeigen;
- `seekTo` erst bei `UP` committen;
- `CANCEL` ohne unbeabsichtigten Commit behandeln;
- Marker separat als Overlay zeichnen;
- bei fehlender Analyse einen stabilen sichtbaren Fallback statt eines leeren oder irreführenden Zustands zeigen.

## 8. Funktionsanforderungen

Beibehalten und prüfen:

- Play/Pause.
- Previous/Next Track.
- Repeat-Zustände.
- Shuffle-Zustand.
- Queue öffnen und Titel auswählen.
- Artwork horizontal wischen, um den Track zu wechseln.
- Waveform-Tap zum Seek.
- Waveform-Drag mit Vorschau und Commit beim Loslassen.
- Marker setzen, verschieben und löschen, sofern diese Funktion im Produktumfang bleiben soll.
- Vertikal nach unten wischen, um NowPlaying zu schließen, sofern diese Geste tatsächlich gewünscht bleibt.
- Playback beim Schließen zur Bibliothek erhalten.
- Sleep Timer und Visualizer nicht anzeigen.

Jede sichtbare Schaltfläche braucht eine sinnvolle Accessibility-Beschreibung. Zustände wie Repeat, Shuffle und Play/Pause müssen semantisch erkennbar sein.

## 9. Empfohlener Arbeitsablauf

1. Aktuellen `git diff` der NowPlaying-relevanten Dateien sorgfältig lesen. Keine fremden Änderungen verwerfen.
2. Poweramp-Referenzscreens direkt öffnen und als visuelle Primärquelle verwenden.
3. FlowRep-APK auf `emulator-5554` installieren oder den aktuellen Dev-Server/Build-Weg des Projekts nutzen.
4. Einen realen Titel öffnen, vorzugsweise `adore u`.
5. Screenshot der aktuellen FlowRep-Ansicht erstellen.
6. Referenz und aktuelle Ansicht anhand dieser Punkte nebeneinander vergleichen:
   - Covergröße und vertikale Position;
   - Weißraum zwischen Cover, Titel und Optionen;
   - Titel-/Artist-Größe und Farbe;
   - Overflow-Position;
   - Optionsslots und Icon-Größe;
   - Waveform-Breite, Balkenbreite, Abstand, Höhe und vertikale Spiegelung;
   - Größe/Position des Play-Kreises;
   - Zeitpositionen;
   - Abstand zur unteren Navigation und System-Navigationsleiste.
7. Erst danach gezielt Code ändern.
8. Nach jeder größeren Änderung APK neu bauen, installieren und Screenshot abnehmen.
9. Interaktionen separat testen, damit Layoutkorrekturen keine Gesten zerstören.
10. Keine Abschlussbehauptung ohne aktuellen Screenshot der tatsächlich installierten APK.

## 10. Definition of Done

Die Arbeit ist erst abgeschlossen, wenn alle Punkte erfüllt sind:

- [ ] Aktueller Screenshot zeigt weißen Poweramp-ähnlichen Screen ohne Cards oder dunkle Playerfläche.
- [ ] Cover ist groß, exakt kreisförmig und in der richtigen vertikalen Position.
- [ ] Hintergrund zeigt nur dezent die unscharfe Coverversion und bleibt insgesamt nahe an Weiß.
- [ ] Titel ist cyanblau, zentriert und passend groß.
- [ ] Interpret/Metadaten stehen grau darunter.
- [ ] Overflow-Kreis sitzt rechts im Titelbereich.
- [ ] Sleep Timer und Visualizer fehlen sichtbar und in Accessibility.
- [ ] Optionsreihe entspricht der vereinbarten Poweramp-Anordnung und besitzt keine unnötigen Card-Hintergründe.
- [ ] Waveform zeigt einen laufenden sichtbaren Ausschnitt statt einer statischen Gesamtübersicht.
- [ ] Waveform besteht aus dünnen vertikalen oberen Balken und blasser Spiegelung unten.
- [ ] Waveform-Form bleibt während des Playback-Laufs stabil und bewegt sich zeitlich nachvollziehbar.
- [ ] Play-/Pause-Kreis liegt zentral über der Waveform und hat Referenzgröße.
- [ ] Zeit links/rechts ist sichtbar und korrekt.
- [ ] Untere App-Navigation und native Systemleiste überdecken keine Playerinhalte.
- [ ] Artwork-Swipe wechselt tatsächlich den aktiven Titel.
- [ ] Play/Pause, Repeat, Shuffle, Previous/Next und Queue funktionieren.
- [ ] Waveform-Tap/Drag/Cancel funktionieren korrekt.
- [ ] Keine neuen Crashs oder `FATAL EXCEPTION`-Einträge.
- [ ] Relevante Player-/Waveform-Tests und `:app:assembleDebug` sind grün.
- [ ] Ein aktueller Emulator-Screenshot wird im Handoff/Abschluss verlinkt.

## 11. Wichtige Warnung

Die vorherige Arbeit hat mehrfach aus einzelnen erfolgreichen Interaktionen abgeleitet, dass der Screen fertig sei. Das war falsch, weil die visuelle Abweichung trotz funktionierender Controls bestehen blieb. Die nächste KI soll visuelle Abnahme und funktionale Abnahme getrennt behandeln und die visuelle Referenz höher gewichten als frühere Abschlussnotizen.
