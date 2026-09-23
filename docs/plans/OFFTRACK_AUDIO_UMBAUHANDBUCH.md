# DropSync Audio-Ausbau: Offtrack-artige Wiedergabe, Waveform, Gapless, DropSync und Uebergaenge

> **Beispiel-Hinweis:** Kotlin-Bloecke mit der Kennzeichnung **Pseudocode** sind bewusst ein Architekturgeruest. Sie zeigen die benoetigte Form, sind aber nicht automatisch kompilierfertig. Vor dem Einfuegen muessen bestehende Paketnamen, `Clock`, `DispatcherProvider`, Imports, `AppResult` und Test-Fakes des Repositories verwendet werden.

> **Verbindlicher Rahmen:** Dieses Handbuch setzt das Design-Dokument `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` (flowrep-main) um. Alle dort getroffenen Entscheidungen (4 Tabs, keine Sessions, ReplayGain als Option, Piep-Töne, Marker Review in Music) haben Vorrang. Dieses Handbuch ist die technische Ausarbeitung des Abschnitts "Musik Workout Kopplung" und "Analyse-Pipeline".

**Dokumenttyp:** Umsetzungs- und Migrationshandbuch
**Zielgruppe:** Entwickler oder Coding-KI ohne tiefes Vorwissen ueber dieses Repository
**Stand:** 2026-08-07
**Geltungsbereich:** `DropSync-Timer-main`
**Wichtig:** Dieses Dokument beschreibt eine eigenstaendige, beobachtete Architektur. Es kopiert keinen proprietaeren Offtrack-Code und behauptet keine Kenntnis nicht beobachtbarer nativer Implementierungsdetails.

---

## 0. Lies diesen Abschnitt zuerst

### 0.1 Was am Ende funktionieren soll

Die App soll lokal gespeicherte Musik so abspielen koennen:

1. Ein Titel laeuft ueber einen dauerhaften Playback-Service.
2. Der naechste Titel wird fruehzeitig vorbereitet, ohne bereits hoerbar zu starten.
3. Normale Titelwechsel koennen gapless oder mit einem kontrollierten Crossfade erfolgen.
4. Eine Waveform wird einmal analysiert, im Room-Cache gespeichert und in Compose ohne erneutes Decodieren gezeichnet.
5. Nutzer koennen Drop-Marker manuell setzen.
6. Eine automatische Analyse darf nur Vorschlaege liefern; ein Vorschlag wird erst nach Nutzerbestaetigung fuer DropSync verwendet.
7. Beim Start einer Restpause berechnet die App:

   ```text
   WorkStart = RestEnde - DropPosition
   ```

8. Das Ziel wird vor dem Restende vorbereitet. Zum geplanten Moment wird nur noch die bereits vorbereitete Wiedergabe aktiviert.
9. Ein manueller Skip, Pause, Seek, Audio-Routenwechsel oder Buffer-Underrun kann den Plan abbrechen oder auf `BEST_EFFORT` herabstufen.
10. Die UI zeigt keine falsche Sample-Genauigkeit an. Bluetooth und Android-Hardware-Puffer erlauben keine universelle Garantie auf den Millisekunden.

### 0.2 Der wichtigste Architekturgrundsatz

Der aktuelle Code hat bereits eine gute Huelle. Nicht die Compose-UI und nicht die komplette Datenbank muessen neu gebaut werden. Der Umbau betrifft vor allem die Ausfuehrungsschicht zwischen Timer, PlaybackService und Audioausgabe.

Aktueller vereinfachter Pfad:

```text
RestMusicCoordinator
    -> delay(...)
    -> PlaybackRepository.crossfadeTo(song, position)
    -> MediaController Custom Command
    -> PlaybackService
    -> CrossfadeController
    -> secondary.prepare()/play()
    -> Lautstaerke-Rampen
    -> mainPlayer.setMediaItem(...)/seekTo(...)
```

Zielpfad:

```text
TimerEngine
    -> DropLandingPlanner
    -> TransitionPlan
    -> PlaybackRepository.prepareTransition(plan)
    -> PlaybackService / TransitionManager
    -> Ziel-Decoder frueh vorbereiten
    -> AudioClock beobachten
    -> Transition armed
    -> genau ein Execute-Ereignis
    -> Crossfade oder Drop-Start
    -> bestaetigtes Resultat / Fallback / Abbruch
```

Der Unterschied ist entscheidend: `crossfadeTo()` ist heute ein einzelner Befehl. Offtrack-artiges Verhalten braucht einen Lebenszyklus aus **Planen, Vorbereiten, Scharfstellen, Ausfuehren, Beobachten und Abbrechen**.

### 0.3 Was nicht versprochen werden darf

Folgende Aussagen sind auf Android ohne kontrollierten nativen Audiostack falsch oder zumindest nicht garantiert:

- "Der Drop landet auf jedem Bluetooth-Geraet sample-genau."
- "`ExoPlayer.currentPosition` ist immer die hoerbare Position."
- "`delay(1000)` startet den Ton exakt nach 1000 ms."
- "Zwei ExoPlayer werden nach einem Handoff automatisch zu einem gapless Player."
- "Ein `AudioTrack`-Timestamp beschreibt bei jedem Bluetooth-Codec die physische Lautsprecherposition."
- "WorkManager startet eine Aufgabe exakt zum Timer-Ende."

Die App soll stattdessen eine Qualitaet und einen Status anzeigen koennen:

```text
EXACT_BEST_EFFORT  // lokale Ausgabe, vorbereiteter Decoder, stabile Clock
ROUTE_CALIBRATED   // Ausgabeweg wurde fuer dieses Geraet empirisch vermessen
BEST_EFFORT        // Bluetooth, Rebuffer, Routenwechsel oder unbekannte Latenz
ABORTED            // Nutzer oder System hat den Plan unterbrochen
```

---

# 1. Ist-Zustand des Repositories

## 1.1 Relevante Module

| Modul | Aufgabe | Darf kennen |
|---|---|---|
| `:domain:audio` | Audio-Modelle, Kurven, Analyzer-Vertraege | Kotlin/JVM, keine Android-/Media3-Typen |
| `:domain:playback` | Playback-Vertrag fuer Features | Domain-Modelle |
| `:domain:timer` | Timer und reine Drop-Landungs-Mathematik | Kotlin/JVM |
| `:data:audio` | MediaExtractor, MediaCodec, DSP, Audio-Route | Android, Media3, Room ueber Schnittstellen |
| `:data:playback` | MediaController, PlaybackService, ExoPlayer, Crossfade | Android, Media3 |
| `:data:timer` | Timer-Datenfluss und Request-Bus | Android/DataStore je nach aktuellem Code |
| `:core:database` | Room-Entities, DAOs, Migrationen | Room |
| `:feature:player` | Player-UI und `RestMusicCoordinator` | Domain-Schnittstellen, Compose |
| `:feature:workout` | Training und Rest-Timer-Start | Domain-Schnittstellen |
| `:feature:settings` | Audio-/Rest-/Mix-Einstellungen | Domain-Schnittstellen |
| `:core:designsystem` | Waveform-Canvas und UI-Komponenten | Compose |

Die bestehende Dependency-Regel aus `core/testing/.../ModuleDependencyRulesTest.kt` darf nicht aufgeweicht werden. Insbesondere darf `:domain:*` keine Media3- oder Android-Klassen importieren und `:feature:*` darf keinen ExoPlayer direkt erzeugen.

**Architekturentscheidung vor Phase 3:** Die Repo-Dokumentation fordert an mehreren Stellen 'genau einen Player und eine MediaSession'. Der aktuelle Service besitzt zusaetzlich bereits einen service-eigenen sekundaeren ExoPlayer fuer Crossfade. Vor einer Verstaetigung dieses Musters muss deshalb eine ADR-Ergaenzung beschlossen werden: **ein MediaSession-/Playback-State-fuehrender Hauptspieler plus optionaler, ausschliesslich service-eigener Transition-Player**. Der Transition-Player darf nie AudioFocus, MediaSession oder Feature-Zustand selbst besitzen. Wenn diese ADR nicht akzeptiert wird, muss statt Dual-Player ein einziger Timeline-/PCM-Mixer-Pfad verwendet werden. Der Architekturtest muss die erlaubte Ausnahme explizit pruefen, nicht pauschal weitere Player verbieten.

## 1.2 Bereits vorhandene Bausteine

Der aktuelle Stand hat bereits:

- `PlaybackService` als MediaLibraryService;
- genau einen sessionfuehrenden Hauptspieler;
- `MediaLibrarySession`;
- `PlaybackRepository` ueber einen `MediaController`;
- `CrossfadeController` mit zweitem ExoPlayer;
- `AudioPipeline` und `MasterDspProcessor`;
- `DspRenderersFactory`;
- Preamp, EQ, Limiter, DVC, Resampler und weitere DSP-Konfiguration;
- `TrackAnalyzerImpl` mit `MediaExtractor` und `MediaCodec`;
- Waveform-Akkumulatoren;
- Onset-Kandidaten;
- `TrackAnalysisRepositoryImpl` und `TrackAnalysisWorker`;
- Room-Tabelle `track_analysis`;
- Marker-Entities und Marker-Repository;
- `DropLandingPlanner`;
- `RestMusicCoordinator`;
- Tests fuer Kurven, Planner, Coordinator und Repository-Fakes.

Das sind gute Ansatzpunkte. Der Plan soll vorhandene Klassen erweitern, nicht parallel eine zweite App-Architektur einfuehren.

## 1.3 Konkrete aktuelle Schwachstellen

### A. Crossfade-Handoff ist nicht wirklich gapless

In `data/playback/.../CrossfadeController.kt` wird nach der Rampe sinngemaess Folgendes gemacht:

```kotlin
mainPlayer.setMediaItem(next, secondary.currentPosition)
mainPlayer.prepare()
mainPlayer.play()
secondary.release()
```

Das kann eine Luecke oder einen kleinen Sprung erzeugen, weil:

1. der Hauptspieler einen neuen MediaItem- und Decoder-Lebenszyklus beginnt;
2. `prepare()` nicht kostenlos ist;
3. der zweite Spieler bereits an einer anderen Decoder-/Audio-Clock laeuft;
4. die Positionen nicht zwingend dieselbe Zeitbasis haben;
5. der zweite Spieler danach sofort freigegeben wird.

Die Lautstaerke-Rampe kann dennoch subjektiv gut klingen. Das ist aber nicht dasselbe wie ein sample-kontrollierter Handoff.

### B. Der zweite Spieler wird zu spaet vorbereitet

`beginFade()` startet den zweiten Player erst, wenn der Haupttitel bereits in der Fade-Zone angekommen ist. Das ist fuer einen einfachen Best-Effort-Fade brauchbar, aber nicht fuer eine verlaessliche Drop-Landung. Decoder-Initialisierung, Pufferfuellung und Audio-Track-Oeffnung muessen vorher passieren.

### C. Der zweite Spieler nutzt nicht dieselbe DSP-RenderersFactory

Der Hauptspieler wird mit `DspRenderersFactory` aufgebaut. Der sekundere ExoPlayer wird im aktuellen Service mit einem normalen Builder erzeugt. Dadurch koennen Quellen unterschiedlich behandelt werden:

- anderer DSP-Pfad;
- anderer Resampler;
- anderes Ausgabeformat;
- andere Lautheitsstruktur;
- moeglicherweise unterschiedliche Audio-Session-/Route-Eigenschaften.

Bevor ein echter Dual-Player-Fade verwendet wird, muss entschieden werden, ob der zweite Spieler denselben DSP-Pfad erhaelt oder ob der Fade auf eine gemeinsame PCM-Mixer-Schicht verschoben wird.

### D. `RestMusicCoordinator` plant nur mit Coroutine-Delay

Heute wird ungefaehr so gearbeitet:

```kotlin
landingJob = scope.launch {
    delay(plan.startAfterDelayMs)
    playbackRepository.crossfadeTo(workSong, plan.startAtPositionMs)
}
```

Das hat mehrere Probleme:

