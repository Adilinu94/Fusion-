# FlowRep UI/UX-Umbauhandbuch
## Bedienung von Training, Musik, Waveform, DropSync und Audio-Uebergaengen

**Projekt:** DropSync-Timer / FlowRep
**Plattform:** Native Android, Kotlin, Jetpack Compose
**Offline-Anforderung:** Vollstaendig offline nutzbar
**Designrichtung:** Premium Fitness Tech, Editorial Minimalism, Schwarz/Weiss/Lime
**Status:** Umsetzungsplan, kein Produktionscode in diesem Dokument geaendert
**Zielgruppe:** Entwickler oder Coding-KI ohne tiefes Vorwissen ueber das bestehende UI
**Datum:** 2026-08-07

> **Verbindlicher Rahmen:** Dieses Handbuch setzt das Design-Dokument `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (flowrep-main) um. Alle dort getroffenen Produktentscheidungen haben Vorrang, insbesondere: **4 Tabs** (Train/Music/Verlauf/Einstellungen), **keine Sessions** (flache Satz-Liste), **Piep-Töne statt TTS** fuer 3-2-1-Go, **Marker Review in Music statt Settings**, **ReplayGain nur als Einstellung (Default aus)**.

---

## 0. Kurzfassung fuer die Umsetzung

Die aktuelle UI besitzt bereits viele gute Bausteine:

- Compose-Navigation;
- Music-, Training- und Settings-Bereiche;
- Mini-Player in der Shell;
- Now-Playing-Screen;
- interaktive Waveform;
- manuelle Marker;
- automatische Marker-Kandidaten;
- Rest-Timer;
- Drop-Rest-Karte;
- Reps-/Gewichtseingabe;
- Rest-Presets;
- Crossfade- und Mix-Einstellungen;
- FlowRep-Designsystem mit Raleway, Schwarz, Weiss und Lime;
- `ProgressRing`, `BrandCard`, Brand Buttons und Outline-Icons.

Das Hauptproblem ist nicht, dass zu wenig UI vorhanden waere. Das Problem ist, dass wichtige Funktionen aktuell auf mehreren Karten und Ebenen nebeneinander existieren. Waerend des Trainings konkurrieren dadurch:

```text
Timer
DropRestCard
Workout-Set-Eingabe
Musik-Mini-Player
Rest-Einstellungen
Workout-Navigation
```

Der zentrale UI-Umbau lautet:

> **Die App wird waehrend eines Trainings zu einer Workout-Konsole. Musik und DropSync sind darin ein klarer Status- und Aktionsbereich, aber keine konkurrierenden Vollbildkarten.**

Die neue Prioritaet auf dem aktiven Train-Screen lautet:

```text
1. Welche Uebung ist aktiv?
2. Welcher Satz wird gerade bearbeitet?
3. Welche Gewichte/Reps werden gespeichert?
4. Was ist die naechste grosse Aktion?
5. Wie lange laeuft die Pause?
6. Ist DropSync vorbereitet?
7. Welche Musik laeuft?
8. Welche technischen Details sind nur optional relevant?
```

Die Hauptnavigation soll vier klar erkennbare Bereiche anbieten:

```text
Train | Music | Verlauf | Einstellungen
```

Wenn die bestehende Produktentscheidung bewusst bei drei Hauptzielen bleiben soll, kann `Verlauf` vorerst als Unterseite von `Train` bestehen bleiben. Aus UX-Sicht ist ein eigener sichtbarer Verlauf aber die bessere langfristige Struktur.

---

# 1. Ziele, Nicht-Ziele und Leitprinzipien

## 1.1 Ziele

Der Umbau soll erreichen:

- schnelle einhaendige Bedienung im Fitnessstudio;
- klare Bedienung bei verschwitzten oder ungenauen Fingern;
- moeglichst wenig Texteingabe waehrend eines Satzes;
- sichtbare und verstaendliche DropSync-Zustaende;
- keine versehentlichen Skips, Seeks oder Planabbrueche;
- Musik und Timer als zusammengehoerige, aber unterscheidbare Funktionen;
- automatische Erkennung als Vorschlag und nicht als unkontrollierte Wahrheit;
- Marker-Bearbeitung direkt an der Waveform;
- technische Audioinformationen nur dort, wo sie wirklich helfen;
- gute Bedienung bei heller Studiobeleuchtung, Bewegung und kurzen Blicken;
- Accessibility ohne Abhaengigkeit von Farbe oder Gesten;
- Erhalt der bestehenden FlowRep-Markenidentitaet;
- keine neue Backend- oder Netzwerkabhaengigkeit;
- keine direkte Media3-Abhaengigkeit in Feature- oder UI-Modulen.

## 1.2 Nicht-Ziele

Dieser UI-Umbau baut nicht:

- eine neue Audio-Engine;
- eine neue Marker-Analyse-Engine;
- eine Cloud-Synchronisation;
- einen Account- oder Login-Flow;
- eine neue Workout-Datenbank;
- eine vollstaendige DJ-Wellenform-DAW;
- eine universelle Bluetooth-Latenzgarantie;
- eine vollstaendig neue visuelle Marke;
- eine UI, die technische Genauigkeit besser darstellt als sie gemessen wurde.

## 1.3 Leitprinzipien

### Prinzip A: Eine primaere Aktion pro Zustand

Jeder aktive Screen-Zustand braucht genau eine dominante Aktion.

Beispiele:

```text
Leerer Trainingszustand:       Training starten
Aktiver Satz:                   Satz fertig
Rest laeuft:                   Pause/Resume
Drop vorbereitet:              Rest abbrechen oder weiterlaufen lassen
Now Playing:                   Play/Pause
Marker-Review:                 Bestaetigen oder Verwerfen
```

Nicht mehrere grosse Lime-Buttons gleichzeitig nebeneinander anzeigen.

### Prinzip B: Der Daumen entscheidet

Primaere Aktionen liegen im unteren oder mittleren Bereich des Bildschirms. Seltene oder potenziell destruktive Aktionen liegen oben rechts, in Menues oder hinter bewusstem Swipe.

### Prinzip C: Status vor Technik

Die normale UI sagt:

```text
DROP BEREIT
```

Nicht:

```text
TransitionPhase.ARMED / AudioTrackEstimate
```

Technische Details sind in einer optionalen Diagnoseebene sichtbar.

### Prinzip D: Gesten duerfen nie die einzige Bedienung sein

Waveform-Scrubbing und Marker-Long-Press sind zusaetzliche schnelle Wege. Es muss immer auch eine sichtbare Aktion oder ein Accessibility-Pfad existieren.

### Prinzip E: Der Nutzer hat Vorrang

Play, Pause, Seek, Skip und Abbruch durch den Nutzer muessen einen automatischen Drop-Plan sichtbar und nachvollziehbar unterbrechen. Die App darf nicht heimlich spaeter trotzdem eine alte Transition ausfuehren.

### Prinzip F: Offline ist ein sichtbarer Vorteil

Analyse, Marker, Musik und Training funktionieren ohne Netz. Die UI soll keine Netzwerk-Spinner oder nichtssagenden Synchronisationszustande anzeigen.

---

# 2. Ist-Zustand des aktuellen UI

## 2.1 Aktuelle App-Shell

Die aktuelle Shell liegt in:

```text
app/src/main/kotlin/com/dropsync/app/DropSyncApp.kt
```

Sie besitzt derzeit drei Top-Level-Ziele:

```text
MUSIC
TRAINING
SETTINGS
```

Auf kompakten Geraeten wird eine `NavigationBar` verwendet. Auf groesseren Breiten ein `NavigationRail`. Das ist grundsaetzlich richtig und soll beibehalten werden.

Der Mini-Player bleibt als Shell-Element sichtbar. Das ist wichtig, weil Musik waehrend Training und Bibliothek weiterlaufen kann.

## 2.2 Aktueller Train-Aufbau

Der Training-Tab enthaelt derzeit sinngemaess:

```text
TimerSection
DropRestCard
WorkoutFeature
```

Die Idee ist korrekt: Timer und Drop-Rest bleiben sichtbar, waehrend die innere Workout-Navigation Session, Routinen und Fortschritt zeigt.

Das Problem ist die visuelle Konkurrenz. In einer aktiven Session gibt es mehrere Karten mit eigenen Ueberschriften und Aktionen. Ein Nutzer muss zu oft lesen, um zu verstehen, was jetzt wichtig ist.

## 2.3 Aktueller Set-Aufbau

`WorkoutScreen.kt` besitzt `SetEntryCard` mit:

- Uebungstitel;
- Uebungstausch;
- Gewicht;
- Reps;
- pro Hand Checkbox;
- Restlabel;
- Rest bearbeiten;
- Rest starten;
- Satz abschliessen.

Funktional ist das umfassend. Fuer schnelle Nutzung ist es zu formularlastig. Das UI sollte zwischen einem **Schnellmodus** und einem **Detailmodus** unterscheiden.

## 2.4 Aktueller Rest-Timer

`TimerSection.kt` zeigt:

- Rest-Presets im Idle-Zustand;
- grossen Progress-Ring;
- Restzeit;
- Pause/Resume;
- Abbrechen;
- Completed-Zustand;
- Haken bei Abschluss.

Das ist als Timer-Karte gut. Fuer DropSync fehlen jedoch im selben Hero sichtbar:

- Ziel-Track;
- Markername;
- Vorbereitungsstatus;
- Audio-Confidence;
- sichtbarer manueller Override;
- Unterschied zwischen normalem Rest und Drop-Rest.

## 2.5 Aktuelle DropRestCard

`DropRestCard.kt` unterscheidet Idle und Active und zeigt Blockadegruende. Das ist fachlich sauber.

UX-Problem:

```text
TimerSection = eine grosse Timerkarte
DropRestCard = eine weitere Karte
```

Der Nutzer muss zwei Karten mental zusammenfuehren. Ziel ist ein gemeinsamer Rest-Hero mit Unterstatus.

## 2.6 Aktueller Now-Playing-Screen

`NowPlayingScreen.kt` besitzt bereits:

- Cover-Karussell;
- Titel und Artist;
- Waveform;
- Play/Pause in der Waveform;
- Scrubbing erst beim Loslassen;
- Marker als Markerpositionen;
- Long-Press fuer Marker;
- Marker-Dialog;
- Slider-Fallback;
- Queue-Anzeige ueber das Karussell.

Das ist eine solide Basis. Der naechste Schritt ist keine komplette Neugestaltung, sondern die Erweiterung zum Marker- und Drop-Editor.

## 2.7 Aktuelle Settings-Seite

`SettingsScreen.kt` enthaelt sehr viele Themen in einer langen Liste:

```text
Darstellung
Akzentfarbe
Audio-Einstieg
Mix-Uebergaenge
Rest-Musik
Workout-Extras
Marker-Import
Nicht zugeordnete Marker
Auto-Marker-Review
Datenschutz
```

Diese Inhalte muessen nicht alle entfernt werden. Sie brauchen aber eine bessere Informationsarchitektur.

---

# 3. Ziel-Informationsarchitektur

## 3.1 Hauptnavigation

Empfohlene Struktur:

```text
Train
Music
Verlauf
Einstellungen
```

### Train

Der operative Bereich fuer Uebungen und Sätze. Keine Sessions, keine Routinen. Ein Satz ist ein Zeitstempel + Uebung + Gewicht + Reps.

Unterseiten oder interne Navigation:

```text
Uebung waehlen (Chip oben)
Satz eingeben (Gewicht + Reps)
Satz fertig -> Rest
Uebung abschliessen
```

### Music

Musiksteuerung und Musikvorbereitung.

```text
Music Home
Now Playing
Work-Playlisten
Rest-Playlisten
Marker Review
Analysestatus
Queue
```

### Verlauf

Historie und Auswertung.

```text
Heute
Woche
Monat
Uebung
PRs
Volumen
Session-Musik
```

### Einstellungen

Nur dauerhafte Verhalten und Konfiguration.

```text
Training & Pausen
Musik & Uebergaenge
DropSync
Audio Experten
Darstellung
Daten & Marker
Datenschutz
```

## 3.2 Wenn vorerst drei Tabs bleiben

Falls die bestehende Drei-Tab-Vorgabe nicht geaendert werden soll:

```text
Music | Train | Settings
```

Dann gilt:

- Verlauf wird als gut sichtbare Unterseite von Train angeboten;
- Music Home enthaelt Marker Review und Playlisten;
- Einstellungen enthaelt nur Konfiguration, nicht die operative Kandidatenpruefung;
- der Nutzer erreicht Verlauf maximal mit zwei Taps aus der Hauptnavigation.

## 3.3 Navigationselemente als Kotlin-Modell

**Pseudocode:**

```kotlin
enum class TopLevelDestination(
    val route: String,
    val iconRes: Int,
    val labelRes: Int,
) {
    TRAIN("train", BrandIcons.NavTraining, R.string.nav_train),
    MUSIC("music", BrandIcons.NavMusic, R.string.nav_music),
    HISTORY("history", BrandIcons.NavHistory, R.string.nav_history),
    SETTINGS("settings", BrandIcons.NavSettings, R.string.nav_settings),
}
```

Wenn `HISTORY` noch nicht umgesetzt wird, bleibt es vorerst eine interne Route:

```kotlin
const val ROUTE_HISTORY = "history"
```

Die Top-Level-Navigation soll nicht bei jedem Tap neue Backstack-Eintraege stapeln:

```kotlin
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
```

---

# 4. Ziel-Zustandsmodell fuer die UI

Die UI darf nicht nur Screennamen kennen. Sie muss den laufenden Zustand ausdruecken.

## 4.1 Workout-Zustaende

```kotlin
enum class WorkoutUiMode {
    IDLE,          // keine Uebung gewaehlt, leere Konsole
    SET_ENTRY,     // Uebung gewaehlt, Gewicht/Reps eingeben
    REST_RUNNING,  // Pause laeuft nach Satz fertig
    GO_CUE,        // Go-Moment erreicht
    EXERCISE_DONE, // Uebung abgeschlossen, naechste waehlen
}
```

Keine Session-Modi. Die App kennt nur Uebungen und Sätze als flache Liste (Zeitstempel + Uebung + Gewicht + Reps).

## 4.2 Audio-Zustaende

```kotlin
enum class AudioUiState {
    STOPPED,
    PLAYING,
    PAUSED,
    BUFFERING,
    ROUTE_CHANGED,
    ERROR,
}
```

## 4.3 DropSync-Zustaende

```kotlin
enum class DropSyncUiState {
    OFF,
    PLANNING,
    PREPARING,
    READY,
    ARMED,
    GO,
    LANDED,
    BEST_EFFORT,
    MANUAL_OVERRIDE,
    CANCELLED,
    FAILED,
}
```

## 4.4 Gemeinsames UI-Modell

Das Feature-Modul soll keine Media3-Typen sehen. Es kann ein Domain-/Feature-Modell bekommen:

**Pseudocode:**

```kotlin
data class WorkoutConsoleUiState(
    val mode: WorkoutUiMode = WorkoutUiMode.IDLE,
    val exerciseName: String? = null,
    val setNumber: Int = 0,
    val totalSets: Int? = null,
    val weightText: String = "",
    val repsText: String = "",
    val repsSource: RepsSource = RepsSource.MANUAL,
    val perHand: Boolean = false,
    val rest: RestUiState = RestUiState.Idle,
    val dropSync: DropSyncUiStateModel = DropSyncUiStateModel.Off,
    val music: SessionMusicUiState = SessionMusicUiState.Hidden,
    val error: ConsoleError? = null,
)

