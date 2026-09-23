package com.dropsync.core.testing

import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.sensor.SetDiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P2-17/RC-7: Test-Doppel des Satz-Diagnose-Logs. Zeichnet alle Aufrufe auf
 * (Reihenfolge, Inhalt) und stellt den letzten Wert als [last] bereit.
 */
class FakeSetDiagnosticsLog : SetDiagnosticsLog {
    private val _last = MutableStateFlow<SetDiagnostics?>(null)
    override val last: StateFlow<SetDiagnostics?> = _last.asStateFlow()

    val recorded = mutableListOf<SetDiagnostics>()

    override fun record(diagnostics: SetDiagnostics) {
        recorded.add(diagnostics)
        _last.value = diagnostics
    }
}
