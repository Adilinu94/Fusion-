package com.dropsync.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.domain.library.DropTargetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-Persistenz des bevorzugten DropSync-Ziels (P2-21).
 *
 * Ein Eintrag je Song: `drop_target_<songId> = markerId`. Die Zuordnung
 * ist absichtlich weich — sie zeigt auf eine Marker-ID, keine
 * Fremdschluessel-Beziehung. Existiert der Marker nicht mehr (geloescht
 * oder deaktiviert), faellt die Planung ohne Aufraeumen auf den
 * naechsten Marker zurueck.
 */
class DropTargetStore(
    private val dataStore: DataStore<Preferences>,
) : DropTargetRepository {
    override fun observeTargetMarkerId(songId: Long): Flow<Long?> =
        dataStore.data.map { prefs -> prefs[keyFor(songId)] }

    override val targets: Flow<Map<Long, Long>> =
        dataStore.data.map { prefs ->
            prefs
                .asMap()
                .mapNotNull { (key, value) ->
                    if (key.name.startsWith(PREFIX) && value is Long) {
                        key.name
                            .removePrefix(PREFIX)
                            .toLongOrNull()
                            ?.let { songId -> songId to value }
                    } else {
                        null
                    }
                }.toMap()
        }

    override suspend fun setTarget(
        songId: Long,
        markerId: Long,
    ): AppResult<Unit> =
        try {
            dataStore.edit { prefs -> prefs[keyFor(songId)] = markerId }
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.DatabaseFailure("setDropTarget"))
        }

    override suspend fun clearTarget(songId: Long): AppResult<Unit> =
        try {
            dataStore.edit { prefs -> prefs.remove(keyFor(songId)) }
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.DatabaseFailure("clearDropTarget"))
        }

    private fun keyFor(songId: Long) = longPreferencesKey("$PREFIX$songId")

    companion object {
        const val DATA_STORE_NAME = "drop_target_prefs"

        /** Praefix der Schluessel; die Planung liest alle Eintraege auf einmal. */
        const val PREFIX = "drop_target_"
    }
}
