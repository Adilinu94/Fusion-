package com.dropsync.core.common

/**
 * Geschlossener Fehlervertrag der App (Bauplan Schritt 2.2).
 *
 * Repository-Methoden geben niemals Infrastruktur-Exceptions bis in ein
 * Composable weiter, sondern bilden Fehler auf genau diese Faelle ab.
 */
sealed interface AppError {
    /** Eine benoetigte Laufzeitberechtigung wurde verweigert. */
    data class PermissionDenied(
        val permission: String,
    ) : AppError

    /** Eine Mediendatei oder Content-URI ist nicht (mehr) lesbar. */
    data class MediaUnavailable(
        val mediaStoreId: Long?,
    ) : AppError

    /** Ein importierter Marker konnte keinem lokalen Song zugeordnet werden. */
    data class MarkerUnmatched(
        val markerLabel: String?,
    ) : AppError

    /** Es laeuft bereits eine Timer-Session in RUNNING, PREPARING oder PAUSED. */
    data object TimerConflict : AppError

    /** TTS ist nicht initialisiert, nicht verfuegbar oder die Sprache fehlt. */
    data object TtsUnavailable : AppError

    /** Eine Datenbankoperation ist fehlgeschlagen; die Transaktion wurde verworfen. */
    data class DatabaseFailure(
        val operation: String,
    ) : AppError

    /** Nicht klassifizierter Fehler; Details nur im Debug-Log. */
    data class Unknown(
        val debugMessage: String?,
    ) : AppError
}
