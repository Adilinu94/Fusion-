# Hardware-Testplan: M5StickC-Plus2 + Android-Gerät

**Stand:** 2026-08-13
**Zweck:** Sammelt ALLE Tests, die auf echter Hardware laufen müssen
(M5StickC-Plus2 als Sensor + echtes Android-Handy). Die bisherige
Arbeit ist "codeseitig abgeschlossen", aber fast nichts
geräteverifiziert. Dieses Dokument ist der Fahrplan für die
Hardware-Abnahme und das lebende Abnahmeprotokoll (nach jedem Lauf
ergänzen: Datum, Ergebnis, Artefakt).

**Bezugspunkte:**
- Gate 11b: `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md`
  Abschnitt 11b (5 Freigabe-Szenarien, nur auf echter M5-Hardware)
- Aufnahme-Anleitung + Manifest-Schema:
  `tools/golden_shadow_corpus/README.md`
- Testinfra-Plan Schritt 4 (instrumentierte Kern-Tests):
  `docs/design/TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md`
- README Schritt 13/14 (Release-Gates, Geräteabnahme)

**Regeln für alle Läufe:**
1. Jede Sitzung wird mit Datum + Ergebnis in die Tabelle am Ende
   eingetragen (Freigabe-Protokoll).
2. Rep-Zahl **direkt nach jedem Satz** von Hand notieren, nie später.
3. Bei jeder Abweichung: Logcat-Ausschnitt sichern
   (`adb logcat -d > logs/<datum>_<test-id>.txt`).
4. Die JSONL-Aufnahmen landen automatisch unter
   `/Android/data/com.dropsync/files/recordings/` und werden per
   `adb pull` gesichert, bevor die App-Daten gelöscht werden.
5. Ein Lauf zählt nur, wenn der Sensor-Zustand **STREAMING** zeigt
   (nicht nur CONNECTED).

---

## 1. Vorbereitung (einmalig)

- [ ] Debug-Build installieren: `./gradlew installDebug`
- [ ] M5StickC-Plus2 aufgeladen und mit der Sensor-Firmware bespielt
- [ ] Handy: Bildschirm-Timeout auf 10+ Minuten (Testläufe)
- [ ] USB-Kabel + `adb` verfügbar, Gerät autorisiert
- [ ] Notizblock für Handzählungen bereitlegen
- [ ] Ziel-Handy ggf. abweichend vom Entwicklungsgerät (Xiaomi mit
      HyperOS besonders wichtig, siehe Teil E)

---

## 2. Teil A: Rep-Zählung und Sensor (Gate 11b)

**Das ist der wichtigste Block.** Ohne diese Ergebnisse darf die
Zähl-Pipeline nicht als freigegeben gelten und die Flags
`accelEnabled`/`orientationTrackingEnabled` bleiben aus.

**Ablauf je Session (aus dem Corpus-README):**
1. Train-Tab öffnen (startet die Recording-Session automatisch).
2. Sensor verbinden, warten bis STREAMING.
3. Übung wählen, Sätze normal trainieren, Gewicht/Reps bestätigen.
4. Nach jedem Satz die Handzählung notieren.
5. Übung/Session abschließen → JSONL wird geschrieben.
6. JSONL + Manifest nach `tools/golden_shadow_corpus/` übertragen und
   Harness laufen lassen:
   `python tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus`

**Kern-Szenarien (aus Abschnitt 11b, alle mit M5StickC-Plus2):**

| ID | Szenario | Manifest-`scenario` | Ablauf | Erwartung |
|---|---|---|---|---|
| A1 | 5 Übungen ohne Kalibrierungsprofil (`noTemplate`) | `no_template` | Alle 5 bestätigten Übungen (Bizeps-Curl, Iso-Lateral Front Lat Pulldown, Iso-Lateral Incline Press, Plate Loaded Iso-Lateral Row, Scott/Preacher Curls), bewusst KEIN Profil anlegen | Rep-Diff = 0 je Satz gegenüber Handzählung |
| A2 | Dieselben 5 Übungen nach echter Kalibrierung | `calibrated` | Vorher Kalibrierung in der App durchführen (Profil anlegen), dann gleiche 5 Übungen | Rep-Diff = 0 je Satz; Template wirkt (der Fall, in dem Befund C greift) |
| A3 | Langsame Wiederholungen | `slow` | Pro Übung mind. 1 Satz mit stark verlangsamter exzentrischer Phase (3-4 s runter) | Keine vorzeitige Ablehnung, exzentrische Phase vollständig erfasst, Rep-Diff = 0 |
| A4 | Sensor-Reconnect mitten in der Session | `reconnect` | Während des Trainings Sensor ausschalten, 10-30 s warten, wieder einschalten; im Manifest `reconnect_before_set` setzen | Ab dem Satz nach Reconnect Rep-Diff = 0; davor dokumentierte Abweichungen erlaubt; App zeigt wieder STREAMING |
| A5 | Übungswechsel mit verschiedenen Profilen | `exercise_switch` | Zwei Übungen mit unterschiedlichen Kalibrierungsprofilen in derselben Session abwechselnd trainieren | Rep-Diff = 0 je Satz, kein Template-Übersprechen zwischen Übungen |

