package com.dropsync.domain.sensor

/**
 * Unveraenderlicher Mitschnitt eines gezaehlten Sets (Umbauplan Phase 7.1):
 * die einzige Eingabe fuer den Lernpfad. Wird vom [ActiveSetController] beim
 * Set-Abschluss eingefroren; keine Coroutine darf danach eine mutable Liste
 * lesen, die spaeter geleert wird.
 */
data class SetTrace(
    val exerciseId: Long,
    val deviceId: String,
    /** Pipeline-Version, mit der dieses Set gezaehlt wurde. */
    val engineVersion: RepEngineVersion,
    /** Revision des Profils, das beim Set-Start aktiv war. */
    val profileRevision: Int,
    /** Unveraenderliche Sample-Kopie (Learn-Loop-Grundlage). */
    val samples: List<SensorSample>,
    /** Alle gezaehlten Rep-Events in Reihenfolge. */
    val repEvents: List<RepEvent>,
    /** Vom Live-Engine gezaehlte Wiederholungen. */
    val predictedReps: Int,
    /** Signalqualitaet beim Set-Abschluss (eingefroren). */
    val signalQuality: SignalQuality,
    /** Monotone Startzeit des Sets (fuer Diagnose). */
    val startedAtMs: Long,
    /** Dauer des Sets in Millisekunden (Start -> Abschluss). */
    val durationMs: Long,
    /**
     * P2-Fix #19: unabhaengige Zweitmeinung zur Rep-Zahl aus der
     * Autokorrelation des Signals. Kein Zaehler, sondern eine
     * Konsistenzpruefung: sie erkennt ohne Ground Truth, ob die kalibrierte
     * Schwelle noch zum tatsaechlichen Signal passt. null, wenn die Pruefung
     * nicht lief (z. B. abgebrochenes Set).
     */
    val plausibility: RepCountPlausibility.Result? = null,
    /**
     * P2-Fix #21: die im Set GEMESSENE Abtastrate in Hz. Die Pipeline nahm
     * bisher pauschal 50 Hz an; jede Zeitkonstante und jede abgeleitete
     * Dauer haengt daran, deshalb gehoert der Wert in den Mitschnitt.
     */
    val measuredSampleRateHz: Double = SampleRateEstimator.NOMINAL_RATE_HZ,
    /**
     * RC-7/RC-17: Diagnose-Snapshot des Sets (Frames, Gaps, ZUPT,
     * klassifizierte Ablehnungen). null, wenn kein Snapshot vorliegt
     * (z. B. Trace aus einem Pfad ohne [ActiveSetController.stop]).
     */
    val diagnostics: SetDiagnostics? = null,
)
