package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.sensor.SetDiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RC-7: In-Memory-Log des letzten Satz-Diagnose-Snapshots. Kein Persistieren:
 * nach einem App-Neustart ist der letzte Satz ohnehin nicht mehr live
 * nachvollziehbar, und das Diagnose-Panel ist ein Entwickler-Werkzeug.
 */
@Singleton
class InMemorySetDiagnosticsLog
    @Inject
    constructor() : SetDiagnosticsLog {
        private val _last = MutableStateFlow<SetDiagnostics?>(null)
        override val last: StateFlow<SetDiagnostics?> = _last.asStateFlow()

        override fun record(diagnostics: SetDiagnostics) {
            _last.value = diagnostics
        }
    }
