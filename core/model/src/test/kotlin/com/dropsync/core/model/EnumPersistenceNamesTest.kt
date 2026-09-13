package com.dropsync.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C4: Die Datenbank speichert Enum-NAMEN als Strings (Bauplan Abschnitt 6,
 * keine Ordinals). Ein Umbenennen einer Konstante bricht damit bestehende
 * Daten ohne Migration. Dieser Test pinnt die Namen als Vertrag: schlaegt er
 * fehl, ist das eine bewusste Migrationsentscheidung, kein Versehen.
 */
class EnumPersistenceNamesTest {
    @Test
    fun `SessionStatus Namen sind stabil`() {
        assertEquals(listOf("ACTIVE", "COMPLETED", "DISCARDED"), SessionStatus.values().map { it.name })
    }

    @Test
    fun `ExerciseKind Namen sind stabil`() {
        assertEquals(listOf("STRENGTH", "TIME", "DISTANCE"), ExerciseKind.values().map { it.name })
    }

    @Test
    fun `RestMode Namen sind stabil`() {
        assertEquals(listOf("NORMAL", "DROPSYNC"), RestMode.values().map { it.name })
    }

    @Test
    fun `SetRole Namen sind stabil`() {
        assertEquals(
            listOf("WARMUP", "WORKING", "FAILURE", "TIME", "DISTANCE"),
            SetRole.values().map { it.name },
        )
    }

    @Test
    fun `MarkerSource Namen sind stabil`() {
        assertEquals(
            listOf("IMPORT", "MANUAL", "AUTO_DETECTED"),
            MarkerSource.values().map { it.name },
        )
    }

    @Test
    fun `LinkMethod Namen sind stabil`() {
        assertEquals(
            listOf("HASH", "METADATA", "MANUAL", "AUTO_DETECTED"),
            LinkMethod.values().map { it.name },
        )
    }

    @Test
    fun `PlaylistLabel Namen sind stabil`() {
        assertEquals(listOf("REST", "WORK"), PlaylistLabel.values().map { it.name })
    }

    @Test
    fun `RestMusicBehavior Namen sind stabil`() {
        assertEquals(
            listOf("NORMAL", "REST_PLAYLIST", "DROP_LANDING"),
            RestMusicBehavior.values().map { it.name },
        )
    }

    @Test
    fun `ThemeMode und AccentColor Namen sind stabil`() {
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.values().map { it.name })
        assertEquals(listOf("LIME", "BLUE"), AccentColor.values().map { it.name })
    }

    @Test
    fun `PR-Typen und Einheiten Namen sind stabil`() {
        assertEquals(
            listOf("HIGHEST_LOAD", "HIGHEST_SESSION_VOLUME", "MOST_REPS_AT_LOAD"),
            PrType.values().map { it.name },
        )
        assertEquals(listOf("MILLI_KG", "REPS"), PrValueUnit.values().map { it.name })
    }
}
