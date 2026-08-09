package com.dropsync.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_V2 = "migration-test-v2.db"
        const val TEST_DB_V3 = "migration-test-v3.db"
        const val TEST_DB_V4 = "migration-test-v4.db"
    }
}
