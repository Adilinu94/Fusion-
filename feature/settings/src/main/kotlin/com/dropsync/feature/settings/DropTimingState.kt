package com.dropsync.feature.settings

/**
 * Latenz der aktuellen Ausgabe-Route fuers Drop-Timing.
 *
 * @property latencyMs angenommene Ausgabe-Latenz in ms (Tabellenwert oder eingestellt)
 * @property calibrated true, wenn die Person den Wert eingestellt hat; false = nur Schaetzung
 */
data class DropTimingState(
    val latencyMs: Long,
    val calibrated: Boolean,
) {
    companion object {
        /** Einstellbarer Bereich: Lautsprecher/Kabel liegen bei 25-40 ms, Bluetooth-Codecs bis ca. 300 ms. */
        const val MIN_MS = 0L
        const val MAX_MS = 400L
        const val STEP_MS = 10L
        val RANGE_MS: LongRange = MIN_MS..MAX_MS
    }
}