- die Vorbereitung des Zielplayers geschieht erst am Ende der Wartezeit;
- ein `delay()` ist kein Audio-Clock-Scheduler;
- zwischen Timer-Clock und Player-Clock wird nicht nachgeregelt;
- ein Seek, Skip oder Routenwechsel kann den Plan veraendern;
- mehrere Jobs koennen logisch veraltet sein, auch wenn sie technisch gecancelt werden;
- ein Rebuffer waehrend des Drops wird nicht als Planfehler gemeldet.

### E. Track-Analyse ist vollstaendig, nicht progressiv

`TrackAnalyzerImpl` decodiert den Track in einem Durchgang. Das spart einen zweiten Decoder, ist fuer Akku und Konsistenz gut, hat aber diese Grenze:

- die Waveform erscheint erst nach Abschluss des Worker-Laufs;
- es gibt keine progressiven Zwischenresultate;
- `TrackAnalysisEntity` speichert aktuell primar Waveform-Metadaten;
- BPM, Tonart, RMS-/Energie-Kurven und Confidence sind noch nicht als vollstaendiges Transition-Modell vorhanden;
- Onsets werden bei einem expliziten Lauf als Marker geschrieben, nicht als reichhaltiges Analyseobjekt persistiert.

### F. Waveform-Bucket-Anzahl ist fuer Analyse okay, aber nicht fuer jeden Zoom

256 Buckets reichen fuer eine kleine Mini-Waveform. Bei einem 5-Minuten-Song und feinem Scrubbing sind sie zu grob. Die Loesung ist nicht, in jedem Compose-Recompose neu zu decodieren, sondern mehrere persistierte oder deterministisch abgeleitete Aufloesungen zu speichern.

### G. Die Audio-Clock ist nicht explizit modelliert

`PlaybackState.positionMs` basiert auf der Player-Position. Es gibt noch kein Domain-Modell fuer:

- monotone Planzeit;
- Decoder-/Prepare-Zustand;
- Audio-Track-Clock;
- route-spezifische Ausgabelatenz;
- Clock-Confidence;
- Rebuffer-/Underrun-Status.

Ohne dieses Modell kann DropSync nur ungefaehr landen.

---

# 2. Zielarchitektur

## 2.1 Zwei Betriebsarten bewusst trennen

Es gibt nicht einen einzigen Uebergangsfall. Teilt die Engine in zwei Modi:

### Modus 1: Normaler Queue-Wechsel

Ziel: moeglichst gapless und effizient.

- ein ExoPlayer;
- `setMediaItems(...)` oder `addMediaItem(...)` vor der Wiedergabe;
- Media3 uebernimmt die Timeline;
- Audio-Decoder kann den naechsten Titel vorbereiten;
- Crossfade nur dort, wo ein einfacher Player-Fade reicht;
- bei Album-/CUE-Grenzen kein Crossfade, sondern Gapless-Weiterlauf.

### Modus 2: Explizite Drop-Landung

Ziel: ein bestimmter Song wird an einer bestimmten Position zu einem bestimmten monotone-Zeitpunkt gestartet.

- Transition wird als Job geplant;
- Zieltrack wird vorher geladen;
- Zielposition wird vor dem Execute-Schritt gesetzt;
- Audio-Route und Clock werden beobachtet;
- bei genuegend Reserve wird crossfaded;
- bei kurzer Restzeit erfolgt ein kontrollierter Direct-Drop;
- nachher wird der Zustand als `LANDED`, `BEST_EFFORT` oder `ABORTED` vermerkt.

Normaler Queue-Wechsel und Drop-Landung duerfen nicht dieselbe Methode mit immer mehr Parametern werden. Sie brauchen einen gemeinsamen Backend-Vertrag, aber getrennte Planungsregeln.

## 2.2 Zielklassen

Empfohlene neue Klassen in `:domain:playback`:

```text
TransitionRequest.kt
TransitionPlan.kt
AudioClockModels.kt
PlaybackTransitionRepository.kt
```

Empfohlene neue Klassen in `:data:playback`:

```text
TransitionManager.kt
AudioClockMonitor.kt
PreparedTarget.kt
TransitionResultMapper.kt
```

Empfohlene Erweiterungen:

```text
PlaybackRepository.kt
PlaybackModels.kt
PlaybackCommands.kt
PlaybackService.kt
CrossfadeController.kt
```

## 2.3 Keine Media3-Typen in der Domain

Falsch:

```kotlin
// NICHT in :domain:playback
fun prepare(player: ExoPlayer, item: MediaItem)
```

Richtig:

```kotlin
// :domain:playback
suspend fun prepareTransition(request: TransitionRequest): AppResult<TransitionId>
```

Die Data-Schicht entscheidet, ob sie intern ExoPlayer, zwei ExoPlayer oder spaeter einen PCM-Mixer verwendet.

---

# 3. Phase 0: Ausgangslage einfrieren und Build pruefen

## 3.1 Ziel

Bevor Produktionscode veraendert wird, muss ein reproduzierbarer Ausgangszustand festgehalten werden. Eine Coding-KI darf nicht gleichzeitig Architektur, Formatierung und ungepruefte Nebenfehler reparieren.

## 3.2 Dateien lesen

Die KI soll mindestens diese Dateien oeffnen:

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
core/testing/src/test/kotlin/com/dropsync/core/testing/ModuleDependencyRulesTest.kt
domain/playback/.../PlaybackRepository.kt
data/playback/.../PlaybackService.kt
data/playback/.../CrossfadeController.kt
data/audio/.../AudioPipeline.kt
data/audio/.../TrackAnalyzerImpl.kt
data/audio/.../TrackAnalysisRepositoryImpl.kt
feature/player/.../RestMusicCoordinator.kt
domain/timer/.../DropLanding.kt
```

## 3.3 Java-Version beachten

Im analysierten Arbeitsverzeichnis wurde der Gradle-Wrapper mit Java 8 gestartet. Das Projekt verwendet moderne Android-/Kotlin-/Gradle-Versionen. Wenn Gradle oder Android-Plugins eine hoehere JVM verlangen, muss zuerst ein JDK 17 oder die vom Projekt festgelegte Version verwendet werden.

Beispiel Windows-Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew --version
```

Nur wenn das JBR-Verzeichnis wirklich existiert, darf es verwendet werden. Die Coding-KI soll keinen globalen Java-Installer ungefragt installieren.

## 3.4 Baseline-Befehle

```bash
./gradlew :domain:audio:test \
  :domain:timer:test \
  :data:playback:testDebugUnitTest \
  :feature:player:testDebugUnitTest \
  --no-daemon --console=plain
```

Danach:

```bash
./gradlew :core:testing:test \
  :app:assembleDebug \
  --no-daemon --console=plain
```

Wenn der Build wegen Java 8 abbricht, ist das ein Toolchain-Problem und kein Grund, Audio-Code zu veraendern. Das Ergebnis muss im Arbeitsprotokoll festgehalten werden.

## 3.5 Phase-0-Abnahme

Phase 0 ist fertig, wenn:

- die relevante Java-Version dokumentiert ist;
- die Baseline-Befehle ausgefuehrt wurden oder der konkrete Blocker dokumentiert ist;
- keine Produktionsdatei geaendert wurde;
- der aktuelle Crossfade- und RestMusic-Pfad als Sequenzdiagramm vorliegt;
- bestehende Tests unveraendert laufen.

---

# 4. Phase 1: Domain-Vertraege fuer Transitionen

## 4.1 Ziel

Die bisherige Methode

```kotlin
crossfadeTo(song, startPositionMs)
```

wird nicht sofort geloescht. Sie bleibt als Kompatibilitaets-Fallback bestehen. Zusaetzlich wird ein expliziter Lebenszyklus eingefuehrt.

## 4.2 Transition-Identitaet und Status

Datei:

```text
domain/playback/src/main/kotlin/com/dropsync/domain/playback/TransitionModels.kt
```

Beispiel:

```kotlin
package com.dropsync.domain.playback

@JvmInline
value class TransitionId(val value: String)

enum class TransitionKind {
    NORMAL_CROSSFADE,
    DROP_LANDING,
    DIRECT_DROP,
}

enum class TransitionPhase {
    PLANNED,
    PREPARING,
    PREPARED,
    ARMED,
    EXECUTING,
    LANDED,
    BEST_EFFORT,
    CANCELLED,
    FAILED,
}

enum class TransitionFailure {
    EMPTY_TARGET,
    TARGET_UNAVAILABLE,
    PREPARE_TIMEOUT,
    AUDIO_ROUTE_CHANGED,
    BUFFER_UNDERRUN,
    USER_OVERRIDE,
    STALE_PLAN,
    PLAYER_DISCONNECTED,
    UNSUPPORTED_FORMAT,
}

data class TransitionTarget(
    val songId: Long,
    val startPositionMs: Long,
    val markerId: Long? = null,
)

data class TransitionRequest(
    val id: TransitionId,
    val kind: TransitionKind,
    val target: TransitionTarget,
    /** Monotone Zeitpunkt, zu dem der Zieltrack hoerbar beginnen soll. */
    val executeAtElapsedRealtimeMs: Long?,
    val crossfadeDurationMs: Long,
    /** Reserve, in der ein neuer Decoder nicht mehr sicher gestartet wird. */
    val minimumPrepareReserveMs: Long = 1_500L,
    val requestedAtElapsedRealtimeMs: Long,
)

data class TransitionPlan(
    val request: TransitionRequest,
    val createdAtElapsedRealtimeMs: Long,
    val expectedRouteId: String?,
    val expectedOutputLatencyMs: Long?,
)

data class TransitionState(
    val id: TransitionId,
    val phase: TransitionPhase,
    val failure: TransitionFailure? = null,
    val progress: Float = 0f,
    val audibleStartEstimateElapsedRealtimeMs: Long? = null,
    val clockConfidence: ClockConfidence = ClockConfidence.UNKNOWN,
)

enum class ClockConfidence {
    UNKNOWN,
    SOFTWARE_ESTIMATE,
    AUDIO_TRACK_ESTIMATE,
    ROUTE_CALIBRATED,
}
```

### Warum `executeAtElapsedRealtimeMs` nullable ist

Ein normaler Crossfade wird oft relativ zum Titelende gestartet. Eine Drop-Landung hat dagegen einen konkreten monotone-Zielzeitpunkt. `null` bedeutet: nicht auf einen absoluten Zeitpunkt warten, sondern durch Queue-/Titelende-Logik ausfuehren.

### Warum ein `TransitionId` notwendig ist

Ein alter Coroutine-Job darf niemals einen neuen Plan ausfuehren. Jeder Befehl traegt deshalb eine ID. Der Service prueft vor jedem mutierenden Schritt:

```kotlin
if (activeTransitionId != request.id) return
```

Das ist robuster als nur `Job.cancel()`, weil Cancellation zwischen zwei IPC- oder Main-Dispatcher-Grenzen zu spaet ankommen kann.

## 4.3 Domain-Vertrag erweitern

Datei:

```text
domain/playback/src/main/kotlin/com/dropsync/domain/playback/PlaybackRepository.kt
```

Ergaenzung:

```kotlin
interface PlaybackRepository {
    // Bestehende Methoden bleiben bestehen.

    suspend fun prepareTransition(
        request: TransitionRequest,
    ): AppResult<TransitionId>

    suspend fun armTransition(
        id: TransitionId,
    ): AppResult<Unit>

    suspend fun cancelTransition(
        id: TransitionId,
        reason: TransitionFailure = TransitionFailure.USER_OVERRIDE,
    ): AppResult<Unit>

    fun observeTransition(id: TransitionId): Flow<TransitionState>
}
```

Die Implementierung darf in `:data:playback` intern eine `MediaController`-Custom-Command verwenden. Die Domain sieht nur den Vertrag.

## 4.4 Keine grosse Bundle-Ansammlung

Bestehende Custom Commands verwenden `Bundle`. Das bleibt fuer IPC in Ordnung, aber die Mapping-Logik muss zentral sein.

Nicht an vielen Stellen:

