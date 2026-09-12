package com.dropsync.domain.timer

/**
 * Abbruch-Sequenzierung ohne Main-Blockade (Ausbauplan A1).
 *
 * Hintergrund: Die Abbruchpfade muessen das Snapshot loeschen, BEVOR der
 * Service stoppt bzw. neu startet — sonst stellt ein spaeterer Prozessstart
 * den bereits abgebrochenen Timer wieder her (Umbauplan Phase 10.3). Das stand
 * bisher als `runBlocking` auf dem Main-Thread im Service (ANR-Risiko bei
 * langsamem DataStore).
 *
 * Diese Klasse fasst die Reihenfolge als suspend-Sequenz: Der Aufrufer (Service)
 * startet sie in seinem Scope und haengt das Stoppen dahinter — gleiche
 * Ordnungsgarantie, keine Blockade. Rein JVM und ohne Android testbar; die
 * Stores kommen als Lambdas herein, damit `:domain:timer` keinen
 * DataStore-Port kennen muss.
 */
class TimerTermination(
    private val engine: TimerEngine,
    private val clearSnapshot: suspend () -> Unit,
    private val stampMonotonicClock: suspend () -> Unit,
) {
    /**
     * Abbruch: Engine stoppen, Snapshot loeschen, monotone Uhr stempeln —
     * strikt in dieser Reihenfolge.
     */
    suspend fun terminate() {
        engine.cancel(CancelReason.USER)
        engine.reset()
        clearSnapshot()
        stampMonotonicClock()
    }

    /** +15 s: alten Stand verwerfen, dann frischen REST-Timer starten. */
    suspend fun restartFresh(durationMs: Long) {
        engine.cancel(CancelReason.USER)
        engine.reset()
        clearSnapshot()
        engine.start(TimerMode.REST, durationMs)
    }
}
