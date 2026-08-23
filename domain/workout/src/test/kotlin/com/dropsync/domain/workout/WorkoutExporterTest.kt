package com.dropsync.domain.workout

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutExporterTest {
    private val exercises =
        mapOf(
            1L to ("bench-press" to "Bankdrücken"),
            2L to ("squat, low" to "Kniebeuge \"tief\""),
        )

    private fun set(
        id: Long,
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ) = FlatSet(
        id = id,
        exerciseId = exerciseId,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs = 1_700_000_000_000 + id,
    )

    @Test
    fun `JSON enthaelt alle Saetze mit Slug und Gewicht in kg`() {
        val document =
            WorkoutExporter.buildDocument(
                sets =
                    listOf(
                        set(1, 1, 80_000_000, 10),
                        set(2, 2, 100_500_000, 8),
                    ),
                exercises = exercises,
                exportedAtEpochMs = 1_760_000_000_000,
            )
        val json = WorkoutExporter.toJson(document)

        assertTrue(json.contains("\"exerciseSlug\": \"bench-press\""))
        assertTrue(json.contains("\"weightKg\": 80.0"))
        assertTrue(json.contains("\"weightKg\": 100.5"))
        assertTrue(json.contains("\"schemaVersion\": ${WorkoutExporter.SCHEMA_VERSION}"))

        // Roundtrip: das Dokument ist wieder einlesbar.
        val parsed = Json.decodeFromString<WorkoutExport>(json)
        assertEquals(2, parsed.sets.size)
        assertEquals("squat, low", parsed.sets[1].exerciseSlug)
    }

    @Test
    fun `unbekannte Uebung faellt auf Platzhalter zurueck`() {
        val document =
            WorkoutExporter.buildDocument(
                sets = listOf(set(3, 99, 50_000_000, 5)),
                exercises = exercises,
                exportedAtEpochMs = 1_760_000_000_000,
            )
        assertEquals("unknown-99", document.sets[0].exerciseSlug)
        assertEquals("Unbekannte Übung", document.sets[0].exerciseName)
    }

    @Test
    fun `CSV hat Header und maskiert Komma und Anfuehrungszeichen`() {
        val document =
            WorkoutExporter.buildDocument(
                sets =
                    listOf(
                        set(1, 1, 80_000_000, 10),
                        set(2, 2, 100_500_000, 8),
                    ),
                exercises = exercises,
                exportedAtEpochMs = 1_760_000_000_000,
            )
        val csv = WorkoutExporter.toCsv(document)
        val lines = csv.trimEnd('\r', '\n').split("\r\n")

        assertEquals("exercise_slug,exercise_name,weight_kg,reps,logged_at_epoch_ms", lines[0])
        assertEquals("bench-press,Bankdrücken,80,10,1700000000001", lines[1])
        // "squat, low" muss wegen des Kommas in Anfuehrungszeichen, das
        // Anfuehrungszeichen in Kniebeuge "tief" verdoppelt sein (RFC 4180).
        assertEquals("\"squat, low\",\"Kniebeuge \"\"tief\"\"\",100.5,8,1700000000002", lines[2])
    }

    @Test
    fun `CSV-Gewicht ohne Gleitkomma-Artefakte`() {
        val document =
            WorkoutExporter.buildDocument(
                sets = listOf(set(4, 1, 75_000, 12)),
                exercises = exercises,
                exportedAtEpochMs = 1_760_000_000_000,
            )
        val csv = WorkoutExporter.toCsv(document)
        assertTrue("CSV soll 0.075 enthalten: $csv", csv.contains(",0.075,"))
    }

    @Test
    fun `leeres Log erzeugt nur Header beziehungsweise leere Liste`() {
        val empty = WorkoutExporter.buildDocument(emptyList(), exercises, 0)
        assertEquals(0, empty.sets.size)
        assertEquals("exercise_slug,exercise_name,weight_kg,reps,logged_at_epoch_ms\r\n", WorkoutExporter.toCsv(empty))
    }
}