```kotlin
Bundle().apply {
    putLong("song_id", ...)
    putLong("start_position_ms", ...)
    putLong("execute_at", ...)
}
```

Stattdessen:

```kotlin
object TransitionCommandCodec {
    fun toBundle(request: TransitionRequest): Bundle = Bundle().apply {
        putString(KEY_ID, request.id.value)
        putString(KEY_KIND, request.kind.name)
        putLong(KEY_SONG_ID, request.target.songId)
        putLong(KEY_START_POSITION_MS, request.target.startPositionMs)
        putLong(KEY_CROSSFADE_MS, request.crossfadeDurationMs)
        request.executeAtElapsedRealtimeMs?.let { putLong(KEY_EXECUTE_AT, it) }
        request.target.markerId?.let { putLong(KEY_MARKER_ID, it) }
    }

    fun fromBundle(args: Bundle): TransitionRequest? {
        val id = args.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: return null
        val songId = args.getLong(KEY_SONG_ID, -1L)
        if (songId <= 0L) return null
        val kind = runCatching {
            TransitionKind.valueOf(args.getString(KEY_KIND).orEmpty())
        }.getOrNull() ?: return null
        return TransitionRequest(
            id = TransitionId(id),
            kind = kind,
            target = TransitionTarget(
                songId = songId,
                startPositionMs = args.getLong(KEY_START_POSITION_MS, 0L).coerceAtLeast(0L),
                markerId = args.getLong(KEY_MARKER_ID, Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE },
            ),
            executeAtElapsedRealtimeMs = args
                .getLong(KEY_EXECUTE_AT, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE },
            crossfadeDurationMs = args.getLong(KEY_CROSSFADE_MS, 0L).coerceAtLeast(0L),
            requestedAtElapsedRealtimeMs = args.getLong(KEY_REQUESTED_AT, 0L),
        )
    }

    private const val KEY_ID = "transition_id"
    private const val KEY_KIND = "transition_kind"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_START_POSITION_MS = "start_position_ms"
    private const val KEY_EXECUTE_AT = "execute_at_elapsed_ms"
    private const val KEY_CROSSFADE_MS = "crossfade_ms"
    private const val KEY_MARKER_ID = "marker_id"
    private const val KEY_REQUESTED_AT = "requested_at_elapsed_ms"
}
```

In der echten Datei muss `KEY_REQUESTED_AT` ebenfalls in `toBundle()` gesetzt werden. Das Beispiel zeigt bewusst die Stellen, an denen eine Coding-KI auf Vollstaendigkeit achten muss.

## 4.5 Phase-1-Tests

```kotlin
@Test
fun `transition bundle roundtrip behaelt die planparameter`() {
    val request = TransitionRequest(
        id = TransitionId("test-1"),
        kind = TransitionKind.DROP_LANDING,
        target = TransitionTarget(42L, 18_000L, 7L),
        executeAtElapsedRealtimeMs = 100_000L,
        crossfadeDurationMs = 3_000L,
        requestedAtElapsedRealtimeMs = 90_000L,
    )

    val decoded = TransitionCommandCodec.fromBundle(
        TransitionCommandCodec.toBundle(request),
    )

    assertEquals(request, decoded)
}
```

Weitere Tests:

- unbekannter `kind` ergibt `null`;
- negative Song-ID ergibt `null`;
- fehlende Transition-ID ergibt `null`;
- eine alte ID darf einen neuen Plan nicht ausfuehren;
- Cancellation-Grund wird erhalten.

---

# 5. Phase 2: Drop-Landung richtig berechnen

## 5.1 Bestehende Domain-Mathematik beibehalten

`domain/timer/.../DropLanding.kt` ist bereits die richtige Stelle fuer reine Mathematik. Nicht in den Media3-Service verschieben.

Die vorhandene Grundformel ist:

```text
R = verbleibende Restzeit
D = Drop-Position ab Songanfang

wenn D >= R:
    Song sofort starten bei Position D - R
sonst:
    Song erst nach R - D Millisekunden starten bei Position 0
```

Das stellt sicher, dass der Drop theoretisch bei `R = 0` liegt.

## 5.2 Latenz und Vorbereitungsreserve aufnehmen

Die aktuelle Funktion sollte um eine Ausfuehrungsreserve erweitert werden. Die Reserve ist keine Audio-Latenzkorrektur allein, sondern verhindert, dass ein nicht vorbereiteter Decoder zu spaet gestartet wird.

```kotlin
data class DropLandingInput(
    val remainingRestMs: Long,
    val outputLatencyMs: Long,
    val schedulingSafetyMs: Long = 100L,
    val crossfadeMs: Long,
    val minimumPrepareReserveMs: Long = 1_500L,
)

data class DropLandingDecision(
    val startAtPositionMs: Long,
    val startAfterDelayMs: Long,
    val executeAtElapsedRealtimeMs: Long?,
    val mode: TransitionKind,
    val timingConfidence: ClockConfidence,
)
```

Wichtig: `outputLatencyMs` darf nicht blind als exakte Wahrheit verwendet werden. Bei einem kabelgebundenen Ausgang kann es eine brauchbare Schaetzung sein. Bei Bluetooth ist es oft nur eine grobe Route-Kategorie.

## 5.3 Direct-Drop-Regel

Wenn die Restzeit kleiner als `crossfadeMs + minimumPrepareReserveMs` ist, wird kein neuer langer Fade erzwungen.

Beispiel:

```kotlin
fun chooseDropMode(
    remainingRestMs: Long,
    crossfadeMs: Long,
    prepareReserveMs: Long,
): TransitionKind =
    if (remainingRestMs >= crossfadeMs + prepareReserveMs) {
        TransitionKind.DROP_LANDING
    } else {
        TransitionKind.DIRECT_DROP
    }
```

`DIRECT_DROP` bedeutet nicht "unsauber springen". Es bedeutet:

1. Zieltrack vorbereiten, wenn noch moeglich;
2. Zielposition setzen;
3. laufende Quelle mit kurzer Gain-Rampe absenken;
4. Zielquelle mit kurzer Gain-Rampe anheben;
5. niemals einen nackten Sample-Sprung ohne Fade ausfuehren;
6. falls das Ziel nicht vorbereitet ist, bewusst auf `BEST_EFFORT` oder `FAILED` wechseln.

## 5.4 Marker-Auswahl korrigieren

In `RestMusicCoordinator.workCandidates()` muss die Auswahl der Marker deterministisch sein. Verlasst euch nicht darauf, dass ein DAO zufaellig sortiert liefert.

```kotlin
val marker = markerRepository
    .getEnabledMarkersForSong(song.mediaStoreId)
    .getOrNull()
    ?.filter { it.positionMs in 0..song.durationMs }
    ?.minByOrNull { it.positionMs }
    ?: return@mapNotNull null
```

Wenn spaeter mehrere Drops pro Song unterstuetzt werden, sollte die Auswahl nicht nur den fruehesten Marker nehmen. Dann werden Kandidaten nach einer Scoring-Funktion bewertet:

```kotlin
data class DropCandidateScore(
    val candidate: WorkSongDrop,
    val distanceToIdealStartMs: Long,
    val confidence: Float,
)
```

Fuer v1 reicht der frueheste bestaetigte Marker. Die Regel muss dokumentiert und getestet sein.

---

# 6. Phase 3: PlaybackService und TransitionManager

## 6.1 Ziel

Alle Media3-Zugriffe fuer vorbereitete Uebergaenge werden in `PlaybackService` oder einer dort erzeugten `TransitionManager`-Instanz konzentriert. `feature:player` darf weder Player-Zustand mutieren noch Timing auf einem lokalen ExoPlayer simulieren.

## 6.2 Zustandsmaschine

```text
IDLE
  |
  | prepare(request)
  v
PREPARING ---- Fehler ----> FAILED
  |
  | target ready
  v
PREPARED
  |
  | arm(id)
  v
ARMED ---- Nutzer-Pause/Seek/Skip ----> CANCELLED
  |
  | executeAt erreicht
  v
EXECUTING ---- Underrun/Route change ----> BEST_EFFORT oder FAILED
  |
  v
LANDED
```

Nur der aktive Plan darf weiterlaufen. Ein neuer `prepare()`-Befehl cancelt den alten Plan logisch, bevor er den neuen vorbereitet.

## 6.3 Manager-Skelett

Datei:

```text
data/playback/src/main/kotlin/com/dropsync/data/playback/TransitionManager.kt
```

Beispielstruktur:

```kotlin
@OptIn(UnstableApi::class)
class TransitionManager(
    private val mainPlayer: ExoPlayer,
    private val targetPlayerFactory: () -> ExoPlayer,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val songResolver: suspend (Long) -> MediaItem?,
) {
    private val mutableStates = MutableStateFlow<Map<TransitionId, TransitionState>>(emptyMap())
    private var active: ActiveTransition? = null

    fun observe(id: TransitionId): Flow<TransitionState> =
        mutableStates
            .map { it[id] ?: TransitionState(id, TransitionPhase.FAILED) }
            .distinctUntilChanged()

    suspend fun prepare(request: TransitionRequest): Result<TransitionId> {
        cancelActive(TransitionFailure.STALE_PLAN)
        publish(request.id, TransitionPhase.PREPARING)

        val item = songResolver(request.target.songId)
            ?: run {
                publish(request.id, TransitionPhase.FAILED, TransitionFailure.TARGET_UNAVAILABLE)
                return Result.failure(IllegalArgumentException("target unavailable"))
            }

        val target = runCatching { targetPlayerFactory() }.getOrElse { error ->
            publish(request.id, TransitionPhase.FAILED, TransitionFailure.PLAYER_DISCONNECTED)
            return Result.failure(error)
        }

        return try {
            withContext(Dispatchers.Main.immediate) {
                target.volume = 0f
                target.setMediaItem(item, request.target.startPositionMs)
                target.prepare()
                // Nicht play() aufrufen: Vorbereitung darf noch keinen Ton erzeugen.
            }
            active = ActiveTransition(request, target)
            publish(request.id, TransitionPhase.PREPARED)
            Result.success(request.id)
        } catch (error: Throwable) {
            target.release()
            publish(request.id, TransitionPhase.FAILED, TransitionFailure.TARGET_UNAVAILABLE)
            Result.failure(error)
        }
    }

    fun arm(id: TransitionId): Boolean {
        val current = active ?: return false
        if (current.request.id != id) return false
        publish(id, TransitionPhase.ARMED)
        current.executeJob?.cancel()
        current.executeJob = scope.launch {
            val at = current.request.executeAtElapsedRealtimeMs
            if (at != null) {
                waitUntil(at)
            }
            if (active?.request?.id != id) return@launch
            execute(current)
        }
        return true
    }

    suspend fun cancel(id: TransitionId, reason: TransitionFailure) {
        if (active?.request?.id != id) return
        cancelActive(reason)
    }

    private suspend fun execute(activeTransition: ActiveTransition) {
        val request = activeTransition.request
        val target = activeTransition.target
        publish(request.id, TransitionPhase.EXECUTING)

        withContext(dispatchers.main) {
            target.play()
        }
        publish(request.id, TransitionPhase.START_REQUESTED)

        // Nur wenn AudioClock/Player-Ereignisse einen plausiblen Start melden,
        // darf spaeter LANDED gesetzt werden. In diesem Geruest bleibt der
        // Nachweis bewusst offen; ein sofortiges LANDED waere falsch.
        // Niemals hier ungeprueft setMediaItem() auf dem Hauptspieler ausfuehren.
    }

    private suspend fun waitUntil(targetElapsedMs: Long) {
        while (isActive) {
            val remaining = targetElapsedMs - clock.elapsedRealtimeMs()
            if (remaining <= 0L) return
            delay(minOf(remaining, 25L))
        }
    }

    private fun cancelActive(reason: TransitionFailure) {
        val old = active ?: return
        old.executeJob?.cancel()
        old.target.release()
        publish(old.request.id, TransitionPhase.CANCELLED, reason)
        active = null
    }

    private fun publish(
        id: TransitionId,
        phase: TransitionPhase,
        failure: TransitionFailure? = null,
    ) {
        mutableStates.update { old ->
            old + (id to TransitionState(id, phase, failure))
        }
    }

    private data class ActiveTransition(
        val request: TransitionRequest,
        val target: ExoPlayer,
        var executeJob: Job? = null,
    )
}
```

