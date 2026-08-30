package com.dropsync.domain.sensor

/** Result of the rep pipeline for one frame (port of rep_counter.dart). */
data class RepResult(
    val repCounted: Boolean,
    val repNumber: Int,
    val qualityScore: Double? = null,
    val correlation: Double? = null,
    val rejectionReason: String? = null,
    /** Diagnostic: window size in samples (time decisions use ms). */
    val durationSamples: Int? = null,
    /** Rep duration in milliseconds (time basis: timestamps). */
    val durationMs: Long? = null,
    val prominence: Double? = null,
) {
    companion object {
        val NONE = RepResult(repCounted = false, repNumber = 0)
    }
}

/**
 * Orchestrator of the full rep-detection pipeline:
 * PeakDetector -> TemplateMatcher -> PhaseValidator -> QualityScorer.
 *
 * Umbauplan Phase 4: the pending window is only finalised after a COMPLETE
 * cycle was observed (positive phase, direction change into the negative
 * phase, return toward the baseline). A pure lift without a return never
 * counts. Window extension and durations use TIMESTAMPS.
 *
 * Befund-C fix applied: TemplateMatcher.match() receives `peak.window`,
 * NOT the extended window used by PhaseValidator (see
 * PHASE_VALIDATOR_FIX_AUDIT_2026-08-05 section 7).
 *
 * Punkt 4: mit [accelPeakDetector] != null laeuft ein zweiter PeakDetector
 * auf dem Accel-Kanal (smoothedAccel). Ein Gyro-Peak zaehlt nur, wenn der
 * Accel-Kanal innerhalb von [accelVoteWindowSamples] um denselben Index
 * ebenfalls einen Peak gemeldet hat (Voting gegen False-Positives wie
 * Erschuetterungen).
 *
 * Punkt 5: bestaetigte Rep-Windows wandern per addToPool in den
 * Template-Pool (Formdrift / Ermuedung).
 *
 * Punkt 6: trackForAdaptation aktualisiert die erwartete Rep-Dauer im
 * PeakDetector (adaptive Refraktaerzeit) und die Pending-Fenster-Grenze.
 */
