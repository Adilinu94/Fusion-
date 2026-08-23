Kritische Befunde
P0: Kalibrierung und Live-Erkennung verwenden möglicherweise verschiedene Signale
CalibrationController kann GP, COMBINED oder GYRO_MAG auswählen:
- domain/sensor/.../calibration/CalibrationController.kt:234-242
chosenSignal wird anschließend nicht in CalibrationProfile gespeichert:
- domain/sensor/.../SensorModels.kt:42-56
- feature/workout/.../CalibrationViewModel.kt:114-126
Die Live-Pipeline verarbeitet immer die signierte Gyro-Projektion GP:
- domain/sensor/.../SignalChain.kt:65-75
Damit können Threshold und Template beispielsweise aus GYRO_MAG stammen, später aber auf GP angewendet werden. Eine erfolgreiche Kalibrierung kann dadurch systematisch falsch zählen.
Empfehlung: Kurzfristig den Kalibrierungs-Sweep ausschließlich auf GP begrenzen. Danach ChosenSignal versioniert im Profil persistieren und im Live-Pfad denselben Signalzweig verwenden.
P0: Die gespeicherten Schwellen entsprechen nicht der kalibrierten Schwelle
Beim Speichern wird aus theta Folgendes konstruiert:
signalPeakLevel = theta + expectedProminence
noisePeakLevel = theta * 0.5
Fundstelle:
- feature/workout/.../CalibrationViewModel.kt:121-124
Der PeakDetector berechnet seine Schwelle später erneut aus SPK und NPK. Diese neue Schwelle entspricht im Allgemeinen nicht mehr dem kalibrierten theta.
Auswirkung: Bereits das erste Live-Set läuft mit einer anderen Empfindlichkeit als die Kalibrierung.
Empfehlung: theta direkt als Profilwert persistieren oder SPK/NPK mathematisch so erzeugen, dass der PeakDetector exakt wieder theta berechnet. Dafür einen Roundtrip-Test ergänzen:
Kalibrierung -> Profil speichern -> Profil laden -> LiveDetector.threshold == theta
P0: Halbe Wiederholungen können als vollständige Wiederholung zählen
PhaseValidator akzeptiert Bewegungen, die nur aus einer positiven oder negativen Phase bestehen:
- domain/sensor/.../PhaseValidator.kt:45-53
RepCounter beendet das Fenster sogar unmittelbar, wenn noch keine negative Phase vorliegt:
- domain/sensor/.../RepCounter.kt:120-131
Die Live-Pipeline verwendet aber ein signiertes Signal. Ein reines Anheben, Stoßen oder eine abgebrochene Wiederholung kann daher als vollständige Rep durchgehen.
Empfehlung: Für signiertes GP immer beide Phasen verlangen:
- konzentrische Phase
- Richtungswechsel
- exzentrische Phase
- Mindestdauer pro Phase
- plausibles Verhältnis beider Phasen
Der vereinfachte Einphasenmodus darf nur explizit für unipolare Envelope-Signale verwendet werden.
P0: Disconnect und Übungswechsel brechen ein aktives Set nicht zuverlässig ab
Betroffene Stellen:
- feature/workout/.../TrainViewModel.kt:147-161
- feature/workout/.../TrainViewModel.kt:182-190
- feature/workout/.../TrainViewModel.kt:447-463
- feature/workout/.../TrainViewModel.kt:520-531
Bei Disconnect, Übungswechsel oder „Übung beenden“ werden nicht gemeinsam zurückgesetzt:
- countdownJob
- SetPhase
- liveEngine
- setSamples
- _liveCountedReps
- Engine-Filterzustände
Ein Disconnect während des Countdowns kann deshalb trotzdem in COUNTING übergehen. Bei einem Übungswechsel kann die alte Engine weitere Samples für die neue Übung verarbeiten.
Empfehlung: Eine einzige idempotente Funktion einführen:
private fun abortActiveSet(reason: SetAbortReason)
Sie muss alle Set-Ressourcen atomar abbrechen. Aufrufen bei:
- Disconnect
- Verbindungsverlust
- Übungswechsel
- Übung beenden
- Kalibrierungswechsel
- onCleared()
P0: Vorhandene Kalibrierung wird nach späterem Verbinden nicht geladen
loadActiveProfile() läuft bei der Übungsauswahl:
- feature/workout/.../TrainViewModel.kt:182-190
Wenn der Nutzer zuerst die Übung auswählt und danach den Sensor verbindet, war connectedDeviceId beim Laden noch null:
- feature/workout/.../TrainViewModel.kt:575-593
Danach wird das Profil nicht automatisch erneut geladen. Der Startbutton kann dadurch trotz vorhandener Kalibrierung wirkungslos bleiben.
Empfehlung: selectedExercise und connectedDeviceId kombinieren:
combine(selectedExercise, connectedDeviceId) { exercise, deviceId ->
    exercise?.id to deviceId
}
    .distinctUntilChanged()
    .flatMapLatest { ... }
