package com.dropsync.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.database.entity.TimerPresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    /** Erneuter Scan aktualisiert vorhandene Zeilen ueber mediaStoreId (5.1). */
    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE media_store_id = :mediaStoreId")
    suspend fun getById(mediaStoreId: Long): SongEntity?

    /** Einmalige Momentaufnahme fuer Scan-Abgleich und Markerzuordnung. */
    @Query("SELECT * FROM songs")
    suspend fun getAllOnce(): List<SongEntity>

    /** Speichert den extern gelieferten Analyzer-Hash (nie selbst berechnet). */
    @Query("UPDATE songs SET known_sha256 = :sha256 WHERE media_store_id = :mediaStoreId")
    suspend fun setKnownSha256(
        mediaStoreId: Long,
        sha256: String,
    )

    @Query("SELECT * FROM songs WHERE is_available = 1 ORDER BY title COLLATE NOCASE")
    fun observeAvailable(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    /**
     * Nicht mehr im MediaStore vorhandene Songs bleiben mit
     * isAvailable = false erhalten (Schritt 4.4); Historie und Marker
     * werden nie geloescht.
     *
     * **Chunking-Pflicht (2026-09-27):** die Abfrage erzeugt einen
     * gebundenen Parameter je ID. `SQLITE_MAX_VARIABLE_NUMBER` ist 999 bis
     * SQLite 3.32; API 26/27 liefern aeltere SQLite-Builds. Mit mehr als
     * 999 Songs scheiterte der Scan und damit die gesamte Bibliothek.
     * Aufrufer zerlegen die ID-Liste mit [BIND_CHUNK_SIZE] und rufen
     * diese Methode je Block auf. **Dabei je Block erneut aufrufen, nicht
     * die Gesamtliste uebergeben** — ein zweiter Aufruf markiert sonst
     * alles aus den anderen Bloecken wieder als fehlend.
     */
    @Query("UPDATE songs SET is_available = 0 WHERE media_store_id NOT IN (:presentIds)")
    suspend fun markMissingAsUnavailable(presentIds: List<Long>)

    /**
     * Setzt **alle** Songs auf unavailable. Wird gebraucht, wenn der
     * Scan keine einzige ID liefert — `NOT IN ()` ist kein gueltiges SQL
     * und wuerde als Fehler enden, obwohl "nichts vorhanden" die korrekte
     * Antwort ist.
     */
    @Query("UPDATE songs SET is_available = 0")
    suspend fun markAllUnavailable()

    @Query("UPDATE songs SET is_available = :isAvailable WHERE media_store_id = :mediaStoreId")
    suspend fun setAvailability(
        mediaStoreId: Long,
        isAvailable: Boolean,
    )

    companion object {
        /**
         * Blockgroesse fuer [markMissingAsUnavailable].
         *
         * 500 statt 999: SQLite reserviert Parameter fuer interne Zwecke
         * (Subqueries, Trigger), und die Zahl ist seit SQLite 3.32 auf
         * 32766 angehoben. 500 liegt darunter und traegt damit auf jedem
         * unterstuetzten API-Level (minSdk 26).
         */
        const val BIND_CHUNK_SIZE: Int = 500
    }
}

/**
 * Marker samt Zielsong aus der Linkzeile; Grundlage der Review-Liste
 * (Pending-Abfrage) und der Batch-Abfrage der Planung (D5/A7).
 */
data class LinkedMarkerRow(
    @Embedded val marker: SongMarkerEntity,
    @ColumnInfo(name = "linked_song_id")
    val linkedSongId: Long,
)

@Dao
interface MarkerDao {
    @Insert
    suspend fun insert(marker: SongMarkerEntity): Long

    @Query("SELECT * FROM song_markers WHERE id = :id")
    suspend fun getById(id: Long): SongMarkerEntity?

    @Query("SELECT * FROM song_markers WHERE source_fingerprint = :fingerprint")
    suspend fun getByFingerprint(fingerprint: String): List<SongMarkerEntity>

    @Query(
        "UPDATE song_markers SET label = :label, position_ms = :positionMs, is_enabled = :isEnabled WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        label: String,
        positionMs: Long,
        isEnabled: Boolean,
    )

    /** Marker ohne Linkzeile sind "nicht zugeordnet" (5.1). */
    @Query(
        "SELECT m.* FROM song_markers m LEFT JOIN marker_song_links l ON l.marker_id = m.id WHERE l.id IS NULL",
    )
    fun observeUnmatched(): Flow<List<SongMarkerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLink(link: MarkerSongLinkEntity): Long

    /** Entfernt eine bestehende Zuordnung (manuelles Umziehen in Settings). */
    @Query("DELETE FROM marker_song_links WHERE marker_id = :markerId")
    suspend fun deleteLinkForMarker(markerId: Long)

    @Query("SELECT * FROM marker_song_links WHERE marker_id = :markerId")
    suspend fun getLinkForMarker(markerId: Long): MarkerSongLinkEntity?

