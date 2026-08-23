package com.dropsync.feature.progress

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Zahlenformate gemaess CONTEXT E4c / UI-Vertrag R2b:
 * - Gewicht ganzzahlig ohne Dezimalstelle (`95 kg`), krumme Werte mit einer
 *   Stelle (`92,5 kg`). `95,0 kg` ist verboten; krumme Werte werden nie
 *   stillschweigend gerundet.
 * - Volumen unter 1000 kg ganzzahlig in kg, ab 1000 kg in Tonnen mit einer
 *   Dezimalstelle. Fester Schwellwert am gerundeten Wert, nicht dynamisch.
 * - Reps immer ganzzahlig.
 *
 * Dezimaltrennzeichen aus dem Locale des Nutzers
 * (`Locale.getDefault()` als Default-Parameter), nicht `Locale.ROOT` —
 * deutsche Nutzer sehen `12,4 t`, nicht `12.4 t`.
 *
 * Die Gramm-Basis verhindert Gleitkomma-Ungenauigkeit beim Ganzzahl-Vergleich
 * (dieselbe Disziplin, die der Kern spaeter am Eingang fordert, E1).
 */
internal object ProgressFormatters {
    fun weight(
        kg: Double,
        locale: Locale = Locale.getDefault(),
    ): String {
        val grams = (kg * 1000).roundToLong()
        return if (grams % 1000 == 0L) {
            String.format(locale, "%.0f kg", grams / 1000.0)
        } else {
            String.format(locale, "%.1f kg", grams / 1000.0)
        }
    }

    fun volume(
        kg: Double,
        locale: Locale = Locale.getDefault(),
    ): String =
        if (kg.roundToLong() < 1000) {
            String.format(locale, "%.0f kg", kg)
        } else {
            String.format(locale, "%.1f t", kg / 1000.0)
        }

    fun weightTimesReps(
        kg: Double,
        reps: Int,
        locale: Locale = Locale.getDefault(),
    ): String = "${weight(kg, locale)} × $reps"
}