Damit werden alte Abfragen automatisch verworfen und das richtige Profil geladen.
P0: BLE-Verbindung kann dauerhaft hängen
Die GATT-Queue wartet auf Callbacks. Bei requestMtu(), discoverServices() oder writeDescriptor() wird nicht geprüft, ob Android die Operation überhaupt gestartet hat:
- data/sensor/.../BleSensorProvider.kt:620-647
- data/sensor/.../BleSensorProvider.kt:697-715
Der MTU-Timeout reiht einen Retry in dieselbe blockierte Queue ein:
- data/sensor/.../BleSensorProvider.kt:273-284
Wenn kein Callback kommt, bleibt opInFlight = true. Retry und Service Discovery können nie ausgeführt werden.
Empfehlung:
- Jede GATT-Operation bekommt eine Operations-ID.
- Rückgabewert jedes Android-GATT-Aufrufs prüfen.
- Bei false sofort opDone() und Fehler liefern.
- Pro Operation einen Timeout setzen.
- Späte Callbacks alter Operations-IDs ignorieren.
- Alle Fehlerpfade über eine zentrale Cleanup-Funktion führen.
P0: Remote-Disconnect räumt den BLE-Zustand nicht vollständig auf
Bei unerwartetem Disconnect wird hauptsächlich der öffentliche Status geändert:
- data/sensor/.../BleSensorProvider.kt:246-255
Polling, Device-Event-Polling, Jitterbuffer und GATT-Objekt werden nicht vollständig beendet. isConnected kann dadurch weiterhin wahr bleiben.
Auswirkung: Alte Polling-Jobs und neue Verbindungen können sich überlagern. Das ist für zuverlässiges Zählen besonders gefährlich, weil Filter und Zeitbasis vermischt werden können.
Empfehlung: Remote-Disconnect muss denselben vollständigen Cleanup ausführen wie disconnect().
P0: Paketverluste verfälschen die zeitbasierte Erkennung
Paketlücken werden erkannt und gezählt:
- data/sensor/.../BatchDedupTracker.kt:36-43
Die Detektion arbeitet aber intern mit einem fortlaufenden lokalen Sampleindex. Verlorene Samples werden in Refraktärzeit und Rep-Dauer nicht berücksichtigt:
- domain/sensor/.../PeakDetector.kt
- domain/sensor/.../RepCounter.kt:211-229
Auswirkung: Nach BLE-Lücken wird physische Zeit komprimiert. Reps können zu nah erscheinen, Tempo-Scores werden falsch und die adaptive Refraktärzeit lernt falsche Werte.
Empfehlung: Alle Zeitentscheidungen auf SensorSample.timestampMs umstellen. Bei größeren Lücken:
- laufenden Peak verwerfen
- Pending-Rep verwerfen
- Filterzustand optional neu einschwingen
- Set als qualitativ eingeschränkt markieren
P0: Der Lernpfad leert seine Daten zu früh
logSet() startet applyCorrection(reps) und leert direkt danach setSamples:
- feature/workout/.../TrainViewModel.kt:265-273
applyCorrection() startet wiederum eine neue Coroutine und liest dieselbe mutable Liste:
- feature/workout/.../TrainViewModel.kt:555-568
Die Korrektur sieht dadurch meistens eine bereits geleerte Liste.
Empfehlung:
val correctionSamples = setSamples.toList()
setSamples.clear()

val improved = CalibrationRefiner.refine(
    samples = correctionSamples,
    correctedReps = reps,
    profile = profile,
)
Die Korrektur sollte innerhalb derselben Coroutine wie das Logging ausgeführt oder bewusst serialisiert werden.
P0: Der Refiner lernt aus weniger Peaks als der Nutzer bestätigt hat
CalibrationRefiner akzeptiert jede nicht leere Peak-Liste:
- domain/sensor/.../calibration/CalibrationRefiner.kt:34-58
Bei zehn bestätigten Reps können beispielsweise drei erkannte Peaks genügen, um das Profil zu verändern. Zusätzlich werden nur Template, Prominenz und Dauer angepasst, nicht die eigentliche Peak-Schwelle.
Empfehlung:
- Nur lernen, wenn exakt correctedReps plausible Peaks gefunden wurden.
- Kandidaten müssen zeitlich plausibel und vollständig zweiphasig sein.
- Das angepasste Profil muss erneut durch die echte Live-Pipeline laufen.
- Nur speichern, wenn die Pipeline danach den bestätigten Count reproduziert.
- Pro Set nur kleine begrenzte Parameteränderungen erlauben.
Testproblem
Der zentrale Test speist zwei Reps ein, erwartet aber nur mindestens eine:
- domain/sensor/.../RepPipelineTest.kt:53-79
assertTrue(counted >= 1)
Damit gilt eine 50-prozentige Unterzählung als Erfolg.
Auch einphasige positive Bewegungen werden in Tests ausdrücklich als gültig festgeschrieben:
- domain/sensor/.../PhaseValidatorTest.kt:25-28
Empfehlung: Der Rep-Counter braucht einen aufgezeichneten Ground-Truth-Datensatz.
Mindestens diese Szenarien sollten verbindliche Release-Gates werden:
 1. Normales Tempo, exakt 10 von 10 Reps.
 2. Langsame Reps mit Pause am Umkehrpunkt.
 3. Schnelle Reps.
 4. Halbe Reps dürfen nicht zählen.
 5. Isometrisches Halten darf nicht zählen.
 6. Sensorstoß und Armpositionierung dürfen nicht zählen.
 7. Unterschiedliche Bewegungsamplituden.
 8. Ermüdung mit kleiner werdender Amplitude.
 9. BLE-Paketverlust von 1, 2 und 5 Batches.
