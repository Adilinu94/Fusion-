package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Strukturpruefung des Importformats (Bauplan 6.1). */
class MarkerDocumentParserTest {
    private val validDocument =
        """
        {
          "schemaVersion": 1,
          "generatedBy": "dropsync-drop-analyzer",
          "tracks": [
            {
              "relativePath": "Music/Training",
              "displayName": "example.mp3",
              "sizeBytes": 8421137,
              "durationMs": 215000,
              "sha256": "abc123",
              "markers": [
                { "label": "Drop 1", "positionMs": 134500 }
              ]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `gueltiges dokument liefert tracks und marker`() {
        val result = MarkerDocumentParser.parse(validDocument)
        val success = result as ParsedMarkerDocument.Success
        assertEquals(1, success.schemaVersion)
        val track = success.tracks.single()
        assertEquals("Music/Training", track.relativePath)
        assertEquals("example.mp3", track.displayName)
        assertEquals(8_421_137L, track.sizeBytes)
        assertEquals(215_000L, track.durationMs)
        assertEquals("abc123", track.sha256)
        assertEquals("Drop 1", track.markers.single().label)
        assertEquals(134_500L, track.markers.single().positionMs)
    }

    @Test
    fun `sha256 ist optional`() {
        val json =
            """
            {"schemaVersion":1,"tracks":[{"relativePath":"Music/","displayName":"a.mp3",
            "sizeBytes":1,"durationMs":1000,"markers":[]}]}
            """.trimIndent()
        val success = MarkerDocumentParser.parse(json) as ParsedMarkerDocument.Success
        assertNull(success.tracks.single().sha256)
    }

    @Test
    fun `kein json wird als malformed abgelehnt`() {
        assertTrue(MarkerDocumentParser.parse("kein json {") is ParsedMarkerDocument.Malformed)
        assertTrue(MarkerDocumentParser.parse("[]") is ParsedMarkerDocument.Malformed)
    }

    @Test
    fun `fehlende pflichtfelder werden als malformed abgelehnt`() {
        val missingVersion = """{"tracks":[]}"""
        assertTrue(MarkerDocumentParser.parse(missingVersion) is ParsedMarkerDocument.Malformed)

        val missingDuration =
            """
            {"schemaVersion":1,"tracks":[{"relativePath":"Music/","displayName":"a.mp3",
            "sizeBytes":1,"markers":[]}]}
            """.trimIndent()
        assertTrue(MarkerDocumentParser.parse(missingDuration) is ParsedMarkerDocument.Malformed)
    }

    @Test
    fun `falsch typisierte marker werden als malformed abgelehnt`() {
        val stringPosition =
            """
            {"schemaVersion":1,"tracks":[{"relativePath":"Music/","displayName":"a.mp3",
            "sizeBytes":1,"durationMs":1000,"markers":[{"label":"x","positionMs":"bald"}]}]}
            """.trimIndent()
        assertTrue(MarkerDocumentParser.parse(stringPosition) is ParsedMarkerDocument.Malformed)
    }

    @Test
    fun `strukturfehler speichern nichts - parser liefert keine teilliste`() {
        // 6.1: transaktional; der zweite Track ist kaputt, also gibt es
        // kein Success mit nur dem ersten Track.
        val secondBroken =
            """
            {"schemaVersion":1,"tracks":[
              {"relativePath":"Music/","displayName":"a.mp3","sizeBytes":1,"durationMs":1000,"markers":[]},
              {"displayName":"b.mp3"}
            ]}
            """.trimIndent()
        assertTrue(MarkerDocumentParser.parse(secondBroken) is ParsedMarkerDocument.Malformed)
    }
}
