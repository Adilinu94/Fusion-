package com.dropsync.domain.library

import kotlinx.coroutines.flow.Flow

/**
 * Ordnerauswahl der Bibliothek (Poweramp "Folders and Library", Umbau
 * Punkt 3): der Nutzer entscheidet, welche Musikordner in die Bibliothek
 * aufgenommen werden. Gespeichert wird die Ausschlussmenge (relative_path),
 * damit neu hinzukommende Ordner standardmaessig sichtbar bleiben.
 *
 * Titel in ausgeschlossenen Ordnern werden beim Abgleich als nicht
 * verfuegbar gefuehrt und verschwinden dadurch aus allen Ansichten, weil
 * saemtliche Browse-Abfragen `is_available = 1` filtern. Datensaetze,
 * Marker und Historie bleiben erhalten (Bauplan 4.4).
 */
interface MusicFolderFilterRepository {
    /** Ausgeschlossene Ordner (relative_path, exakter Treffer); leer = alle sichtbar. */
    val excludedFolders: Flow<Set<String>>

    suspend fun setExcludedFolders(paths: Set<String>)
}
