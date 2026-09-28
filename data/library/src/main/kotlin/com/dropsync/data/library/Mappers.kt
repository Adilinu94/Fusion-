package com.dropsync.data.library

import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker

// Abbildung zwischen Room-Entities und Domainmodellen (Bauplan 3.2/3):
// Features sehen nur Domainmodelle, nie Datenbankzeilen.

internal fun SongEntity.toDomain(): Song =
    Song(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateModifiedSeconds = dateModifiedSeconds,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = isAvailable,
    )

internal fun Song.toEntity(knownSha256: String?): SongEntity =
    SongEntity(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateModifiedSeconds = dateModifiedSeconds,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = isAvailable,
        knownSha256 = knownSha256,
        // 2026-09-27 (Befund 6.9): die gesuchte Form mitschreiben. FTS4
        // und LIKE vergleichen Bytes, also findet "beyonce" "Beyoncé"
        // nicht. Die Spalte traegt die normalisierte Form, damit die
        // Abfrage sie direkt treffen kann.
        //
        // `displayName` ist bewusst **nicht** Teil davon: der Dateiname
        // enthaelt die Endung, und "Beyoncé.mp3" gegen "beyonce" zu
        // vergleichen ist der gleiche Aufwand wie gegen den Titel.
        titleFolded = foldForSearch(title),
        artistFolded = foldForSearch(artist),
        albumFolded = foldForSearch(album),
    )

/**
 * Normalisiert einen Text fuer die Suche: Diakritika entfernt,
 * kleingeschrieben (2026-09-27, Befund 6.9).
 *
 * **Der Weg ist derselbe wie bei Lucene und SQLite-FTS5**
 * (`remove_diacritics`): NFD zerlegt „é" in „e" + Combining-Accent,
 * die Marken werden entfernt. Danach `lowercase()` — die Spalte soll
 * einen Vergleich ohne `COLLATE` ermoeglichen.
 *
 * **Warum [String.lowercase] statt [String.lowercase]: `toLowerCase`
 * vor:** `lowercase()` nutzt Locale-unabhaengige Regeln. Die
 * türkische Variante von `toLowerCase` macht aus „I" ein punktloses „ı",
 * und auf einem türkischen Geraet wuerde die Spalte dann nicht zu der
 * Abfrage passen. Das ist kein theoretisches Problem — die Funktion
 * wird auf **jedem** Geraet aufgerufen, das den Code benutzt.
 *
 * `null` bleibt `null`: ein fehlender Interpreten ist kein leerer
 * String, und die Abfrage soll das unterscheiden koennen.
 */
internal fun foldForSearch(text: String?): String? {
    if (text == null) return null
    val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
    return COMBINING_MARKS.replace(decomposed, "").lowercase()
}

/** Combining-Diakritika (Unicode-Kategorie Mn). */
private val COMBINING_MARKS = Regex("\\p{Mn}+")

internal fun SongMarkerEntity.toDomain(linkedSongId: Long?): SongMarker =
    SongMarker(
        id = id,
        label = label,
        positionMs = positionMs,
        source = MarkerSource.valueOf(source),
        isEnabled = isEnabled,
        linkedSongId = linkedSongId,
    )
