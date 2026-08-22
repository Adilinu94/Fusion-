# UI-Vertrag: Progress-Dashboard (Flowtimer-Integration)

**Datum:** 2026-08-22
**Status:** Vorschlag zur Abnahme
**Untergeordnet:** [`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`](FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md) — dieses Dokument wählt aus dessen Tokens aus und definiert keine eigenen Farb-, Schrift- oder Abstandswerte.
**Gehört zu:** [`2026-08-22-flowtimer-integration-design.md`](2026-08-22-flowtimer-integration-design.md), [`2026-08-22-flowtimer-integration-CONTEXT.md`](2026-08-22-flowtimer-integration-CONTEXT.md)

<a name="einwand"></a>
## Der eine Einwand, der alles andere bestimmt

Das Design-Dokument legt in Entscheidung 12 vier Dashboard-Elemente fest:
Streak, Wochenziel-Ring, Wochen-Volumen-Chart, Pro-Übung-Fortschritt. Vier
gleichwertige Elemente auf einem Screen sind eine Kartenwand — und das
Designsystem verbietet sie ausdrücklich: „Keine Kartenwand; jede Ansicht
hat eine dominante Aufgabe und klare Priorität." Sein Abnahmekriterium
lautet: „Ein Nutzer erkennt die aktuelle primäre Aktion innerhalb einer
Sekunde."

Die Frage ist also nicht, wie man vier Kacheln schön anordnet, sondern:
**welche eine Frage beantwortet dieser Screen?** Antwort:

> Bin ich diese Woche auf Kurs?

Alles andere ist Begründung dieser Antwort und wird ihr optisch
untergeordnet. Aus vier gleichwertigen Elementen werden eine Aussage
und drei Belege.

<a name="streak"></a>
## Der Streak zählt Tage — mit Ruhetag-Toleranz

**Entschieden:** Tages-Streak, kein Wochen-Streak.

Ein Streak über *aufeinanderfolgende Kalendertage* wäre in einer
Krafttraining-App aber unbrauchbar. Bei 3 Trainings pro Woche liegt
zwischen zwei Einheiten immer mindestens ein Ruhetag — der Zähler stünde
dauerhaft auf 1 oder 2 und würde jeden korrekt eingehaltenen Ruhetag als
Abbruch werten. Die Empfehlung für Krafttraining lautet 2–3 Einheiten pro
Woche mit 48–72 Stunden Abstand; ein Zähler, der genau das bestraft,
misst das Gegenteil von gutem Training.

**Definition, die den Tages-Streak brauchbar macht:**

> Der Streak zählt **Trainingstage in Folge**. Er bricht, wenn **drei oder
> mehr** aufeinanderfolgende Tage ohne einen einzigen Satz vergehen.

Zwei Ruhetage sind also frei, der dritte bricht. Das deckt sich genau mit
dem 48–72-Stunden-Fenster und ist gleichzeitig die von der
Streak-Literatur empfohlene Kulanz (harter Reset auf Null ist der
häufigste Abbruchgrund).

```text
Mo Di Mi Do Fr Sa So Mo Di
 ×  ·  ×  ·  ×  ·  ·  ×  ×     → 5 Trainingstage in Folge
                ↑ 2 Ruhetage, Streak läuft weiter

Mo Di Mi Do Fr Sa So Mo
 ×  ·  ·  ·  ×                 → Streak bricht am Freitag, beginnt bei 1
    ↑ 3 Ruhetage
```