sealed interface RestUiState {
    data object Idle : RestUiState

    data class Running(
        val remainingMs: Long,
        val totalMs: Long,
        val paused: Boolean,
    ) : RestUiState

    data class Completed(
        val acknowledged: Boolean,
    ) : RestUiState
}

sealed interface RepsSource {
    data object MANUAL : RepsSource
    data object SENSOR : RepsSource
    data object SENSOR_WITH_MANUAL_CORRECTION : RepsSource
    data object SENSOR_DISCONNECTED : RepsSource
}
```

## 4.5 UI-State darf keine technische Luecke verstecken

Schlecht:

```kotlin
val dropSyncActive: Boolean
```

Das reicht nicht. Die UI muss wissen, ob der Plan:

- noch berechnet wird;
- den Zieltrack vorbereitet;
- armed ist;
- wegen Nutzerinteraktion verworfen wurde;
- wegen Bluetooth nur Best Effort ist.

Besser:

```kotlin
sealed interface DropSyncUiStateModel {
    data object Off : DropSyncUiStateModel

    data object Planning : DropSyncUiStateModel

    data class Preparing(
        val trackTitle: String,
        val markerLabel: String,
    ) : DropSyncUiStateModel

    data class Armed(
        val trackTitle: String,
        val markerLabel: String,
        val remainingMs: Long,
        val confidence: TimingConfidenceUi,
    ) : DropSyncUiStateModel

    data class ManualOverride(
        val reason: String,
    ) : DropSyncUiStateModel

    data class BestEffort(
        val reason: String,
    ) : DropSyncUiStateModel

    data class Failed(
        val message: String,
        val canRetry: Boolean,
    ) : DropSyncUiStateModel
}
```

---

# 5. Phase 0: Designsystem- und UI-Basis sichern

## 5.1 Bestehende Designsystem-Regeln beibehalten

Die vorhandene FlowRep-Marke ist passend und wird nicht ersetzt:

```text
Schwarz = Fokus
Weiss = Ruhe
Lime = Energie / Primaeraktion
Raleway = konsistente Typografie
```

Bestehende relevante Dateien:

```text
core/designsystem/.../theme/Theme.kt
core/designsystem/.../theme/Type.kt
core/designsystem/.../theme/Spacing.kt
core/designsystem/.../component/Buttons.kt
core/designsystem/.../component/BrandCard.kt
core/designsystem/.../component/ProgressRing.kt
core/designsystem/.../chart/Waveform.kt
```

## 5.2 Lime semantisch strenger verwenden

Lime soll nur fuer aktive, wichtige Zustaende verwendet werden:

```text
Primaerer CTA
Aktiver Timerfortschritt
Aktiver DropSync-Marker
Drop READY / ARMED
Aktive Navigation
Bestaetigter aktiver Zustand
```

Lime soll nicht gleichzeitig fuer alle Titel, alle Links und alle Marker verwendet werden. Sonst verliert es seine Signalwirkung.

## 5.3 Neue UI-Tokens

**Pseudocode:**

```kotlin
object FlowRepUiTokens {
    val minTouchTarget = 48.dp
    val compactTouchTarget = 44.dp
    val cardRadius = 24.dp
    val heroRadius = 32.dp
    val sectionGap = 24.dp
    val majorSectionGap = 32.dp
    val controlGap = 8.dp
    val consoleBottomPadding = 24.dp
    val timerHeroSize = 224.dp
}
```

Die Werte sollen als Tokens in `:core:designsystem` liegen, nicht als zufaellige Literale in jedem Screen.

## 5.4 Zustandsfarben

Die UI darf nicht nur `primary` verwenden. Fuer Status sollten zusaetzliche semantische Farben definiert werden, ohne das Kernfarbsystem zu zerstoeren:

```kotlin
data class FlowRepStatusColors(
    val ready: Color,
    val warning: Color,
    val error: Color,
    val neutral: Color,
)
```

Empfehlung:

```text
READY: Lime
WARNING/BEST_EFFORT: warmes Amber, sparsam
ERROR: gedecktes Rot
NEUTRAL: Outline/Gray
```

Die Farbe muss immer mit Text oder Icon kombiniert werden. Niemals nur Rot/Gruen als Bedeutung verwenden.

---

# 6. Phase 1: Train-Screen zur Workout Console umbauen

## 6.1 Zielstruktur

Der aktive Train-Screen sollte in dieser Reihenfolge aufgebaut sein:

```text
TopAppBar
  Uebung / Aktionen / Overflow

WorkoutConsoleHeader
  Uebungsname
  Zeit / Sensorstatus / Musikstatus

SetEntryHero
  Uebungsname
  Gewicht
  Reps
  Auto-/Manuellstatus
  Satz fertig

RestConsole
  nur sichtbar, wenn Rest aktiv

SessionActions
  Uebung hinzufuegen / Uebung abschliessen / Verlauf
```

## 6.2 Kein permanenter Timer-Hero waehrend des aktiven Satzes

Wenn der Nutzer gerade einen Satz eintraegt, soll der Rest-Timer nicht genauso gross wie die Satz-Eingabe sein. Er wird erst nach `Satz fertig` dominant.

Zustand:

```text
Aktiver Satz:
  Satz-Eingabe gross
  Rest kompakt oder nicht sichtbar

Rest:
  Rest-Timer gross
  Eingabe fuer naechsten Satz vorbereitet
```

Das verhindert, dass zwei grosse Zahlen gleichzeitig um Aufmerksamkeit kaempfen.

## 6.3 Beispiel fuer `WorkoutConsole`

**Pseudocode:**

```kotlin
@Composable
fun WorkoutConsole(
    state: WorkoutConsoleUiState,
    onWeightChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onRepAdjustment: (Int) -> Unit,
    onCompleteSet: () -> Unit,
    onPauseRest: () -> Unit,
    onResumeRest: () -> Unit,
    onCancelRest: () -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.space16,
            end = Spacing.space16,
            top = Spacing.space16,
            bottom = Spacing.space32,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.space16),
    ) {
        item {
            WorkoutConsoleHeader(state)
        }

        when (state.mode) {
            WorkoutUiMode.IDLE -> {
                item {
                    EmptyOrReadySessionHero(state)
                }
            }

            WorkoutUiMode.SET_ENTRY -> {
                item {
                    SetEntryHero(
                        state = state,
                        onWeightChange = onWeightChange,
                        onRepsChange = onRepsChange,
                        onRepAdjustment = onRepAdjustment,
                        onCompleteSet = onCompleteSet,
                    )
                }
            }

            WorkoutUiMode.REST_RUNNING,
            WorkoutUiMode.GO_CUE,
            WorkoutUiMode.EXERCISE_DONE -> {
                item {
                    RestConsole(
                        state = state,
                        onPause = onPauseRest,
                        onResume = onResumeRest,
                        onCancel = onCancelRest,
                    )
                }
            }
        }

        item {
            SessionMusicStrip(
                music = state.music,
                onOpenMusic = onOpenMusic,
            )
        }
    }
}
```

Die `when`-Struktur ist absichtlich explizit. Eine schwache Implementierung soll nicht versuchen, alle Zustaende mit zehn Boolean-Flags zu kombinieren.

---

# 7. Phase 2: Set-Eingabe fuer eine Hand optimieren

## 7.1 Zielbild

Der wichtigste Teil des Satzes sollte so aussehen:

```text
BANKDRUECKEN

Gewicht
[ 80.0 kg ]

