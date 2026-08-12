package com.dropsync.domain.timer

/**
 * Kill-Fallback (Testinfrastruktur-Umbauplan Schritt 2, 5b): stellt einen
 * laufenden REST/NORMAL-Timer nach einem App-/Service-Kill wieder her.
 *
 * Ablauf bei App-Start:
 * 1. [recover] liest das zuletzt persistierte [TimerSnapshot].
 * 2. Kein Snapshot oder kein rehydrierbarer Timer -> nichts zu tun (`false`).
 * 3. Sonst wird die Engine aus dem Snapshot rehydriert; der Aufrufer startet
 *    danach den Foreground-Service neu, damit `evaluate()` wieder laeuft.
 *
 * Reboot-Erkennung liegt ausserhalb: der Speicher hat bei Ruecksprung der
 * monotonen Uhr (Reboot) das Snapshot bereits verworfen, sodass hier kein
 * veralteter Zustand auftaucht.
 */
interface RestTimerRecovery {
    /**
     * Versucht, einen laufenden Timer in [engine] zu rehydrieren.
     *
     * @return `true`, wenn ein Snapshot restauriert wurde (Service neu starten),
     *   `false`, wenn nichts zu tun ist oder das Snapshot verworfen wurde.
     */
    suspend fun recover(engine: TimerEngine): Boolean
}

/**
 * Persistenz-Port fuer das [TimerSnapshot] (5b). Die Implementierung haelt
 * das Snapshot als JSON in DataStore (`:data:timer`); diese reine JVM-
 * Abstraktion haelt die Recovery-Logik androidfrei und damit testbar.
 */
interface TimerSnapshotStore {
    /** Zuletzt gespeichertes Snapshot oder `null`. */
    suspend fun load(): TimerSnapshot?

    /** Persistiert [snapshot] (ueberschreibt das vorherige). */
    suspend fun save(snapshot: TimerSnapshot)

    /** Loescht das Snapshot (Timer beendet, abgebrochen oder Reboot). */
    suspend fun clear()
}
