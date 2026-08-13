package com.dropsync.domain.sensor

/** Result of the rep pipeline for one frame (port of rep_counter.dart). */
data class RepResult(
    val repCounted: Boolean,
    val repNumber: Int,
    val qualityScore: Double? = null,
    val correlation: Double? = null,
    val rejectionReason: String? = null,
    val durationSamples: Int? = null,
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
 * SHADOW ONLY (design doc Phase 4 step 6): this counter runs alongside the
 * classic engine once a calibration profile exists, but never counts live.
 * Promoted to live counting only after its own shadow DoD (section 11b).
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
    private val accelVoteWindowSamples: Int = 5,
) {
    var repCount: Int = 0
        private set

    private val recentDurations = mutableListOf<Double>()
    private val recentProminences = mutableListOf<Double>()

    // Pending phase-window extension: the peak-detector window ends shortly
    // after the falling edge and mostly covers the concentric half-wave.
    // PhaseValidator needs both half-waves, so the counting decision is
    // deferred until the eccentric half-wave completed (or the safety
    // limit hit). Peak detection itself is untouched.
    private var pendingPeak: PeakEvent? = null
    private var pendingWindow: MutableList<Double>? = null
    private var pendingStartMin: Double = 0.0
    private var pendingWentBelowStartMin = false
    private var pendingExtraSamples = 0

    /** Punkt 4: Accel-Peaks (Sample-Index) der letzten Frames. */
    private val recentAccelPeakIndexes = ArrayDeque<Int>()

    /** Processes ONE frame through the whole pipeline. */
    fun process(frame: ProcessedFrame): RepResult {
        val peak = peakDetector.process(frame)
        accelPeakDetector?.process(frame)?.let { accelPeak ->
            recentAccelPeakIndexes.addLast(accelPeak.sampleIndex)
            while (recentAccelPeakIndexes.size > 600) {
                recentAccelPeakIndexes.removeFirst()
            }
        }

        if (peak != null) {
            var finishedOld: RepResult? = null
            if (pendingPeak != null) {
                finishedOld = finalizePending()
            }
            startPending(peak)
            if (finishedOld != null) return finishedOld
            if (pendingComplete()) return finalizePending()
            return RepResult.NONE
        }

        val window = pendingWindow ?: return RepResult.NONE
        val value = frame.smoothedGp
        window.add(value)
        pendingExtraSamples++
        if (value < pendingStartMin) pendingWentBelowStartMin = true

        if (pendingComplete()) return finalizePending()
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
        return recentAccelPeakIndexes.any { kotlin.math.abs(it - peak.sampleIndex) <= accelVoteWindowSamples }
    }

    private fun startPending(peak: PeakEvent) {
        pendingPeak = peak
        pendingWindow = peak.window.toMutableList()
        pendingStartMin = peak.window.min()
        pendingWentBelowStartMin = false
        pendingExtraSamples = 0
    }

    private fun pendingComplete(): Boolean {
        val window = pendingWindow ?: return false
        // Punkt 4: bei aktivem Accel-Voting erst schliessen, wenn der
        // Accel-Peak im Toleranzfenster liegt - er feuert wegen seiner
        // Falling-Debounce einige Samples spaeter als der Gyro-Peak.
        if (accelPeakDetector != null && !accelVotePassed(pendingPeak!!)) {
            return pendingExtraSamples >= maxExtraPhaseSamples()
        }
        val hasNegative = window.any { it < 0 }
        if (!hasNegative) return true
        return (pendingWentBelowStartMin && window.last() >= 0) ||
            pendingExtraSamples >= maxExtraPhaseSamples()
    }

    /**
     * Punkt 6: Pending-Fenster-Grenze dynamisch aus der erwarteten
     * Rep-Dauer (2x, begrenzt auf 60-300 Samples), statt fix 120.
     */
    private fun maxExtraPhaseSamples(): Int =
        (peakDetector.expectedDurationSamples * 2.0).toInt().coerceIn(60, 300)

    private fun finalizePending(): RepResult {
        val peak = pendingPeak!!
        val window = pendingWindow!!
        pendingPeak = null
        pendingWindow = null
        return decide(peak, window)
    }

    private fun decide(
        peak: PeakEvent,
        window: List<Double>,
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

        val qualityResult =
            qualityScorer.score(
                correlation = if (matchResult.noTemplate) 1.0 else matchResult.correlation,
                prominence = peak.prominence,
                durationSamples = window.size,
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
        trackForAdaptation(peak.prominence, window.size)
        return RepResult(
            repCounted = true,
            repNumber = repCount,
            qualityScore = qualityResult.score,
            correlation = if (matchResult.noTemplate) null else matchResult.correlation,
            durationSamples = window.size,
            prominence = peak.prominence,
        )
    }

    private fun trackForAdaptation(
        prominence: Double,
        durationSamples: Int,
    ) {
        recentDurations.add(durationSamples.toDouble())
        recentProminences.add(prominence)
        if (recentDurations.size > 10) {
            recentDurations.removeAt(0)
            recentProminences.removeAt(0)
        }
        if (recentDurations.size >= 3) {
            val avgDuration = recentDurations.average()
            val avgProminence = recentProminences.average()
            qualityScorer.updateExpectations(
                expectedDurationSamples = avgDuration,
                expectedProminence = avgProminence,
            )
            // Punkt 6: adaptive Refraktaerzeit folgt der echten Rep-Dauer.
            peakDetector.updateExpectedDuration(avgDuration)
        }
    }

    /** Resets counter and detector state (new session / exercise switch). */
    fun reset() {
        repCount = 0
        recentDurations.clear()
        recentProminences.clear()
        pendingPeak = null
        pendingWindow = null
        pendingStartMin = 0.0
        pendingWentBelowStartMin = false
        pendingExtraSamples = 0
        recentAccelPeakIndexes.clear()
        peakDetector.reset()
        accelPeakDetector?.reset()
    }

    fun setTemplate(template: List<Double>) = templateMatcher.setTemplate(template)

    /** Feeds the calibration levels (SPK/NPK) into the peak detector. */
    fun updateLevels(
        spk: Double? = null,
        npk: Double? = null,
        expectedDurationSamples: Double? = null,
    ) = peakDetector.updateLevels(spk, npk, expectedDurationSamples)

    val hasTemplate: Boolean
        get() = templateMatcher.hasTemplate
}