Reps
[ − ]      8      [ + ]

[ SATZ FERTIG ]
```

Das Gewicht kann weiter ueber Tastatur eingegeben werden. Reps sollten zusaetzlich grosse `+/-`-Buttons erhalten.

## 7.2 Warum `+/-` wichtig sind

Im Gym:

- Haende sind verschwitzter;
- Nutzer wechseln zwischen Hantel und Telefon;
- Tastatur oeffnen kostet Zeit;
- Reps werden oft nur um eins korrigiert;
- automatische BLE-Zaehlung kann sich um eine Rep irren.

`+/-` sind deshalb keine Komfortfunktion, sondern der schnelle Korrekturpfad.

## 7.3 Compose-Beispiel

**Pseudocode:**

```kotlin
@Composable
private fun RepStepper(
    reps: Int?,
    source: RepsSource,
    onAdjust: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.workout_reps),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { onAdjust(-1) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(BrandIcons.Remove),
                    contentDescription = stringResource(R.string.workout_reps_decrease),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = reps?.toString() ?: "–",
                    style = MaterialTheme.typography.displayMedium,
                    fontFeatureSettings = "tnum",
                )
                RepSourceLabel(source)
            }

            IconButton(
                onClick = { onAdjust(1) },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(BrandIcons.Add),
                    contentDescription = stringResource(R.string.workout_reps_increase),
                )
            }
        }
    }
}
```

## 7.4 Reps-Quellen sichtbar machen

Gemeinsames Rep-Ergebnis statt zwei konkurrierender Werte:

```text
8
AUTO
Sensor verbunden
```

```text
8
MANUELL KORRIGIERT
Sensor erkannte 7
```

```text
8
MANUELL
Sensor nicht verbunden
```

**Pseudocode:**

```kotlin
@Composable
private fun RepSourceLabel(source: RepsSource) {
    val (text, icon) = when (source) {
        RepsSource.SENSOR -> R.string.reps_source_auto to BrandIcons.Sensor
        RepsSource.SENSOR_WITH_MANUAL_CORRECTION -> {
            R.string.reps_source_corrected to BrandIcons.Edit
        }
        RepsSource.MANUAL -> R.string.reps_source_manual to BrandIcons.Edit
        RepsSource.SENSOR_DISCONNECTED -> {
            R.string.reps_source_disconnected to BrandIcons.SensorOff
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelSmall)
    }
}
```

## 7.5 Satzabschluss

Der `Satz fertig`-Button muss:

- volle Breite haben;
- mindestens 56 dp hoch sein;
- bei ungueltiger Eingabe deaktiviert sein;
- eine klare aktive/Pressed-Animation haben;
- direkt den naechsten Zustand ausloesen;
- eine Undo-Moeglichkeit anzeigen.

Nach dem Tap:

```text
1. Satz wird gespeichert
2. kurze haptische Bestaetigung
3. Undo-Snackbar fuer 10 Sekunden
4. Rest-Console wird dominant
5. Eingabe fuer den naechsten Satz wird vorbereitet
```

Die bestehende Snackbar-Undo-Logik soll beibehalten und visuell staerker in den Workflow eingebunden werden.

---

# 8. Phase 3: Rest-Timer und DropSync zu einer Rest Console verbinden

## 8.1 Aktuelles Problem

Der Nutzer sieht derzeit separate Komponenten:

```text
TimerSection
DropRestCard
```

Besser ist eine gemeinsame Komponente, die im Inneren zwischen normalem Rest und DropSync unterscheidet:

```text
RestConsole
├── RestHeader
├── RestTimerHero
├── DropSyncStatus
├── RestMusicStatus
└── RestActions
```

## 8.2 Idle-Zustand

Wenn kein Timer laeuft:

```text
REST STARTEN

[ 60 s ] [ 90 s ] [ 120 s ] [ 180 s ]

[ DropSync fuer diesen Rest verwenden ]
```

Die DropSync-Auswahl darf nicht in einem komplizierten Dialog versteckt sein, wenn sie haeufig genutzt wird. Die gespeicherte Uebungs-Praeferenz bleibt aber erhalten.

## 8.3 Normaler Rest

```text
REST
01:27

Naechster Satz
Bankdruecken · Satz 4

Musik laeuft normal

[ PAUSE ]  [ +15 s ]  [ ABBRECHEN ]
```

## 8.4 DropSync wird geplant

```text
DROPSYNC WIRD VORBEREITET
01:27

Work-Track
„Track Name"

Zielmarker
Drop 2 · 01:28

[ ABBRECHEN ]
```

Der Timer bleibt dominant. Technische Vorbereitung wird als kurze Statuszeile darunter gezeigt.

## 8.5 DropSync ist bereit

```text
DROP BEREIT
01:27

„Track Name"
Drop 2 trifft bei 00:00

● Audio vorbereitet
● Timing stabil

[ PAUSE ]  [ +15 s ]  [ ABBRECHEN ]
```

## 8.6 Best-Effort-Zustand

```text
BEST EFFORT
01:27

Bluetooth-Ausgabe wurde geaendert.
Der Track laeuft weiter, aber der Drop kann leicht abweichen.

[ WEITER ]  [ PLAN ABBRECHEN ]
```

Nicht sofort den Timer abbrechen. Der Nutzer soll bewusst entscheiden koennen.

## 8.7 Manueller Override

```text
MANUELL UEBERNOMMEN

Du hast die Musik veraendert.
DropSync ist fuer diese Pause deaktiviert.

[ OK ]
```

Der Status muss erklaeren, warum der Plan nicht mehr aktiv ist. Sonst sieht der Nutzer nur, dass der Drop spaeter nicht wie erwartet landet.

## 8.8 Go-Zustand

Wenn der Rest endet:

```text
GO

BANKDRUECKEN
80 kg × 8

DROP GELANDET
```

Diese Anzeige sollte als kurzes Overlay oder klarer Zustand der Rest-Console erscheinen. Nicht als neue Navigation, damit der Zurueck-Stack nicht veraendert wird.

---

# 9. Rest-Console Compose-Struktur

**Pseudocode:**

```kotlin
@Composable
fun RestConsole(
    rest: RestUiState,
    dropSync: DropSyncUiStateModel,
    music: SessionMusicUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onExtend: (Long) -> Unit,
    onCancel: () -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.space16),
        ) {
            RestConsoleHeader(dropSync)
            RestTimerHero(rest)
            DropSyncStatusBlock(dropSync)
            SessionMusicStrip(
                music = music,
                onOpenMusic = onOpenMusic,
            )
            RestActionRow(
                rest = rest,
                onPause = onPause,
                onResume = onResume,
                onExtend = onExtend,
                onCancel = onCancel,
            )
        }
    }
}
```

## 9.1 Timer-Aktionen

Die grossen Aktionen sollen im unteren, gut erreichbaren Bereich liegen:

```text
[ Pause / Resume ] [ +15 s ] [ Abbrechen ]
```

Bei sehr kleinen Bildschirmen:

```text
[ PAUSE ]
[ +15 s ] [ ABBRECHEN ]
```

Nicht mehr als drei gleichwertige Buttons in einer Reihe erzwingen.

## 9.2 Timer-Tap

Ein Tap auf den Ring darf Pause/Resume ausloesen, aber die Accessibility-Beschreibung muss das klar machen:

```text
Resttimer 1 Minute 27 Sekunden, laeuft. Doppeltippen zum Pausieren.
```

Ein separater sichtbarer Button bleibt trotzdem notwendig.

---

# 10. Phase 4: Session Music Strip

## 10.1 Ziel

Der Nutzer soll im Training sehen, ob Musik aktiv ist, ohne den gesamten Music-Screen zu oeffnen.

## 10.2 Normalzustand

```text
[Cover] Track Name
        Artist · 01:18
                           [ Oeffnen ]
```

## 10.3 DropSync-Zustand

```text
[Cover] DROP READY
        Track Name · Drop 2
                           [ Details ]
```

## 10.4 Buffering-Zustand

```text
[Cover] AUDIO WIRD GELADEN
        DropSync wird neu bewertet
```

## 10.5 Implementation

**Pseudocode:**

```kotlin
@Composable
fun SessionMusicStrip(
    music: SessionMusicUiState,
    onOpenMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (music) {
        SessionMusicUiState.Hidden -> Unit

        is SessionMusicUiState.Playing -> {
            MusicStatusRow(
                title = music.title,
                subtitle = music.artist,
                status = music.statusLabel,
                coverUri = music.coverUri,
                onClick = onOpenMusic,
                modifier = modifier,
            )
        }

        is SessionMusicUiState.DropReady -> {
            MusicStatusRow(
                title = music.title,
                subtitle = "${music.markerLabel} · ${music.remainingLabel}",
                status = stringResource(R.string.music_drop_ready),
                coverUri = music.coverUri,
                statusAccent = true,
                onClick = onOpenMusic,
                modifier = modifier,
            )
        }

        is SessionMusicUiState.BestEffort -> {
            MusicStatusRow(
                title = music.title,
                subtitle = music.reason,
                status = stringResource(R.string.music_best_effort),
                coverUri = music.coverUri,
                onClick = onOpenMusic,
                modifier = modifier,
            )
        }
    }
}
```

---

# 11. Phase 5: Mini-Player sicherer und kontextabhaengig machen

## 11.1 Aktuelles Verhalten

Der aktuelle Mini-Player zeigt:

- Cover;
- Titel;
- Play/Pause;
- Skip Next;
- Queue.

Das ist im normalen Musikbetrieb sinnvoll. Waehren eines aktiven DropSync-Plans ist `Skip Next` aber eine gefaehrliche Aktion.

## 11.2 Normaler Mini-Player

```text
[Cover] Track Name         [Play] [Next] [Queue]
        Artist
```

## 11.3 Mini-Player waehrend DropSync

```text
[Cover] DROP READY         [Pause] [Details]
        Track Name · Drop 2
```

`Next` wird nicht direkt prominent angezeigt. Es kann ueber Details oder einen bewusst bestaetigten Overflow erreichbar bleiben.

## 11.4 Skip-Schutz

Wenn der Nutzer waehrend DropSync skippt:

1. aktive Transition sofort logisch abbrechen;
2. Musik normal weiterbedienen;
3. Snackbar anzeigen:

```text
DropSync abgebrochen
[ Rueckgaengig ]
```

Das Rueckgaengig darf nur den UI-Plan wiederherstellen, wenn der Audiozustand noch sinnvoll wiederherstellbar ist. Es darf nicht versprechen, dass ein bereits verpasster Drop zurueckkommt.

## 11.5 Mini-Player-Komponente

**Pseudocode:**

```kotlin
@Composable
fun MiniPlayer(
    state: MiniPlayerUiState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTransitionDetails: () -> Unit,
) {
    if (!state.isVisible) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            MiniProgressLine(state)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniCover(state.coverUri)
                MiniTitleBlock(
                    state = state,
                    onClick = onOpenNowPlaying,
                    modifier = Modifier.weight(1f),
                )

                IconButton(onClick = onTogglePlayback) {
                    PlayPauseIcon(state.isPlaying)
                }

                if (state.dropSyncStatus == MiniDropSyncStatus.OFF) {
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            painterResource(BrandIcons.SkipNext),
                            contentDescription = stringResource(R.string.player_next),
                        )
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            painterResource(BrandIcons.Queue),
                            contentDescription = stringResource(R.string.player_queue_open),
                        )
                    }
                } else {
                    IconButton(onClick = onOpenTransitionDetails) {
                        Icon(
                            painterResource(BrandIcons.Info),
                            contentDescription = stringResource(R.string.drop_sync_details),
                        )
                    }
                }
            }
        }
    }
}
```

---

# 12. Phase 6: Music Home neu ordnen

## 12.1 Zielstruktur

Der Music-Tab soll nicht nur eine lange Bibliothek zeigen. Er braucht eine klare Musik-Startseite:

```text
MUSIC

