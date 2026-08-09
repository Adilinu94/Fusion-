# ADR-0013: Fusionsdesign hebt Tab- und Feature-Grundsätze aus FLOWREP_DESIGN_PLAN.md auf

Datum: 2026-08-09
Status: Akzeptiert

## Problem

`FLOWREP_DESIGN_PLAN.md` (reines Rebrand-Projekt, alle Phasen abgeschlossen)
legt als Grundsatz fest: "3-Tab-IA (MUSIC/TRAINING/SETTINGS) bleibt" und
"keine neuen Fitness-Features". Das spaetere
`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` verlangt eine
4-Tab-Struktur (Train/Music/Verlauf/Einstellungen) und umfangreiche neue
Fitness-Funktionalitaet (kompletter Sensor-Stack, Zaehl-Pipelines,
Kalibrierung). Beide Dokumente lagen bisher unkommentiert nebeneinander,
ohne dass der Widerspruch irgendwo aufgeloest war.

## Optionen

1. Fusionsdesign zurueckstellen, Grundsaetze aus FLOWREP_DESIGN_PLAN
   unveraendert lassen.
2. Die zwei konkret widerspruechlichen Grundsaetze (Tab-Anzahl, keine
   neuen Fitness-Features) aus FLOWREP_DESIGN_PLAN fuer die Fusion
   aufheben, Rest unveraendert.
3. FLOWREP_DESIGN_PLAN.md vollstaendig neu schreiben.

## Entscheidung

Option 2. Das Fusionsdesign definiert in seiner eigenen Geltungsordnung
bereits: "Dieses Design-Dokument ist die oberste Referenz fuer
Produktentscheidungen. Die beiden Handbuecher setzen es technisch um und
duerfen es nicht widersprechen." Diese ADR vollzieht das fuer die zwei
konkret widerspruechlichen Punkte formal nach, statt den Widerspruch
unkommentiert im Dokumentenbestand stehen zu lassen. Alle anderen Inhalte
aus `FLOWREP_DESIGN_PLAN.md` (Name/Paket/Farbschema/Typografie/Icon,
Phasen A-G) bleiben unveraendert gueltig — die dort umgesetzte
Rebrand-Arbeit (Anzeige "FlowRep", Paket `com.dropsync`) ist inhaltlich
deckungsgleich mit dem, was das Fusionsdesign an anderer Stelle ohnehin
festlegt.

## Folgen

- `FLOWREP_DESIGN_PLAN.md`s Grundsaetze "3-Tab-IA bleibt" und "keine
  neuen Fitness-Features" gelten ab diesem Datum als historisch, nicht
  als aktuell bindend. Ein Leser dieses Dokuments muss diese ADR kennen,
  um es korrekt einzuordnen.
- Keine Aenderung an Code, Architektur oder Datenmodell — reine
  Dokumentations-ADR, analog zu ADR-0010.
- Massgeblich fuer Tab-Struktur und Fitness-Funktionsumfang bleibt ab
  sofort ausschliesslich `FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md`.
