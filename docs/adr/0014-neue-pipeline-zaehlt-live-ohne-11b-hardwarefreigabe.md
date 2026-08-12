# ADR-0014: Neue Zaehl-Pipeline zaehlt live, ohne Abschnitt-11b-Hardwarefreigabe

Datum: 2026-08-12
Status: Akzeptiert

## Problem

`FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` Abschnitt 9 (Phase 4,
Schritt 6) und Abschnitt 11b verlangen, dass die neu nach Kotlin
portierte `ExerciseEnginePipeline` (PeakDetector/PhaseValidator/
TemplateMatcher/QualityScorer) ausschliesslich im Shadow-Modus laeuft,
bis 5 definierte Freigabe-Szenarien auf echter M5StickC-Hardware mit
Rep-Diff = 0 bestanden sind ("zaehlt sichtbar nirgends live").

Tatsaechlich instanziiert `TrainViewModel.startCountedSet()`
(`feature/workout/.../TrainViewModel.kt`) dieselbe `ExerciseEnginePipeline`-
Klasse auch als `liveEngine` und speist ihren Zaehlstand direkt in
`_repsInput`/`_liveCountedReps` — die Pipeline zaehlt seit Phase 4
(Commit `6e0a7f9`) live, ohne dass eine der 5 Szenarien je auf Hardware
gelaufen ist und ohne dass der vorausgesetzte Befund-C-Fix (flowrep,
Commit `79ee710`) mit einem echten `flutter test`-Lauf statt nur
Code-Audit verifiziert wurde. Eine eigenstaendige Portierung der alten,
signed-gP-projektionsbasierten WorkoutEngine als separater Live-Pfad —
wie in Schritt 3 vorgesehen — existiert nicht; nur einzelne Bausteine
davon (`SignalProcessor.signedGyroProjection()`) liegen unbenutzt im
Code.

## Optionen

1. Alte WorkoutEngine (signed gyro-projection, gP) vollstaendig nach
   Kotlin portieren und als `liveEngine` einsetzen; `ExerciseEnginePipeline`
   bleibt bis zur 11b-Freigabe strikt shadow-only.
2. Abweichung von Abschnitt 11b formal akzeptieren: die neue Pipeline
   zaehlt live, mit `confirmedRepsEdited` (Shadow-Diff-Harness-Plan,
   Ground-Truth-Regel D3) als Sicherheitsnetz gegen stille
   Selbstbestaetigung, bis echte Hardware-Laeufe vorliegen.
3. Live-Zaehlung deaktivieren (nur manuelle Eingabe), bis Option 1 oder
   die 5 Szenarien erledigt sind.

## Entscheidung

Option 2. Fusion- ist ein Solo-Projekt fuer den Eigengebrauch (ein
Nutzer, eigene Geraete, kein Vertrieb) — der Aufwand einer vollstaendigen
WorkoutEngine-Zweitportierung (Option 1) steht in keinem Verhaeltnis zum
Nutzen fuer genau einen Nutzer, und Option 3 wuerde eine bereits
funktionierende Kernfunktion ohne konkreten Anlass abschalten. Die
Abweichung wird hiermit formal dokumentiert statt stillschweigend
hingenommen — analog zu ADR-0013's Umgang mit widerspruechlichen
Dokumenten. Das D3-Sicherheitsnetz aus dem Shadow-Diff-Harness-Plan (nur
aktiv editierte `confirmedReps` zaehlen als unabhaengige Wahrheit,
niemals unveraendert uebernommene Vorbefuellung) mindert das Hauptrisiko
der stillen Selbstbestaetigung, ersetzt aber nicht die 5
Freigabe-Szenarien als eigentliches Ziel.

## Folgen

- Abschnitt 11b's Formulierung ("zaehlt sichtbar nirgends live") gilt ab
  diesem Datum als durch diese ADR ueberschrieben, nicht als aktuell
  bindend. Ein Leser des Design-Dokuments muss diese ADR kennen, um
  Abschnitt 11b korrekt einzuordnen.
- Die 5 Freigabe-Szenarien aus Abschnitt 11b bleiben das Ziel, sind aber
  keine Voraussetzung mehr fuer die Live-Zaehlung — sie werden
  nachtraeglich nachgeholt, sobald der Shadow-Diff-Harness-Plan
  umgesetzt ist.
- Der Befund-C-Fix (flowrep, Commit `79ee710`) sollte weiterhin mit
  einem echten `flutter test`-Lauf verifiziert werden — offener Punkt,
  unabhaengig von dieser ADR.
- `confirmedRepsEdited` (TrainViewModel) ist ab jetzt sicherheitsrelevant
  fuer die Ground-Truth-Qualitaet des Shadow-Diff-Harness und muss vor
  jeder Aenderung an `setReps()`/`stopCountedSet()`/`applyCorrection()`
  mitgedacht werden.
- Keine Code-Aenderung durch diese ADR selbst; sie dokumentiert den seit
  Phase 4 bestehenden Ist-Zustand formal.
