# ADR-0016: Flowtimer-Integration hebt Punkte des Fusionsdesigns auf

Datum: 2026-08-22
Status: Akzeptiert

Nummer 0015 ist im `WAVEFORM_PERFORMANCE_UMBAU_PLAN.md` (Phase 7) fuer
"Track-Analyse in zwei Stufen mit getrennter Cache-Versionierung"
vorbelegt; diese ADR nimmt daher 0016.

## Problem

`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` erklaert in
seiner Geltungsordnung: "Dieses Design-Dokument ist die oberste Referenz
fuer Produktentscheidungen." Das neue
`docs/design/2026-08-22-flowtimer-integration-design.md` aendert
Produktentscheidungen, die dort getroffen wurden, ohne den Vorrang
aufzuloesen:

1. Der Verlauf-Tab wird vom reinen Satz-Verlauf zu einem
   Progress-Dashboard mit Umschaltung "Uebersicht | Verlauf". Das
   Fusionsdesign legt fuer Verlauf nur "Today, Woche, Monat, PRs und
   Uebungsverlauf" fest, ohne Streak, Wochenziel oder Ziel-Status.
2. Die Uebungsverwaltung erhaelt Ziele (Zielgewicht + Ziel-Reps) und
   vollstaendiges CRUD mit Archivieren/Wiederherstellen. Das
   Fusionsdesign kennt keine Ziele.
3. Das Fusionsdesign schliesst in Prinzip 6 "keine Sessions" aus. Die
   Flowtimer-Integration definiert dagegen eine Workout-Zaehlung
   ("ein Kalendertag mit mindestens einem Satz = ein Workout") als
   Grundlage des Wochenziel-Rings. Das ist keine Session-Tabelle, aber
   eine Zaehlgroesse, die das Fusionsdesign nicht vorsieht.
4. Ein Teil der Trainingsmathematik zieht in ein externes Repo
   (`training-core`), das per Submodule eingebunden wird. Das
   Fusionsdesign beschreibt eine geschlossene Modulstruktur ohne
   externe Quellcode-Abhaengigkeit.

Zusaetzlich hat die Flowtimer-Integration ihre eigene Erstfassung an
sechs Stellen revidiert (siehe Abschnitt "Revisionen" dort). Ohne diese
ADR muesste jede spaetere Session die Widersprueche selbst aufloesen —
bei mehreren parallel arbeitenden Sessions die haeufigste Fehlerquelle.

## Optionen

1. Flowtimer-Integration zuruecksstellen, Fusionsdesign unveraendert
   lassen.
2. Die vier konkret widerspruechlichen Punkte fuer die
   Flowtimer-Integration aufheben, alles andere unveraendert lassen.
3. `FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` vollstaendig neu
   schreiben.

## Entscheidung

Option 2, analog zu ADR-0010 und ADR-0013.

Ab diesem Datum gilt fuer die vier genannten Punkte ausschliesslich
`docs/design/2026-08-22-flowtimer-integration-design.md`. Alle uebrigen
Inhalte des Fusionsdesigns bleiben unveraendert bindend — insbesondere
die 4-Tab-Struktur, der DropSync-Flow, die Sensor-Pipelines, die
Audio-Architektur und Prinzip 6 in seinen restlichen Punkten (nur kg,
kein Onboarding, keine Kamera, kein Export).

Die Umsetzungsentscheidungen zur Integration liegen nicht im
Design-Dokument, sondern in
`docs/design/2026-08-22-flowtimer-integration-CONTEXT.md` (E1 bis E9).
Bei Widerspruch zwischen Design-Dokument und CONTEXT-Datei gilt die
CONTEXT-Datei, weil sie juenger und gegen den Code geprueft ist.

Der visuelle Vertrag des Progress-Dashboards liegt in
`docs/design/2026-08-22-flowtimer-integration-UI.md` und ist dem
`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md` untergeordnet: er waehlt
aus dessen Tokens und Komponenten aus, definiert aber keine eigenen
Farb-, Schrift- oder Abstandswerte.

## Folgen

- Wer den Verlauf-Tab oder die Uebungsverwaltung anfasst, liest zuerst
  die Flowtimer-Integration, nicht das Fusionsdesign.
- Das Fusionsdesign behaelt seine Geltungsordnung fuer alles ausser den
  vier aufgezaehlten Punkten. Ein Leser muss diese ADR kennen, um es
  korrekt einzuordnen.
- Neu gegenueber ADR-0010 und ADR-0013: diese ADR hat Code-Folgen. Das
  Repo erhaelt eine externe Submodule-Abhaengigkeit
  (`training-core`, oeffentliches GitHub-Repo). `actions/checkout` in
  `.github/workflows/ci.yml` braucht `submodules: recursive`; ohne das
  ist das Verzeichnis leer und `settings.gradle.kts` bricht ab.
  Ein Deploy-Key ist nicht noetig — siehe den Nachtrag zu E3 in
  [`../design/2026-08-22-flowtimer-integration-CONTEXT.md`](../design/2026-08-22-flowtimer-integration-CONTEXT.md).
- `ModuleDependencyRulesTest` bekommt ein weiteres Feature-Modul
  (`:feature:progress`) in seine Pflichtliste. Die Liste dort ist
  bereits unvollstaendig (`health`, `sensor`, `audio` fehlen) und wird
  bei der Gelegenheit vervollstaendigt.
- Die Rangfolge der Dokumente lautet ab jetzt, von oben nach unten:
  1. `2026-08-22-flowtimer-integration-CONTEXT.md` (Umsetzung, geprueft)
  2. `2026-08-22-flowtimer-integration-design.md` (Was, fuer die vier Punkte)
  3. `FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (alles uebrige)
  4. `FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md` (visuell, ueberall bindend)