Das ist **Pseudocode und kein fertiger Crossfade**. Besonders wichtig:

- `target.prepare()` ist nicht dasselbe wie "sample-genau bereit";
- `target.play()` darf nicht vor dem geplanten Ausfuehrungszeitpunkt passieren;
- `release()` muss auf dem Main-Thread erfolgen;
- `withContext(Dispatchers.Main.immediate)` muss im echten Projekt durch `dispatchers.main` aus dem vorhandenen `DispatcherProvider` ersetzt werden;
- `MonotonicClock` muss durch den vorhandenen `com.dropsync.core.common.Clock` oder eine bewusst eingefuehrte testbare Fassade ersetzt werden;
- alle aktiven IDs muessen geprueft werden;
- bei Exception darf kein halbaktiver Target-Player zurueckbleiben;
- **`target.play()` ist nur eine Startanforderung.** Der Status darf danach nicht sofort `LANDED` heissen. Erst `START_REQUESTED`, danach bei ausreichendem Wiedergabe-/Clock-Nachweis `LANDED`; andernfalls `BEST_EFFORT` oder `FAILED`.

## 6.4 ExoPlayer-Builder fuer den Target-Player

Der Target-Player muss mindestens dieselben Audio-Grundeinstellungen erhalten:

```kotlin
private fun buildTransitionPlayer(context: Context): ExoPlayer =
    ExoPlayer
        .Builder(
            context,
            DspRenderersFactory(
                context,
                audioPipeline.audioProcessors(),
                floatOutput = true,
            ),
        )
        .setAudioAttributes(
            AudioAttributes
                .Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .setHandleAudioBecomingNoisy(false)
        .build()
```

In der Praxis muss geprueft werden, ob zwei Spieler dieselbe `AudioPipeline`-Instanz sicher parallel bedienen duerfen. Wenn `MasterDspProcessor` internen mutablen Zustand besitzt, darf nicht einfach dieselbe Instanz parallel verwendet werden. Dann gibt es drei Optionen:

1. pro Spieler eine eigene DSP-Instanz;
2. DSP-Konfiguration vor dem Fade einfrieren und nur Lautstaerke ausserhalb der DSP-Kette rampen;
3. spaeter einen gemeinsamen PCM-Mixer vor einer einzigen DSP-Kette einsetzen.

Fuer eine stabile v1 ist Option 2 oft sicherer als eine ungetestete doppelte DSP-Kette.

---

# 7. Phase 4: Gapless-Playback und Handoff richtig umsetzen

## 7.1 Normale Gapless-Wiedergabe zuerst stabilisieren

Bevor ein komplexer Drop-Handoff gebaut wird, muss der normale Queue-Pfad korrekt sein:

```kotlin
player.setMediaItems(
    songs.map(MediaItemFactory::fromSong),
    startIndex = startIndex,
    startPositionMs = 0L,
)
player.prepare()
player.playWhenReady = true
```

Fuer einen fortlaufenden Queue-Wechsel darf nicht fuer jeden Song `setMediaItem()` plus `prepare()` ausgefuehrt werden. Der Player soll die Timeline kennen.

### Gapless-Regeln

- Bei Dateien desselben Albums/CUE-Kontexts keinen zusaetzlichen Crossfade erzwingen.
- Encoder Delay und Padding koennen nur dann korrekt entfernt werden, wenn Container/Decoder die Metadaten liefern.
- Unterschiedliche Sample-Raten sind kein automatischer Fehler. Media3 kann resamplen; das ist aber nicht dasselbe wie eine eigene sample-exakte Mixer-Clock.
- Ein Wechsel von 48 kHz zu 44,1 kHz kann einen neuen AudioTrack-/Sink-Kontext ausloesen. Die App muss diesen Wechsel als moegliche Latenzaenderung behandeln.

## 7.2 Warum `setMediaItem` nach dem Fade entfernt werden muss

Der bisherige Handoff:

```kotlin
mainPlayer.setMediaItem(next, secondary.currentPosition)
mainPlayer.prepare()
```

ist die zentrale Lueckenquelle. Es darf nicht der Default sein.

Verbesserung fuer normale Queue-Wechsel:

```kotlin
mainPlayer.setMediaItems(queue, currentIndex, currentPosition)
```

Verbesserung fuer einen expliziten Zieltrack:

- entweder den Zieltrack frueh in die Timeline aufnehmen und zum richtigen Zeitpunkt zu ihm wechseln;
- oder einen Dual-Player-Backend verwenden, bei dem die Rollen nicht nach jeder Transition durch `setMediaItem()` zurueckgesetzt werden;
- oder einen PCM-Mixer verwenden, der beide Dekoder in eine gemeinsame Audioausgabe mischt.

## 7.3 Pragmatiker-Entscheidung fuer v1

Es gibt drei technische Stufen:

### Stufe A: Media3-Playlist, kein echter Parallelmix

- beste Stabilitaet;
- gute Gapless-Chancen;
- kein beliebiger Drop-Sprung mit parallelem Rest-Track;
- geeignet fuer normale Bibliothekswiedergabe.

### Stufe B: Zwei dauerhaft verwaltete ExoPlayer, Volume-Fade

- geeignet fuer Rest-zu-Work und ueberlappende Musik;
- Target kann vorher vorbereitet werden;
- kein harter `mainPlayer.setMediaItem()`-Handoff waehrend der Audioausgabe;
- Rollenwechsel und MediaSession-Abbildung werden komplex;
- Timing bleibt Best-Effort.

### Stufe C: PCM-Mixer / eigener AudioSink

- gemeinsame Sample-/Frame-Zeitbasis;
- saubere Crossfades und Automation;
- hoher Aufwand, hohe Testlast, native/AudioTrack-Risiken;
- erst nach Messdaten und wenn Stufe B nicht ausreicht.

Empfehlung: Stufe A und B fuer v1, Stufe C nur als eigenes spaeteres Projekt. Nicht versuchen, Stufe C halb in `CrossfadeController` zu verstecken.

## 7.4 Volume-Rampe nicht mit Audio-Frame-Rampe verwechseln

Der aktuelle Controller arbeitet alle 50 ms:

```kotlin
delay(STEP_MS)
mainPlayer.volume = nextGain
secondary.volume = nextGain
```

Bei modernen Playern ist das fuer eine langsame 3-Sekunden-Rampe oft ausreichend. Es ist aber nicht sample-exakt und kann bei kurzen 50-ms-Rampen oder hartem `SLAM` hoerbare Stufen erzeugen.

Verbesserungen in Stufe B:

- UI-/Control-Thread nur fuer Zielwerte verwenden;
- Rampenwerte auf Main-Thread setzen;
- Fade-Dauer nicht kuerzer als 100-200 ms fuer harte Schnitte;
- bei 3 Sekunden 20-50-ms Steuerpunkte als Best-Effort akzeptieren;
- keine 60-fps-Compose-Recomposition als Audio-Scheduler verwenden;
- alle Rampen mit einem Fake-Clock-Test pruefen.

Ein echter sample-basierter Fade gehoert in einen `AudioProcessor`/Mixer, nicht in eine Coroutine.

---

# 8. Phase 5: Audio-Clock, Latenz und Drop-Timing

## 8.1 Drei Zeiten unterscheiden

```text
1. Planzeit:
   SystemClock.elapsedRealtime() bzw. monotone App-Clock.

2. Playerposition:
   ExoPlayer.currentPosition.

3. Hoerbare Position:
   Position des bereits ausgegebenen Audioframes; durch AudioTrack-/Route-
   Puffer hinter der Playerposition.
```

Die Drop-Landung muss die Planzeit und die Ausgabezeit verbinden. Nur `currentPosition` zu lesen reicht nicht.

## 8.2 Domain-Modell fuer Clock-Snapshots

```kotlin
data class AudioClockSnapshot(
    val capturedAtElapsedRealtimeMs: Long,
    val playerPositionMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val outputLatencyMs: Long?,
    val routeId: String?,
    val confidence: ClockConfidence,
    val hadRecentUnderrun: Boolean,
)
```

## 8.3 Polling-Frequenz

Empfehlung:

- UI-Position: 200 ms reicht.
- Transition-State: 50-100 ms.
- letzte 2 Sekunden vor einem kritischen Execute: 10-25 ms Scheduler-Schritte, aber nicht als Garantie.
- Audio-Clock-Timestamp: nur so oft abfragen, wie die API und das Geraet sinnvoll liefern; nicht blind 60-mal pro Sekunde auf Android-Hauptthread.

Der Scheduler darf nicht blockieren:

```kotlin
private suspend fun waitUntil(target: Long, clock: MonotonicClock) {
    while (currentCoroutineContext().isActive) {
        val remaining = target - clock.elapsedRealtimeMs()
        if (remaining <= 0L) return
        delay(
            when {
                remaining > 500L -> 100L
                remaining > 100L -> 25L
                else -> 10L
            },
        )
    }
}
```

Das ist ein Startsignal-Scheduler, keine Sample-Clock.

## 8.4 AudioTrack-Timestamps korrekt einordnen

Eine eigene `AudioSink`-/`AudioTrack`-Integration kann Playback-Head oder Timestamp-Daten liefern. In der aktuellen Architektur meldet `AudioInfoListener` zwar `AudioTrackConfig`, aber nicht die komplette physische Ausgabezeit an die Domain. **Das loest Bluetooth-Latenz nicht automatisch.** Ein AudioTrack-Timestamp beschreibt primaer die Android-Ausgabekette; bei Bluetooth ist die zusaetzliche Codec-/Headset-/Funkpufferzeit oft nicht vollstaendig sichtbar. Die Probe darf daher nur eine Schaetzung liefern. Route-/Codec-Kalibrierung und `BEST_EFFORT` bleiben notwendig; Reflection auf private Media3-Felder ist kein zulaessiger Ersatz.

Fuer eine belastbare Implementierung:

1. Ergaenze in `:data:audio` eine kleine `OutputClockProbe`-Schnittstelle.
2. Liefere nur Messwerte und keine Media3-Typen an `:data:playback`.
3. Kennzeichne `timestamp` als `AUDIO_TRACK_ESTIMATE`.
4. Wenn die API keinen validen Timestamp liefert, falle auf `SOFTWARE_ESTIMATE` zurueck.
5. Bei Bluetooth route-spezifische Unsicherheit anzeigen.

Beispiel:

```kotlin
interface OutputClockProbe {
    fun snapshot(): OutputClockSample?
}

data class OutputClockSample(
    val framePosition: Long,
    val nanoTime: Long,
    val sampleRateHz: Int,
    val valid: Boolean,
)
```

Die genaue Implementierung haengt davon ab, ob der verwendete Media3-Sink den `AudioTrack` zugaenglich macht. Nicht mit Reflection auf private Media3-Felder zugreifen. Wenn der Sink die Information nicht freigibt, ist ein kleiner eigener Sink-Wrapper oder eine alternative Messstrategie noetig.

## 8.5 Route-Latenz kalibrieren

### Kabelgebunden / lokaler Lautsprecher

- Playback-Head-/Timestamp-Schaetzung verwenden;
- feste Sicherheitsreserve von z. B. 50-100 ms;
- auf mehreren Android-Versionen testen.

### Bluetooth

- Route-ID, Codec und Ausgabegeraet beobachten;
- bekannte ungefaehre Route-Latenz als Profil speichern;
- bei Codec-Wechsel Profil als veraltet markieren;
- innerhalb der letzten 5 Sekunden vor Go nicht vollstaendig neu planen, sondern `BEST_EFFORT` melden oder Drop bewusst verschieben.

