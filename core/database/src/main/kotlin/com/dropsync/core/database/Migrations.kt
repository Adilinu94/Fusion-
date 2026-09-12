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

/**
 * v6 -> v7: fuegt `track_analysis` die Spalten `bpm` und `camelot_key`
 * hinzu (Mix-Uebergaenge-Plan Phase 1). Additiv, nullable, ohne Default;
 * alte Analysen bleiben als Waveform-Fallback gueltig, der
 * Analyzer-Version-Bump (3 -> 4) stoesst die Neuberechnung an.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `bpm` REAL")
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `camelot_key` TEXT")
        }
    }

/**
 * v7 -> v8: fuegt `track_analysis` die Spalten `bpm_confidence`,
 * `key_confidence`, `integrated_lufs` und `true_peak_db` hinzu
 * (Offtrack Phase 8, Analyse-/Lautheits-Erweiterung). Additiv, nullable,
 * ohne Default; alte Eintraege bleiben als Waveform-Fallback gueltig.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `bpm_confidence` REAL")
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `key_confidence` REAL")
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `integrated_lufs` REAL")
            db.execSQL("ALTER TABLE `track_analysis` ADD COLUMN `true_peak_db` REAL")
        }
    }

/**
 * v8 -> v9: legt `exercise_targets` an (Flowtimer-Integration
 * Entscheidung 14, CONTEXT E4/Punkt 3). Rein additiv — keine bestehende
 * Tabelle wird angefasst, die echte Musikbibliothek bleibt unberuehrt.
 *
 * `exercise_id` ist Primary Key: genau ein Ziel je Uebung. Gewicht als
 * ganze Millikilogramm (INTEGER), nie REAL — Gleitkommazahlen sind in
 * Entities verboten (Schritt 3.2).
 *
 * Die Spaltenreihenfolge und die `CREATE TABLE`-Form muessen mit dem
 * generierten Schema uebereinstimmen, sonst schlaegt `MigrationTest` fehl:
 * Room vergleicht das migrierte Schema mit `9.json`.
 */
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `exercise_targets` (" +
                    "`exercise_id` INTEGER NOT NULL, " +
                    "`target_weight_milli_kg` INTEGER NOT NULL, " +
                    "`target_reps` INTEGER NOT NULL, " +
                    "`updated_at_epoch_ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`exercise_id`), " +
                    "FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

/**
 * v9 -> v10: versioniert Mix-Metadaten getrennt von der Waveform. Bestehende
 * BPM-/Key-Werte erhalten Version 0 und werden im Hintergrund neu berechnet;
 * ihre Waveform bleibt gueltig und sofort sichtbar.
 */
val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `track_analysis` ADD COLUMN `mix_analyzer_version` " +
                    "INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

/**
 * v10 -> v11: legt die fehlenden Indizes auf `songs` und `song_markers` an
 * (Verbesserungsplan B-DB-1). Rein additiv - keine Spalte, keine Zeile, kein
 * Datentyp aendert sich; nur Lesezugriffe werden schneller.
 *
 * `songs` hatte bis v10 ausser dem Primaerschluessel keinen einzigen Index,
 * obwohl zehn Browse-Queries darauf gruppieren oder filtern. Bei einer
 * 5.000-Titel-Bibliothek war jede davon ein Full-Table-Scan.
 *
 * Die Namen muessen exakt denen entsprechen, die Room aus den
 * `@Entity(indices = ...)`-Eintraegen ableitet (`index_<tabelle>_<spalten>`),
 * sonst schlaegt `runMigrationsAndValidate` fehl.
 */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_songs_is_available_album` " +
                    "ON `songs` (`is_available`, `album`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_songs_is_available_artist` " +
                    "ON `songs` (`is_available`, `artist`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_songs_is_available_genre` " +
                    "ON `songs` (`is_available`, `genre`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_songs_is_available_relative_path` " +
                    "ON `songs` (`is_available`, `relative_path`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_songs_is_available_date_modified_seconds` " +
                    "ON `songs` (`is_available`, `date_modified_seconds`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_song_markers_source_fingerprint` " +
                    "ON `song_markers` (`source_fingerprint`)",
            )
        }
    }

/** Vollstaendige Migrationskette der Datenbank (Reihenfolge egal). */
val DROPSYNC_MIGRATIONS: Array<Migration> =
    arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
    )
