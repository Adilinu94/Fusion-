package com.dropsync.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.database.dao.LinkedMarkerRow
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D5/A3: Das Ersetzen der unbestaetigten Onset-Kandidaten laeuft als EINE
 * Transaktion (DAO-`@Transaction`) — entweder alle neuen Kandidaten oder
 * keiner. Vorher liefen DELETE und Inserts ohne Transaktion: brach ein
 * Insert ab, blieb eine halb ersetzte Kandidatenliste stehen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkerCandidateTransactionTest {
    private lateinit var db: DropSyncDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ersetzen tauscht nur unbestaetigte kandidaten und laesst bestaetigte stehen`() =
        runTest {
            val dao = db.markerDao()
            val songId = 42L
            insertSong(songId)
            val confirmedId = dao.insert(marker(label = "Drop bestaetigt", isEnabled = true, at = 111))
            dao.insertLink(link(confirmedId, songId))

            dao.replacePendingCandidates(
                songId = songId,
                source = SOURCE,
                markers = listOf(marker("alt 1", at = 100), marker("alt 2", at = 100)),
                linkMethod = LINK_METHOD,
                linkedAtEpochMs = 100,
            )
            assertEquals(2, pendingOfSong(songId).size)

            // Zweiter Lauf: eine Uhrzeit fuer alle, alte Kandidaten weg.
            dao.replacePendingCandidates(
                songId = songId,
                source = SOURCE,
                markers =
                    listOf(
                        marker("Drop 1", at = 999),
                        marker("Drop 2", at = 999),
                        marker("Drop 3", at = 999),
                    ),
                linkMethod = LINK_METHOD,
                linkedAtEpochMs = 999,
            )

            val pending = pendingOfSong(songId)
            assertEquals(listOf("Drop 1", "Drop 2", "Drop 3"), pending.map { it.marker.label })
            assertEquals(listOf(999L, 999L, 999L), pending.map { it.marker.createdAtEpochMs })
            // Der bestaetigte Marker samt Link hat den Lauf ueberlebt.
            assertEquals(1, dao.getEnabledMarkersForSong(songId).size)
            assertEquals("Drop bestaetigt", dao.getEnabledMarkersForSong(songId).single().label)
        }

    @Test
    fun `ersetzen rollt bei einem insert-konflikt komplett zurueck`() =
        runTest {
            val dao = db.markerDao()
            val songId = 7L
            insertSong(songId)
            // Ein bestaetigter Marker ueberlebt den DELETE — seine ID ist
            // damit ein garantierter PK-Konflikt fuer den zweiten Insert.
            val confirmedId = dao.insert(marker("bestaetigt", isEnabled = true, at = 50))
            dao.insertLink(link(confirmedId, songId))
            dao.replacePendingCandidates(
                songId = songId,
                source = SOURCE,
                markers = listOf(marker("alt 1", at = 100), marker("alt 2", at = 100)),
                linkMethod = LINK_METHOD,
                linkedAtEpochMs = 100,
            )
            val before = pendingOfSong(songId).map { it.marker.label }

            val conflicting = marker("konflikt", at = 200).copy(id = confirmedId)
            var failed = false
            try {
                dao.replacePendingCandidates(
                    songId = songId,
                    source = SOURCE,
                    markers = listOf(marker("neu 1", at = 200), conflicting),
                    linkMethod = LINK_METHOD,
                    linkedAtEpochMs = 200,
                )
            } catch (e: Exception) {
                failed = true
            }

            assertTrue("Der Insert-Konflikt muss den Lauf abbrechen", failed)
            // Rollback: der DELETE und der erste Insert sind mit zurueckgerollt.
            assertEquals(before, pendingOfSong(songId).map { it.marker.label })
        }

    private suspend fun pendingOfSong(songId: Long): List<LinkedMarkerRow> =
        db
            .markerDao()
            .observePendingBySource(SOURCE)
            .first()
            .filter { it.linkedSongId == songId }
            .sortedBy { it.marker.positionMs }

    /** Links tragen einen FK auf `songs`; ohne Songzeile schlaegt der Insert fehl. */
    private suspend fun insertSong(id: Long) {
        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    mediaStoreId = id,
                    contentUri = "content://$id",
                    displayName = "$id.mp3",
                    relativePath = "Music",
                    durationMs = 200_000,
                    sizeBytes = 1_000,
                    dateModifiedSeconds = 1,
                    title = "T$id",
                    artist = "A",
                    album = "Al",
                    genre = null,
                    isAvailable = true,
                ),
            ),
        )
    }

    private fun marker(
        label: String,
        isEnabled: Boolean = false,
        at: Long,
    ): SongMarkerEntity =
        SongMarkerEntity(
            sourceFingerprint = "fp",
            label = label,
            positionMs = at,
            source = SOURCE,
            isEnabled = isEnabled,
            createdAtEpochMs = at,
        )

    private fun link(
        markerId: Long,
        songId: Long,
    ): MarkerSongLinkEntity =
        MarkerSongLinkEntity(
            markerId = markerId,
            songId = songId,
            linkMethod = LINK_METHOD,
            linkedAtEpochMs = 1,
        )

    private companion object {
        const val SOURCE = "AUTO_DETECTED"
        const val LINK_METHOD = "AUTO_DETECTED"
    }
}