Now Playing

[ WORK ]
12 Titel · 9 Drops bestaetigt

[ REST ]
18 Titel · analysiert

Marker pruefen       3
Analyse ausstehend   6

Zuletzt gespielt
```

## 12.2 Work- und Rest-Playlisten

Die vorhandenen Playlist-Labels `REST` und `WORK` sollen visuell sofort erkennbar sein.

Beispiel:

```text
┌─────────────────────────────┐
│ WORK                        │
│ Heavy / Energie             │
│ 12 Titel · 9 Drops          │
│ 118–142 BPM                 │
└─────────────────────────────┘
```

```text
┌─────────────────────────────┐
│ REST                        │
│ Recovery / Fokus            │
│ 18 Titel · 14 analysiert    │
│ 72–104 BPM                  │
└─────────────────────────────┘
```

Lime kann fuer `WORK` oder den aktiven Zustand verwendet werden. `REST` sollte neutral bleiben, damit die Farbsemantik nicht ueberladen wird.

## 12.3 Playlist-Qualitaetsstatus

Eine Playlist sollte nicht nur Titelanzahl zeigen, sondern auch DropSync-Tauglichkeit:

```text
12 Titel
9 bestaetigte Drops
3 Vorschlaege
```

Zustandszeile:

```text
DropSync bereit
```

oder:

```text
Noch 3 Marker pruefen
```

## 12.4 Playlist-Detail

Auf einer Work-Playlist:

```text
WORK PLAYLIST
Heavy Rotation

[ Play ] [ DropSync verwenden ]

Drop-Abdeckung 9 / 12

Titel
Track A        128 BPM · 01:22 Drop
Track B        134 BPM · Marker pruefen
Track C        Analyse ausstehend
```

Die `DropSync verwenden`-Aktion darf nicht nur in globalen Settings existieren. Der Nutzer soll die Playlist direkt als Work-Quelle markieren koennen.

---

# 13. Phase 7: Marker-Review aus Music statt aus Settings

## 13.1 Grundentscheidung

Die aktuelle Settings-Seite zeigt Pending Auto-Detected Marker. Das ist technisch logisch, aber UX-seitig falsch verortet.

Settings ist fuer Regeln:

```text
Automatische Marker erkennen
Sensitivitaet
Mindestabstand
Analyseprofile
```

Music ist fuer Ergebnisse:

```text
Vorschlag anhoeren
Marker bestaetigen
Marker verwerfen
Position korrigieren
```

## 13.2 Marker-Review-Liste

```text
DROP-VORSCHLAEGE

Track A
01:12 · Energieanstieg

[ Mini-Waveform ]

[ ▶ Anhoeren ] [ Bestaetigen ] [ Verwerfen ]
```

Beim Antippen von `Anhoeren`:

- Track startet einige Sekunden vor dem Marker;
- Markerposition wird erreicht;
- die Wiedergabe stoppt oder bleibt nach klarer Nutzerentscheidung weiterlaufen;
- der Vorschlag bekommt einen sichtbaren Review-Status.

## 13.3 Review-Item-Datenmodell

**Pseudocode:**

```kotlin
data class MarkerReviewUiModel(
    val markerId: Long,
    val songTitle: String,
    val artist: String?,
    val positionMs: Long,
    val label: String,
    val confidence: Float?,
    val previewState: MarkerPreviewState,
    val waveformSnippet: List<WaveformBucket>,
)

enum class MarkerPreviewState {
    IDLE,
    PLAYING,
    PAUSED,
    CONFIRMED,
    DISCARDED,
}
```

## 13.4 Bestaetigung

Nach `Bestaetigen`:

```text
Marker bestaetigt
DropSync kann diesen Marker verwenden.
```

Die UI soll nicht einfach die Zeile verschwinden lassen, ohne Feedback. Eine kurze Snackbar oder Undo-Aktion ist sinnvoll.

---

# 14. Phase 8: Now Playing als Waveform- und Marker-Editor

## 14.1 Bestehende Staerken erhalten

Beibehalten:

- grosse Waveform;
- Fortschritts-Highlight;
- Play/Pause in der Waveform;
- Slider-Fallback;
- Seek erst beim Loslassen;
- Long-Press als schneller Markerpfad;
- Cover und Queue-Karussell.

## 14.2 Neue Hierarchie

Empfohlene Reihenfolge:

```text
TopAppBar
Titel / Artist / Work- oder Rest-Chip
Waveform
Marker-Status
Transportsteuerung
DropSync-Aktionen
Technische Metadaten
```

Das Cover darf auf kleinen Geraeten kleiner werden, wenn die Waveform und Marker die Hauptaufgabe sind. Fuer einen normalen Musik-Player kann das Cover prominent bleiben; fuer DropSync ist die Zeitachse wichtiger als das Cover.

## 14.3 Marker-Legende

Unter der Waveform:

```text
│ bestaetigt    ┆ Vorschlag    ◆ aktives Ziel
```

Diese Legende muss auch fuer Accessibility als Text verfuegbar sein.

## 14.4 Marker direkt antippen

Ein Marker-Tap oeffnet ein Bottom Sheet:

```text
DROP 2
01:18.420
Bestaetigt automatisch

[ Ab Marker anhoeren ]
[ Position feinjustieren ]
[ Als DropSync-Ziel waehlen ]
[ Umbenennen ]
[ Loeschen ]
```

## 14.5 Marker-Feinjustierung

Die Position darf nicht nur ueber Pixel-Scrubbing korrigiert werden. Kleine Controls:

```text
[ −100 ms ] [ −10 ms ] 01:18.420 [ +10 ms ] [ +100 ms ]
```

Optional:

```text
[ Auf Beat einrasten ]
```

Die App sollte die urspruengliche Auto-Position behalten, damit der Nutzer eine Korrektur nachvollziehen oder rueckgaengig machen kann.

**Pseudocode:**

```kotlin
data class MarkerEditState(
    val originalPositionMs: Long,
    val editedPositionMs: Long,
    val isDirty: Boolean,
)
```

## 14.6 DropSync-Ziel aus Now Playing waehlen

Im Marker-Menue:

```text
Als DropSync-Ziel verwenden
```

Das ist besonders wichtig, wenn der Nutzer gerade einen Song anhoert und den richtigen Drop findet. Der Weg darf nicht sein:

```text
Back -> Settings -> Marker -> Playlist -> Auswahl
```

Sondern:

```text
Marker antippen -> Als DropSync-Ziel verwenden
```

---

# 15. Phase 9: Waveform-Bedienung im Detail

## 15.1 Tap

Tap auf die Waveform:

- springt direkt zur Position;
- zeigt kurz eine Zeitblase;
- aktualisiert die Fortschrittsposition;
- loest keinen Marker aus.

## 15.2 Drag

Beim Drag:

```text
1. kein seekTo pro Pixel
2. lokale Vorschauposition anzeigen
3. vertikale Cursorlinie anzeigen
4. Marker-Snap optional
5. Seek erst bei onDragEnd
```

## 15.3 Long-Press

Long-Press:

- oeffnet Marker-Dialog;
- erzeugt niemals sofort einen aktiven Marker;
- zeigt Position und optional den naechsten Onset;
- bestaetigt erst nach Nutzeraktion.

## 15.4 Snap-to-Marker

Wenn die Fingerposition innerhalb eines kleinen Zeitfensters liegt:

```text
maximal 80–120 ms Entfernung
```

kann die Position magnetisch an den Marker springen. Bei Snap:

- kurze Haptik;
- sichtbarer Cursor springt auf Marker;
- Text zeigt `Drop 2` statt nur Zeit.

Das Snap-Fenster muss konfigurierbar oder abschaltbar sein.

## 15.5 Compose-Performance

Die bestehende Waveform soll weiter mit vorbereiteten Geometrien arbeiten. Wichtig:

- keine DB-Abfrage in `Canvas`;
- keine Analyseanforderung in `Canvas`;
- keine neue `List` pro Frame;
- Markerpositionen vorab in UI-Modelle umrechnen;
- Fortschritt nur als einfacher Float aktualisieren;
- Drag-Preview lokal halten;
- Analyse-State immutable machen.

**Pseudocode:**

```kotlin
@Composable
fun rememberWaveformGeometry(
    buckets: List<Pair<Float, Float>>,
    widthPx: Int,
    heightPx: Int,
): List<WaveformMapping.Bar> =
    remember(buckets, widthPx, heightPx) {
        WaveformMapping.mapToBars(
            buckets = buckets,
            width = widthPx.toFloat(),
            height = heightPx.toFloat(),
        )
    }
```

In der Praxis kann die Geometrie besser in einem `ViewModel` oder einer kleinen Cache-Klasse vorbereitet werden, wenn die Bucket-Anzahl gross ist.

---

# 16. Phase 10: Settings neu gruppieren

## 16.1 Zielstruktur

Die Settings-Seite soll nicht weniger koennen, sondern weniger gleichzeitig zeigen.

```text
Einstellungen

TRAINING & PAUSEN
  Rest-Presets
  Get Ready
  Standard-Restmodus

MUSIK & UEBERGAENGE
  Crossfade
  Uebergangspreset
  ReplayGain
  Work-/Rest-Verhalten

DROPSYNC
  DropSync aktivieren
  Work-Playlist
  Rest-Playlist
  Automatische Drop-Vorschlaege
  Marker Review oeffnen

AUDIO EXPERTEN
  Equalizer
  Preamp
  DVC
  Resampler
  Bit-Perfect
  Output-Profile

DARSTELLUNG
  Theme
  Akzentfarbe

DATEN & MARKER
  Marker importieren
  Analyse-Cache

DATENSCHUTZ
  Offline-Daten-Hinweis
```

## 16.2 Anfänger und Experten

Die oberste Ebene zeigt nur wichtige, verstaendliche Einstellungen. Technische Optionen werden hinter `Audio Experten` gruppiert.

Anfaenger sehen:

```text
Crossfade
Rest-Ducking
DropSync
Work-Playlist
Rest-Playlist
```

Fortgeschrittene finden innerhalb von maximal zwei Taps:

```text
Audio Experten -> Output / DSP
```

## 16.3 Keine technischen Begriffe als Hauptlabel

Nicht:

```text
DVC
```

Sondern:

```text
Direct Volume Control
Lautstaerkeregelung innerhalb der Audio-Pipeline
```

Nicht:

```text
AudioTrack latency compensation
```

Sondern:

```text
Drop-Timing-Korrektur
Automatisch / Manuell
```

## 16.4 Warnungen sinnvoll formulieren

Bei Bit-Perfect:

```text
Bit-Perfect umgeht DSP und Crossfade.
Equalizer, Ducking und Drop-Uebergaenge koennen dadurch deaktiviert sein.
```

Bei Bluetooth:

```text
Bluetooth kann die hoerbare Ausgabe verzoegern.
DropSync bleibt aktiv, wird aber als Best Effort behandelt.
```

Nicht:

```text
Diese Funktion funktioniert eventuell nicht.
```

---

# 17. Phase 11: DropSync direkt im Satz-Workflow konfigurieren

Die globale Einstellung ist sinnvoll, aber der Nutzer muss DropSync fuer eine konkrete Uebung schnell sehen und aendern koennen.

## 17.1 Rest-Praeferenz im Set

In der Set-Karte kompakt:

```text
Rest 90 s · Normal       [ Aendern ]
```

oder:

```text
Rest 90 s · DropSync     [ Aendern ]
```

Das ist besser als nur:

```text
Restmodus: DROPSYNC
```

## 17.2 Rest-Dialog

Der Rest-Dialog sollte diese Reihenfolge haben:

```text
Restdauer
[ 60 ] [ 90 ] [ 120 ] [ 180 ]

