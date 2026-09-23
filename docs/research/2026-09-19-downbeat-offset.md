# Downbeat-Offset fuer den Beat-Snap: Recherche und Umsetzungsvorschlag (RC-22)

Stand: 2026-09-19. Anlass: Der Beat-Snap in `MarkerSnapping` rastet auf ein
Beat-Raster ab 0 ms (der Code nennt das selbst Spekulation,
`MarkerSnapping.kt:19-21`). Bei 128 BPM ist ein halber Beat 234 ms — mehr
als das gesamte Audio-Latenzbudget (Tiefenrecherche Abschnitt 3.4, D2).
Diese Notiz klaert, wie der Downbeat-Offset ohne ML und offline bestimmbar
ist.

## Was die Recherche ergeben hat

Gesucht wurde mit `orx discover keyword/embedding` (arXiv-Korpus) und
`hpr scholar search` (OpenAlex/Crossref). Das Feld ist heute klar
ML-dominiert:

- **BeatNet, Beat Tracking as Object Detection (arXiv 2510.14391), DBNs**:
  neuronale Modelle mit sehr guten Werten auf gaengigen Datensaetzen.
- **The SMC Blind Spot (arXiv 2605.12287)**: genau deshalb relevant — auf
  dem SMC-Datensatz erzeugen moderne Beat-Tracker "confident-but-wrong"-
  Aktivierungen, mit drei Fehlerklassen (Oktavfehler, Kontinuitaetsfehler,
  Totalausfall < 0,3 F-Mass); der Standard-DBN verhindert bei 21 % der
  Tracks das korrekte Tempo (erzwingt Doppeltempo bei langsamer Musik).
- **Zehren et al. (Computer Music Journal 46(3), 2022)** und
  **EDMFormer (arXiv 2603.08759)**: Downbeat-Erkennung ist Kernbestandteil
  professioneller Cue-Point-Erkennung; EDM-Annotationen gelten mit
  **±0,5 s** als Referenzqualitaet.

**Schlussfolgerung:** Ein neuronales Downbeat-Modell waere erstens ein
Bruch mit dem Grundsatz "bewusst klassische Signalverarbeitung, kein ML"
(`OnsetDetection.kt:6-11`), zweitens laut SMC-Befund nicht einmal
zuverlaessig, und drittens ist die Grundwahrheit selbst nur auf ±0,5 s
bestimmt. Der Snap braucht keine perfekte Downbeat-Erkennung — er braucht
einen **besseren Startphasen-Offset als 0 ms**.

## Vorschlag: Low-Band-Phasenheuristik (offline, klassisch, pruefbar)

1. Das Beat-Raster existiert bereits aus der BPM-Analyse
   (`TempoAccumulator`, Beat-Periode = 60/BPM).
2. Fuer die vier Beat-Phasen (0, 1/4, 2/4, 3/4 der Periode) wird ueber
   alle Beats des Tracks die **niedrige Bandenergie** summiert
   (z. B. 40-120 Hz; die Analyse hat bereits Spektraldaten, ein
   Goertzel-Band genuegt). In 4/4-EDM traegt die Bassdrum den Downbeat.
3. Die Phase mit der hoechsten mittleren Energie ist der Downbeat-Kandidat;
   die Konfidenz ist das Verhaeltnis beste zu zweitbeste Phase. Liegt es
   unter einem Schwellwert, bleibt der Snap **aus** (Stille vor Vermutung).
4. Der Offset wird als Zahl an der Analyse gespeichert (additive Spalte
   oder in-memory im `track_analysis`-Cache) und in `MarkerSnapping`
   addiert — der Snap selbst bleibt optional und abschaltbar.

**Kosten:** ein zusaetzlicher Band-Akkumulator im bestehenden
Analyse-Durchgang (kein zweiter Decode), eine Spalte, wenige Zeilen im
Snap. **Risiko:** gering; ohne validen Offset verhaelt sich alles wie
heute.

## Verifikation

- Synthetischer Test: 4/4-Klick mit Bassdrum auf Schlag 1, drei Phasen
  ohne Bass -> erkannte Phase = 1.
- Corpus-Test: manuell annotierte Downbeats fuer 5-10 EDM-Titel
  (die ±0,5-s-Referenz aus EDMFormer genuegt fuer die Phasenwahl), dann
  Offset-Fehler in ms messen.
- Gegenprobe: Offset auf 0 erzwingen -> Snap-Fehler reproduzierbar
  groesser auf demselben Material.

## Einordnung

Das ist **Stufe 2/3 der Tiefenrecherche** (belegter Defekt D2), kein
Notfall. Der Snap ist heute optional; der Fehler wirkt nur, wenn er benutzt
wird. Bis zur Umsetzung bleibt der Hinweis im Code bestehen.

## Quellen

- `docs/research/RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md` Abschnitt 3.4
  (D2) und 3.6.
- arXiv 2605.12287 (SMC Blind Spot), arXiv 2510.14391 (Beat Tracking as
  Object Detection), arXiv 2603.08759 (EDMFormer) — gefunden mit
  `orx discover`.
- Zehren et al., Computer Music Journal 46(3), 2022 (Cue-Point-Erkennung
  mit Downbeat) — zitiert nach der Tiefenrecherche.
