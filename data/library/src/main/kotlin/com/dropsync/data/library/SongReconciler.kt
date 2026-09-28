package com.dropsync.data.library

import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.Song

/**
 * Ordnet verschobene oder umbenannte Dateien wieder zu (2026-09-27,
 * Befund 6.7).
 *
 * **Das Problem, das sie loest:** der Android-MediaStore vergibt die
 * Spalte `_id` **pro Datei**, nicht pro Inhalt. Wer `song.mp3` von
 * `Music/` nach `Music/Archive/` verschiebt, bekommt eine neue ID. Die
 * alte gilt als verschwunden, die neue ist ein unbekannter Titel. Alles,
 * was an der alten ID haengt, haengt ins Leere:
 *
 * | Tabelle | Folge |
 * |---|---|
 * | `song_markers` | die Drop-Position ist weg — die DropSync-Funktion geht fuer den Titel verloren |
 * | `flat_sets.song_id` | der Satz-Log zeigt einen Titel, den es nicht mehr gibt |
 * | `favorites` | das Lied ist aus den Favoriten raus |
 * | `playlist_items` | aus allen Nutzer-Playlists raus |
 * | `play_stats` | die Abspielhistorie beginnt bei null |
 * | `known_sha256` | der Titel wird **erneut** analysiert, das dauert bei vier Minuten rund 1,5 s |
 *
 * Vorher geschah das **stumm**: der Nutzer tippt "Aktualisieren", und
 * danach fehlen seine Marker — ohne jede Meldung.
 *
 * **Die Heuristik, und warum sie so streng ist:** erkannt wird nur bei
 * Uebereinstimmung von **Dateiname, Dauer und Groesse**, und nur wenn
 * der alte Eintrag im aktuellen Scan **nicht** wieder auftaucht. Diese
 * Dreier-Kombination ist praktisch eindeutig:
 *
 * - **Groesse** grenzt die haeufigste Fehlzuordnung aus. Ein
 *   `track01.mp3` gibt es in vielen Alben; die Byte-Zahl dagegen praktisch
 *   nie zweimal.
 * - **Dauer** schliesst den Rest aus: dieselbe Groesse bei
 *   unterschiedlicher Dauer ist ein anderer Titel.
 * - **Name** macht die Erkennung fuer den Nutzer nachvollziehbar — er
 *   sieht "das ist derselbe Titel, nur woanders".
 * - **"nicht zurueckgekommen"** verhindert die gefaehrliche Richtung:
 *   eine Datei, die unter ihrer alten ID noch da ist, wird **nie** als
 *   verschoben behandelt. Sonst wuerde ein Duplikat (dieselbe Datei an
 *   zwei Orten) die alte ID kapern.
 *
 * **Was sie nicht loest:** echte Umbenennungen mit geaenderter Dauer
 * (bearbeitet, neu geschnitten) und Samplings mit veraenderter Bitrate.
 * Beides aendert die Datei, und dann gibt es keine verlaessliche
 * Identitaet. Fuer those Faelle ist ein Fingerabdruck-Vergleich noetig
 * (`known_sha256` ist bereits in der Datenbank), aber das kostet einen
 * kompletten Lesevorgang pro Kandidat — das ist eine eigene
 * Entscheidung, keine Beigabe.
 *
 * Bewusst eine **reine Funktion ohne Seiteneffekte**: sie entscheidet
 * nur, das Schreiben bleibt beim Repository. Damit ist sie ohne
 * Datenbank testbar, was hier mehr bedeutet als sonst — die Heuristik
 * ist genau die Sorte Code, die man lieber mit Beispielen prueft als
 * mit einem Rescan.
 */
