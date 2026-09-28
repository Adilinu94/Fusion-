package com.dropsync.core.database.di

import android.content.Context
import android.util.Log
import com.dropsync.core.database.DropSyncDatabase
import java.io.File

/**
 * Sichert eine nicht oeffenbare Datenbank, statt sie zu loeschen
 * (2026-09-27, Befund 4.8).
 *
 * **Warum es das gibt:** `Room.databaseBuilder(...).build()` oeffnet die
 * Datei nicht, sondern validiert das Schema beim ersten Zugriff. Eine
 * fehlende Migration wirft dann mitten im Betrieb. Ohne Recovery bleibt
 * die App bei jedem Start unbenutzbar, bis der Nutzer die App-Daten
 * manuell loescht — und damit auch das Trainingsjournal, die importierten
 * Marker und die Playlists.
 *
 * **Warum verschieben statt loeschen:** die Trainingsdaten sind nicht
 * rekonstruierbar (die Audio-Dateien liegen zwar auf dem Geraet, das
 * Journal nicht). Die Datei zu sichern kostet ein paar Megabyte und
 * laesst die Wiederherstellung zu.
 *
 * Room legt drei Dateien an: `<name>`, `<name>-wal` und `<name>-shm`.
 * Verschoben wird die Hauptdatei; die beiden Begleitdateien werden
 * mitverschoben, damit die frische Datenbank nicht an einem veralteten
 * WAL-Stand scheitert.
 */
internal object Quarantine {
    private const val LOG_TAG = "DropSyncDatabase"

    /**
     * Verschiebt die defekte Datenbank nach `<name>.broken-<zeitstempel>`
     * und gibt den neuen Namen (oder `null` bei einem IO-Fehler) zurueck.
     */
    fun renameBrokenDatabase(context: Context): String? {
        val name = DropSyncDatabase.NAME
        val files = context.getDatabasePath(name)
        val stamp = System.currentTimeMillis()
        val targetName = "$name.broken-$stamp"
        return try {
            val ok = files.renameTo(context.getDatabasePath(targetName))
            // WAL und SHM gehoeren zum alten Stand. Bleiben sie liegen,
            // spielt SQLite sie in die frische Datenbank zurueck und die
            // Validierung scheitert erneut.
            context.getDatabasePath("$name-wal").delete()
            context.getDatabasePath("$name-shm").delete()
            if (ok) targetName else null
        } catch (failure: Exception) {
            Log.e(LOG_TAG, "Defekte Datenbank nicht sicherbar", failure)
            null
        }
    }

    /** Loescht eine gesicherte Datei samt Begleitdateien. */
    fun deleteQuarantine(
        context: Context,
        name: String,
    ) {
        val base = context.getDatabasePath(name)
        base.delete()
        File(base.parentFile, "$name-wal").delete()
        File(base.parentFile, "$name-shm").delete()
    }
}
