# FlowRep x DropSync — Verbesserungsplan

Stand: 03.09.2026. Grundlage ist eine vollstaendige Bestandsanalyse des
Repositorys: 395 Kotlin-Dateien (59.079 Zeilen, davon 285 Produktivdateien
mit 1,68 MB), 31 Gradle-Module, 89 Commits, Datenbank v10. Gelesen wurden
die Kerndateien in `:domain:audio`, `:data:audio`, `:data:playback`,
`:data:timer`, `:data:sensor`, `:core:database`, `:core:designsystem` und
`:app`; jeder Befund ist am Code mit Datei und Zeile belegt.

Zusaetzlich wurden die Qualitaetsgates real ausgefuehrt, nicht nur gelesen:
`spotlessCheck`, `detekt`, `lintDebug` (SARIF-Auswertung ueber alle 18
Module), `:data:audio:testDebugUnitTest`, `:domain:audio:test`,
`:app:assembleDebug` und `:app:generateBaselineProfile --dry-run`.

## Geltung und Verhaeltnis zu anderen Dokumenten

Dieses Dokument ist **Befundliste und Reihenfolge**, kein Architekturentwurf.
Es hebt keine Regel auf und ersetzt keinen ADR. Wo ein Befund eine
Architekturentscheidung beruehrt, ist der noetige ADR benannt.

