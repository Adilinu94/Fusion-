# ADR-0015: Track-Analyse in zwei Stufen mit getrennter Cache-Versionierung

Datum: 2026-09-01
Status: akzeptiert

## Kontext

Die Waveform im Now-Playing erschien beim Titelwechsel mehrere Sekunden zu
spaet. Die Ursache war nicht eine langsame Stelle, sondern eine
Kopplung: `TrackAnalyzerImpl` baute bei jedem Lauf Tempo-, Chroma- und
Loudness-Akkumulator mit auf, und der Worker schrieb das Ergebnis erst,
wenn ALLE fertig waren (ein einziger Upsert). Die sichtbare Waveform war
damit Geisel der Tonart-Erkennung.

Das Interface versprach den Nur-Waveform-Pfad bereits ("Ohne
`detectOnsets` wird nur die Waveform berechnet"), und `AnalysisProfile`
mit `WAVEFORM_ONLY`/`MIX_METADATA`/`FULL` lag angelegt vor - beides
wurde von der Implementierung nie eingeloest.

Die JVM-Baseline (`TrackAnalysisBaselineTest`, 4 min, 44,1 kHz,
aufgewaermte JIT) beziffert die Kopplung:

| Abschnitt | Zeit Desktop-JVM |
|---|---:|
| Waveform + Peak | 227-288 ms |
| Chroma/Goertzel | 397-446 ms |
| Tempo | 63-83 ms |
| Energy/RMS | 37-39 ms |
| Loudness | 34-48 ms |
| kombinierter Pfad (heute) | 592-745 ms |
| nur Waveform | 183-227 ms |

**69-70 % der Akkumulatorzeit** des UI-kritischen Laufs entfallen auf
Werte, die der Nutzer in diesem Moment nicht sieht.

Vor der Messung stand ein anderer Plan: Block-API statt Sample-API, um
die inneren Schleifen zu beschleunigen. Die Messung hat diese Reihenfolge
umgedreht. Sie hat ausserdem eine Begruendung des Plans widerlegt: die
74.412 `cos()`-Aufrufe der Goertzel-Koeffizienten, laut Plan ein
Flaschenhals, kosten zusammen **3,5 ms**. Die Zeit steckt in ca. 76,2
Mio. inneren Schleifendurchlaeufen, nicht in den Koeffizienten.

Zweiter, unabhaengiger Befund: `analyzerVersion` galt fuer die ganze
Zeile. Eine Aenderung am BPM- oder Key-Algorithmus haette die
Waveform-Caches der gesamten Bibliothek invalidiert und nach dem Update
einen Re-Analyse-Sturm ausgeloest.

## Entscheidung

**Stufe 1 (`WAVEFORM_ONLY`)** ist UI-kritisch: Decode, Waveform-Buckets,
Peak, sofortiger Upsert. Sie laeuft **in-process priorisiert** in einem
anwendungsweiten Scope (`SupervisorJob + dispatchers.default`), nicht
ueber WorkManager.

**Stufe 2 (`MIX_METADATA`)** ist aufschiebbar: BPM, Camelot-Key,
Konfidenzen, LUFS, True-Peak. Sie laeuft als WorkManager-Auftrag und
reichert die bestehende Zeile per echtem SQL-`UPDATE`
(`updateMixMetadata`) an.

**Getrennte Versionierung.** Neue Spalte
`mixAnalyzerVersion INTEGER NOT NULL DEFAULT 0` (DB v9 -> v10,
`MIGRATION_9_10`). `analyzerVersion` gilt ab jetzt ausschliesslich fuer
Waveform und Peaks; die Metadatenspalten sind nur gueltig, wenn
`mixAnalyzerVersion == WaveformCodec.MIX_ANALYZER_VERSION`. Beide
Versionen werden in `requestAnalysis` getrennt geprueft und in
`observeAnalysis` getrennt ausgewertet.

**Reihenfolge ueber Verkettung, nicht ueber Hoffnung.** Ist die Waveform
veraltet, laeuft `WAVEFORM_ONLY` und per `.then(...)` verkettet
`MIX_METADATA`. Sind nur die Metadaten veraltet, laeuft ein eigener
Unique-Work `mix_analysis_<id>`.

**Cancel-und-Ueberholen statt Warteschlange.** `activeJobs` haelt einen
Job je Song; jeder `requestAnalysis`-Aufruf bricht Laeufe zu *anderen*
Songs ab. `Semaphore(2)` begrenzt gleichzeitige Decoder.

**Prewarming der Anzeige, nicht der Bibliothek.**
`requestAnalysisPrewarm(songs, limit = 2)` bereitet die naechsten
Queue-Titel vor - nur Stufe 1, angestossen erst wenn die Waveform des
laufenden Titels bereit ist.

Die Block-API (Plan-Phase 1) wird **nicht** gebaut, solange die
Geraetemessung nicht zeigt, dass Decode + Stufe 1 das 1,5-s-Ziel
verfehlen.

## Begruendung

**Warum ein zweiter Decode fuer Stufe 2 in Kauf genommen wird.** Er ist
unabhaengig versionierbar und nie UI-kritisch. Die Alternative - alles in
einem Durchgang halten und nur den DB-Write aufteilen - haette die
Kopplung nicht geloest: der zweite Teil des Laufs blockiert weiter denselben
Decoder und dieselbe CPU, auf die der sichtbare Titel wartet.

**Warum Stufe 2 die Waveform NICHT mitschreibt** (Abweichung vom
urspruenglichen Plan). Der Plan sah vor, dass Stufe 2 die Waveform
"notfalls gleich mitliefert". Ein Zweitlauf, der Waveform-Bytes schreibt,
kann eine bereits sichtbare Waveform durch ein abweichendes Ergebnis
ersetzen - sichtbares Flackern, genau das, was Stufe 1 verhindern soll.
Trifft das `UPDATE` keine Zeile, liefert der Worker `Result.retry()`
statt eine Zeile ohne Waveform anzulegen.

**Warum `mix_analysis_<id>` einen eigenen Work-Namen braucht.** Unter
`track_analysis_<id>` haette `ExistingWorkPolicy.KEEP` einen reinen
Metadatenlauf verworfen, solange dort noch ein Eintrag existiert.

**Warum Stufe 1 WorkManager verlaesst.** `enqueueUniqueWork` +
Expedited-Quota ist fuer den titelwechselkritischen Pfad die falsche
Lane; das Kontingent ist nicht garantiert und faellt auf normale Latenz
zurueck. Phase 2 hatte nur die *Arbeit* verkuerzt, nicht die *Wartezeit
davor*. Prozess-Tod ist unkritisch: das Ergebnis lebt allein im
DB-Cache, der Lauf ist idempotent wiederholbar, und der
WorkManager-Retry bleibt als Netz fuer dauerhafte Faelle.

**Warum kein Mutex fuer `activeJobs`.** Aufgeraeumt wird in
`Job.invokeOnCompletion`, das nicht suspendieren darf. Ein
`Mutex.withLock` dort bricht nach einem Cancel sofort erneut ab und
laesst den Eintrag fuer immer stehen - ein schleichendes Leck, das den
Dedup-Check dauerhaft verfaelscht. Stattdessen `synchronized` um reine
Map-Operationen ohne I/O.

**Warum Prewarming an eine Bedingung gekoppelt ist und nicht an den
Titelwechsel.** Frueher angestossen konkurrieren die Prewarm-Decodes mit
dem einen Lauf, auf den der Nutzer gerade wartet. Prewarming waere dann
messbar schaedlich statt nuetzlich. Das ist der Unterschied zwischen
dieser Funktion und blindem Vorausladen.

**Warum getrennte Cache-Versionen und nicht zwei Entitaeten.** Zwei
Tabellen haetten einen Join oder zwei Flows je Titel bedeutet, dazu eine
groessere Migration. Der Gewinn - Teilbarkeit - entsteht bereits durch
nullable Spalten plus zwei Versionsfelder. Das UI-Modell `TrackAnalysis`
bleibt unveraendert.

## Konsequenzen

**Was gemessen besser ist:** 69-70 % weniger Akkumulatorarbeit vor dem
ersten sichtbaren DB-Write; keine Dispatch- und Quota-Latenz mehr vor dem
Start; ab dem zweiten Titel einer Session ist die Waveform durch
Prewarming praktisch immer schon da.

**Was offen bleibt - und das ist die Hauptluecke:** der verbindliche
Zielwert. `TrackAnalysisTiming` liefert Stopuhren je Abschnitt, aber
MediaCodec-Decode und WorkManager-Dispatch laufen nur auf Android. Ob
Decode + Stufe 1 die angestrebten 1,5 s halten, entscheiden erst drei
Cold-Cache-Laeufe auf einem Mittelklasse-Geraet. **Die JVM-Zahlen sind
eine Untergrenze, keine Geraeteprognose.** Bis dahin gilt der 1,5-s-Wert
als Absicht, nicht als belegtes Ergebnis - dieselbe Luecke wie bei den
Sensor-Ground-Truth-Traces (ADR-0017) und dem Konfidenz-Gate (ADR-0019):
die Mechanik steht, die Referenzmessung fehlt.

**Prewarming loest die erste Waveform einer Session nicht.** Beim ersten
Titel gibt es keinen Vorgaenger, der ihn vorbereitet haette. Genau dort
zaehlt die Geraetemessung.

**Zwei Extraktionen sind ab jetzt zu pflegen.**
`TrackAnalysisPersister` (Versions- und Feld-Uebernahmeregeln, zwei
Aufrufer) und `DeferredAnalysisScheduler` (WorkManager-Fassade). Der
zweite entstand aus Testnot, nicht aus Aesthetik: ohne ihn scheiterte
jeder Testfall des Prioritaetspfads an `WorkManager.getInstance()` unter
Robolectric.

**Zwei Fehlerpfade weichen bewusst voneinander ab.** In-process schreibt
ein voruebergehender Fehler nichts und plant keinen Retry - Anlass ist
immer eine Nutzeraktion, der naechste Aufruf versucht es erneut. Im
Worker bleibt der Backoff-Retry, weil dort niemand zuschaut.

**`ANALYZER_VERSION` bleibt bei 4.** Die Ausgabewerte sind unveraendert;
ein Update loest keinen Re-Analyse-Sturm aus. Kuenftige Aenderungen an
BPM/Key/LUFS bumpen nur `MIX_ANALYZER_VERSION`.

## Alternativen

- **Alles in einem Lauf, nur den DB-Write aufteilen.** Loest die Kopplung
  nicht - Decoder und CPU bleiben belegt, waehrend der sichtbare Titel
  wartet.
- **Zuerst die inneren Schleifen optimieren (Block-API).** War der
  urspruengliche Plan. Die Messung zeigt: nach der Stufentrennung
  verbessert eine Optimierung der Metadaten-Akkumulatoren die sichtbare
  Waveform nicht mehr. Hoeheres Algorithmusrisiko bei kleinerem Nutzen.
- **Zwei Entitaeten statt Spaltenversionen.** Join oder zwei Flows je
  Titel, groessere Migration, kein zusaetzlicher Gewinn.
- **Stufe 1 mit `setExpedited` in WorkManager lassen.** Das Kontingent
  ist nicht garantiert; der Rueckfall ist normale Dispatch-Latenz - also
  genau der Flaschenhals, der beseitigt werden sollte.
- **Prewarming ohne Bedingung, direkt beim Titelwechsel.** Verlangsamt
  messbar den Lauf, auf den der Nutzer wartet.
