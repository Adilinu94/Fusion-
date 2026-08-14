# Ergänzung zum Handoff (2026-08-14) — offene Fragen ohne Lösung

**Zweck:** Ergänzt `docs/handoff_2026-08-13.md` und
`docs/design/KI_EMULATOR_TESTSTRATEGIE_2026-08-14.md`, ersetzt beide
nicht — die sind bereits umfassend. Das hier ist eine bewusst kurze
Liste von Fragen, zu denen ich (diese Session, kein Hardware-Zugriff)
einen konkreten Anlass sehe, aber selbst keine Lösung gefunden habe.
Gedacht als Startpunkte für eine unabhängige Review, nicht als
Bug-Liste.

**Commit-Stand:** `700bae7`

---

## 1. Delta seit dem 13.08.-Handoff (zur Einordnung)

Seitdem gelandet: alle 9 Punkte des Rep-Zählungs-Umbauplans, drei
instrumentierte Tests (AudioFocus, AudioTimestamp-Extrapolation,
BLE-Scan/Connect — Letzterer bewusst `@Ignore`, kein Emulator-BLE),
`docs/HARDWARE_TESTPLAN.md`, und die Emulator-Teststrategie oben. Die
in Abschnitt 5 des 13.08.-Handoffs gelisteten Blocker (Gate 11b,
Corpus-Kuration) sind dadurch nicht gelöst, aber jetzt mit einem
konkreten Ablauf hinterlegt.

---

## 2. Musik/Audio — die für mich größte unbearbeitete Fläche

Ehrlich: in dieser gesamten mehrtägigen Session ging praktisch die
gesamte Zeit in Sensor/Rep-Zählung. Ich habe keine der ~45 Dateien in
`data/audio`, `data/playback`, `domain/audio`, `domain/playback`,
`feature/audio`, `feature/player` inhaltlich geprüft — nur die
Dateiliste heute zum ersten Mal gesehen. Gegeben, dass Musik-Wiedergabe
laut Auftrag genauso wichtig ist wie Rep-Zählung, ist das der
naheliegendste Startpunkt für die nächste Review, nicht weil ich einen
Fehler vermute, sondern weil dort schlicht noch niemand mit demselben
Maßstab draufgeschaut hat wie auf `domain/sensor` (SOFORT/MITTELFRISTIG/
LANGFRISTIG-Audit, Line-by-Line-Verifikation, Literatur-Abgleich).

Konkrete Kandidaten, ungeprüft: Timing-Mathematik in
`DropLandingPlanner`/`CrossfadeController`/`AudioClock`+
`AudioTimestampExtrapolator` (Latenz-Abzug, Generation-Token aus Phase
6) — die gleiche Art Edge-Case-Audit, die bei der Rep-Zählung mehrere
echte Bugs gefunden hat, wurde hier noch nicht gemacht. Die
BT-Codec-Reflection (`getBluetoothCodecStatus()`, bereits als
Schwachstelle c im 13.08.-Handoff benannt) — offen ist, ob es einen
Fallback gibt, falls die versteckte API auf einem OEM-Skin anders
reagiert als erwartet (ähnlich der bereits bekannten
HyperOS-517-MTU-Eigenheit).

---

## 3. Rep-Zählung — konkrete Fragen, die ich nicht gelöst habe

1. **Übungswechsel mitten im Satz:** `selectExercise()` ruft
   `resetShadowEngine()` + `loadActiveProfile()` auf, aber es gibt
   keinen entsprechenden Reset für `liveEngine` — die Zuweisung
   (`TrainViewModel.kt:495`) passiert nur in `startCountedSet()`.
   Falls ein Übungswechsel während eines laufenden Satzes möglich ist
   (UI-seitig nicht geprüft), würde `liveEngine` bis zum nächsten
   `startCountedSet()` mit dem Profil der vorherigen Übung
   weiterzählen. Ich habe nicht geprüft, ob die UI einen Wechsel
   mitten im Satz überhaupt zulässt — falls nein, ist der Punkt
   irrelevant.
2. **Gewichtsabhängige Bewegungsvariation:** SPK/NPK, Multi-Template-
   Pool und Quality-Schwelle sind alle Übungs-, nicht
   Gewichts-abhängig. Ob sich die IMU-Signatur einer Wiederholung bei
   deutlich höherem Gewicht (langsameres Tempo, andere
   Beschleunigungskurve) genug ändert, um Template-Matching zu
   beeinflussen, ist nirgends dokumentiert oder getestet — reine
   Hypothese meinerseits, keine Bestätigung.
3. **Ermüdungsdrift innerhalb eines Satzes:** dieselbe Frage für die
   letzten Wiederholungen eines Satzes nahe dem Muskelversagen (Tempo,
   ROM verändern sich typischerweise) — gegen ein festes, am Satzanfang
   gelerntes Template. Nirgends adressiert.
4. **`BleProtocolParser.kt` gegen `docs/reference/protocol.yaml`:**
   heute zum ersten Mal vollständig gelesen (v1/v2-Format-Erkennung,
   Little-Endian, Skalierungsfaktoren) — auf den ersten Blick sauber,
   aber nicht gegen die referenzierte `protocol.yaml` gegengeprüft, die
   laut eigenem Docstring "MUST stay in sync" sein muss.
5. Punkt (k) aus dem 13.08.-Handoff (Property-Based-Testing statt
   handgebauter Streams) würde genau die Fragen 1-3 oben am ehesten
   aufdecken — ich habe es nicht umgesetzt, nur für genau diese Fälle
   als besonders lohnend markiert.

---

## 4. Ein alter, nie geklärter Punkt

Ganz am Anfang dieser Session (`IMPORT_README.md`, mittlerweile
überholt) stand ein Verweis auf bereits "an anderer Stelle vermerkte
Bedenken" zu `WISSEN_POWERAMP_OFFTRACK_2026-08-07.md`
(reverse-engineerte Poweramp/Offtrack-Interna), ohne dass ich je
gefunden habe, welche Bedenken genau gemeint waren. Da dieses Dokument
jetzt explizit als Grundlage für den "Bauplan für spätere native
Audio-Engine" (Abschnitt 15) zitiert wird, halte ich das für offen und
relevant, nicht erledigt.

---

## 5. Eine Kleinigkeit seit Tag 1 dieser Session, immer noch offen

`README.md` wurde seither leicht angepasst (App-Name FlowRep ergänzt),
verweist aber weiterhin auf `DropSync-Technischer-Bauplan.md` als
"verbindlichen Bauplan" — die Datei existiert weiterhin nicht im Repo
(bereits am ersten Tag dieser Session so vorgefunden). Train-Tab,
BLE-Sensor oder die Fusion selbst kommen im README nach wie vor nicht
vor. Klein, aber seit der ganzen Session unverändert liegen geblieben.

---

## 6. Einordnung zur Emulator-Strategie

`KI_EMULATOR_TESTSTRATEGIE_2026-08-14.md` stuft Rep-Zählung/BLE
zurecht als nicht emulator-testbar ein. Das heißt für eine KI ohne
Hardware-Zugriff: der Beitrag zu Abschnitt 3 oben bleibt zwangsläufig
Code-/Architektur-Ebene, nicht Testausführung — derselbe Modus wie in
dieser ganzen Session. Bei Musik/UI (Abschnitt 2) ist dagegen laut
derselben Strategie sowohl Code-Review als auch echte
Emulator-Testautomatisierung möglich.