    /**
     * Haengt alle Marker eines Titels auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7).
     *
     * **Warum das noetig ist:** verschiebt der Nutzer eine Datei, vergibt
     * der MediaStore eine neue `_id`. Ohne dieses Umhaengen bleiben die
     * Marker auf der alten ID — und die DropSync-Funktion geht fuer den
     * Titel verloren, **stumm**. Das ist der teuerste Einzelschaden der
     * Verschiebung, weil die Landungspositionen das sind, was der
     * Nutzer ueberhaupt gesetzt hat.
     *
     * `ON CONFLICT` ist bewusst **nicht** gesetzt: `marker_song_links`
     * hat einen Primärschlüssel auf `marker_id`, ein Kollisionsfall kann
     * also nicht auftreten — der Marker existiert bereits mit genau
     * dieser Zeile.
     */
    @Query("UPDATE marker_song_links SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int

    @Query(
        "SELECT m.* FROM song_markers m INNER JOIN marker_song_links l ON l.marker_id = m.id " +
            "WHERE l.song_id = :songId AND m.is_enabled = 1 ORDER BY m.position_ms",
    )
    suspend fun getEnabledMarkersForSong(songId: Long): List<SongMarkerEntity>

    /** Benennt einen Marker um (P2-21): nur das Label aendert sich. */
    @Query("UPDATE song_markers SET label = :label WHERE id = :id")
    suspend fun renameMarker(
        id: Long,
        label: String,
    )

    /** Loescht den Marker; die Linkzeile faellt per ON DELETE CASCADE mit. */
    @Query("DELETE FROM song_markers WHERE id = :markerId")
    suspend fun deleteMarker(markerId: Long)

    /** Unbestaetigte Kandidaten einer Quelle (Phase 5: AUTO_DETECTED). */
    @Query(
        "SELECT m.*, l.song_id AS linked_song_id FROM song_markers m " +
            "INNER JOIN marker_song_links l ON l.marker_id = m.id " +
            "WHERE m.source = :source AND m.is_enabled = 0 ORDER BY l.song_id, m.position_ms",
    )
    fun observePendingBySource(source: String): Flow<List<LinkedMarkerRow>>

    /**
     * D5/A7: aktive Marker fuer mehrere Songs in EINER Abfrage — die
     * Planung lud vorher je Work-Titel eine eigene Query (N+1). Reihenfolge
     * wie [getEnabledMarkersForSong]: je Song nach Position.
     */
    @Query(
        "SELECT m.*, l.song_id AS linked_song_id FROM song_markers m " +
            "INNER JOIN marker_song_links l ON l.marker_id = m.id " +
            "WHERE l.song_id IN (:songIds) AND m.is_enabled = 1 ORDER BY l.song_id, m.position_ms",
    )
    suspend fun getEnabledMarkersForSongs(songIds: List<Long>): List<LinkedMarkerRow>

    /**
     * D5/A8: aktive Marker eines Songs als Flow — das Gate beobachtet
     * damit Marker-Aenderungen (Room invalidiert), statt sie im
     * 500-ms-Takt neu zu laden.
     */
    @Query(
        "SELECT m.* FROM song_markers m INNER JOIN marker_song_links l ON l.marker_id = m.id " +
            "WHERE l.song_id = :songId AND m.is_enabled = 1 ORDER BY m.position_ms",
    )
    fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarkerEntity>>

    /**
     * C4: Song-IDs mit mindestens einem aktiven Marker — Grundlage der
     * Drop-Abdeckung ("9/12 Drops") auf Music Home und in der Playlist.
     * Eine Query statt einer Zaehlung je Titel (kein N+1).
     */
    @Query(
        "SELECT DISTINCT l.song_id FROM marker_song_links l " +
            "INNER JOIN song_markers m ON m.id = l.marker_id WHERE m.is_enabled = 1",
    )
    fun observeSongsWithEnabledMarkers(): Flow<List<Long>>

    /**
     * Entfernt unbestaetigte Kandidaten einer Quelle fuer einen Song:
     * ein erneuter Erkennungslauf ersetzt seine alten Kandidaten.
     */
    @Query(
        "DELETE FROM song_markers WHERE source = :source AND is_enabled = 0 AND id IN " +
            "(SELECT marker_id FROM marker_song_links WHERE song_id = :songId)",
    )
    suspend fun deletePendingBySourceForSong(
        songId: Long,
        source: String,
    )

    /**
     * D5/A3: Ersetzt die unbestaetigten Kandidaten eines Songs in EINER
     * Transaktion — loeschen, dann Marker + Link je Kandidat einfuegen.
     * Vorher lief das ohne Transaktion: brach ein Insert ab, blieb eine halb
     * ersetzte Kandidatenliste stehen. Die Link-Methode und die Uhrzeit
     * kommen vom Aufrufer (eine Uhrzeit fuer den ganzen Lauf).
     */
    @Transaction
    suspend fun replacePendingCandidates(
        songId: Long,
        source: String,
        markers: List<SongMarkerEntity>,
        linkMethod: String,
        linkedAtEpochMs: Long,
    ) {
        deletePendingBySourceForSong(songId, source)
        markers.forEach { marker ->
            val markerId = insert(marker)
            insertLink(
                MarkerSongLinkEntity(
                    markerId = markerId,
                    songId = songId,
                    linkMethod = linkMethod,
                    linkedAtEpochMs = linkedAtEpochMs,
                ),
            )
        }
    }
}

@Dao
interface TimerPresetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(preset: TimerPresetEntity): Long

    @Query("SELECT * FROM timer_presets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TimerPresetEntity>>

    @Query("DELETE FROM timer_presets WHERE id = :id")
    suspend fun delete(id: Long)
}
