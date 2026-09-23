package com.dropsync.feature.workout

import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.SetLogHaptics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A1 (T-1): Einmal-Ereignisse des Satz-Loggings fuer die Snackbar.
 * [Logged] traegt die IDs fuer das Undo, [LogFailed] den Fehlerhinweis.
 */
sealed interface SetLogEvent {
    /** Satz gespeichert (Snackbar "Satz gespeichert" + Rueckgaengig). */
    data class Logged(
        val setId: Long,
        val exerciseId: Long,
    ) : SetLogEvent

    /** Undo ausgefuehrt; der Satz ist entfernt. */
    data class Undone(
        val exerciseId: Long,
    ) : SetLogEvent

    /** Speichern fehlgeschlagen (Snackbar "Log-Fehler"). */
    data object LogFailed : SetLogEvent
}

/** Was ein Undo entfernt hat (UI-Buchhaltung: Live-Zaehler, Listen). */
data class UndoOutcome(
    val exerciseId: Long,
    val reps: Int,
)

/**
 * A1/5.6/5.15: kapselt den Erfolgspfad des Satz-Loggings — Log, Undo,
 * Haptik und Ereignisse. Der Controller haelt keinen eigenen Scope; der
 * Aufrufer (ViewModel) laesst die suspendierenden Methoden laufen.
 *
 * - Haptik feuert genau einmal und nur nach DAO-Erfolg.
 * - [events] laeuft ueber einen gepufferten Kanal: ein Log ohne aktiven
 *   Collector verliert das Ereignis nicht.
 * - Der Undo-Zustand ist bewusst fluechtig; das ViewModel verwirft ihn bei
 *   Uebungswechsel, Sitzungsende und `onCleared`.
 */
class SetLogController(
    private val flatSetRepository: FlatSetRepository,
    private val haptics: SetLogHaptics,
) {
    private val _events = Channel<SetLogEvent>(Channel.BUFFERED)
    val events: Flow<SetLogEvent> = _events.receiveAsFlow()

    private var lastLogged: LoggedSet? = null

    /**
     * Speichert den Satz; liefert das Repository-Ergebnis fuer die
     * UI-Buchhaltung. Bei Erfolg: Undo-Zustand setzen, Haptik, [SetLogEvent.Logged].
     */
    suspend fun logSet(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ): AppResult<Long> {
        val result = flatSetRepository.logSet(exerciseId, weightMilliKg, reps)
        when (result) {
            is AppResult.Success -> {
                lastLogged = LoggedSet(setId = result.value, exerciseId = exerciseId, reps = reps)
                haptics.confirm()
                _events.send(SetLogEvent.Logged(result.value, exerciseId))
            }

            is AppResult.Failure -> {
                _events.send(SetLogEvent.LogFailed)
            }
        }
        return result
    }

    /**
     * Macht den zuletzt geloggten Satz rueckgaengig (loescht ihn; die PRs
     * berechnet das Repository aus der Resthistorie neu).
     *
     * null, wenn es nichts rueckzunehmen gibt — kein Log oder bereits
     * zurueckgenommen. Ein Fehler kommt als [AppResult.Failure] zurueck.
     */
    suspend fun undoLast(): AppResult<UndoOutcome>? {
        val target = lastLogged ?: return null
        return when (val result = flatSetRepository.deleteSet(target.setId)) {
            is AppResult.Success -> {
                lastLogged = null
                _events.send(SetLogEvent.Undone(target.exerciseId))
                AppResult.success(UndoOutcome(exerciseId = target.exerciseId, reps = target.reps))
            }

            is AppResult.Failure -> {
                AppResult.failure(result.error)
            }
        }
    }

    /** Verwirft ein offenes Undo (Uebungswechsel, Sitzungsende, onCleared). */
    fun clearUndo() {
        lastLogged = null
    }

    private data class LoggedSet(
        val setId: Long,
        val exerciseId: Long,
        val reps: Int,
    )
}
