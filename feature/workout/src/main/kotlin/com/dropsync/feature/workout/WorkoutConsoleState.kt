package com.dropsync.feature.workout

/**
 * A.4/RC-5: Modus der Train-Konsole (UI-Handbuch 4.1). Die `when`-Struktur in
 * [TrainScreen] haengt an genau diesem Modus statt an einer Kette von
 * Booleans ("Satz-Karte ODER Sensor-Karte ODER Waveform").
 *
 * `EXERCISE_DONE` aus dem Handbuch fehlt bewusst: Die App kennt keinen
 * Session-Abschluss (Umbauhandbuch 24.1). "Uebung abschliessen" setzt die
 * Auswahl zurueck — die Konsole ist dann wieder [IDLE] mit der Uebungszeile.
 */
enum class WorkoutConsoleMode {
    /** Keine Uebung gewaehlt: leere Konsole, die Uebungszeile fuehrt. */
    IDLE,

    /** Uebung gewaehlt: Gewicht/Reps eingeben, genau eine Primaeraktion. */
    SET_ENTRY,

    /** Pause laeuft: die Rest-Console ist der Hero. */
    REST_RUNNING,

    /** Landung ausgefuehrt: Rest-Console mit GO-Overlay (keine Navigation). */
    GO_CUE,
}

/**
 * RC-5 / UI-Handbuch 7.4: Herkunft der Rep-Zahl im Hero. Genau eine Zahl mit
 * genau einer Quelle — statt Zaehler in der Sensor-Karte und Eingabe im Hero
 * nebeneinander zu zeigen. Die Texte liegen in `strings.xml`.
 */
sealed interface RepsSource {
    /** Vom Sensor gezaehlt und unberuehrt uebernommen. */
    data object Sensor : RepsSource

    /** Vom Sensor gezaehlt, danach vom Nutzer korrigiert ([counted] = Original). */
    data class SensorWithManualCorrection(
        val counted: Int,
    ) : RepsSource

    /** Per Hand eingetragen — kein Zaehlstand im Spiel. */
    data object Manual : RepsSource

    /**
     * Design 8.1, Fehlerfall Sensorabriss: der Chip ist weg, die Zahl kommt
     * jetzt per `+/-`. Die Zeile traegt dann auch die Handlung ("Reps per +/-").
     */
    data object SensorDisconnected : RepsSource
}

/**
 * A.4: Modus-Ableitung als reine Funktion — ohne Compose testbar. [dropLanded]
 * ist der Landungs-Zustand des DropSync-Koordinators; die Rest-Console zeigt
 * dafuer ihr GO-Overlay.
 */
internal fun workoutConsoleMode(
    hasExercise: Boolean,
    restActive: Boolean,
    dropLanded: Boolean,
): WorkoutConsoleMode =
    when {
        restActive && dropLanded -> WorkoutConsoleMode.GO_CUE
        restActive -> WorkoutConsoleMode.REST_RUNNING
        hasExercise -> WorkoutConsoleMode.SET_ENTRY
        else -> WorkoutConsoleMode.IDLE
    }

/**
 * RC-5: Ableitung der Rep-Quelle (UI-Handbuch 7.4). [lastCounted] ist der
 * Zaehlstand des letzten gestoppten Live-Satzes, [edited] eine aktive
 * Nutzer-Korrektur (D3/ADR-0014: nur editierte Werte sind unabhaengige
 * Wahrheit), [dropped] ein Stream-Abriss seit dem letzten Zuruecksetzen.
 *
 * Reihenfolge ist Absicht: ein vorhandener Zaehlstand schlaegt den Abriss —
 * eine korrigierte Zahl bleibt "korrigiert", auch wenn der Chip inzwischen
 * weg ist. Ohne Zaehlstand entscheidet nur, ob der Chip gerade fehlt.
 */
internal fun repsSourceOf(
    edited: Boolean,
    lastCounted: Int?,
    streaming: Boolean,
    dropped: Boolean,
): RepsSource =
    when {
        lastCounted != null && edited -> RepsSource.SensorWithManualCorrection(lastCounted)
        lastCounted != null -> RepsSource.Sensor
        dropped && !streaming -> RepsSource.SensorDisconnected
        else -> RepsSource.Manual
    }