[ Benutzerdefiniert ]

Musik fuer diese Pause
( ) Normal
( ) Rest-Musik
( ) DropSync

[ Speichern ]
```

Wenn `DropSync` keine Work-Playlist oder keinen bestaetigten Marker findet, muss direkt am Auswahlpunkt erklaert werden:

```text
DropSync ist fuer diese Auswahl noch nicht bereit.
Work-Playlist oder bestaetigten Drop-Marker einrichten.
```

Nicht erst nach dem Speichern eine kryptische Blockade anzeigen.

---

# 18. Phase 12: Go-Overlay und Haptik

## 18.1 Ziel

Der Uebergang zum Satz soll sich wie ein Ereignis anfuehlen, ohne die Bedienung zu blockieren.

## 18.2 Go-Overlay

**Pseudocode:**

```kotlin
@Composable
fun GoCueOverlay(
    visible: Boolean,
    exerciseName: String,
    setSummary: String,
    dropStatus: GoDropStatus,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.go_cue_title),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(exerciseName, style = MaterialTheme.typography.headlineSmall)
                Text(setSummary, style = MaterialTheme.typography.bodyLarge)
                GoDropStatusLabel(dropStatus)
            }
        }
    }
}
```

Der Overlay darf nicht automatisch auf ein anderes Ziel navigieren. Er verschwindet nach kurzer Zeit oder bei Tap.

## 18.3 Haptik

Haptik signalisiert:

- Satz gespeichert;
- DropSync armed;
- Timer fertig;
- Go;
- Marker-Snap;
- Fehler oder manueller Override.

Nicht jede Sekunde des Countdowns vibrieren. Das ermuedet und senkt die Bedeutung des Go-Signals.

## 18.4 Go-Signal: Piep-Toene (Entscheidung)

Die Produktentscheidung lautet: **erstmal Piep-Toene**, keine TTS-Sprachansage.

- 3-2-1-Go wird als vorgerenderte Piep-Clips abgespielt: kurzer Beep, kurzer Beep, kurzer Beep, laengerer Beep = Go.
- Die Clips werden vor dem Countdown dekodiert und ueber den Gain-/Mixer-Pfad auf feste Audio-Frames geplant. Go ist damit ein echtes Audio-Event, kein TTS-Callback.
- TTS-Sprache ist keine v1-Anforderung. Spaeter kann eine Option `Go-Signal: Ton / Sprache / Ton + Sprache / Aus` ergaenzt werden, ist aber kein Standard.

Der Vorteil: reproduzierbare Startlatenz, keine Engine-Abhaengigkeit, gleicher Pfad wie die Drop-Landung.

---

# 19. Phase 13: Accessibility

## 19.1 Grundregeln

- Touch-Ziel mindestens 48 dp;
- Icons erhalten Content Descriptions;
- Status wird als Text und nicht nur als Farbe dargestellt;
- Timer hat eine `stateDescription`;
- Waveform bietet Slider-Fallback;
- Gesten besitzen sichtbare Alternativen;
- TalkBack wird nicht mit sekundenweisen Countdowns ueberflutet;
- alle Inhalte funktionieren bei grosser Schrift;
- Text darf nicht nur ueber feste Zeilenhoehe funktionieren.

## 19.2 Semantics fuer Timer

**Pseudocode:**

```kotlin
val timerDescription = when (rest) {
    is RestUiState.Running -> {
        val seconds = (rest.remainingMs + 999) / 1000
        stringResource(
            R.string.timer_accessibility_running,
            formatSeconds(seconds),
        )
    }
    RestUiState.Idle -> stringResource(R.string.timer_accessibility_idle)
    is RestUiState.Completed -> stringResource(R.string.timer_accessibility_completed)
}

Modifier.semantics {
    stateDescription = timerDescription
}
```

## 19.3 Semantics fuer DropSync

Nicht:

```text
Lime dot
```

Sondern:

```text
DropSync bereit. Track „Track Name“. Marker „Drop 2“. Ziel in 1 Minute 27 Sekunden.
```

## 19.4 Waveform Accessibility

Die Canvas-Waveform braucht:

- Content Description;
- Slider-Fallback;
- sichtbare Zeitangabe;
- Marker-Liste als alternative Textansicht.

Beispiel:

```text
Waveform fuer Track Name. Position 01:18 von 03:42. Zwei bestaetigte Marker.
```

Darunter kann ein `LazyColumn` mit Markern nur fuer Accessibility oder bei aktiviertem Detailmodus sichtbar sein.

---

# 20. Phase 14: Fehlende, leere und gesperrte Zustaende

Jede neue UI-Funktion braucht mindestens diese Zustaende:

```text
Loading
Ready
Empty
Unavailable
Error
Retrying
Manual override
```

## 20.1 Keine Work-Playlist

```text
Noch keine Work-Playlist

DropSync braucht eine Work-Playlist mit mindestens einem bestaetigten Marker.

[ Work-Playlist einrichten ]
```

## 20.2 Keine Marker

```text
Keine Drop-Marker vorhanden

Analysiere einen Track oder setze den Marker manuell in Now Playing.

[ Track oeffnen ]
```

## 20.3 Analyse laeuft

```text
Analyse laeuft im Hintergrund
Waveform ist verfuegbar, Drop-Erkennung folgt.
```

Wichtig: Waveform und Drop-Erkennung sind getrennte Verfuegbarkeiten. Die Waveform soll nicht verschwinden, nur weil BPM oder Onset noch nicht fertig sind.

## 20.4 Bluetooth-Best-Effort

```text
Bluetooth verbunden
Drop-Timing: Best Effort
```

Der Nutzer soll die Musik nicht verlieren, nur weil die Genauigkeit nicht garantiert werden kann.

## 20.5 Fehler beim Audio

```text
Wiedergabe konnte nicht fortgesetzt werden.

Die lokale Datei ist nicht mehr verfuegbar oder das Ausgabeformat wird nicht unterstuetzt.

[ Erneut versuchen ] [ Aus Queue entfernen ]
```

---

# 21. Phase 15: Motion Design

## 21.1 Zweck von Animation

Animationen sollen Statuswechsel verstaendlich machen, nicht nur dekorativ sein.

Sinnvolle Animationen:

- Timer-Ring bewegt sich kontinuierlich;
- Waveform-Fortschritt gleitet zwischen Ticks;
- Drop-Status wechselt von Preparing zu Ready;
- Go-Overlay erscheint kurz;
- Marker-Snap gibt eine kleine Haptik und visuelle Bewegung;
- Satz gespeichert zeigt eine kurze Bestaetigung.

## 21.2 Keine kritische Audio-Steuerung aus Compose-Animationen ableiten

Die UI-Animation darf nicht die Audio-Clock sein. Ein `animateFloatAsState`-Fortschritt ist nur visuell. Die Transition wird von Playback-/Timer-Logik gesteuert.

## 21.3 Reduced Motion

Bei reduzierter Bewegung:

- keine 3D-Cover-Rotation;
- keine grosse Skalierung;
- schnelle, einfache Statuswechsel;
- Timer und Waveform bleiben funktional;
- Go-Signal bleibt ueber Text, Haptik und Sound verfuegbar.

**Pseudocode:**

```kotlin
// PSEUDOCODE: keine konkrete Compose-API voraussetzen.
// Im echten Projekt den Wert einmal ueber eine vorhandene, injizierte
// Accessibility-/System-Abstraktion beziehen.
val reducedMotion = accessibilitySettings.reducedMotion
```

---

# 22. Phase 16: Responsive Layout fuer Telefone und Tablets

## 22.1 Compact Phone

```text
TopBar
Workout Console
Rest Console
Session Music Strip
Mini Player / Bottom Navigation
```

Nur eine Spalte. Keine parallelen Detailpanels.

## 22.2 Medium Phone / Foldable

```text
links: Workout Console
rechts oder darunter: Rest / Music Status
```

Die Hauptaktion bleibt in der Daumenregion. Nicht nur wegen mehr Breite alles nebeneinander legen.

## 22.3 Tablet

```text
Navigation Rail | Train Console | Music / Queue Detail
```

Der Tablet-Aufbau darf Waveform und Marker-Details parallel zeigen:

```text
linke Spalte: Track / Waveform
rechte Spalte: Marker Review / Transition Plan
```

## 22.4 WindowSizeClass

Die bestehende `WindowSizeClass`-Logik soll weiter verwendet werden. Keine Abfragen gegen konkrete Geraetemodelle.

**Pseudocode:**

```kotlin
@Composable
fun TrainRoute(windowSizeClass: WindowSizeClass) {
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> CompactTrainLayout()
        else -> ExpandedTrainLayout()
    }
}
```

---

# 23. Phase 17: UI-ViewModels und Events

## 23.1 UI nicht direkt mit vielen Lambdas ueberladen

Bei komplexen Console-Zustaenden kann ein Event-Modell besser lesbar sein:

```kotlin
sealed interface WorkoutConsoleEvent {
    data class WeightChanged(val value: String) : WorkoutConsoleEvent
    data class RepsChanged(val value: String) : WorkoutConsoleEvent
    data class AdjustReps(val delta: Int) : WorkoutConsoleEvent
    data object CompleteSet : WorkoutConsoleEvent
    data object PauseRest : WorkoutConsoleEvent
    data object ResumeRest : WorkoutConsoleEvent
    data object CancelRest : WorkoutConsoleEvent
    data object OpenMusic : WorkoutConsoleEvent
}
```

ViewModel:

```kotlin
fun onEvent(event: WorkoutConsoleEvent) {
    when (event) {
        is WorkoutConsoleEvent.WeightChanged -> updateWeight(event.value)
        is WorkoutConsoleEvent.RepsChanged -> updateReps(event.value)
        is WorkoutConsoleEvent.AdjustReps -> adjustReps(event.delta)
        WorkoutConsoleEvent.CompleteSet -> completeSet()
        WorkoutConsoleEvent.PauseRest -> pauseRest()
        WorkoutConsoleEvent.ResumeRest -> resumeRest()
        WorkoutConsoleEvent.CancelRest -> cancelRest()
        WorkoutConsoleEvent.OpenMusic -> openMusic()
    }
}
```

Die Fachlogik bleibt im ViewModel/Use Case bzw. Domain. Composables rendern nur Zustand und senden Events.

## 23.2 Einmalige Events

Snackbar, Haptik und Navigation sind keine dauerhaften State-Felder, die bei jeder Recomposition erneut ausgefuehrt werden duerfen.

```kotlin
sealed interface WorkoutConsoleEffect {
    data class ShowSnackbar(val message: String, val undo: Boolean) : WorkoutConsoleEffect
    data object HapticSetSaved : WorkoutConsoleEffect
    data object OpenNowPlaying : WorkoutConsoleEffect
    data object PlayGoCue : WorkoutConsoleEffect
}
```

Die UI sammelt Effects in einem kontrollierten `LaunchedEffect`-Block.

---

# 24. Phase 18: Konkrete Datei- und Modulzuordnung

## 24.1 App-Shell

```text
app/src/main/kotlin/com/dropsync/app/DropSyncApp.kt
```

Aufgaben:

- Top-Level-Navigation erweitern oder Verlauf als Unterseite einhaengen;
- Shell-Insets korrekt an Mini-Player und Bottom-Navigation weitergeben;
- Now Playing als separate Route beibehalten;
- keine Playback-Logik in die App-Shell verschieben.

## 24.2 Training

```text
feature/workout/.../WorkoutScreen.kt
feature/workout/.../WorkoutViewModel.kt
feature/timer/.../TimerSection.kt
feature/player/.../DropRestCard.kt
```

Empfehlung:

- `TimerSection` nicht direkt weiter als isolierte Top-Level-Karte rendern;
- neue `RestConsole` im Feature-Player oder einem gemeinsamen UI-nahen Feature-Orchestrator anlegen;
- `WorkoutScreen` bekommt nur Domain-/UI-State und Events;
- keine Media3-Imports in Feature-Modulen.

## 24.3 Player

```text
feature/player/.../NowPlayingScreen.kt
feature/player/.../MiniPlayer.kt
feature/player/.../PlayerViewModel.kt
feature/player/.../Waveform...
```

Aufgaben:

- Marker-Tap und Marker-Sheet;
- Marker Review Preview;
- DropSync Status;
- Mini-Player-Modi;
- Waveform-Interaktion und Accessibility.

## 24.4 Settings

```text
feature/settings/.../SettingsScreen.kt
feature/settings/.../SettingsViewModel.kt
```

Aufgaben:

- Sektionen und progressive Offenlegung;
- technische Audiooptionen gruppieren;
- Marker Review nicht als primaren operativen Arbeitsort verwenden;
- dauerhafte Konfiguration beibehalten.

## 24.5 Designsystem

```text
core/designsystem/.../theme/Theme.kt
core/designsystem/.../theme/Spacing.kt
core/designsystem/.../theme/Type.kt
core/designsystem/.../component/
core/designsystem/.../chart/Waveform.kt
```

Neue Kandidaten:

```text
component/StatusPill.kt
component/ConsoleCard.kt
component/RepStepper.kt
component/TimerHero.kt
component/DropSyncStatus.kt
component/MusicStatusRow.kt
component/MarkerLegend.kt
```

## 24.6 Domain-/Data-Grenze

Neue UI-Modelle duerfen nicht direkt `ExoPlayer`, `MediaItem`, `AudioTrack` oder Room-Entities verwenden.

Falsch:

```kotlin
data class UiState(val player: ExoPlayer)
```

Richtig:

```kotlin
data class MusicUiState(
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val playback: PlaybackUiStatus,
)
```

---

# 25. Phase 19: Teststrategie fuer die UI

## 25.1 Pure State-/Reducer-Tests

Der UI-State-Reducer muss deterministisch pruefen:

```text
Satz fertig -> Rest Running
Rest Ende -> Go Cue
Pause -> Rest Paused
Skip waehrend DropSync -> Manual Override
Route Change waehrend Armed -> Best Effort
Undo nach Satzabschluss -> vorheriger Eingabestatus
```

**Pseudocode:**

```kotlin
@Test
fun `skip waehrend armed drop macht manual override`() {
    val state = initialState.copy(
        dropSync = DropSyncUiStateModel.Armed(
            trackTitle = "Track",
            markerLabel = "Drop 2",
            remainingMs = 20_000L,
            confidence = TimingConfidenceUi.Stable,
        ),
    )

    val next = reduce(state, WorkoutConsoleEvent.UserSkippedTrack)

    assertTrue(next.dropSync is DropSyncUiStateModel.ManualOverride)
}
```

## 25.2 Compose UI-Tests

Testfaelle:

- `Satz fertig` ist sichtbar und enabled, wenn Gewicht/Reps gueltig;
- `Satz fertig` ist disabled bei leerem Gewicht;
- `+/-` veraendert Reps;
- Sensorstatus wird als Text angezeigt;
- Drop Ready erscheint bei armed State;
- Manual Override ersetzt Drop Ready;
- `+15 s` erhoeht den Timer;
- Accessibility Node enthaelt Timerstatus;
- Waveform Slider-Fallback ist erreichbar;
- Marker-Menue enthaelt `Als DropSync-Ziel verwenden`;
- Mini-Player zeigt bei DropSync nicht den normalen Skip-Button als Hauptaktion.

**Pseudocode:**

```kotlin
@get:Rule
val composeRule = createComposeRule()

