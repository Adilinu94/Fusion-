package com.dropsync.feature.workout

import com.dropsync.domain.sensor.calibration.CalibrationFailure

/**
 * UI-Fehler des Kalibrierungs-Wizards — P3-Fix #27.
 *
 * [GateFailure] traegt den typisierten Domain-Fehler samt Messwerten. Die
 * beiden anderen Faelle entstehen erst beim Orchestrieren bzw. Speichern im
 * ViewModel. Keiner der Faelle enthaelt Text; die Composable-Schicht kennt
 * Locale und Ressourcen und rendert ihn dort.
 */
internal sealed interface CalibrationUiError {
    data class GateFailure(
        val failure: CalibrationFailure,
    ) : CalibrationUiError

    data object Incomplete : CalibrationUiError

    data object SaveFailed : CalibrationUiError
}
