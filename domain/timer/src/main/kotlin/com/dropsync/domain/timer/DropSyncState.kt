package com.dropsync.domain.timer

// DropSync-Zustandsmodell (Verbesserungsplan A.3, MP-5): EIN beobachtbarer
// Zustand fuer UI, Diagnose und Tests. Reine Domain-Typen ohne Android-,
// Media3- oder Room-Bezug; die Texte liegen in strings.xml.

/** Zeitliche Verlaesslichkeit der geplanten Landung (Design 7.1b). */
enum class TimingConfidence {
    /** Route-Latenz bekannt und stabil. */
    EXACT,

    /** Route-Latenz unbekannt oder veraltet: Landung bleibt Best Effort. */
    DEGRADED,

    /** Keine Aussage moeglich (z. B. kein Profil, keine Messung). */
    UNKNOWN,
}

/** Betriebsart der laufenden DropSync-Sitzung. */
enum class DropSyncMode {
    /** Musik landet am Pausenende (Drop-Auto bzw. geplante Landung). */
    LANDING_AT_REST_END,

    /** Manueller DropRest: Pause endet am naechsten Drop des Titels. */
    UNTIL_MARKER,
}

/** Warum nur eine Best-Effort-Landung moeglich war. */
enum class BestEffortReason {
    /** Kein Route-Profil: mit Latenz 0 gerechnet. */
    UNKNOWN_LATENCY,

    /** Route hat sich waehrend des Plans geaendert. */
    ROUTE_CHANGED,

    /** Der Plan kam zu spaet (Wiedergabe bereits am Ziel). */
    LATE_ARMED,

    /** Die PlayerMessage hat nicht gefeuert; der Watchdog hat uebernommen. */
    WATCHDOG,
}

/** Warum der Nutzer den Plan uebernommen hat (Design 4.5). */
enum class OverrideReason {
    SONG_CHANGED,
    SEEK,
    PAUSED,
    QUEUE_CHANGED,

    /**
     * C2: Der Nutzer hat innerhalb der Rest-Queue weitergesprungen
     * (Queue-Sheet, Bluetooth, Kopfhoerer) — der Plan wird sichtbar
     * zurueckgenommen und laesst sich per Undo neu armieren (5.10).
     */
    SKIPPED,
}

/** Warum keine Landung zustande kam. */
enum class DropSyncFailureReason {
    /** Keine "Rest/Pause"-Playlist: NORMAL-Verhalten (Entscheidung 7). */
    NO_REST_PLAYLIST,

    /** Kein Work-Titel mit brauchbarem Drop. */
    NO_WORK_DROP,

    /** Restzeit unter der Mindestdauer des Planers. */
    REST_TOO_SHORT,

    /** Kein Player verbunden oder Landung fehlgeschlagen. */
    PLAYBACK_ERROR,

    /**
     * C13: Ein Plan war aktiv, ueberlebte aber den App-/Service-Kill
     * nicht — oder ein manueller DropRest wurde nicht wiederhergestellt.
     * Wird einmalig sichtbar gemeldet (Entscheidungen 5.8/5.9).
     */
    PLAN_LOST,
}

/**
 * Ein Zustand je DropSync-Sitzung (Design 4.5, UI-Handbuch 4.5).
 * Jeder Pfad endet in genau einem Zustand — es gibt keine stillen
 * Fehlschlaege (Ausfuehrungsregel 4, A.3).
 */
sealed interface DropSyncState {
    /** Keine Sitzung, kein Plan. */
    data object Off : DropSyncState

    /** Plan steht, ist aber noch nicht scharf (Vorbereitung). */
    data class Planned(
        val songTitle: String,
        val markerLabel: String,
        val targetElapsedRealtimeMs: Long,
        val remainingMs: Long,
        val confidence: TimingConfidence,
        val mode: DropSyncMode,
        /**
         * C16 (5.22): geplante Ueberleitungskette als Titelzeilen
         * ("A -> B -> Drop"); leer bei einer Einzellandung.
         */
        val chain: List<String> = emptyList(),
    ) : DropSyncState

    /** Plan ist scharf: Landung terminiert, Watchdog laeuft. */
    data class Armed(
        val plan: Planned,
        val token: Long,
        /**
         * P1-10: True, wenn die Landung auf der Audio-Uhr armiert ist;
         * false, wenn nur der Deadline-Fallback laeuft. Die Konsole zeigt
         * damit "Audio vorbereitet" nur, wenn es auch stimmt.
         */
        val audioPrepared: Boolean,
    ) : DropSyncState

    /** Landung ausgefuehrt; [deltaMs] = tatsaechlich - geplant. */
    data class Landed(
        val atElapsedRealtimeMs: Long,
        val deltaMs: Long,
    ) : DropSyncState

    /** Nur Best-Effort-Landung moeglich. */
    data class BestEffort(
        val reason: BestEffortReason,
    ) : DropSyncState

    /** Nutzer hat den Plan uebernommen; er wird sichtbar zurueckgenommen. */
    data class Overridden(
        val reason: OverrideReason,
    ) : DropSyncState

    /** Keine Landung moeglich. */
    data class Failed(
        val reason: DropSyncFailureReason,
    ) : DropSyncState

    /** Plan bewusst beendet (Pause zu Ende, Abbruch, Verlassen). */
    data object Cancelled : DropSyncState
}