**Abnahmekriterium Gate 11b:** Alle 5 Szenarien über **mindestens
3 unabhängige Sessions**, Exact-Match-Rate 100 %, MAE 0. Jede
Abweichung muss dokumentiert und von Adi einzeln freigegeben werden.
Erst danach: Freigabe-Entscheidung und Flag-Aktivierung.

**Zusatzszenarien (nicht Teil des Gates, aber wichtig):**

| ID | Szenario | Ablauf | Erwartung |
|---|---|---|---|
| A6 | Falsche Rep-Zahl korrigieren | Nach einem Satz die von der App gezählte Zahl manuell ändern (`setReps`), Satz speichern | `confirmedRepsEdited=true` im JSONL; Lern-Loop aktualisiert das Profil nur mit bestätigten Werten (D3-Regel) |
| A7 | Verbindungsabbruch ohne Reconnect | Sensor ausschalten und NICHT wieder verbinden, Training fortsetzen | Kein Absturz, klarer UI-Zustand (nicht STREAMING), manuelle Eingabe weiter möglich |
| A8 | Akku-Lebensdauer | Sensor über 45-60 min durchgehend trainieren | Keine Verbindungsabbrüche durch Sensor-Energie; App-Akkuverbrauch notieren |
| A9 | Reichweite | Sensor 1-2 m vom Handy entfernt am Gerät, Handy in der Tasche | Keine Aussetzer bei normaler Trainingsdistanz |

**Beweise:** JSONL + Manifest im Corpus, Handzählungs-Notizen,
Harness-Report (Output in `docs/STATUS_FORTSCHRITT.md` als
Freigabe-Protokoll anhängen).

---

## 3. Teil B: Audio und Drop-Landung

Hier wird geprüft, ob Musik-Drops wirklich auf den Beat/Marker landen
und die Latenz-Tabellen stimmen. **Pro Route mindestens 3 Läufe.**

### B1: Drop-Landung je Route (P95-Drop-Fehler dokumentieren)

**Ablauf je Route (Lautsprecher, Kabel-Kopfhörer, Bluetooth SBC,
Bluetooth AAC/LDAC falls verfügbar, USB-DAC falls vorhanden):**
1. Song mit Marker setzen (oder importierten Marker nutzen).
2. Pausen-Timer mit DropSync-Rest starten, Work-Track auf der
   Drop-Position vorbereiten.
3. Beim Go (Countdown-Ende) landet der Drop per Crossfade.
4. Mit Stoppuhr/Video (120 fps) messen: Abstand zwischen hörbarem
   Go-Beep-Ende und hörbarem Drop.

**Erwartung:** Der Drop liegt hörbar am Marker. Abweichung je Route
notieren (Ziel: im Bereich der Tabellenwerte: Speaker 40 ms, Wired
25 ms, BT SBC 120 ms, AAC 80 ms, LDAC 150 ms, USB 30 ms). Die
Messwerte fließen in `RouteProfileStore` als kalibrierte Profile ein.

**Nachweis:** Messprotokoll je Route; bei systematischer Abweichung
Tabellenwerte im Code anpassen.

### B2: DIRECT_TO_DROP

1. Rest-Musik-Settings: DIRECT_TO_DROP aktivieren.
2. Rest starten (Musik läuft volle Restzeit), Go abwarten.

**Erwartung:** Beim Go springt die Musik direkt zur Drop-Position
(kein Vorlauf durch die Restzeit), Crossfade spielt.

### B3: Countdown-Beeps

1. Get-Ready-Countdown aktivieren (3-2-1), Lautstärke auf leise/mittel.
2. Timer laufen lassen.

**Erwartung:** Drei kurze Beeps (880 Hz) für 3-2-1, ein langer Beep
(1760 Hz) beim Go; keine Klick-/Knacks-Geräusche (Hüllkurve), Beeps
sind unabhängig von der Musiklautstärke hörbar, ändern aber nicht die
Systemlautstärke.

### B4: Rest-Ducking

