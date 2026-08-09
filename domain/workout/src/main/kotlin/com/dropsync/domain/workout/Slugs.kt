package com.dropsync.domain.workout

/**
 * Erzeugung und Pruefung sprachneutraler Slugs (`canonicalName`, Schritt
 * 9.1). Slugs sind ausschliesslich `[a-z0-9]` mit `_` als Trenner; deutsche
 * Umlaute werden transliteriert, alle uebrigen Zeichen zu `_` reduziert.
 * Die Eindeutigkeitspruefung gegen die Datenbank uebernimmt das Repository.
 */
object Slugs {
    private val ALLOWED = Regex("[a-z0-9]+(_[a-z0-9]+)*")

    fun isValid(slug: String): Boolean = ALLOWED.matches(slug)

    fun fromDisplayName(name: String): String {
        val transliterated =
            name
                .trim()
                .lowercase()
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
        val base =
            buildString {
                for (c in transliterated) {
                    append(if (c in 'a'..'z' || c in '0'..'9') c else '_')
                }
            }.replace(Regex("_+"), "_").trim('_')
        return base.ifEmpty { "exercise" }
    }
}