10. Disconnect und Reconnect während eines Sets.
11. Übungswechsel während Countdown und Zählung.
12. Sensor wird leicht anders am Arm positioniert.
13. Linker und rechter Arm.
14. Verschiedene Nutzer und Bewegungsausführungen.
Zielmetriken sollten nicht nur „Count stimmt“ sein:
- Precision
- Recall
- False positives pro Minute
- absolute Count-Abweichung
- Zeitabweichung pro erkannter Rep
- Anzahl verworfener Sets wegen schlechter Signalqualität
Weitere technische P0/P1-Probleme
Playback-Transition spielt potenziell zwei Titel gleichzeitig
TransitionManager startet den Zielplayer, übernimmt ihn aber nicht als sessionführenden Player und stoppt den alten Player nicht:
- data/playback/.../TransitionManager.kt:114-143
Notification, Bluetooth und Repository-State können daher den alten Titel zeigen.
Custom Commands melden Erfolg, bevor die Operation fertig ist
- data/playback/.../PlaybackRepositoryImpl.kt:174-230
- data/playback/.../PlaybackService.kt:323-335
- data/playback/.../PlaybackService.kt:384-418
sendCustomCommand() wird nicht abgewartet. Prepare und Arm können sich überholen.
Playback-Service gibt interne Kommandos an fremde Apps frei
Der Service ist exportiert:
- data/playback/src/main/AndroidManifest.xml:13-20
onConnect() gewährt jedem Controller auch interne Transition-Kommandos:
- data/playback/.../PlaybackService.kt:366-381
Empfehlung: Interne Kommandos nur für das eigene Package beziehungsweise dieselbe Signatur freigeben. Android Auto darf nur Browse- und Transportrechte erhalten.
Timer kann einen abgebrochenen Timer wiederherstellen
Skip und Finish löschen das Snapshot nicht synchron:
- data/timer/.../TimerService.kt:72-116
- data/timer/.../TimerService.kt:121-161
Nach Prozessende kann ein alter Timer erneut geladen werden.
Analysefehler werden dauerhaft gecacht
Ein temporärer Analysefehler wird als aktueller leerer Cacheeintrag gespeichert:
- data/audio/.../TrackAnalysisRepositoryImpl.kt:188-203
Damit wird nach einem temporären Fehler unter Umständen nie erneut analysiert.
Ungültige Trainingsdaten sind speicherbar
TrainViewModel.logSet() akzeptiert unter anderem negative Werte, NaN, Infinity und null Wiederholungen:
- feature/workout/.../TrainViewModel.kt:234-244
Die Repository-Grenze validiert ebenfalls nicht ausreichend.
Empfehlung: UI und Repository validieren:
weightKg.isFinite()
weightKg >= 0.0
reps > 0
weightKg <= MAX_REASONABLE_WEIGHT_KG
Buildzustand
Ausgeführt:
:domain:sensor:test
:data:sensor:testDebugUnitTest
:feature:workout:testDebugUnitTest
Ergebnis: erfolgreich, allerdings vollständig UP-TO-DATE.
Ausgeführt:
lintDebug --continue
Ergebnis: fehlgeschlagen in sechs Modulen:
- data:audio
- data:playback
- data:timer
- feature:library
- feature:settings
- app
Unter anderem:
- API-28-Aufruf bei minSdk 26
- API-33-Aufruf bei minSdk 26
- fehlende VIBRATE-Permission
- Media3-Opt-in-Fehler
- Theme-Attribut ab API 27 im falschen Ressourcenordner
- fehlende Übersetzungen
Damit ist der aktuelle Worktree nicht releasefähig.
Empfohlene Reihenfolge
 1. Kalibrierung und Live-Pipeline auf denselben Signalvertrag bringen.
 2. Halbe Reps zuverlässig ablehnen.
 3. Set-Lifecycle und Profil-Laden deterministisch machen.
 4. BLE-Operationsqueue, Disconnect und Timestamp-Lücken reparieren.
 5. Lernpfad mit unveränderlichen Set-Traces und echter Revalidierung umbauen.
 6. Ground-Truth-Trace-Suite mit echten Sensordaten aufbauen.
 7. Sensor-Health-Flow und Telemetrie ergänzen.
 8. Erst danach Thresholds und Filterparameter feinjustieren.
 9. Playback-, Timer- und Security-P0-Probleme beheben.
10. Alle Lint-Fehler beseitigen und Lint als CI-Gate aktivieren.
Die größte Verbesserung für zuverlässiges Zählen wäre nicht ein komplexerer Algorithmus, sondern ein konsistenter, timestamp-basierter Signalvertrag mit aufgezeichneten realen Testdaten.
▣  Build · GPT-5.6 Sol · 2m 27s
Kannst du bitte ein detailierten Umbauplan schrieben
Thought: 12.2s
Planning detailed refactor phasesDefining phases with acceptance criteriaIncorporating feature flags and rollout strategy
Detaillierter technischer Umbauplan
Ziel
Das Wiederholungszählen soll nicht nur unter idealen Testbedingungen funktionieren, sondern bei realen Trainingseinheiten zuverlässig bleiben:
- unterschiedliche Bewegungsgeschwindigkeiten
- kleiner werdende Bewegungsamplitude bei Ermüdung
- halbe oder abgebrochene Wiederholungen
- Sensorstöße und Positionswechsel
- BLE-Paketverluste
- Verbindungsabbrüche und Reconnects
- verschiedene Übungen und Nutzer
- nachträglich korrigierte Wiederholungszahlen
Der Umbau erfolgt schrittweise. Die bestehende manuelle Satzeingabe bleibt währenddessen immer funktionsfähig.
Zielarchitektur
BLE-Paket
    ↓
Protokollvalidierung
    ↓
Timestamp- und Sequenznormalisierung
    ↓
SensorHealth / Paketlücken
    ↓
Signalvorverarbeitung
    ↓
Kalibriertes Signal
    ↓
Rep-State-Machine
    ↓
Qualitätsbewertung
    ↓
RepEvent
    ↓
ActiveSetController
    ↓
TrainViewModel
    ↓
Nutzerbestätigung / Korrektur
    ↓
