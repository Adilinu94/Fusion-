package com.dropsync.data.audio

import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.LinkedMarkerRow
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.LinkMethod
import com.dropsync.core.model.MarkerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation A10/Phase 5: Onset-Kandidaten landen als unbestaetigte
 * `AUTO_DETECTED`-Marker samt Link; ein erneuter Lauf ersetzt nur die
 * unbestaetigten Kandidaten desselben Songs. Der Writer ist bewusst ohne
 * WorkManager/Hilt testbar (wie [TrackAnalysisPersister]).
 */
class OnsetCandidateWriterTest {
    private val markerDao = RecordingMarkerDao()
    private val writer = OnsetCandidateWriter(markerDao = markerDao, clock = FixedOnsetClock)

    @Test
    fun `kandidaten werden als unbestaetigte auto_detected marker verlinkt`() =
        runTest {
            writer.replacePending(song(), listOf(1_000L, 30_000L, 90_000L))

            val markers = markerDao.markers.values.sortedBy { it.positionMs }
            assertEquals(3, markers.size)
            assertEquals(listOf(1_000L, 30_000L, 90_000L), markers.map { it.positionMs })
            assertEquals(listOf("Drop 1", "Drop 2", "Drop 3"), markers.map { it.label })
            markers.forEach { marker ->
                assertEquals(MarkerSource.AUTO_DETECTED.name, marker.source)
                assertFalse("Kandidaten sind nie automatisch aktiv", marker.isEnabled)
            }
            assertEquals(3, markerDao.links.size)
            markerDao.links.values.forEach { link ->
                assertEquals(LinkMethod.AUTO_DETECTED.name, link.linkMethod)
                assertEquals(SONG_ID, link.songId)
            }
        }

    @Test
    fun `fingerprint entspricht der bibliotheks-identitaet`() =
        runTest {
            writer.replacePending(song(), listOf(1_000L))

            // Pfad, Name, Groesse, Dauer — getrennt durch US (0x1F), exakt
            // wie MarkerRepositoryImpl.fingerprintOf().
            assertEquals(
                "Music/\u001Fsong.mp3\u001F5000000\u001F240000",
                markerDao.markers.values
                    .single()
                    .sourceFingerprint,
            )
        }

    @Test
    fun `erneuter lauf ersetzt nur die unbestaetigten kandidaten`() =
        runTest {
            writer.replacePending(song(), listOf(1_000L, 30_000L))
            addConfirmedMarker(positionMs = 5_000L)

            writer.replacePending(song(), listOf(60_000L))

            val remaining = markerDao.markers.values.sortedBy { it.positionMs }
            assertEquals(listOf(5_000L, 60_000L), remaining.map { it.positionMs })
            assertEquals(listOf(true, false), remaining.map { it.isEnabled })
        }

    @Test
    fun `leere kandidatenliste raeumt die alten kandidaten ab`() =
        runTest {
            writer.replacePending(song(), listOf(1_000L, 30_000L))

            writer.replacePending(song(), emptyList())

            assertTrue(markerDao.markers.isEmpty())
        }

    private fun song() =
        SongEntity(
            mediaStoreId = SONG_ID,
            contentUri = "content://media/external/audio/media/$SONG_ID",
            displayName = "song.mp3",
            relativePath = "Music/",
            durationMs = 240_000L,
            sizeBytes = 5_000_000L,
            dateModifiedSeconds = 1_000L,
            title = "Song",
            artist = null,
            album = null,
            isAvailable = true,
        )

    private suspend fun addConfirmedMarker(positionMs: Long) {
        val markerId =
            markerDao.insert(
                SongMarkerEntity(
                    sourceFingerprint = "Music/\u001Fsong.mp3\u001F5000000\u001F240000",
                    label = "Drop",
                    positionMs = positionMs,
                    source = MarkerSource.AUTO_DETECTED.name,
                    isEnabled = true,
                    createdAtEpochMs = 1_000L,
                ),
            )
        markerDao.insertLink(
            MarkerSongLinkEntity(
                markerId = markerId,
                songId = SONG_ID,
                linkMethod = LinkMethod.AUTO_DETECTED.name,
                linkedAtEpochMs = 1_000L,
            ),
        )
    }

    private companion object {
        const val SONG_ID = 7L
    }
}

private object FixedOnsetClock : Clock {
    override fun elapsedRealtimeMs(): Long = 1_000L

    override fun epochMillis(): Long = 1_000L
}

/**
 * Bildet nur die drei vom Writer benutzten DAO-Vertraege nach
 * (`insert`, `insertLink`, `deletePendingBySourceForSong`); alles andere
 * schlaegt laut fehl, damit ein unerwarteter Aufruf auffaellt.
 */
private class RecordingMarkerDao : MarkerDao {
    val markers = linkedMapOf<Long, SongMarkerEntity>()
    val links = linkedMapOf<Long, MarkerSongLinkEntity>()
    private var nextMarkerId = 1L
    private var nextLinkId = 1L

    override suspend fun insert(marker: SongMarkerEntity): Long {
        val id = nextMarkerId++
        markers[id] = marker.copy(id = id)
        return id
    }

    override suspend fun insertLink(link: MarkerSongLinkEntity): Long {
        val id = nextLinkId++
        links[id] = link.copy(id = id)
        return id
    }

    override suspend fun deletePendingBySourceForSong(
        songId: Long,
        source: String,
    ) {
        // Wie das DAO: nur unbestaetigte Marker der Quelle, die per Link
        // an genau diesen Song haengen.
        val linkedIds =
            links.values
                .filter { it.songId == songId }
                .map { it.markerId }
                .toSet()
        markers.values
            .filter { it.id in linkedIds && it.source == source && !it.isEnabled }
            .forEach { marker ->
                markers.remove(marker.id)
                links.values.filter { it.markerId == marker.id }.forEach { links.remove(it.id) }
            }
    }

    override suspend fun getById(id: Long): SongMarkerEntity? = unsupported()

    override suspend fun getByFingerprint(fingerprint: String): List<SongMarkerEntity> = unsupported()

    override suspend fun update(
        id: Long,
        label: String,
        positionMs: Long,
        isEnabled: Boolean,
    ): Unit = unsupported()

    override fun observeUnmatched(): Flow<List<SongMarkerEntity>> = unsupported()

    override suspend fun deleteLinkForMarker(markerId: Long): Unit = unsupported()

    override suspend fun getLinkForMarker(markerId: Long): MarkerSongLinkEntity? = unsupported()

    override suspend fun getEnabledMarkersForSong(songId: Long): List<SongMarkerEntity> = unsupported()

    override suspend fun getEnabledMarkersForSongs(songIds: List<Long>): List<LinkedMarkerRow> = unsupported()

    override fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarkerEntity>> = unsupported()

    override suspend fun renameMarker(
        id: Long,
        label: String,
    ): Unit = unsupported()

    override suspend fun deleteMarker(markerId: Long): Unit = unsupported()

    override fun observePendingBySource(source: String): Flow<List<LinkedMarkerRow>> = unsupported()

    override fun observeSongsWithEnabledMarkers(): Flow<List<Long>> = unsupported()

    private fun unsupported(): Nothing = error("in diesem Test nicht benutzt")
}