internal object SongReconciler {
    /**
     * Ergebnis der Zuordnung.
     *
     * @param renamedIds neue MediaStore-ID -> alte MediaStore-ID. Nur
     *   Eintraege fuer Titel, die der Scan als **neu** sieht.
     * @param movedCount Anzahl erkannter Verschiebungen (fuer die
     *   Diagnose-Ausgabe nach dem Scan).
     */
    data class Result(
        val renamedIds: Map<Long, Long>,
        val movedCount: Int,
    ) {
        /** Der Scan sah diese Titel als bereits bekannt. */
        fun isReconciled(newId: Long): Boolean = newId in renamedIds
    }

    /**
     * Ordnet [scanned] gegen [existing] ab.
     *
     * @param existing alle Eintraege der Datenbank, **ungefiltert**
     *   (auch die mit `is_available = 0` — genau die sind die Kandidaten
     *   fuer eine erkannte Rueckkehr).
     * @param scanned das Ergebnis des MediaStore-Scans.
     */
    fun reconcile(
        existing: Collection<SongEntity>,
        scanned: List<Song>,
    ): Result {
        val presentIds = scanned.map { it.mediaStoreId }.toSet()
        val existingIds = existing.map { it.mediaStoreId }.toSet()
        // Kandidaten: Eintraege, die der Scan **nicht** mehr sieht. Nur
        // die koennen verschoben sein; alles andere ist per Definition
        // noch da.
        val gone = existing.filter { it.mediaStoreId !in presentIds }
        if (gone.isEmpty() || scanned.isEmpty()) return Result(emptyMap(), 0)

        // Index der verschwundenen Eintraege ueber ihren Fingerabdruck.
        val goneByFingerprint = HashMap<Fingerprint, MutableList<SongEntity>>()
        for (entity in gone) {
            val key =
                Fingerprint(
                    name = entity.displayName,
                    durationMs = entity.durationMs,
                    sizeBytes = entity.sizeBytes,
                )
            goneByFingerprint.getOrPut(key) { mutableListOf() } += entity
        }

        val renamed = HashMap<Long, Long>()
        val usedOldIds = HashSet<Long>()
        // **Nur** die Titel, die der Scan als **neu** sieht. Ein Titel,
        // dessen alte ID noch im Bestand ist, wurde nicht verschoben —
        // er ist unter derselben ID zurueckgekommen.
        //
        // (Diesen Filter gab es nicht in der ersten Fassung: die Schleife
        // lief ueber alle Scan-Eintraege und hat dadurch der ID 100 —
        // die es weiterhin gibt — die alte ID 101 zugewiesen. Der
        // Test `datei unter ihrer alten id gilt nie als verschoben` hat
        // das aufgedeckt.)
        val newSongs = scanned.filter { it.mediaStoreId !in existingIds }
        // In aufsteigender neuer ID: stabil und reproduzierbar, unabhaengig
        // von der Reihenfolge, in der der MediaStore liefert.
        for (song in newSongs.sortedBy { it.mediaStoreId }) {
            val key =
                Fingerprint(
                    name = song.displayName,
                    durationMs = song.durationMs,
                    sizeBytes = song.sizeBytes,
                )
            // Kleinste freie Alt-ID gewinnt. Das verhindert, dass zwei
            // verschobene Dateien einander die Zuordnung wegnehmen, und
            // ist stabil ueber mehrere Scans hinweg.
            val candidate =
                goneByFingerprint[key]
                    ?.filter {
                        it.mediaStoreId !in usedOldIds
                    }?.minByOrNull { it.mediaStoreId }
            if (candidate != null) {
                renamed[song.mediaStoreId] = candidate.mediaStoreId
                usedOldIds += candidate.mediaStoreId
            }
        }
        return Result(renamedIds = renamed, movedCount = renamed.size)
    }

    /**
     * Der dreiteilige Schluessel. [name] ist der **Anzeigename** inklusive
     * Endung: `a.mp3` und `a.flac` sind verschiedene Dateien, auch bei
     * gleicher Dauer und Groesse (gerade bei verlustfreier Konvertierung,
     * wo Groesse und Dauer fast identisch sind).
     */
    private data class Fingerprint(
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
    )
}
