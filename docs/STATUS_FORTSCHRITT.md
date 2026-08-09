# STATUS & FORTSCHRITT – FlowRep x DropSync Fusion

**Zweck:** Lebendiges Koordinationsdokument, da mehrere Claude-Instanzen
parallel an der Fusion arbeiten koennen. Wird bei jeder Session als
Erstes gelesen (vor Phase 0), und nach jedem Arbeitsschritt aktualisiert.
Massgeblich fuer Produktentscheidungen ist
`docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (siehe dessen
eigene Geltungsordnung) — dieses Dokument haelt nur den Fortschritt fest.

Bis 2026-08-09 gab es keine Koordinationsdatei fuer die Fusion in diesem
Repo. Die entsprechende Datei fuer den fruehereren FlowRep-Umbauplan
liegt archiviert unter `flowrep`-Repo,
`docs/archive/umbauplan/STATUS_FORTSCHRITT.md` — thematisch nicht mehr
aktuell fuer die Fusion, aber als Beispiel fuer den Dokumentationsstil
frueherer Sessions brauchbar.

**Regeln für jede Session:**

1. Vor Beginn einer Aufgabe: passende Zeile auf `[~] in Arbeit (Session: <Kennung>, <Datum>)` setzen.
2. Nach Abschluss: `[x] erledigt (Session: <Kennung>, <Datum>) – <1-Zeilen-Ergebnis>`.
3. Nie eine fremde `[~]`-Zeile ohne Rücksprache überschreiben oder als erledigt markieren.
4. Session-Kennung: `Claude-<8-stelliger-Zufalls-Hex>`, erzeugbar z. B. via `openssl rand -hex 4`.

Legende: `[ ]` offen · `[~]` in Arbeit · `[x]` erledigt · `[!]` blockiert / braucht Entscheidung von Adi

---

## A. Vorarbeiten am Design-Dokument (vor Phase 0)

- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Widerspruch zwischen Abschnitt 9 (alter Phasenplan, Phasen 0-10) und Abschnitt 12 (überarbeitete Reihenfolge nach Super-KI-Review, Phasen 0-12) aufgelöst: Abschnitt 9 komplett auf die Abschnitt-12-Struktur umgeschrieben, Auto-Drop-Erkennung von alter Phase 5 in neue Phase 12 verschoben.
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Verwaisten alten Dokumentschluss (doppelte Abschnitte 11–13 aus der Zeit vor dem Super-KI-Review, u. a. mit "Phase 0 starten, dann 1 bis 10" statt 1 bis 12) entfernt.
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Kopf-Block (Tabelle "Verbundene Dokumente" + Geltungsordnung) fehlte in dieser Repo-Kopie des Design-Dokuments, aus der flowrep-Kopie nachgetragen. Beide Kopien danach byte-identisch (verifiziert per `diff`).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Phase 4: neue Zähl-Pipeline (ExerciseEngine/PeakDetector/PhaseValidator/TemplateMatcher) als zusätzlicher Shadow-Port ergänzt (Adi-Entscheidung), Befund-C-Fix als Vorbedingung referenziert, neuer Abschnitt 11b mit Shadow-DoD-Checkliste (5 Freigabe-Szenarien, modelliert nach `DIRECTIONAL_GP_SHADOW_ROLLOUT_2026-07-27.md`).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – Phase 2: expliziter Löschauftrag für vorhandenen Routinen-Code ergänzt (`RoutineScreens`/`RoutineViewModels`/`RoutineExpander`/`ProgressAnalysis`/`ProgressScreens`/`ProgressViewModels`, Adi-Entscheidung).
- [x] erledigt (Session: Claude-936c6f89, 2026-08-09) – ADR-0013 geschrieben: hebt die "3-Tab / keine neuen Fitness-Features"-Grundsätze aus `FLOWREP_DESIGN_PLAN.md` formal für die Fusion auf, analog zu ADR-0010.
- [ ] Phase 0 noch nicht begonnen. Nächster Schritt laut Design-Dokument Abschnitt 15: `docs/plans/YYYY-MM-DD-fusion-foundation-plan.md` schreiben, danach Phase 0.

---

## A1. Kollision entdeckt und aufgelöst (2026-08-09)

- [x] **Wichtig für alle künftigen Sessions (Session: Claude-936c6f89, 2026-08-09):** Parallel zu dieser Doku-Arbeit hat eine andere Session bereits mit echter Umsetzung begonnen, Branch `fusion/foundation` (Autor "Claude (Entwurf, ungeprüft)"): FlowRep-Referenzmaterial nach `docs/archive/flowrep-import/` gespiegelt + CI-Workflow (`.github/workflows/ci.yml`) eingerichtet (inkl. Fix für `gradlew`-Ausführungsrecht) — **beides bereits in `main`**, unproblematisch. **Zusätzlich** enthält `fusion/foundation` einen dritten, noch nicht gemergten Commit (`9692b6f`), der Abschnitt 9 (Phasenplan) fast komplett löscht (nur noch Verweis auf Abschnitt 12, keine Einzelschritte mehr) — das ist eine **andere Lösung für dasselbe Problem**, das diese Session per vollständiger Umsortierung gelöst hat (siehe A oben). Adi hat sich für die ausführliche Version entschieden (Einzelschritte pro Phase bleiben erhalten). **Für die nächste Session, die `fusion/foundation` weiterführt: Commit `9692b6f` NICHT nach `main` mergen** — würde die jetzt in `main` stehende ausführliche Abschnitt-9-Fassung wieder überschreiben. Die anderen `fusion/foundation`-Commits (Archiv-Spiegel, CI) sind davon nicht betroffen und bereits sicher in `main`.
- [x] Branch `docs/fix-fusion-design-doc` (ein dritter, unabhängiger Versuch, nur den doppelten Dokumentschluss zu entfernen) ist damit ebenfalls überholt — Inhalt ist jetzt vollständig in `main` enthalten, Branch kann gelöscht werden.
- [!] **Grundursache:** Bis zu diesem Eintrag gab es keine gemeinsame Koordinationsdatei in diesem Repo, wodurch zwei Sessions unabhängig am selben Problem gearbeitet haben, ohne es zu wissen. Ab jetzt: vor Beginn jeder Aufgabe diese Datei lesen und `git fetch --prune` gegen alle Branches, nicht nur `main`.

---

## B. Offene Punkte aus dem Plan-Review (noch nicht adressiert)

- [!] Datenbank-Verschlüsselung: FlowRep verschlüsselt lokal (`sqlite3mc`), die fusionierte App bewusst nicht (Adi-Entscheidung, 2026-08-09) — kein weiterer Handlungsbedarf, hier nur zur Nachvollziehbarkeit protokolliert.
- [!] Instrumentierte Tests (`androidTest`) existieren in diesem Repo noch nicht als Grundgerüst — nötig für mehrere Verifikationskriterien in Abschnitt 11, bisher nicht als eigener Schritt im Phasenplan berücksichtigt.
- [ ] Automatisierte Vergleichs-/Diff-Logik zwischen Shadow- und Live-Zählung existiert noch nicht (nur CSV-Aufnahme) — Voraussetzung für die Freigabe-Szenarien in Abschnitt 11b.
