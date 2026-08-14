# KI-Agent-Teststrategie für den Android-Emulator (2026-08-14)

**Zweck:** Recherche-Ergebnis auf Adis Anfrage — wie soll eine
unabhängige KI (nicht die Session, die den Code geschrieben hat) die
App am Ende umfassend testen, UI und technische Funktionen, am besten
per Android-Emulator? Ergänzt `docs/HARDWARE_TESTPLAN.md`, ersetzt ihn
nicht. Für später: umsetzbar, sobald die App laut Adi "soweit fertig"
ist.

**Kontext:** Kein Appstore-Release, ein Nutzer, kein Budget für
Enterprise-Tooling nötig oder sinnvoll.

---

## 1. Die eine harte Grenze zuerst: BLE/M5StickC Plus2 nicht im Standard-Emulator

Android-Emulatoren (AVD) hatten historisch **kein** echtes Bluetooth —
das ist ein seit über zehn Jahren offener AOSP-Punkt. Seit einigen
Emulator-Versionen gibt es einen virtuellen Bluetooth-Controller
namens **Netsim** (`-packet-streamer-endpoint`), kombinierbar mit
Googles **Bumble**-Projekt (Python-Software-Bluetooth-Stack), um
virtuelle BLE-Peripheriegeräte zu simulieren.

**Trotzdem: für dieses Projekt nicht verfolgen.** Um den M5StickC
Plus2 darüber zu simulieren, müsste dessen komplettes GATT-Profil
(Custom Service/Characteristics, MTU-Verhalten, Firmware-Timing)
in Bumble nachgebaut werden — Aufwand, der für ein Solo-Projekt in
keinem Verhältnis zum Nutzen steht. Der bereits vorhandene
`BleScanConnectInstrumentedTest` (Commit `1dd7109`) macht es richtig:
mit `@Ignore` markiert, Kommentar "kein Emulator-BLE", nur für den
manuellen Lauf gegen echte Hardware gedacht. Teil A aus
`HARDWARE_TESTPLAN.md` (Rep-Zählung, Gate 11b) bleibt manuell mit
echtem Gerät + M5Stick — daran ändert diese Strategie nichts.

Alles unten ist für den **Rest** der App gedacht — und das ist der
weit größere Teil (Musikplayer, Bibliothek, Workout-Log, Timer, UI,
Performance, Barrierefreiheit).

---

## 2. Werkzeug-Empfehlung

**Nicht:** Enterprise-SaaS-Plattformen (Autify Aximo, TestSprite,
AskUI, Panto AI, Drizz, Minitap). Alle 2026 real und funktionsfähig,
aber auf Geräte-Cloud-Abos und Team-Nutzung ausgelegt — für ein
Solo-Projekt ohne Appstore-Ziel unpassend teuer und komplex.

**Empfehlung: Claude Code (frische, unabhängige Session) + ein
ADB/Emulator-MCP-Werkzeug, lokal auf dem Windows-Rechner.** Passt zu
deinem bestehenden Workflow (kein neues Konto, keine Cloud), und
"unabhängig" heißt hier: eine Session ohne Kontext zum eigenen Code,
die die App wie ein fremder Tester bedient — das ist der eigentliche
Punkt hinter "nicht du" in deiner Anfrage, nicht zwingend ein anderes
KI-Modell.

Konkrete, aktuell existierende Optionen (alle per MCP an Claude Code
anbindbar, Stand August 2026):

| Werkzeug | Stärke |
|---|---|
| **Android Emulator Skill** (mcpmarket) | Umfangreichste Option: 20 Skripte, semantische UI-Navigation (nicht pixelbasiert), Gradle-Integration, Accessibility-Audits, visuelle Regression, Emulator-Lifecycle (erstellen/booten/zurücksetzen), Push-Notification-Simulation. Für Teil D/E unten am besten geeignet. |
| **android-adb-testing** (LobeHub) | Vision-getriebene Observe-Think-Act-Schleife, Espresso/Compose/Gradle-Testrunner-Integration, Logcat-Monitoring. Guter Allrounder. |
| **claude-in-android / android-adb-mcp-server** | Simpler: Screenshot, Tap, Swipe, Type über ADB. Reicht für gezielte Einzelchecks. |

**Ergänzend, kostenlos, unabhängig vom eigenen Setup:** Firebase Test
Lab Robo-Test — kostenloses Kontingent 10 Emulator-Läufe/Tag, kein
Google-Play-Konto nötig für lokale Nutzung über die CLI. Der
Robo-Crawler braucht keine Skripte, tippt sich automatisch durchs UI
und meldet Abstürze/ANRs. Er versteht keine Fachlogik (zählt keine
Wiederholungen, prüft keine Rep-Zahlen), ist aber eine gute, billige
zweite Meinung zusätzlich zum gezielten Agenten-Testen — findet Dinge,
die kein Testplan vorhergesehen hat.

---

## 3. Mapping: `HARDWARE_TESTPLAN.md` → was automatisierbar ist

