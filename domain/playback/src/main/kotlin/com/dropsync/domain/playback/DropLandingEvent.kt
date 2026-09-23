package com.dropsync.domain.playback

// Ergebnis einer armierten Drop-Landung (MP-3/MP-5): Der ausfuehrende
// Service meldet ueber diese Ereignisse, ob der Wechsel auf der
// Wiedergabe-Uhr gefeuert hat oder verpasst wurde. Die Feature-Schicht
// uebersetzt sie in DropSync-Zustaende; der Domain-Kern bleibt frei von
// Android-Typen.

/** Ereignis einer armierten Landung. */
sealed interface DropLandingEvent {
    /** Landung ausgefuehrt; [deltaMs] = tatsaechlich - geplant. */
    data class Landed(
        val songId: Long,
        val deltaMs: Long,
    ) : DropLandingEvent

    /**
     * Landung nicht ausgefuehrt. [reason] nennt den Mechanismus; die UI
     * entscheidet, ob daraus BestEffort, Override oder Failed wird.
     */
    data class Missed(
        val reason: Reason,
    ) : DropLandingEvent

    enum class Reason {
        /** Die PlayerMessage hat nicht gefeuert; Watchdog hat uebernommen. */
        WATCHDOG,

        /** Nutzer hat pausiert/geskippt/gesucht: Vorrang, kein Fehler. */
        OVERRIDDEN,

        /** Player nicht verbunden oder Wechsel fehlgeschlagen. */
        PLAYER_ERROR,
    }
}
