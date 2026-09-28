package com.dropsync.data.library

import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconciliation verschobener Dateien (2026-09-27, Befund 6.7).
 *
 * **Warum der eigene Test und nicht nur ein Scan-Test:** die Heuristik
 * ist die Sorte Code, die man lieber an Beispielen prueft als an einem
 * Rescan. Zusaetzlich ist sie eine **reine Funktion** — kein Scan, keine
 * Datenbank, kein Dispatcher. Das ist Absicht: eine Funktion, die
 * Dateiidentitaeten zuordnet, gehoert in eine Form, in der man Beispiele
 * aufschreiben kann, statt sie ueber den MediaStore zu beobachten.
 */
class SongReconcilerTest {
    @Test
    fun `verschobene datei wird wiedererkannt`() {
        // Die Datei liegt jetzt in Music/Archive/ und hat eine neue ID,
        // bekommt aber sonst exakt dieselben Eigenschaften.
        val existing = listOf(entity(id = 100, name = "song.mp3", durationMs = 240_000, size = 5_000_000))
        val scanned = listOf(song(id = 200, name = "song.mp3", durationMs = 240_000, size = 5_000_000))

        val result = SongReconciler.reconcile(existing, scanned)

        assertEquals(
            "Die verschobene Datei muss wiedererkannt werden",
            mapOf(200L to 100L),
            result.renamedIds,
        )
        assertEquals(1, result.movedCount)
    }