Offline-Reanalyse und Profilverbesserung
Zentrale Regel:
Kalibrierung, Live-Erkennung und Korrekturanalyse müssen exakt dieselbe Signalpipeline verwenden.
Phase 0: Sicherheitsnetz und Ausgangsbasis
Ziel
Bevor der Algorithmus verändert wird, muss der aktuelle Zustand reproduzierbar messbar sein.
Änderungen
0.1 Zählpipeline versionieren
Neue Typen:
enum class RepEngineVersion {
    V1_CURRENT,
    V2_RELIABLE,
}
Jedes Kalibrierungsprofil erhält:
val engineVersion: RepEngineVersion
val schemaVersion: Int
Betroffene Dateien:
- domain/sensor/.../SensorModels.kt
- data/sensor/.../DataStoreCalibrationProfileRepository.kt
- zugehörige Repository-Tests
Alte Profile dürfen nicht stillschweigend mit der neuen Pipeline verwendet werden.
0.2 Bestehenden Shadow-Modus weiterverwenden
Die neue Pipeline läuft zunächst parallel, ohne die sichtbare Wiederholungszahl zu beeinflussen.
Pro Set werden gespeichert beziehungsweise protokolliert:
data class RepEvaluation(
    val exerciseId: Long,
    val deviceId: String,
    val engineVersion: RepEngineVersion,
    val predictedReps: Int,
    val confirmedReps: Int,
    val wasEdited: Boolean,
    val signalQuality: SignalQuality,
    val rejectedCandidates: Int,
    val packetLossRate: Double,
)
0.3 Aktuelle Fehlerfälle als Tests festschreiben
Vor dem Umbau müssen Tests ergänzt werden, die aktuell fehlschlagen:
- zwei vollständige Reps müssen exakt zwei ergeben
- eine halbe Rep ergibt null
- reiner positiver Ausschlag ergibt null
- Sensorstoß ergibt null
- Disconnect beendet das Set
- Übungswechsel beendet das Set
- Paketlücke verändert nicht unbemerkt die Zeitbasis
- Profil wird nach späterem Verbinden geladen
- Korrektur verwendet eine unveränderliche Samplekopie
Abnahmekriterien
- Alle bekannten Fehler sind als fehlschlagende Tests reproduzierbar.
- Der aktuelle Algorithmus bleibt über V1_CURRENT verfügbar.
- Neue Arbeiten erfolgen hinter V2_RELIABLE.
- Kein bestehendes Kalibrierungsprofil wird falsch interpretiert.
Phase 1: Einheitlicher Signalvertrag
Ziel
Kalibrierung und Live-Zähler verwenden denselben Signaltyp, dieselben Filter und dieselben Parameter.
Problem
CalibrationController kann unterschiedliche Signale auswählen. Die Live-Pipeline verarbeitet aktuell immer GP.
Änderungen
1.1 Signaltyp explizit modellieren
enum class RepSignalKind {
    SIGNED_GYRO_PROJECTION,
    GYRO_MAGNITUDE,
    COMBINED_GYRO_ACCEL,
}
Das Kalibrierungsprofil erhält:
val signalKind: RepSignalKind
1.2 Einen gemeinsamen Signalprozessor einführen
interface RepSignalProcessor {
    fun process(sample: SensorSample): RepSignalFrame
    fun reset()
}
data class RepSignalFrame(
    val timestampMs: Long,
    val signedValue: Double,
    val envelope: Double,
    val accelActivity: Double,
    val isSettled: Boolean,
)
Der gemeinsame Prozessor wird verwendet von:
- Guided Calibration
- Live Rep Counter
- Shadow Counter
- Calibration Refiner
- Recorded-Trace-Tests
1.3 Kurzfristig nur signiertes GP freigeben
Bis andere Signalarten mit echten Hardware-Traces verifiziert sind:
signalKind = RepSignalKind.SIGNED_GYRO_PROJECTION
GYRO_MAGNITUDE und COMBINED_GYRO_ACCEL bleiben experimentell.
1.4 Threshold direkt speichern
Das Profil erhält:
val detectionThreshold: Double
val noiseFloor: Double
val expectedProminence: Double
Die fehleranfällige Rekonstruktion aus künstlich erzeugten SPK/NPK-Werten entfällt zunächst.
Später kann adaptive SPK/NPK-Logik intern wieder ergänzt werden, darf aber den kalibrierten Startwert nicht verändern.
Betroffene Dateien
- domain/sensor/.../SensorModels.kt
- domain/sensor/.../SignalChain.kt
- domain/sensor/.../ExerciseEnginePipeline.kt
- domain/sensor/.../PeakDetector.kt
- domain/sensor/.../calibration/CalibrationController.kt
- domain/sensor/.../calibration/CalibrationRefiner.kt
- feature/workout/.../CalibrationViewModel.kt
- feature/workout/.../TrainViewModel.kt
- data/sensor/.../DataStoreCalibrationProfileRepository.kt
Abnahmekriterien
- Kalibrierung und Live-Pipeline produzieren für denselben Trace identische vorverarbeitete Signalwerte.
- Der gespeicherte Threshold entspricht nach Laden exakt dem kalibrierten Threshold.
- Signalart und Engine-Version sind Bestandteil des Profils.
- Unbekannte oder alte Profilversionen führen kontrolliert zu „Neu kalibrieren“.
Phase 2: BLE- und Timestamp-Pipeline stabilisieren
Ziel
Paketverlust, Duplikate und Disconnects dürfen weder die Zeitbasis noch den Pipelinezustand beschädigen.
2.1 GATT-Operationsqueue umbauen
Jede Operation erhält einen expliziten Zustand:
data class GattOperation(
    val id: Long,
    val type: GattOperationType,
    val timeoutMs: Long,
)
Operationen:
enum class GattOperationType {
    REQUEST_MTU,
    DISCOVER_SERVICES,
    ENABLE_NOTIFICATION,
    READ_CHARACTERISTIC,
    WRITE_CHARACTERISTIC,
}
Erforderliches Verhalten:
- Android-Rückgabewert prüfen
- bei false sofort fehlschlagen
- Callback-Timeout pro Operation
- Queue nach Timeout freigeben
- späte Callbacks über Operations-ID ignorieren
- maximal ein aktiver GATT-Aufruf
- keine frei erzeugten CoroutineScopes pro Operation
2.2 Zentralen Disconnect-Cleanup einführen
private suspend fun cleanupConnection(reason: DisconnectReason)
Cleanup muss durchführen:
- Polling-Job abbrechen
- Device-Event-Job abbrechen
- MTU-Timeout abbrechen
- GATT-Queue leeren
- Pending Reads/Writes beenden
- Jitterbuffer stoppen und leeren
- Dedup-Tracker zurücksetzen
- Characteristics löschen
- GATT schließen
- Device-ID löschen
- Zustand auf DISCONNECTED setzen
Diese Funktion wird verwendet bei:
- manuellem Disconnect
- Remote-Disconnect
- Scan-Timeout
- fehlendem Service
- fehlender Characteristic
- GATT-Timeout
- Permission-Fehler
- Protokollfehler
2.3 Scan immer sauber beenden
Bei Erfolg, Fehler und Cancellation muss stopScan() ausgeführt werden.
2.4 Protokollversion prüfen
Bei V2-Paketen muss das Versionsbyte validiert werden.
Unbekannte Versionen werden verworfen und als Sensorfehler gemeldet.
2.5 Device-Timestamps verwenden
Zeitentscheidungen dürfen nicht mehr ausschließlich vom lokalen Sampleindex abhängen.
Neue Normalisierung:
data class TimedSensorSample(
    val sample: SensorSample,
    val monotonicTimestampMs: Long,
    val sequenceNumber: Long?,
    val gapBeforeMs: Long,
)
2.6 Paketlücken behandeln
Bei kleinen Lücken:
- Zeitbasis bleibt erhalten
- keine künstlichen Samples erfinden
- Dauerberechnungen verwenden Timestamps
Bei großen Lücken:
gap >= 150–250 ms
- laufenden Peak verwerfen
- Pending-Rep abbrechen
- Filter optional neu einschwingen
- Signalqualität herabsetzen
Abnahmekriterien
- Kein GATT-Aufruf kann die Queue dauerhaft blockieren.
- Scan endet nach erfolgreichem Fund.
- Remote-Disconnect räumt alle Jobs und Zustände auf.
- Reconnect startet mit vollständig leerem Filter- und Peakzustand.
- Paketverlust verändert nicht die physische Rep-Dauer.
- Unbekannte Firmwareprotokolle werden nicht als gültige Samples interpretiert.
Phase 3: Sensor-Health und Signalqualität
Ziel
Die App muss erkennen, wann eine Wiederholungszahl nicht vertrauenswürdig ist.
Neues Modell
data class SensorHealth(
    val connectionState: SensorConnectionState,
    val receivedBatches: Long,
    val duplicateBatches: Long,
    val missedBatches: Long,
    val parseErrors: Long,
    val jitterBufferDrops: Long,
    val largestGapMs: Long,
    val recentPacketLossRate: Double,
    val quality: SignalQuality,
)
enum class SignalQuality {
    GOOD,
    DEGRADED,
    UNRELIABLE,
}
Regeln
Beispielhafte Startwerte:
Zustand
GOOD
DEGRADED
UNRELIABLE
Die genauen Grenzwerte müssen mit Hardware-Traces validiert werden.
Verhalten im Set
Bei GOOD:
- normal zählen
Bei DEGRADED:
- weiterzählen
- sichtbaren Qualitätshinweis anzeigen
- Ergebnis vor dem Speichern bestätigen lassen
Bei UNRELIABLE:
- aktive Kandidaten verwerfen
- automatische Zählung pausieren
- manuelle Eingabe weiterhin erlauben
- Set nicht als Trainingsdaten für den Lernpfad verwenden
Abnahmekriterien
- Paketverlust ist für UI, Tests und Logging sichtbar.
- Ein unzuverlässiger Stream trainiert niemals automatisch das Profil.
- Die App behauptet bei schlechtem Signal nicht, die Zahl sei zuverlässig.
Phase 4: Rep-State-Machine neu aufbauen
Ziel
Eine Rep wird erst gezählt, wenn ein vollständiger Bewegungszyklus erkannt wurde.
Empfohlene Zustände
enum class RepPhase {
    IDLE,
    CONCENTRIC,
    TURNAROUND,
    ECCENTRIC,
    COMPLETE,
    REJECTED,
}
Ablauf
IDLE
  ↓ Mindestaktivität überschritten
