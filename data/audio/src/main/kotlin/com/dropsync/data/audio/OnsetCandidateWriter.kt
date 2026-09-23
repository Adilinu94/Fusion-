package com.dropsync.data.audio

import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.LinkMethod
import com.dropsync.core.model.MarkerSource

/**
 * Schreibt Onset-Kandidaten als unbestaetigte Marker (Phase 5, A10).
 *
 * Eigene Klasse wie [TrackAnalysisPersister]: der Worker bleibt duenn,
 * und die Marker-Form (Quelle, `isEnabled`, Link-Methode) ist ohne
 * WorkManager/Hilt testbar. Ein erneuter Lauf ersetzt die alten, noch
 * unbestaetigten Kandidaten desselben Songs; bestaetigte Marker bleiben
 * unberuehrt. Nie Automatik: aktiv wird ein Kandidat erst durch die
 * bestaetigende Aktion in der Review-Liste.
 */
class OnsetCandidateWriter(
    private val markerDao: MarkerDao,
    private val clock: Clock,
) {
    /**
     * Ersetzt die unbestaetigten `AUTO_DETECTED`-Kandidaten von [song]
     * durch [onsetCandidatesMs] (zeitlich aufsteigend, so liefert sie der
     * Detektor). `Drop 1` ist damit der frueheste Kandidat.
     */
    suspend fun replacePending(
        song: SongEntity,
        onsetCandidatesMs: List<Long>,
    ) {
        // Derselbe Fingerprint, den die Bibliothek fuer den Song fuehrt.
        val fingerprint =
            listOf(
                song.relativePath,
                song.displayName,
                song.sizeBytes.toString(),
                song.durationMs.toString(),
            ).joinToString(FINGERPRINT_SEPARATOR)
        // D5/A3: eine Uhrzeit fuer den ganzen Lauf; das Ersetzen (DELETE +
        // Inserts) laeuft als EINE Transaktion im DAO — kein halber Zustand,
        // wenn ein Insert abbricht.
        val now = clock.epochMillis()
        val markers =
            onsetCandidatesMs.mapIndexed { index, positionMs ->
                SongMarkerEntity(
                    sourceFingerprint = fingerprint,
                    label = "Drop ${index + 1}",
                    positionMs = positionMs,
                    source = MarkerSource.AUTO_DETECTED.name,
                    isEnabled = false,
                    createdAtEpochMs = now,
                )
            }
        markerDao.replacePendingCandidates(
            songId = song.mediaStoreId,
            source = MarkerSource.AUTO_DETECTED.name,
            markers = markers,
            linkMethod = LinkMethod.AUTO_DETECTED.name,
            linkedAtEpochMs = now,
        )
    }

    private companion object {
        /** Trennzeichen des Bibliotheks-Fingerprints (US, 0x1F). */
        const val FINGERPRINT_SEPARATOR = "\u001F"
    }
}
