package com.dropsync.feature.workout

import com.dropsync.domain.sensor.SensorConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * RC-5: Zustand der Rep-Quelle im Hero (UI-Handbuch 7.4) — eine eigene, kleine
 * Zustandsmaschine neben [TrainViewModel]: Zaehlstand des letzten Stopps,
 * Sensorabriss und Korrektur. Die Ableitung selbst liegt in [repsSourceOf]
 * und bleibt dort testbar; hier lebt nur das Gedaechtnis.
 */
internal class RepSourceTracker(
    scope: CoroutineScope,
    edited: StateFlow<Boolean>,
    connection: StateFlow<SensorConnectionState>,
) {
    private val lastCounted = MutableStateFlow<Int?>(null)
    private val sensorDropped = MutableStateFlow(false)

    /** Nur der Abriss eines zuvor wirklich streamenden Chips zaehlt. */
    private var wasStreaming = false

    /**
     * Eagerly wie die Zweitmeinung im ViewModel: der Wert wird direkt nach
     * dem Stop gebraucht und darf nicht vom Collector-Glueck abhaengen.
     */
    val source: StateFlow<RepsSource> =
        combine(edited, lastCounted, connection, sensorDropped) { isEdited, counted, state, dropped ->
            repsSourceOf(
                edited = isEdited,
                lastCounted = counted,
                streaming = state == SensorConnectionState.STREAMING,
                dropped = dropped,
            )
        }.stateIn(scope, SharingStarted.Eagerly, RepsSource.Manual)

    /** Nach einem Stop mit Zaehlstand: "Sensor erkannte n" bleibt verfuegbar. */
    fun onCountedSetStopped(counted: Int) {
        if (counted > 0) lastCounted.value = counted
    }

    /** Neuer Satz/neue Uebung: die Quelle startet ohne Alt-Zaehlstand. */
    fun onSetReset() {
        lastCounted.value = null
        sensorDropped.value = false
    }

    /**
     * Verbindungswechsel (Design 8.1): der erste, beim Start nachgelieferte
     * Zustand darf keinen Abriss vortaeuschen — gleiche Regel wie im
     * ActiveSetController (`streamProvenGood`).
     */
    fun onConnectionChanged(state: SensorConnectionState) {
        when {
            state == SensorConnectionState.STREAMING -> {
                wasStreaming = true
                sensorDropped.value = false
            }

            wasStreaming -> {
                wasStreaming = false
                sensorDropped.value = true
            }
        }
    }
}