CONCENTRIC
  ↓ Peak + Mindestdauer
TURNAROUND
  ↓ Richtungswechsel bestätigt
ECCENTRIC
  ↓ Rückkehr Richtung Baseline
COMPLETE
Abbruchpfade:
zu kurz
zu lang
kein Richtungswechsel
Signalqualität verloren
Paketlücke
zweiter Peak ohne Rückkehr
Amplitude zu klein
Regeln für signiertes GP
Eine gültige Rep benötigt:
- positive oder negative Startphase
- Mindestamplitude
- Mindestprominenz
- Mindestdauer der ersten Phase
- echten Richtungswechsel
- zweite Phase mit Mindestdauer
- Rückkehr in Richtung Ausgangsniveau
- Gesamtdauer innerhalb plausibler Grenzen
- Ende der Refraktärzeit
Die Richtung darf je Übung beziehungsweise Sensorausrichtung invertiert sein. Entscheidend ist nicht „positiv zuerst“, sondern „Phase A, Richtungswechsel, Phase B“.
Keine Einphasen-Akzeptanz
Der aktuelle Sonderfall aus PhaseValidator wird für signierte Signale entfernt.
Falls später Magnitudensignale verwendet werden, bekommen sie einen separaten Validator:
interface RepCycleValidator
Implementierungen:
SignedTwoPhaseValidator
EnvelopeCycleValidator
Zeit statt Sampleanzahl
Nicht:
durationSamples
refractorySamples
Sondern primär:
durationMs
refractoryMs
Sampleanzahlen dürfen nur noch diagnostisch verwendet werden.
Abnahmekriterien
- Halbe Reps zählen nicht.
- Reines Anheben ohne Rückbewegung zählt nicht.
- Sensorstöße zählen nicht.
- Langsame vollständige Reps zählen.
- Pause am Umkehrpunkt ist innerhalb definierter Grenzen erlaubt.
- Schnelle Reps werden nicht durch statische Samplegrenzen verworfen.
- Eine Rep erzeugt exakt ein RepEvent.
Phase 5: Kalibrierung neu strukturieren
Ziel
Die Kalibrierung muss reproduzierbar sein und genau die Parameter liefern, die später verwendet werden.
Neuer Ablauf
Schritt 1: Sensorposition prüfen
- Verbindung ist STREAMING
- Device-ID stimmt
- Sample-Rate liegt im erwarteten Bereich
- Paketverlust ist niedrig
- Arm liegt ruhig
Schritt 2: Ruheprofil aufnehmen
Ermitteln:
- Gyro-Bias
- Rauschboden
- Accel-Rauschboden
- effektive Sample-Rate
- Sensorqualität
Schritt 3: Einzelne vollständige Rep
Ermitteln:
- dominante Rotationsachse
- Bewegungsrichtung
- grobe Amplitude
- Phasenstruktur
- ungefähre Rep-Dauer
Schritt 4: Bekannter Satz
Der Nutzer führt beispielsweise fünf vollständige Reps aus.
Die Kalibrierung muss exakt fünf Zyklen finden. Andernfalls:
- nicht speichern
- verständlich erklären, was fehlgeschlagen ist
- Schritt wiederholen
Schritt 5: Langsamer Satz
Damit werden Tempo- und Haltevarianten abgedeckt.
Schritt 6: Profilvalidierung
Vor dem Speichern wird der komplette aufgezeichnete Kalibrierungstrace durch die echte Live-Pipeline geschickt.
Nur speichern, wenn:
predictedReps == knownReps
und:
false positives in rest windows == 0
Profilmodell
data class CalibrationProfile(
    val schemaVersion: Int,
    val engineVersion: RepEngineVersion,
    val exerciseId: Long,
    val deviceId: String,
    val signalKind: RepSignalKind,
    val sampleRateHz: Double,
    val rotationAxis: List<Double>,
    val gyroBias: List<Double>,
    val detectionThreshold: Double,
    val noiseFloor: Double,
    val expectedProminence: Double,
    val expectedDurationMs: Long,
    val minRepDurationMs: Long,
    val maxRepDurationMs: Long,
    val refractoryMs: Long,
    val repTemplate: List<Double>,
    val qualityScore: Double,
    val createdAtEpochMs: Long,
)
Kalibrierungsabbruch
Kalibrierung wird sofort abgebrochen bei:
- Disconnect
- Device-ID-Wechsel
- Paketverlust über Grenzwert
- App-Hintergrund, falls Stream nicht garantiert bleibt
- Übungswechsel
- Protokollfehler
Abnahmekriterien
- Nur ein vollständiger und validierter Trace kann gespeichert werden.
- Kalibrierung und Live-Pipeline liefern auf demselben Trace denselben Count.
- Profile sind pro Übung, Gerät und Engine-Version getrennt.
- Schlechte Kalibrierungen werden nicht gespeichert.
- Reconnect oder Gerätewechsel kann keinen gemischten Profiltrace erzeugen.
Phase 6: ActiveSetController einführen
Ziel
Der Lifecycle eines gezählten Sets darf nicht lose in TrainViewModel verteilt sein.
Neues Zustandsmodell
sealed interface ActiveSetState {
    data object Idle : ActiveSetState

