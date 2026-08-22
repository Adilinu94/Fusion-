# UI-Vertrag: Progress-Dashboard (Flowtimer-Integration)

**Datum:** 2026-08-22
**Status:** Vorschlag zur Abnahme
**Untergeordnet:** [`FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md`](FLOWREP_MOBILE_DESIGN_SYSTEM_2026-08-14.md) — dieses Dokument wählt aus dessen Tokens aus und definiert keine eigenen Farb-, Schrift- oder Abstandswerte.
**Gehört zu:** [`2026-08-22-flowtimer-integration-design.md`](2026-08-22-flowtimer-integration-design.md), [`2026-08-22-flowtimer-integration-CONTEXT.md`](2026-08-22-flowtimer-integration-CONTEXT.md)
**Prototyp:** [`prototypes/progress-dashboard.html`](prototypes/progress-dashboard.html) — Entscheidungswerkzeug, **nicht** Spezifikation. Bei Abweichung gilt dieses Dokument ([Prototyp](#prototyp)).

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

<a name="palette"></a>
## Palette — vier Farben mit je einer Aufgabe

Neu hinzu: `756FFA` (Violett), `141414` (Grund), `E7E6FB` (Helllila).
Zusammen mit Lime sind das vier Träger. Damit vier Farben nicht zu Chaos
werden, hat jede genau **eine** Rolle:

| Farbe | Token-Rolle | Aufgabe | Nie verwenden für |
|---|---|---|---|
| `DFFF2F` Lime | `primary` | Die **eine** Hauptaktion oder der aktive Fortschritt pro Screen | Flächen, Fließtext, mehr als ein Element |
| `756FFA` Violett | `secondary` | **Ziele.** Zielring, Ziel-Fortschritt, Ziel-erreicht-Marke | Aktionen, Buttons, Navigation |
| `141414` | `background` | App-Grund unter allen Tiles | Tile-Fläche (verschwindet gegen den Grund) |
| `E7E6FB` Helllila | `secondaryContainer` | Genau **ein** heller Tile pro Screen — der Aussage-Tile | Mehrere Tiles, Text auf dunkel |

**Die semantische Trennung ist der Kern:** Lime heißt „tu das jetzt",
Violett heißt „das ist dein Ziel". Ein Nutzer lernt das in einer Sitzung,
und danach ist jeder Screen ohne Legende lesbar. Ohne diese Trennung sind
es nur zwei bunte Farben, und das Designsystem-Verbot „mehrere
gleichwertige Lime-CTAs" wird zu „mehrere gleichwertige bunte Flächen".

<a name="kontrast"></a>
### Gemessene Kontraste — was erlaubt ist

Alle Werte nach WCAG 2.x, gerechnet mit derselben Formel wie
`ThemeColorSnapshotTest`:

| Vordergrund auf Hintergrund | Ratio | Erlaubt für |
|---|---|---|
| Lime auf `141414` | 16,21 | alles |
| `E7E6FB` auf `141414` | 15,01 | alles |
| Weiß `F7FBFF` auf `141414` | 17,72 | alles |
| Grau `B7B7B7` auf `141414` | 9,19 | alles |
| **Violett `756FFA` auf `141414`** | **4,75** | Text ab 14sp, Grafik ✓ |
| **`141414` auf Violett-Fläche** | **4,75** | Text ✓ — **das ist `onSecondary`** |
| Weiß auf Violett-Fläche | 3,73 | nur ≥ 24sp oder ≥ 19sp bold |
| `E7E6FB` auf Violett-Fläche | 3,16 | nur ≥ 24sp oder ≥ 19sp bold |
| `141414` auf `E7E6FB`-Fläche | 15,01 | alles ✓ — **das ist `onSecondaryContainer`** |
| Violett auf `E7E6FB`-Fläche | 3,16 | nur große Zahlen, keine Labels |
| Lime auf `E7E6FB`-Fläche | **1,08** | **verboten** |
| Violett neben Lime | 3,42 | Grafik ✓ (≥ 3:1), **Text verboten** |

Vier harte Regeln daraus:

1. **Auf Violett steht dunkler Text (`141414`), nicht weißer.** Weiß auf
   `756FFA` erreicht nur 3,73 und fällt bei Body-Text durch. Das ist
   dieselbe Logik, die im Designsystem schon für Lime gilt
   (`onPrimary = 101010`).
2. **Lime berührt `E7E6FB` nie.** 1,08 ist praktisch unsichtbar. Wenn der
   helle Tile eine Aktion braucht, ist sie `141414` auf `E7E6FB`.
3. **Lime-Text auf Violett ist verboten, Lime-Grafik auf Violett erlaubt.**
   3,42 unterschreitet die 4,5:1-Grenze für Text, überschreitet aber die
   3:1-Grenze, die WCAG für grafische Objekte und Bedienelemente ansetzt.
   Eine Lime-Pille auf einem Violett-Balken ist damit zulässig, ein
   Lime-Label darauf nicht. Diese Unterscheidung eröffnet die
   Chart-Hervorhebung in [R4](#r4); ohne sie wären die beiden Farben
   pauschal getrennt und der Chart hätte keinen Fokuspunkt.
4. **Violett und Lime bilden nie eine Textkante.** Wo sie sich berühren,
   ist die eine Fläche und die andere Grafik — nie zwei Farbflächen mit
   Text an der Naht.

<a name="theme"></a>
### Was das am Theme ändert

`Theme.kt` belegt heute `secondary = BrandWhite` — ein ungenutzter Slot,
der weder im Designsystem noch im Code eine Rolle spielt. Violett zieht
dort ein:

```text
background          141010 → 141414      (Grund unter den Tiles)
surface             101010 → 141414
secondary           F7FBFF → 756FFA      (Ziele)
onSecondary         101010 → 141414      (dunkler Text auf Violett)
secondaryContainer  (neu)  → E7E6FB      (der helle Aussage-Tile)
onSecondaryContainer (neu) → 141414
surfaceContainer     1D1D1D bleibt        (dunkle Tiles)
surfaceContainerHigh 252525 bleibt        (Tile-Hervorhebung)
primary             DFFF2F bleibt         (Aktion)
```

`surfaceVariant`, `outline`, `onSurfaceVariant` bleiben unangetastet.
`AccentColor` (heute `LIME | BLUE`) wird **nicht** erweitert — Violett ist
keine wählbare Akzentfarbe, sondern eine feste semantische Rolle.

`ThemeColorSnapshotTest` braucht drei neue Fälle: Violett bleibt
`756FFA`, `onSecondary` ist dunkel (nicht weiß), und Lime auf
`secondaryContainer` wird **nie** gepaart. Der letzte Test ist der
wichtigste — er verhindert die eine Kombination, die unlesbar ist.

<a name="bento"></a>
## Bento-Layout

Ein Bento-Grid funktioniert nur, wenn die Tiles **unterschiedlich groß**
sind. Gleich große Kacheln sind eine Kartenwand mit runden Ecken — genau
das, was das Designsystem verbietet. Größe ist hier die Hierarchie:

```text
┌─────────────────────────────────────┐
│  Fortschritt                        │  Headline, kein Tile
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│                                     │
│           ╭────────╮                │  TILE 1 — 2 Spalten
│           │   2    │                │  E7E6FB, radiusHero 28dp
│           ╰────────╯                │  Ring Lime auf hell
│      von 3 Trainings                │
│   Noch ein Training diese Woche     │  Text 141414
│                                     │
└─────────────────────────────────────┘

┌───────────────────┐ ┌───────────────┐
│  12               │ │  12,4 t       │  TILE 2 + 3 — je 1 Spalte
│  Tage in Folge    │ │  diese Woche  │  1D1D1D, radiusCard 20dp
└───────────────────┘ └───────────────┘

┌─────────────────────────────────────┐
│  ▁▃▅▂▆▇▅█                           │  TILE 4 — 2 Spalten, flach
│  KW28              KW35             │  1D1D1D
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│  ZIELE            2 von 5 erreicht  │  TILE 5 — 2 Spalten, wächst
│                                     │  1D1D1D
│  Bankdrücken         10 kg fehlen   │
│  ●●●●●●●●○○                         │  Violett-Punkte statt Balken
│  Kniebeuge      2 Wdh. fehlen       │
│  Rudern             erreicht ✓      │  Häkchen Violett
└─────────────────────────────────────┘
```

**Grid-Regeln:**

- Zwei Spalten, `space12` Abstand zwischen Tiles, `space16` Seitenrand.
  `LazyVerticalStaggeredGrid` mit `StaggeredGridCells.Fixed(2)`; breite
  Tiles nutzen `StaggeredGridItemSpan.FullLine`.
- **Genau ein heller Tile pro Screen.** Der Aussage-Tile ist hell, alles
  andere dunkel. Zwei helle Tiles und die Hierarchie ist weg.
- Radius nach Größe: `radiusHero` (28dp) für den Aussage-Tile,
  `radiusCard` (20dp) für alle anderen. Der Radius signalisiert Rang.
- Kein Tile ist leer. Wenn es nichts zu zeigen hat, ist es nicht da
  (siehe [Ausblenden](#ausblenden)).
- Tiles haben `space24` Innenabstand, keine Hairline und keinen Schatten.
  Sie trennen sich durch Flächenhelligkeit gegen `141414`, nicht durch
  Rahmen — Ratio 1,09 ist bewusst subtil und genau richtig für Tiles.
- Der Ziele-Tile ist der einzige, der mit dem Inhalt wächst. Alle anderen
  haben feste Höhe, damit die Anordnung beim Datenwechsel nicht springt.

Ziel-Fortschritt pro Übung sind **zehn Punkte** in Violett, nicht ein
Balken. Punkte sind auf 6 Zoll klar zählbar, tragen die Zehnerteilung
ohne Achse und wirken bei zehn Zeilen ruhiger als zehn Balken. Ein
gefüllter Punkt = 10 % der Restdistanz geschlossen.

<a name="ausblenden"></a>
## Ein- und Ausblenden

Grundsatz: **Ein Tile ohne Aussage existiert nicht.** Kein leerer
Platzhalter, keine graue Kachel mit „keine Daten", keine Überschrift ohne
Inhalt.

| Tile | Sichtbar wenn | Sonst |
|---|---|---|
| Aussage (Ring) | mindestens ein Satz existiert | ganzer Screen wird Leerzustand |
| Streak | Streak ≥ 2 Tage | weg — „1 Tag in Folge" ist keine Serie |
| Volumen diese Woche | diese Woche ≥ 1 Satz | weg |
| Chart | ≥ 2 Wochen mit Sätzen | weg — ein Balken ist kein Trend |
| Ziele | ≥ 1 Ziel gesetzt | eine Zeile: `Ziel setzen` → ExerciseLibrary |
| PR-Zeile | PR in den letzten 7 Tagen | weg |

Die Tiles rücken dabei nach — deshalb ein Grid und keine feste
Anordnung. Ein neuer Nutzer sieht am zweiten Trainingstag zwei Tiles, nach
zwei Wochen vier, nach dem ersten Ziel fünf. Der Screen wächst mit den
Daten mit, statt von Anfang an leere Fächer zu zeigen.

**Übergang:** `animateItem()` im Grid plus `AnimatedVisibility` mit
`fadeIn + scaleIn(0.96f)` beim Erscheinen, 200 ms. Ein Tile, das zum
ersten Mal auftaucht (erster Streak, erstes Ziel), erscheint mit einem
kurzen Violett-Aufblitzen des Randes — das ist der einzige Ort, an dem der
Screen etwas feiert. Bei reduzierter Systemanimation: sofort da, kein
Blitz.

**Was nicht ausgeblendet wird:** der Aussage-Tile. Solange Sätze
existieren, ist der Ring da — auch bei 0 von 3
(siehe [R3](#r3)). Sonst verschwindet die Antwort auf die eine Frage, die
der Screen beantworten soll.

<a name="aufbau"></a>
## Reihenfolge der Tiles

```text
1  Aussage-Ring          hell, 2 Spalten, immer
2  Streak                dunkel, 1 Spalte
3  Volumen diese Woche   dunkel, 1 Spalte
4  Chart 8 Wochen        dunkel, 2 Spalten
5  PR-Zeile              keine Kachel, nur Text
6  Ziele                 dunkel, 2 Spalten, wächst
7  Letzte Sätze          dunkel, 2 Spalten, LazyColumn-Fortsetzung
```

Reihenfolge nach Zeithorizont: jetzt (Ring), diese Woche (Streak,
Volumen), letzte Wochen (Chart), langfristig (Ziele), Rohdaten (Sätze).
Wer scrollt, geht in der Zeit zurück — das braucht keine Erklärung.

**Kein Segmented Control.** „Letzte Sätze" ist Tile 7, nicht ein zweiter
Modus. Ein Screen, eine Scroll-Richtung, kein Zustand, der einen
Prozess-Tod überleben muss (siehe [Verlauf](#verlauf)).

<a name="regeln"></a>
## Die Regeln dahinter

<a name="r1"></a>
### R1 — Lime handelt, Violett zielt

Lime erscheint pro Screen genau einmal als **Fläche**: als Ring auf dem
hellen Aussage-Tile. Violett trägt alles, was mit **Zielen** zu tun hat —
Ziel-Punkte, Ziel-Häkchen, die Chart-Grundlinie erfüllter Wochen, das
Aufblitzen beim ersten erreichten Ziel.

Kein Element trägt beide Farben als Text-auf-Fläche. Die einzige erlaubte
Berührung ist die Wert-Pille im Chart: Lime-Pille auf Violett-Balken,
Grafik auf Grafik ([R4](#r4)). Das ist zulässig, weil 3,42 die
Grafik-Grenze von 3:1 überschreitet — für Text tut es das nicht.

Dunkle Tiles bleiben farblos: Zahlen in `onSurface`, Beschriftungen in
`onSurfaceVariant`, Chart-Balken in `onSurfaceVariant`. Farbe ist die
Ausnahme, nicht die Grundausstattung — bei fünf Tiles auf einem Screen
wäre alles andere Chaos.

Einzige weitere Lime-Verwendung: die Zeile unter dem Ring wird Lime, **wenn
genau ein Training fehlt**. Das ist der Goal-Gradient-Moment — Motivation
steigt mit der Nähe zum Ziel, und dieser eine Zustand verdient die Farbe.
Auf dem hellen Tile `E7E6FB` ist Lime aber unlesbar (1,08); dort wird die
Zeile stattdessen `141414` **bold**. Gewicht statt Farbe.

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

**Die aktuelle Woche ist der Fokuspunkt.** Ihr Balken ist Violett statt
grau und trägt darüber eine Lime-Pille mit dem Wert: `12,4 t`. Acht graue
Balken ohne Ankerpunkt lassen offen, welcher davon *jetzt* gilt — der
Nutzer muss die Achse lesen und zählen. Mit einem gefärbten Balken plus
Wert ist die Frage „wo stehe ich" ohne Tap beantwortet, und der einzige
Zahlenwert im Chart hängt genau dort, wo er gebraucht wird.

Die Pille ist der eine Ort, an dem Lime und Violett sich berühren
dürfen: Grafik auf Grafik erreicht mit 3,42 die WCAG-Grenze von 3:1 für
grafische Objekte. Der Pillen-**Text** steht auf Lime in `141010`
(Ratio 16,2), nicht auf Violett. Wäre die Pille selbst violett und der
Text Lime, wäre es unzulässig.

Damit trägt der Chart drei Informationen ohne dritte Kodierung: Höhe =
Volumen, Grundlinie = Wochenziel erfüllt, Farbe = jetzt. Wer mehr will,
tippt einen Balken an.

Kein Chart-Framework. `BarChart` in
`core/designsystem/.../chart/Charts.kt` existiert, ist Canvas-basiert,
animiert und trägt `contentDescription` — es fehlen nur die Grundlinien-
Markierung und die Hervorhebung des letzten Balkens. Eine Fremdbibliothek
für acht Balken wäre Gewicht ohne Gewinn.

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

Im Tile-Kopf steht der Ziel-Zähler: `ZIELE   2 von 5 erreicht`. Das ist
die einzige Zahl im unteren Screen-Drittel und beantwortet die Frage
„lohnt sich das Scrollen?", ohne dass gescrollt werden muss.

Fortschritt pro Zeile sind **zehn Punkte in Violett**, kein Balken:

```text
Bankdrücken                10 kg fehlen
●●●●●●●●○○
```

Ein gefüllter Punkt entspricht 10 % geschlossener Restdistanz. Punkte
sind auf 6 Zoll zählbar, tragen die Zehnerteilung ohne Achse und wirken
bei zehn Zeilen deutlich ruhiger als zehn Balken. Erreichte Ziele zeigen
statt Punkten ein Violett-Häkchen plus das Wort `erreicht` — Farbe ist
nie der einzige Kanal.

<a name="r5b"></a>
### R5b — Die Übungszeile öffnet den Ziel-Dialog

Jede Zeile ist antippbar und öffnet direkt den Bearbeiten-Dialog der
ExerciseLibrary bei der Ziel-Sektion. Ein Tap statt vier. Entscheidung 13
bleibt unangetastet: gepflegt wird weiterhin in der ExerciseLibrary, der
TrainScreen bleibt frei — nur der Weg dorthin wird kurz.

Ohne das ist die realistische Nutzung: man sieht „10 kg fehlen", denkt
„das Ziel ist zu niedrig", und ändert es nie, weil der Weg dorthin zu
lang ist.

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
## Barrierefreiheit — sieben Punkte, die konkret brechen können

1. **Der Ring bei 200 % Schriftgröße.** Eine 48sp-Zahl in einem 220dp-Ring
   passt bei doppelter Systemschrift nicht mehr hinein. Ab
   `fontScale > 1.5` wandert die Zahl unter den Ring und der Ring
   schrumpft auf 120dp. Der Ring ist `dp`, die Zahl ist `sp` — sie
   skalieren unterschiedlich, das muss abgefangen werden.
2. **Das Bento-Grid bei 200 % Schriftgröße.** Zwei einspaltige Tiles
   nebeneinander sind bei doppelter Schrift zu schmal für „Tage in Folge".
   Ab `fontScale > 1.5` wird das Grid **einspaltig** — jeder Tile nimmt
   die volle Breite. Das ist der Punkt, an dem ein Bento-Layout am
   ehesten bricht, und der einzige Grund, `LazyVerticalStaggeredGrid`
   statt einer festen `Row`-Anordnung zu nehmen.
3. **`stateDescription` am Ring**, nicht nur `contentDescription`:
   „2 von 3 Wochentrainings, ein Training fehlt". TalkBack liest den
   Zustand, nicht die Grafik.
4. **Der Chart braucht eine Textalternative.** Acht Balken sind für
   TalkBack unbrauchbar. `contentDescription` des Charts nennt Trend und
   aktuelle Woche: „Volumen der letzten acht Wochen, steigend,
   diese Woche 12,4 Tonnen." Einzelwerte über den Tap-Dialog.
5. **Zehn Ziel-Punkte sind für TalkBack ein Element**, nicht zehn. Die
   Zeile liest sich als „Bankdrücken, 10 Kilogramm fehlen, 80 Prozent" —
   die Punkte selbst werden nicht angesagt.
6. **Ziel erreicht braucht Text und Icon**, nie nur ein Violett-Häkchen —
   Designsystem: „Farbe wird nie als einziger Statuskanal genutzt."
7. **Jede Übungszeile ist ein TalkBack-Element** via `mergeDescendants`,
   nicht drei (Name, Differenz, Punkte). Der heutige `HistoryScreen`
   macht das für Satzzeilen schon so (Verbesserungsplan 6.2) — dasselbe
   Muster gilt hier. Die Zeile trägt zusätzlich die Rolle „Button", weil
   sie den Ziel-Dialog öffnet ([R5b](#r5b)).

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
| Chart-Balken | Höhe 0 → Zielwert, einmal beim Erscheinen | Bereits in `BarChart` vorhanden |
| Ziel-Punkte | keine | Zehn Zeilen × zehn Punkte animiert wären Flimmern |
| Streak-Zahl | keine | Der Streak ändert sich um 1, nicht um 40 — Count-Up wäre Theater |
| Tile erscheint neu | `fadeIn + scaleIn(0.96f)`, 200 ms, plus `animateItem()` fürs Nachrücken | Ein Tile, das aufpoppt, muss erklärt werden — Bewegung tut das |
| Erstes Tile überhaupt | zusätzlich kurzes Violett-Aufblitzen des Randes, 300 ms, einmalig | Der einzige Feiermoment auf diesem Screen |

Bei reduzierter Systemanimation: Endwerte sofort, kein Count-Up, kein
Balkenwachstum, kein Aufblitzen. Tiles erscheinen ohne Übergang. Der
Zustand bleibt vollständig sichtbar.

Der Ring animiert **nur bei echter Änderung**, nicht bei jedem
Recomposition-Durchlauf. `animateFloatAsState` erledigt das, solange der
Zielwert stabil aus dem StateFlow kommt — ein neu berechneter Float pro
Emission würde den Ring dauerhaft zappeln lassen.

<a name="verlauf"></a>
## Verlauf: Tile statt zweiter Modus

Das Segmented Control „Übersicht | Verlauf" ist **gestrichen** (Adi,
2026-08-22). Entscheidung 17 des Design-Dokuments ist damit aufgehoben.

Begründung: Ein Segmented Control ist ein Zugeständnis daran, dass man
sich zwischen zwei Anordnungen nicht entscheiden konnte. Der Verlauf ist
aber keine Alternative zur Übersicht, sondern ihre Fortsetzung — die
Rohdaten unter den Aggregaten. Als Tile 7 am Ende des Grids braucht er
keinen Zustand, der einen Prozess-Tod überleben muss, keine
`SavedStateHandle`-Verdrahtung und keinen zweiten Einstiegspunkt.

Umsetzung: Tile 7 zeigt die letzten 10 Sätze plus `Alle Sätze anzeigen`.
Der Tap öffnet eine eigene Route innerhalb `:feature:progress` mit der
vollen `LazyColumn` — dort greift Android-Back normal, was ein Segmented
Control nie geleistet hätte.

Damit hat der Screen eine Scroll-Richtung und keine Modi. Das ist der
größte Beitrag zur Übersichtlichkeit in diesem Dokument.

<a name="prototyp"></a>
## Der HTML-Prototyp und was er nicht ist

[`prototypes/progress-dashboard.html`](prototypes/progress-dashboard.html)
zeigt fünf Datenzustände nebeneinander in je einem 393 × 852 dp Rahmen:
kein Satz, Tag 2, zwei Wochen, voller Stand, Wochenziel übertroffen. Eine
Datei, keine externen Assets, kein Netzwerk.

**Er ist ein Entscheidungswerkzeug, keine Spezifikation.** Wo Prototyp und
dieses Dokument sich widersprechen, gilt dieses Dokument. Der Prototyp
darf gelöscht werden, sobald `:feature:progress` steht.

Grund für ihn: `:feature:progress` existiert noch nicht. Eine
Compose-Preview würde ein neues Gradle-Modul, einen Eintrag in
`settings.gradle.kts`, eine Anpassung von `ModuleDependencyRulesTest` und
einen Build über 29 Module verlangen — pro Layoutfrage. Im Browser ist
eine Variante ein Reload. Da Flowtimer v2 ohnehin zuerst kommt, blockiert
das nichts.

**Was der Prototyp beantwortet:**

- Dominiert der helle Tile die Hierarchie oder erdrückt er den Rest?
- Sind zehn Violett-Punkte auf 393 dp Breite zählbar?
- Wirkt der Screen mit zwei Tiles (Fall 1) leer oder ruhig?
- Steht die Antwort auf „bin ich auf Kurs?" über der Faltlinie? Der
  Rahmen zeichnet sie bei 852 dp ein.
- Bricht das Grid bei doppelter Schrift? Ein Schalter setzt alle
  Schriftgrößen hoch, macht das Grid einspaltig und schrumpft den Ring
  auf 120 dp mit Zahl darunter (A11y-Punkt 1 und 2).
- Ein zweiter Schalter legt das 8-dp-Raster als Linien darüber.

**Wo der Prototyp lügt:**

- Poppins ist echt (lokale TTF aus `core/designsystem`), aber
  Browser-Zeilenhöhen und optische Größen weichen von Compose ab.
- `animateItem()`, `fadeIn + scaleIn`, Federkurven mit `StiffnessLow`:
  CSS kann sie nur nachahmen, nicht abbilden. Der Prototyp animiert
  deshalb gar nicht.
- TalkBack-Verhalten, `mergeDescendants`, `stateDescription`: nicht
  prüfbar. Alle sieben A11y-Punkte bleiben Compose-Aufgaben.
- Der `fontScale`-Schalter ist eine Annäherung. Androids Skalierung
  betrifft nur `sp`-Werte, nicht `dp`; im Browser ist beides `px`.

Was aus dem Prototyp zurück in dieses Dokument geflossen ist: die
Chart-Hervorhebung der aktuellen Woche und die Unterscheidung
Lime-Text/Lime-Grafik auf Violett ([R4](#r4), [Kontrast](#kontrast)).
Beides kam aus dem Vergleich mit einem Referenz-Design, das dieselbe
Palette nutzt.

<a name="entschieden"></a>
## Entschieden (2026-08-22, Adi)

| Frage | Entscheidung |
|---|---|
| Streak-Art | **Tages-Streak** — Trainingstage in Folge, bricht nach 3 zusammenhängenden Ruhetagen ([Streak](#streak)) |
| Kulanz | Zwei Ruhetage sind frei; das ist die Kulanz. Kein zusätzlicher Freischein |
| Gewichts-Format | Ganzzahlig ohne Dezimalstelle (`95 kg`), krumme Werte mit einer Stelle (`92,5 kg`), niemals `95,0 kg` ([R2b](#r2b)) |
| Volumen-Einheit | kg unter 1000, ab 1000 kg Tonnen mit einer Dezimalstelle ([R2b](#r2b)) |
| Ziel-Balken je Übung | **Kein Balken.** Zehn Violett-Punkte plus Textdifferenz ([R5](#r5)) |
| Segmented Control | **Gestrichen.** Verlauf ist Tile 7, kein zweiter Modus ([Verlauf](#verlauf)) |
| Layout | **Bento-Grid**, zwei Spalten, Tiles unterschiedlich groß ([Bento](#bento)) |
| Sichtbarkeit | Tiles ohne Aussage werden **ausgeblendet**, nicht leer gezeigt ([Ausblenden](#ausblenden)) |
| Palette | Lime = Aktion, `756FFA` = Ziele, `141414` = Grund, `E7E6FB` = ein heller Tile ([Palette](#palette)) |
| Prototyp | **HTML zuerst**, vor `:feature:progress`. Entscheidungswerkzeug, nicht Spezifikation ([Prototyp](#prototyp)) |

Zwei Punkte kamen aus dem Vergleich mit einem Referenz-Design derselben
Palette und sind meine Entscheidung als Umsetzer: die Hervorhebung der
aktuellen Woche im Chart und die Lockerung der Lime-neben-Violett-Regel
auf „Text verboten, Grafik erlaubt" ([R4](#r4)).

Alles Übrige in diesem Dokument ist meine Entscheidung als Umsetzer und
kann ohne Rückfrage geändert werden, solange das Designsystem und die
Abnahmekriterien unten eingehalten bleiben.

<a name="pruefung"></a>
## Abnahmekriterien

- Der Screen beantwortet „bin ich auf Kurs?" ohne Scrollen, auf einem
  Gerät mit 6 Zoll.
- Genau **ein** heller Tile (`E7E6FB`) pro Screen; genau **eine**
  Lime-Fläche (Ring). Violett trägt ausschließlich Ziel-Elemente.
- Lime und `E7E6FB` berühren sich nirgends (Kontrast 1,08). Lime-Text
  steht nirgends auf Violett (3,42 < 4,5) — die Wert-Pille im Chart ist
  Grafik auf Grafik und damit die einzige erlaubte Berührung.
- Auf Violett-Flächen steht dunkler Text (`141414`, Ratio 4,75), niemals
  weißer (3,73).
- Der Chart hebt die aktuelle Woche hervor (Violett-Balken plus
  Lime-Wertpille); die Pillen-Schrift steht auf Lime, nicht auf Violett.
- Kein Tile ist leer oder zeigt „keine Daten". Geprüft für: neuer Nutzer
  ohne Sätze, ein Trainingstag, eine Woche Daten, Sätze ohne Ziel,
  Montagmorgen.
- Bei `fontScale > 1.5` wird das Grid einspaltig und keine Kachel
  schneidet Text ab.
- Kein Gewicht wird als `95,0 kg` angezeigt; kein krummer Wert wird
  stillschweigend gerundet.
- Zahlen nutzen `Locale.getDefault()`, nicht `Locale.ROOT` — deutsche
  Nutzer sehen `12,4 t`, nicht `12.4 t`.
- Der Streak bricht bei drei zusammenhängenden Ruhetagen und keinen Tag
  früher. Test mit Lücke 1, 2 und 3.
- Kein Segmented Control und kein Modus-Zustand auf diesem Screen.
- TalkBack liest jeden Tile und jede Übungszeile als je ein Element mit
  Zustand; zehn Ziel-Punkte werden nicht einzeln angesagt.
- Bei reduzierter Animation ist jeder Wert sofort korrekt und kein Tile
  blitzt auf.
- Alle Texte kommen aus `values/strings.xml` und `values-de/strings.xml`
  — kein festverdrahteter String im Compose-Code.
- `ThemeColorSnapshotTest` deckt ab: Violett bleibt `756FFA`,
  `onSecondary` ist dunkel, Lime wird nie mit `secondaryContainer`
  gepaart.
- Scrollen, Ring-Animation und Tile-Übergänge halten 60 fps auf einem
  Mittelklassegerät.