    /**
     * Der eigentliche Nutzen: die alte ID **verschwindet** nicht
     * grundlos, und ohne die Zuordnung waeren die Marker verloren.
     */
    @Test
    fun `mehrere verschobene dateien werden alle erkannt`() {
        val existing =
            listOf(
                entity(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
                entity(id = 101, name = "b.mp3", durationMs = 200_000, size = 2_000_000),
                entity(id = 102, name = "c.mp3", durationMs = 300_000, size = 3_000_000),
            )
        val scanned =
            listOf(
                // a ist geblieben, b und c sind umgezogen.
                song(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
                song(id = 201, name = "b.mp3", durationMs = 200_000, size = 2_000_000),
                song(id = 202, name = "c.mp3", durationMs = 300_000, size = 3_000_000),
            )

        val result = SongReconciler.reconcile(existing, scanned)

        assertEquals(
            mapOf(201L to 101L, 202L to 102L),
            result.renamedIds,
        )
        assertFalse("Der gebliebene Titel darf nicht als verschoben gelten", result.isReconciled(100L))
    }

    /**
     * **Die wichtigste Gegenprobe.** Gleicher Dateiname gibt es in vielen
     * Alben (`track01.mp3`). Ohne die Groesse im Fingerabdruck wuerde
     * ein `track01.mp3` aus einem anderen Album den Marker des
     * falschen Titels bekommen — und damit die Drop-Position auf ein
     * voellig anderes Lied legen.
     */
    @Test
    fun `gleicher name aber andere groesse ist keine verschiebung`() {
        val existing = listOf(entity(id = 100, name = "track01.mp3", durationMs = 100_000, size = 1_000_000))
        val scanned = listOf(song(id = 200, name = "track01.mp3", durationMs = 100_000, size = 9_000_000))

        val result = SongReconciler.reconcile(existing, scanned)

        assertTrue(
            "Eine andere Datei mit gleichem Namen darf nicht zugeordnet werden: ${result.renamedIds}",
            result.renamedIds.isEmpty(),
        )
    }

    /** Zweite Gegenprobe zur Groesse: die Dauer unterscheidet verschiedene Titel. */
    @Test
    fun `gleiche groesse aber andere dauer ist keine verschiebung`() {
        val existing = listOf(entity(id = 100, name = "x.mp3", durationMs = 100_000, size = 5_000_000))
        val scanned = listOf(song(id = 200, name = "x.mp3", durationMs = 250_000, size = 5_000_000))

        assertTrue(
            "Verschiedene Dauer bei gleicher Groesse darf nicht zugeordnet werden",
            SongReconciler.reconcile(existing, scanned).renamedIds.isEmpty(),
        )
    }

    /**
     * **Die gefaehrliche Richtung.** Eine Datei, die unter ihrer alten ID
     * noch da ist, wird **nie** als verschoben behandelt. Sonst wuerde
     * ein Duplikat (dieselbe Datei an zwei Orten) der neuen ID die alte
     * ID wegnehmen — und damit einen Marker faelschlich umhaengen.
     */
    @Test
    fun `datei unter ihrer alten id gilt nie als verschoben`() {
        val existing =
            listOf(
                entity(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
                // Exakt dieselben Eigenschaften wie die 100.
                entity(id = 101, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
            )
        val scanned =
            listOf(
                song(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
                song(id = 200, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
            )

        val result = SongReconciler.reconcile(existing, scanned)

        assertEquals(
            "Die neue ID darf nur die verschwundene 101 uebernehmen",
            mapOf(200L to 101L),
            result.renamedIds,
        )
    }

    /** Zwei verschwundene Kandidaten mit gleichem Fingerabdruck: der kleinste gewinnt. */
    @Test
    fun `zwei identische kandidaten werden stabil zugeordnet`() {
        val existing =
            listOf(
                entity(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
                entity(id = 101, name = "a.mp3", durationMs = 100_000, size = 1_000_000),
            )
        val scanned = listOf(song(id = 200, name = "a.mp3", durationMs = 100_000, size = 1_000_000))

        // Kleinste freie Alt-ID gewinnt — deterministisch, nicht
        // abhaengig von der Reihenfolge im Scan.
        assertEquals(
            mapOf(200L to 100L),
            SongReconciler.reconcile(existing, scanned).renamedIds,
        )
        // Und in der umgekehrten Reihenfolge dasselbe Ergebnis.
        assertEquals(
            mapOf(200L to 100L),
            SongReconciler.reconcile(existing.reversed(), scanned).renamedIds,
        )
    }

    /** Eine echte Umbenennung (anderer Name) ist **keine** Verschiebung. */
    @Test
    fun `umbenannte datei wird nicht zugeordnet`() {
        val existing = listOf(entity(id = 100, name = "alt.mp3", durationMs = 100_000, size = 1_000_000))
        val scanned = listOf(song(id = 200, name = "neu.mp3", durationMs = 100_000, size = 1_000_000))

        assertTrue(
            "Eine Umbenennung aendert die Identitaet der Datei",
            SongReconciler.reconcile(existing, scanned).renamedIds.isEmpty(),
        )
    }

    /** Leerer Scan (SD-Karte gezogen) darf nichts zuordnen. */
    @Test
    fun `leerer scan ordnet nichts zu`() {
        val existing = listOf(entity(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000))
        assertTrue(SongReconciler.reconcile(existing, emptyList()).renamedIds.isEmpty())
    }

    /** Nichts Neues, nichts Verschwundenes: aufwendig, aber noetig. */
    @Test
    fun `unveraenderte bibliothek ordnet nichts zu`() {
        val entities = listOf(entity(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000))
        val scanned = listOf(song(id = 100, name = "a.mp3", durationMs = 100_000, size = 1_000_000))

        assertEquals(0, SongReconciler.reconcile(entities, scanned).movedCount)
    }

    private fun entity(
        id: Long,
        name: String,
        durationMs: Long,
        size: Long,
    ) = SongEntity(
        mediaStoreId = id,
        contentUri = "content://$id",
        displayName = name,
        relativePath = "Music/",
        durationMs = durationMs,
        sizeBytes = size,
        dateModifiedSeconds = 1_700_000_000L,
        title = "Titel $id",
        artist = "Interpret",
        album = "Album",
        genre = "Pop",
        isAvailable = true,
    )

    private fun song(
        id: Long,
        name: String,
        durationMs: Long,
        size: Long,
    ) = Song(
        mediaStoreId = id,
        contentUri = "content://$id",
        displayName = name,
        relativePath = "Music/Archive/",
        durationMs = durationMs,
        sizeBytes = size,
        dateModifiedSeconds = 1_700_000_000L,
        title = "Titel $id",
        artist = "Interpret",
        album = "Album",
        genre = "Pop",
        isAvailable = true,
    )
}
