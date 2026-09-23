package com.dropsync.core.common.datastore

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences

/**
 * Zentrale DataStore-Factory (Befund 1.7a): Jeder DataStore bekommt einen
 * `ReplaceFileCorruptionHandler`, damit eine beschaedigte Datei (Kill waehrend
 * des Schreibens, defekte SD-Karte) nicht die App zum Crashen bringt, sondern
 * mit leeren Defaults neu beginnt.
 *
 * WICHTIG: `corruptionHandler` muss beim ERSTEN create() fuer die Datei
 * uebergeben werden — der Handler laesst sich nicht nachtraeglich an ein
 * bereits erstelltes Singleton haengen.
 */
fun createResilientPreferencesDataStore(produceFile: () -> java.io.File) =
    PreferenceDataStoreFactory.create(
        corruptionHandler =
            ReplaceFileCorruptionHandler {
                // Beschaedigte Datei verwerfen und mit leeren Defaults starten.
                emptyPreferences()
            },
        produceFile = produceFile,
    )
