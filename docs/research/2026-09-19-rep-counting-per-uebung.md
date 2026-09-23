# Rep-Zaehlung: Parameter und Schwierigkeit pro Uebung (Recherche 3)

Stand: 2026-09-19. Anlass: Adi hat im Wizard die Recherche-Punkte 2 und 3
gewaehlt. Diese Notiz liefert die Per-Uebungs-Daten aus dem Volltext von
Lim & Lee (2024) und ordnet sie fuer die Gate-11b-Kampagne ein. Die tiefere
Gesamtanalyse steht in `RESEARCH_REPCOUNT_TTS_DROPSYNC_2026-09.md`
(Abschnitt 1.5 "Parameter pro Uebung, nicht global"); hier kommt die
konkrete Zahlentabelle dazu.

## Herkunft der Daten

- Volltext: `orx paper 2410.00407 --full` (OpenResearch-CLI, alphaXiv),
  lokal geprueft am 2026-09-19.
- Papier: Yooseok Lim, Sujee Lee, "Intelligent Repetition Counting for
  Unseen Exercises: A Few-Shot Learning Approach with Sensor Signals",
  arXiv 2410.00407 (2024).
- Literaturueberblick derselben Sitzung: `hpr scholar search` (OpenAlex/
  Crossref), u. a. Soro et al. 2019, ExerSense 2020, Burns et al. 2018,
  Hua et al. 2020, Wu et al. 2022.

## Tabelle 1 des Papiers: Bestes Fenster und Stride je Uebung

| Uebung | Reps | Sek./Rep | Fenster | Stride |
|---|---:|---:|---:|---:|
| Squat | 809 | 2,94 | 80 | 20 |
| Dips | 663 | 1,48 | 50 | 25 |
| Pull-Up | 812 | 2,20 | 80 | 10 |
| Fixed Lunge | 1120 | 2,16 | 80 | 50 |
| Barbell Row | 875 | 1,42 | 80 | 10 |
| Push-Up | 489 | 0,89 | 50 | 25 |
| Overhead Press | 1065 | 2,38 | 100 | 10 |
| Deadlift | 785 | 2,89 | 150 | 10 |
| Crunch | 670 | 0,90 | 50 | 10 |
| Back Extension | 718 | 2,18 | 100 | 20 |
| Thruster | 529 | 2,92 | 50 | 25 |
| Calf Raises | 844 | 1,59 | 80 | 10 |

**Befund 1:** Das optimale Fenster schwankt zwischen 50 und 150 Samples,
der Stride zwischen 10 und 50. Es gibt keinen globalen Wert — das stuetzt
RC-20 (DTW-/Qualitaets-Schwelle pro Uebung) und die bereits vorhandene
adaptive Refraktaerzeit.

## Tabelle 2 des Papiers: Guete je Uebung

| Uebung | Accuracy | Recall | Precision | F1 |
|---|---:|---:|---:|---:|
| Squat | 0,90 | 0,77 | 0,87 | 0,81 |
| Dip | 0,93 | 0,90 | 0,84 | 0,88 |
| Pull-Up | 0,84 | 0,79 | 0,82 | 0,81 |
| Fixed Lunge | 0,88 | 0,78 | 0,92 | 0,85 |
| **Barbell Row** | 0,80 | 0,67 | **0,56** | **0,61** |
| Push-Up | 0,82 | 0,96 | 0,68 | 0,80 |
| **Overhead Press** | 0,83 | 0,49 | 0,75 | **0,59** |
| **Deadlift** | 0,64 | 0,84 | 0,40 | **0,55** |
| Crunch | 0,53 | 0,95 | 0,45 | 0,61 |
| Back Extension | 0,70 | 0,99 | 0,52 | 0,68 |
| Thruster | 0,91 | 0,91 | 0,86 | 0,89 |
| Calf Raise | 0,54 | 1,00 | 0,54 | 0,70 |
| **Lat Pull-Down** | 0,73 | 0,70 | 0,57 | **0,63** |
| **Barbell Curl** | 0,67 | 0,87 | 0,53 | **0,66** |
| Side Lateral Raise | 0,86 | 0,76 | 0,96 | 0,85 |
| Arm Pull-Down | 0,83 | 0,81 | 0,68 | 0,74 |
| **Seated Row** | 0,70 | 0,81 | 0,59 | **0,69** |

**Befund 2 (der wichtigere):** Auch ein trainiertes Modell mit 18 Uebungen
bleibt bei genau den Uebungen schwach, die FlowRep testet: Row (F1 0,61 /
Seated Row 0,69), Lat Pull-Down (0,63), Curl (0,66), Press (0,59). Die
Precision ist dort besonders niedrig (0,40-0,59) — es wird zu viel gezaehlt,
nicht zu wenig. Das ist ein Datenproblem der Bewegung (Zugbewegungen,
Kurzhantel-Kurven, Schulterbewegung), kein Methodenproblem: das Papier
nutzt ein neuronales Verfahren, FlowRep eine kalibrierte klassische
Pipeline.

## Konsequenzen fuer den Plan und die Kampagne

1. **Pro Uebung berichten, nie aggregieren.** Der Harness-Report und das
   Freigabe-Protokoll bekommen eine Zeile je Uebung (genau wie Tabelle 2).
   Ein Gesamt-Exact-Match von 100 % kann sonst 5/5 verdecken, obwohl zwei
   Uebungen nur 3/5 schaffen.
2. **Erwartungshaltung:** Rows, Pulldowns, Curls und Presses sind die
   schweren Faelle. Wenn dort Abweichungen auftreten, ist das erwartbar —
   die Kampagne soll sie dokumentieren, nicht als Ueberraschung behandeln.
3. **Zaehlrichtung beachten:** Die niedrige Precision im Papier heißt
   "zu viele Reps". FlowReps Ablehnungs-Diagnose (RC-17) muss deshalb
   zwischen "verpasst" und "zu viel gezaehlt" unterscheiden — das sind
   verschiedene Ursachen und Fixes.
4. **Fenster/Stride stuetzen RC-20:** Die Kalibrierung sollte je Uebung
   mindestens die Qualitaets- und DTW-Schwelle mitbestimmen; die
   Fensterlogik ist mit der adaptiven Refraktaerzeit bereits datengetrieben.
5. **Abtastrate bleibt 50 Hz.** Das Papier arbeitet mit 92 Hz; die
   Tiefenrecherche (Abschnitt 1.8) belegt 50 Hz als ausreichend fuer
   Krafttraining (Fan et al. 2025, Villa et al. 2025, Phan et al. 2023).
   Kein Firmware-Eingriff noetig.
6. **Handgelenk vs. Hebelarm (R7):** Das Papier nennt Handgelenk/Brust als
   uebliche Positionen; Adi traegt am Handgelenk (Entscheidung 6). Die
   Vergleichssession aus R7 wird in A10 aufgenommen — 4 von 5 FlowRep-
   Uebungen sind Hebelarm-Maschinen mit fester Achse.

## Werkzeug-Transparenz

Erzeugt mit den am 19.09.2026 getesteten Werkzeugen (siehe Plan 0.2):
`orx paper --full` fuer den Volltext, `hpr scholar search` fuer den
Literaturueberblick. Beide Ergebnisse sind Rohmaterial und wurden gegen
den Code/das Repo geprueft; die Tabellen oben sind woertlich aus dem
Volltext uebertragen.