Eine In-App-Mikrofonmessung Click->Mikrofon ist nur eine grobe End-to-End-Messung und kann durch Raum, Lautsprecher, Mikrofon-AGC und Echo-Cancellation verfaelscht werden. Sie ist fuer Entwicklerkalibrierung nuetzlich, nicht als stille Nutzeraktion bei jedem Workout.

## 8.6 Route-Wechsel-Verhalten

Bei `AudioDeviceCallback` oder Output-Format-Wechsel:

```text
wenn kein aktiver Plan:
    normal weiter

wenn Plan PREPARING:
    Target verwerfen und auf neuer Route vorbereiten

wenn Plan ARMED und mehr als 5 s Reserve:
    Plan neu bewerten, Route-Latenz aktualisieren

wenn Plan ARMED und weniger als 5 s Reserve:
    nicht hektisch mehrfach neu planen
    BEST_EFFORT markieren
    Direct-Drop oder sicherer Standardwechsel

wenn Plan EXECUTING:
    keine neue grosse Vorbereitung erzwingen
    Underrun/Route change melden
```

---

# 9. Phase 6: RestMusicCoordinator umbauen

## 9.1 Ziel

Der Coordinator soll keine Audiooperation mehr direkt nach `delay()` ausloesen. Er soll:

1. Restpause erkennen;
2. Rest-Queue starten;
3. Work-Kandidaten sammeln;
4. Drop-Landing berechnen;
5. `prepareTransition()` sofort aufrufen;
6. nach erfolgreicher Vorbereitung `armTransition()` aufrufen;
7. Transition-Status beobachten;
8. bei Nutzer-Override oder Timer-Abbruch canceln.

## 9.2 Neue Ablaufstruktur

```kotlin
private suspend fun onRestBegin(
    session: TimerSession,
    behavior: RestMusicBehavior,
) {
    val restSongs = songsForLabel(PlaylistLabel.REST)
    if (restSongs.isEmpty()) return

    playbackRepository.setQueue(restSongs, 0, playWhenReady = true)
    controlling = true

    if (behavior != RestMusicBehavior.DROP_LANDING) return

    val request = createDropTransitionRequest(session) ?: return
    val prepared = playbackRepository.prepareTransition(request)
    if (prepared is AppResult.Failure) return

    val armed = playbackRepository.armTransition(request.id)
    if (armed is AppResult.Failure) return

    observeTransitionUntilEnd(request.id, session.id)
}
```

## 9.3 Request-Erzeugung

```kotlin
private suspend fun createDropTransitionRequest(
    session: TimerSession,
): TransitionRequest? {
    val startedAt = session.startedElapsedRealtimeMs ?: return null
    val now = clock.elapsedRealtimeMs()
    val restEnd = startedAt + session.durationMs
    val remaining = (restEnd - now).coerceAtLeast(0L)

    val (candidates, songsById) = workCandidates()
    val result = DropLandingPlanner.plan(remaining, candidates)
    val plan = (result as? DropLandingResult.Scheduled)?.plan ?: return null
    if (songsById[plan.songId] == null) return null

    val id = TransitionId(
        "rest-${session.id}-${plan.songId}-${plan.markerId}",
    )
    val mode = chooseDropMode(
        remainingRestMs = remaining,
        crossfadeMs = 3_000L,
        prepareReserveMs = 1_500L,
    )

    return TransitionRequest(
        id = id,
        kind = mode,
        target = TransitionTarget(
            songId = plan.songId,
            startPositionMs = plan.startAtPositionMs,
            markerId = plan.markerId,
        ),
        executeAtElapsedRealtimeMs = restEnd,
        crossfadeDurationMs = if (mode == TransitionKind.DIRECT_DROP) 100L else 3_000L,
        requestedAtElapsedRealtimeMs = now,
    )
}
```

Die Ausfuehrung muss beachten, dass ein 3-Sekunden-Crossfade vor dem Drop beginnen kann. Es gibt zwei Zeitpunkte:

```text
Drop-Zeitpunkt = Restende
Crossfade-Beginn = Restende - Crossfade-Dauer - Zieltrack-Lead-Anteil
```

Der Transition-Plan darf deshalb nicht blind `executeAt = restEnd` setzen, wenn der Controller den Fade erst dann startet. Entweder:

- der Target-Track startet zum Fade-Beginn und sein Marker wird entsprechend vorbereitet;
- oder `executeAt` bezeichnet den Beginn der Transition und `dropAt` wird separat gespeichert.

Robusteres Modell:

```kotlin
data class TransitionTiming(
    val transitionStartAtElapsedRealtimeMs: Long,
    val targetAudibleStartAtElapsedRealtimeMs: Long,
    val dropAtElapsedRealtimeMs: Long,
)
```

## 9.4 Nutzer-Override

Der Coordinator muss Player-Ereignisse nicht nur indirekt ueber `TimerState` erkennen. Der Playback-State braucht ein Ereignis fuer manuelle Befehle oder zumindest eine Sequenznummer.

Minimalvariante:

```kotlin
data class PlaybackState(
    // bestehende Felder
    val userOverrideGeneration: Long = 0L,
)
```

Bei Play/Pause/Seek/Skip aus UI, Notification oder Bluetooth wird die Generation erhoeht. Der Transition-Plan speichert die Generation und bricht ab, wenn sie sich aendert.

Besser ist ein Domain-Event-Flow:

```kotlin
sealed interface PlaybackInterruption {
    data object UserPause : PlaybackInterruption
    data object UserSeek : PlaybackInterruption
    data object UserSkip : PlaybackInterruption
    data class AudioRouteChanged(val routeId: String?) : PlaybackInterruption
    data object BufferUnderrun : PlaybackInterruption
}
```

---

# 10. Phase 7: CrossfadeController reparieren

## 10.1 Erst die Fehler beseitigen

Folgende Aenderungen sind Pflicht, bevor Presets erweitert werden:

1. `secondary.prepare()` fruehzeitig ausfuehren.
2. Einen vorbereiteten Target-Player nicht bei jeder Planung verwerfen.
3. Keine `mainPlayer.setMediaItem()`-Uebernahme mitten in der Ausgabe als Standard.
4. `cancelFade()` idempotent machen.
5. `secondary.release()` immer auf dem Main-Thread ausfuehren.
6. bei einem neuen Plan die alte Transition-ID invalidieren.
7. unterschiedliche Audio-Session-/DSP-Eigenschaften messen.

## 10.2 Lazy Target Preparation

Minimaler Zwischenschritt:

```kotlin
fun prepareTarget(next: MediaItem, startPositionMs: Long): Result<Unit> {
    return runCatching {
        val player = getOrCreateSecondary()
        player.volume = 0f
        player.setMediaItem(next, startPositionMs)
        player.prepare()
    }
}

fun executePreparedTarget(): Boolean {
    val player = secondaryPlayer ?: return false
    if (player.playbackState == Player.STATE_IDLE) return false
    player.play()
    return true
}
```

`STATE_READY` ist ein gutes Indiz, aber keine Garantie, dass beliebig viel Audio bereits gepuffert ist. Deshalb muss auch `isLoading` und bei Bedarf `bufferedPosition` beobachtet werden.

## 10.3 Rollen statt sofortiger Handoff

Wenn zwei Player verwendet werden, darf die Logik nicht automatisch annehmen, dass `mainPlayer` immer die Quelle bleibt. Modelliert Rollen:

```kotlin
enum class PlayerRole { SOURCE, TARGET }

data class PlayerPairState(
    val sourceRole: PlayerRole,
    val sourceSongId: Long?,
    val targetSongId: Long?,
    val fadeProgress: Float,
)
```

Nach einem erfolgreichen Fade kann der bisherige Target-Player logisch die aktive Quelle sein. Der alte Source-Player wird nicht mitten im Ton als neuer Hauptspieler neu initialisiert. Fuer MediaSession muss jedoch geprueft werden, wie der logische Player-Zustand weitergereicht wird. Wenn diese Abbildung nicht sauber moeglich ist, ist der Dual-Player nur fuer kurze explizite Uebergaenge zulaessig und danach muss die Wiedergabe bewusst in die normale Queue zurueckkehren.

## 10.4 Audio-Focus-Regel

Nur der sessionfuehrende Player darf Audio Focus anfordern. Der Target-Player wird mit `handleAudioFocus = false` erstellt. Sonst koennen sich die zwei Player gegenseitig ducking oder focus loss ausloesen.

Bei Focus-Events:

- Timer laeuft weiter;
- Musik kann pausieren oder ducking bekommen;
- aktiver Transition-Plan wird eingefroren oder `BEST_EFFORT`;
- nach Focus-Gewinn nicht automatisch auf den alten Samplezeitpunkt springen, ohne Clock neu zu pruefen.

---

# 11. Phase 8: Waveform und Analyse erweitern

## 11.1 Ein Analyse-Durchgang bleibt Pflicht

Nicht fuer Waveform, BPM, Onset und RMS jeweils einen eigenen Decoder starten. Der bestehende `MediaExtractor`/`MediaCodec`-Loop wird erweitert.

Akkumulatoren:

```text
WaveformAccumulator       Min/Max je Zeitbucket
RmsAccumulator             Kurzzeit-RMS je Fenster
OnsetAccumulator           Novelty/positive Energieaenderung
TempoAccumulator           Inter-Onset-Histogramm
ChromaAccumulator          12 Pitch-Klassen, optional spaeter
LoudnessAccumulator        integrierte Lautheit / True-Peak-Naeherung
```

Nicht jeder Track muss alle Akkumulatoren berechnen. Der Worker bekommt ein Analyseprofil:

```kotlin
enum class AnalysisProfile {
    WAVEFORM_ONLY,
    WAVEFORM_AND_ONSETS,
    MIX_METADATA,
    FULL,
}
```

## 11.2 Datenmodell erweitern

Aktuelle Entity:

```kotlin
TrackAnalysisEntity(
    songId,
    waveformData,
    bucketCount,
    analyzerVersion,
    analyzedAtEpochMs,
)
```

Additiv erweitern:

```kotlin
@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val songId: Long,
    @ColumnInfo(name = "waveform_data") val waveformData: ByteArray,
    @ColumnInfo(name = "bucket_count") val bucketCount: Int,
    @ColumnInfo(name = "analyzer_version") val analyzerVersion: Int,
    @ColumnInfo(name = "analyzed_at_epoch_ms") val analyzedAtEpochMs: Long,
    @ColumnInfo(name = "rms_data") val rmsData: ByteArray? = null,
    @ColumnInfo(name = "onset_data") val onsetData: ByteArray? = null,
    @ColumnInfo(name = "bpm") val bpm: Float? = null,
    @ColumnInfo(name = "bpm_confidence") val bpmConfidence: Float? = null,
    @ColumnInfo(name = "camelot_key") val camelotKey: String? = null,
    @ColumnInfo(name = "key_confidence") val keyConfidence: Float? = null,
    @ColumnInfo(name = "integrated_lufs") val integratedLufs: Float? = null,
    @ColumnInfo(name = "true_peak_db") val truePeakDb: Float? = null,
)
```

Das ist eine additive Migration. Bestehende Wellenformen duerfen bei einer Analyzer-Version-Aenderung als Anzeige-Fallback erhalten bleiben, waehrend die neue Analyse laeuft.

## 11.3 Cache-Invalidierung

Die Version muss eine Konstante bleiben:

```kotlin
object AnalyzerVersion {
    const val CURRENT = 3
}
```

Regel:

```text
Version gleich CURRENT:
    alle angeforderten Felder verwenden

Version kleiner CURRENT:
    vorhandene Waveform als Fallback zeigen
    neue Analyse dedupliziert anstossen
    Mix-Metadaten erst verwenden, wenn sie neu berechnet sind

Version groesser CURRENT:
    Eintrag nicht ueberschreiben, bis die App kompatibel ist
```

Ein blinder `takeIf { version == CURRENT }` macht die Waveform bei jedem Analyzer-Update leer. Das ist fuer die UX nicht noetig.

## 11.4 Worker-Queue fuer 1000 Songs

`WorkManager` ist fuer Analyse richtig, aber nicht fuer den exakten Rest-Timer.

