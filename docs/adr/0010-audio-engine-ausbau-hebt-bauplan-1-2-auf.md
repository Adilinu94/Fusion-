# ADR-0010: Audio-Engine-Ausbau hebt Bauplan-Abschnitt 1.2 (Equalizer/Crossfade) auf

Datum: 2026-07-28
Status: Akzeptiert

## Problem

Bauplan Abschnitt 1.2 schliesst Equalizer und Crossfade fuer Version 1
ausdruecklich aus ("Diese Grenzen verhindern, dass Version 1 in mehrere
eigenstaendige Produkte zerfaellt"). Der seit ADR-0005 umgesetzte
Audio-Engine-Ausbau (Plan "DropSync Audio-Engine-Ausbau", Phasen 1-7)
implementiert beides vollstaendig. Ohne diese ADR widersprechen sich
das verbindliche Referenzdokument und der tatsaechliche Code dauerhaft.

## Optionen

1. Audio-Engine-Ausbau rueckgaengig machen, Bauplan 1.2 unveraendert lassen.
2. Bauplan 1.2 fuer Equalizer und Crossfade aufheben, Rest unveraendert.
3. Bauplan vollstaendig neu schreiben.

## Entscheidung

Option 2. Der Projektinhaber hat den Audio-Engine-Ausbau und die
Audiophile-Nischenfeatures (EQ, Crossfade, Bit-Perfect, FFmpeg-Formate)
als dauerhaften Projektbestandteil bestaetigt (28.07.2026). Bauplan
Abschnitt 1.2 gilt fuer "Equalizer" und "Crossfade" ab diesem Datum
nicht mehr; alle anderen Abschnitte, insbesondere die Architekturregeln
aus Abschnitt 3.2 und die fachlichen Regeln aus Abschnitt 5, bleiben
unveraendert verbindlich. Die Umsetzung selbst ist bereits durch
ADR-0005 bis ADR-0009 vollstaendig dokumentiert.

## Folgen

- Bauplan Abschnitt 1.2 gilt fuer diese zwei Punkte als historisch,
  nicht als aktuell bindend. Ein Leser des Bauplans muss diese ADR
  kennen, um den Code korrekt einzuordnen.
- Keine Aenderung an Architektur, Datenmodell oder den uebrigen
  Nicht-Zielen (Streaming, Konten, Cloud-Sync, Ernaehrung etc. bleiben
  ausgeschlossen).
