# DropSync-Timer — vollständiger Import

**Quelle:** github.com/Adilinu94/DropSync-Timer @ `d7845d0f8777cef26774a5ccc7d90dfd823afbe6` (main, 2026-08-09)
**Methode:** vollständige Arbeitsbaum-Kopie (`cp -a`, ohne `.git` — kein
`git subtree`, um konsistent mit dem bereits bestehenden Präzedenzfall in
die andere Richtung zu bleiben: DropSync-Timer selbst hat FlowRep-Material
am 2026-08-08 per einfacher Datei-Kopie nach
`docs/archive/flowrep-import/` gespiegelt, gleiche Methode hier
spiegelbildlich angewendet).
**Warum kein `git subtree`:** hätte DropSync-Timers vollständige, von
diesem Repo unabhängige Commit-Historie in die FlowRep-Historie
eingewoben — bei einem Repo mit aktiver paralleler Multi-Session-Arbeit
(siehe `docs/archive/umbauplan/STATUS_FORTSCHRITT.md`) ein größeres,
schwerer rückgängig zu machendes strukturelles Risiko als eine einfache
Kopie. Wer die volle Commit-Historie von DropSync-Timer braucht: Quelle
oben ist unverändert unter der genannten URL/Commit abrufbar.

**Umfang:** wirklich vollständig, kein kuratierter Ausschnitt (anders als
der `flowrep-import`-Ordner auf DropSync-Timer-Seite, der bewusst nur
Parser/Engine/Screens/aktive Docs enthält) — 539 Dateien, alle Module
(`app`, `core`, `data`, `domain`, `feature`, `gradle`), alle
Wurzel-Dokumente (inkl. `AUDIO_ENGINE_AUSBAU_PLAN.md`,
`OFFTRACK_AUDIO_UMBAUHANDBUCH.md`, `UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md`
u. a. — siehe Hinweis unten zu einem davon), UI-Assets, CI-Workflow.

**Kontext:** entspricht inhaltlich "Phase 0 – Repo Merge und Fundament"
aus `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (dort noch
als "noch nicht begonnen" vermerkt, Stand DropSync-Timers eigener
`docs/STATUS_FORTSCHRITT.md` vom 2026-08-09) — hier von der FlowRep-Seite
aus begonnen, auf Adis direkte Anweisung.

**Nicht getan, bewusst offen gelassen:**
- Keine Build-System-Integration (`app/build.gradle.kts` von FlowRep und
  `dropsync-timer/build.gradle.kts` bestehen unverbunden nebeneinander —
  das ist die eigentliche, viel größere Aufgabe aus Phase 0/1 des
  Design-Dokuments, hier nur die Dateibasis dafür geschaffen).
- Keine Deduplizierung mit `docs/archive/flowrep-import/` auf
  DropSync-Timer-Seite (dort liegt bereits eine kuratierte Teilmenge des
  *flowrep*-Repos — spiegelbildlich zu diesem Ordner, aber andere Richtung
  und anderer Umfang).
- **Hinweis, kein Fix:** dieser Import enthält unter
  `dropsync-timer/docs/design/` denselben Foliensatz reverse-engineerter
  Poweramp/Offtrack-Interna
  (`WISSEN_POWERAMP_OFFTRACK_REVERSE_ANALYSE_2026-08-07.md`), zu dem in
  diesem Repo bereits an anderer Stelle Bedenken vermerkt wurden — hier
  nur dupliziert, nicht neu bewertet oder entfernt.

**Verifikation:** keine — reiner Dateitransfer, kein Flutter- oder
Gradle-Lauf in dieser Sandbox möglich/nötig für diesen Schritt.