Empfohlene Regeln:

- unique work pro Song und Analyzer-Version;
- `ExistingWorkPolicy.KEEP` fuer gleiche Version;
- Akku-Konstraint fuer Batch-Analyse;
- keine Analyse parallel zu einem kritischen Workout, wenn Akku-/CPU-Budget niedrig ist;
- zuletzt gespielte Songs zuerst;
- bei Import nur Waveform-Minimum;
- BPM/Key/Onset erst bei Mix-Aktivierung oder explizitem Nutzerwunsch;
- Worker in kleine Abschnitte teilen, wenn der Decoder lange monopolisiert.

Beispiel:

```kotlin
val constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .build()

val request = OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
    .setInputData(
        workDataOf(
            TrackAnalysisWorker.KEY_SONG_ID to song.mediaStoreId,
            TrackAnalysisWorker.KEY_PROFILE to AnalysisProfile.MIX_METADATA.name,
            TrackAnalysisWorker.KEY_ANALYZER_VERSION to AnalyzerVersion.CURRENT,
        ),
    )
    .setConstraints(constraints)
    .build()
```

Keine `setRequiresCharging(true)`-Pflicht fuer die Analyse im normalen Import, sonst bleibt sie bei vielen Nutzern lange liegen. Fuer eine freiwillige Vollanalyse kann Laden als Option angeboten werden.

## 11.5 BPM und Key nicht als Drop-Wahrheit verwenden

BPM und Tonart helfen bei Queue-Sortierung und Uebergangswahl. Sie beweisen nicht, wo ein musikalischer Drop liegt. Ein Drop-Vorschlag sollte mehrere Signale kombinieren:

```text
Bass-Energieanstieg
+ spektraler Fluss
+ Novelty-Spike
+ beatnahe Position
+ Mindestabstand
+ Konfidenz
```

Alle automatischen Vorschlaege bleiben deaktiviert, bis der Nutzer sie bestaetigt.

---

# 12. Phase 9: Waveform-UI performant machen

## 12.1 Grundregel

Die Compose-Recomposition darf niemals den MediaCodec-Decoder neu starten.

Trennt:

```text
Analyse-Repository -> StateFlow<TrackAnalysis>
                   -> immutable UI model
                   -> Canvas zeichnet Buckets

Player-Ticker -> currentPositionMs
              -> nur Fortschrittsmarke aktualisieren
```

## 12.2 Keine Allokation in `Canvas`-Draw-Schleifen

Schlecht:

```kotlin
Canvas(...) {
    val points = analysis.buckets.map { ... } // pro Frame neue Liste
    points.forEach { drawLine(...) }
}
```

Besser:

```kotlin
val preparedBuckets by remember(analysis?.songId, analysis?.version) {
    derivedStateOf {
        WaveformGeometry.from(analysis?.waveformBuckets.orEmpty())
    }
}

Canvas(modifier = modifier) {
    val geometry = preparedBuckets
    drawWaveformBatch(
        geometry = geometry,
        progress = progress,
        markerPositions = markers,
    )
}
```

Noch besser fuer grosse Datenmengen: eine `Path` oder vorbereitete Float-Arrays je Zoomstufe.

## 12.3 Zoomstufen

Empfohlene gespeicherte oder berechenbare Ebenen:

```text
256 Buckets   Mini-Player / Listenkarte
1024 Buckets  Now Playing Standard
4096 Buckets  grobes Scrubbing / lange Songs
8192 Buckets  optionaler Detailmodus
```

Die Analyse muss nicht zwingend vier Decoderlaeufe machen. Die Buckets koennen im selben Durchgang auf mehreren Ebenen aggregiert werden:

```kotlin
class MultiResolutionWaveform(
    private val bucketCounts: IntArray,
    private val totalSamples: Long,
) {
    fun accept(sample: Double) {
        // je Ebene den passenden Zeitbereich aktualisieren
    }
}
```

## 12.4 Marker-Position in Pixel umrechnen

```kotlin
fun positionToX(
    positionMs: Long,
    durationMs: Long,
    widthPx: Float,
): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs * widthPx)
        .coerceIn(0.0, widthPx.toDouble())
        .toFloat()
}
```

Nicht fuer jeden Marker `seekTo()` ausloesen. Beim Drag:

- lokal Vorschauposition berechnen;
- erst bei Loslassen `PlaybackRepository.seekTo()` aufrufen;
- optional bei Accessibility ueber explizite Buttons feinjustieren.

## 12.5 Fortschrittsfarbe

Zeichnet die Waveform nicht zweimal mit komplett neuen Path-Objekten, wenn ein Clip reicht:

```kotlin
drawWaveform(path, color = inactiveColor)
clipRect(right = width * progress) {
    drawWaveform(path, color = activeColor)
}
```

Marker werden in einer separaten, kleinen Schleife gezeichnet. Die Markeranzahl ist klein; die Waveform bleibt voralloziert.

---

# 13. Phase 10: Ducking, ReplayGain und DSP-Reihenfolge

## 13.1 Gain nicht doppelt anwenden

Die App hat mehrere moegliche Pegelstellen:

```text
ReplayGain / Track Gain
-> Nutzer-Preamp
-> Rest-Ducking
-> TTS-Ducking
-> EQ / Bass / Treble
-> Limiter
-> DVC / Systemlautstaerke
-> AudioTrack / Route
```

Die genaue Reihenfolge muss im Projekt als Entscheidung festgelegt werden. Eine praxistaugliche interne Struktur ist:

```text
Quellenskalierung (ReplayGain)
    -> gemeinsame interne Preamp-/Ducking-Gain-Kombination
    -> EQ und sonstige DSP-Stufen
    -> Limiter / Schutz
    -> Ausgabeformat / Dither
    -> DVC bzw. Systemlautstaerke
```

Wichtig ist, dass Rest-Duck und TTS-Duck nicht zwei unabhaengige Multiplikatoren an verschiedenen unkontrollierten Stellen werden.

## 13.2 Ducking mathematisch kombinieren

Nicht:

```kotlin
gain *= restDuck
// spaeter nochmal
 gain *= ttsDuck
```

Das kann bei mehreren Systemen unklar werden. Besser:

```kotlin
data class DuckingState(
    val restGain: Double = 1.0,
    val ttsGain: Double = 1.0,
)

fun combinedDucking(state: DuckingState): Double =
    minOf(state.restGain, state.ttsGain)
```

`minOf` bedeutet: das staerkste Ducking gewinnt. Wenn bewusst additive dB-Werte gewuenscht sind, muss das ebenfalls explizit sein:

```kotlin
fun combinedDb(restDb: Double, ttsDb: Double): Double =
    (restDb + ttsDb).coerceIn(-24.0, 0.0)
```

Nicht beide Modelle gleichzeitig verwenden.

## 13.3 ReplayGain

ReplayGain oder R128-Lautheit ist **kein v1-Standard**. Es wird nur aktiviert, wenn der Nutzer es in den Einstellungen explizit einschaltet (Default aus, Entscheidung des Nutzers). Die Analyse-Metadaten (`LoudnessInfo`) koennen in v1 dennoch optional mitlaufen, aber angewendet wird ReplayGain erst nach Aktivierung.

- Track-Gain fuer gemischte Gym-Playlist;
- Album-Gain nur, wenn Album-Reihenfolge und Album-Lautheit bewusst erhalten werden soll;
- Preamp-Reserve vor EQ und Boost einplanen;
- Limiter als Schutz, nicht als staendige Lautheitskorrektur.

Beispiel-Domainmodell:

```kotlin
data class LoudnessInfo(
    val trackGainDb: Float?,
    val albumGainDb: Float?,
    val truePeakDb: Float?,
)
```

Wenn ein Song bereits am True-Peak-Limit ist und zusaetzlich EQ-Boost bekommt, muss die Kette Gain reduzieren oder limitieren. Keine Crossfade-Kurve kann Clipping reparieren, das vorher schon entsteht.

---

# 14. Phase 11: Buffer-Underruns und AudioFocus

## 14.1 Listener-Ereignisse

`PlaybackService` soll mindestens diese Zustandswechsel erfassen:

- `onPlaybackStateChanged`;
- `onIsLoadingChanged`;
- `onPlayerError`;
- `onPositionDiscontinuity`;
- `onMediaItemTransition`;
- Analytics-/AudioSink-Underrun-Ereignisse, soweit die verwendete Media3-Version sie anbietet.

Ein Rebuffer ist nicht dasselbe wie ein normaler Titelwechsel. Im Transition-Modell muss er sichtbar werden.

## 14.2 Verhalten im Drop-Kontext

```text
Underrun vor PREPARED:
    Ziel erneut vorbereiten, wenn genug Reserve

Underrun waehrend ARMED und > 2 s vor Drop:
    Plan neu bewerten, Execute-Zeit bleibt nur bei stabiler Clock

Underrun < 2 s vor Drop:
    nicht mehrfach seek/prepare ausfuehren
    BEST_EFFORT oder Direct-Drop

Underrun genau beim Drop:
    Resultat als MISSED/BEST_EFFORT melden
    keinen zweiten hektischen Drop ausloesen
```

## 14.3 AudioFocus

Der Audio-Foreground-Service soll AudioFocus von Media3 verwalten lassen. Keine doppelte eigene AudioFocus-Logik daneben, ausser fuer eine klar definierte TTS-Integration.

Bei `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`:

- Player bleibt aktiv, wenn Media3 das zulaesst;
- Timer laeuft unabhaengig weiter;
- aktiver Drop-Plan verliert bei grosser Fokusunterbrechung seine Genauigkeitsgarantie;
- UI kann `BEST_EFFORT` anzeigen.

Bei dauerhaftem Focus Loss:

- Wiedergabe pausieren;
- Transition canceln;
- beim Nutzer-Resume nicht heimlich den alten Drop erzwingen.

---

# 15. Phase 12: TTS und Haptik

## 15.1 TTS nicht als harte Audio-Clock verwenden

Android `TextToSpeech` hat eigene Synthese- und Ausgabepuffer. `speak("Go", ...)` bedeutet nicht, dass der erste Laut exakt beim Aufrufzeitpunkt am Lautsprecher beginnt.

Fuer einen Countdown:

- `3`, `2`, `1` frueh genug planen;
- fertige kurze Sprachsamples lokal vorhalten, wenn ein enger Cue wichtig ist;
- `Go` als lokales PCM/Audio-Asset abspielen, wenn reproduzierbare Startlatenz wichtiger ist als beliebige Sprache;
- TTS nur als Komfortansage verwenden;
- TextToSpeech-Callbacks zeigen Synthesezustand, nicht garantiert den physikalischen Lautsprecherbeginn.

## 15.2 Haptik

Haptik und Audio haben getrennte Latenzen. Ein gemeinsamer monotone-Zeitpunkt ist moeglich, aber keine physische Gleichzeitigkeit auf jedem Telefon.

```kotlin
data class GoCue(
    val executeAtElapsedRealtimeMs: Long,
    val audioAsset: String,
    val vibrationEffect: VibrationEffect,
)
```

Beide Aktionen werden im selben Scheduler-Job angestossen:

```kotlin
val remaining = cue.executeAtElapsedRealtimeMs - clock.elapsedRealtimeMs()
if (remaining > 0) delay(remaining)

audioCuePlayer.play(cue.audioAsset)
vibrator.vibrate(cue.vibrationEffect)
```

Das liefert gemeinsame Startintention, nicht identische Wahrnehmung. Im Gym sollte die Haptik kurz und deutlich sein, nicht als lautes Notification-Muster den Nutzer erschrecken.

---

# 16. Phase 13: Datenbankmigration

## 16.1 Aktueller Stand

Die gelesene `DropSyncDatabase` steht auf Version 4. `TrackAnalysisEntity` enthaelt aktuell Waveform-Daten und Analyzer-Version. Neue Analysefelder brauchen eine additive Migration.

## 16.2 Beispiel Migration 4 -> 5