1. Rest starten, Musik läuft. Prüfen: Musik wird auf -8 dB (Default)
   abgesenkt, sanft (kein harter Sprung).
2. Pause beenden / manuell übernehmen: Musik fährt sanft zurück.
3. Gleichzeitig TTS-Hinweis: kein doppeltes Ducking, lauteste Regel
   (min-Logik) greift.

### B5: Crossfade

1. Track-Skip (manuell und automatisch am Ende): Crossfade mit
   gewähltem Preset (Fade/Rise/Blend/Wave/Melt/Slam).
2. Gapless-Album/CUE-Datei: kein Crossfade, harter sauberer Übergang.
3. Bit-Perfect-Modus aktiv: Crossfade ist automatisch aus.

**Erwartung:** Keine hörbaren Klicks; bei SLAM kein Knacksen
(Mikro-Rampe); Equal-Power-Lautstärke (kein Lautstärkental in der
Mitte des Fades).

### B6: Underrun-Monitoring (manuell provoziert)

1. Während der Wiedergabe künstliche Last erzeugen: viele Apps öffnen,
   Screenshot-Serie, App-Wechsel im Kreis.
2. Zusätzlich: Bluetooth-Reichweite ausreizen (Raum verlassen) bis
   Aussetzer hörbar.

**Erwartung:** Keine Abstürze; Aussetzer werden überlebt. Notieren, ob
die App Underruns erkennt (aktuell ist der MISSED_UNDERRUN-Callback
noch NICHT angebunden - dieser Test entscheidet, ob das nachgeholt
werden muss).

### B7: Bit-Perfect / USB-DAC

Nur wenn ein USB-DAC verfügbar ist:
1. Bit-Perfect-Panel öffnen, DAC anschließen.
2. Hi-Res-Datei (96/192 kHz FLAC) abspielen.

**Erwartung:** Panel zeigt DAC-Fähigkeiten, DSP ist umgangen (EQ
ändern hat keinen Effekt), DAC zeigt native Sample-Rate. Sonst:
dokumentieren, Panel-Hinweis prüfen.

### B8: BT-Codec-Anzeige und Profilwechsel

1. BT-Kopfhörer verbinden, der AAC oder LDAC unterstützt (im
   Entwickler-Menü den Codec erzwingen für den Test).
2. Audio-Einstellungen öffnen: BT-Hinweis sichtbar.
3. Codec wechseln (z. B. LDAC → SBC) und erneut verbinden.

**Erwartung:** Die App erkennt den Codec (interne Latenz-Tabelle
wechselt auf 150/80/120 ms), getrennte Profile je Codec. Falls
Reflection-Abfrage fehlschlägt: Fallback auf SBC, kein Absturz.
(ACHTUNG: `getBluetoothCodecStatus()` ist versteckte API - genau hier
zeigt sich, ob der Reflection-Fallback auf dem echten Gerät trägt.)

### B9: AudioFocus

1. Während der Wiedergabe: Anruf annehmen → LOSS_TRANSIENT.
2. Navigation starten (Sprachanweisung) → transient duckt.
3. Zweiten Musik-Player starten → permanenter LOSS.

**Erwartung:** Transient: Musik duckt, Timer läuft weiter; Permanent:
Player stoppt. Kein Doppel-Play nach dem Anruf, korrektes Resume.

### B10: MediaSession / Bluetooth-/Headset-Tasten

1. Headset/Kopfhörer-Tasten: Play/Pause, Skip, Lautstärke.
2. Auto-Info: MediaSession-Baum durchblättern (falls Auto verfügbar).
3. Bei BT-Verbindung: "automatisch fortsetzen" Option testen.

**Erwartung:** Alle Standardbefehle funktionieren; Nutzer-Vorrang:
manuelle Skip während einer automatischen Rest-Landung bricht die
Automatik ab (kein "Kampf" zwischen App und Nutzer).

### B11: Waveform- und Lautstärke-Korrektheit (Abschluss der
Waveform-Härtung)

1. Leise gemasterten Song abspielen → Waveform sichtbar (Boden 0.35),
   nicht fast flach.
2. Sehr lauten Song (Loudness-War) abspielen → Waveform nicht
   übersteuert, Deckel greift.
3. Stille Datei / Intro mit Stille → keine angehobene flache Linie.
4. Player und Library zeigen dieselbe Waveform (Normalisierung
   identisch).

**Erwartung:** Optisch plausible Balken in allen drei Fällen; keine
Allokations-Ruckler beim Scrollen (subjektiv + logcat).

---

## 4. Teil C: Timer, Kill-Fallback, Recovery

