package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Flacher Satz (FlowRep-Design Phase 2): direkt einer Uebung zugeordnet,
 * ohne Session/Cluster/Segment-Kette. Gewicht in Millikilogramm (Long).
 */
@Entity(
    tableName = "flat_sets",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["exercise_id"]),
        Index(value = ["logged_at_epoch_ms"]),
    ],
)
data class FlatSetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,
    @ColumnInfo(name = "weight_milli_kg")
    val weightMilliKg: Long,
    @ColumnInfo(name = "reps")
    val reps: Int,
    @ColumnInfo(name = "logged_at_epoch_ms")
    val loggedAtEpochMs: Long,
    /**
     * MediaStore-ID des Titels, der waehrend dieses Satzes lief (2026-09-27,
     * Befund 11.3/13.4). `null`, wenn nichts lief oder der Titel
     * inzwischen aus der Bibliothek verschwunden ist.
     *
     * **Warum das die groesste offene Luecke der Verzahnung schliesst:**
     * `PlaybackSnapshotEntity` existierte seit dem Kopplungs-Ausbau und war
     * **toter Code**. `completeCluster()` hat keinen Produktivaufrufer (nur
     * Tests und ein Fake), der reale Pfad ist `FlatSetRepository.logSet()`
     * — und `feature/progress` hatte null Playback-Referenzen. Die Tabelle
     * blieb leer, und `markerId` wurde im toten Pfad sogar hart auf `null`
     * gesetzt, obwohl genau der Marker die wertvollste Spalte gewesen
     * waere.
     *
     * Drei nullable Spalten in derselben Transaktion wie der Satz kosten
     * nichts und ermoeglichen endlich die Frage, die sich jeder
     * Trainierende stellt: "welche Musik lief bei meinem letzten PR?"
     *
     * **Bewusst kein Fremdschluessel auf `songs`:** der Titel kann
     * verschwinden, und die Historie soll das ueberleben. Die
     * Verknuepfung ist eine Nummer, kein Zwang.
     */
    @ColumnInfo(name = "song_id")
    val songId: Long? = null,
    /**
     * Position im Titel in Millisekunden zum Zeitpunkt des Loggings.
     * Zusammen mit [songId] reicht das, um den Satz im Nachhinein
     * zeitlich zu verorten ("15 Sekunden nach dem Drop").
     */
    @ColumnInfo(name = "playback_position_ms")
    val playbackPositionMs: Long? = null,
    /**
     * ID des Markers, der bei diesem Satz als Drop-Ziel aktiv war.
     *
     * Das ist die **eigentlich** wertvolle Spalte: sie macht sichtbar,
     * ob der Satz in einem musikalischen Drop landete oder nicht — die
     * Frage, die die ganze App aufgeworfen hat. Sie referenziert
     * `song_markers`, ebenfalls **ohne** Fremdschluessel: Marker werden
     * bei einem Rescan neu geschrieben, und ein Satz soll nicht daran
     * haengen, ob der Marker noch existiert.
     */
    @ColumnInfo(name = "marker_id")
    val markerId: Long? = null,
)