    data class Countdown(
        val exerciseId: Long,
        val deviceId: String,
        val remainingSeconds: Int,
    ) : ActiveSetState

    data class Counting(
        val exerciseId: Long,
        val deviceId: String,
        val startedAtMs: Long,
        val repCount: Int,
        val signalQuality: SignalQuality,
    ) : ActiveSetState

    data class Review(
        val exerciseId: Long,
        val deviceId: String,
        val predictedReps: Int,
        val trace: SetTrace,
        val signalQuality: SignalQuality,
    ) : ActiveSetState
}
Neuer Controller
class ActiveSetController
Verantwortlichkeiten:
- Countdown
- Engineerzeugung
- Samplebuffer
- RepEvents
- Disconnect-Reaktion
- Übungswechsel
- Set beenden
- Set abbrechen
- unveränderlichen SetTrace erzeugen
TrainViewModel hält nur UI-Eingaben und ruft den Controller auf.
Zentrale Abbruchfunktion
suspend fun abort(reason: SetAbortReason)
Sie setzt garantiert zurück:
- Countdown
- Engine
- RepCount
- Samplebuffer
- Pending-Rep
- Qualitätsstatus
- Jobs
- aktive Übung und Device-Bindung des Sets
Profilbindung
Beim Setstart werden diese Werte eingefroren:
exerciseId
deviceId
profileVersion
engineVersion
Ein späterer UI-Wechsel darf ein laufendes Set nicht still auf eine andere Übung umhängen.
Abnahmekriterien
- Disconnect während Countdown endet in Idle.
- Disconnect während Counting endet kontrolliert.
- Übungswechsel bricht das alte Set ab.
- Reconnect verwendet immer eine neue Engine.
- Kein Sample eines alten Sets gelangt in ein neues Set.
- onCleared() beendet Controller und Jobs.
Phase 7: Korrektur- und Lernpfad sicher machen
Ziel
Nutzerkorrekturen sollen das Profil verbessern, dürfen es aber niemals verschlechtern.
7.1 Unveränderlichen SetTrace verwenden
data class SetTrace(
    val exerciseId: Long,
    val deviceId: String,
    val engineVersion: RepEngineVersion,
    val profileVersion: Int,
    val samples: List<TimedSensorSample>,
    val predictedReps: Int,
    val signalQuality: SignalQuality,
)
Vor jeder asynchronen Verarbeitung:
val trace = activeSamples.toList()
Keine Coroutine darf eine mutable Liste lesen, die später geleert wird.
7.2 Nur gute Sets lernen lassen
Lernen ist ausgeschlossen bei:
- SignalQuality.UNRELIABLE
- Disconnect
- Paketverlust über Grenzwert
- manuell abgebrochenem Set
- zu kurzer Aufnahme
- Device-Wechsel
- Profilwechsel während des Sets
7.3 Korrektur als Parametersuche
Der Refiner soll nicht einfach die stärksten Peaks übernehmen.
Stattdessen:
1. Kandidatenparameter erzeugen.
2. Trace durch echte Pipeline laufen lassen.
3. Ergebnis mit correctedReps vergleichen.
4. False Positives in Ruhebereichen prüfen.
5. Beste konservative Parameterkombination auswählen.
6. Parameteränderung begrenzen.
7. Erst nach erfolgreicher Revalidierung speichern.
7.4 Profil nicht sofort überschreiben
Profile erhalten Revisionen:
val revision: Int
val parentRevision: Int?
Neue Profile werden zunächst als Kandidat gespeichert. Aktivierung erst nach mehreren erfolgreichen Sets.
Beispiel:
3 aufeinanderfolgende korrigierte oder bestätigte Sets
keine Verschlechterung
gleiche Übung
gleiches Gerät
gute Signalqualität
7.5 Rollback ermöglichen
Wenn die Genauigkeit nach einer Anpassung sinkt:
- vorherige Revision reaktivieren
- problematische Revision markieren
- Nutzer muss nicht neu kalibrieren
Abnahmekriterien
- Eine Korrektur arbeitet immer mit vollständigem unveränderlichem Trace.
- Weniger Peaks als bestätigte Reps verändern das Profil nicht.
- Ein neues Profil muss den korrigierten Count reproduzieren.
- Schlechte Signalqualität trainiert nicht.
- Speicherfehler werden sichtbar behandelt.
- Profilrollback ist möglich.
Phase 8: Ground-Truth-Testsystem
Ziel
Zuverlässigkeit wird mit realen Daten gemessen, nicht nur mit synthetischen Dreiecken.
Trace-Format
data class RecordedSensorTrace(
    val id: String,
    val exercise: String,
    val deviceModel: String,
    val sensorPlacement: String,
    val sampleRateHz: Double,
    val samples: List<SensorSample>,
    val expectedRepTimestampsMs: List<Long>,
    val invalidMotionWindows: List<LongRange>,
    val packetLossEvents: List<PacketLossEvent>,
)
Speicherung beispielsweise als komprimiertes JSON oder binäres Fixture unter:
domain/sensor/src/test/resources/traces/
Pflichtdatensätze
Kategorie
normale Reps
schnelle Reps
langsame Reps
Ermüdung
halbe Reps
Sensorstöße
isometrisches Halten
Paketverlust
Disconnect/Reconnect
unterschiedliche Nutzer
unterschiedliche Übungen
Metriken
data class RepMetrics(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val countError: Int,
    val meanTimingErrorMs: Double,
)
Vorgeschlagene Release-Gates
Für gut kalibrierte, vollständige Sets:
Precision >= 98 %
Recall >= 97 %
mittlere Count-Abweichung <= 0,2 Reps pro 10 Reps
keine False Positives in Ruhe-Traces
Für degradierte BLE-Traces:
keine Phantom-Reps
unzuverlässige Sets werden als solche erkannt
kein still falscher Count
Abnahmekriterien
- Jeder Algorithmuswechsel läuft über alle aufgezeichneten Traces.
- Tests erwarten exakte Counts, nicht >= 1.
- False Positives und False Negatives werden getrennt ausgewiesen.
- Regressionen blockieren den Build.
Phase 9: UI und Observability
Ziel
Der Nutzer soll erkennen, ob automatische Zählung aktiv und vertrauenswürdig ist.
Train-UI
Während des Sets:
8 Wiederholungen
Signal: Gut
Bei Problemen:
Verbindung instabil
Zählung bitte prüfen
Bei unzuverlässigem Signal:
Automatische Zählung pausiert
Wiederholungen manuell eingeben
Entwicklerdiagnose
Nur in Debug-Builds:
- effektive Sample-Rate
- Paketverlust
- größter Timestamp-Gap
- aktiver Threshold
- aktive Profilrevision
- Engine-Version
- verworfene Kandidaten mit Grund
- letzte Rep-Qualität
- Phase der State Machine
Ablehnungsgründe typisieren
Nicht nur Strings:
enum class RepRejectionReason {
    TOO_SHORT,
    TOO_LONG,
    NO_DIRECTION_CHANGE,
    INCOMPLETE_RETURN_PHASE,
    LOW_PROMINENCE,
    TEMPLATE_MISMATCH,
    LOW_QUALITY,
    SENSOR_GAP,
    ACCEL_VOTE_FAILED,
}
Damit können Tests und Telemetrie zuverlässig auswerten, warum Reps fehlen.
Abnahmekriterien
- Nutzer sieht unzuverlässige Zählung.
- Jede verworfene Kandidatenbewegung besitzt einen strukturierten Grund.
- Debug-Ausgaben enthalten keine personenbezogenen Daten.
- Release-Builds schreiben keine vollständigen Sensordaten ohne explizite Zustimmung.
Phase 10: Weitere technische P0-Probleme
Diese Arbeiten können teilweise parallel erfolgen, sollten aber vor Release abgeschlossen sein.
10.1 Playback-Transition korrigieren
Betroffene Dateien:
- data/playback/.../TransitionManager.kt
- data/playback/.../PlaybackService.kt
- data/playback/.../PlaybackRepositoryImpl.kt
Erforderlich:
- genau ein sessionführender Player
- Zielplayer nach Transition übernehmen
- alten Player stoppen und freigeben
- Custom Commands wirklich abwarten
- Fehlerresultate transportieren
- Prepare/Arm-Race verhindern
- Pause während Crossfade respektieren
10.2 Playback-Service absichern
Interne Transition-Kommandos nur für:
controller.packageName == applicationId
oder gleiche App-Signatur freigeben.
Android Auto erhält nur Browse- und Transportkommandos.
10.3 Timer-Persistenz serialisieren
Skip und Finish müssen in dieser Reihenfolge erfolgen:
Timer abbrechen
Snapshot synchron löschen
monotone Zeit speichern
Foreground beenden
Service stoppen
Danach darf kein neuer Tick gestartet werden.
10.4 Analysefehler unterscheiden
Unterscheiden:
SEALED_FAILURE
TEMPORARY_FAILURE
CANCELLED
SUCCESS
- CancellationException erneut werfen
- temporäre Fehler mit WorkManager-Retry
- permanente Decoderfehler separat speichern
- leeres Ergebnis nicht als erfolgreichen aktuellen Cache behandeln
10.5 Eingabevalidierung
Sowohl UI als auch Repository prüfen:
Gewicht ist endlich
Gewicht >= 0
Gewicht innerhalb Long-Konvertierung
Reps > 0
Reps unter sinnvoller Obergrenze
Room erhält langfristig passende CHECK-Constraints.
Phase 11: Lint, CI und Release-Gates
Aktueller Zustand
lintDebug scheitert aktuell in sechs Modulen.
Zu beheben
- API-28-Aufruf bei minSdk 26
- API-33-Aufruf bei minSdk 26
- fehlende VIBRATE-Permission
- fehlende Media3-Opt-ins
- API-27-Themeattribute in values-v27
- fehlende Übersetzungen
- weitere Modul-Lintfehler
CI-Pipeline
Jeder Pull Request muss mindestens ausführen:
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat :app:assembleDebug
Für Sensoränderungen zusätzlich:
.\gradlew.bat :domain:sensor:test
.\gradlew.bat :data:sensor:testDebugUnitTest
.\gradlew.bat :feature:workout:testDebugUnitTest
Später zusätzlich:
RecordedTraceRegressionTest
BLE lifecycle instrumentation tests
API-26 emulator smoke test
API-36 emulator smoke test
Abnahmekriterien
- Keine Lint-Baseline zum Verstecken bestehender Fehler.
- Alle Lint-Errors sind behoben.
- Tests laufen nicht nur UP-TO-DATE, sondern regelmäßig clean in CI.
- Release ist blockiert, wenn Sensor-Ground-Truth-Gates unterschritten werden.
Umsetzungspakete
Paket A: Zählkern stabilisieren
Enthält:
- Signalvertrag
- Threshold-Roundtrip
- zweiphasige Rep-State-Machine
- timestampbasierte Dauer
- exakte Unit-Tests
Abhängigkeit: Phase 0
Risiko: hoch
Priorität: sofort
Paket B: BLE stabilisieren
Enthält:
- GATT-Operationsqueue
- Timeouts
- zentraler Cleanup
- Scanabschluss
- Protokollvalidierung
- Timestamp-Gaps
- SensorHealth
Abhängigkeit: Phase 0
Risiko: hoch
Priorität: sofort
Paket A und Paket B können parallel entwickelt werden.
Paket C: Set-Lifecycle stabilisieren
Enthält:
- ActiveSetController
- Profilreaktion auf Übung und Device
- deterministischer Abort
- unveränderlicher SetTrace
- Reconnect-Verhalten
Abhängigkeit: Paket A und SensorHealth-Vertrag aus Paket B
Risiko: hoch
Priorität: direkt danach
Paket D: Kalibrierung und Lernen
Enthält:
- neue Profilstruktur
- Validierung mit echter Pipeline
- sichere Korrekturanalyse
- Profilrevisionen
- Rollback
Abhängigkeit: Paket A und Paket C
Risiko: hoch
Priorität: danach
Paket E: Reale Hardware-Validierung
Enthält:
- Trace-Recorder
- Ground-Truth-Format
- echte Übungsdaten
- Regressionstests
- Metriken
- Release-Gates
Abhängigkeit: kann früh begonnen werden; finale Bewertung nach Paket D
Risiko: mittel
Priorität: parallel so früh wie möglich
Paket F: Allgemeine technische Stabilität
Enthält:
- Playback-Transition
- MediaSession-Security
- Timer-Persistenz
- Analyse-Retry
- Eingabevalidierung
- Lint
Abhängigkeit: weitgehend unabhängig
Risiko: mittel bis hoch
Priorität: vor Release
Empfohlene Reihenfolge der konkreten Änderungen
 1. Bestehende Fehler als Tests reproduzieren.
 2. Engine- und Profilversion einführen.
 3. Kalibrierung vorübergehend auf SIGNED_GYRO_PROJECTION begrenzen.
 4. Threshold direkt persistieren und Roundtrip testen.
 5. Einphasen-Akzeptanz entfernen.
 6. Rep-State-Machine timestampbasiert implementieren.
 7. GATT-Queue mit Operationstimeouts umbauen.
 8. Zentralen BLE-Cleanup implementieren.
 9. SensorHealth veröffentlichen.