**Anzeige:** `12 Trainingstage in Folge`. Darunter, wenn heute noch kein
Satz geloggt ist und gestern auch nicht: `Heute oder morgen trainieren,
damit die Serie hält` — das ist der einzige Ort, an dem der Streak eine
Aufforderung ausspricht. Nie eine Drohung („Du verlierst deine Serie!"),
immer der erreichte Stand plus der nächste Schritt.

Technisch: Flowtimers `streakCount` (`Streak.kt`) macht heute
`d -= DAY_MS` in jeder Iteration und braucht dafür einen Parameter
`maxGapDays: Int = 2`. Das ist eine echte Verhaltensänderung an einer
Funktion, die 4 Tests hat — die Tests müssen um Fälle für Lücke 1, 2 und
3 erweitert werden, die bestehenden bleiben grün, wenn der Default 0 ist
und Fusion 2 übergibt. Flowtimers Verhalten ändert sich damit nicht.

<a name="aufbau"></a>
## Aufbau: eine Aussage, drei Belege

```text
Fortschritt                              ← Headline 28sp

[ Übersicht | Verlauf ]                   ← Segmented Control, direkt unter Titel

        ╭─────────────────╮
        │       2         │               ← Display 48sp, tabellarisch, Count-Up
        │   von 3 Wochen- │               ← Meta 14sp
        │     trainings   │
        ╰─────────────────╯               ← ProgressRing, Lime, 220dp
     Noch ein Training diese Woche        ← Body 16sp, Lime NUR wenn 1 fehlt
     12 Trainingstage in Folge            ← Meta 14sp, onSurfaceVariant

  ─────────────────────────────────       ← Hairline, space32 Abstand

  ▁▃▅▂▆▇▅█                                ← 8 Wochen, Balkenhöhe = Volumen
  KW28            KW35                    ← Label 12sp, nur erste und letzte
  Volumen pro Woche · 12,4 t diese Woche

  ─────────────────────────────────

  ÜBUNGEN MIT ZIEL          2 von 5 erreicht   ← SectionHeader + Zähler

  Bankdrücken                  10 kg fehlen
  Kniebeuge          2 Wiederholungen fehlen
  Rudern                     Ziel erreicht ✓   ← Text + Icon, nicht nur Farbe
  Kreuzheben                  noch kein Satz

  Ziel für eine Übung setzen                   ← nur wenn Übungen ohne Ziel existieren
```

Kein Balken pro Übungszeile — die Zahl trägt die Information, ein Balken
daneben wiederholt sie nur und bringt zehn zappelnde Elemente in eine
Liste, die ruhig sein soll.

<a name="regeln"></a>
## Die Regeln dahinter

<a name="r1"></a>
### R1 — Lime erscheint genau einmal

Der Ring ist der einzige Lime-Träger. Chart-Balken sind
`onSurfaceVariant`, Ziel-Häkchen sind `onSurface` plus Icon, die
Ziel-Differenzen sind `onSurfaceVariant`. Sobald ein zweites Element
leuchtet, verliert der Ring seine Bedeutung. Das ist nicht Geschmack,
sondern Entscheidung des Designsystems: „Lime erscheint pro Kontext nur
als Hauptaktion oder aktiver Fortschritt."

Einzige Ausnahme: die Zeile unter dem Ring wird Lime, **wenn genau ein
Training fehlt**. Das ist der Goal-Gradient-Moment — Motivation steigt mit
der Nähe zum Ziel, und dieser eine Zustand verdient die Farbe.

<a name="r2"></a>
### R2 — Zahlen benennen die Distanz, nicht den Stand

`2 von 3` ist eine Statusmeldung. `Noch ein Training diese Woche` ist eine
Handlungsaufforderung. Bei 3 von 3: `Wochenziel erreicht`. Bei 4 von 3:
`Wochenziel übertroffen` — nicht `133 %`, und der Ring bleibt bei 100 %
gefüllt, mit einem zweiten dünnen Bogen für den Überschuss.

Gleiches beim Übungsziel: nicht „90 kg (Ziel 100 kg)", sondern
`10 kg fehlen`. Die Differenz ist die Information.

Weil das Ziel **zwei** Bedingungen hat (Gewicht **und** Reps, siehe E4),
nennt die Zeile beide, wenn beide offen sind:

```text
Bankdrücken          10 kg fehlen
Kniebeuge            2 Wiederholungen fehlen
Kreuzheben           10 kg und 2 Wiederholungen fehlen
Rudern               Ziel erreicht ✓
Schulterdrücken      noch kein Satz
```

Maßgeblich ist der beste einzelne Satz der Übung: der Satz mit dem
höchsten Gewicht, bei Gleichstand der mit mehr Reps. Nicht der letzte
Satz — sonst fällt der Status nach einem Absatz-Satz grundlos zurück.

<a name="r2b"></a>
### R2b — Zahlenformate

Du gibst Gewichte immer ganzzahlig ein (55 kg, nie 55,5 kg). Die Anzeige
folgt dem:

| Größe | Format | Beispiel |
|---|---|---|
| Gewicht, ganzzahliger Wert | ohne Dezimalstelle | `95 kg` |
| Gewicht, krummer Wert (Import, Tippfehler) | eine Dezimalstelle | `92,5 kg` |
| Differenz zum Ziel | wie Gewicht | `10 kg fehlen` |
| Volumen unter 1000 kg | kg, ganzzahlig | `840 kg` |
| Volumen ab 1000 kg | Tonnen, eine Dezimalstelle | `12,4 t` |
| Reps | ganzzahlig, immer | `8` |

`95,0 kg` ist verboten — eine Dezimalstelle, die immer Null ist, kostet
Platz und suggeriert Präzision, die nicht existiert. Umgekehrt wird ein
krummer Wert nie stillschweigend gerundet: wenn 92,5 kg in der Datenbank
steht, zeigt die App 92,5 kg.

Volumen wechselt bei 1000 kg auf Tonnen, weil `12,4 t` auf einem
Chart-Label lesbar ist und `12.400 kg` nicht. Der Wechsel geschieht am
festen Schwellwert, nicht dynamisch — sonst springt die Einheit im
Balken-Dialog von Woche zu Woche.

Dezimaltrennzeichen und Tausenderpunkt kommen aus dem Locale
(`Locale.getDefault()`), nicht aus `Locale.ROOT`. Der heutige
`HistoryScreen.kt:199` nutzt `Locale.ROOT` und zeigt deutschen Nutzern
damit `12.4` statt `12,4` — das wird beim Umzug korrigiert.

<a name="r3"></a>
### R3 — Montag ist kein Versagen

Am Montagmorgen steht der Ring auf 0 von 3. Ein leerer Ring mit einer Null
liest sich als Rückstand, obwohl nichts versäumt wurde. Erster Wochentag
ohne Sätze zeigt deshalb:

```text
        ╭─────────────────╮
        │   Neue Woche    │
        ╰─────────────────╯
     3 Trainings geplant
     12 Trainingstage in Folge
```

Der Ring ist leer, aber die Sprache ist nach vorne gerichtet. Ab dem
ersten geloggten Satz greift die normale Darstellung.

<a name="r4"></a>
### R4 — Der Chart trägt zwei Größen, aber nur eine sichtbar

Balkenhöhe ist Volumen. Ziel-Erfüllung wird **nicht** durch Farbe
kodiert (siehe [R1](#r1)), sondern durch eine 2dp-Grundlinie unter
erreichten Wochen — dieselbe Logik wie GitHubs Contribution-Graph, nur
sparsamer. Wer es genau wissen will, tippt einen Balken an und bekommt
`KW 33 · 14,2 t · 3 von 3`.

Acht Balken sind bewusst wenig. Ein Jahr Balken zeigt keinen Trend,
sondern Rauschen. Acht Wochen ist die Spanne, über die eine Änderung im
Training sichtbar wird und eine Entscheidung noch möglich ist.

Wochen ohne Training zeigen **keinen** Balken der Höhe Null, sondern eine
2dp-Markierung auf der Grundlinie. Ein Nullbalken sieht wie ein Fehler
aus; eine Markierung liest sich als „hier war nichts".

Kein Chart-Framework. `BarChart` in
`core/designsystem/.../chart/Charts.kt` existiert, ist Canvas-basiert,
animiert und trägt `contentDescription` — es fehlt nur die Grundlinien-
Markierung. Eine Fremdbibliothek für acht Balken wäre Gewicht ohne Gewinn.

<a name="r5"></a>
### R5 — Die Übungsliste zeigt Bewegung, nicht Bestand

Nicht alle Übungen, sondern in dieser Reihenfolge:

1. Übungen mit Ziel, sortiert nach Nähe zum Ziel — das Fast-Geschaffte
   zuerst (Goal-Gradient). Erreichte Ziele rutschen ans Ende der Gruppe.
2. Übungen mit Ziel und ohne Satz in den letzten 8 Wochen, gruppiert unter
   `Länger nicht trainiert`.
3. Übungen ohne Ziel erscheinen **nicht**. Stattdessen einmal am Listenende:
   `Ziel für eine Übung setzen` → öffnet die ExerciseLibrary.

Ohne diese Filterung wird die Liste mit jeder neuen Übung länger und
sagt immer weniger.

Sortierschlüssel für Punkt 1: prozentuale Restdistanz über beide
Bedingungen, die größere von beiden entscheidet. Beispiel: Ziel 100 kg × 5,
erreicht 90 kg × 5 → Gewicht 10 % offen, Reps 0 % offen → Sortierwert 10 %.
Bei Gleichstand gewinnt die Übung mit dem jüngeren Satz. Die Rechnung
gehört in den Kern (`TargetMath`), nicht ins ViewModel — Flowtimers
`targetPct` ist die Grundlage und muss dafür auf zwei Dimensionen
erweitert werden.

Über der Liste bleibt der Ziel-Zähler: `2 von 5 Zielen erreicht`. Das ist
die einzige Zahl im unteren Screen-Drittel und beantwortet die Frage
„lohnt sich das Scrollen?", ohne dass gescrollt werden muss.

<a name="r6"></a>
### R6 — Der PR wird nicht hier gefeiert

Ein neuer Bestwert erscheint im Moment des Loggens im TrainScreen, mit
`HEAVY_CLICK`-Haptik und `CountUpText` auf der neuen Zahl. Das Dashboard
ist ein Archiv; ein Konfetti-Moment drei Stunden nach dem Satz ist
Dekoration. Peak-End heißt: der Höhepunkt liegt im Ereignis, nicht im
Bericht.

Im Dashboard bekommt ein PR aus den letzten 7 Tagen eine unaufdringliche
Zeile über der Übungsliste: `Neuer Bestwert: Bankdrücken 95 kg × 5`.
Ohne Lime, ohne Animation. Mehrere PRs in sieben Tagen werden zusammen­
gefasst: `3 neue Bestwerte diese Woche` — antippbar, öffnet den Verlauf.

<a name="r7"></a>
### R7 — Der Leerzustand ist Onboarding

Ohne Sätze zeigt der Screen keinen leeren Ring, sondern:

```text
Fortschritt

Sobald du Sätze loggst, siehst du hier
deine Wochen, dein Volumen und wie nah
du an deinen Zielen bist.

[ TRAINING ÖFFNEN ]                       ← einzige Lime-Fläche
```

Der heutige Leerzustand (`HistoryScreen.kt:127`) macht das schon richtig
und wird übernommen, nicht neu erfunden.

Zwischenzustand nicht vergessen: Sätze vorhanden, aber **kein Ziel**
gesetzt. Dann erscheinen Ring, Streak und Chart normal; an der Stelle der
Übungsliste steht eine einzelne Zeile:
`Setze ein Ziel, um deinen Fortschritt pro Übung zu sehen` →
ExerciseLibrary. Kein leerer Abschnitt, keine Überschrift ohne Inhalt.

<a name="a11y"></a>
## Barrierefreiheit — fünf Punkte, die konkret brechen können

1. **Der Ring bei 200 % Schriftgröße.** Eine 48sp-Zahl in einem 220dp-Ring
   passt bei doppelter Systemschrift nicht mehr hinein. Ab
   `fontScale > 1.5` wandert die Zahl unter den Ring und der Ring
   schrumpft auf 120dp. Der Ring ist `dp`, die Zahl ist `sp` — sie
   skalieren unterschiedlich, das muss abgefangen werden.
2. **`stateDescription` am Ring**, nicht nur `contentDescription`:
   „2 von 3 Wochentrainings, ein Training fehlt". TalkBack liest den
   Zustand, nicht die Grafik.
3. **Der Chart braucht eine Textalternative.** Acht Balken sind für
   TalkBack unbrauchbar. `contentDescription` des Charts nennt Trend und
   aktuelle Woche: „Volumen der letzten acht Wochen, steigend,
   diese Woche 12,4 Tonnen." Einzelwerte über den Tap-Dialog.
4. **Ziel erreicht braucht Text und Icon**, nie nur ein grünes Häkchen —
   Designsystem: „Farbe wird nie als einziger Statuskanal genutzt." Und
   Lime ist ohnehin keine Erfolgsfarbe.
5. **Jede Übungszeile ist ein TalkBack-Element** via `mergeDescendants`,
   nicht drei (Name, Differenz, Balken). Der heutige `HistoryScreen`
   macht das für Satzzeilen schon so (Verbesserungsplan 6.2) — dasselbe
   Muster gilt hier.

Offener Befund: `Theme.kt:89` definiert `error`, aber die vom
Designsystem geforderten Rollen `warning` und `info` fehlen im
ColorScheme. Für dieses Dashboard nicht blockierend (Status läuft über
Text und Icon), aber es sollte nachgetragen werden.

<a name="motion"></a>
## Bewegung — sparsam und begründet

| Element | Bewegung | Warum |
|---|---|---|
| Ring | Feder, `StiffnessLow`, bereits in `ProgressRing.kt:41` | Fortschritt soll ankommen, nicht springen |
| Ringzahl | `CountUpText`, 600 ms | Die Zahl ist das Ergebnis; Hochzählen macht sie zum Moment |
| Balken | Höhe 0 → Zielwert, einmal beim Erscheinen | Bereits in `BarChart` vorhanden |
| Übungsbalken | keine | Zehn gleichzeitig animierte Balken sind Unruhe, kein Feedback |
| Streak-Zahl | keine | Der Streak ändert sich um 1, nicht um 40 — Count-Up wäre Theater |
| Tab-Wechsel Übersicht/Verlauf | Crossfade ≤ 200 ms | Kein Slide — es ist ein Filter, kein Ortswechsel |

Bei reduzierter Systemanimation: Endwerte sofort, kein Count-Up, kein
Balkenwachstum. Der Zustand bleibt vollständig sichtbar.

Der Ring animiert **nur bei echter Änderung**, nicht bei jedem
Recomposition-Durchlauf. `animateFloatAsState` erledigt das, solange der
Zielwert stabil aus dem StateFlow kommt — ein neu berechneter Float pro
Emission würde den Ring dauerhaft zappeln lassen.

<a name="segmented"></a>
## Umschaltung Übersicht | Verlauf

Entscheidung 17 ist gesperrt — die Umschaltung bleibt. Präzisierung:

- Position direkt unter der Headline, nicht in der Bottom Bar und nicht
  über dem Ring. Die Bottom Bar hat vier Ziele und bekommt kein fünftes.
- `SingleChoiceSegmentedButtonRow` (Material 3), Höhe 48dp, ausgewählter
  Zustand `surfaceHigh` — **nicht** Lime, sonst konkurriert er mit dem Ring.
- Der Zustand überlebt Tab-Wechsel und Prozess-Tod
  (`SavedStateHandle`), sonst landet man nach jedem Musik-Ausflug wieder
  in der Übersicht.
- „Verlauf" bleibt die heutige flache Satzliste, unverändert bis auf die
  Sprachdateien.

<a name="entschieden"></a>
## Entschieden (2026-08-22, Adi)

| Frage | Entscheidung |
|---|---|
| Streak-Art | **Tages-Streak** — Trainingstage in Folge, bricht nach 3 zusammenhängenden Ruhetagen ([Streak](#streak)) |
| Kulanz | Zwei Ruhetage sind frei; das ist die Kulanz. Kein zusätzlicher Freischein |
| Gewichts-Format | Ganzzahlig ohne Dezimalstelle (`95 kg`), krumme Werte mit einer Stelle (`92,5 kg`), niemals `95,0 kg` ([R2b](#r2b)) |
| Volumen-Einheit | kg unter 1000, ab 1000 kg Tonnen mit einer Dezimalstelle ([R2b](#r2b)) |
| Ziel-Balken je Übung | **Kein Balken.** Reine Textangabe der Differenz — ruhiger und verliert keine Information |

Alles Übrige in diesem Dokument ist meine Entscheidung als Umsetzer und
kann ohne Rückfrage geändert werden, solange das Designsystem und die
Abnahmekriterien unten eingehalten bleiben.

<a name="pruefung"></a>
## Abnahmekriterien

- Der Screen beantwortet „bin ich auf Kurs?" ohne Scrollen, auf einem
  Gerät mit 6 Zoll.
- Genau eine Lime-Fläche sichtbar (Ring), plus die Zeile unter dem Ring
  nur im Zustand „ein Training fehlt".
- Kein Zustand zeigt eine nackte Null ohne einordnenden Satz — geprüft
  für: Montagmorgen, Woche ohne Training, Übung ohne Satz, Datenbank ohne
  Sätze, Sätze ohne Ziel.
- Kein Gewicht wird als `95,0 kg` angezeigt; kein krummer Wert wird
  stillschweigend gerundet.
- Zahlen nutzen `Locale.getDefault()`, nicht `Locale.ROOT` — deutsche
  Nutzer sehen `12,4 t`, nicht `12.4 t`.
- Der Streak bricht bei drei zusammenhängenden Ruhetagen und keinen Tag
  früher. Test mit Lücke 1, 2 und 3.
- Bei 200 % Systemschrift ist keine Zahl abgeschnitten und der Ring
  liegt nicht über seinem Text.
- TalkBack liest Ring, Chart und jede Übungszeile als je ein Element mit
  Zustand.
- Bei reduzierter Animation ist jeder Wert sofort korrekt.
- Alle Texte kommen aus `values/strings.xml` und `values-de/strings.xml`
  — kein festverdrahteter String im Compose-Code.
- Scrollen und Ring-Animation halten 60 fps auf einem Mittelklassegerät.