@Test
fun `drop ready zeigt verständlichen status`() {
    composeRule.setContent {
        WorkoutConsole(
            state = fixtureState.copy(
                dropSync = DropSyncUiStateModel.Armed(
                    trackTitle = "Heavy Track",
                    markerLabel = "Drop 2",
                    remainingMs = 87_000L,
                    confidence = TimingConfidenceUi.Stable,
                ),
            ),
            onWeightChange = {},
            onRepsChange = {},
            onRepAdjustment = {},
            onCompleteSet = {},
            onPauseRest = {},
            onResumeRest = {},
            onCancelRest = {},
            onOpenMusic = {},
        )
    }

    composeRule
        .onNodeWithText("Drop bereit")
        .assertIsDisplayed()
}
```

## 25.3 Screenshot-/Golden-Tests

Mindestens diese Zustandsbilder sollten festgehalten werden:

```text
Train / Empty
Train / Set Entry
Train / Sensor Connected
Train / Rest Normal
Train / Drop Preparing
Train / Drop Ready
Train / Best Effort
Train / Go Cue
Music / Work Playlist
Music / Marker Review
Now Playing / Marker Sheet
Settings / collapsed sections
```

Die Screenshots sollen auf mindestens zwei Breiten getestet werden:

```text
Compact Phone
Expanded / Tablet
```

## 25.4 Accessibility Tests

Pruefen:

- keine interaktiven Ziele unter 48 dp;
- Content Descriptions bei Icons;
- Timerstatus lesbar;
- Farbe allein traegt keine Information;
- grosse Schrift ueberlauft keine primaeren Buttons;
- Slider-Fallback fuer Waveform vorhanden;
- TalkBack wird nicht jede Sekunde mit einer neuen Ansage geflutet.

## 25.5 UI-Performance

Pruefen:

- Waveform-Scrubbing bleibt fluessig;
- keine neue Analyse bei Recomposition;
- keine Player-Aktion bei jedem Progress-Frame;
- Marker-Liste verwendet stabile Keys;
- lange Listen sind `LazyColumn` und nicht `Column` mit kompletter Materialisierung;
- Cover-/Waveform-UI erzeugt keine unbounded Bitmap-Allokationen.

---

# 26. Phase 20: Migrationsreihenfolge

Die folgende Reihenfolge soll exakt eingehalten werden.

## Schritt 1: UI-Zustaende dokumentieren

Anlegen oder erweitern:

```text
feature/workout/.../WorkoutConsoleUiState.kt
feature/player/.../DropSyncUiStateModel.kt
```

Noch keine grosse visuelle Aenderung. Zuerst Zustandsnamen und Events vereinheitlichen.

Abnahme:

- alle vorhandenen Timer-/Drop-/Player-Zustaende abgebildet;
- keine Boolean-Kombination mit unmoeglichen Zustaenden;
- Tests fuer Reducer-Grundfaelle.

## Schritt 2: Designsystem-Komponenten

Anlegen:

```text
core/designsystem/.../component/StatusPill.kt
core/designsystem/.../component/TimerHero.kt
core/designsystem/.../component/RepStepper.kt
core/designsystem/.../component/MusicStatusRow.kt
```

Abnahme:

- Preview oder Compose-Test;
- Light/Dark vorhanden;
- Touch-Ziele korrekt;
- Semantics gesetzt.

## Schritt 3: Set Entry umbauen

Datei:

```text
feature/workout/.../WorkoutScreen.kt
```

Aenderungen:

- Rep-Stepper;
- groessere Satz-fertig-Aktion;
- Sensor-/Manuellstatus;
- Optionen in Overflow;
- Undo-Verhalten erhalten.

Abnahme:

- Satz kann weiterhin gespeichert werden;
- bestehende Workout-Tests bleiben gruen;
- UI-Test fuer +/- und Satz fertig.

## Schritt 4: RestConsole einfuehren

Neue Komponente:

```text
feature/player/.../RestConsole.kt
```

Zuerst als visuelle Komposition aus vorhandenen Timer-/Drop-States. Fachlogik nicht duplizieren.

Abnahme:

- Normal Rest;
- DropSync Preparing;
- DropSync Ready;
- Pause/Resume;
- Cancel;
- Completed;
- Manual Override.

## Schritt 5: Train-Shell umstellen

Datei:

```text
app/.../DropSyncApp.kt
```

`TimerSection` und `DropRestCard` nicht beide als konkurrierende Top-Level-Karten rendern. Entweder:

```text
RestConsole ersetzt beide
```

oder:

```text
TimerSection bleibt als Subkomponente in RestConsole
DropRestCard wird zu DropSyncStatusBlock
```

Abnahme:

- keine doppelte Timeranzeige;
- keine doppelte Abbrechen-Aktion;
- Insets stimmen mit Mini-Player/Navigation.

## Schritt 6: Mini-Player kontextabhaengig machen

Datei:

```text
feature/player/.../MiniPlayer.kt
```

Abnahme:

- normaler Player unveraendert bedienbar;
- aktiver DropSync-Plan sichtbar;
- Skip bricht Plan logisch ab;
- Status wird verstaendlich angezeigt.

## Schritt 7: Music Home und Playlist-Status

Betroffene Library-Dateien:

```text
feature/library/...
```

Abnahme:

- Work-/Rest-Label sichtbar;
- Markerabdeckung sichtbar;
- Analyse ausstehend sichtbar;
- Playlist kann aus Music als DropSync-Quelle verwendet werden.

## Schritt 8: Marker Review verschieben/duplizieren

Die bestehende Settings-Funktion bleibt als Einstieg erhalten, aber Music bekommt den operativen Review-Einstieg.

Abnahme:

- Kandidat anhoeren;
- bestaetigen;
- verwerfen;
- Position oeffnen;
- DropSync-Ziel setzen.

## Schritt 9: Now Playing Marker Sheet

Datei:

```text
feature/player/.../NowPlayingScreen.kt
```

Abnahme:

- Marker-Tap;
- Feinjustierung;
- Preview;
- Marker-Quelle erkennbar;
- Snap optional;
- Accessibility-Alternative.

## Schritt 10: Settings gruppieren

Datei:

```text
feature/settings/.../SettingsScreen.kt
```

Abnahme:

- keine Funktion verloren;
- Audio Experten kollabiert;
- Marker Review erreichbar;
- Warnungen verstaendlich;
- Einstellungen bleiben offline.

## Schritt 11: Responsive und Accessibility

Abnahme:

- Compact Phone;
- Tablet/Expanded;
- grosse Schrift;
- TalkBack;
- reduzierte Bewegung;
- Touch-Ziele.

## Schritt 12: Visueller Finaltest

Nur nach funktionalen Tests:

- Lime-Anteil pruefen;
- Textkontrast pruefen;
- Kartenabstaende angleichen;
- Animationen reduzieren, falls sie Aufmerksamkeit stehlen;
- keine neuen konkurrierenden primaeren Buttons einfuehren.

---

# 27. Detaillierte Akzeptanzkriterien

## 27.1 Aktiver Satz

- Der Nutzer erkennt innerhalb eines kurzen Blicks die Uebung.
- Gewicht und Reps sind groesser als Sekundaerinformationen.
- Reps koennen ohne Tastatur um eins erhoeht oder reduziert werden.
- Auto-/Manuellstatus ist als Text erkennbar.
- `Satz fertig` ist die dominante Aktion.
- Nach Abschluss erscheint Undo.
- Kein DropSync-Status wird als technischer Debug-Text dargestellt.

## 27.2 Rest-Timer

- Restzeit bleibt im aktiven Restzustand groesstes UI-Element.
- Pause/Resume und Abbrechen sind immer auffindbar.
- +15 Sekunden ist ohne Settings erreichbar.
- Normal Rest und DropSync unterscheiden sich klar.
- Drop Ready zeigt Track und Marker.
- Best Effort zeigt einen Grund.
- Manuelle Eingriffe werden als Override angezeigt.

## 27.3 DropSync

- Ein Nutzer versteht, ob DropSync aus, in Vorbereitung oder bereit ist.
- Der Nutzer sieht, welcher Track und welcher Marker verwendet werden.
- Ein aktiver Plan kann bewusst abgebrochen werden.
- Skip/Pause/Seek erzeugt keinen versteckten alten Plan.
- Bluetooth-Unsicherheit wird ehrlich beschrieben.
- Go ist als eigener kurzer UI-Zustand wahrnehmbar.

## 27.4 Musik

- Work und Rest sind ohne langes Suchen erreichbar.
- Playlist zeigt Drop-Abdeckung.
- Marker Review ist direkt aus Music erreichbar.
- Now Playing ist nicht nur ein Cover-Screen, sondern ein Zeitachsen-Editor.
- Waveform kann per Tap und Drag bedient werden.
- Slider-Fallback ist vorhanden.

## 27.5 Settings

- Anfaenger sehen keine lange unstrukturierte DSP-Liste.
- Experten erreichen DSP in maximal zwei Taps.
- Warnungen erklaeren Konsequenzen.
- Analyzer-/Marker-Optionen sind von Marker-Ergebnissen getrennt.
- Es gibt keine Online-Anforderung.

## 27.6 Accessibility

- Touch-Ziele mindestens 48 dp.
- Farbe ist nie alleinige Bedeutung.
- Timerstatus ist per Semantics lesbar.
- Waveform besitzt eine alternative Bedienung.
- grosse Schrift ueberlaeuft keine Primaeraktion.
- reduzierte Bewegung wird respektiert.

---

# 28. Typische Fehlentscheidungen vermeiden

## Fehler 1: Alles auf eine riesige Startseite legen

Mehr Funktionen sichtbar zu machen bedeutet nicht, alles gleichzeitig zu zeigen. Der Train-Screen braucht den aktuellen Zustand, nicht die komplette Produktnavigation.

## Fehler 2: Zwei grosse Zahlen nebeneinander

Timer und Reps duerfen nicht gleichzeitig gleich gross sein. Der Zustand entscheidet, was heroisch ist.

## Fehler 3: Jede Funktion bekommt Lime

Wenn Timer, Waveform, Titel, Marker, Buttons und alle Chips Lime sind, ist nichts mehr primaer.

## Fehler 4: Technische Genauigkeit als UI-Marketing

Nicht `sample exact`, wenn Android-Ausgabe und Bluetooth das nicht garantieren. `Drop bereit` und `Best Effort` sind ehrlicher.

## Fehler 5: Marker Review in Settings lassen

Settings ist gut fuer Regeln, schlecht fuer eine wiederholte Review-Aufgabe mit Audio-Preview.

## Fehler 6: Skip direkt neben Drop-Plan zeigen

Ein Skip ist eine normale Playeraktion, aber waehrend DropSync eine bewusste Planunterbrechung. Das UI muss diesen Unterschied zeigen.

## Fehler 7: Gesten-only

Long-Press und Scrubbing sind gut, aber jeder wichtige Vorgang braucht eine sichtbare Alternative.

## Fehler 8: UI-Animation als Timingquelle

Compose rendert den Status. Die Audio-Engine und die Timerlogik planen die Zeit.

## Fehler 9: Ein weiteres Feature-Modul nur fuer UI anlegen

Zuerst bestehende Modulgrenzen nutzen. `:feature:player`, `:feature:workout`, `:feature:library` und `:feature:settings` sind bereits vorhanden.

## Fehler 10: Settings durch neue Optionen vergroessern

Neue Funktionen muessen gruppiert, progressiv offengelegt und im laufenden Workflow erreichbar gemacht werden.

---

# 29. Beispiel fuer den Ziel-Workflow

## 29.1 Uebung waehlen und Satz eingeben

```text
Train oeffnen
    -> Uebung waehlen (Chip oben, Sheet mit eigenen Uebungen)
    -> Gewicht eintragen
    -> Reps manuell oder automatisch
