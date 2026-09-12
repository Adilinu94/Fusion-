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

    /**
     * v10 -> v11 legt nur Indizes an (Verbesserungsplan B-DB-1). Rein additive
     * Migrationen gelten leicht als risikofrei, deshalb pruefen beide Faelle
     * unten das, was wirklich schiefgehen kann: Zeilen verlieren, und Indizes
     * anlegen, die die Queries dann nicht benutzen.
     */
    @Test
    fun `migration 10 auf 11 erhaelt die bibliothek und legt indizes an`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V11).absolutePath

        helper.createDatabase(dbPath, 10).use { db ->
            db.execSQL(
                "INSERT INTO songs (media_store_id, content_uri, display_name, relative_path, " +
                    "duration_ms, size_bytes, date_modified_seconds, title, artist, album, genre, " +
                    "is_available) VALUES " +
                    "(1, 'content://1', 'a.mp3', 'Music/Rock/', 200000, 5000000, 1700000000, " +
                    "'Erster Titel', 'Interpret A', 'Album A', 'Rock', 1)",
            )
            db.execSQL(
                "INSERT INTO song_markers (id, source_fingerprint, label, position_ms, source, " +
                    "is_enabled, created_at_epoch_ms) " +
                    "VALUES (1, 'fp-abc', 'Drop', 45000, 'IMPORTED', 1, 1700000000000)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 11, true, *DROPSYNC_MIGRATIONS).use { db ->
            // 1. Die Bibliothek ist noch da.
            db
                .query("SELECT title, album, genre FROM songs WHERE media_store_id = 1")
                .use { cursor ->
                    assertTrue("Song aus v10 fehlt nach der Migration", cursor.moveToFirst())
                    assertEquals("Erster Titel", cursor.getString(0))
                    assertEquals("Album A", cursor.getString(1))
                    assertEquals("Rock", cursor.getString(2))
                }
            db.query("SELECT source_fingerprint FROM song_markers WHERE id = 1").use { cursor ->
                assertTrue("Marker aus v10 fehlt nach der Migration", cursor.moveToFirst())
                assertEquals("fp-abc", cursor.getString(0))
            }

            // 2. Die Indizes existieren unter genau den Namen, die Room aus den
            //    Entities ableitet - sonst haette runMigrationsAndValidate schon
            //    geworfen, aber der Name ist auch der Vertrag fuer 11.json.
            val indexNames = mutableSetOf<String>()
            db
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('songs', 'song_markers')")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(indexNames::add)
                    }
                }
            listOf(
                "index_songs_is_available_album",
                "index_songs_is_available_artist",
                "index_songs_is_available_genre",
                "index_songs_is_available_relative_path",
                "index_songs_is_available_date_modified_seconds",
                "index_song_markers_source_fingerprint",
            ).forEach { expected ->
                assertTrue("Index $expected fehlt, vorhanden: $indexNames", expected in indexNames)
            }
        }
    }

    /**
     * Der ehrliche Nachweis: ein Index, den der Planer nicht benutzt, ist nur
     * Schreiblast. `EXPLAIN QUERY PLAN` nennt entweder `SCAN songs` oder
     * `SEARCH songs USING INDEX ...` - der Unterschied ist genau der Befund
     * aus B-DB-1.
     */
    @Test
    fun `browse-queries benutzen die neuen indizes statt zu scannen`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V11_PLAN).absolutePath

        helper.createDatabase(dbPath, 10).close()
        helper.runMigrationsAndValidate(dbPath, 11, true, *DROPSYNC_MIGRATIONS).use { db ->
            // Ohne Statistiken waehlt SQLite bei leerer Tabelle gern den Scan;
            // ANALYZE gibt dem Planer die Grundlage, die er im Betrieb hat.
            // Rekursive CTE statt generate_series - das Modul ist in
            // Robolectrics SQLite-Build nicht eingebaut.
            db.execSQL(
                "WITH RECURSIVE seq(value) AS (" +
                    "SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 500) " +
                    "INSERT INTO songs (media_store_id, content_uri, display_name, relative_path, " +
                    "duration_ms, size_bytes, date_modified_seconds, title, artist, album, genre, " +
                    "is_available) SELECT value, 'content://' || value, value || '.mp3', " +
                    "'Music/Ordner' || (value % 20) || '/', 200000, 5000000, 1700000000 + value, " +
                    "'Titel ' || value, 'Interpret ' || (value % 50), 'Album ' || (value % 100), " +
                    "'Genre ' || (value % 8), 1 FROM seq",
            )
            db.execSQL("ANALYZE")

            fun plan(sql: String): String {
                val steps = StringBuilder()
                db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                    while (cursor.moveToNext()) {
                        steps.append(cursor.getString(cursor.columnCount - 1)).append(" | ")
                    }
                }
                return steps.toString()
            }

            // Gegenprobe: eine Spalte ohne Index muss als SCAN erscheinen.
            // Ohne diese Zeile koennte der Test gruen sein, weil EXPLAIN in
            // dieser Umgebung gar nicht zwischen SCAN und SEARCH trennt.
            val unindexedPlan = plan("SELECT * FROM songs WHERE known_sha256 = 'abc'")
            assertTrue(
                "EXPLAIN unterscheidet SCAN nicht - der Test beweist nichts. Plan war: $unindexedPlan",
                unindexedPlan.contains("SCAN"),
            )

            mapOf(
                "index_songs_is_available_album" to
                    "SELECT * FROM songs WHERE is_available = 1 AND album = 'Album 7'",
                "index_songs_is_available_artist" to
                    "SELECT * FROM songs WHERE is_available = 1 AND artist = 'Interpret 7'",
                "index_songs_is_available_genre" to
                    "SELECT * FROM songs WHERE is_available = 1 AND genre = 'Genre 3'",
                "index_songs_is_available_relative_path" to
                    "SELECT * FROM songs WHERE is_available = 1 AND relative_path = 'Music/Ordner3/'",
                "index_songs_is_available_date_modified_seconds" to
                    "SELECT * FROM songs WHERE is_available = 1 ORDER BY date_modified_seconds DESC LIMIT 25",
            ).forEach { (index, sql) ->
                val queryPlan = plan(sql)
                assertTrue(
                    "Query nutzt $index nicht. Plan war: $queryPlan",
                    queryPlan.contains(index),
                )
            }

            // Das Alben-Aggregat darf fuer GROUP BY nicht sortieren muessen.
            val albumPlan =
                plan(
                    "SELECT album, COUNT(*) FROM songs WHERE is_available = 1 " +
                        "AND album IS NOT NULL AND album != '' GROUP BY album",
                )
            assertTrue(
                "GROUP BY album nutzt den Index nicht. Plan war: $albumPlan",
                albumPlan.contains("index_songs_is_available_album"),
            )
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
        const val TEST_DB_V11 = "migration-test-v11.db"
        const val TEST_DB_V11_PLAN = "migration-test-v11-plan.db"
    }
}
