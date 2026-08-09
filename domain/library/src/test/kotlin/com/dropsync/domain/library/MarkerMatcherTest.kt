package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerMatcherTest {
    private val matcher = MarkerMatcher()

    private fun song(
        id: Long,
        path: String = "Music/Training",
        name: String = "track.mp3",
        size: Long = 1000,
        duration: Long = 200_000,
        hash: String? = null,
    ) = SongFingerprint(id, path, name, size, duration, hash)

    private fun track(
        path: String = "Music/Training",
        name: String = "track.mp3",
        size: Long = 1000,
        duration: Long = 200_000,
        hash: String? = null,
    ) = ImportedTrack(path, name, size, duration, hash, listOf(ImportedMarker("Drop 1", 100_000)))

    @Test
    fun `stufe 1 gespeicherter hash gewinnt vor metadaten`() {
        val hash = "a".repeat(64)
        val candidates =
            listOf(
                song(id = 1, hash = hash, name = "anders.mp3", size = 5),
                song(id = 2), // wuerde per Metadaten passen
            )
        val result = matcher.match(track(hash = hash), candidates)
        assertEquals(MatchResult.Matched(1, MatchMethod.HASH), result)
    }

    @Test
    fun `stufe 1 wird ohne gespeicherten hash uebersprungen`() {
        val result =
            matcher.match(
                track(hash = "b".repeat(64)),
                listOf(song(id = 7, hash = null)),
            )
        // Fallthrough auf Stufe 2 (strikte Metadaten).
        assertEquals(MatchResult.Matched(7, MatchMethod.METADATA_STRICT), result)
    }

    @Test
    fun `stufe 3 nur bei genau einem treffer`() {
        val candidates =
            listOf(
                song(id = 1, path = "Music/A"),
                song(id = 2, path = "Music/B"),
            )
        // relativePath des Imports passt zu keinem -> Stufe 3, zwei Treffer.
        val result = matcher.match(track(path = "Music/C"), candidates)
        assertTrue(result is MatchResult.Ambiguous)
        assertEquals(listOf(1L, 2L), (result as MatchResult.Ambiguous).candidateIds)
    }

    @Test
    fun `gleicher name aber andere groesse wird nie zugeordnet`() {
        // Abnahmekriterium Schritt 6: zwei gleichnamige Songs mit
        // unterschiedlicher Groesse duerfen nicht falsch zugeordnet werden.
        val candidates = listOf(song(id = 1, size = 1111), song(id = 2, size = 2222))
        val result = matcher.match(track(size = 3333), candidates)
        assertEquals(MatchResult.Unmatched, result)
    }
}

class ImportValidatorTest {
    private val validator = ImportValidator()

    private fun validTrack() =
        ImportedTrack(
            relativePath = "Music/Training",
            displayName = "example.mp3",
            sizeBytes = 8_421_137,
            durationMs = 215_000,
            sha256 = null,
            markers = listOf(ImportedMarker("Drop 1", 134_500)),
        )

    @Test
    fun `gueltiges dokument besteht`() {
        assertEquals(ImportValidation.Valid, validator.validate(1, listOf(validTrack())))
    }

    @Test
    fun `unbekannte schemaversion lehnt vor fachpruefung ab`() {
        val result = validator.validate(2, listOf(validTrack()))
        assertTrue(result is ImportValidation.Invalid)
        assertEquals(1, (result as ImportValidation.Invalid).violations.size)
    }

    @Test
    fun `marker ausserhalb der dauer wird abgelehnt`() {
        val bad = validTrack().copy(markers = listOf(ImportedMarker("Drop", 999_999)))
        assertTrue(validator.validate(1, listOf(bad)) is ImportValidation.Invalid)
    }

    @Test
    fun `doppelte markerposition wird abgelehnt`() {
        val bad =
            validTrack().copy(
                markers = listOf(ImportedMarker("A", 1000), ImportedMarker("B", 1000)),
            )
        assertTrue(validator.validate(1, listOf(bad)) is ImportValidation.Invalid)
    }

    @Test
    fun `ungueltiger hash wird abgelehnt`() {
        val bad = validTrack().copy(sha256 = "NICHT-HEX")
        assertTrue(validator.validate(1, listOf(bad)) is ImportValidation.Invalid)
    }

    @Test
    fun `negative position und leeres label werden abgelehnt`() {
        val bad = validTrack().copy(markers = listOf(ImportedMarker(" ", -5)))
        val result = validator.validate(1, listOf(bad))
        assertTrue(result is ImportValidation.Invalid)
        assertTrue((result as ImportValidation.Invalid).violations.size >= 2)
    }
}
