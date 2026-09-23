package com.dropsync.domain.sensor

/**
 * RC-7/RC-17: kompakter Diagnose-Snapshot eines gezaehlten Sets.
 *
 * Buendelt die bisher nur verstreut vorhandenen Zaehler (Pipeline-Frames,
 * Gaps, ZUPT, gemessene Rate, klassifizierte Ablehnungen) zu einem
 * unveraenderlichen Wert, den der [ActiveSetController] beim Stoppen
 * einfriert. Konsumenten: der Satz-Report in der Train-UI und das
 * Diagnose-Panel in den Einstellungen.
 */
data class SetDiagnostics(
    /** Vom Live-Zaehler bestaetigte Wiederholungen (wie im Trace). */
    val countedReps: Int = 0,
    /** Frames, die die Filter-Einschwingzeit passiert haben. */
    val framesProcessed: Int = 0,
    /** Frames waehrend der Einschwingzeit (verworfen). */
    val framesRejected: Int = 0,
    /** Grosse Zeitluecken (>= [ExerciseEnginePipeline.LARGE_GAP_MS]). */
    val largeGapCount: Int = 0,
    /** Nachgefuehrte Gyro-Bias-Korrekturen (ZUPT). */
    val zuptBiasUpdates: Int = 0,
    /** Wegen Ruhe verworfene Pending-Reps (ZUPT). */
    val zuptAbortedPending: Int = 0,
    /** Im Set gemessene Abtastrate in Hz. */
    val measuredSampleRateHz: Double = SampleRateEstimator.NOMINAL_RATE_HZ,
    /** Signalqualitaet beim Set-Abschluss. */
    val signalQuality: SignalQuality = SignalQuality.UNRELIABLE,
    /**
     * RC-17: Ablehnungen je Mechanismus (nur Mechanismen mit Treffern).
     * Die Summe ist [totalRejections].
     */
    val rejectionCounts: Map<RepRejectionReason, Int> = emptyMap(),
    /** P2-Fix #19: Autokorrelations-Zweitmeinung zum Zaehlerstand. */
    val plausibility: RepCountPlausibility.Result? = null,
) {
    /** Gesamtzahl der abgelehnten Rep-Kandidaten. */
    val totalRejections: Int
        get() = rejectionCounts.values.sum()

    /** Anzahl der Ablehnungen fuer einen einzelnen Mechanismus. */
    fun rejectionCount(reason: RepRejectionReason): Int = rejectionCounts[reason] ?: 0
}
