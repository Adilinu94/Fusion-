# Rep-Zählung — Umbauplan (Extrem ausführlich)

**Datum:** 2026-08-12
**Status:** Entwurf
**Zweck:** Systematische Behebung aller Schwachstellen der IMU-basierten
Rep-Zählung in `ExerciseEnginePipeline` (domain/sensor) und ihrer
Einbettung in `TrainViewModel` (feature/workout). So detailliert, dass eine
schwächere KI jeden Punkt korrekt umsetzen kann.

**Referenzen (intern):**
- `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` → [Shadow-DoD](FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md#11b-shadow-dod)
- `docs/design/SHADOW_DIFF_HARNESS_PLAN.md` → D2/D3/D4, §11b
- `docs/design/TESTINFRASTRUKTUR_UMBAUPLAN_2026-08-10.md` → 5a/5b, Z. 205–226
- `docs/STATUS_FORTSCHRITT.md` → Abschnitte E, F, ADR-0014
- `docs/adr/0014-neue-pipeline-zaehlt-live-ohne-11b-hardwarefreigabe.md`
- `domain/sensor/.../ExerciseEnginePipeline.kt`
- `domain/sensor/.../SignalChain.kt`
- `domain/sensor/.../PeakDetector.kt`
- `domain/sensor/.../RepCounter.kt`
- `domain/sensor/.../TemplateMatcher.kt`
- `domain/sensor/.../PhaseValidator.kt`
- `domain/sensor/.../QualityScorer.kt`
- `domain/sensor/.../calibration/CalibrationRefiner.kt`
- `domain/sensor/.../SensorModels.kt`
- `feature/workout/.../TrainViewModel.kt`
- `feature/workout/.../shadow/ShadowSessionRecorder.kt`
- `data/sensor/.../BleSensorProvider.kt`

**Referenzen (extern / Forschung):**
- Balestra et al. 2021: Accel+Gyro 95.6% Zählgenauigkeit. Gyro allein
  schlechter als Kombination.
- RecoFit (Morris et al. 2014, CHI): ±1 Rep in 93%, Erkennung 96–99%.
  Dataset: github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors
  (MATLAB, archived).
- LiftRight (2019): 96% Set/Rep-Genauigkeit.
- Yurtman & Barshan: Multi-Template DTW 93.5% Klassifikation.
- Filippou et al. 2023: DTW-Barycenter-Averaging für Template-Update.
- Prabhu et al. 2020: Peak-Detector vs CNN — CNN robuster bei Variation,
  Peak konkurrenzfähig auf sauberen Signalen.
- Lim et al. 2024: Few-Shot Siamese/Triplet, 86.8% für ungesehene Übungen.
- MM-Fit: Multi-Device/RGB-D-Referenz-Korpus (öffentlich).

---

## Übersicht: 9 Verbesserungspunkte

| # | Priorität | Titel | Aufwand | Risiko-Reduktion |
|---|---|---|---|---|
| 1 | SOFORT | Shadow-Engine mit Profil-Achse/Bias | 1h | Hoch (falscher Diff) |
| 2 | SOFORT | SPK/NPK aus Profil laden | 1h | Hoch (Defaults falsch) |
| 3 | SOFORT | QualityScorer-Schwelle 0.4 vs 0.55 | 30min | Mittel (Inkonsistenz) |
| 4 | MITTELFRISTIG | Accel als zweiter Kanal | 8h | Mittel (95.6% vs 90%) |
| 5 | MITTELFRISTIG | Multi-Template / adaptives Template | 12h | Mittel (Formdrift) |
| 6 | MITTELFRISTIG | Adaptive Refraktärzeit | 4h | Mittel (explosive Reps) |
| 7 | LANGFRISTIG | Gate 11b durchlaufen | 20h | Hoch (Freigabe) |
| 8 | LANGFRISTIG | Orientierungsschätzung (Madgwick) | 20h | Mittel (Sensor-Drift) |
| 9 | LANGFRISTIG | RecoFit/MM-Fit als Validierung | 8h | Mittel (Baseline) |

---

## Punkt 1 (SOFORT): Shadow-Engine mit Profil-Achse/Bias

### Problem
`TrainViewModel.resetShadowEngine()` (Z. 565–593) erzeugt die Shadow-Pipeline
mit `NEUTRAL_AXIS = listOf(0.0, 0.0, 1.0)` und `NEUTRAL_BIAS = listOf(0.0, 0.0, 0.0)`.
Das Template wird zwar aus dem Profil gesetzt, aber die Projektion des Gyro-Signals
auf die falsche Achse erzeugt ein verzerrtes `rawGp` → der Shadow-Rep-Count
weicht systematisch vom Live-Rep-Count ab, noch bevor eine Pipeline-Divergenz
vorliegt. Der Shadow-Diff ist damit nicht aussagekräftig.

### Lösung
Die Shadow-Engine muss mit denselben `rotationAxis`/`gyroBias` initialisiert
werden wie die Live-Engine — sobald das Profil geladen ist.

### Änderung in `TrainViewModel.resetShadowEngine()`

**Aktuell (Z. 565–593):**
```kotlin
private fun resetShadowEngine() {
    shadowRepCount = 0
    liveRepCount = 0
    val exercise = _selectedExercise.value ?: run {
        shadowEngine = null
        return
    }
    val deviceId = connectedDeviceId.value ?: run {
        shadowEngine = null
        return
    }
    shadowEngine =
        ExerciseEnginePipeline(
            ExerciseEngineConfig(
                rotationAxis = NEUTRAL_AXIS,
                gyroBias = NEUTRAL_BIAS,
            ),
        )
    viewModelScope.launch {
        val result = calibrationProfileRepository.load(exercise.id, deviceId)
        val profile = (result as? AppResult.Success)?.value ?: return@launch
        shadowEngine?.let { engine ->
            engine.setTemplate(profile.repTemplate)
            Log.d(SHADOW_TAG, "shadow template set (${profile.repTemplate.size} samples)")
        }
    }
}
```

**Ziel (geändert):**
```kotlin
private fun resetShadowEngine() {
    shadowRepCount = 0
    liveRepCount = 0
    val exercise = _selectedExercise.value ?: run {
        shadowEngine = null
        return
    }
    val deviceId = connectedDeviceId.value ?: run {
        shadowEngine = null
        return
    }
    viewModelScope.launch {
        val result = calibrationProfileRepository.load(exercise.id, deviceId)
        val profile = (result as? AppResult.Success)?.value
        shadowEngine =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = profile?.rotationAxis ?: NEUTRAL_AXIS,
                    gyroBias = profile?.gyroBias ?: NEUTRAL_BIAS,
                    expectedProminence = profile?.expectedProminence ?: 50.0,
                    expectedDurationSamples = profile?.expectedDurationSamples ?: 50.0,
                    hasValidCalibration = profile != null,
                ),
            )
        if (profile != null) {
            shadowEngine?.setTemplate(profile.repTemplate)
            // Punkt 2: auch SPK/NPK setzen
            shadowEngine?.let { engine ->
                engine.updateLevels(profile.signalPeakLevel, profile.noisePeakLevel)
            }
            Log.d(SHADOW_TAG, "shadow engine configured: axis=${profile.rotationAxis}, " +
                "template=${profile.repTemplate.size} samples, " +
                "spk=${profile.signalPeakLevel}, npk=${profile.noisePeakLevel}")
        }
    }
}
```

**Wichtig:** Der `viewModelScope.launch`-Block muss jetzt die Engine-Erstellung
*enthalten*, nicht erst das Template nachträglich setzen. Die Engine darf nicht
mehr ausserhalb des Launch-Blocks erstellt werden, weil die Achse erst nach dem
asynchronen Laden bekannt ist.

### Tests
- `TrainViewModelTest` braucht einen neuen Test: `shadow engine uses profile axis and bias when profile exists`
  - `FakeCalibrationProfileRepository` liefert ein Profil mit `rotationAxis = listOf(0.0, 1.0, 0.0)`
  - `selectExercise` aufrufen
  - `shadowEngine` muss `rotationAxis = listOf(0.0, 1.0, 0.0)` haben
  - Vor dem Fix: Engine hat `NEUTRAL_AXIS` trotz Profil
- `FakeCalibrationProfileRepository` muss aktuell immer `null` liefern
  → muss erweiterbar sein: entweder Konstruktor-Parameter `defaultProfile: CalibrationProfile?` oder
    `setProfile(exerciseId, deviceId, profile)`-Methode.

### Änderung in `FakeCalibrationProfileRepository` (core:testing)

```kotlin
// Aktuell (immer null):
class FakeCalibrationProfileRepository : CalibrationProfileRepository {
    override suspend fun load(exerciseId: Long, deviceId: String): AppResult<CalibrationProfile?> =
        AppResult.success(null)
    override suspend fun save(profile: CalibrationProfile): AppResult<Unit> = AppResult.success(Unit)
}

// Ziel (konfigurierbar):
class FakeCalibrationProfileRepository(
    private val defaultProfile: CalibrationProfile? = null,
) : CalibrationProfileRepository {
    private val profiles = mutableMapOf<Pair<Long, String>, CalibrationProfile>()

    override suspend fun load(exerciseId: Long, deviceId: String): AppResult<CalibrationProfile?> =
        AppResult.success(profiles[exerciseId to deviceId] ?: defaultProfile)

    override suspend fun save(profile: CalibrationProfile): AppResult<Unit> {
        profiles[profile.exerciseId to profile.deviceId] = profile
        return AppResult.success(Unit)
    }
}
```

---

## Punkt 2 (SOFORT): SPK/NPK aus Profil laden

### Problem
`PeakDetector` wird mit `initialSpk = 100.0` und `initialNpk = 10.0` erstellt
(Z. 24–25 in `PeakDetector.kt`). Die Methode `updateLevels(spk, npk)` existiert
(Z. 143–149) wird aber nirgends aufgerufen. `CalibrationProfile` hat die Felder
`signalPeakLevel` und `noisePeakLevel` (SensorModels.kt Z. 47–48), die genau
dafür vorgesehen sind — sie werden aber nie gesetzt.

Die Kalibrierung (`CalibrationController.finalize()`, Z. 201–245 in
CalibrationController.kt) berechnet `theta`, `baseline` und
`expectedProminence` im `GuidedCalibrationResult`.

**Korrektur (Review 2026-08-12):** Ein Setter-Pfad für `signalPeakLevel`/
`noisePeakLevel` existiert bereits — anders als hier ursprünglich
angenommen. `CalibrationViewModel.confirmAndSave()` (Z. 121–122) setzt
beide Felder schon beim Speichern:
```kotlin
signalPeakLevel = result.theta,
noisePeakLevel = result.baseline,
```
Das eigentliche Problem: hier werden die falschen Größen zugewiesen
(`theta` direkt statt `theta + expectedProminence`, `baseline` statt
`theta * 0.5`). `CalibrationController.finalize()`/`GuidedCalibrationResult`
müssen dafür **nicht** geändert werden — `theta`, `baseline` und
`expectedProminence` liegen dort bereits vor, `CalibrationViewModel` liest
sie nur mit den falschen Zuordnungen aus.

### Lösung — zwei Teiländerungen

#### 2a: `CalibrationProfile`-Felder beim Speichern korrekt setzen

Einzige Änderung: `CalibrationViewModel.confirmAndSave()` (Z. 121–122).
`GuidedCalibrationResult` liefert `theta`, `baseline` und
`expectedProminence` bereits — **keine** neuen Felder auf
`GuidedCalibrationResult`, **keine** Änderung an
`CalibrationController.finalize()` nötig. `signalPeakLevel` =
`theta + expectedProminence` (SPK ≈ Schwelle + Prominenz). `noisePeakLevel`
= `theta * 0.5` (NPK ≈ halbe Schwelle, konservativ).

**Vorher (Z. 121–122):**
```kotlin
signalPeakLevel = result.theta,
noisePeakLevel = result.baseline,
```

**Nachher:**
```kotlin
signalPeakLevel = result.theta + result.expectedProminence,  // SPK
noisePeakLevel = result.theta * 0.5,                          // NPK
```

#### 2b: `updateLevels()` nach Pipeline-Start aufrufen

In `TrainViewModel.startCountedSet()` (Z. 465–495) wird die Live-Engine erstellt:
```kotlin
liveEngine =
    ExerciseEnginePipeline(
        ExerciseEngineConfig(
            rotationAxis = profile.rotationAxis,
            gyroBias = profile.gyroBias,
            expectedProminence = profile.expectedProminence,
            expectedDurationSamples = profile.expectedDurationSamples,
            hasValidCalibration = true,
        ),
    ).also { it.setTemplate(profile.repTemplate) }
```

**Ergänzung:** nach `.also { it.setTemplate(profile.repTemplate) }`:
```kotlin
.also { engine ->
    engine.setTemplate(profile.repTemplate)
    // Punkt 2: SPK/NPK aus Profil laden
    engine.updateLevels(profile.signalPeakLevel, profile.noisePeakLevel)
}
```

**Hinweis:** `ExerciseEnginePipeline` hat keine eigene `updateLevels()`-Methode.
Die `updateLevels()` liegt auf `PeakDetector` (PeakDetector.kt Z. 143–149).
`ExerciseEnginePipeline` muss die Methode durchreichen:

```kotlin
// In ExerciseEnginePipeline.kt (nach Z. 166):
fun updateLevels(
    spk: Double? = null,
    npk: Double? = null,
) = repCounter.updateLevels(spk, npk)
```

Und `RepCounter` bekommt eine Durchreich-Methode:
```kotlin
// In RepCounter.kt:
fun updateLevels(
    spk: Double? = null,
    npk: Double? = null,
) = peakDetector.updateLevels(spk, npk)
```

### Tests für Punkt 2
- `CalibrationViewModelTest`: Neuen Test `confirmAndSave leitet signalPeakLevel/noisePeakLevel korrekt aus theta+expectedProminence ab`
  (bisher kein Test für diese Zuordnung — die 2a-Korrektur selbst war
  ungetestet, deshalb ist der falsche Wert nie aufgefallen)
- `PeakDetectorTest`: Neuen Test `updateLevels changes threshold`
  - Default: `spk=100, npk=10` → `theta = 10 + 0.25 * (100-10) = 32.5`
  - Nach `updateLevels(200.0, 20.0)`: `theta = 20 + 0.25 * (200-20) = 65.0`
- `ExerciseEnginePipelineIsolationTest`: Neuen Test `updateLevels propagates to peak detector`
  - Pipeline erstellen, `updateLevels(200.0, 50.0)` aufrufen
  - `signalChain.process()` mit einem Sample, das bei 32.5 einen Peak triggert
  - Vor `updateLevels`: Peak wird erkannt. Nach `updateLevels` mit höheren Werten:
    derselbe Sample triggert keinen Peak mehr.
- `TrainViewModelTest`: Neuen Test `startCountedSet calls updateLevels from profile`
  - `FakeCalibrationProfileRepository` liefert Profil mit `signalPeakLevel = 200.0`
  - `startCountedSet()` aufrufen
  - `liveEngine` muss `updateLevels(200.0, ...)` aufgerufen haben
  - Indirekter Test: `liveEngine.repCount` muss nach N Samples anders sein als ohne `updateLevels`
    (schwer direkt zu testen — alternativ: `PeakDetector`-Instanz über `ExerciseEngineConfig`
    injizierbar machen, siehe Refactoring-Hinweis unten)

---

## Punkt 3 (SOFORT): QualityScorer-Schwelle vereinheitlichen

### Problem
`ExerciseEngineConfig` hat `minQualityScore = 0.4` (Z. 20 in
ExerciseEnginePipeline.kt). `QualityScorer` hat Default `minScore = 0.55`
(Z. 25 in QualityScorer.kt). Der `ExerciseEngineConfig`-Wert wird an den
`QualityScorer` im Konstruktor übergeben (Z. 69–74 in ExerciseEnginePipeline.kt):
```kotlin
private val qualityScorer =
    QualityScorer(
        expectedProminence = config.expectedProminence,
        expectedDurationSamples = config.expectedDurationSamples,
        minScore = config.minQualityScore,
    )
```

Der Config-Wert `0.4` gewinnt also — aber das ist inkonsistent zum Scorer-Default.
Entweder der Config-Default muss `0.55` sein, oder der Scorer-Default muss `0.4`.
Die Entscheidung: **Config-Default auf `0.55` setzen** (den Scorer-Default beibehalten),
weil der Scorer-Default der ursprüngliche, validierte Wert aus der Dart-Vorlage ist.

### Änderung
In `ExerciseEngineConfig` (ExerciseEnginePipeline.kt Z. 20):
```kotlin
val minQualityScore: Double = 0.55,
```

### Test
- `ExerciseEnginePipelineIsolationTest`: Config-default `minQualityScore = 0.55`
  → `QualityScorer.minScore` muss `0.55` sein.
- `QualityScorerTest`: Bereits vorhanden, Default-Wert prüfen.

---

## Punkt 4 (MITTELFRISTIG): Accel als zweiter Kanal

### Problem
Aktuell verwendet die Pipeline **nur Gyro** (`gx, gy, gz`) für die
Rep-Zählung. Die Beschleunigungssensordaten (`ax, ay, az`) werden nur für
die Ruhe-Gate-Erkennung und die Waveform-UI verwendet. Forschung (Balestra
et al. 2021) zeigt: Accel+Gyro-Kombination erreicht 95.6% Zählgenauigkeit,
Gyro allein ist schlechter.

### Ziel
Accel als zweiten, unabhängigen Kanal in die Pipeline integrieren:
- **Option A (einfach):** Accel-Magnitude als zusätzlichen Signal-Kanal
  parallel zu `rawGp`. Ein zweiter `SignalChain`-Zweig verarbeitet `ax, ay, az`
  → `rawAccelMag` → OneEuroFilter → Envelope → `smoothedAccel`. Der
  `RepCounter` verwendet beide Kanäle: Ein Peak muss in *beiden* Kanälen
  innerhalb eines Toleranzfensters auftreten (Voting), um die Zählung zu
  bestätigen. Das reduziert False-Positives.
- **Option B (fortgeschrittener):** `combinedRaw = accelMagnitude + gyroWeight * gyroMag`
  wie in `CalibrationController.candidateSignals()` (Z. 432–456 in
  CalibrationController.kt). Ein gewichtetes Signal aus Accel und Gyro.
  Der Gewichtungsfaktor `gyroWeight = 0.05` ist aus der Kalibrierung bekannt.

### Implementierung (Option A — empfohlen für erste Iteration)

#### 4a: `SignalChain` um Accel-Zweig erweitern

```kotlin
// SignalChain.kt: neue Felder
private val oneEuroAccel = OneEuroFilter(oneEuroMinCutoff, oneEuroBeta, sampleRateHz)
private val envelopeAccel = EnvelopeDetector(envelopeCutoffHz, sampleRateHz)

// Neues Feld in ProcessedFrame (oder neues Data Class):
// smoothedAccel: Double

// In process() nach Z. 45:
val accelMag = sqrt(ax * ax + ay * ay + az * az) // oder ax/ay/az einzeln
val filteredAccel = oneEuroAccel.process(accelMag)
val envAccel = envelopeAccel.process(abs(filteredAccel))

// ProcessedFrame erhält:
// val smoothedAccel: Double,
// val accelEnvelope: Double,
```

`ProcessedFrame` in SensorModels.kt um zwei Felder erweitern:
```kotlin
data class ProcessedFrame(
    // ... bestehende Felder ...
    val smoothedAccel: Double = 0.0,
    val accelEnvelope: Double = 0.0,
)
```

#### 4b: `PeakDetector`-Kopie für Accel oder zweiten PeakDetector

Entweder:
- `RepCounter` bekommt einen zweiten `PeakDetector` für Accel
- Oder `PeakDetector` bekommt eine zweite `process()`-Methode für Accel

**Empfohlen:** Zweiten `PeakDetector` in `RepCounter`:
```kotlin
class RepCounter(
    private val peakDetector: PeakDetector,
    private val accelPeakDetector: PeakDetector,  // NEU
    private val templateMatcher: TemplateMatcher,
    private val phaseValidator: PhaseValidator,
    private val qualityScorer: QualityScorer,
    private val accelVoteWindowSamples: Int = 5,  // NEU: Toleranzfenster
) {
    // In process(): nach peakDetector.process(frame) auch accelPeakDetector.process(frame)
    // Wenn beide innerhalb von accelVoteWindowSamples einen Peak melden → zählen
}
```

#### 4c: `ExerciseEngineConfig` um Accel-Parameter erweitern

```kotlin
data class ExerciseEngineConfig(
    // ... bestehende Felder ...
    val accelEnabled: Boolean = false,  // Feature-Flag für Rollout
    val accelWeight: Double = 0.05,     // Gyro-Gewicht für combined (Option B)
)
```

### Tests
- `SignalChainTest`: Accel-Verarbeitung erzeugt `smoothedAccel != 0.0`
- `RepCounterTest`: Accel-Voting verhindert False-Positive bei reinem Gyro-Peak
- `ExerciseEnginePipelineIsolationTest`: Mit `accelEnabled=true` und einem
  Sample, das nur im Gyro einen Peak hat → kein RepCount. Mit Sample, das
  in beiden Kanälen einen Peak hat → RepCount.

### Risiken
- Accel ist anfälliger für Erschütterungen (Aufsetzen der Hantel, Bodenkontakt)
  → das Voting reduziert genau diese False-Positives
- Accel-Rauschen ist höher als Gyro-Rauschen → OneEuro-Cutoff ggf. anpassen
  (höheres `minCutoff` für Accel, z. B. 2.0 Hz statt 1.0 Hz)

---

## Punkt 5 (MITTELFRISTIG): Multi-Template / adaptives Template

### Problem
Aktuell wird ein **einziges** 64-Sample-Median-Template aus der Kalibrierung
verwendet (TemplateMatcher.kt Z. 57–59: `TEMPLATE_LENGTH = 64`). Der NCC
(Normalized Cross-Correlation) gegen dieses Template muss ≥ 0.7 sein,
damit ein Peak als Rep gezählt wird (Z. 47: `ncc >= threshold`).

Mit fortschreitender Ermüdung ändert sich die Rep-Form (längere exzentrische
Phase, kürzere konzentrische, veränderter Range of Motion). Der NCC gegen
das starre Template fällt dann unter 0.7, und die Rep wird nicht gezählt
(False Negative). Forschung: Yurtman & Barshan zeigen Multi-Template-DTW
mit 93.5% Klassifikation; Filippou et al. 2023 zeigen DTW-Barycenter-Averaging.

### Lösung — Drei Varianten

#### Variante 5a (Einfach, empfohlen): Template-Pool mit Best-Match

Ein Pool der letzten N bestätigten Rep-Windows (N = 5–10). NCC wird gegen
**alle** Templates im Pool gemessen; der beste Score gewinnt. Neue bestätigte
Reps werden in den Pool aufgenommen; das älteste Template wird verdrängt.

```kotlin
// TemplateMatcher.kt:
class TemplateMatcher(
    private val threshold: Double = 0.7,
    private val poolSize: Int = 5,  // NEU
) {
    private val templates: MutableList<List<Double>> = mutableListOf()  // NEU: statt einzelner template

    fun setTemplate(rawTemplate: List<Double>) {
        if (rawTemplate.size < 4) return
        templates.clear()
        templates.add(normalize(resample(rawTemplate, TEMPLATE_LENGTH)))
    }

    fun addToPool(rawWindow: List<Double>) {  // NEU
        if (rawWindow.size < 4) return
        val normalized = normalize(resample(rawWindow, TEMPLATE_LENGTH)) ?: return
        templates.add(normalized)
        if (templates.size > poolSize) {
            templates.removeAt(0)  // FIFO
        }
    }

    fun match(window: List<Double>): MatchResult {
        if (templates.isEmpty()) return MatchResult(0.0, accepted = true, noTemplate = true)
        if (window.size < 4) return MatchResult(0.0, accepted = false)
        val normalized = normalize(resample(window, TEMPLATE_LENGTH)) ?: return MatchResult(0.0, accepted = false)
        var bestNcc = 0.0
        for (tpl in templates) {
            val ncc = crossCorrelate(tpl, normalized)
            if (ncc > bestNcc) bestNcc = ncc
        }
        return MatchResult(bestNcc, accepted = bestNcc >= threshold)
    }
}
```

**In `RepCounter.decide()` (Z. 102–151):** Nach erfolgreicher Zählung
den bestätigten Peak-Window zum Pool hinzufügen:
```kotlin
// In decide(), nach repCount++ (Z. 141):
if (matchResult.accepted) {
    templateMatcher.addToPool(peak.window)  // NEU
}
```

#### Variante 5b (Fortgeschritten): EMA-Template-Update

Wie `CalibrationRefiner` (Z. 139–142): Neues Template = `old * 0.7 + new * 0.3`.
Das Template driftet langsam mit der aktuellen Rep-Form, ohne dass ein Pool
verwaltet werden muss.

```kotlin
// In TemplateMatcher:
fun updateTemplate(rawWindow: List<Double>, adaptRate: Double = 0.3) {
    val tpl = template ?: return
    if (rawWindow.size < 4) return
    val normalized = normalize(resample(rawWindow, TEMPLATE_LENGTH)) ?: return
    val adapted = tpl.zip(normalized) { a, b -> a * (1 - adaptRate) + b * adaptRate }
    template = adapted
}
```

#### Variante 5c (Kombiniert): Pool + EMA

Pool mit 5 Templates, jedes wird bei Übereinstimmung per EMA aktualisiert.
So hat man sowohl mehrere Basisformen als auch Anpassung innerhalb jeder Form.

### Test
- `TemplateMatcherTest`: Neuen Test `multiTemplate: best match wins`
  - Template A = Sinus, Template B = Rechteck
  - Window = Rechteck → NCC gegen A < 0.7, gegen B ≥ 0.7 → `accepted = true`
- `TemplateMatcherTest`: `addToPool adds and evicts`
  - 8 Windows hinzufügen bei PoolSize=5 → Pool hat nur 5
- `RepCounterTest`: `confirmed rep updates template pool`
  - Nach 5 bestätigten Reps ist der Pool gefüllt

---

## Punkt 6 (MITTELFRISTIG): Adaptive Refraktärzeit

### Problem
`PeakDetector` hat eine feste `refractorySeconds = 0.5` (Z. 29 in PeakDetector.kt).
Das entspricht `refractorySamples = (0.5 * 50) = 25` Samples. Bei schnellen
Reps (< 0.5 s Zykluszeit) werden legitime Peaks unterdrückt (False Negative).
Bei sehr langsamen Reps (> 3 s) ist die Refraktärzeit irrelevant, aber das
`MAX_EXTRA_PHASE_SAMPLES = 120` (RepCounter.kt Z. 190) begrenzt das
Pending-Fenster auf 2.4 s → auch False Negative.

### Lösung
Die Refraktärzeit dynamisch aus der erwarteten Rep-Dauer berechnen:
`refractorySeconds = expectedDurationSamples / sampleRateHz * 0.3`
(30% der erwarteten Rep-Dauer, wie bereits in
`CalibrationRefiner.detectPeaks()` Z. 86: `refractory = SAMPLE_RATE_HZ * 0.3`).

### Änderung in `PeakDetector.kt`

```kotlin
class PeakDetector(
    val sampleRateHz: Double = 50.0,
    initialSpk: Double = 100.0,
    initialNpk: Double = 10.0,
    private val thresholdFactor: Double = 0.25,
    private val fallingRatio: Double = 0.5,
    private val fallingDebounce: Int = 4,
    refractorySeconds: Double = 0.5,  // Fallback, wenn keine updateLevels aufgerufen wurde
    private val prominenceRatio: Double = 0.2,
    // NEU: adaptive Refraktär
    private val refractoryDurationRatio: Double = 0.3,  // 30% der erwarteten Dauer
) {
    // ...
    private var expectedDurationSamples: Double = refractorySeconds * sampleRateHz  // NEU: Fallback
    // refractorySamples bleibt, wird aber dynamisch gesetzt:
    private var refractorySamples = (refractorySeconds * sampleRateHz).toInt()

    // NEU: Update-Methode für die Dauer
    fun updateExpectedDuration(durationSamples: Double) {
        expectedDurationSamples = durationSamples
        refractorySamples = (durationSamples * refractoryDurationRatio).toInt()
            .coerceAtLeast(5)  // mindestens 5 Samples = 100 ms
            .coerceAtMost(100)  // maximal 100 Samples = 2 s
    }

    // updateLevels() erweitern:
    fun updateLevels(
        spk: Double? = null,
        npk: Double? = null,
        expectedDurationSamples: Double? = null,  // NEU
    ) {
        spk?.let { this.spk = it }
        npk?.let { this.npk = it }
        expectedDurationSamples?.let { updateExpectedDuration(it) }  // NEU
    }
}
```

**In `RepCounter.trackForAdaptation()` (Z. 153–169):** Nachdem die
`recentDurations` aktualisiert wurden, auch die Refraktärzeit anpassen:
```kotlin
private fun trackForAdaptation(prominence: Double, durationSamples: Int) {
    recentDurations.add(durationSamples.toDouble())
    recentProminences.add(prominence)
    if (recentDurations.size > 10) {
        recentDurations.removeAt(0)
        recentProminences.removeAt(0)
    }
    if (recentDurations.size >= 3) {
        val avgDuration = recentDurations.average()
        qualityScorer.updateExpectations(
            expectedDurationSamples = avgDuration,
            expectedProminence = recentProminences.average(),
        )
        // NEU: adaptive Refraktär
        peakDetector.updateExpectedDuration(avgDuration)
    }
}
```

### Auch `MAX_EXTRA_PHASE_SAMPLES` anpassen (RepCounter.kt Z. 190)
Bisher hartkodiert `120` (= 2.4 s). Dynamisch aus `expectedDurationSamples`:
```kotlin
// In RepCounter:
private fun maxExtraPhaseSamples(): Int =
    (peakDetector.expectedDurationSamples * 2.0).toInt().coerceIn(60, 300)
    // 2x erwartete Dauer, mindestens 60 Samples (= 1.2 s), max 300 (= 6 s)
```

### Tests
- `PeakDetectorTest`: Neuen Test `adaptive refractory: fast reps not suppressed`
  - `expectedDurationSamples = 25` (0.5 s bei 50 Hz) → `refractorySamples = 7`
  - Zwei Peaks im Abstand von 10 Samples → beide werden erkannt (vorher: 0.5 s = 25 Samples, zweiter unterdrückt)
- `RepCounterTest`: `trackForAdaptation updates refractory`
  - Nach 3 bestätigten Reps mit `durationSamples = 20` → `refractorySamples = 6`

---

## Punkt 7 (LANGFRISTIG): Gate 11b durchlaufen

### Problem
Die 5 Freigabe-Szenarien aus dem [Shadow-DoD](FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md#11b-shadow-dod) des Design-Dokuments sind nie
vollständig durchlaufen worden. ADR-0014 dokumentiert die Live-Zählung als
bewusste Abweichung, aber das Gate bleibt formal offen.

### Voraussetzungen
- Punkte 1–6 sind implementiert und getestet
- `ShadowSessionRecorder` schreibt JSONL (bisher `NoOpShadowSessionRecorder`)
- `tools/shadow_harness.py` existiert und kann Reports generieren
- Ein initialer Corpus existiert (mindestens Szenario 1, eine Übung)

### Implementierung

#### 7a: JSONL-Recorder (Shadow-Diff-Harness Schritt 2/3)

**Korrektur (Review 2026-08-12):** `ShadowSessionRecorder` deklariert bisher
nur `recordSet()`. `TrainViewModel` hält den Recorder als Interface-Typ
(`shadowSessionRecorder: ShadowSessionRecorder`, Z. 63 — korrekt so, wegen
Testbarkeit über `NoOpShadowSessionRecorder`). Ruft man `startSession()`/
`endSession()` unten wie ursprünglich geplant nur auf der *konkreten*
`JsonlShadowSessionRecorder`-Klasse auf, kompiliert der Aufruf aus
`TrainViewModel` heraus nicht. Erst das Interface erweitern:

**Änderung in `ShadowSessionRecorder.kt`:**
```kotlin
interface ShadowSessionRecorder {
    fun recordSet(event: ShadowDiffEvent)
    fun startSession(sessionId: String)
    fun endSession()
}

class NoOpShadowSessionRecorder : ShadowSessionRecorder {
    override fun recordSet(event: ShadowDiffEvent) = Unit
    override fun startSession(sessionId: String) = Unit
    override fun endSession() = Unit
}
```

**Neue Datei:** `feature/workout/.../shadow/JsonlShadowSessionRecorder.kt`

```kotlin
package com.dropsync.feature.workout.shadow

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schreibt ShadowDiffEvents als JSONL unter
 * /Android/data/<pkg>/files/recordings/<session>.jsonl
 * (SHADOW_DIFF_HARNESS_PLAN.md Abschnitt 6). */
@Singleton
class JsonlShadowSessionRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : ShadowSessionRecorder {
    private var writer: FileWriter? = null
    private var sessionId: String? = null

    override fun startSession(sessionId: String) {
        this.sessionId = sessionId
        val dir = File(context.getExternalFilesDir(null), "recordings")
        dir.mkdirs()
        val file = File(dir, "${sessionId}.jsonl")
        writer = FileWriter(file, true)  // append
        writer?.write("{\"t\":\"session_start\",\"sessionId\":\"$sessionId\"}\n")
        writer?.flush()
    }

    override fun recordSet(event: ShadowDiffEvent) {
        writer?.write(event.toJsonLine() + "\n")
        writer?.flush()
    }

    override fun endSession() {
        val sid = sessionId ?: return
        writer?.write("{\"t\":\"session_end\",\"sessionId\":\"$sid\"}\n")
        writer?.flush()
        writer?.close()
        writer = null
        sessionId = null
    }
}
```

**Binding in `ShadowRecorderModule.kt` ersetzen** (`@Binds` statt `@Provides`
— `JsonlShadowSessionRecorder` hat bereits einen `@Inject constructor`, der
den `Context` selbst anfordert; eine manuelle `@Provides`-Funktion müsste
den Context von Hand durchreichen, siehe der ursprüngliche, nicht
kompilierende `/* context */`-Platzhalter unten):
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class ShadowRecorderModule {
    @Binds
    @Singleton
    abstract fun bindShadowSessionRecorder(impl: JsonlShadowSessionRecorder): ShadowSessionRecorder
    // Für Tests: NoOpShadowSessionRecorder via Hilt-Test-Modul
}
```
(`object` → `abstract class`, da `@Binds`-Funktionen abstrakt sein müssen.)

**Anbindung in `TrainViewModel`** (Feldname `shadowSessionRecorder`, nicht
`recorder` — siehe Z. 63):
- `init`-Block: `shadowSessionRecorder.startSession(UUID.randomUUID().toString().take(8))`
- `finishExercise()`: `shadowSessionRecorder.endSession()`
- `disconnect()`: `shadowSessionRecorder.endSession()`

#### 7b: `tools/shadow_harness.py` (Shadow-Diff-Harness Schritt 4)

Python-Skript nach dem Vorbild von `flowrep-clone/tools/golden_csv_harness.py`.

```python
#!/usr/bin/env python3
"""
Shadow-Diff-Harness: Liest JSONL + Manifest-Paare aus tools/golden_shadow_corpus/
und produziert PASS/FAIL-Report pro Szenario (Abschnitt 11b).

Usage:
    python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus
    python3 tools/shadow_harness.py --smoke-test  # synthetische Fixtures
"""

import json
import os
import sys
from collections import defaultdict
from pathlib import Path

# Abnahmekriterien (zentral, SHADOW_DIFF_HARNESS_PLAN.md §10)
DELTA_TOLERANCE_REPS = 0          # exakte Übereinstimmung
MIN_SESSIONS_PER_SCENARIO = 3     # mehrere unabhängige Sessions
EXACT_MATCH_RATE_MIN = 1.0        # bei Toleranz 0
MAE_MAX = 0.0                     # bei Toleranz 0

def parse_jsonl(path: Path) -> list[dict]:
    lines = []
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            lines.append(json.loads(line))
    return lines

def parse_manifest(path: Path) -> dict:
    with open(path, "r") as f:
        return json.load(f)

def evaluate_session(jsonl_path: Path, manifest: dict) -> dict:
    events = parse_jsonl(jsonl_path)
    scenario = manifest.get("scenario", "unknown")
    known_active_reps = manifest.get("known_active_reps", [])
    
    set_events = [e for e in events if e.get("t") == "set"]
    
    results = []
    for i, set_event in enumerate(set_events):
        confirmed = set_event.get("confirmedReps", 0)
        shadow = set_event.get("shadowReps", 0)
        delta = set_event.get("delta", shadow - confirmed)
        edited = set_event.get("confirmedRepsEdited", False)
        
        # Wahrheits-Priorität (§7)
        if i < len(known_active_reps):
            truth = known_active_reps[i]
            truth_source = "known_active_reps"
        elif edited:
            truth = confirmed
            truth_source = "confirmedReps (edited)"
        else:
            results.append({
                "set_index": i,
                "delta": delta,
                "truth": None,
                "truth_source": "none",
                "pass": None,
            })
            continue
        
        results.append({
            "set_index": i,
            "delta": shadow - truth,
            "truth": truth,
            "truth_source": truth_source,
            "pass": abs(shadow - truth) <= DELTA_TOLERANCE_REPS,
        })
    
    return {
        "scenario": scenario,
        "session": jsonl_path.stem,
        "exercise_id": manifest.get("exercise_id", "unknown"),
        "results": results,
        "n_sets": len(set_events),
        "n_passed": sum(1 for r in results if r["pass"] is True),
        "n_failed": sum(1 for r in results if r["pass"] is False),
        "n_no_truth": sum(1 for r in results if r["pass"] is None),
    }

def main():
    corpus_dir = None
    smoke_test = False
    
    for arg in sys.argv[1:]:
        if arg == "--smoke-test":
            smoke_test = True
        elif arg.startswith("--corpus-dir="):
            corpus_dir = arg.split("=", 1)[1]
        elif arg.startswith("--corpus-dir"):
            idx = sys.argv.index(arg)
            if idx + 1 < len(sys.argv):
                corpus_dir = sys.argv[idx + 1]
    
    if smoke_test:
        # TODO: synthetische Fixtures generieren
        print("SMOKE TEST: nicht implementiert")
        sys.exit(0)
    
    if not corpus_dir:
        print("Usage: shadow_harness.py --corpus-dir <path> [--smoke-test]", file=sys.stderr)
        sys.exit(1)
    
    corpus_path = Path(corpus_dir)
    if not corpus_path.exists():
        print(f"Corpus nicht gefunden: {corpus_path}", file=sys.stderr)
        sys.exit(1)
    
    sessions = defaultdict(list)
    
    for meta_path in corpus_path.glob("*.jsonl.meta.json"):
        jsonl_path = meta_path.with_name(meta_path.stem.replace(".meta", ""))
        if not jsonl_path.exists():
            jsonl_path = corpus_path / (meta_path.stem.replace(".meta.json", "") + ".jsonl")
        if not jsonl_path.exists():
            print(f"WARN: keine JSONL zu {meta_path.name}", file=sys.stderr)
            continue
        
        manifest = parse_manifest(meta_path)
        result = evaluate_session(jsonl_path, manifest)
        sessions[result["scenario"]].append(result)
    
    # Report
    all_pass = True
    print("=" * 60)
    print("SHADOW-DIFF-HARNESS REPORT")
    print("=" * 60)
    
    for scenario, scenario_sessions in sorted(sessions.items()):
        print(f"\n--- Szenario: {scenario} ---")
        print(f"  Sessions: {len(scenario_sessions)}")
        
        all_deltas = []
        total_sets = 0
        total_passed = 0
        total_failed = 0
        
        for sess in scenario_sessions:
            print(f"  {sess['session']} (exercise={sess['exercise_id']}): "
                  f"{sess['n_passed']}/{sess['n_sets']} passed, "
                  f"{sess['n_failed']} failed, {sess['n_no_truth']} no truth")
            for r in sess["results"]:
                if r["pass"] is not None:
                    all_deltas.append(r["delta"])
            total_sets += sess["n_sets"]
            total_passed += sess["n_passed"]
            total_failed += sess["n_failed"]
        
        if total_sets > 0:
            mae = sum(abs(d) for d in all_deltas) / len(all_deltas) if all_deltas else 0
            exact_match = total_passed / total_sets if total_sets > 0 else 0
            bias = sum(all_deltas) / len(all_deltas) if all_deltas else 0
            
            print(f"  Exact-Match: {exact_match:.1%} (benötigt: {EXACT_MATCH_RATE_MIN:.0%})")
            print(f"  MAE: {mae:.2f} (max: {MAE_MAX})")
            print(f"  Bias: {bias:+.2f}")
            print(f"  Sessions: {len(scenario_sessions)} (min: {MIN_SESSIONS_PER_SCENARIO})")
            
            scenario_pass = (
                len(scenario_sessions) >= MIN_SESSIONS_PER_SCENARIO
                and exact_match >= EXACT_MATCH_RATE_MIN
                and mae <= MAE_MAX
            )
            all_pass = all_pass and scenario_pass
            print(f"  => {'PASS' if scenario_pass else 'FAIL'}")
    
    print("\n" + "=" * 60)
    print(f"GESAMT: {'PASS' if all_pass else 'FAIL'}")
    print("=" * 60)
    sys.exit(0 if all_pass else 1)

if __name__ == "__main__":
    main()
```

#### 7c: Kurations-Workflow (Shadow-Diff-Harness Schritt 5)

1. Hardware-Lauf mit M5StickC durchführen (5 Übungen, je 3 Sätze)
2. JSONL aus `/Android/data/com.dropsync/files/recordings/` kopieren nach
   `tools/golden_shadow_corpus/`
3. Manifest `<name>.jsonl.meta.json` anlegen:
   ```json
   {
     "recording": "2026-08-12_bizeps_curl_normal.jsonl",
     "exercise_id": "bicep_curl",
     "scenario": "calibrated",
     "known_active_reps": [12, 10, 8],
     "device": "M5StickC-Plus2",
     "notes": "Template aktiv, normales Tempo",
     "samples_recorded": false
   }
   ```
4. `python3 tools/shadow_harness.py --corpus-dir tools/golden_shadow_corpus`
5. Report in `STATUS_FORTSCHRITT.md` eintragen

---

## Punkt 8 (LANGFRISTIG): Orientierungsschätzung (Madgwick)

### Problem
Die Rotationsachse wird einmalig während der Kalibrierung (PCA, Stage A)
bestimmt und danach nie aktualisiert (`CalibrationController.axisAnalysis()`).
Wenn der Sensor während des Trainings verrutscht (Schweiß, Kleidung,
Muskelbewegung), weicht die tatsächliche Achse von der kalibrierten ab.
Das projizierte `rawGp` wird leiser, die Zählgenauigkeit sinkt.

### Lösung
Online-Achsen-Tracking mit einem Madgwick-Filter (komplementärer Filter,
gyro+accel, 6 DOF). Der Filter schätzt die Orientierung des Sensors
relativ zur Erdgravitation. Damit kann die Rotation der Achse online
korrigiert werden.

### Implementierung (Skizze)

**Neue Datei:** `domain/sensor/OrientationTracker.kt`

```kotlin
package com.dropsync.domain.sensor

/**
 * Madgwick IMU-Filter (6 DOF, gyro+accel).
 * Schätzt die Orientierung des Sensors als Quaternion.
 * Kein Magnetometer (9 DOF) — im Kraftraum zu starke magnetische
 * Störungen durch Eisenmassen.
 */
class OrientationTracker(
    private val sampleRateHz: Double = 50.0,
    private val beta: Double = 0.1,  // Madgwick gain
) {
    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    /**
     * Aktualisiert die Orientierung mit einem neuen IMU-Sample.
     * @return aktuelles Quaternion (q0, q1, q2, q3)
     */
    fun update(ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double): Quaternion {
        // Madgwick 2010, Section III: Filter Equations
        val dt = 1.0 / sampleRateHz
        val gxRad = Math.toRadians(gx)
        val gyRad = Math.toRadians(gy)
        val gzRad = Math.toRadians(gz)

        // Gyro-basierte Prädiktion
        val qDot1 = 0.5 * (-q1 * gxRad - q2 * gyRad - q3 * gzRad)
        val qDot2 = 0.5 * (q0 * gxRad + q2 * gzRad - q3 * gyRad)
        val qDot3 = 0.5 * (q0 * gyRad - q1 * gzRad + q3 * gxRad)
        val qDot4 = 0.5 * (q0 * gzRad + q1 * gyRad - q2 * gxRad)

        // Accel-basierte Korrektur (Gradient Descent)
        val norm = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (norm > 0.0) {
            val axN = ax / norm
            val ayN = ay / norm
            val azN = az / norm

            // Objective function F und Jacobian J
            val f1 = 2.0 * (q1 * q3 - q0 * q2) - axN
            val f2 = 2.0 * (q0 * q1 + q2 * q3) - ayN
            val f3 = 2.0 * (0.5 - q1 * q1 - q2 * q2) - azN

            val j11 = -2.0 * q2
            val j12 = 2.0 * q3
            val j13 = -2.0 * q0
            val j14 = 2.0 * q1
            val j21 = 2.0 * q1
            val j22 = 2.0 * q0
            val j23 = 2.0 * q3
            val j24 = 2.0 * q2
            val j31 = 0.0
            val j32 = -4.0 * q1
            val j33 = -4.0 * q2
            val j34 = 0.0

            val step1 = j11 * f1 + j21 * f2 + j31 * f3
            val step2 = j12 * f1 + j22 * f2 + j32 * f3
            val step3 = j13 * f1 + j23 * f2 + j33 * f3
            val step4 = j14 * f1 + j24 * f2 + j34 * f3

            val stepNorm = kotlin.math.sqrt(step1 * step1 + step2 * step2 + step3 * step3 + step4 * step4)
            if (stepNorm > 0.0) {
                val s1 = step1 / stepNorm
                val s2 = step2 / stepNorm
                val s3 = step3 / stepNorm
                val s4 = step4 / stepNorm

                // Fusion: gyro-Prädiktion + accel-Korrektur
                q0 -= (qDot1 - beta * s1) * dt
                q1 -= (qDot2 - beta * s2) * dt
                q2 -= (qDot3 - beta * s3) * dt
                q3 -= (qDot4 - beta * s4) * dt
            }
        } else {
            q0 -= qDot1 * dt
            q1 -= qDot2 * dt
            q2 -= qDot3 * dt
            q3 -= qDot4 * dt
        }

        // Normalize
        val qNorm = kotlin.math.sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= qNorm
        q1 /= qNorm
        q2 /= qNorm
        q3 /= qNorm

        return Quaternion(q0, q1, q2, q3)
    }

    /**
     * Rotiert einen Vektor um die geschätzte Orientierung.
     * Anwendung: kalibrierte Achse in das aktuelle Sensor-Koordinatensystem rotieren.
     */
    fun rotateVector(vx: Double, vy: Double, vz: Double): Triple<Double, Double, Double> {
        // q * v * q_conj (Quaternion-Rotation)
        val vq0 = 0.0
        val vq1 = vx
        val vq2 = vy
        val vq3 = vz

        val r0 = q0 * vq0 - q1 * vq1 - q2 * vq2 - q3 * vq3
        val r1 = q0 * vq1 + q1 * vq0 + q2 * vq3 - q3 * vq2
        val r2 = q0 * vq2 - q1 * vq3 + q2 * vq0 + q3 * vq1
        val r3 = q0 * vq3 + q1 * vq2 - q2 * vq1 + q3 * vq0

        val conj = Quaternion(q0, -q1, -q2, -q3)
        val rx = r0 * conj.q1 + r1 * conj.q0 + r2 * conj.q3 - r3 * conj.q2
        val ry = r0 * conj.q2 - r1 * conj.q3 + r2 * conj.q0 + r3 * conj.q1
        val rz = r0 * conj.q3 + r1 * conj.q2 - r2 * conj.q1 + r3 * conj.q0

        return Triple(rx, ry, rz)
    }

    fun reset() {
        q0 = 1.0; q1 = 0.0; q2 = 0.0; q3 = 0.0
    }
}

data class Quaternion(val q0: Double, val q1: Double, val q2: Double, val q3: Double)
```

**Anbindung in `SignalChain`:** Nach der Bias-Korrektur und vor der Projektion
die Achse mit `OrientationTracker` rotieren:
```kotlin
// In SignalChain.process(), nach Z. 40-42:
val rotatedAxis = orientationTracker.rotateVector(
    rotationAxis[0], rotationAxis[1], rotationAxis[2]
)
// orientationTracker.update(ax, ay, az, gx, gy, gz) wird vorher aufgerufen
val rawGp = dx * rotatedAxis.first + dy * rotatedAxis.second + dz * rotatedAxis.third
```

### Test
- `OrientationTrackerTest`: Bekannte Rotation (90° um X) → Input-Vektor (0,0,1)
  → erwartet (0, -1, 0) (oder nach Madgwick-Konvention)
- `SignalChainTest`: Mit aktivem OrientationTracker liefert `process()` bei
  Sensor-Rotation das gleiche `rawGp` wie ohne Rotation (Invarianz-Test)

---

## Punkt 9 (LANGFRISTIG): RecoFit/MM-Fit als Validierung

### Problem
Es gibt keinen externen Validierungsdatensatz. Die Pipeline wurde nur gegen
selbst aufgezeichnete Daten getestet (Overfitting-Risiko).

### Lösung
Öffentliche IMU-Datensätze als Korpus-Baseline nutzen.

#### 9a: RecoFit-Dataset konvertieren

`tools/recofit_bootstrap.py`:
```python
#!/usr/bin/env python3
"""
Lädt den Microsoft RecoFit-Datensatz
(github.com/microsoft/Exercise-Recognition-from-Wearable-Sensors)
und konvertiert die MATLAB-.mat-Dateien in unser JSONL-Schema.

RecoFit: 114 Teilnehmer (Sessions), 146 Sessions in einer Figure,
±1 Rep in 93%, Erkennung 96-99%.

Caveat: Anderes Gerät, andere Sensorposition (Handgelenk statt M5Stick),
andere Übungen. Ein grüner Lauf validiert die Harness-Mechanik, nicht
die Hardware-Freigabe.
"""

import argparse
import json
import os
from pathlib import Path

try:
    import scipy.io as sio
    import numpy as np
except ImportError:
    print("Benötigt: scipy, numpy. pip install scipy numpy")
    sys.exit(1)

def convert_mat_to_jsonl(mat_path: Path, output_dir: Path):
    """Konvertiert eine RecoFit-.mat-Datei in JSONL."""
    data = sio.loadmat(mat_path)
    
    # RecoFit-Format: accel (Nx3), gyro (Nx3), rep_labels (Nx1)
    # timestamp wird synthetisch bei 50 Hz generiert
    accel = data.get("accel")
    gyro = data.get("gyro")
    labels = data.get("rep_labels")
    
    if accel is None or gyro is None:
        print(f"WARN: {mat_path.name} hat kein accel/gyro")
        return
    
    n = len(accel)
    session_id = mat_path.stem
    
    events = []
    for i in range(n):
        ts = i * 20  # 50 Hz -> 20 ms
        ax, ay, az = accel[i] if accel.ndim > 1 else (accel[i], 0, 0)
        gx, gy, gz = gyro[i] if gyro.ndim > 1 else (gyro[i], 0, 0)
        
        events.append({
            "t": "sample",
            "ts": ts,
            "ax": float(ax), "ay": float(ay), "az": float(az),
            "gx": float(gx), "gy": float(gy), "gz": float(gz),
            "workoutState": "counting" if labels and labels[i] > 0 else "idle",
        })
    
    # Ausgabe
    output_path = output_dir / f"{session_id}.jsonl"
    with open(output_path, "w") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")
    
    # Manifest
    known_reps = int(np.sum(labels > 0)) if labels is not None else 0
    manifest = {
        "recording": f"{session_id}.jsonl",
        "exercise_id": "unknown_recofit",
        "scenario": "recofit_reference",
        "known_active_reps": [known_reps],
        "device": "RecoFit (wrist)",
        "notes": "Konvertiert aus RecoFit-Dataset, Handgelenk-Position",
        "samples_recorded": True,
    }
    with open(output_path.with_suffix(".jsonl.meta.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    
    print(f"  {session_id}: {n} Samples, {known_reps} Reps")

def main():
    parser = argparse.ArgumentParser(description="RecoFit-Dataset → JSONL")
    parser.add_argument("--input-dir", required=True, help="RecoFit .mat Verzeichnis")
    parser.add_argument("--output-dir", required=True, help="Zielverzeichnis für JSONL")
    args = parser.parse_args()
    
    input_dir = Path(args.input_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    for mat_path in sorted(input_dir.glob("*.mat")):
        print(f"Konvertiere: {mat_path.name}")
        convert_mat_to_jsonl(mat_path, output_dir)
    
    print(f"\nFertig. {len(list(output_dir.glob('*.jsonl')))} JSONL-Dateien in {output_dir}")

if __name__ == "__main__":
    main()
```

#### 9b: MM-Fit-Dataset konvertieren

Analog zu RecoFit, aber MM-Fit hat RGB-D-Referenz (Video) plus IMU.
Format: CSV/JSON, multi-device (wrist, ankle, hip, arm).

#### 9c: Validierung gegen Korpus

```bash
python3 tools/recofit_bootstrap.py \
    --input-dir data/recofit/mat/ \
    --output-dir tools/golden_shadow_corpus/recofit/

python3 tools/shadow_harness.py \
    --corpus-dir tools/golden_shadow_corpus/recofit/
```

---

## Zusammenfassung: Datei-Änderungsliste

### Sofort (Punkte 1–3)

| Datei | Änderung |
|---|---|
| `feature/workout/.../TrainViewModel.kt` | `resetShadowEngine()`: Achse/Bias aus Profil, SPK/NPK setzen |
| `feature/workout/.../TrainViewModel.kt` | `startCountedSet()`: `updateLevels()` aufrufen |
| `domain/sensor/.../ExerciseEnginePipeline.kt` | Neue `updateLevels()`-Durchreich-Methode |
| `domain/sensor/.../RepCounter.kt` | Neue `updateLevels()`-Durchreich-Methode |
| `domain/sensor/.../ExerciseEngineConfig` | `minQualityScore` = 0.55 |
| `feature/workout/.../CalibrationViewModel.kt` | `confirmAndSave()`: SPK/NPK korrekt aus `theta`/`expectedProminence` ableiten (Z. 121–122) |
| `core/testing/.../FakeCalibrationProfileRepository.kt` | Konfigurierbarer Default-Profil-Constructor |

### Mittelfristig (Punkte 4–6)

| Datei | Änderung |
|---|---|
| `domain/sensor/.../SensorModels.kt` | `ProcessedFrame` um `smoothedAccel`, `accelEnvelope` |
| `domain/sensor/.../SignalChain.kt` | Accel-Zweig (oneEuroAccel, envelopeAccel), Accel-in-process() |
| `domain/sensor/.../RepCounter.kt` | Zweiter PeakDetector für Accel, Voting-Logik |
| `domain/sensor/.../ExerciseEngineConfig` | `accelEnabled`, `accelWeight` |
| `domain/sensor/.../TemplateMatcher.kt` | Pool (addToPool, templates-Liste), Multi-Template-match() |
| `domain/sensor/.../RepCounter.kt` | `trackForAdaptation()` ruft `addToPool()` auf |
| `domain/sensor/.../PeakDetector.kt` | `updateExpectedDuration()`, adaptives `refractorySamples` |
| `domain/sensor/.../RepCounter.kt` | `MAX_EXTRA_PHASE_SAMPLES` dynamisch |

### Langfristig (Punkte 7–9)

| Datei | Änderung |
|---|---|
| `feature/workout/.../shadow/ShadowSessionRecorder.kt` | Interface um `startSession()`/`endSession()` erweitern, `NoOpShadowSessionRecorder` nachziehen |
| `feature/workout/.../shadow/JsonlShadowSessionRecorder.kt` | NEU: JSONL-Schreib-Recorder |
| `feature/workout/.../shadow/di/ShadowRecorderModule.kt` | `object`→`abstract class`, `@Provides`→`@Binds` auf JsonlShadowSessionRecorder |
| `feature/workout/.../TrainViewModel.kt` | `init`/`finishExercise`/`disconnect` Recorder-Lifecycle |
| `tools/shadow_harness.py` | NEU: Python-Auswertungs-Harness |
| `tools/golden_shadow_corpus/` | NEU: Corpus-Verzeichnis (initial leer) |
| `tools/recofit_bootstrap.py` | NEU: RecoFit-Konverter |
| `domain/sensor/.../OrientationTracker.kt` | NEU: Madgwick-Filter |
| `domain/sensor/.../SignalChain.kt` | OrientationTracker in process() integrieren |

---

## Abnahmekriterien (DoD für den Umbauplan)

- [x] `./gradlew :domain:sensor:test` grün (alle Pipeline-Komponenten-Tests)
- [x] `./gradlew :core:testing:test` grün (FakeCalibrationProfileRepository)
- [x] `./gradlew :feature:workout:compileDebugUnitTestKotlin` grün
- [x] Punkt 1: Shadow-Engine verwendet Profil-Achse/Bias (Test: `TrainViewModelTest`)
- [x] Punkt 2: `updateLevels()` wird bei Pipeline-Start aufgerufen (Test: `PeakDetectorTest`)
- [x] Punkt 3: `minQualityScore` = 0.55 im Config-Default (Test: `QualityScorerTest`)
- [x] Punkt 4: Accel-Voting aktivierbar, reduziert False-Positives (Test: `RepCounterTest`)
- [x] Punkt 5: Multi-Template-Pool akzeptiert Best-Match (Test: `TemplateMatcherTest`)
- [x] Punkt 6: Adaptive Refraktärzeit bei schnellen Reps (Test: `PeakDetectorTest`)
- [x] Punkt 7: JSONL wird geschrieben, `shadow_harness.py` produziert Report
- [x] Punkt 8: `OrientationTrackerTest` grün (Madgwick-Filter)
- [x] Punkt 9: `recofit_bootstrap.py` konvertiert .mat → JSONL
- [x] `docs/STATUS_FORTSCHRITT.md` aktualisiert (Abschnitt G, siehe Reporting)

---

## Reporting

Jeder abgeschlossene Punkt wird in `docs/STATUS_FORTSCHRITT.md` unter einem
neuen Abschnitt **G. Rep-Zählung-Umbau (2026-08-12)** eingetragen:

```
## G. Rep-Zählung-Umbau (2026-08-12, Session: Claude-<hex>)

- [x] Punkt 1 (SOFORT): Shadow-Engine mit Profil-Achse/Bias — erledigt
- [x] Punkt 2 (SOFORT): SPK/NPK aus Profil laden — erledigt
- [x] Punkt 3 (SOFORT): QualityScorer-Schwelle vereinheitlicht — erledigt
- [x] Punkt 4 (MITTELFRISTIG): Accel als zweiter Kanal
- [x] Punkt 5 (MITTELFRISTIG): Multi-Template / adaptives Template
- [x] Punkt 6 (MITTELFRISTIG): Adaptive Refraktärzeit
- [~] Punkt 7 (LANGFRISTIG): Gate 11b durchlaufen — Hardware-Validierung durch Adi offen
- [x] Punkt 8 (LANGFRISTIG): Orientierungsschätzung (Madgwick)
- [x] Punkt 9 (LANGFRISTIG): RecoFit/MM-Fit als Validierung
```