class RepCounter(
    private val peakDetector: PeakDetector,
    private val templateMatcher: TemplateMatcher,
    private val phaseValidator: PhaseValidator,
    private val qualityScorer: QualityScorer,
    private val accelPeakDetector: PeakDetector? = null,
    /** Punkt 4: Vote-Fenster in ms (Zeitbasis, nicht Sample-Index). */
    private val accelVoteWindowMs: Long = 800,
) {
    var repCount: Int = 0
        private set

    private val recentDurationsMs = mutableListOf<Double>()
    private val recentProminences = mutableListOf<Double>()

    // Pending phase-window extension: the peak-detector window ends shortly
    // after the falling edge and mostly covers the concentric half-wave.
    // PhaseValidator needs both half-waves, so the counting decision is
    // deferred until the eccentric half-wave completed AND the signal
    // returned toward the baseline (or the safety limit hit).
    private var pendingPeak: PeakEvent? = null
    private var pendingWindow: MutableList<Double>? = null
    private var pendingStartMs: Long = 0L
    private var pendingStartMin: Double = 0.0
    private var pendingWentBelowStartMin = false
    private var pendingSawNegative = false

    /**
     * Timestamp des letzten in das Pending-Fenster aufgenommenen Frames.
     * Zeitbasis der Rep-Dauer (Umbauplan Phase 2.5); die Sample-Anzahl ist
     * bei Paketverlust KEIN Zeitmass.
     */
    private var pendingLastMs: Long = 0L

    /** Punkt 4: Accel-Peaks (Timestamp) der letzten Frames. */
    private val recentAccelPeakTimestamps = ArrayDeque<Long>()

    /** Processes ONE frame through the whole pipeline. */
    fun process(frame: ProcessedFrame): RepResult {
        val peak = peakDetector.process(frame)
        accelPeakDetector?.process(frame)?.let { accelPeak ->
            recentAccelPeakTimestamps.addLast(accelPeak.timestampMs)
            while (recentAccelPeakTimestamps.isNotEmpty() &&
                frame.timestampMs - recentAccelPeakTimestamps.first() > accelVoteWindowMs
            ) {
                recentAccelPeakTimestamps.removeFirst()
            }
        }

        if (peak != null) {
            var finishedOld: RepResult? = null
            if (pendingPeak != null) {
                finishedOld = finalizePending()
            }
            startPending(peak)
            if (finishedOld != null) return finishedOld
            if (pendingComplete(frame.timestampMs)) return finalizePending()
            return RepResult.NONE
        }

        val window = pendingWindow ?: return RepResult.NONE
        val value = frame.smoothedGp
        window.add(value)
        pendingLastMs = frame.timestampMs
        if (value < 0) pendingSawNegative = true
        if (value < pendingStartMin) pendingWentBelowStartMin = true

        if (pendingComplete(frame.timestampMs)) return finalizePending()
        return RepResult.NONE
    }

    /**
     * Punkt 4: Gyro-Peak muss einen Accel-Peak im Toleranzfenster haben.
     * Bewusst erst in decide() geprueft: der Accel-Peak feuert wegen der
     * Falling-Debounce oft 1-2 Samples spaeter als der Gyro-Peak; zum
     * Zeitpunkt der Pending-Finalisierung liegt er sicher vor.
     */
    private fun accelVotePassed(peak: PeakEvent): Boolean {
        val accel = accelPeakDetector ?: return true
        return recentAccelPeakTimestamps.any { kotlin.math.abs(it - peak.timestampMs) <= accelVoteWindowMs }
    }

    private fun startPending(peak: PeakEvent) {
        pendingPeak = peak
        pendingWindow = peak.window.toMutableList()
        pendingStartMs = peak.timestampMs
        pendingLastMs = peak.timestampMs
        pendingStartMin = peak.window.min()
        pendingWentBelowStartMin = false
        pendingSawNegative = false
    }

    private fun pendingComplete(nowMs: Long): Boolean {
        val window = pendingWindow ?: return false
        // Punkt 4: bei aktivem Accel-Voting erst schliessen, wenn der
        // Accel-Peak im Toleranzfenster liegt - er feuert wegen seiner
        // Falling-Debounce einige Samples spaeter als der Gyro-Peak.
        if (accelPeakDetector != null && !accelVotePassed(pendingPeak!!)) {
            return (nowMs - pendingStartMs) >= maxExtraPhaseMs()
        }
        // Umbauplan Phase 4: full cycle = negative phase seen AND return
        // toward the start level. A lift without a return never completes
        // early; only the time-based safety limit forces finalisation.
        // Toleranz gegen Filter-Nachschwingen knapp unter 0 (Float-Artefakt).
        if (!pendingSawNegative) return false
        val returnTolerance = kotlin.math.max(1e-9, kotlin.math.abs(pendingPeak?.peakValue ?: 1.0) * 0.05)
        return (pendingWentBelowStartMin && window.last() >= -returnTolerance) ||
            (nowMs - pendingStartMs) >= maxExtraPhaseMs()
    }

    /**
     * Punkt 6: Pending-Fenster-Grenze dynamisch aus der erwarteten
     * Rep-Dauer in ms (2x, begrenzt auf 1200-6000 ms).
     */
    private fun maxExtraPhaseMs(): Long = (peakDetector.expectedDurationMs * 2.0).toLong().coerceIn(1_200, 6_000)

    private fun finalizePending(): RepResult {
        val peak = pendingPeak!!
        val window = pendingWindow!!
        val lastMs = pendingLastMs
        pendingPeak = null
        pendingWindow = null
        return decide(peak, window, lastMs)
    }

    private fun decide(
        peak: PeakEvent,
        window: List<Double>,
        lastMs: Long,
    ): RepResult {
        // Punkt 4: erst beim Finalisieren voten - der Accel-Peak feuert
        // wegen der Falling-Debounce oft einige Samples spaeter als der
        // Gyro-Peak und liegt zu diesem Zeitpunkt sicher vor.
        if (!accelVotePassed(peak)) {
            return RepResult(
                repCounted = false,
                repNumber = repCount,
                rejectionReason = "Accel-Voting fehlgeschlagen: kein Peak im Accel-Kanal",
            )
        }

        // Befund-C fix: TemplateMatcher sees the ORIGINAL peak.window,
        // PhaseValidator sees the (possibly extended) window.
        val matchResult = templateMatcher.match(peak.window)
        if (!matchResult.accepted && !matchResult.noTemplate) {
            return RepResult(
                repCounted = false,
                repNumber = repCount,
                rejectionReason = "Template-Match abgelehnt (NCC=${"%.3f".format(matchResult.correlation)})",
            )
        }

        val phaseResult = phaseValidator.validate(window)
        if (!phaseResult.valid) {
            return RepResult(
                repCounted = false,
                repNumber = repCount,
                rejectionReason = "Phasen-Validierung fehlgeschlagen: ${phaseResult.rejectionReason}",
            )
        }

        val durationMs = windowDurationMs(peak, window, lastMs)
        val qualityResult =
            qualityScorer.score(
                correlation = if (matchResult.noTemplate) 1.0 else matchResult.correlation,
                prominence = peak.prominence,
                durationMs = durationMs,
                durationRatio = phaseResult.durationRatio,
            )
        if (!qualityResult.accepted) {
            return RepResult(
                repCounted = false,
                repNumber = repCount,
                rejectionReason = "Qualitaet zu niedrig (score=${"%.3f".format(qualityResult.score)})",
            )
        }

        repCount++
        templateMatcher.addToPool(peak.window)
        trackForAdaptation(peak.prominence, durationMs)
        return RepResult(
            repCounted = true,
            repNumber = repCount,
            qualityScore = qualityResult.score,
            correlation = if (matchResult.noTemplate) null else matchResult.correlation,
            durationSamples = window.size,
            durationMs = durationMs,
            prominence = peak.prominence,
        )
    }

    /**
     * Umbauplan Phase 2.5: Rep-Dauer aus den Frame-TIMESTAMPS, nicht aus der
     * Sample-Anzahl.
     *
     * Der Peak beginnt bei `peak.timestampMs - peak.durationMs` (Anfang der
     * Exkursion, siehe [PeakEvent.durationMs]) und das Pending-Fenster endet
     * beim letzten aufgenommenen Frame ([pendingLastMs]). Bei Paketverlust
     * fehlen Samples, die physische Zeit vergeht aber weiter — die frueher
     * hier verwendete Rechnung `window.size / sampleRateHz` unterschaetzte
     * die Dauer dann systematisch. Das wirkte doppelt schaedlich: der
     * `tempoScore` (20 % Gewicht) verwarf gute Reps, und die adaptive
     * Refraktaerzeit sank, was Doppelzaehlungen wahrscheinlicher machte.
     *
     * Rueckfall auf die Sample-Rechnung nur, wenn die Timestamps keine
     * positive Spanne ergeben (Fake-Provider ohne Zeitbasis, Tests).
     */
    private fun windowDurationMs(
        peak: PeakEvent,
        window: List<Double>,
        lastMs: Long,
    ): Long {
        val excursionStartMs = peak.timestampMs - peak.durationMs
        val spanMs = lastMs - excursionStartMs
        if (spanMs > 0) return spanMs
        return (window.size * (1_000.0 / peakDetector.sampleRateHz)).toLong()
    }

    private fun trackForAdaptation(
        prominence: Double,
        durationMs: Long,
    ) {
        recentDurationsMs.add(durationMs.toDouble())
        recentProminences.add(prominence)
        if (recentDurationsMs.size > 10) {
            recentDurationsMs.removeAt(0)
            recentProminences.removeAt(0)
        }
        if (recentDurationsMs.size >= 3) {
            val avgDurationMs = recentDurationsMs.average()
            val avgProminence = recentProminences.average()
            qualityScorer.updateExpectations(
                expectedDurationMs = avgDurationMs,
                expectedProminence = avgProminence,
            )
            // Punkt 6: adaptive Refraktaerzeit folgt der echten Rep-Dauer.
            peakDetector.updateExpectedDurationMs(avgDurationMs)
        }
    }

    /**
     * Umbauplan Phase 2.6: verwirft einen laufenden Peak/Pending-Rep nach
     * einer grossen Zeitluecke. Der Filterzustand wird vom Aufrufer
     * (Pipeline) separat neu eingeschwungen.
     */
    fun abortPending() {
        pendingPeak = null
        pendingWindow = null
        pendingStartMs = 0L
        pendingLastMs = 0L
        pendingStartMin = 0.0
        pendingWentBelowStartMin = false
        pendingSawNegative = false
        recentAccelPeakTimestamps.clear()
        peakDetector.reset()
        accelPeakDetector?.reset()
    }

    /** Resets counter and detector state (new session / exercise switch). */
    fun reset() {
        repCount = 0
        recentDurationsMs.clear()
        recentProminences.clear()
        pendingPeak = null
        pendingWindow = null
        pendingStartMs = 0L
        pendingLastMs = 0L
        pendingStartMin = 0.0
        pendingWentBelowStartMin = false
        pendingSawNegative = false
        recentAccelPeakTimestamps.clear()
        peakDetector.reset()
        accelPeakDetector?.reset()
    }

    fun setTemplate(template: List<Double>) = templateMatcher.setTemplate(template)

    /** Umbauplan Phase 1.4: feeds the calibrated threshold directly. */
    fun updateThreshold(
        theta: Double,
        expectedDurationMs: Double? = null,
    ) = peakDetector.updateThreshold(theta, expectedDurationMs)

    /** P2-Fix #21: reicht die gemessene Abtastrate an beide Detektoren. */
    fun updateSampleRate(rateHz: Double) {
        peakDetector.updateSampleRate(rateHz)
        accelPeakDetector?.updateSampleRate(rateHz)
    }

    val hasTemplate: Boolean
        get() = templateMatcher.hasTemplate
}