| ID | Test | Ablauf | Erwartung |
|---|---|---|---|
| C1 | Rest-Timer im Hintergrund | Timer starten, App in den Hintergrund, 2 min warten | Timer läuft weiter, Notification zählt korrekt, Beep kommt pünktlich |
| C2 | App-Kill durch Swipe | Timer läuft, App aus Recents wischen, neu öffnen | Timer läuft im Foreground-Service weiter (falls Service aktiv) bzw. Recovery stellt den Stand wieder her |
| C3 | Xiaomi/HyperOS-Aggressivität | Auf dem Xiaomi-Gerät: Timer laufen lassen, Auto-Start/Batterie-Optimierung restriktiv | Service überlebt oder Recovery greift; dokumentieren, ob manuelle Batterie-Einstellungen nötig sind (First-Start-Hinweis, Teil E) |
| C4 | Reboot | Timer läuft (PAUSED/RUNNING), Gerät neu starten, App öffnen | RebootGuard erkennt den Neustart: bei RUNNING wird das Snapshot verworfen (kein "Geister-Timer"); bei PAUSED wird die Restzeit wiederhergestellt und der Service neu gestartet |
| C5 | Timer abschließen | Timer bis Ende laufen lassen | Snapshot wird geleert, kein Recovery beim nächsten Start |

---

## 5. Teil D: UI-Performance und Barrierefreiheit

| ID | Test | Ablauf | Erwartung |
|---|---|---|---|
| D1 | Waveform-Scrubbing | Now-Playing: Waveform hin- und herziehen, dabei Uhr/Frame-Zeit beobachten (Profil-Overlay / adb `dumpsys gfxinfo`) | Flüssiges Ziehen ohne Ruckler; keine GC-Pausen im logcat; Ziel 60 fps |
| D2 | Library-Scroll | 500-1000 Songs importieren, schnell durch die Liste scrollen (mit und ohne Mini-Waveforms) | Kein sichtbares Ruckeln; Mini-Waveforms erscheinen nur für analysierte Songs, kein Anstoßen neuer Analysen beim Scrollen |
| D3 | Erst-Import 1000 Songs | Frische Installation, 1000 Songs importieren, Zeit stoppen | Importdauer notieren (Ziel: wenige Minuten); UI bleibt bedienbar; Analyse-Queue füllt sich im Hintergrund (Batch-Anstoß), kein UI-Freeze |
| D4 | Now-Playing-Ticker | Now-Playing 5 min offen lassen, Akkuverbrauch beobachten | Kein auffälliger Mehrverbrauch durch den 200-ms-Ticker; Position läuft flüssig |
| D5 | TalkBack | TalkBack aktivieren, durch Train-Tab, Waveform (Sprung per Barrierefreiheits-Aktion), Einstellungen, EQ navigieren | Waveform liest Position (progressBarRangeInfo), alle Slider mit stateDescription, 48-dp-Ziele erreichbar |
| D6 | 200%-Schrift / Display-Größe | Systemschrift auf Maximum, alle Hauptscreens prüfen | Keine abgeschnittenen Texte/Buttons; Scrollen wo nötig |
| D7 | Dark/Light-Theme | Theme wechseln, alle Screens prüfen | Lime-Akzente konsistent, keine unlesbaren Kontraste |

---

## 6. Teil E: Berechtigungen und First-Start (Xiaomi-Fokus)

| ID | Test | Ablauf | Erwartung |
|---|---|---|---|
| E1 | First Start | Frische Installation öffnen | Berechtigungsfluss: Audio (READ_MEDIA_AUDIO), Benachrichtigungen (POST_NOTIFICATIONS) mit verständlichem Rationale; ohne Berechtigung kein leerer Screen, sondern klarer Fehlerzustand mit CTA |
| E2 | Xiaomi-Sonderwege | Auf Xiaomi/HyperOS: Benachrichtigungen verweigern → Timer starten | Verständlicher Hinweis, dass die Timer-Notification fehlt; ggf. Anleitung zum Aktivieren |
| E3 | Battery-Optimierung | Xiaomi: "Batteriesparer" aktiv, Timer + Sensor testen | Dokumentieren, ob ein Hinweis auf "Unbegrenzt" nötig ist (nicht im Code erzwungen, nur Anleitung) |
| E4 | Kein Netzwerk | Flugmodus, alle Funktionen prüfen | App vollständig offline nutzbar, keine Fehlermeldungen durch fehlendes Netz |

---

## 7. Teil F: Instrumentierte Kern-Tests (Testinfra-Plan Schritt 4)

**Status 2026-08-14: UMGEsetzt und auf dem Emulator gruen.**

