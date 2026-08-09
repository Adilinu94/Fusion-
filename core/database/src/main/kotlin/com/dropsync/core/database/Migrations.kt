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

/** Vollstaendige Migrationskette der Datenbank (Reihenfolge egal). */
val DROPSYNC_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
