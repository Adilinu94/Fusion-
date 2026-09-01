package com.dropsync.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migrationstest gemaess Schritt 3.5: jede exportierte Schemaversion muss
 * bis zur aktuellen Version migrierbar sein. Fuer jede neue Version wird
 * hier die Migrationskette ergaenzt.
 * Destruktive Migration ist in Release verboten.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            DropSyncDatabase::class.java,
        )

    @Test
    fun `schema version 1 laesst sich anlegen und validieren`() {
        // Room 2.8 erwartet konsistente Pfade zwischen Anlegen und Validieren.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB).absolutePath

        // Erzeugt die Datenbank exakt nach exportiertem Schema v1 und
        // validiert sie gegen die aktuelle Entity-Definition.
        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 1, true)
    }

    @Test
    fun `migration 1 auf 2 ergaenzt exercise rest prefs`() {
        // Kette v1 -> v2 (neue Tabelle exercise_rest_prefs, Abschnitt 8);
        // validiert gegen das exportierte Schema 2.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V2).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 2, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 2 auf 3 ergaenzt track analysis`() {
        // Kette v1 -> v3 (neue Tabelle track_analysis, Marker/Waveform-Plan
        // Phase 2); validiert gegen das exportierte Schema 3.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V3).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 3, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 3 auf 4 ergaenzt playlist label`() {
        // Kette v1 -> v4 (neue Spalte playlists.label fuer die Workout-
        // Kopplung, Musik-Workout-Plan Phase 2); validiert gegen 4.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V4).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 4, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 4 auf 5 ergaenzt flat sets`() {
        // Kette v1 -> v5 (neue Tabelle flat_sets, FlowRep-Design Phase 2);
        // validiert gegen das exportierte Schema 5.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V5).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 5, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 5 auf 6 ergaenzt track peak`() {
        // Kette v1 -> v6 (neue Spalte track_analysis.peak_linear, Phase 8);
        // validiert gegen das exportierte Schema 6.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V6).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 6, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 6 auf 7 ergaenzt bpm und camelot key`() {
        // Kette v1 -> v7 (neue Spalten track_analysis.bpm und camelot_key,
        // Mix-Phase 1); validiert gegen das exportierte Schema 7.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V7).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 7, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 7 auf 8 ergaenzt lautheit und confidence`() {
        // Kette v1 -> v8 (neue Spalten track_analysis.bpm_confidence,
        // key_confidence, integrated_lufs, true_peak_db; Offtrack Phase 8);
        // validiert gegen das exportierte Schema 8.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V8).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 8, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 8 auf 9 ergaenzt exercise targets`() {
        // Kette v1 -> v9 (neue Tabelle exercise_targets, Flowtimer-Integration
        // Entscheidung 14); validiert gegen das exportierte Schema 9.json.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V9).absolutePath

        helper.createDatabase(dbPath, 1).close()
        helper.runMigrationsAndValidate(dbPath, 9, true, *DROPSYNC_MIGRATIONS).close()
    }

    @Test
    fun `migration 9 auf 10 trennt mix version ohne waveform zu verlieren`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V10).absolutePath

        helper.createDatabase(dbPath, 9).use { db ->
            db.execSQL(
                "INSERT INTO track_analysis " +
                    "(song_id, waveform_data, bucket_count, analyzer_version, analyzed_at_epoch_ms, " +
                    "peak_linear, bpm, bpm_confidence, camelot_key, key_confidence, integrated_lufs, true_peak_db) " +
                    "VALUES (42, X'F60AEC14', 2, 4, 1000, 0.8, 128.0, 0.9, '8A', 0.9, -12.0, -1.0)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 10, true, *DROPSYNC_MIGRATIONS).use { db ->
            db
                .query(
                    "SELECT bucket_count, analyzer_version, mix_analyzer_version, bpm " +
                        "FROM track_analysis WHERE song_id = 42",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                    assertEquals(4, cursor.getInt(1))
                    assertEquals(0, cursor.getInt(2))
                    assertEquals(128f, cursor.getFloat(3))
                }
        }
    }

    /**
     * Die eigentliche Gefahr bei v9: Die DB enthaelt die echte
     * Musikbibliothek. Eine Migration, die vorhandene Zeilen verliert, waere
     * nicht wiederherstellbar. Dieser Test schreibt Nutzdaten in v8 und
     * prueft nach der Migration, dass sie noch da sind.
     */
    @Test
    fun `migration 8 auf 9 erhaelt vorhandene daten`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V9_DATA).absolutePath

        helper.createDatabase(dbPath, 8).use { db ->
            db.execSQL(
                "INSERT INTO exercises (id, canonical_name, kind, equipment, is_custom, is_archived) " +
                    "VALUES (1, 'bench_press', 'STRENGTH', 'BARBELL', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO flat_sets (id, exercise_id, weight_milli_kg, reps, logged_at_epoch_ms) " +
                    "VALUES (1, 1, 92500, 5, 1000)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 9, true, *DROPSYNC_MIGRATIONS).use { db ->
            db.query("SELECT weight_milli_kg, reps FROM flat_sets WHERE id = 1").use { cursor ->
                assertTrue("Satz aus v8 fehlt nach der Migration", cursor.moveToFirst())
                assertEquals(92_500L, cursor.getLong(0))
                assertEquals(5, cursor.getInt(1))
            }
            // Die neue Tabelle ist leer, aber vorhanden und beschreibbar.
            db.execSQL(
                "INSERT INTO exercise_targets " +
                    "(exercise_id, target_weight_milli_kg, target_reps, updated_at_epoch_ms) " +
                    "VALUES (1, 100000, 5, 2000)",
            )
            db.query("SELECT COUNT(*) FROM exercise_targets").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_V2 = "migration-test-v2.db"
        const val TEST_DB_V3 = "migration-test-v3.db"
        const val TEST_DB_V4 = "migration-test-v4.db"
        const val TEST_DB_V5 = "migration-test-v5.db"
        const val TEST_DB_V6 = "migration-test-v6.db"
        const val TEST_DB_V7 = "migration-test-v7.db"
        const val TEST_DB_V8 = "migration-test-v8.db"
        const val TEST_DB_V9 = "migration-test-v9.db"
        const val TEST_DB_V9_DATA = "migration-test-v9-data.db"
        const val TEST_DB_V10 = "migration-test-v10.db"
    }
}