Die konkreten Spalten muessen mit der finalen Entity uebereinstimmen. Beispiel:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `rms_data` BLOB")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `onset_data` BLOB")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `bpm` REAL")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `bpm_confidence` REAL")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `camelot_key` TEXT")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `key_confidence` REAL")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `integrated_lufs` REAL")
        db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `true_peak_db` REAL")
    }
}
```

Keine Spalte hinzufuegen, die nicht in der Entity vorhanden ist. Keine destruktive Migration. Schema-Export aktualisieren und Migration-Test schreiben.

## 16.3 Migration-Test

```kotlin
@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    @Test
    fun migration_preserves_existing_waveform() {
        helper.createDatabase("dropsync.db", 4).apply {
            // Fixture mit einem alten track_analysis-Eintrag schreiben.
            close()
        }

        helper.runMigrationsAndValidate(
            "dropsync.db",
            5,
            true,
            MIGRATION_4_5,
        )
    }
}
```

Der Test muss wirklich pruefen, dass bestehende `waveform_data` und `bucket_count` erhalten bleiben.

---

# 17. Phase 14: Deterministische Tests ohne echtes Geraet

## 17.1 Fake-Clock

```kotlin
class TestMonotonicClock(
    initialMs: Long = 0L,
) : MonotonicClock {
    var nowMs: Long = initialMs

    override fun elapsedRealtimeMs(): Long = nowMs

    fun advanceBy(deltaMs: Long) {
        check(deltaMs >= 0L)
        nowMs += deltaMs
    }
}
```

Keine echten `delay()`-Aufrufe in Domain-Tests. Der Scheduler muss eine injizierbare Clock und einen kontrollierbaren Delay-Mechanismus verwenden.

## 17.2 Fake-Playback-Backend

```kotlin
class FakeTransitionBackend : TransitionBackend {
    val calls = mutableListOf<String>()
    val states = MutableStateFlow<Map<TransitionId, TransitionState>>(emptyMap())

    override suspend fun prepare(request: TransitionRequest): Result<TransitionId> {
        calls += "prepare:${request.id.value}"
        publish(request.id, TransitionPhase.PREPARED)
        return Result.success(request.id)
    }

    override suspend fun arm(id: TransitionId): Result<Unit> {
        calls += "arm:${id.value}"
        publish(id, TransitionPhase.ARMED)
        return Result.success(Unit)
    }

    override suspend fun cancel(id: TransitionId, reason: TransitionFailure) {
        calls += "cancel:${id.value}:$reason"
        publish(id, TransitionPhase.CANCELLED, reason)
    }

    private fun publish(
        id: TransitionId,
        phase: TransitionPhase,
        failure: TransitionFailure? = null,
    ) {
        states.update {
            it + (id to TransitionState(id, phase, failure))
        }
    }
}
```

## 17.3 Race-Condition-Szenarien

Jeder Test soll Ereignisse in fester Reihenfolge ausloesen:

1. Rest-Timer startet.
2. Coordinator erstellt Plan A.
3. Plan A wird vorbereitet.
4. Nutzer drueckt Skip.
5. Plan B wird erstellt.
6. Zeit erreicht Execute-Zeit von A.
7. Erwartung: A darf nichts mehr ausfuehren.
8. B darf nur ausfuehren, wenn es noch aktiv und armed ist.

Weitere Tests:

- Timer wird beendet, waehrend `prepareTransition()` laeuft;
- Audio-Route wechselt vor `armTransition()`;
- `crossfadeSeconds = 0`;
- `startPositionMs` liegt hinter Songdauer;
- Song wird aus Library entfernt;
- Work-Playlist ist leer;
- Marker ist deaktiviert;
- zwei Marker liegen innerhalb 50 ms;
- `AudioTrack`-Timestamp ist ungueltig;
- Rebuffer exakt bei Execute;
- Service verliert MediaController-Verbindung;
- Nutzer pausiert waehrend der Rampe;
- zweiter Player wirft bei `prepare()` eine Exception;
- `cancel()` wird zweimal aufgerufen.

## 17.4 Crossfade-Kurven testen

Fuer alle Presets ausser `SLAM`:

```kotlin
@Test
fun `equal power remains bounded`() {
    for (preset in MixPreset.entries.filter { it != MixPreset.SLAM }) {
        for (step in 0..100) {
            val t = step / 100.0
            val inGain = preset.fadeInGain(t)
            val outGain = preset.fadeOutGain(t)
            assertEquals(1.0, inGain * inGain + outGain * outGain, 1e-9)
        }
    }
}
```

Bei Ducking muss die Invariante angepasst werden: Equal Power gilt fuer die nicht-geduckten Quellen. Wenn eine Quelle bewusst -8 dB leiser ist, darf der Summenpegel nicht automatisch als Equal-Power-Identitaet behauptet werden.

---

# 18. Phase 15: Messungen und reale Abnahme

## 18.1 Testgeraete-Matrix

Mindestens:

| Klasse | Beispielhafte Eigenschaft |
|---|---|
| Pixel/Android Referenz | relativ konsistente Media3-Ausgabe |
| Samsung | eigener Audio-Stack / Routing-Verhalten |
| Xiaomi/HyperOS | aggressive Hintergrund- und Bluetooth-Optimierung |
| Kabel/USB | andere Puffer-/Resampler-Eigenschaften |
| SBC Bluetooth | hohe und variable Latenz |
| AAC Bluetooth | route-/headset-abhaengig |
| LDAC oder vergleichbar | Codec-Wechsel und Puffer groesser |

Keine konkrete Geraeteliste als universelle Wahrheit behandeln. Die Ergebnisse muessen als Messprotokoll mit Android-Version, Route, Codec, Sample-Rate und Crossfade-Dauer gespeichert werden.

## 18.2 Messwerte

Pro Lauf erfassen:

```text
routeId
codec, wenn sichtbar
sourceSampleRate
outputSampleRate
crossfadeDuration
plannedDropAt
estimatedAudibleDropAt
playerPositionAtExecute
rebufferCount
underrunCount
resultPhase
clockConfidence
```

## 18.3 Akzeptanzkriterien fuer v1

Beispielhafte, ehrliche Kriterien:

- normale Queue wechselt ohne absichtliche Zusatzluecke, sofern Quelle/Container gapless-faehig sind;
- Waveform wird nach Cache-Hit ohne MediaCodec-Start angezeigt;
- Waveform-Scrubbing erzeugt keine neue Analyse und keine Allokationslawine;
- ein bestaetigter Marker wird beim naechsten Drop-Landing-Plan verwendet;
- ein alter Plan kann nach Skip nie mehr ausfuehren;
- Rest-Timer laeuft unabhaengig von Audiofocus und UI;
- bei fehlender Vorbereitung gibt es einen sichtbaren Fallback-Zustand;
- lokale Ausgabe erzielt in kontrolliertem Test eine dokumentierte Toleranz, z. B. Zielbereich ±50-100 ms;
- Bluetooth wird als Best-Effort getestet und nicht als harte Millisekunden-Garantie beworben;
- keine Compose-Recomposition startet Decoder oder Worker neu;
- `:core:testing:ModuleDependencyRulesTest` bleibt gruen.

---

# 19. Konkrete Reihenfolge der Umsetzung

Diese Reihenfolge ist fuer eine Coding-KI verbindlich. Keine spaetere Phase vorziehen, wenn eine fruehere Schnittstelle noch nicht getestet ist.

## Schritt 1: Dokumentation und Baseline

Dateien nicht veraendern. Build und Tests ausfuehren. Crossfade-/Coordinator-Tests lesen.

**Fertig wenn:** Baseline und Blocker dokumentiert.

## Schritt 2: Domain-Modelle

Anlegen:

```text
domain/playback/.../TransitionModels.kt
domain/playback/.../TransitionCommandModels.kt
```

PlaybackRepository um prepare/arm/cancel/observe erweitern. Alle Fakes und Test-Implementierungen aktualisieren.

**Fertig wenn:** Domain- und Fake-Tests kompilieren.

## Schritt 3: Command-Codec und Service-Commands

`PlaybackCommands.kt` und `PlaybackService.LibrarySessionCallback` erweitern. Bundle-Mapping nur an einer Stelle.

**Fertig wenn:** Roundtrip- und unbekanntes-Payload-Tests gruen sind.

## Schritt 4: TransitionManager ohne echte Rampe

Nur PREPARING -> PREPARED -> ARMED -> LANDED simulieren. Target-Player vorbereiten, aber noch keinen alten Handoff entfernen.

**Fertig wenn:** Service kann einen Target-Track vorbereiten und wieder freigeben, ohne Leak.

## Schritt 5: RestMusicCoordinator auf Plan-Lifecycle umstellen

`delay { crossfadeTo() }` entfernen. Stattdessen Request erzeugen, vorbereiten, armen und Status beobachten.

**Fertig wenn:** Fake-Tests Skip-vor-Execute, Timer-Ende und leere Work-Playlist abdecken.

## Schritt 6: Normalen Gapless-Queue-Pfad stabilisieren

`setMediaItems` und Queue-Transition testen. Crossfade-Gate fuer Album/CUE testen. Nicht parallel neue Presets bauen.

**Fertig wenn:** normale Queue keine durch den Umbau eingefuehrte Luecke hat.

## Schritt 7: Prepared Target und kurze Fade-Rampe

Target frueh vorbereiten. Direct-Drop mit 100-200-ms Schutzrampe. `setMediaItem`-Handoff nicht mehr als Erfolgsfall ausgeben.

**Fertig wenn:** ein vorbereiteter Zieltrack startet kontrolliert und Cancellation funktioniert.

## Schritt 8: AudioClock-Modell

Snapshot, route ID, output format, isLoading, Underrun-Status und Confidence ergaenzen. Erst messen, dann Latenzkorrektur aktivieren.

**Fertig wenn:** jeder Transition-State einen Clock-/Confidence-Kontext hat.

## Schritt 9: Waveform- und Analyse-Cache erweitern

Analyzer-Profile, Multi-Resolution-Buckets, RMS/Onset/BPM optional, additive Room-Migration. Bestehende Waveform-Fallbacks erhalten.

**Fertig wenn:** Cache-Hit ohne Decoding funktioniert und Migration bestaetigt ist.

## Schritt 10: Compose-Waveform optimieren

Vorbereitete Geometry, Clip fuer Fortschritt, Drag-Commit, Marker-Layer. Keine Decoder-/DB-Aufrufe in Draw.

**Fertig wenn:** 60-fps-Scrubbing auf Testgeraet keine sichtbare Allokationslawine verursacht.

## Schritt 11: Ducking und Loudness

ReplayGain optional, Ducking zentral kombinieren, Limiter testen, keine Doppel-Ducking-Pfade.

**Fertig wenn:** Rest- und TTS-Duck zusammen deterministisch berechnet werden.

## Schritt 12: Reale Audioabnahme

Geraetematrix, lokale Ausgabe, Bluetooth, Rebuffer, Route-Wechsel, TTS, Haptik. Ergebnisse in Messprotokoll speichern.

**Fertig wenn:** Produkttext die tatsaechliche Genauigkeit ehrlich beschreibt.

---

# 20. Dateien nach Phase

## Domain

```text
domain/playback/src/main/kotlin/com/dropsync/domain/playback/PlaybackRepository.kt
domain/playback/src/main/kotlin/com/dropsync/domain/playback/PlaybackModels.kt
domain/playback/src/main/kotlin/com/dropsync/domain/playback/TransitionModels.kt
domain/timer/src/main/kotlin/com/dropsync/domain/timer/DropLanding.kt
domain/audio/src/main/kotlin/com/dropsync/domain/audio/TrackAnalyzer.kt
domain/audio/src/main/kotlin/com/dropsync/domain/audio/CrossfadeCurves.kt
```

## Playback/Data

```text
data/playback/src/main/kotlin/com/dropsync/data/playback/PlaybackRepositoryImpl.kt
data/playback/src/main/kotlin/com/dropsync/data/playback/PlaybackCommands.kt
data/playback/src/main/kotlin/com/dropsync/data/playback/PlaybackService.kt
data/playback/src/main/kotlin/com/dropsync/data/playback/CrossfadeController.kt
data/playback/src/main/kotlin/com/dropsync/data/playback/TransitionManager.kt
data/playback/src/main/kotlin/com/dropsync/data/playback/PlayerConnection.kt
```

## Audio/Data

```text
data/audio/src/main/kotlin/com/dropsync/data/audio/AudioPipeline.kt
data/audio/src/main/kotlin/com/dropsync/data/audio/DspRenderersFactory.kt
data/audio/src/main/kotlin/com/dropsync/data/audio/MasterDspProcessor.kt
data/audio/src/main/kotlin/com/dropsync/data/audio/TrackAnalyzerImpl.kt
data/audio/src/main/kotlin/com/dropsync/data/audio/TrackAnalysisRepositoryImpl.kt
data/audio/src/main/kotlin/com/dropsync/data/audio/OutputDeviceMonitor.kt
```

## Coordinator/UI/Database

```text
feature/player/src/main/kotlin/com/dropsync/feature/player/RestMusicCoordinator.kt
core/designsystem/src/main/kotlin/com/dropsync/core/designsystem/chart/Waveform.kt
core/database/src/main/kotlin/com/dropsync/core/database/entity/TrackAnalysisEntity.kt
core/database/src/main/kotlin/com/dropsync/core/database/dao/TrackAnalysisDao.kt
core/database/src/main/kotlin/com/dropsync/core/database/DropSyncDatabase.kt
core/database/src/main/kotlin/com/dropsync/core/database/Migrations.kt
```

---

# 21. Typische Fehler und Diagnose

## Fehler: Drop kommt zu spaet

Pruefen:

1. Wurde Target vor dem Execute vorbereitet?
2. Ist die Restzeit-Clock monotone?
3. Wurde `currentPosition` mit hoerbarer Position verwechselt?
4. Gab es Rebuffer oder Route-Wechsel?
5. Wurde der Fade erst am Drop-Zeitpunkt gestartet?
6. Hat der neue Track eine andere Sample-Rate?
7. Ist Bluetooth aktiv?

Nicht sofort den Offset um weitere 500 ms erhoehen. Erst die Messdaten und Confidence pruefen.

## Fehler: Knacksen beim Direct-Drop

Pruefen:

- wurde Gain in einem Schritt von 1 auf 0 gesetzt?
- wurde mitten in einem Audio-Buffer seeked?
- laeuft ein alter Fade-Job noch?
- wurde der Target-Player zweimal gestartet?
- wurde der AudioTrack geflusht oder neu erzeugt?

Massnahmen:

- kurze Gain-Rampe;
- genau ein aktiver Transition-Job;
- Target vor dem Execute vorbereiten;
- kein unnnoetiger `flush()` waehrend normalem Fade;
- Fehlerfall sauber auf Pause/Resume statt wiederholtes Seek.

## Fehler: Waveform flackert oder ist leer

Pruefen:

- wird `ANALYZER_VERSION` falsch verglichen?
- wird bei jeder Recomposition `requestAnalysis()` aufgerufen?
- sind `ByteArray` und `List` in Compose instabil?
- wird `Path` pro Draw neu erstellt?
- ist `bucket_count = 0` ein persistierter Fehlerfall?

## Fehler: TTS macht Drop ungenau

Das ist erwartbar, wenn TTS spontan synthetisiert wird. Vorgefertigte lokale Cue-Samples verwenden oder TTS weiter vom kritischen Drop entfernen. TTS darf nicht die einzige Go-Synchronisationsquelle sein.

## Fehler: Xiaomi beendet Wiedergabe

Pruefen:

- Foreground-Service korrekt deklariert?
- Notification-Kanal und laufende Notification vorhanden?
- Akku-Optimierung / Autostart-Anleitung?
- kein WorkManager fuer exakte Playback-Timer?
- Service startet Audio nur auf Nutzeraktion?
- Bluetooth-Reconnect und Controller-Verbindung robust?

Die App kann OEM-Kill-Verhalten nicht durch Kotlin-Code vollstaendig garantieren. Sie kann nur Lifecycle, Foreground-Service und Recovery sauber implementieren.

---

# 22. Was aus den Offtrack-/Poweramp-Befunden uebernommen werden darf

## Uebernehmbare Muster

Die vorherige Offtrack-Analyse liefert statische Evidenz, aber keinen proprietaeren Algorithmusnachbau. Direkt beobachtet wurden insbesondere: ein Transition-/Job-Aufrufpfad mit sichtbaren Parametern, asynchrone Ausfuehrung und Cancellation, ein nativer Aufruf mit nicht vollstaendig geklaerter Semantik sowie ein sichtbarer `MappedByteBuffer -> ByteBuffer -> AudioTrack.write()`-Pfad. Nicht direkt bewiesen sind die genaue Einheit der Werte, ein erfolgreicher vollstaendiger Native-Mix, die konkrete Automix-Heuristik oder sample-genaue Offtrack-Synchronisation. Die folgenden Punkte sind deshalb **Architektur-Analogien fuer DropSync**, keine Behauptungen ueber proprietaere interne Implementierung.

- getrennte Analyse- und Playback-Orchestrierung;
- Vorbereitung vor dem Uebergang;
- benoetigte Zeitwerte als explizite Planparameter;
- benannte Transition-Jobs mit Status und Cancellation;
- direkte Audioausgabe-/Buffer-Grenzen als eigene technische Schicht;
- lokale Analyse und Cache-Versionierung;
- mehrere Uebergangsprofile statt ein unbenannter Spezialfall;
- Ducking, Loudness und Output-Profile als kontrollierte Gain-Struktur;
- ehrliche Fallbacks bei fehlender Vorbereitung oder Route-Wechsel.

## Nicht als Tatsache behaupten

- dass ein beobachteter nativer Aufruf sicher einen vollstaendigen DJ-Mixer rendert;
- dass unbekannte `long`-Werte bestimmte Frame-/Sample-Einheiten haben;
- dass ein `MappedByteBuffer -> AudioTrack.write()` automatisch bedeutet, dass der komplette Mixpfad verstanden ist;
- dass Poweramp- oder Offtrack-interne proprietaere Algorithmen rekonstruiert wurden;
- dass ein Media3-Volume-Fade sample-exakt ist.

## Praktische Schlussfolgerung

Die wichtigste Offtrack-artige Eigenschaft ist nicht ein magischer Funktionsname. Es ist der kontrollierte Lebenszyklus:

```text
Analyse
 -> Kandidat
 -> Plan
 -> Vorbereitung
 -> Queue/Buffer
 -> Armed State
 -> Audio-Clock
 -> Transition
 -> Status/Fallback
