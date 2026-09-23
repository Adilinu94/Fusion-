package com.dropsync.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * A1/5.7: v11 -> v12 macht `achieved_session_id` nullable — der flache
     * Satz-Pfad wird PR-faehig und hat keine Session. Die Tabelle wird dafuer
     * neu aufgebaut; der Test prueft, dass vorhandene PRs unveraendert
     * ueberleben und dass session-lose Eintraege danach erlaubt sind.
     */
    @Test
    fun `migration 11 auf 12 erhaelt PRs und erlaubt session-lose eintraege`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V12).absolutePath

        helper.createDatabase(dbPath, 11).use { db ->
            db.execSQL(
                "INSERT INTO exercises (id, canonical_name, kind, equipment, is_custom, is_archived) " +
                    "VALUES (1, 'bench_press', 'STRENGTH', 'BARBELL', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO workout_sessions (id, started_at_epoch_ms, zone_id_at_start, status) " +
                    "VALUES (7, 1000, 'Europe/Berlin', 'ACTIVE')",
            )
            db.execSQL(
                "INSERT INTO personal_records " +
                    "(id, exercise_id, type, achieved_session_id, achieved_cluster_id, " +
                    "value_long, value_unit, comparable_load_milli_kg, achieved_at_epoch_ms) " +
                    "VALUES (1, 1, 'HIGHEST_LOAD', 7, 3, 80000, 'MILLI_KG', 80000, 2000)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 12, true, *DROPSYNC_MIGRATIONS).use { db ->
            db
                .query(
                    "SELECT type, achieved_session_id, value_long FROM personal_records WHERE id = 1",
                ).use { cursor ->
                    assertTrue("PR aus v11 fehlt nach der Migration", cursor.moveToFirst())
                    assertEquals("HIGHEST_LOAD", cursor.getString(0))
                    assertEquals(7L, cursor.getLong(1))
                    assertEquals(80_000L, cursor.getLong(2))
                }
            // NULL ist jetzt erlaubt: PR aus einem flachen Satz ohne Session.
            db.execSQL(
                "INSERT INTO personal_records " +
                    "(exercise_id, type, achieved_session_id, achieved_cluster_id, " +
                    "value_long, value_unit, comparable_load_milli_kg, achieved_at_epoch_ms) " +
                    "VALUES (1, 'MOST_REPS_AT_LOAD', NULL, 5, 12, 'REPS', 40000, 3000)",
            )
            db.query("SELECT COUNT(*) FROM personal_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    /**
     * B4/RC-22: v12 -> v13 ergaenzt den Raster-Offset (Beat-Phase) fuer
     * das Marker-Snap. Additiv und nullable; Altzeilen behalten NULL und
     * rasten damit nicht (kein geratenes 0-ms-Raster).
     */
    @Test
    fun `migration 12 auf 13 ergaenzt das beat-raster`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V13).absolutePath

        helper.createDatabase(dbPath, 12).use { db ->
            db.execSQL(
                "INSERT INTO track_analysis " +
                    "(song_id, waveform_data, bucket_count, analyzer_version, analyzed_at_epoch_ms) " +
                    "VALUES (1, X'00', 1, 4, 1000)",
            )
        }

        helper.runMigrationsAndValidate(dbPath, 13, true, *DROPSYNC_MIGRATIONS).use { db ->
            db
                .query("SELECT downbeat_offset_ms, downbeat_confidence FROM track_analysis WHERE song_id = 1")
                .use { cursor ->
                    assertTrue("Altzeile fehlt nach der Migration", cursor.moveToFirst())
                    assertTrue("Offset muss NULL bleiben", cursor.isNull(0))
                    assertTrue("Konfidenz muss NULL bleiben", cursor.isNull(1))
                }
            // Neue Spalten sind beschreibbar (Wertebereich wie im Analyzer).
            db.execSQL(
                "UPDATE track_analysis SET downbeat_offset_ms = 137, downbeat_confidence = 0.5 " +
                    "WHERE song_id = 1",
            )
            db.query("SELECT downbeat_offset_ms FROM track_analysis WHERE song_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(137L, cursor.getLong(0))
            }
        }
    }

    /**
     * D5/A4: v13 -> v14 legt den Index auf `song_markers(source, is_enabled)`
     * an. Der Indexname ist Vertrag (Room-Validierung), und der
     * EXPLAIN-Nachweis zeigt, dass die Pending-Kandidaten-Abfrage ihn nutzt
     * statt zu scannen — mit Negativkontrolle, damit der Test nicht gruen
     * sein kann, weil EXPLAIN hier gar nicht unterscheidet.
     */
    @Test
    fun `migration 13 auf 14 legt den pending-index an und die abfrage nutzt ihn`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V14_PENDING).absolutePath

        helper.createDatabase(dbPath, 13).close()
        helper.runMigrationsAndValidate(dbPath, 14, true, *DROPSYNC_MIGRATIONS).use { db ->
            db
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_song_markers_source_is_enabled'",
                ).use { cursor ->
                    assertTrue("Index fehlt nach der Migration", cursor.moveToFirst())
                }

            // Realistischer Bestand: 50 Songs, 1000 Marker (5 % davon
            // unbestaetigte AUTO_DETECTED-Kandidaten) mit Links.
            db.execSQL(
                "WITH RECURSIVE seq(value) AS (" +
                    "SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 50) " +
                    "INSERT INTO songs (media_store_id, content_uri, display_name, relative_path, " +
                    "duration_ms, size_bytes, date_modified_seconds, title, artist, album, genre, " +
                    "is_available) SELECT value, 'content://' || value, value || '.mp3', " +
                    "'Music/', 200000, 5000000, 1700000000 + value, 'Titel ' || value, " +
                    "'Interpret', 'Album', 'Genre', 1 FROM seq",
            )
            db.execSQL(
                "WITH RECURSIVE seq(value) AS (" +
                    "SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 1000) " +
                    "INSERT INTO song_markers (source_fingerprint, label, position_ms, source, " +
                    "is_enabled, created_at_epoch_ms) SELECT 'fp' || value, 'Drop ' || value, " +
                    "value * 1000, CASE WHEN value % 20 = 0 THEN 'AUTO_DETECTED' ELSE 'IMPORT' END, " +
                    "CASE WHEN value % 20 = 0 THEN 0 ELSE 1 END, 1000 + value FROM seq",
            )
            db.execSQL(
                "INSERT INTO marker_song_links (marker_id, song_id, link_method, linked_at_epoch_ms) " +
                    "SELECT id, (id % 50) + 1, 'AUTO_DETECTED', 1000 FROM song_markers",
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
            val unindexedPlan = plan("SELECT * FROM song_markers WHERE label = 'Drop 5'")
            assertTrue(
                "EXPLAIN unterscheidet SCAN nicht - der Test beweist nichts. Plan war: $unindexedPlan",
                unindexedPlan.contains("SCAN"),
            )

            // Gemessen (2026-09-22): der Planer nutzt den neuen Index fuer den
            // reinen Filter und fuer den DELETE der Kandidaten-Ersetzung (der
            // heisse Pfad), NICHT fuer die Pending-JOIN-Query — die faehrt er
            // ueber den marker_id-Index der Links-Tabelle (Plan unten als
            // Kommentar, damit die Messung nicht als Annahme verloren geht).
            val filterPlan = plan("SELECT * FROM song_markers WHERE source = 'AUTO_DETECTED' AND is_enabled = 0")
            assertTrue(
                "Filter-Query nutzt den Index nicht. Plan war: $filterPlan",
                filterPlan.contains("index_song_markers_source_is_enabled"),
            )

            // Die echte DAO-Query (MarkerDao.deletePendingBySourceForSong).
            val deletePlan =
                plan(
                    "DELETE FROM song_markers WHERE source = 'AUTO_DETECTED' AND is_enabled = 0 AND id IN " +
                        "(SELECT marker_id FROM marker_song_links WHERE song_id = 3)",
                )
            assertTrue(
                "Kandidaten-DELETE nutzt den Index nicht. Plan war: $deletePlan",
                deletePlan.contains("index_song_markers_source_is_enabled"),
            )

            // Gegenprobe fuer die Pending-Query: sie bleibt ein Scan ueber die
            // Marker-Tabelle mit Index-Probe auf den Links
            // (SCAN TABLE song_markers AS m | SEARCH TABLE marker_song_links AS l
            // USING INDEX index_marker_song_links_marker_id ...). Kein
            // Index-Einsatz, also keine Zusicherung — nur die Kontrolle, dass
            // die Abfrage ueberhaupt ueber Indizes/Scan unterscheidbar ist.
            val pendingPlan =
                plan(
                    "SELECT m.*, l.song_id AS linked_song_id FROM song_markers m " +
                        "INNER JOIN marker_song_links l ON l.marker_id = m.id " +
                        "WHERE m.source = 'AUTO_DETECTED' AND m.is_enabled = 0 " +
                        "ORDER BY l.song_id, m.position_ms",
                )
            assertTrue(
                "Pending-Query muss ueber einen Index oder Scan laufen. Plan war: $pendingPlan",
                pendingPlan.contains("SCAN TABLE song_markers") ||
                    pendingPlan.contains("index_song_markers_source_is_enabled"),
            )
        }
    }

    /**
     * D5/A5+A6: v14 -> v15 legt die nach EXPLAIN gemessenen Indizes an.
     * Der Test misst BEIDE Zustaende: auf der v14-Datenbank (ohne Index) den
     * Full-Scan bzw. den Temp-B-Tree, nach der Migration den Index-Zugriff.
     * Die Negativkontrolle (unindizierte Spalte -> SCAN) stellt sicher, dass
     * EXPLAIN in dieser Umgebung ueberhaupt unterscheidet.
     */
    @Test
    fun `migration 14 auf 15 legt die gemessenen indizes an und die abfragen nutzen sie`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbPath = context.getDatabasePath(TEST_DB_V15_INDICES).absolutePath

        helper.createDatabase(dbPath, 14).use { db ->
            seedIndexMeasurementData(db)
            db.execSQL("ANALYZE")

            val sessionPlan =
                queryPlan(db, "SELECT * FROM workout_sessions WHERE status = 'ACTIVE' LIMIT 1")
            assertTrue("v14 muss die Sessions scannen. Plan war: $sessionPlan", sessionPlan.contains("SCAN"))
            val itemsPlan =
                queryPlan(db, "SELECT * FROM playlist_items WHERE playlist_id = 1 ORDER BY position")
            assertTrue(
                "v14 muss die Positionen sortieren. Plan war: $itemsPlan",
                itemsPlan.contains("TEMP B-TREE"),
            )
            // Die ANALYZE-Statistik ist eine eigene Tabelle und wuerde die
            // Room-Schema-Validierung nach der Migration brechen; sie hat
            // ihren Zweck (Messung oben) erfuellt.
            db.execSQL("DROP TABLE IF EXISTS sqlite_stat1")
        }

        helper.runMigrationsAndValidate(dbPath, 15, true, *DROPSYNC_MIGRATIONS).use { db ->
            // 1. Die Indexnamen sind Vertrag (Room-Validierung); der alte
            //    einspaltige Playlist-Index ist ersetzt, nicht gedoppelt.
            val indexNames = mutableSetOf<String>()
            db
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND tbl_name IN ('workout_sessions', 'playlist_items')",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(indexNames::add)
                    }
                }
            assertTrue(
                "Status-Index fehlt, vorhanden: $indexNames",
                "index_workout_sessions_status" in indexNames,
            )
            assertTrue(
                "Zusammengesetzter Playlist-Index fehlt, vorhanden: $indexNames",
                "index_playlist_items_playlist_id_position" in indexNames,
            )
            assertFalse(
                "Alter Playlist-Index muss ersetzt sein, vorhanden: $indexNames",
                "index_playlist_items_playlist_id" in indexNames,
            )

            db.execSQL("ANALYZE")

            // 2. Gegenprobe: eine unindizierte Spalte muss SCAN bleiben.
            val unindexedPlan =
                queryPlan(db, "SELECT * FROM workout_sessions WHERE zone_id_at_start = 'Europe/Berlin'")
            assertTrue(
                "EXPLAIN unterscheidet SCAN nicht - der Test beweist nichts. Plan war: $unindexedPlan",
                unindexedPlan.contains("SCAN"),
            )

            // 3. Nachher: die echten DAO-Abfragen nutzen die Indizes.
            val sessionPlan =
                queryPlan(db, "SELECT * FROM workout_sessions WHERE status = 'ACTIVE' LIMIT 1")
            assertTrue(
                "Session-Suche nutzt den Status-Index nicht. Plan war: $sessionPlan",
                sessionPlan.contains("index_workout_sessions_status"),
            )

            val itemsPlan =
                queryPlan(db, "SELECT * FROM playlist_items WHERE playlist_id = 1 ORDER BY position")
            assertTrue(
                "Playlist-Query nutzt den zusammengesetzten Index nicht. Plan war: $itemsPlan",
                itemsPlan.contains("index_playlist_items_playlist_id_position"),
            )
            assertFalse(
                "Playlist-Query sortiert weiter extern. Plan war: $itemsPlan",
                itemsPlan.contains("TEMP B-TREE"),
            )

            // Die echte DAO-Query (observeSongsOfPlaylist) als JOIN-Variante.
            val joinPlan =
                queryPlan(
                    db,
                    "SELECT s.* FROM playlist_items pi INNER JOIN songs s ON s.media_store_id = pi.song_id " +
                        "WHERE pi.playlist_id = 1 ORDER BY pi.position",
                )
            assertTrue(
                "Playlist-JOIN nutzt den zusammengesetzten Index nicht. Plan war: $joinPlan",
                joinPlan.contains("index_playlist_items_playlist_id_position"),
            )
            assertFalse(
                "Playlist-JOIN sortiert weiter extern. Plan war: $joinPlan",
                joinPlan.contains("TEMP B-TREE"),
            )
        }
    }

    /** Realistischer Bestand fuer die Index-Messung (D5/A5+A6). */
    private fun seedIndexMeasurementData(db: SupportSQLiteDatabase) {
        // 400 Sessions, davon 2 ACTIVE — die Verteilung, fuer die der
        // Status-Index gedacht ist (viele abgeschlossene, hoechstens eine
        // laufende).
        db.execSQL(
            "WITH RECURSIVE seq(value) AS (" +
                "SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 400) " +
                "INSERT INTO workout_sessions (started_at_epoch_ms, ended_at_epoch_ms, zone_id_at_start, " +
                "status, title, notes) SELECT value * 1000, value * 1000 + 900, 'Europe/Berlin', " +
                "CASE WHEN value > 398 THEN 'ACTIVE' ELSE 'COMPLETED' END, NULL, NULL FROM seq",
        )
        db.execSQL("INSERT INTO playlists (id, name, created_at_epoch_ms, label) VALUES (1, 'Work', 1000, 'WORK')")
        db.execSQL("INSERT INTO playlists (id, name, created_at_epoch_ms, label) VALUES (2, 'Rest', 1000, 'REST')")
        db.execSQL("INSERT INTO playlists (id, name, created_at_epoch_ms, label) VALUES (3, 'Mix', 1000, NULL)")
        db.execSQL(
            "WITH RECURSIVE seq(value) AS (" +
                "SELECT 1 UNION ALL SELECT value + 1 FROM seq WHERE value < 600) " +
                "INSERT INTO songs (media_store_id, content_uri, display_name, relative_path, " +
                "duration_ms, size_bytes, date_modified_seconds, title, artist, album, genre, " +
                "is_available) SELECT value, 'content://' || value, value || '.mp3', 'Music/', " +
                "200000, 5000000, 1700000000 + value, 'Titel ' || value, 'Interpret', 'Album', " +
                "'Genre', 1 FROM seq",
        )
        // 3 Playlists mit je 200 Positionen.
        db.execSQL(
            "WITH RECURSIVE seq(value) AS (" +
                "SELECT 0 UNION ALL SELECT value + 1 FROM seq WHERE value < 599) " +
                "INSERT INTO playlist_items (playlist_id, song_id, position) " +
                "SELECT (value / 200) + 1, value + 1, value % 200 FROM seq",
        )
    }

    /** `EXPLAIN QUERY PLAN` als Textzeile (Muster aus dem v11-Test). */
    private fun queryPlan(
        db: SupportSQLiteDatabase,
        sql: String,
    ): String {
        val steps = StringBuilder()
        db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                steps.append(cursor.getString(cursor.columnCount - 1)).append(" | ")
            }
        }
        return steps.toString()
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
        const val TEST_DB_V12 = "migration-test-v12.db"
        const val TEST_DB_V13 = "migration-test-v13.db"
        const val TEST_DB_V14_PENDING = "migration-test-v14-pending.db"
        const val TEST_DB_V15_INDICES = "migration-test-v15-indices.db"
    }
}