1. Vorbereitung im Code (Testinfra-Plan Schritt 4) - erledigt:
   - `hilt-android-testing` + `kspAndroidTest` in `app/build.gradle.kts`
   - `testApplicationId` + `customTestApplicationClass` Runner-Argument
   - `HiltTestApplication` in `app/src/androidTest/` (bewusst OHNE
     `@HiltAndroidApp`: Hilt erlaubt nur eine Root pro Modul, die
     Produktions-`DropSyncApplication` ist die Root)
   - `androidTestImplementation(libs.androidx.media3.exoplayer/common)`
     (die Tests brauchen ExoPlayer direkt)
2. Tests angelegt:
   - `AudioTimestampExtrapolationInstrumentedTest`: echter AudioTrack,
     Warm-up-Gate, Delta-Monotonie + Extrapolator (2 Tests, gruen)
   - `AudioFocusPermanentLossInstrumentedTest`: ExoPlayer mit
     `setAudioAttributes(attrs, handleFocus=true)` identisch zur App,
     permanenter LOSS stoppt die Wiedergabe (1 Test, gruen)
   - `BleScanConnectInstrumentedTest`: manuell markiert (`@Ignore`),
     braucht echtes M5StickC (2 Tests, per @Ignore deaktiviert)
3. Ausfuehren: `./gradlew :app:connectedDebugAndroidTest` auf dem
   Emulator - BUILD SUCCESSFUL (3/3 Audio-Tests gruen).

**Underrun-Test (NICHT testbar):** `UnderrunMonitorInstrumentedTest`
wurde geprueft und bewusst NICHT angelegt: der `AudioInfoListener` im
`PlaybackService` implementiert nur `onAudioInputFormatChanged`/
`onAudioTrackInitialized`, es gibt KEIN Underrun-Monitoring
(`onAudioSinkError`/`onAudioTrackUnderrun` fehlen). Ohne Feature laesst
sich kein Underrun erkennen. Muss erst als Feature gebaut werden
(Hardware-Testplan B6 prueft den Bedarf), danach kommt der Test.

**Erwartung:** Alle nicht-BLE-Tests gruen auf dem echten Geraet; der
BLE-Test verbindet einmal komplett (Scan → Connect → Service Discovery
→ MTU-Verhandlung → STREAMING).

---

## 8. Teil G: Macrobenchmark und Baseline (Schritt 13)

Nur wenn Baseline-Profile wirklich gewünscht sind (ADR-0001 verweist
auf Schritt 13):

1. `:baselineprofile`-Modul anlegen (derzeit nicht vorhanden).
2. Macrobenchmarks auf echtem Gerät: App-Start (kalt/warm),
   Library-Scroll, Now-Playing-Öffnen, EQ-Öffnen.
3. Baseline-Profile generieren und erneut messen.

**Erwartung:** Start- und Frame-Zeiten dokumentieren; Baseline-Profile
verbessern die Startzeit messbar. Falls der Aufwand den Nutzen nicht
trägt: bewusste Entscheidung dokumentieren (ADR-0001 aktualisieren).

---

## 9. Auswertung und Freigabe

**Nach jedem Testtag:**
1. JSONL-Dateien per `adb pull` sichern, Manifeste anlegen.
2. Harness laufen lassen: `python tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus`
3. Ergebnisse in die Tabelle unten eintragen (Datum, Test-ID, Ergebnis,
   Artefakt/Log-Pfad).
4. Bei Abweichungen: Logcat + Bug-Beschreibung (ID, Schritte,
   erwartet, beobachtet) in `docs/` ablegen.

**Freigabe-Reihenfolge:**
1. Teil A (Gate 11b) → Entscheidung: Zähl-Pipeline-Freigabe +
   Flag-Aktivierung (`accelEnabled`/`orientationTrackingEnabled`).
2. Teil B1 → Latenz-Tabellen kalibrieren (RouteProfileStore) oder
   BEST_EFFORT-Hinweis in der UI schärfen.
3. Teil F → "Kein instrumentierter Test" im STATUS schließen.
4. Teile C-E → Schritt-13-Reste abhaken.

---

## 10. Freigabe-Protokoll (laufend ausfüllen)

| Datum | Test-ID | Ergebnis (PASS/FAIL/ABWEICHUNG) | Artefakt/Notiz |
|---|---|---|---|
| (Beispiel) 2026-08-20 | A1 | ... | corpus/2026-08-20_a1/ |
| | | | |
| | | | |
| | | | |

**Log-Verzeichnis:** `logs/<datum>_<test-id>.txt` (nicht eingecheckt,
aber für die Auswertung referenziert).