```

Genau dieser Lebenszyklus fehlt im aktuellen DropSync-Pfad noch teilweise. Die vorhandenen Module sind jedoch passend geschnitten, um ihn einzubauen.

---

# 23. Definition of Done

Der Ausbau gilt erst als abgeschlossen, wenn alle Punkte wahr sind:

## Architektur

- [ ] `:domain:*` bleibt Media3-frei.
- [ ] `:feature:*` erzeugt keinen Player.
- [ ] Playback-Service bleibt einziger Besitzer der Audioausgabe.
- [ ] Transitionen haben IDs, Status und Cancellation.
- [ ] normaler Queue-Wechsel und Drop-Landung sind getrennte Modi.

## Playback

- [ ] Target kann vor dem Uebergang vorbereitet werden.
- [ ] `setMediaItem()+prepare()` im Handoff ist kein ungetesteter Default mehr.
- [ ] AudioFocus wird nicht doppelt von zwei Playern angefordert.
- [ ] Rebuffer und PlayerError werden in Transition-Status abgebildet.
- [ ] Route-Wechsel invalidiert oder bewertet den Plan neu.

## DropSync

- [ ] Drop-Landung basiert auf monotone Zeit.
- [ ] Marker sind bestaetigt und gueltig.
- [ ] Plan wird vor dem Timer-Ende vorbereitet.
- [ ] Skip/Pause/Seek beendet den alten Plan.
- [ ] Direct-Drop hat Klickschutz und Fallback.
- [ ] Genauigkeit wird mit Confidence angezeigt oder intern protokolliert.

## Waveform/Analyse

- [ ] Analyse laeuft im Hintergrund.
- [ ] Cache-Hit decodiert nicht erneut.
- [ ] Analyzer-Version invalidiert korrekt.
- [ ] Alte Waveform bleibt als UI-Fallback sichtbar.
- [ ] Canvas zeichnet voralloziertes Geometry-Modell.
- [ ] Scrubbing loest nicht bei jedem Pixel einen Seek aus.

## Qualitaet

- [ ] Domain-Unit-Tests gruen.
- [ ] Playback- und Coordinator-Race-Tests gruen.
- [ ] Room-Migration getestet.
- [ ] Architekturtest gruen.
- [ ] Debug-APK baut.
- [ ] mindestens ein lokaler Audioausgang und ein Bluetooth-Ausgang getestet.
- [ ] Ergebnisse und Grenzen dokumentiert.

---

# 24. Kurzfassung fuer eine schwache Coding-KI

Wenn nur wenige Regeln behalten werden koennen, dann diese:

1. Veraendere zuerst nur Vertrage und Tests.
2. Lass Media3 ausschliesslich in `:data:playback`.
3. Verwende keine WorkManager-Aufgabe fuer den exakten Drop-Zeitpunkt.
4. Bereite den Zieltrack frueh vor; starte ihn nicht erst nach `delay()`.
5. Gib jedem Transition-Plan eine eindeutige ID.
6. Pruefe die ID vor jedem Execute-Schritt.
7. Behandle `currentPosition` nicht als garantiert hoerbare Position.
8. Verwende `setMediaItems` fuer normale Queue-Wiedergabe statt jeden Titel neu zu `prepare()`n.
9. Entferne den aktuellen `mainPlayer.setMediaItem()`-Handoff nicht ohne Ersatztest; er ist eine bekannte Lueckenquelle.
10. Waveform-Analyse laeuft im Worker, Waveform-Rendering im Canvas, niemals umgekehrt.
11. Automatische Drops sind Vorschlaege und muessen bestaetigt werden.
12. Bei Bluetooth, Rebuffer oder Route-Wechsel ist `BEST_EFFORT` korrekt und keine Niederlage.
13. Erst messen, dann Latenzwerte hart in die Formel einbauen.
14. Ein echter sample-exakter Mixer ist ein spaeteres eigenes Projekt, nicht eine kleine Coroutine-Aenderung.
15. Nach jeder Phase: formatieren, betroffene Tests, Architekturtest und Debug-Build.

Damit wird aus dem aktuellen funktionalen Player schrittweise eine robuste, Offtrack-inspirierte Workout-Audioengine, ohne die bestehende Modularchitektur zu zerstoeren oder technische Genauigkeit zu versprechen, die Android auf allen Ausgabegeraeten nicht liefern kann.

---

# 25. Doku-Zusammenhang

Dieses Handbuch arbeitet mit dem Design-Dokument und dem UI-Handbuch zusammen:

| Dokument | Ort | Rolle |
|---|---|---|
| `FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md` | `docs/design/` (flowrep-main und DropSync) | Oberste Referenz, Produktentscheidungen |
| `FLOWREP_DROPSYNC_FUSION_FRAGEN_ANTWORTEN_2026-08-07.md` | `docs/design/` | Alle Entscheidungen im Detail |
| `WISSEN_POWERAMP_OFFTRACK_2026-08-07.md` | `docs/design/` | Konzepte aus oeffentlicher Doku |
| `UI_UX_UMBAUHANDBUCH_TRAIN_MUSIC_DROPSYNC.md` | Repo-Wurzel | UI-Ausarbeitung, setzt dasselbe Design um |

**Regel:** Wenn dieses Audio-Handbuch und das UI-Handbuch oder das Design-Dokument widersprechen, gewinnt das Design-Dokument. Produktentscheidungen des Nutzers (4 Tabs, keine Sessions, Piep-Toene, Marker Review in Music, ReplayGain als Einstellung) haben immer Vorrang.
