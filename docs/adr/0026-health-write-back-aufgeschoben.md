# ADR-0026: Health-Write-back aufgeschoben, Health bleibt in V1 lesend

Datum: 2026-09-23
Status: Akzeptiert (Nutzerentscheidung im Analyse-Paket 6)

## Problem

Die Analyse vom 23.09.2026 (Befund 6.4, Paket 6 Punkt 36) stellt die
Frage: Health Write-back als Roadmap-Entscheidung. Der Ist-Stand ist
rein lesend — `:data:health` liest die Herzfrequenz aus Health Connect
(`READ_HEART_RATE`, `data/health/AndroidManifest.xml`), schreibt aber
nichts zurueck: kein Training, keine Sessions, keine Sets.

## Optionen

1. **Write-back in V1:** Trainings und Saetze nach Health Connect
   schreiben (ExerciseSession, TrainingRecord).
2. **Aufschieben:** V1 bleibt lesend; Write-back als eigene spätere
   Ausbaustufe mit eigenem ADR.

## Entscheidung

**Option 2 — aufschieben.** Begruendung:

- Write-back erfordert eine neue sensible Permission
  (`WRITE_EXERCISE`-Klasse) und damit einen neuen Abnahme-Bereich im
  `HARDWARE_TESTPLAN.md` (Berechtigungen, Xiaomi-Fokus, Teil E). Der
  Lesepfad ist bereits so viel Aufwand, dass der Write-Pfad die
  Release-Kriterien verschieben wuerde.
- Der Produktwert in V1 liegt im Lesen (Puls waehrend des Trainings
  anzeigen); das Zurueckschreiben ist ein Komfort-Feature fuer
  Health-Connect-Nutzer ohne direkten Trainingsnutzen.
- Der Schreibpfad ist ohne Datenverlust nachlieferbar: die flachen
  Satz-Logs (`flat_sets`) bleiben die Quelle; ein spaeterer Export kann
  die Historie komplett schreiben.

## Folgen

- Befund 6.4 bleibt fuer den Write-back-Teil offen und ist bewusst
  aufgeschoben; die Fehlerdifferenzierung (`AppError.Unknown`) wird mit
  dem Write-Pfad zusammen ueberarbeitet.
- Kein Code-Aenderung durch diesen ADR.
- Ein spaeterer Write-back braucht einen eigenen ADR mit
  Permission-/Abnahme-Plan.
