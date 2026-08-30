package com.dropsync.domain.sensor

/**
 * Schaetzt die TATSAECHLICHE Abtastrate des Sensorstroms.
 *
 * Hintergrund (P2-Fix): die gesamte Zaehl-Pipeline war auf `50.0` Hz
 * verdrahtet — als Default in [SignalChain], [PeakDetector],
 * [ExerciseEngineConfig] und als Konstante im Kalibrierungs-Refiner. Die
 * echte Rate ergibt sich aber erst aus dem Zusammenspiel von
 * Firmware-Takt, BLE-Uebertragung und dem Jitterbuffer-Tick; Paketverlust
 * und verworfene Frames druecken sie zusaetzlich. Jede Filter-Zeitkonstante
 * (One-Euro-Alpha, Envelope-Decay), jede Fensterlaenge und jede daraus
 * abgeleitete Dauer haengt an diesem Wert.
 *
 * Die Schaetzung nutzt den MEDIAN der Sample-Abstaende, nicht den
 * Mittelwert: ein einzelner 300-ms-Gap nach Paketverlust wuerde den
 * Mittelwert massiv verzerren, den Median praktisch nicht.
 *
 * Rein JVM, zustandsbehaftet, nicht threadsicher — wie der Rest der
 * Pipeline auf einen Consumer ausgelegt.
 */
class SampleRateEstimator(
    /** Anzahl der Abstaende, ueber die der Median laeuft. */
    private val windowSize: Int = DEFAULT_WINDOW,
    /** Startwert, bis genug Abstaende vorliegen. */
    private val nominalRateHz: Double = NOMINAL_RATE_HZ,
) {
    private val deltas = ArrayDeque<Long>(windowSize)
    private var lastTimestampMs: Long? = null

    /** Anzahl bisher beobachteter Abstaende (Diagnose). */
    var observedDeltas = 0
        private set

    /**
     * Aktuelle Schaetzung in Hz. Vor [MIN_DELTAS_FOR_ESTIMATE] Abstaenden
     * gilt [nominalRateHz]; danach der Median-basierte Wert, begrenzt auf
     * einen physikalisch sinnvollen Bereich.
     */
    val estimatedRateHz: Double
        get() {
            if (deltas.size < MIN_DELTAS_FOR_ESTIMATE) return nominalRateHz
            val median = medianDelta() ?: return nominalRateHz
            if (median <= 0) return nominalRateHz
            return (1_000.0 / median).coerceIn(MIN_RATE_HZ, MAX_RATE_HZ)
        }

    /**
     * True, sobald die Schaetzung auf genug Abstaenden beruht, um die
     * Pipeline damit umzukonfigurieren.
     */
    val isConfident: Boolean
        get() = deltas.size >= MIN_DELTAS_FOR_ESTIMATE

    /**
     * Nimmt einen Sample-Timestamp auf. Abstaende jenseits von
     * [MAX_PLAUSIBLE_DELTA_MS] gelten als Luecke (Paketverlust,
     * Reconnect, Set-Pause) und gehen NICHT in die Schaetzung ein — sonst
     * wuerde die Pipeline nach jeder Stoerung eine falsche Rate lernen.
     */
    fun onSample(timestampMs: Long) {
        val last = lastTimestampMs
        lastTimestampMs = timestampMs
        if (last == null) return
        val delta = timestampMs - last
        if (delta <= 0 || delta > MAX_PLAUSIBLE_DELTA_MS) return
        deltas.addLast(delta)
        observedDeltas++
        while (deltas.size > windowSize) deltas.removeFirst()
    }

    /** Verwirft die Historie (Reconnect, Uebungswechsel, neues Set). */
    fun reset() {
        deltas.clear()
        lastTimestampMs = null
        observedDeltas = 0
    }

    private fun medianDelta(): Long? {
        if (deltas.isEmpty()) return null
        val sorted = deltas.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    companion object {
        /** Nominale Rate der v2-Firmware (protocol.yaml: 20 ms je Sample). */
        const val NOMINAL_RATE_HZ = 50.0

        /** Fenster ueber ~2 s bei Nominalrate. */
        const val DEFAULT_WINDOW = 100

        /** Ab so vielen Abstaenden ist der Median belastbar. */
        const val MIN_DELTAS_FOR_ESTIMATE = 20

        /**
         * Grosser Abstand = Luecke, kein regulaerer Takt. Passend zu
         * [ExerciseEnginePipeline.LARGE_GAP_MS], damit beide dieselbe
         * Grenze verwenden.
         */
        const val MAX_PLAUSIBLE_DELTA_MS = 150L

        /** Unter 10 Hz ist Rep-Zaehlung nicht sinnvoll moeglich. */
        const val MIN_RATE_HZ = 10.0

        /** Ueber 200 Hz liefert kein von uns unterstuetzter Sensor. */
        const val MAX_RATE_HZ = 200.0
    }
}