| Teil | Inhalt | Per Emulator-Agent automatisierbar? |
|---|---|---|
| A: Rep-Zählung/Sensor | Gate 11b, BLE-Verbindung | **Nein** — bleibt manuell, echtes Gerät + M5Stick |
| B: Audio/Drop-Landung | Crossfade, Ducking, Beeps, UI-Flows | **Teilweise** — UI-Bedienung (Play/Pause/Seek/Marker setzen) ja; BT-Codec-Erkennung (B8, Reflection-API) und echtes Latenzgefühl (B1) nein, brauchen echtes Bluetooth-Gerät |
| C: Timer/Kill-Fallback/Recovery | Foreground-Service, Prozess-Kill | **Ja** — `adb shell am kill` simuliert den Prozess-Tod zuverlässig, Recovery danach prüfbar |
| D: UI-Performance/A11y | Frame-Zeiten, TalkBack, Kontrast | **Ja** — genau der Anwendungsfall der oben genannten Werkzeuge |
| E: Berechtigungen/First-Start | Runtime-Permissions, Xiaomi-Sonderfälle | **Ja** für den generischen Flow (Permission-Dialoge, First-Start-UI); Xiaomi-spezifische Quirks (HyperOS-Verhalten) nur auf echtem Xiaomi-Gerät reproduzierbar |
| F: Instrumentierte Kern-Tests | Bereits vorhanden (`androidTest`) | Läuft eigenständig über `connectedDebugAndroidTest`, kein Agent nötig — nur regelmäßig ausführen |
| G: Macrobenchmark/Baseline | Cold-Start, Jank, Speicher | **Ja** — offizielles Google-Tooling, läuft auf Emulator oder Gerät |

---

## 4. Performance-Tooling konkret (Teil G, Google-Standard, Stand 2026)

- **Macrobenchmark-Library** (Jetpack): reale Nutzerflüsse messen
  (Cold-Start, Scroll-Jank), nicht nur isolierte Funktionen. Läuft im
  eigenen `androidTest`-Modul, auf Emulator oder Gerät.
- **JankStats-Library**: laufende Jank-Erkennung zur Laufzeit.
- **LeakCanary**: Speicherleck-Erkennung, läuft automatisch im
  Debug-Build mit.
- **Android Studio Profiler** (CPU/Speicher/Netzwerk) für die manuelle
  Tiefenanalyse, wenn ein automatisierter Test etwas auffällig
  markiert.

Richtwerte, an denen sich orientieren lässt (keine harten Vorgaben,
Branchen-Faustregeln): Cold-Start < 500 ms, Frame-Budget 16 ms bei
60 fps (adressierbar), keine geleakten Activities/Fragments in
LeakCanary, Baseline-Profile generiert.

Für dieses Projekt besonders relevant, weil in
`docs/handoff_2026-08-13.md` (Abschnitt 6g/6h) bereits selbst als
ungemessene Risiken benannt: Waveform-Neuzeichnung pro Frame,
Library-Flow-Subscriptions bei 1000+ Songs, 200-ms-Positions-Ticker.
Genau dafür ist Macrobenchmark/JankStats gebaut — konkreter Vorschlag,
nicht nur "irgendwann testen": ein Macrobenchmark-Test, der die
Bibliothek mit einer großen (synthetisch befüllten) Song-Liste
scrollt, würde (g) direkt in eine Zahl verwandeln.

---

## 5. Ablauf, wenn die App so weit ist

1. `HARDWARE_TESTPLAN.md` Teil A (manuell, echtes Gerät + M5Stick) —
   unverändert, zuerst, weil das zentrale Feature.
2. Frische Claude-Code-Session (kein Kontext aus den Bau-Sessions),
   Android Emulator Skill (oder Alternative aus Abschnitt 2)
   einrichten, Emulator hochfahren, Debug-APK installieren.
3. Agent bekommt `HARDWARE_TESTPLAN.md` Teile C/D/E/G als Auftrag
   (natürlichsprachig: "gehe jeden Screen durch, prüfe X, Y, Z"),
   läuft die dortigen Schritte automatisiert durch, dokumentiert
   Befunde nach demselben Freigabe-Protokoll-Format wie Teil 10.
4. Parallel/danach: Firebase Test Lab Robo-Test als unabhängiger
   zweiter Crawler-Lauf, gratis, für Überraschungen außerhalb des
   Testplans.
5. Macrobenchmark-Suite für Teil G, insbesondere den Bibliotheks-Scroll
   (siehe oben).
6. Befunde zurück in `HARDWARE_TESTPLAN.md`/`STATUS_FORTSCHRITT.md`
   einpflegen — gleiches Dokumentationsmuster wie der Rest des
   Projekts, damit nichts wieder in einer Chat-Historie verschwindet.

## 6. Was das nicht ersetzt

BLE/M5Stick (Teil A), Bluetooth-Audio-Codec-Verhalten (B8), Xiaomi-
HyperOS-Eigenheiten (Teil E), und das subjektive "fühlt sich beim
Trainieren gut an" — dafür bleibt ein manueller Durchlauf mit echtem
Gerät am Ende sinnvoll, unabhängig davon, wie viel der Agent vorher
automatisiert abgedeckt hat.