10. ActiveSetController aus TrainViewModel extrahieren.
11. Profil-Laden über Exercise- und Device-Flows modellieren.
12. Korrektur auf unveränderliche SetTraces umstellen.
13. Kalibrierung mit echter Live-Pipeline revalidieren.
14. Profilrevisionen und Rollback ergänzen.
15. Hardware-Traces sammeln und Ground-Truth-Suite aufbauen.
16. Neue Pipeline zunächst im Shadow-Modus messen.
17. Erst nach Erreichen der Zielmetriken als sichtbaren Zähler aktivieren.
18. Playback-, Timer-, Security- und Lintprobleme schließen.
19. API-26- und API-36-Smoke-Tests durchführen.
20. Release-Gates verbindlich in CI aktivieren.
Definition Of Done
Der Umbau ist abgeschlossen, wenn:
- Kalibrierung und Live-Erkennung dieselbe Signalpipeline verwenden.
- Zwei vollständige Reps exakt zweimal gezählt werden.
- Halbe Reps und Sensorstöße nicht zählen.
- Paketverlust die Zeitbasis nicht verfälscht.
- Disconnect und Übungswechsel keine alte Engine weiterlaufen lassen.
- Korrekturen nur anhand vollständiger unveränderlicher Traces lernen.
- Das neue Profil nach Reanalyse den bestätigten Count reproduziert.
- Ground-Truth-Traces die festgelegten Precision- und Recall-Gates erfüllen.
- Nutzer bei schlechtem Signal gewarnt werden.
- Manuelle Eingabe jederzeit als sicherer Fallback funktioniert.
- testDebugUnitTest, lintDebug und assembleDebug vollständig grün sind.
- Keine kritischen Playback-, Timer- oder MediaSession-Sicherheitsprobleme verbleiben.