```

## 29.2 Satz speichern

```text
Satz fertig
    -> kurze Haptik
    -> Satz gespeichert
    -> Undo-Snackbar
    -> RestConsole wird gross
```

## 29.3 DropSync vorbereiten

```text
RestConsole
    -> DropSync ist fuer Uebung aktiviert
    -> Work-Kandidat wird ausgewaehlt
    -> Zielmarker wird angezeigt
    -> Track wird vorbereitet
    -> Status wird DROP BEREIT
```

## 29.4 Rest laeuft

```text
01:27
Drop 2 trifft bei 00:00
[Pause] [+15 s] [Abbrechen]
```

## 29.5 Nutzer skippt

```text
Skip
    -> Transition wird abgebrochen
    -> Status MANUELL UEBERNOMMEN
    -> Musik laeuft normal weiter
    -> Snackbar erklaert den Grund
```

## 29.6 Rest endet

```text
Go Overlay
    -> lokale Go-Cue
    -> Haptik
    -> Drop Status
    -> Satz-Eingabe fuer naechsten Satz
```

## 29.7 Marker korrigieren

```text
Music
    -> Track oeffnen
    -> Waveform scrubben
    -> Marker antippen
    -> Feinjustierung
    -> Als DropSync-Ziel verwenden
```

---

# 30. Definition of Done

## Informationsarchitektur

- [ ] Hauptnavigation ist auf Train, Music, Verlauf und Settings vorbereitet.
- [ ] Bei Drei-Tab-Betrieb ist Verlauf in maximal zwei Taps erreichbar.
- [ ] Music besitzt Work-/Rest-Playlisten als sichtbare Einstiege.
- [ ] Marker Review ist im Music-Kontext erreichbar.

## Train Console

- [ ] Aktiver Satz und Rest-Timer konkurrieren nicht um dieselbe visuelle Prioritaet.
- [ ] Gewicht und Reps sind im Schnellmodus gut sichtbar.
- [ ] Reps koennen per +/- korrigiert werden.
- [ ] Auto-/Manuellstatus ist verstaendlich.
- [ ] Satz fertig ist die eindeutige Primaeraktion.
- [ ] Undo bleibt verfuegbar.

## Rest und DropSync

- [ ] `RestConsole` ersetzt die konkurrierende getrennte Timer-/Drop-Karten-Hierarchie.
- [ ] Normal, Preparing, Ready, Best Effort, Manual Override und Completed sind sichtbar unterscheidbar.
- [ ] +15 Sekunden ist direkt erreichbar.
- [ ] Track und Marker werden bei Drop Ready angezeigt.
- [ ] Nutzeraktionen brechen den alten Plan nachvollziehbar ab.
- [ ] Go wird als eigener kurzer Zustand gezeigt.

## Mini-Player

- [ ] Normaler Mini-Player hat Play/Pause, Skip und Queue.
- [ ] Aktiver DropSync-Plan zeigt Status statt gleichwertigem Skip.
- [ ] Skip waehrend DropSync erzeugt Manual Override.
- [ ] Keine Audio-/Transition-Logik wird in die Composable verschoben.

## Now Playing und Waveform

- [ ] Waveform bleibt Haupt-Seekflaeche.
- [ ] Slider-Fallback bleibt vorhanden.
- [ ] Drag seeked erst beim Loslassen.
- [ ] Marker koennen direkt angetippt werden.
- [ ] Marker koennen feinjustiert werden.
- [ ] Markerquelle und Bestaetigungsstatus sind erkennbar.
- [ ] Marker kann direkt als DropSync-Ziel gewaehlt werden.
- [ ] Waveform Canvas erzeugt keine Analyse- oder DB-Aufrufe.

## Settings

- [ ] Training, Musik, DropSync und Audio Experten sind getrennt.
- [ ] Technische Audiooptionen sind progressiv offengelegt.
- [ ] Bit-Perfect- und Bluetooth-Grenzen sind verstaendlich formuliert.
- [ ] Marker Review ist nicht nur in Settings versteckt.

## Accessibility

- [ ] Alle primaeren Touch-Ziele sind mindestens 48 dp.
- [ ] Alle Icons haben sinnvolle Content Descriptions.
- [ ] Timerstatus ist als Semantics vorhanden.
- [ ] Farbe ist niemals alleinige Statusinformation.
- [ ] Waveform hat eine nicht-gestische Alternative.
- [ ] Grosse Schrift wurde auf Compact und Expanded getestet.
- [ ] Reduced Motion wird beruecksichtigt.

## Qualitaet

- [ ] Bestehende Workout-, Player- und Marker-Tests bleiben gruen.
- [ ] Neue Reducer-Tests decken Planabbruch und Zustandswechsel ab.
- [ ] Compose UI-Tests decken die wichtigsten Zustaende ab.
- [ ] Architekturtest bleibt gruen.
- [ ] `spotlessCheck` bleibt gruen.
- [ ] Debug-APK baut.
- [ ] UI wurde mit echter einhaendiger Bedienung auf einem Telefon getestet.

---

# 31. Kurzfassung fuer eine schwache Coding-KI

Wenn die Umsetzung in kleinen Schritten erfolgen muss, gelten diese Regeln:

1. Nicht zuerst Farben aendern. Zuerst die Zustandsstruktur aendern.
2. Train ist die Workout-Konsole.
3. Aktiver Satz und Rest-Timer sind zwei verschiedene Hero-Zustaende.
4. Timer und DropSync muessen als eine RestConsole erscheinen.
5. Reps bekommen sichtbare +/--Buttons.
6. Auto und manuell werden als eine Rep-Zahl mit Quellenlabel dargestellt.
7. `Satz fertig` ist die wichtigste Aktion im aktiven Satz.
8. `+15 s` ist direkt im laufenden Rest erreichbar.
9. Drop Ready zeigt Track, Marker und verständlichen Status.
10. Bluetooth-Unsicherheit wird als Best Effort gezeigt.
11. Skip waehrend DropSync zeigt Manual Override und beendet den alten Plan.
12. Der Mini-Player veraendert seinen Modus, wenn DropSync aktiv ist.
13. Work-/Rest-Playlisten werden direkt in Music sichtbar.
14. Marker Review gehoert in Music, nicht nur in Settings.
15. Now Playing ist Waveform- und Marker-Editor.
16. Marker koennen angetippt, angehoert, justiert und als DropSync-Ziel gewaehlt werden.
17. Scrubbing seeked erst beim Loslassen.
18. Waveform-Rendering darf niemals MediaCodec oder Room starten.
19. Settings werden nach Aufgabe gruppiert.
20. Audio-Expertenoptionen bleiben hinter einer Advanced-Gruppe.
21. Lime markiert nur wichtige aktive Zustaende.
22. Jede Farbe bekommt zusaetzlich Text oder Icon.
23. Jede Geste bekommt eine sichtbare Alternative.
24. Keine Audio-Clock aus Compose-Animationen ableiten.
25. Nach jeder Phase: Formatierung, Unit-Tests, Compose-Tests, Architekturtest und Debug-Build.

Das Ziel ist keine dekorative Neugestaltung. Das Ziel ist eine UI, die beim Training unter Bewegung, Zeitdruck und eingeschraenkter Aufmerksamkeit funktioniert und gleichzeitig die leistungsfaehigen Audio-/DropSync-Funktionen sichtbar, ehrlich und kontrollierbar macht.

---

# 32. Verbindliche Entscheidungen vor Umsetzung

Dieser Abschnitt loest die verbleibenden Architektur- und Implementierungsfragen. Eine Coding-KI darf diese Punkte nicht stillschweigend anders entscheiden.

## 32.1 Navigation hat vier Tabs

Die Produktentscheidung des Nutzers lautet verbindlich:

```text
TRAIN | MUSIC | VERLAUF | EINSTELLUNGEN
```

Das ist die v1-Struktur. `HISTORY` (Verlauf) ist ein eigener Top-Level-Tab, kein Unterpunkt von Train. Die bisherige Drei-Tab-Vorgabe (`MUSIC | TRAINING | SETTINGS`) ist damit **ersetzt**. Die Navigation enthaelt vier feste Tabs unten, Train ist der Start-Tab.

Konkret:

- `DropSyncApp.kt` hat vier Top-Level-Destinationen: TRAIN, MUSIC, HISTORY, SETTINGS.
- `HISTORY` zeigt die flache Satz-Liste gruppiert nach Tag, Tagesvolumen, Volumen-Linie und PR-Abzeichen.
- Es gibt **keine Sessions** (Entscheidung des Nutzers): keine Session-Start/Ende-Klammer, kein Session-Status, keine Routinen. Die UI zeigt Sätze als flache Liste mit Zeitstempel + Übung + Gewicht + Reps.
- Die `WorkoutConsoleUiState`-Modi `NO_SESSION`, `SESSION_READY`, `SET_ENTRY`, `SESSION_COMPLETE` werden entsprechend vereinfacht: Es gibt keinen `SESSION_READY`-Startzustand, keinen Session-Abschluss. Stattdessen: Übung wählen -> Satz eingeben -> Satz fertig -> Rest. Nach `Übung abschließen` kehrt die Eingabe zur Übungswahl zurück.

## 32.2 RestConsole darf keine Feature-Abhaengigkeit erzeugen

`feature:workout` darf nicht von `feature:player` abhaengen und umgekehrt. Fuer v1 gilt:

```text
:app
  komponiert den fachuebergreifenden RestConsole-Zustand