Wichtig zur Namensgleichheit: `README.md` verweist an drei Stellen (Zeilen
42, 92, 141) auf einen "Verbesserungsplan Phase 4" bzw. "Phase 5" —
gemeint sind Baseline Profiles und die Health-Connect-Berechtigungs-UI.
Ein Dokument dieses Namens existiert im Repo **nicht**; die Verweise zeigen
ins Leere. Dieser Plan uebernimmt den Namen bewusst und fuehrt die alten
Phasennummern unter [B-DOC-5](#b-doc-5) als eigenen Befund, statt sie
stillschweigend zu recyceln. Die Phasen hier heissen deshalb **P0-P4**, nicht
"Phase 4/5".

Vorrang bei Widerspruch:

1. `docs/adr/*` — verbindliche Entscheidungen
2. `docs/design/FLOWREP_DROPSYNC_FUSION_DESIGN_2026-08-07.md`
3. dieser Plan
4. `README.md`-Statustabellen (siehe [B-DOC-4](#b-doc-4): teils falsch)

## Vorbemerkung: was dieses Projekt richtig macht

Die Befunde unten sind keine Anfaengerfehler. Sie sind fast durchweg Luecken
zwischen dem, was die Dokumentation behauptet, und dem, was der Code
garantiert. Der Kontext dazu, weil er die Bewertung des Rests bestimmt:

- **Die Modulgrenzen sind echt.** Grep ueber alle `domain/**` nach
  `androidx.room|androidx.media3|androidx.compose|android.` als Import:
  null Treffer. `:core:model` hat keine Projektabhaengigkeit, `:data:*`
  importiert kein Feature, kein Feature importiert ein anderes. Acht
  Domain-Module sind echte `kotlin.jvm`-Module ohne Android-Plugin.
- **Die Migrationskette ist exemplarisch.** Zehn Versionen, `1.json` bis
  `10.json` alle eingecheckt, jede Stufe gegen ihr exportiertes Schema
  getestet — und zwei Tests pruefen **Datenerhalt mit echten Nutzdaten**
  (`migration 8 auf 9 erhaelt vorhandene daten`, `migration 9 auf 10 trennt
  mix version ohne waveform zu verlieren`). Das lassen die meisten Projekte
  aus.
- **Der Datenschutzanspruch ist strukturell durchgesetzt.** Kein
  `INTERNET`-Permission in irgendeinem Manifest — "kein Netzwerkzugriff" ist
  damit garantiert, nicht zugesagt. Auto Backup vollstaendig aus
  (`allowBackup=false`, `fullBackupContent=false`, `data_extraction_rules.xml`
  mit `exclude` fuer alle fuenf Domains in **beiden** Kanaelen).
- **Die DSP-Mathematik ist Handwerk.** RBJ-Cookbook-Biquads mit
  Nyquist-Guard (`Biquad.kt:42`), transponierte Direktform II, Freeverb
  korrekt auf die Abtastrate skaliert, Equal-Power-Invariante als
  `sqrt(1 - g^2)` statt handgemalter Kurven (`MixPreset.kt:77`),
  `WAVE_DEPTH = 0.10 < 1/(2*PI)` mit Monotonie-Begruendung. 90 Tests in
  `:domain:audio`.
- **Compose-Performance ist verstanden.** `Waveform.kt` uebergibt
  `progressFraction` als **Lambda** (Zeile 263), damit der Aufrufer die
  Position nicht in seiner Composition liest; `rememberSmoothedFraction`
  liefert einen `State`, der nur im Zeichenblock gelesen wird (Zeilen
  207-224, mit Begruendung des vorher bestehenden Recomposition-Sturms);
  `toFlatBars` erzeugt ein flaches `FloatArray` statt Objektlisten.
- **Die CI ist ernsthaft.** `test` -> Doku-Link-Check -> `spotlessCheck` ->
  `lintDebug` -> `detekt` -> `assembleDebug` -> **`assembleRelease` mit R8**.
  Der Release-Gate ist der Schritt, den fast alle auslassen.
- **Die Doku korrigiert sich selbst.** `docs/Kritische Befunde.md` bekam
  einen Ist-Stand-Nachtrag mit 18 belegten Zeilen und der Begruendung "Jede
  Session, die hier oben einsteigt, arbeitete gegen Phantome". STATUS
  Abschnitt W entversionierte `ui-test/` (42 MB), bevor es in die History
  wanderte.

## Der wiederkehrende Fehlermodus

Ein Muster erklaert die Mehrzahl der schweren Befunde, und es ist wichtiger
als jeder Einzelfall:

> Die untere Schicht wird gruendlich gebaut und getestet, die letzte
> Verdrahtung fehlt, und die Statustabelle sagt "Abgeschlossen".

Belegte Faelle: `TempoSheet` (fertiges Sheet ohne Aufrufstelle — vom Projekt
selbst gefunden, STATUS Abschnitt X), `:feature:timer` (ganzes Modul ohne
Konsument, [B-ARCH-2](#b-arch-2)), `MixPreset` (sechs gepruefte Kurven ohne
Produktivkonsument, [B-AUD-5](#b-aud-5)), Bit-Perfect (Schalter ohne
Mixer-Aufruf, [B-AUD-4](#b-aud-4)), Health Connect (UI aktiv, Manifest leer,
[B-SEC-1](#b-sec-1)).

Gegenmittel in P2: ein Test, der prueft, dass jeder oeffentliche Vertrag in
`:domain:*` mindestens einen Aufrufer ausserhalb von Tests hat. Das faengt
diese Klasse Fehler systematisch statt einzeln.

---

## 0. Prozess-Befund: parallele Sessions auf demselben Repo

Waehrend dieser Analyse hat eine zweite Agent-Session am selben Arbeitsbaum
gearbeitet. Belege, weil der Befund die Arbeitsweise betrifft und nicht den
Code:

- `TrackAnalysisPriorityPathTest.kt` wurde mit 349 Zeilen gelesen, in denen
  drei Tests fehlschlugen. Ein Edit darauf scheiterte mit "oldString not
  found"; beim erneuten Lesen hatte die Datei 356 Zeilen, alle drei Faelle
  waren mit einem `advanceUntilIdle()` und einem fremden Kommentar gefixt.
- Zeitstempel 16:07:09 und 16:08:58 lagen nach dem gescheiterten Edit.
- `Get-Process` zeigte ~25 laufende `opencode`-Prozesse (08:14 bis 16:36).
- Zwischen Analysebeginn und Planerstellung entstanden drei Commits
  (`6646da5` In-Process-Prioritaetspfad, `a69c535` Queue-Prewarming,
  `27aa314` ADR-0015 plus README/STATUS-Nachtrag) — Arbeit an genau den
  Dateien, die dieser Plan bewertet. Der `ensureActive`-Fix zu
  [B-AUD-6](#b-aud-6) liegt zusaetzlich uncommitted im Arbeitsbaum.

Das ist woertlich das Problem aus `docs/STATUS_FORTSCHRITT.md` Abschnitt A1
("Grundursache: keine gemeinsame Koordinationsdatei"). Das Projekt hat dafuer
eine Regel — Zeile auf `[~] in Arbeit (Session: <Kennung>, <Datum>)` setzen —
und sie wurde in dieser Runde von keiner Seite befolgt.

**Konsequenz fuer diesen Plan:** Die Befunde sind gegen den Stand
`27aa314` (03.09.2026) nachgeprueft. Alle unten als offen markierten Punkte
wurden **nach** den drei neuen Commits erneut per Grep verifiziert.
Korrektur zur Erstfassung: Der `ensureActive`-Grep mit null Treffern lief,
bevor die Parallelsession ihren Fix (13:32 Uhr) in den Arbeitsbaum schrieb —
weder `a69c535` noch `27aa314` enthalten ihn (`git grep` gegen beide Commits
bestaetigt). Siehe [B-AUD-6](#b-aud-6).

**Regel fuer die Umsetzung:** Vor jedem Arbeitspaket die Statuszeile in
diesem Dokument auf `[~]` setzen, mit Session-Kennung und Datum. Zwei
Sessions gleichzeitig in `data/audio` verlieren Arbeit.

---

## 1. Was die letzten drei Commits bereits erledigt haben

Der Vollstaendigkeit wegen, damit dieser Plan nicht gegen Phantome arbeitet
(dieselbe Falle, die `docs/Kritische Befunde.md` beschreibt):

| Frueherer Befund | Stand |
|---|---|
| Arbeitsbaum rot: 3 Tests, Spotless gebrochen | **behoben** — 32/32 Tests gruen, `spotlessCheck` gruen (verifiziert) |
| `TrackAnalysisPersister.kt` untracked, HEAD nicht baubar | **behoben** — in `6646da5` eingecheckt, `git ls-files` bestaetigt |
| In-Process-Prioritaetspfad (Plan Phase 3) fehlt | **umgesetzt** — `6646da5`, mit `activeJobs`, `Semaphore(2)`, Cancel-und-Ueberholen |
| Queue-Prewarming (Plan Phase 4) fehlt | **umgesetzt** — `a69c535`, `requestAnalysisPrewarm` + `schedulePrewarmWaveform` |
| ADR-0015 zur Zwei-Stufen-Analyse fehlt | **committet** — `27aa314`, plus README-Statustabelle (Waveform-Performance) und STATUS-Abschnitt AC |

Ebenfalls erledigt: **`ensureActive()` im Decoder-Loop**
([B-AUD-6](#b-aud-6)) — Fix am Schleifenkopf im Arbeitsbaum (uncommitted),
Abbruchtest mit Gegenbeweis in dieser Session. Damit ist aus diesem
Abschnitt nichts mehr offen.

---

## 2. Befunde nach Schweregrad

Schweregrade: **KRITISCH** (Laufzeitdefekt oder Datenverlust moeglich),
**HOCH** (falsche Zusage an Nutzer oder Entwickler, oder strukturelles
Risiko), **MITTEL** (Wartbarkeit, Performance im Zielszenario),
**NIEDRIG** (Kosmetik, Aufraeumen).

Jeder Befund hat eine stabile ID, damit Commits und STATUS-Eintraege
darauf verweisen koennen, ohne Abschnittsnummern zu zitieren (dieselbe Regel,
die `tools/doku_links_check.py` fuer `docs/design/*` erzwingt).
### 2.1 Audio-Engine

<a name="b-aud-6"></a>
#### B-AUD-6 — KRITISCH — Decoder-Loop ohne Abbruchkooperation

**Status:** `[x]` behoben — Fix im Arbeitsbaum (uncommitted, Parallelsession),
Test in dieser Session (`TrackAnalyzerCancellationTest`, Gegenbeweis 45 vs 5 Buffer)

`TrackAnalyzerImpl.drainDecoder()` (`data/audio/.../TrackAnalyzerImpl.kt:185-258`)
ist eine `while (!outputDone)`-Schleife **ohne einen einzigen
Suspension-Punkt**. Kotlin-Coroutinen brechen kooperativ ab: ohne
Suspension-Punkt laeuft ein "abgebrochener" Lauf bis zum Trackende weiter.

Damit ist die Kernzusage des gerade gebauten Prioritaetspfads nicht
eingeloest. `TrackAnalysisRepositoryImpl.cancelOvertakenRunsLocked()` kuendigt
Jobs, deren Decoder trotzdem weiter CPU und eine MediaCodec-Instanz halten.
Bei `Semaphore(permits = 2)` blockieren zwei solche Zombies die Lane fuer
genau den Titel, den der Nutzer gerade ansieht — das Gegenteil der Absicht.

Der eigene Umbauplan fordert es explizit (Grundregeln, Zeile 92-93): "Jede
Decoder-Schleife prueft Abbruchkooperation (`ensureActive()` je
Output-Buffer)". Grep ueber `data/audio` nach `ensureActive`: null Treffer.

**Fix (umgesetzt, Position korrigiert gegenueber Erstfassung):**
`coroutineContext.ensureActive()` am Schleifenkopf von `drainDecoder()`
(`TrackAnalyzerImpl.kt:202`), nicht nach `releaseOutputBuffer`. Die
Erstfassung dieses Plans schlug die Position nach `releaseOutputBuffer` vor —
das war falsch: der `INFO_TRY_AGAIN_LATER`-Zweig springt per `continue`
zurueck und wuerde eine Pruefung weiter unten ueberspringen; ein auf Buffer
wartender Decoder waere weiterhin nicht abbrechbar. Zusaetzlich noetig (mit
umgesetzt): `decodeAndAccumulate` und `drainDecoder` sind jetzt `suspend`.

**Verifikation (umgesetzt):** `TrackAnalyzerCancellationTest` faehrt den
echten `TrackAnalyzerImpl` unter Robolectric (`ShadowMediaCodec` als
Passthrough-Decoder, `ShadowMediaExtractor` mit 2 s PCM). Der Abbruch kommt
aus dem Codec-Callback nach Buffer 5 — deterministisch, ohne Wartezeiten.
Gegenbeweis ohne Fix: 45 von 45 Buffern verarbeitet (Zombie bestaetigt); mit
Fix: exakt 5, danach `CancellationException`, keine normale Rueckkehr aus
`analyze()`. `:data:audio:testDebugUnitTest` 37/37 gruen, `spotlessCheck`
gruen.

<a name="b-aud-1"></a>
#### B-AUD-1 — KRITISCH — Allokationen im Audio-Callback

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode) — 3 Tests mit
Gegenbeweis, `:data:audio` 40/40 und `:domain:audio` 90/90 gruen

`MasterDspProcessor.queueInput()` (`data/audio/.../MasterDspProcessor.kt:129`)
laeuft auf dem Audio-Verarbeitungs-Thread des `DefaultAudioSink`. Sie ruft
`applyPendingConfig()` (Zeile 253), die unter Bedingungen `rebuildStages()`
(Zeile 267) aufruft. Diese allokiert:

| Allokation | Zeile | Groesse |
|---|---|---|
| `eqFilters` (32 Baender x Kanaele) | 268 | 64 Objekte |
| `bassFilters` / `trebleFilters` | 272-273 | 2 x Kanaele |
| `Freeverb(...)` | 276 | 8 Comb + 4 Allpass je Kanal, je `DoubleArray(~1600)` -> ca. 300 KB bei Stereo |
| `StreamingResampler(...)` | 287 | Historien-Arrays |
| `DitherGenerator(...)` | 291 | klein |

Ein GC-Stall auf dem Audio-Thread ist ein hoerbarer Underrun.

Der Ausloeser ist eng, aber real: `applyPendingConfig` ruft `rebuildStages`
bei `next.eq.bands.size != config.eq.bands.size || next.ditherMode !=
config.ditherMode` (Zeile 255-257). Bandanzahl aendert sich bei jedem
`applyEqPreset`, Dither bei jedem Umschalten in den Audio-Einstellungen. Ein
Nutzer, der Presets durchprobiert, trifft es zuverlaessig.

Zusaetzlich: `onFlush()` (Zeile 122) ruft `rebuildStages()` bei **jedem Seek
und Titelwechsel** — auf einem Waveform-Drag also in dichter Folge.

**Fix (umgesetzt):**
1. Stufen einmal in `onConfigure` fuer die Maximalkonfiguration
   (`EqSettings.MAX_BANDS` = 32) allokiert; `activeBandCount` sagt, wie viele
   davon rechnen. `rebuildStages()` wird nur noch aus `onConfigure` gerufen —
   Media3 ruft das beim Aufbau der Kette, nicht im Audiothread.
2. `Freeverb.reset()` ergaenzt: nullt Comb-/Allpass-Puffer und die
   Leseindizes statt neu zu allokieren. `StreamingResampler.reset()` ebenso
   fuer Historie und Leseposition.
3. `onFlush` ruft nur noch `resetStageState()` — die vorhandenen, nie
   aufgerufenen `BiquadFilter.reset()` plus die beiden neuen.
4. `DitherGenerator.mode` ist jetzt `var` (verwirft beim Wechsel die
   SHAPED-Fehlerrueckfuehrung, wie ein frischer Generator). Ein
   Dither-Umschalten allokiert damit nichts mehr.

**Zusaetzlich mit erledigt: [B-AUD-2](#b-aud-2).** `StreamingResampler.process`
allokierte pro Block ein `Array(channelCount) { DoubleArray(totalFrames) }` —
bei 48 kHz mehrere Hundert Allokationen pro Sekunde, direkt im Audiothread.
Der Arbeitspuffer wird jetzt wiederverwendet und nur bei Bedarf vergroessert
(eigenes `workFrames`-Feld statt `work[0].size`, weil `channelCount` 0 sein
kann).

**Verifikation (umgesetzt):** drei Faelle in `MasterDspProcessorTest`, alle
ueber Referenzidentitaet der Stufenobjekte (`stageIdentitiesForTest()`) —
bleibt dieselbe Instanz aktiv, hat niemand allokiert:

- `preset wechsel mit anderer bandanzahl allokiert keine filter` (10 -> 31
  Baender, der Hauptausloeser), prueft zusaetzlich, dass die neue Bandanzahl
  wirksam ist.
- `dither umschaltung allokiert keinen neuen generator` (TPDF -> SHAPED).
- `flush setzt zustand zurueck ohne neu zu allokieren` — plus die
  Wirkungspruefung: nach `flush` Stille rein, Spitze < 1e-6 raus.

**Drei Gegenbeweise gefahren** (alle zurueckgerollt): `onFlush` wieder auf
`rebuildStages` → Flush-Test rot. Bandwechsel wieder auf `rebuildStages` →
Preset-Test rot. `Freeverb.reset()` als No-op → Flush-Test rot mit Spitze
0,084 statt 0.

**Dabei einen zu schwachen Test korrigiert.** Der Flush-Test lief zuerst mit
480 Frames — und blieb mit dem No-op-Reverb **gruen**. Grund: der laengste
Freeverb-Kammfilter ist auf 48 kHz rund 1.760 Samples lang; bei 480 Frames
laeuft der Leseindex nie ueber die beschriebenen Stellen, der Nachhall ist
also noch gar nicht hoerbar. Erst mit 4.000 Frames greift der Test. Ein
Reset-Test, der kuerzer als die Verzoegerungsleitung ist, prueft nichts.

`DspPerformanceTest` (32 Baender, > 2x Echtzeit, keine NaN/Inf) bleibt gruen
und ist weiter der Durchsatz-Waechter.

<a name="b-aud-2"></a>
#### B-AUD-2 — HOCH — Resampler allokiert pro Block

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode) — gemeinsam mit
[B-AUD-1](#b-aud-1) umgesetzt, `:domain:audio` 90/90 gruen

`StreamingResampler.process()` (`domain/audio/.../StreamingResampler.kt:60`):
`val work = Array(channelCount) { DoubleArray(totalFrames) }`. Bei 48 kHz und
1.024-Frame-Bloecken sind das ca. 94 Allokationen pro Sekunde, jede 2 x 8 KB —
im Audio-Pfad.

`MasterDspProcessor` macht es fuer `samples`/`resampled` schon richtig
(Zeilen 73-74, 133-135, 159-161): Feld halten, bei Bedarf wachsen lassen.
Dasselbe Muster hier anwenden.

**Verifikation (umgesetzt):** Die bestehenden `StreamingResamplerTest`-Faelle
liefern unveraenderte Ausgaben — 90/90 in `:domain:audio` gruen, keine
Toleranz angepasst. Genau das war die Bedingung: der Puffer wird
wiederverwendet, aber vor jedem Block vollstaendig mit Historie und neuem
Eingang ueberschrieben, also kann kein Rest des Vorblocks einfliessen.

Ergaenzt wurde ausserdem `reset()` (fuer [B-AUD-1](#b-aud-1)): nullt Historie
und Leseposition ohne Neuallokation, damit `onFlush` keinen Kontext des alten
Titels in die Interpolation traegt.

<a name="b-aud-3"></a>
#### B-AUD-3 — MITTEL — Race auf `restDuckCurrent`

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode)

Die Thread-Uebergabe im `MasterDspProcessor` ist ueberwiegend korrekt
durchdacht: `duckingGain` und `restDuckingGain` sind `@Volatile` (Zeilen 50,
56), `pendingConfig` ist `AtomicReference` (Zeile 43), `config` wird nur auf
dem Audio-Thread geschrieben.

Der Rest-Zweifel liegt in `AudioPipeline`: `restDuckCurrent` (Zeile 125) ist
ein nicht-synchronisiertes `var`, das `setRestDuckDb` liest und `rampDuck`
(Zeile 152) aus einer Coroutine auf `Dispatchers.Default` schreibt. Bei zwei
schnell folgenden `setRestDuckDb`-Aufrufen kann `from` ein halb
geschriebener Wert sein.

**Fix (umgesetzt, weiter als geplant).** Der Plan nannte `@Volatile` als
ausreichend — das ist es nicht. `@Volatile` macht den Wert sichtbar, loest
aber nicht das eigentliche Problem: `restDuckJob?.cancel()` **wartet nicht**
auf das Ende der alten Rampe. Die gekuendigte Coroutine kann noch einen
Zwischenwert schreiben, nachdem die neue ihren Startwert gelesen hat; der
Endwert haengt dann am Scheduling. Umgesetzt sind deshalb drei Dinge:

- `restDuckCurrent` ist eine `AtomicReference<Double>`,
- `setRestDuckDb` haelt die vorige `Job`-Referenz und ruft
  `previous?.cancelAndJoin()` **in** der neuen Coroutine, wartet also
  wirklich ab,
- ein `Mutex` um die Rampe, damit sich zwei Rampen nicht ueberlappen koennen.

Praktisch harmlos bleibt es (eine Ducking-Rampe von 40-160 ms), aber die
Reihenfolge ist jetzt definiert statt wahrscheinlich.

**Verifikation:** `:data:audio` 40/40 gruen. Ein Test, der die Race
zuverlaessig ausloest, fehlt — `AudioPipeline` hat keine Testabdeckung
(`git ls-files` findet keinen `AudioPipelineTest`), und ein Timing-Test auf
`Dispatchers.Default` waere flaky. Das ist eine bewusste Luecke: der Fix ist
durch Konstruktion korrekt, nicht durch Messung belegt.

<a name="b-aud-4"></a>
#### B-AUD-4 — HOCH — Bit-Perfect erreicht den Mixer nie

**Status:** `[x]` erledigt (Weg b, Nutzerentscheidung 2026-09-11)

README Schritt 19 und ADR-0009 fuehren Bit-Perfect als "Abgeschlossen".
Verifiziert (auch nach `a69c535`):

- `BitPerfectGateway` liest `getSupportedMixerAttributes()`
  (`data/audio/.../BitPerfectGateway.kt:63`) — **nur lesend**.
- `setPreferredMixerAttributes` / `clearPreferredMixerAttributes`: Grep ueber
  `data/audio` und `data/playback` -> **null Treffer**. Ohne diesen Aufruf
  gibt es keinen Bit-Perfect-Pfad; die AudioMixerAttributes-API existiert
  genau dafuer.
- `PlaybackService.kt:114` setzt `floatOutput = false` **hart**, unabhaengig
  von `config.bitPerfectEnabled`. Der Parameter in `DspRenderersFactory`
  (Zeile 27) wird nie aus der Konfiguration gespeist.
- Real wirksam ist nur `AudioPipeline.apply()` (Zeile 180): der DSP-Bypass.

Ergebnis: Bit-Perfect umgeht die DSP-Kette, erreicht aber nie den
Systemmixer. Fuer einen Audiophilen-Anspruch ist das die Haelfte.

**Zwei zulaessige Wege:**
- (a) Implementieren: `setPreferredMixerAttributes` beim Aktivieren,
  `clearPreferredMixerAttributes` beim Deaktivieren, `floatOutput` aus der
  Konfiguration speisen, Service-Neustart bei Aenderung. Braucht einen
  Nachtrag zu ADR-0009 (der beschreibt den Modus, nicht den Aufruf).
- (b) Status ehrlich machen: README auf "DSP-Bypass umgesetzt, Mixer-Pfad
  offen" korrigieren und das UI-Panel entsprechend beschriften.

Nicht zulaessig: den Status so stehen lassen.

**Umgesetzt (Weg b):** `audio_bitperfect_desc` (DE/EN) verspricht keine
Mixer-Ausgabe mehr ("umgeht derzeit nur die DSP-Kette; bitgenaue
Mixer-Ausgabe ist noch nicht umgesetzt"). Der Schalter bleibt funktional —
er schaltet den funktionierenden DSP-Bypass — aber ohne falsche Zusage.

<a name="b-aud-5"></a>
#### B-AUD-5 — HOCH — Sechs Mix-Presets ohne Konsument

**Status:** `[x]` erledigt (Weg b, Nutzerentscheidung 2026-09-11)

`MixPreset` liefert sechs mathematisch gepruefte Volume-Kurven
(`MixPresetTest`, `CrossfadeCurvesTest`), `DspConfig.crossfadeSeconds` und
`.mixPreset` sind persistiert, die UI zeigt sechs Chips mit Erklaertexten
(`SettingsScreen.kt:644`) und einen Dauer-Slider 1-12 s. Verifiziert:

- `fadeInGain` / `fadeOutGain`: aufgerufen nur in `MixPreset.kt` selbst und
  in Tests. **Kein Produktivkonsument.**
- `crossfadeSeconds`: gelesen nur zum Persistieren, Anzeigen und zum
  Deaktivieren des Bit-Perfect-Panels.
- Grep `crossfade` in `data/playback` + `data/audio`: nur Kommentare und
  DataStore-Keys.

Erklaerbar ist es: README Schritt 18 dokumentiert, dass der
`CrossfadeController` bei der ADR-Konsolidierung entfernt wurde und
Uebergaenge als harter Wechsel laufen. Aber: die Einstellung ist bedienbar
und tut **nichts**. Gegenueber dem Nutzer ist das eine Falschaussage.

**Zwei zulaessige Wege:**
- (a) Konsument bauen: Volume-Rampe nach `MixPreset` um den harten Wechsel in
  `PlaybackRepositoryImpl.playSongAt` / `ACTION_PLAY_SONG_AT`. Das ist kein
  Dual-Player und braucht keinen neuen ADR, nur einen Nachtrag zu ADR-0007.
- (b) Panel ausgrauen mit sichtbarem Hinweis "derzeit ohne Wirkung", README
  korrigieren.

**Umgesetzt (Weg b):** beide Bedienflaechen ausgegraut —
`MixTransitionsSection` (Schalter, 6 Chips, Regler) in `:feature:settings`
und `CrossfadeSection` (Regler) in `:feature:audio` — jeweils mit sichtbarem
Hinweis (`settings_mix_no_effect`, `audio_crossfade_no_effect`, DE/EN).
Stil und Dauer bleiben persistiert, damit sie greifen, sobald der Konsument
existiert. README Schritt 18 + Mix Phase 2/3 entsprechend korrigiert.

<a name="b-aud-7"></a>
#### B-AUD-7 — MITTEL — Analyse haelt Fensterlisten fuer den ganzen Track

**Status:** `[ ]` offen

`EnergyAccumulator.windows` (`domain/audio/.../TrackAnalysisMath.kt:115`) ist
`mutableListOf<Double>()` — ein Fenster je 25 ms, als **geboxte** `Double`.
Ein 10-Minuten-Track ergibt 24.000 Boxen (ca. 600 KB nur fuer die Liste).
Bei `AnalysisProfile.FULL` laufen drei `EnergyAccumulator` parallel
(`EnergyAccumulator` direkt, plus je einer in `TempoAccumulator` Zeile 27 und
`LoudnessAccumulator` Zeile 160). Fuer einen 60-Minuten-DJ-Mix wird das
relevant.

**Fix:** `DoubleArray` mit Wachstumsstrategie; das relative Gate in
`integratedLufs()` (Zeile 184-186) braucht die sortierten Werte, laesst sich
aber auch mit einem Streaming-Perzentil loesen.

**Nebenbefund** (NIEDRIG): `MixAnalysis.kt:112` deckelt
`MAX_CANDIDATES = 1_000`. Bei sehr dichten Onsets wird das
Tempo-Histogramm auf die ersten 1.000 Intervalle beschraenkt, was das
Ergebnis vom Trackanfang dominieren laesst. Entweder dokumentieren oder
gleichmaessig sampeln.

<a name="b-aud-8"></a>
#### B-AUD-8 — NIEDRIG — Deprecated Media3-API

**Status:** `[ ]` offen (bereits in STATUS Abschnitt W notiert)

`DspRenderersFactory.kt:44` nutzt `setEnableAudioTrackPlaybackParams`, in
Media3 deprecated. Mit 1.10.1 kein Bruch, aber Kandidat fuers naechste
Media3-Update.
### 2.2 Sicherheit, Datenschutz, Release

<a name="b-sec-1"></a>
#### B-SEC-1 — KRITISCH — Health-Connect-Permission fehlt im Manifest, Feature ist aktiv

**Status:** `[x]` umgesetzt (03.09.2026, Session: OpenCode) — Stufe 1
gewaehlt (Manifest bauen, nicht Badge abschalten):
`data/health/src/main/AndroidManifest.xml` mit `READ_HEART_RATE`,
`HealthRationaleActivity` + `activity-alias` in `:app` mit beiden
Intent-Filtern, Rationale-Texte in `values{,-de}`. Im Merger-Report
nachgewiesen. Geraeteabnahme des Dialogs bleibt offen.

`:data:health` hat **kein** `AndroidManifest.xml`. Verifiziert: das Projekt
hat genau sieben Manifeste (`app`, `benchmarks`, `data/audio`,
`data/library`, `data/playback`, `data/sensor`, `data/timer`) —
`data/health` ist nicht darunter.
`android.permission.health.READ_HEART_RATE` existiert nur als
Kotlin-Konstante (`HealthConnectHeartRateSource.kt:120`) und im Fake
(`FakeHeartRateSource.kt:24`).

Gleichzeitig ist der Feature-Code produktiv verdrahtet:

| Stelle | Zeile |
|---|---|
| `TrainViewModel` injiziert `HeartRateSource` + Permission-Contract | 82-84 |
| `TrainScreen` registriert `rememberLauncherForActivityResult` | 112-114 |
| `TrainScreen` ruft `launcher.launch(viewModel.heartRatePermissions)` | 257 |
| `HeartRateBadge` zeigt den Zustand `PERMISSION_REQUIRED` | 742 |

Health Connect verweigert ohne Manifest-Deklaration die Anfrage. Der Nutzer
tippt auf "Puls erlauben" und es passiert nichts oder es gibt eine Exception.

Zusaetzlich fehlt die von Health Connect **vorgeschriebene**
Rationale-Deklaration, die der eigene Plan
(`HERZFREQUENZ_HEALTH_CONNECT_PLAN.md`, Abschnitt 7, Zeilen 250-256) korrekt
beschreibt: eine Activity mit Intent-Filter
`androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android <= 13) **und**
`android.intent.action.VIEW_PERMISSION_USAGE` + Kategorie
`android.intent.category.HEALTH_PERMISSIONS` (Android 14+). Ohne sie lehnt
Health Connect die Berechtigung ab.

Der Widerspruch in der Doku macht es schlimmer: der Plan fuehrt Phase 2 als
"Offen", der README als "Abgeschlossen (Train-Tab)" — und der Code ist aktiv.

**Fix, zwei Stufen:**
1. **Sofort:** `data/health/src/main/AndroidManifest.xml` mit der Permission
   anlegen; Rationale-Activity (oder Activity-Alias auf `MainActivity` mit
   Deep-Link auf die Datenschutzseite) im `:app`-Manifest. `POST_NOTIFICATIONS`
   in `:data:timer` ist das Muster, dem man folgen kann.
2. **Falls (1) nicht in dieser Runde passt:** den Badge-Aufruf in
   `TrainScreen` hinter ein Flag legen, damit der Nutzer keinen toten Knopf
   sieht. Ein sichtbarer Knopf ohne Wirkung ist schlechter als kein Knopf.

**Verifikation:** Manifest-Merger-Report (`app/build/outputs/logs/`) muss die
Permission enthalten; auf dem Geraet einmal den Dialog durchlaufen.

<a name="b-sec-2"></a>
#### B-SEC-2 — MITTEL — Kein `signingConfig`, Release unsigniert

**Status:** `[ ]` offen (Release-Blocker fuer Schritt 14)

`app/build.gradle.kts:47-55` hat `isMinifyEnabled`, `isShrinkResources` und
`proguardFiles`, aber keinen `signingConfig`. `assembleRelease` erzeugt ein
unsigniertes APK. Fuer den CI-Regressionstest genuegt das und ist so
dokumentiert (STATUS Abschnitt W); fuer eine Auslieferung nicht.

**Fix:** Keystore ausserhalb des Repos, Pfad und Passwoerter ueber
`~/.gradle/gradle.properties` oder Umgebungsvariablen. `.gitignore` deckt
`*.jks` und `*.keystore` bereits ab (Zeilen 19-20) — die Vorarbeit ist da.

<a name="b-sec-3"></a>
#### B-SEC-3 — MITTEL — Keine Dependency-Verification

**Status:** `[ ]` offen

`gradle/verification-metadata.xml` existiert nicht. Fuer eine App mit dem
Anspruch "vollstaendig offline, ohne Konto, ohne Analytics" ist die
Lieferkette der einzige verbleibende Vertrauenspunkt — und sie ist
unverifiziert. Jede der 30 Abhaengigkeiten im Katalog kommt ohne
Checksummenpruefung.

**Fix:** `./gradlew --write-verification-metadata sha256 help`, Ergebnis
pruefen und einchecken. Danach bricht jeder unerwartete Artefaktwechsel den
Build — was der Punkt ist.

<a name="b-sec-4"></a>
#### B-SEC-4 — NIEDRIG — Lizenzinventar driftet, Poppins-Verweis zeigt auf Raleway

**Status:** `[ ]` offen

Drei Einzelbefunde in `THIRD_PARTY_NOTICES.md`:

1. **Versionsdrift:** Notices sagen KSP `2.3.10` (Zeile 15),
   `libs.versions.toml:13` sagt `2.3.11`. Bei einer Datei, deren Aenderung
   laut eigener Aussage "ohne Review CI blockiert" (Zeile 5), ist die Drift
   selbst der Befund: es gibt keinen automatischen Abgleich zwischen Katalog
   und Inventar.
2. **Falscher Lizenzverweis:** Zeile 47 verweist fuer die **Poppins**-Lizenz
   auf `UI/Raleway/OFL.txt`. Der Poppins-OFL liegt in `UI/Poppins/OFL.txt`
   und wird nirgends referenziert. Ein Lizenznachweis, der auf die
   Lizenzdatei einer anderen Schrift zeigt, ist im Zweifel keiner.
3. **3,3 MB unbenutzte Fonts:** `UI/Raleway/` enthaelt 20 TTF-Dateien, 18
   davon getrackt. Grep nach `raleway` ueber alle `.kt`/`.xml`: **null
   Treffer**. Die App nutzt Poppins (`Type.kt:21-24`, `R.font.poppins_*`).

**Fix:** Skript im CI, das Katalogversionen gegen die Notices-Tabelle
vergleicht (ca. 20 Zeilen Python, analog `tools/doku_links_check.py`);
Poppins-Verweis korrigieren; Raleway entfernen.

<a name="b-sec-5"></a>
#### B-SEC-5 — NIEDRIG — `ExportedService` ohne Permission (dokumentieren)

**Status:** `[ ]` offen

Lint meldet fuer `data/playback/src/main/AndroidManifest.xml`: "Exported
service does not require permission". Fuer einen `MediaLibraryService`, der
von Android Auto und Bluetooth-Controllern gefunden werden muss, ist
`exported="true"` **korrekt und noetig**. Der Zugriffsschutz sitzt richtig
an anderer Stelle: `LibrarySessionCallback.isOwnPackage()`
(`PlaybackService.kt:292`) gibt die internen Kommandos nur dem eigenen
Package frei, fremde Controller bekommen `ERROR_PERMISSION_DENIED`
(Zeilen 323, 340).

**Fix:** Kein Codefix. Einen Kommentar ins Manifest, der die Absicht
festhaelt, damit der Lint-Befund beim naechsten Review nicht als offene Frage
gelesen wird.

<a name="b-sec-6"></a>
#### B-SEC-6 — NIEDRIG — FFmpeg-Lizenzstatus OFFEN

**Status:** `[ ]` offen (blockiert Schritt 14, nicht diesen Plan)

`THIRD_PARTY_NOTICES.md:38` fuehrt FFmpeg als `OFFEN`. `libs/` existiert
nicht (verifiziert), `dropsync.enableFfmpeg=false`. Die LGPL-Bedingungen
(dynamisches Linken, keine GPL-Bauteile, Quellcode-Bereitstellung) sind in
Zeilen 51-61 korrekt beschrieben. Solange kein Artefakt existiert, ist der
Status richtig.

### 2.3 Datenschicht

<a name="b-db-1"></a>
#### B-DB-1 — HOCH — `songs` hat keinen einzigen Index

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode) — Migration v10 -> v11,
6 Indizes, 2 Tests mit Gegenbeweis, 17/17 in `:core:database` gruen

Aus `10.json` verifiziert: `songs PK=media_store_id, Indizes=-`. Gegen diese
Tabelle laufen:

| Query | Datei:Zeile | Ungestuetzt |
|---|---|---|
| Alben-Aggregat | LibraryBrowseDaos:63 | `GROUP BY album` |
| Kuenstler-Aggregat | :72 | `GROUP BY artist` |
| Genre-Aggregat | :78 | `GROUP BY genre` |
| Ordner-Aggregat | :86 | `GROUP BY relative_path` |
| Songs eines Albums | :92 | `WHERE album = ?` |
| Songs eines Kuenstlers | :97 | `WHERE artist = ?` |
| Songs eines Genres | :102 | `WHERE genre = ?` |
| Songs eines Ordners | :107 | `WHERE relative_path = ?` |
| Kuerzlich hinzugefuegt | :113 | `ORDER BY date_modified_seconds DESC` |
| Alle verfuegbaren | LibraryDaos:36 | `WHERE is_available = 1 ORDER BY title` |

Jede ist ein Full-Table-Scan plus Sortierung. Bei 200 Titeln unmerklich, bei
einer 5.000-Titel-Bibliothek — dem Zielnutzer dieser App — jedes Mal.

Zusaetzlich: `song_markers` hat keinen Index auf `source_fingerprint`,
obwohl `LibraryDaos.kt:75` genau darauf sucht und der Onset-Import je
Kandidat schreibt (`TrackAnalysisWorker.writeOnsetCandidates`).

**Fix (umgesetzt, Indexschnitt gegenueber der Erstfassung geaendert):**
Migration v10 -> v11, rein additiv. Die Erstfassung schlug sieben
Einzelspalten-Indizes vor; umgesetzt sind sechs **zusammengesetzte** mit
`is_available` als erster Spalte:

```
CREATE INDEX index_songs_is_available_album ON songs(is_available, album)
CREATE INDEX index_songs_is_available_artist ON songs(is_available, artist)
CREATE INDEX index_songs_is_available_genre ON songs(is_available, genre)
CREATE INDEX index_songs_is_available_relative_path ON songs(is_available, relative_path)
CREATE INDEX index_songs_is_available_date_modified_seconds ON songs(is_available, date_modified_seconds)
CREATE INDEX index_song_markers_source_fingerprint ON song_markers(source_fingerprint)
```

**Warum anders als geplant.** Jede der zehn Browse-Queries filtert
`is_available = 1` **und** gruppiert oder sucht dann ueber genau eine Spalte
(`LibraryBrowseDaos.kt:64-113`). Ein Index nur auf `album` haette SQLite
gezwungen, jede gefundene Zeile zusaetzlich auf `is_available` zu pruefen; der
zusammengesetzte Index bedient `GROUP BY album` und `WHERE album = ?` mit
demselben Eintrag und liest unverfuegbare Titel gar nicht.

Der geplante `index_songs_is_available_title` ist **weggelassen**:
`observeAvailable()` sortiert `ORDER BY title COLLATE NOCASE`, und Rooms
`@Index` kennt keinen Kollations-Parameter. Ein BINARY-Index bedient eine
NOCASE-Sortierung nicht — er waere reine Schreiblast bei jedem
Bibliotheksscan. Der Grund steht als Kommentar an `SongEntity`, damit ihn
niemand "vergessen" nachtraegt.

Die `@Entity`-Annotationen tragen dieselben `indices`-Eintraege; `11.json`
wurde von Room generiert und ist eingecheckt.

**Verifikation (umgesetzt):** zwei Tests in `MigrationTest`.

1. `migration 10 auf 11 erhaelt die bibliothek und legt indizes an` schreibt
   einen Song und einen Marker in v10, migriert und prueft beide Zeilen plus
   alle sechs Indexnamen in `sqlite_master`.
2. `browse-queries benutzen die neuen indizes statt zu scannen` fuellt 500
   Titel per rekursiver CTE, faehrt `ANALYZE` und prueft per
   `EXPLAIN QUERY PLAN`, dass fuenf Queries und das `GROUP BY album`-Aggregat
   den jeweiligen Index nennen. Der Test enthaelt eine **Gegenprobe** auf
   `known_sha256` (nicht indexiert, muss `SCAN` ergeben) — ohne die koennte er
   gruen sein, weil `EXPLAIN` in Robolectric gar nicht unterscheidet.

**Gegenbeweis gefahren** (beide danach zurueckgerollt): Index auf `album`
statt `(is_available, album)` — `runMigrationsAndValidate` bricht mit
"Migration didn't properly handle: songs" ab. Index nach der Migration
gedroppt — der Plan-Test meldet `SCAN TABLE songs`. Die Tests haengen also
wirklich am Index, nicht am Zufall.

Offen bleibt die Messung auf einer echten 5.000-Titel-Bibliothek; der
Query-Plan ist der Nachweis, dass der Index greift, nicht wie viel er bringt.

Gedeckt und **nicht** betroffen (Primaerschluessel ist die Suchspalte):
`play_stats`, `favorites`, `track_analysis`, `exercise_targets`,
`exercise_rest_prefs`. Korrekt indexiert: `flat_sets`, `set_clusters`,
`playlist_items`, `marker_song_links`, `cue_tracks`, `saf_files`.

<a name="b-db-2"></a>
#### B-DB-2 — NIEDRIG — Waisen im Analyse-Cache

**Status:** `[ ]` offen

`TrackAnalysisEntity` hat bewusst keinen Fremdschluessel auf `songs` — der
Kommentar begruendet es korrekt ("damit ein Bibliotheks-Rescan den
Analyse-Cache nicht mitreisst"). Konsequenz: loescht der Nutzer 3.000 Titel,
bleiben 3.000 Waveform-BLOBs fuer immer. `deleteOlderThanVersion` existiert
(`TrackAnalysisDao.kt:53`), ein Waisen-Aufraeumer nicht.

**Fix:** Im Scan-Pfad nach `markMissingAsUnavailable` ein
`DELETE FROM track_analysis WHERE song_id NOT IN (SELECT media_store_id FROM songs)`.
Bewusst gegen `songs`, nicht gegen `is_available` — ein temporaer nicht
verfuegbarer Titel (SD-Karte entfernt) soll seine Waveform behalten.

<a name="b-db-3"></a>
#### B-DB-3 — NIEDRIG — Doppelte Vollabfrage im Scan-Skip-Pfad

**Status:** `[ ]` offen

`LibraryRepositoryImpl.refreshLibrary`: im unveraenderten Fall wird
`songDao.getAllOnce().size` gerufen — die ganze Tabelle wird geladen, um sie
zu zaehlen. `COUNT(*)` genuegt. Betrifft jeden App-Start.

<a name="b-db-4"></a>
#### B-DB-4 — NIEDRIG — FTS4 statt FTS5 (Entscheidung dokumentieren)

**Status:** `[ ]` offen

`SongFtsEntity` nutzt `@Fts4` (`LibraryStatsEntities.kt:118`). FTS5 ist seit
SQLite 3.9 / API 24 verfuegbar (ueber minSdk 26) und bietet `bm25`-Ranking.
Room hat keine `@Fts5`-Annotation, das waere eine manuelle Tabelle — die
Entscheidung fuer FTS4 ist damit vertretbar, steht aber nirgends.

**Fix:** Einen Satz in den Entity-Kommentar. Kein Codefix.
### 2.4 Architektur und Build

<a name="b-arch-1"></a>
#### B-ARCH-1 — HOCH — Der Architekturtest prueft Build-Dateien, nicht Code

**Status:** `[~]` Importpruefung erledigt (04.09.2026, Session: OpenCode),
Konsumententest **abgelehnt** — Begruendung unten

`core/testing/.../ModuleDependencyRulesTest.kt:30-42` liest
`build.gradle.kts` als **Text** und sucht verbotene Tokens. Das findet nur,
was jemand explizit als Gradle-Abhaengigkeit deklariert. Es findet nicht:

- **Transitive Leaks ueber `api(...)`.** `core/database/build.gradle.kts:52`
  deklariert `api(libs.androidx.room.runtime)`. Jedes Modul mit
  `implementation(project(":core:database"))` sieht damit Room. Heute sind
  das nur `data/*` — die Regel ist aber ungeschuetzt.
  `:core:designsystem` gibt Compose per `api(...)` weiter (Zeilen 43-47), fuer
  ein Designsystem korrekt, aber derselbe blinde Fleck.
- **Verstoesse auf Import-Ebene.** Ein `import androidx.room.*` in einem
  Domain-Modul, das Room transitiv sieht, laeuft durch.

Der Test ist ein Deklarations-Linter und verkauft sich als Architekturtest.
Die Regeln, die er schuetzen soll, sind heute eingehalten — das habe ich per
Grep bestaetigt. Der Punkt ist die fehlende Absicherung fuer morgen.

**Fix (umgesetzt):** Der Test prueft jetzt **zwei** Ebenen. Neu sind fuenf
Faelle, die alle `src/main/**/*.kt` je Modul einlesen und die
`import`-Zeilen gegen die Verbotsliste halten:
`core model importiert kein anderes app modul`,
`domain module importieren kein android room oder media3`,
`feature module importieren weder room noch media3 noch andere features`,
`data module importieren keine feature module` — plus
`die importpruefung liest wirklich quelldateien`. 10/10 gruen.

Der Selbsttest ist kein Beiwerk: ohne ihn waeren alle vier Regeltests auch
dann gruen, wenn `productionImports()` nichts liefert — etwa nach einer
geaenderten Pfadkonvention. Er verlangt >20 Imports in `domain/audio`, die
Pruefung muss also nachweislich Dateien gelesen haben.

Beim Feature-Test ist das eigene Paket erlaubt (`allow`-Praedikat); verboten
sind nur fremde `com.dropsync.feature.*`-Importe.

**Gegenbeweise** (beide zurueckgerollt): `import androidx.room.Entity` in
`domain/audio` → neuer Test rot **und der alte Deklarationstest gruen**. Das
ist der blinde Fleck, schwarz auf weiss. Cross-Feature-Import
`progress → workout` → neuer Test rot mit Datei und Importzeile.

**Zusatz aus der Erstfassung — abgelehnt.** Der geplante zweite Test ("jeder
oeffentliche Vertrag in `:domain:*` hat mindestens einen Aufrufer ausserhalb
der Tests") wurde gemessen, bevor er gebaut wurde: **67 von 227**
oeffentlichen Domain-Funktionen haetten ihn sofort rot gemacht. Ein Test, der
beim Einschalten 67 Treffer liefert, landet in einer Baseline und ist damit
Dekoration.

Entscheidend ist aber nicht die Zahl, sondern dass die Treffer ueberwiegend
**korrekt** sind. `domain/sensor` liefert allein 41, weil
`ExerciseEnginePipeline` von `ActiveSetController` benutzt wird — beide in
`domain/sensor`. Ein Kriterium "Aufrufer ausserhalb des Moduls" meldet jede
saubere Innenstruktur als Leiche. Verschaerft man es auf "ausserhalb der
eigenen Datei", bleiben 30 Treffer — dann faellt aber genau der Zielfall
`MixPreset.fadeInGain` heraus, weil `CrossfadeCurves` ihn aufruft. Beide
Zuschnitte treffen also nicht, was sie treffen sollen.

Die Befundidee bleibt richtig, das Kriterium taugt nicht. Wer es spaeter
nochmal versucht, braucht Aufrufgraph-Analyse (Bytecode), nicht Textsuche —
und muss dann gegen die Alpha-Vermeidungsregel abwaegen.

**Nebenbefund der Messung:** `feature/timer` hat **null** Code-Konsumenten.
`app/build.gradle.kts:93` deklariert das Modul, aber kein `app`-Quelltext
importiert daraus. Damit ist [B-ARCH-2](#b-arch-2) auf Import-Ebene belegt,
nicht nur ueber die Gradle-Deklaration.

<a name="b-arch-2"></a>
#### B-ARCH-2 — MITTEL — `:feature:timer` ist toter Code

**Status:** `[x]` erledigt (Verdrahten, Nutzerentscheidung 2026-09-11)

`feature/timer/` (367 Zeilen: `TimerSection.kt` 283, `TimerViewModel.kt` 84)
wird von **niemandem** referenziert. Grep ueber alle `.kt` ausserhalb des
Moduls nach `com.dropsync.feature.timer`: null Treffer. `TimerSection`
erscheint nur in seiner eigenen Definition.

Trotzdem steht das Modul in `settings.gradle.kts:90`, in den Abhaengigkeiten
von `:app` (`app/build.gradle.kts:93`), als Pflichtmodul im Architekturtest
(`ModuleDependencyRulesTest.kt:163`) und wird bei jedem Build kompiliert.

Nebenbefund: `TimerSection.kt:116` hat den hartcodierten deutschen String
`"TIMER STARTEN"` — er wuerde bei Aktivierung sofort die i18n-Regel brechen
(siehe [B-UI-3](#b-ui-3)).

**Entscheidung noetig, kein Fix ohne sie.** Der Rest-Timer laeuft heute
vollstaendig ueber `:feature:workout` (`TrainScreen` + `TrainViewModel`) und
`:feature:player` (`DropRestViewModel`). Entweder das Modul entfernen (dann
auch aus `settings.gradle.kts`, `:app` und dem Architekturtest) oder eine
Route dafuer vorsehen. Ein Modul, das seit Monaten kompiliert wird und
niemanden erreicht, ist die dritte Instanz desselben Musters.

**Umgesetzt (Verdrahten):** neuer `TimerScreen` (TopAppBar + `TimerSection`)
in `:feature:timer`, Route `timer` im App-NavHost (kein fuenfter Haupt-Tab),
Einstieg per Tap auf die Countdown-Anzeige der Train-Pausenkonsole
(`TrainScreen.onOpenTimer`, `train_rest_open_timer` DE/EN, TalkBack nennt die
Aktion per `onClickLabel`, die Zeitansage bleibt). Beide Oberflaechen
treiben dieselbe geteilte `TimerEngine` — der Zustand bleibt konsistent,
egal wo gesteuert wird. Der hartcodierte `"TIMER STARTEN"`-Teil des Befunds
war bereits durch B-UI-3 erledigt (`timer_start` in DE/EN).

<a name="b-arch-3"></a>
#### B-ARCH-3 — HOCH — Windows-Pfad einer Entwicklermaschine im Produktivmodul

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode) — Block entfernt,
32/32 Tests in `:feature:workout` gruen, auch mit `--rerun-tasks`

`feature/workout/build.gradle.kts:44-55`:

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", "C:\\dev\\jbr17\\bin")
    environment("PATH", "C:\\dev\\jbr17\\bin;C:\\Windows\\System32;C:\\Windows")
}
```

Ein absoluter Windows-Pfad einer einzelnen Maschine, hart in einem
versionierten Build-Script. Auf der Linux-CI laeuft der Block mit: dort
existiert `C:\dev\jbr17\bin` nicht, und `PATH` wird auf drei nicht
existierende Verzeichnisse gesetzt. Dass die CI gruen ist, heisst nur, dass
die JVM den unsinnigen `java.library.path` toleriert und der Test-Worker
keine native Bibliothek laedt. Das ist Glueck, nicht Korrektheit.

`settings.gradle.kts:8-11` loest dasselbe Problem sauber: plattformbedingt
und mit `System.getenv("SystemRoot")` statt hartem Pfad.

**Fix (umgesetzt):** Block ersatzlos entfernt. `settings.gradle.kts` genuegt
allein — das war die zu pruefende Annahme, und sie stimmt: 32/32 Tests in
`:feature:workout` gruen, auch mit `--rerun-tasks` (also mit echten
Test-Worker-JVMs statt Cache). Der lokale `PATH` dieser Maschine enthaelt
Leerzeichen, der Fall ist also nicht wegdefiniert, sondern trifft real ein.

**Gegenbeweis:** `settings.gradle.kts` Zeile 8 auf `if (false)` gesetzt, um zu
sehen, ob der Workaround dort ueberhaupt traegt — der Testlauf brach nicht
sofort ab, sondern lief in die 15-Minuten-Grenze dieser Sitzung. Damit ist
nicht bewiesen, dass die Deaktivierung harmlos waere; die Datei wurde
unveraendert zurueckgesetzt (`git diff` leer). Was bewiesen ist: der
**modulspezifische** Block ist entbehrlich.

Der Block war zusaetzlich als einziger im Repo — kein anderes Modul braucht
ihn, was gegen eine echte Notwendigkeit sprach.

<a name="b-arch-4"></a>
#### B-ARCH-4 — MITTEL — 29x dasselbe Build-Boilerplate, kein Convention-Plugin

**Status:** `[~]` Sofort-Teilfix erledigt (Detekt-Abdeckung), Convention-Plugin offen

Jedes der 11 Android-Library-Module wiederholt identisch: die
`compileSdk`-`.get().toInt()`-Kette, die `minSdk`-Kette, `compileOptions`
mit `VERSION_17`, `testInstrumentationRunner`, `buildFeatures { compose =
true }`. Jedes JVM-Modul wiederholt `java {}` plus
`kotlin { compilerOptions { jvmTarget } }`. Ca. 25 Zeilen x 29 Module.

Die Folgekosten sind konkret, nicht aesthetisch:

- **`feature/progress/src` fehlte in der Detekt-Quellenliste**
  (`build.gradle.kts:47-77`). Ein Feature mit 1.668 Produktivzeilen und einem
  1.026-Zeilen-Screen lief ohne statische Analyse. Bei einem
  Convention-Plugin waere das strukturell unmoeglich.
  Korrektur zur Erstfassung: es war **nicht** das einzige fehlende Modul.
  Der Abgleich `settings.gradle.kts` gegen die Detekt-Liste (32 Includes vs
  29 Quellen) zeigt drei Luecken — `feature/progress`, `benchmarks` und
  `training-core`. Die Erstfassung hat nur die erste gefunden, weil sie die
  Liste gegen die `feature/`-Module geprueft hat statt gegen alle Includes.
- Die Detekt-Liste muss bei jedem neuen Modul manuell gepflegt werden — ein
  Fehler, der still bleibt.

**Fix:** `build-logic`-Verzeichnis mit `dropsync.android.library`,
`dropsync.android.feature`, `dropsync.jvm`. Detekt-Quellen daraus ableiten
statt aufzulisten.

**Sofort-Teilfix (erledigt):** `"feature/progress/src"` und
`"benchmarks/src"` eingetragen, Findings gesichtet und behoben statt in die
Baseline geschoben. `feature/progress` brachte zwei echte Treffer, beide in
`StatementTile` (`CyclomaticComplexMethod` 24 > 20, `LongMethod` 135 > 120):
aufgeteilt in `statementRingState()`, `statementDistanceText()` und
`rememberCelebrationFlash()` — reine Extraktion, kein Verhalten geaendert,
42/42 Tests gruen. `training-core` bleibt bewusst **draussen**: es ist ein
Git-Submodul auf `v1.0.0` gepinnt, seine zwei `VariableNaming`-Findings
(`private val DAY`) sind nur im eigenen Repo behebbar. Der Grund steht als
Kommentar in `build.gradle.kts`, damit die Auslassung nicht wieder als
Versehen gelesen wird. Detekt laeuft jetzt ueber 386 statt 384 Dateien, 0
Findings, Baseline unveraendert bei 23.

<a name="b-arch-5"></a>
#### B-ARCH-5 — MITTEL — `:app` enthaelt UI-Fachlogik

**Status:** `[ ]` offen

README behauptet fuer `:app`: "Navigation + Hilt-Verdrahtung, keine
Fachlogik". `DropSyncApp.kt` hat 488 Zeilen, davon ca. 120 fuer
`FlowRepGlassNavigation` (Zeilen 329-449): eigene Pill-Navigation mit
`BoxWithConstraints`, Feder-Physik-Indikator, Icon-Scale-Animation,
Semantics.

Die Komponente gehoert nach `:core:designsystem`; `:app` sollte sie
konfigurieren. Detekt hat
`MatchingDeclarationName:DropSyncApp.kt:TopLevelDestination` in der Baseline —
Symptom derselben Sache.

Zwei Einzelbefunde in derselben Datei:
- Zeile 405: hartcodierte deutsche Strings (siehe [B-UI-3](#b-ui-3)) —
  TalkBack-relevant.
- Zeile 369: `Modifier.offset(x = indicatorOffset)` mit State-Wert; Lint
  meldet `UseOfNonLambdaOffsetOverload`. Die Lambda-Variante verschiebt den
  State-Read in die Layout-Phase und vermeidet Recomposition — dieselbe
  Technik, die `Waveform.kt` bereits vorbildlich anwendet.

**Ausdruecklich gut geloest und nicht anzufassen:** der P0-Fix in
`DropSyncApp.kt:153-162`, der das `PlayerViewModel` **einmal** am
Activity-Owner aufloest und an Mini-Player (ausserhalb NavHost) und
Now-Playing (innerhalb) durchreicht. Die Begruendung im Kommentar ist
praezise richtig.

<a name="b-arch-6"></a>
#### B-ARCH-6 — MITTEL — `training-core`-Submodule ohne Konsistenzpruefung

**Status:** `[ ]` offen

`settings.gradle.kts:45-50` bricht mit lesbarer Meldung ab, wenn das
Submodule fehlt — sauber geloest. Zwei Restrisiken:

1. `training-core/build.gradle.kts` setzt bewusst
   `apiVersion/languageVersion = KOTLIN_2_0`, waehrend Fusion mit 2.4.10
   baut (CONTEXT Punkt 2, begruendet). Es gibt keinen Test, der bricht, wenn
   jemand die Sprachstufe anhebt und damit die Flowtimer-Seite ausschliesst.
2. Das Submodule steht auf `v1.0.0` (`76ad2452`). Es gibt keine Pruefung,
   dass der eingecheckte Commit dem Tag entspricht — ein abweichender Stand
   nach `git submodule update` faellt still durch.

**Fix:** Beides als Assertion in `ModuleDependencyRulesTest` (Sprachstufe aus
der Build-Datei lesen; Submodule-SHA gegen eine erwartete Datei pruefen) oder
als CI-Schritt.

### 2.5 UI, Tests, Lokalisierung

<a name="b-ui-1"></a>
#### B-UI-1 — HOCH — Vier Module ohne einen einzigen Test

**Status:** `[x]` alle vier Module haben Tests (Session: OpenCode)

Gezaehlt ueber `@Test`-Vorkommen in `src/test` und `src/androidTest`:

| Modul | Produktivzeilen | Tests |
|---|---:|---:|
| `feature/settings` | 1.327 | **6** (war 0) |
| `feature/audio` | 910 | **6** (war 0) |
| `feature/timer` | 367 | **6** (war 0) |
| `data/settings` | 110 | **7** (war 0) |

`feature/settings` und `feature/audio` haben beide ein ViewModel mit
Zustandslogik (`SettingsViewModel` 451 Zeilen, `AudioSettingsViewModel`) —
zusammen ca. 2.200 ungetestete Zeilen. `feature/audio` hat nicht einmal ein
`src/test`-Verzeichnis.

Zum Vergleich, damit klar ist, dass die Testkultur existiert und diese vier
Module nur nicht erreicht hat: `domain/sensor` 115 Tests, `domain/audio` 90,
`domain/timer` 56, `data/sensor` 53, `training-core` 46, `feature/progress` 42.

**Fix (`feature/settings` umgesetzt):** `SettingsViewModelTest` mit sechs
Faellen plus `SettingsFakes.kt` (acht Fakes). Geprueft wird, was hier wirklich
Logik ist: die Mix-Setter lesen die aktuelle Konfiguration und rechnen —
Einschalten setzt `DEFAULT_MIX_SECONDS`, Ausschalten 0, die Dauer wird auf
`1..CrossfadeCurves.MAX_SECONDS` geklemmt, das Preset aendert die Dauer
**nicht** mit, Ducking wird auf `REST_DUCK_MIN_DB..MAX_DB` geklemmt. Ein
sechster Fall prueft, dass jeder Weiterleitungs-Setter in **sein eigenes**
Repository schreibt.

Zwei Entscheidungen dabei:

- **Fakes modul-lokal**, nicht in `:core:testing`. Sonst muesste
  `:core:testing` `:domain:library` und `:domain:audio` allein wegen der Fakes
  kennen. Ausnahme waere sinnvoll, wenn ein zweites Modul sie braucht — heute
  keins.
- **Eigener `RecordingWorkoutGoalRepository`** statt
  `core.testing.FakeWorkoutGoalRepository`: der dortige verwirft den
  geschriebenen Wert (`= Unit`), damit waere "Setter schreibt ins richtige
  Repository" nicht pruefbar. `:core:testing` blieb unangetastet, weil dort
  parallel gearbeitet wird.

**Gegenbeweise** (beide zurueckgerollt): Klemmung `coerceIn(1, MAX_SECONDS)`
entfernt → Klemm-Test rot. `setThemeMode` schreibt nichts mehr → Setter-Test
rot. Die Tests haengen also an der Logik, nicht am Zufall.

**Bewusste Luecke:** `importFrom`/`exportTo` sind nicht abgedeckt. Sie lesen
und schreiben echte SAF-Dokumente ueber den `ContentResolver`; das ist ein
eigenes Paket mit eigenen Fixtures, kein Nebenprodukt dieses Tests. Damit
bleiben von 1.327 Zeilen die beiden I/O-Pfade ungetestet — der Rest der
Zustandslogik ist es nicht mehr.

**`AudioSettingsViewModel`** (6 Tests plus modul-lokale Fakes, ohne
Robolectric): DSP-Setter schreiben die gelesene Konfiguration zurueck,
31-gegen-10-Baender beweist den Bandvertrag. Dabei zwei echte Bugs gefixt:
`update()` las `dspConfig.value` (WhileSubscribed-Falle — Gegenbeweis: alte
Lesart 2 Tests rot), und eine parallele Aenderung hatte `setGraphicBandCount`
auf 10 Baender festgenagelt (Test rot, zweimal wiederhergestellt).

**`feature/timer`** (`TimerViewModelTest`, 6 Tests gegen die echte
`TimerEngine`): Start/Dauer, Zweitstart-Ignoranz, Pause/Resume,
Cancel-mit-`stopAll`-Nachweis plus Neustart, Ticker-Abschluss mit
`acknowledgeFinished`. Dabei ein Produktionsbug gefixt: `getReady` lief mit
`WhileSubscribed` ohne jeden Abonnenten — `startRest` las ewig den Startwert,
der B9-Vorlauf war tot (Gegenbeweis: Get-Ready-Test rot vor dem Fix auf
`Eagerly`).

**`data/settings`** (7 Robolectric-Tests nach dem
`DataStoreCalibrationProfileRepositoryTest`-Muster, sdk=33): Roundtrip,
Ueberschreiben, Defaults (SYSTEM/LIME) und der dokumentierte Fallback
unbekannter Rohwerte — gesetzt ueber denselben DataStore-Namen, daher ohne
zweite Datei sichtbar.

`feature/player` (PlayerViewModelTest u. a.) und `feature/workout`
(TrainViewModelTest, PlausibilityTest, CalibrationViewModelTest) waren bereits
abgedeckt und laufen gruen (35 bzw. 34 Tests). Damit ist B-UI-1 erledigt;
offen bleibt nur die bewusste I/O-Luecke bei Settings-Im-/Export.

<a name="b-ui-2"></a>
#### B-UI-2 — MITTEL — Fuenf Composables ueber der Groessengrenze

**Status:** `[ ]` offen (in der Detekt-Baseline bewusst akzeptiert)

Groesste Dateien: `ProgressDashboardScreen.kt` 1.026, `TrainScreen.kt` 945,
`Waveform.kt` 935, `NowPlayingScreen.kt` 888, `SettingsScreen.kt` 876.
Die Baseline haelt den Ist-Zustand fest (`detekt.yml:6-10`, begruendet).

Das ist vertretbar, solange die Baseline nicht waechst. Seit
[B-UI-1](#b-ui-1) erledigt ist, bleibt bei `feature/settings` nur noch die
Groesse (1.327 Zeilen in zwei Dateien) — die Tests (6) sind da.

**Fix:** Kein Big-Bang. Beim naechsten Anlass an diesen Screens jeweils einen
Abschnitt in eine eigene `internal`-Composable ziehen und den
Baseline-Eintrag entfernen. Der Baseline-Zaehler ist damit die Metrik.

<a name="b-ui-3"></a>
#### B-UI-3 — MITTEL — Drei hartcodierte deutsche Strings

**Status:** `[x]` umgesetzt (03.09.2026, Session: OpenCode) — alle drei in
Ressourcen ueberfuehrt: `nav_state_selected`/`nav_state_not_selected`
(`:app`), `settings_screen_title` (`:feature:settings`), `timer_start`
(`:feature:timer`), jeweils zweisprachig.

| Datei:Zeile | String | Wirkung |
|---|---|---|
| `DropSyncApp.kt:405` | `"Ausgewählt"` / `"Nicht ausgewählt"` | **TalkBack-Ansage** der Hauptnavigation |
| `SettingsScreen.kt:352` | `"Einstellungen"` | Sichtbarer Titel |
| `TimerSection.kt:116` | `"TIMER STARTEN"` | Schaltflaeche (Modul derzeit unerreichbar) |

Der erste ist der ernste: er macht die Zustandsansage der Navigation
sprachfest. Bei 20 sauber gepflegten `values`/`values-de`-Paaren (Anzahl je
Paar geprueft und identisch: 73/73, 128/128, 69/69, 49/49, 81/81, 179/179,
12/12, 7/7, 5/5) sind das die einzigen Ausreisser.

<a name="b-ui-4"></a>
#### B-UI-4 — NIEDRIG — 50 Lint-Warnungen, davon 7 in Nutzertexten

**Status:** `[~]` teilweise (03.09.2026, Session: OpenCode) — die 7 `Typos`
sind weg, 43 von 50 Warnungen bleiben. Ausgefuehrt: 117 Zeilen in acht
`values-de`-Dateien auf echte Umlaute umgestellt (nicht nur die 7 gemeldeten
Stellen — der Rest derselben Klasse war nur nicht als Tippfehler erkennbar);
2 x `in Folge` mit `tools:ignore="Typos"` und Begruendung behalten, weil
Lints Vorschlag `infolge` ("wegen") den Satz umdrehen wuerde. Offen:
25 `PluralsCandidate`, 5 `UseKtx`, 4 `TypographyFractions`,
2 `TypographyDashes`, Rest Fehlalarme bzw. bewusst.

| Regel | Anzahl | Bewertung |
|---|---:|---|
| `PluralsCandidate` | 25 | `%d` + Wort statt `<plurals>` -> "1 Wiederholungen" |
| `Typos` | **7** | in `values-de`: "fuer", "Schliessen", "abschliessen" (2x), "Groesse", "in Folge" (2x) |
| `UseKtx` | 5 | `Uri.parse` -> `String.toUri` |
| `TypographyFractions` | 4 | "1/4" -> "¼" |
| `InlinedApi` | 2 | korrekt guarded, Fehlalarm |
| `TypographyDashes` | 2 | Bindestrich -> Halbgeviertstrich |
| `ExportedService` | 1 | korrekt, siehe [B-SEC-5](#b-sec-5) |
| `ModifierParameter` | 1 | `LibraryLists.kt` |
| `ObsoleteSdkInt` | 1 | `mipmap-anydpi-v26` bei minSdk 26 |
| `SwitchIntDef` | 1 | `OutputDeviceMonitor`, bewusst unvollstaendig |
| `UseOfNonLambdaOffsetOverload` | 1 | siehe [B-ARCH-5](#b-arch-5) |

Die 7 `Typos` sind der wichtigste Teil: das ist die ASCII-Konvention des
Projekts, die in **nutzersichtbaren Strings** gelandet ist. Kommentare und
Doku duerfen ASCII bleiben; `values-de/strings.xml` nicht — dort steht es auf
dem Bildschirm.

**Nachtrag 03.09.2026:** Der Befund hat den Umfang unterschaetzt. Lint
meldet nur Woerter aus seinem Wortschatz; die Konvention war aber in
**117 Zeilen** angewandt, quer durch acht Module — `Uebung`, `Zurueck`,
`Saetze`, `laeuft`, `Groesse`, `Lautstaerke` und rund 80 weitere. Fuenf
Dateien mischten dabei innerhalb derselben Datei beide Schreibweisen
(`feature/workout/values-de` hatte `Uebung` in Zeile 9 und `Übung` in Zeile
106). Nur die 7 gemeldeten Stellen zu reparieren haette die Inkonsistenz
festgeschrieben, statt sie zu beheben.

Alle 50 werden gemeldet und die CI ist gruen — Lint bricht also nur bei
Fehlern ab, nicht bei Warnungen. Vertretbar, heisst aber: niemand repariert
sie, solange niemand den Report oeffnet.

**Fix:** Die 7 Typos und die 4 Bruchzahlen sofort (Textaenderung). Die 25
Plurale in einem eigenen Commit je Modul. Danach fuer die reparierten Regeln
`lintOptions.error += ...` setzen, damit sie nicht zurueckkommen.

<a name="b-ui-5"></a>
#### B-UI-5 — MITTEL — Baseline-Profile-Task ohne Generator

**Status:** `[x]` umgesetzt (04.09.2026, Session: OpenCode) — der Befund
nannte zwei Ursachen, es waren fuenf. Alle behoben, siehe Nachtrag unten.

`:app` und `:benchmarks` haben beide das `androidx.baselineprofile`-Plugin,
`:app:generateBaselineProfile` existiert (verifiziert per `--dry-run`:
`mergeReleaseBaselineProfile SKIPPED`, `copyReleaseBaselineProfileIntoSrc
SKIPPED`). Aber: Grep nach `BaselineProfileRule` -> **null Treffer**. Es gibt
nur `StartupBenchmark` (Messung), keinen Generator.
`app/src/release/generated/baselineProfiles/` ist leer und untracked.

Der Task laeuft und produziert nichts. README Zeile 42 fuehrt die
Infrastruktur trotzdem als "vorhanden".

Zweiter Befund in derselben Datei: `StartupBenchmark.kt:30` misst
`packageName = "com.dropsync.app.debug"`. Macrobenchmark verlangt
`debuggable=false`; die Benchmark-Variante (`benchmarkRelease`) hat keinen
`applicationIdSuffix`, heisst also `com.dropsync.app`. Der Benchmark zielt auf
ein Paket, das im Benchmark-Build nicht existiert.

Dritter Punkt: `libs.versions.toml:43` haelt `benchmark = "1.5.0-alpha01"`.
Zusammen mit `detekt = "2.0.0-alpha.6"` sind zwei Alphas im Katalog — in
einem Projekt, dessen Regel "keine Alpha-Abhaengigkeiten" lautet (README,
Musik-UI-Abschnitt: "material3 1.5.0-alpha verworfen"). Beide sind begruendet
(AGP-9-Kompatibilitaet), aber die Regel ist damit weicher als sie klingt.

**Fix:** `BaselineProfileGenerator` in `:benchmarks` anlegen (Standardmuster:
`BaselineProfileRule` + `collect(packageName) { startActivityAndWait() }`),
`packageName` im Benchmark korrigieren, und die Alpha-Ausnahmen im README als
solche benennen.

**Nachtrag 04.09.2026 — der Befund nannte zwei Ursachen, es waren fuenf.**
Beim Umsetzen kam heraus, dass `:benchmarks` seit `cb7efda`
**gar nicht kompilierte**. Der Reihe nach, weil jede Ursache die naechste
verdeckte:

1. **Producer nie verdrahtet.** `baselineProfile(project(":benchmarks"))`
   fehlte in `app/build.gradle.kts`. Ohne diese Zeile kennt
   `:app:generateBaselineProfile` das erzeugende Modul nicht; `merge` und
   `copy` liefen auf leerer Eingabe. Das war der eigentliche Grund fuer die
   zwei `SKIPPED`-Tasks im `--dry-run` des Befunds — nicht der fehlende
   Generator.
2. **Generator fehlte** (wie im Befund) — `BaselineProfileGenerator.kt`
   angelegt, mit `includeInStartupProfile = true`.
3. **`junit4` und `androidx.test.ext.junit` fehlten** in
   `benchmarks/build.gradle.kts`. Beide wurden in `cb7efda` beim Modulumbau
   entfernt; seither schlug jeder Kompilierversuch mit `Unresolved reference
   'AndroidJUnit4'` fehl.
4. **`CompilationMode.BaselineProfile()` existiert nicht mehr.** Die API gab
   es in benchmark 1.0; mit `1.5.0-alpha01` ist der Ersatz
   `CompilationMode.Partial(baselineProfileMode = ...)`. `StartupBenchmark.kt`
   hat mit der aktuellen Version also nie kompiliert.
5. **`kotlin.compose` lag auf `:benchmarks`**, obwohl das Modul keine
   Composables enthaelt. Der Compose-Compiler verlangt die Compose-Runtime
   auf dem Classpath, die dort nicht ankommt — Abbruch mit "requires the
   Compose Runtime to be on the class path".

**Warum es niemand gemerkt hat, und das ist der eigentliche Befund:** die CI
baut `com.android.test`-Module nicht. `test`, `assembleDebug` und
`assembleRelease` fassen `:benchmarks` nicht an. Ein Modul ohne
Compiler-Abdeckung verrottet still — hier ueber mindestens zwei Commits.
Gegenmittel waere `:benchmarks:assembleBenchmarkRelease` als CI-Schritt
(baut nur, laeuft nicht — braucht kein Geraet). Als eigener Punkt in P2
gefuehrt, weil es die CI-Datei anfasst.

**Verifiziert:** alle drei Varianten kompilieren
(`compileNonMinifiedBenchmarkKotlin`, `compileBenchmarkBenchmarkKotlin`,
`compileBenchmarkReleaseKotlin`), und die Taskkette enthaelt jetzt
`:benchmarks:collectNonMinifiedReleaseBaselineProfile` plus
`:benchmarks:connectedNonMinifiedReleaseAndroidTest` vor
`:app:mergeReleaseBaselineProfile`. Der Generatorlauf selbst braucht ein
Geraet (Root oder API 33+, Emulator mit `aosp`-Abbild) und bleibt
Geraeteabnahme.

<a name="b-ui-6"></a>
#### B-UI-6 — MITTEL — Kein Instrumentierungs-Gate in der CI

**Status:** `[ ]` offen

Die CI fuehrt `connectedAndroidTest` nicht aus (kein Emulator im Workflow)
und `:benchmarks` nie. Die vier `androidTest`-Dateien in `:app` (AudioFocus,
AudioTimestamp, BLE-Scan, 5 `@Test`) laufen nur manuell.

Bei einem Projekt, dessen offene Punkte fast alle "braucht Geraet" heissen —
Schritt 13 komplett, TalkBack-Abnahme, 200-%-Schrift, USB-DAC-Bit-Perfect,
BT-Codec, Waveform-Zielwert 1,5 s — ist ein Emulator-Job der groesste
einzelne Hebel im ganzen Plan.

**Fix:** `reactivecircus/android-emulator-runner` mit API 34,
`:app:connectedDebugAndroidTest`, zunaechst `continue-on-error: true` fuer
eine Woche, danach als Pflicht-Gate. Das loest nicht die TalkBack-Abnahme
(die braucht echte Hardware), aber alles Uebrige.
### 2.6 Dokumentation

<a name="b-doc-1"></a>
#### B-DOC-1 — HOCH — Der verbindliche Bauplan existiert nicht im Repo

**Status:** `[x]` erledigt (Weg b, Nutzerentscheidung "Regeln extrahieren", 2026-09-11)

`README.md:9-11`: "Grundlage ist der verbindliche technische Bauplan
(`DropSync-Technischer-Bauplan.md`, Stand 27.07.2026). Abweichungen vom
Bauplan sind nur ueber ADRs in `docs/adr/` erlaubt."

Die Datei existiert nicht (per Suche verifiziert). Der gesamte README, alle
19 ADRs und praktisch jeder Codekommentar referenzieren "Bauplan Schritt
3.2", "Regel 3.2/2", "Schritt 5.6" — auf ein Dokument, das niemand lesen
kann.

Das ist die teuerste Doku-Schuld im Projekt: die normative Quelle **aller**
Architekturregeln fehlt. Ein neuer Mitarbeiter oder eine neue Agent-Session
kann "Regel 3.2/2" nicht nachlesen, nur aus Zitaten rekonstruieren.

**Zwei zulaessige Wege:**
- (a) Dokument einchecken (falls es ausserhalb des Repos existiert).
- (b) Die tatsaechlich zitierten Regeln in einen Abschnitt
  `docs/ARCHITEKTURREGELN.md` extrahieren, die Nummerierung beibehalten
  (`3.2/1` bis `3.2/4` usw.) und alle Verweise darauf umbiegen. Der
  Architekturtest (`ModuleDependencyRulesTest`) zitiert die Regeln bereits
  einzeln — er ist die beste vorhandene Rekonstruktionsquelle.

Nicht zulaessig: den Verweis stehen lassen.

**Umgesetzt (Weg b):** `docs/ARCHITEKTURREGELN.md` — alle tatsaechlich
zitierten Regeln mit Original-Nummerierung (3.2/1–3.2/4 aus dem
Architekturtest als normativer Anker, Bauplan-Schritte aus den Code-Zitaten
mit Belegstelle; was nur dem Namen nach zitiert wird, steht auch nur dem
Namen nach). README-Kopf verweist jetzt auf die Rekonstruktion statt auf das
fehlende Original.

<a name="b-doc-2"></a>
#### B-DOC-2 — MITTEL — `Kritische Befunde.md` bleibt zweideutig

**Status:** `[ ]` offen

Die Ist-Stand-Tabelle im Kopf ist gute Arbeit (18 Zeilen, jede mit Beleg).
Darunter stehen aber noch ca. 40 KB Befundtext im **Praesens**
("`chosenSignal` wird anschliessend nicht gespeichert"), der laut Tabelle
behoben ist. Der Kopf sagt, die Tabelle habe Vorrang — aber niemand liest
43 KB, um das zu erfahren.

**Fix:** Befundteil nach `docs/archive/2026-08-12-review-befunde.md`
verschieben. Im Original bleiben: Ist-Stand-Tabelle, die zwei echten
Restpunkte (Ground-Truth-Traces, Gate 11b) und der Zielarchitektur-Umbauplan.

<a name="b-doc-3"></a>
#### B-DOC-3 — MITTEL — 13 Plaene im Root, teils erledigt

**Status:** `[ ]` offen

Root-Ebene: 275 KB in 13 `.md`-Dateien. Davon sind laut README-Tabellen
vollstaendig abgeschlossen: `MARKER_UND_WAVEFORM_AUSBAU_PLAN.md`,
`WORKOUT_FUNKTIONEN_AUSBAU_PLAN.md`,
`MUSIK_WORKOUT_KOPPLUNG_AUSBAU_PLAN.md`, `AUDIO_ENGINE_AUSBAU_PLAN.md`.
Sie liegen gleichrangig neben dem aktiven
`WAVEFORM_PERFORMANCE_UMBAU_PLAN.md`.

`MIX_TRANSITIONS_AUSBAU_PLAN.md` fuehrt seine Phase 1 als "Entwurf", obwohl
sie umgesetzt ist (DB v7, `TempoAccumulator`, `ChromaAccumulator`).

**Fix:** Erledigte Plaene nach `docs/plans/done/`, Verweise per
`tools/doku_links_check.py` gegenpruefen. Der Root soll zeigen, was aktiv
ist.

<a name="b-doc-4"></a>
#### B-DOC-4 — HOCH — README-Statustabellen widersprechen dem Code

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode) — alle fuenf Zeilen
korrigiert, jede vorher am Code nachgeprueft statt aus diesem Plan uebernommen

| README sagt | Code sagt | Befund |
|---|---|---|
| ~~Schritt 19 Bit-Perfect "Abgeschlossen"~~ | kein `setPreferredMixerAttributes` (null Treffer), `floatOutput` hart `false` in `PlaybackService.kt:114` | [B-AUD-4](#b-aud-4) |
| ~~Mix-Uebergaenge Phase 2/3 "Abgeschlossen"~~ | `fadeInGain()` nur in Tests aufgerufen — es gibt gar keinen Crossfade im Audiopfad | [B-AUD-5](#b-aud-5) |
| ~~Herzfrequenz Phase 2 "Abgeschlossen (Train-Tab)"~~ | Manifest-Permission fehlte — behoben 03.09.2026 (`1b33309`) | [B-SEC-1](#b-sec-1) |
| ~~Mix Phase 1 "Entwurf"~~ | umgesetzt: Migration v6->v7, `TempoAccumulator`/`ChromaAccumulator` in `TrackAnalyzerImpl:99-100`, ADR-0019 | [B-DOC-3](#b-doc-3) |
| ~~Schritt 21 Baseline-Profile "vorhanden"~~ | Plugin angewandt, aber `BaselineProfileRule` kommt im Repo nicht vor | [B-UI-5](#b-ui-5) |

Bei einem Projekt, dessen Statustabellen die primaere Fortschrittsquelle sind,
ist jede Zeile eine Falle fuer die naechste Session — genau das Problem, das
`Kritische Befunde.md` fuer sich schon geloest hat.

**Was die Nachpruefung zusaetzlich ergab.** Zwei der fuenf Zeilen waren
schlimmer als hier notiert:

- **Bit-Perfect** ist nicht "unvollstaendig", sondern **nicht einschaltbar**.
  `BitPerfectGateway` liest `getSupportedMixerAttributes` und meldet
  Faehigkeiten ans UI — der Gegenaufruf `setPreferredMixerAttributes` fehlt
  vollstaendig. Der Ausgang wird also nie umgestellt. Was existiert, ist
  Erkennung plus DSP-Bypass; der bitgenaue Pfad nicht.
- **Mix-Uebergaenge** haben nicht nur "`fadeInGain` ohne Produktivkonsument".
  Es gibt **ueberhaupt keinen Crossfade** mehr: der `CrossfadeController`
  (Dual-Player) wurde bei der ADR-Konsolidierung entfernt, Uebergaenge laufen
  als harter Wechsel via `playSongAt`. Damit hat auch keine Kurve einen Ort,
  an dem sie wirken koennte. Die sechs Stil-Chips in den Einstellungen
  steuern eine Wirkung, die es nicht gibt.

**Neuer Befund bei der Baseline-Profile-Zeile** (nicht in der Erstfassung):
`StartupBenchmark` zielt auf `packageName = "com.dropsync.app.debug"`,
waehrend die `benchmark`-Variante per `matchingFallbacks = ["release"]` auf
den Release-Build faellt — dessen `applicationId` ist `com.dropsync.app`
**ohne** Suffix (`app/build.gradle.kts:19,45`). Der Benchmark wuerde also die
falsche Anwendung suchen. Als Warnung im Klassenkommentar hinterlegt, damit
es vor der ersten Geraetemessung auffaellt.

**Regel fuer die Zukunft:** "Abgeschlossen" setzt voraus, dass ein
Produktivkonsument existiert. Der Test aus [B-ARCH-1](#b-arch-1) macht das
pruefbar statt disziplinabhaengig.

<a name="b-doc-5"></a>
#### B-DOC-5 — MITTEL — Verweise auf einen "Verbesserungsplan", den es nicht gab

**Status:** `[x]` behoben (04.09.2026, Session: OpenCode)

`README.md` verwies an drei Stellen auf "Verbesserungsplan Phase 4"
(Zeilen 42, 141: Baseline Profiles) und "Verbesserungsplan Phase 5"
(Zeile 92: Health-Connect-Berechtigungs-UI). Ein solches Dokument existierte
nicht.

Dieses Dokument uebernimmt den Namen, aber **nicht** die Phasennummern: seine
Phasen heissen P0-P4. Die alten Verweise waren damit ohne Ziel.

**Erledigt:** Alle drei README-Verweise umgebogen — der Baseline-Profile-Verweis
auf Schritt 21 derselben Tabelle plus die Befund-ID [B-UI-5](#b-ui-5), der
Health-Connect-Verweis auf `docs/STATUS_FORTSCHRITT.md`, wo die Arbeit vom
21.08.2026 protokolliert ist.

**Der Befund war unvollstaendig:** die drei README-Stellen waren nicht alle.
`git grep 'Verbesserungsplan Phase'` ueber `*.md` und `*.kt` fand drei
weitere in **Codekommentaren** — `StartupBenchmark.kt:13` ("Phase 4"),
`WorkoutExporter.kt:7` und `SettingsViewModel.kt:199` (beide "Phase 3").
Die Erstfassung hat nur den README durchsucht. Alle sechs sind jetzt weg;
ausserhalb dieses Dokuments nennt kein Verweis mehr eine Phasennummer, die
es nie gab.

<a name="b-doc-6"></a>
#### B-DOC-6 — NIEDRIG — `STATUS_FORTSCHRITT.md` hat das Alphabet aufgebraucht

**Status:** `[ ]` offen

837 Zeilen, 27 Abschnitte A bis Z (voll). Die Datei ist chronologisch, wird
laut eigener Regel bei jeder Session "als Erstes gelesen" und waechst
monoton.

**Fix:** Bei der naechsten Milestone-Grenze schneiden:
`docs/status/2026-08.md` fuer A-W, im Original nur der laufende Monat plus die
Regeln im Kopf.

<a name="b-doc-7"></a>
#### B-DOC-7 — NIEDRIG — Ungetrackte Datei im UI-Ordner

**Status:** `[ ]` offen

`UI/HP0eIktXQAAJfnZ.jpeg` ist untracked — anonymer Download. `UI/` wiegt
bereits 11,4 MB (davon 3,3 MB unbenutztes Raleway, siehe
[B-SEC-4](#b-sec-4)), `.git` 19,4 MB.

**Fix:** Kuratieren (nach `docs/design/reference/` mit sprechendem Namen) oder
loeschen.

---

## 3. Umsetzungsreihenfolge

Die Reihenfolge folgt drei Regeln: (1) was falsch laeuft vor was fehlt,
(2) was eine Zusage bricht vor was unschoen ist, (3) was andere Arbeit
blockiert zuerst.

Nach jedem Paket gilt die Projektregel: `spotlessApply`, betroffene Tests,
`:app:assembleDebug`, README-Statustabelle pflegen, Eintrag in
`docs/STATUS_FORTSCHRITT.md`, committen.

### P0 — Sofort: Korrektheit und Konsistenz

Ziel: kein Laufzeitdefekt, keine falsche Zusage im aktuellen Stand.

| # | Paket | Befund | Aufwand |
|---|---|---|---|
| 1 | `ensureActive()` am Schleifenkopf in `drainDecoder` + Abbruchtest | [B-AUD-6](#b-aud-6) | **erledigt** (Fix im Baum, Test 45-vs-5, 37/37 gruen) |
| 2 | ADR-0015 committen, 14 ausstehende Commits pushen | Abschnitt 0/1 | **halb erledigt** (`27aa314` committet; Push-Entscheidung beim Nutzer) |
| 3 | Health-Connect-Manifest + Rationale, oder Badge deaktivieren | [B-SEC-1](#b-sec-1) | **erledigt** (Stufe 1: Manifest gebaut, nicht Badge abgeschaltet; Geraeteabnahme offen) |
| 4 | README-Statustabellen korrigieren (5 Zeilen) + Phasenverweise umbiegen | [B-DOC-4](#b-doc-4), [B-DOC-5](#b-doc-5) | **erledigt** (5/5 Zeilen, 6 Verweise, 2 Befunde untertrieben) |
| 5 | 7 Lint-Typos in `values-de` + 3 hartcodierte Strings | [B-UI-4](#b-ui-4), [B-UI-3](#b-ui-3) | **erledigt** (117 Zeilen Umlaute, 3 Strings extrahiert; 43 Lint-Warnungen bleiben) |
| 6 | `feature/progress/src` + `benchmarks/src` in die Detekt-Liste, Findings sichten | [B-ARCH-4](#b-arch-4) | **erledigt** (2 Findings behoben, nicht baselined) |

**P0 ist abgeschlossen** ausser dem Push (Punkt 2) — der bleibt eine
Nutzerentscheidung, siehe Abschnitt 0.

Punkt 4 hat den Status ehrlich gemacht, nicht die Funktion gebaut. Die vier
Zeilen nennen jetzt beim Namen, was fehlt: der Bit-Perfect-Modus ist nicht
einschaltbar ([B-AUD-4](#b-aud-4)), es gibt keinen Crossfade
([B-AUD-5](#b-aud-5)), Mix Phase 1 ist laengst umgesetzt
([B-DOC-3](#b-doc-3)), Baseline Profiles haben keinen Generator
([B-UI-5](#b-ui-5)). Die Entscheidung "implementieren oder streichen" steht
damit sichtbar an, statt hinter einem "Abgeschlossen" zu verschwinden — das
war der Zweck des Pakets.

Naechstes Paket: P1 Punkt 8 ([B-AUD-1](#b-aud-1), `MasterDspProcessor`) —
Punkt 7 ist erledigt.

### P1 — Kurzfristig: Performance im Zielszenario

Ziel: die App bleibt bei 5.000 Titeln und aktivem DSP fluessig.

| # | Paket | Befund | Aufwand |
|---|---|---|---|
| 7 | Migration v10 -> v11: 6 Indizes + `MigrationTest` mit Nutzdaten und Query-Plan | [B-DB-1](#b-db-1) | **erledigt** (Indexschnitt geaendert, 1 geplanter Index bewusst weggelassen) |
| 8 | `MasterDspProcessor`: Stufen einmalig, `Freeverb.reset()`, `onFlush` ohne Rebuild | [B-AUD-1](#b-aud-1) | **erledigt** (3 Tests, 3 Gegenbeweise) |
| 9 | `StreamingResampler`: Arbeitspuffer wiederverwenden | [B-AUD-2](#b-aud-2) | **erledigt** (mit Paket 8) |
| 10 | Windows-Pfad aus `feature/workout/build.gradle.kts` | [B-ARCH-3](#b-arch-3) | **erledigt** (Block entfernt, 32/32 mit --rerun-tasks) |
| 11 | `restDuckCurrent` absichern | [B-AUD-3](#b-aud-3) | **erledigt** (AtomicReference + cancelAndJoin + Mutex; `@Volatile` allein reichte nicht) |
| 12 | Emulator-Job in der CI (`continue-on-error` zuerst) | [B-UI-6](#b-ui-6) | halber Tag |

Punkt 8 brauchte Sorgfalt, weil die Ausgabewerte sich nicht aendern durften —
sonst waere der Waveform-/DSP-Vergleich gegen die Baseline wertlos (dieselbe
Regel wie im Waveform-Umbauplan, Grundregeln Zeile 87-91). Nachweis:
`:domain:audio` 90/90 und `:data:audio` 40/40 gruen, keine Toleranz
angepasst, `DspPerformanceTest` unveraendert gruen.

Naechstes Paket: **P1 ist bis auf Punkt 12 erledigt.** Der Emulator-Job
([B-UI-6](#b-ui-6)) braucht einen CI-Lauf und damit eine Entscheidung
ausserhalb dieses Arbeitsbaums.

### P2 — Mittelfristig: Struktur und Absicherung

Ziel: die Fehlerklassen aus Abschnitt "Der wiederkehrende Fehlermodus" werden
strukturell verhindert, nicht einzeln gefunden.

| # | Paket | Befund | Aufwand |
|---|---|---|---|
| 13 | Architekturtest auf Import-Ebene | [B-ARCH-1](#b-arch-1) | **erledigt** (5 Tests, 2 Gegenbeweise; Konsumententest abgelehnt, 67/227 Fehlalarme) |
| 14 | `build-logic`-Convention-Plugins (loest B-ARCH-4 dauerhaft) | [B-ARCH-4](#b-arch-4) | 2-3 Tage |
| 15 | Tests fuer `SettingsViewModel` + `AudioSettingsViewModel` + Timer/Stores | [B-UI-1](#b-ui-1) | **erledigt** (settings 6, audio 6, timer 6, data/settings 7; je mind. 1 Gegenbeweis; player/workout waren abgedeckt) |
| 16 | `DropSync-Technischer-Bauplan.md` einchecken oder Regeln extrahieren | [B-DOC-1](#b-doc-1) | **erledigt** (Weg b: `docs/ARCHITEKTURREGELN.md`, README umgebogen) |
| 17 | Entscheidung `:feature:timer`: verdrahten oder entfernen | [B-ARCH-2](#b-arch-2) | **erledigt** (Verdrahten: Route `timer` ab Train-Pause, gleiche Engine) |
| 18 | Bit-Perfect: implementieren oder Status ehrlich machen | [B-AUD-4](#b-aud-4) | **erledigt** (Weg b: UI-Text nennt die Grenze; Schalter = DSP-Bypass) |
| 19 | Crossfade-Presets: Konsument bauen oder Panel ausgrauen | [B-AUD-5](#b-aud-5) | **erledigt** (Weg b: beide Panels ausgegraut + Hinweis, Werte persistiert) |
| 20 | `BaselineProfileGenerator` + `packageName` im Benchmark | [B-UI-5](#b-ui-5) | **erledigt** (fuenf Ursachen statt zwei; Generatorlauf braucht Geraet) |
| 20b | `:benchmarks:assembleBenchmarkRelease` als CI-Schritt | [B-UI-5](#b-ui-5) | 15 Minuten — ohne diesen Schritt verrottet das Modul weiter still |

Punkt 13 vor 17-19: mit dem Konsumententest sind die drei Entscheidungen
belegt statt vermutet.

### P3 — Aufraeumen

| # | Paket | Befund |
|---|---|---|
| 21 | `gradle/verification-metadata.xml` | [B-SEC-3](#b-sec-3) |
| 22 | `signingConfig` fuer Release | [B-SEC-2](#b-sec-2) |
| 23 | Raleway entfernen, Poppins-Lizenzverweis korrigieren, Notices-Abgleich im CI | [B-SEC-4](#b-sec-4) |
| 24 | Waisen-Aufraeumer `track_analysis`; `COUNT(*)` im Scan-Skip | [B-DB-2](#b-db-2), [B-DB-3](#b-db-3) |
| 25 | `EnergyAccumulator` auf `DoubleArray` | [B-AUD-7](#b-aud-7) |
| 26 | 25 `PluralsCandidate` auf `<plurals>`; 4 Bruchzahlen; 5 `UseKtx` | [B-UI-4](#b-ui-4) |
| 27 | `FlowRepGlassNavigation` nach `:core:designsystem`; Lambda-`offset` | [B-ARCH-5](#b-arch-5) |
| 28 | Doku-Umbau: Befunde archivieren, Plaene nach `done/`, STATUS schneiden | [B-DOC-2](#b-doc-2), [B-DOC-3](#b-doc-3), [B-DOC-6](#b-doc-6) |
| 29 | Media3-Update, dabei `setEnableAudioTrackPlaybackParams` ersetzen | [B-AUD-8](#b-aud-8) |
| 30 | Submodule-Konsistenzpruefung; FTS4-Entscheidung dokumentieren; `ExportedService`-Kommentar | [B-ARCH-6](#b-arch-6), [B-DB-4](#b-db-4), [B-SEC-5](#b-sec-5) |
| 31 | `UI/HP0eIktXQAAJfnZ.jpeg` kuratieren oder loeschen | [B-DOC-7](#b-doc-7) |
| 32 | Detekt-Baseline abbauen: je Anlass einen Composable-Abschnitt ausziehen | [B-UI-2](#b-ui-2) |
| 33 | FFmpeg-Lizenzstatus bei Artefaktbau von OFFEN auf FREIGEGEBEN | [B-SEC-6](#b-sec-6) |

### P4 — Braucht Hardware (nicht durch Code loesbar)

Diese Punkte sind keine Befunde dieses Plans, sondern die bereits bekannten
Geraeteabnahmen. Sie stehen hier, damit die Reihenfolge vollstaendig ist:

- Drei Cold-Cache-Laeufe der Track-Analyse auf einem Mittelklasse-Geraet
  (entscheidet Abbruchkriterium A1 und den verbindlichen 1,5-s-Zielwert;
  `WAVEFORM_PERFORMANCE_UMBAU_PLAN.md` Phase 0).
- TalkBack-Abnahme und 200-%-Schrift (`docs/qa/ACCESSIBILITY_ACCEPTANCE.md`
  ist vollstaendig vorbereitet, inkl. `tools/accessibility-check.ps1`).
- USB-DAC-Bit-Perfect, BT-Codec-Verhalten, MusicFX mit/ohne
  Systemequalizer.
- Gate 11b: die fuenf Hardware-Freigabeszenarien der Zaehlpipeline
  (`tools/golden_shadow_corpus/README.md`). Die Werkzeuge sind fertig
  (`tools/shadow_harness.py`, `tools/recofit_bootstrap.py`),
  `domain/sensor/src/test/resources` existiert nicht — es fehlen ausschliesslich
  Aufnahmen.

---

## 4. Was dieser Plan bewusst nicht anfasst

- **Die DSP-Mathematik in `:domain:audio`.** Sie ist korrekt und getestet.
  Die Befunde betreffen die Grenze zum Audio-Thread, nicht die Rechnung.
- **Den Waveform-Zeichenpfad.** `Waveform.kt` ist Vorbild, nicht Baustelle
  (der Umbauplan sagt dasselbe: "Kein Problem, bewusst nicht angefasst").
- **Die Migrationskette.** Additiv, einzeln getestet, mit Datenerhalt-Tests.
  [B-DB-1](#b-db-1) ergaenzt sie, aendert sie nicht.
- **Die Modulaufteilung.** Sie funktioniert. [B-ARCH-1](#b-arch-1) sichert sie
  ab, [B-ARCH-2](#b-arch-2) entfernt ein verwaistes Modul — keine
  Neuaufteilung.
- **Das Datenschutzkonzept.** Kein `INTERNET`, kein Backup, minimale
  Permissions. [B-SEC-1](#b-sec-1) schliesst eine Luecke im Vollzug, nicht im
  Konzept.
- **Den Anspruch der Dokumentation.** Sie ist zu gross und teils veraltet,
  aber ihr Detailgrad ist der Grund, warum diese Analyse ueberhaupt so
  praezise sein konnte. Die Doku-Befunde zielen auf Aktualitaet und
  Auffindbarkeit, nicht auf Kuerzung um ihrer selbst willen.

---

## 5. Kennzahlen fuer die Nachpruefung

Damit der Fortschritt messbar ist und nicht behauptet:

| Kennzahl | Heute | Ziel |
|---|---:|---:|
| Rote Tests | 0 | 0 |
| Lint-Warnungen (SARIF, 18 Module) | 50 | < 10 |
| Detekt-Baseline-Eintraege | 23 | < 15 |
| Module ohne Tests | 0 (war 4) | 0 |
| Module ohne Detekt-Abdeckung | 0 (war 3) | 0 |
| Indizes auf `songs` | 5 (war 0), plus 1 auf `song_markers` | erreicht |
| Allokationen im Audio-Callback | nein (war ja) | nein |
| README-Statuszeilen mit Codewiderspruch | 0 (war 5) | 0 |
| Doku-Verweise auf fehlende Dateien | 4 | 0 |
| Nicht gepushte Commits | 17 | 0 |
