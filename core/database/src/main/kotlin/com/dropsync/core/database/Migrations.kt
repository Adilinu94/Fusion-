package com.dropsync.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Getestete Schema-Migrationen (Bauplan Schritt 3.5). Destruktive
// Migration ist in Release verboten; jede neue Version ergaenzt hier eine
// additive Migration und einen Eintrag in DROPSYNC_MIGRATIONS.

/**
 * v1 -> v2: fuegt die Tabelle `exercise_rest_prefs` hinzu (pro Uebung
 * gemerkter Resttimer, Abschnitt 8). Rein additiv; bestehende Daten
 * bleiben unveraendert. Das CREATE TABLE entspricht exakt dem von Room
 * erzeugten Schema, damit der Migrationstest validiert.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_rest_prefs` (" +
                    "`exercise_id` INTEGER NOT NULL, " +
                    "`rest_seconds` INTEGER NOT NULL, " +
                    "`rest_mode` TEXT NOT NULL, " +
                    "PRIMARY KEY(`exercise_id`), " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
        }
    }

/**
 * v2 -> v3: fuegt die Tabelle `track_analysis` hinzu (Waveform-/Analyse-
 * Cache, Marker/Waveform-Plan Phase 2). Rein additiv; bestehende Daten
 * bleiben unveraendert.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `track_analysis` (" +
                    "`song_id` INTEGER NOT NULL, " +
                    "`waveform_data` BLOB NOT NULL, " +
                    "`bucket_count` INTEGER NOT NULL, " +
                    "`analyzer_version` INTEGER NOT NULL, " +
                    "`analyzed_at_epoch_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`song_id`))",
            )
        }
    }

/**
 * v3 -> v4: fuegt der Tabelle `playlists` die Spalte `label` hinzu
 * (Playlist-Label Rest/Work fuer die Workout-Kopplung, Musik-Workout-Plan
 * Phase 2). Rein additiv (nullable, kein Default); bestehende Playlisten
 * bleiben ohne Label.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `playlists` ADD COLUMN `label` TEXT")
        }
    }

/**
 * v4 -> v5: fuegt die Tabelle `flat_sets` hinzu (flaches Satz-Log gemaess
 * FlowRep-Design Phase 2). Rein additiv; bestehende Daten bleiben
 * unveraendert. Das CREATE TABLE entspricht exakt dem von Room erzeugten
 * Schema, damit der Migrationstest validiert.
 */
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `flat_sets` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`exercise_id` INTEGER NOT NULL, " +
                    "`weight_milli_kg` INTEGER NOT NULL, " +
                    "`reps` INTEGER NOT NULL, " +
                    "`logged_at_epoch_ms` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flat_sets_exercise_id` ON `flat_sets`(`exercise_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flat_sets_logged_at_epoch_ms` ON `flat_sets`(`logged_at_epoch_ms`)",
            )
        }
    }

/**
 * v5 -> v6: fuegt `track_analysis` die Spalte `peak_linear` hinzu
 * (visuelle Lautheits-Normalisierung, Phase 8). Additiv mit Default 0;
 * alte Analysen bleiben gueltig (Peak 0 = keine Anhebung), der
 * Analyzer-Version-Bump (2 -> 3) sorgt fuer die Neu-Analyse.
 */
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `track_analysis` ADD COLUMN `peak_linear` REAL NOT NULL DEFAULT 0.0",
            )
        }
    }

/** Vollstaendige Migrationskette der Datenbank (Reihenfolge egal). */
val DROPSYNC_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