:feature:workout
  liefert Workout-/Session-UI und Events

:feature:player
  liefert Now Playing, Mini-Player und Musik-UI

:core:designsystem
  liefert nur fachlich neutrale TimerHero-, StatusPill-,
  MusicStatusRow- und RepStepper-Komponenten
```

Die Komposition kann in `app/.../RestConsoleRoute.kt` liegen. Dort darf keine neue Timer-, Marker- oder Playback-Fachlogik entstehen. Wenn eine Komponente nach `:core:designsystem` verschoben wird, darf sie keine Repositories, ViewModels, Room-Entities oder Feature-Modelle importieren.

## 32.3 Pseudocode-Typen zuerst anlegen

Typen wie `SessionMusicUiState`, `TimingConfidenceUi`, `GoDropStatus`, `MiniPlayerUiState`, `MusicStatusRow` und neue `BrandIcons` sind Beispiele und nicht automatisch vorhanden. Vor ihrer Verwendung muss die KI Existenz oder Zielpfad pruefen. Empfohlene Pfade:

```text
feature/workout/.../WorkoutConsoleUiState.kt
feature/workout/.../WorkoutConsoleEvent.kt
feature/player/.../SessionMusicUiState.kt
feature/player/.../DropSyncUiStateModel.kt
feature/player/.../MiniPlayerUiState.kt
app/.../RestConsoleRoute.kt
core/designsystem/.../component/TimerHero.kt
core/designsystem/.../component/RepStepper.kt
core/designsystem/.../component/StatusPill.kt
```

Bei einem neuen Icon gilt: Drawable anlegen, `BrandIcons` erweitern, Content Description ergaenzen, dann verwenden.

## 32.4 Verbindliches Playback-Override-Event

Der aktive Drop-Plan muss auf Nutzeraktionen reagieren. Verwende keinen undefinierten Testnamen, sondern einen expliziten Vertrag:

```kotlin
sealed interface PlaybackOverrideEvent {
    data object UserPaused : PlaybackOverrideEvent
    data object UserResumed : PlaybackOverrideEvent
    data object UserSeeked : PlaybackOverrideEvent
    data object UserSkipped : PlaybackOverrideEvent
    data object UserChangedQueue : PlaybackOverrideEvent
}
```

Die Quelle liegt in `PlaybackRepository`/`PlaybackService` oder einer bestehenden Playback-State-Abstraktion. Ein Feature erzeugt keinen eigenen ExoPlayer-Listener. `UserSkipped`, `UserSeeked` und Queue-Aenderungen setzen den DropSync-Status auf `MANUAL_OVERRIDE`; ein alter Plan darf danach nicht mehr ausfuehren.

## 32.5 Accessibility- und Reduced-Motion-API nicht erfinden

`LocalAccessibilityManager.current` ist im Projekt nicht als sichere Standard-API vorausgesetzt. Vor der Implementierung muss eine reale vorhandene Accessibility-/Settings-Abstraktion wiederverwendet oder eine passende Abstraktion eingefuehrt werden. Bis dahin nur kurze, robuste Animationen verwenden. Kernfunktionen duerfen nicht von Reduced Motion abhaengen.

## 32.6 Doppeltaps und destruktive Aktionen

Alle mutierenden Aktionen muessen auf UI- und ViewModel-/Use-Case-Ebene geschuetzt sein:

```text
Satz fertig        waehrend Speicherung deaktivieren/idempotent machen
Rest abbrechen     zweiter Tap = No-op
Session verwerfen  bestaetigen, wenn Daten verloren gehen
Marker loeschen    Undo oder Bestaetigung
DropSync abbrechen Status bestaetigen und alten Plan invalidieren
Skip              alten Plan zuerst logisch invalidieren
```

Beispiel:

```kotlin
if (uiState.isSavingSet) return
uiState = uiState.copy(isSavingSet = true)
try {
    repository.completeSet(...)
} finally {
    uiState = uiState.copy(isSavingSet = false)
}
```

Zusaetzlich gelten Bounds: Gewicht nicht negativ, Reps im erlaubten Bereich, Restdauer im erlaubten Bereich, Markerposition zwischen null und Songdauer.

## 32.7 Playback-Zustaende auf UI-Zustaende abbilden

| Technischer Befund | UI-Zustand | Nutzertext |
|---|---|---|
| kein Plan | OFF | kein Hinweis |
| Plan wird berechnet | PLANNING | Drop wird berechnet |
| Ziel wird vorbereitet | PREPARING | Track wird vorbereitet |
| Ziel bereit | READY/ARMED | Drop bereit |
| Start angefordert | GO | GO / Audio wird gestartet |
| Start plausibel bestaetigt | LANDED | Drop gelandet |
| Route/Buffer/Clock unsicher | BEST_EFFORT | Timing nicht garantiert |
| Play/Pause/Seek/Skip durch Nutzer | MANUAL_OVERRIDE | Manuell uebernommen |
| Nutzer bricht ab | CANCELLED | DropSync abgebrochen |
| Ziel fehlt oder Wiedergabe scheitert | FAILED | konkrete Recovery-Aktion |

Die UI darf den Status nicht nur aus `player.isPlaying` ableiten. Ein Player kann laufen, waehrend ein Drop-Plan fehlgeschlagen oder manuell abgebrochen ist.

## 32.8 Offline-Recovery ist ein eigener UI-Vertrag

Folgende Situationen brauchen immer eine Aktion und keinen endlosen Spinner:

```text
SAF-Datei nicht lesbar
  -> Datei erneut auswaehlen / Abbrechen

Lokale Songdatei fehlt
  -> Bibliothek scannen / aus Queue entfernen

Format nicht analysierbar
  -> Wiedergabe unabhaengig versuchen / Details

Analyse abgebrochen
  -> Analyse fortsetzen; alte Waveform sichtbar lassen

Analyzer-/Cache-Migration
  -> Analyse aktualisieren; vorhandene Waveform als Vorschau zeigen

Drop-Ziel fehlt
  -> anderen Marker waehlen / DropSync fuer diese Pause aus
```

Offline ist Normalbetrieb; diese Zustaende sind lokale Fehler und duerfen keine Netzwerkaktion suggerieren.

## 32.9 Go-Overlay und TalkBack

Das Go-Overlay wird nicht als neue Navigation auf den Backstack gelegt. Es erscheint fuer eine festgelegte kurze Dauer, kehrt danach automatisch zur Satzansicht zurueck und besitzt zusaetzlich eine sichtbare benannte Fortfahren-/Schliessen-Aktion. Ein beliebiger Tap auf die gesamte Flaeche darf bei TalkBack nicht die einzige Schliessmoeglichkeit sein.

`GO` wird als fokussierbare Live-Region einmal vorgelesen; nicht jede Animationsframe und nicht jede Sekunde des Countdowns. Reduced Motion entfernt Rotation und starke Skalierung, nicht die Text-/Haptik-/Audioinformation.

## 32.10 Review vor jedem UI-Commit

```text
Gilt in diesem Commit die Drei-Tab-Struktur noch?
Verletzt eine Datei die Feature-Modulgrenze?
Sind alle Beispieltypen und Icons angelegt?
Sind Mutation und Doppeltap-Schutz vorhanden?
Haben destruktive Aktionen Undo oder Bestaetigung?
Sind Audio-/Drop-Status ohne technische Begriffe verstaendlich?
Hat jeder Offline-/Analysefehler eine Recovery-Aktion?
Ist GO mit TalkBack bedienbar?
Bleibt Waveform ohne Netz und ohne fertige Analyse nutzbar?
```

Erst wenn diese Fragen beantwortet sind, wird der visuelle Feinschliff abgeschlossen.

---

# 33. Doku-Zusammenhang

Dieses Handbuch arbeitet mit dem Design-Dokument und dem Audio-Handbuch zusammen:

| Dokument | Ort | Rolle |
|---|---|---|
| `FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` | `docs/design/` (flowrep-main und DropSync) | Oberste Referenz, Produktentscheidungen |
| `FLOWREP_DROPSYNC_FUSION_FRAGEN_ANTWORTEN_2026-08-07.md` | `docs/design/` | Alle Entscheidungen im Detail |
| `WISSEN_POWERAMP_OFFTRACK_2026-08-07.md` | `docs/design/` | Konzepte aus oeffentlicher Doku |
| `OFFTRACK_AUDIO_UMBAUHANDBUCH.md` | Repo-Wurzel | Audio-Ausarbeitung (Transitionen, Clock, Waveform, Analyse) |

**Regel:** Wenn dieses UI-Handbuch und das Audio-Handbuch oder das Design-Dokument widersprechen, gewinnt das Design-Dokument. Produktentscheidungen des Nutzers (4 Tabs, keine Sessions, Piep-Toene, Marker Review in Music, ReplayGain als Einstellung) haben immer Vorrang.
