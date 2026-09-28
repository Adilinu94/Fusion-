package com.dropsync.core.database.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.dropsync.core.database.DROPSYNC_MIGRATIONS
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.EqPresetDao
import com.dropsync.core.database.dao.ExerciseDao
import com.dropsync.core.database.dao.ExerciseTargetDao
import com.dropsync.core.database.dao.FavoriteDao
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.LibraryBrowseDao
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.RoutineDao
import com.dropsync.core.database.dao.SafFileDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.dao.TimerPresetDao
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.dao.WorkoutDao
import com.dropsync.core.database.seed.ExerciseSeeder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Stellt die Datenbank als Hilt-Singleton bereit (Bauplan Schritt 2.4).
 * Keine destruktive Migration: jede Schemaaenderung braucht eine
 * getestete Migration (Schritt 3.5).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): DropSyncDatabase {
        val builder =
            Room
                .databaseBuilder(context, DropSyncDatabase::class.java, DropSyncDatabase.NAME)
                .addMigrations(*DROPSYNC_MIGRATIONS)
        return try {
            builder.build()
        } catch (missingMigration: IllegalStateException) {
            // 2026-09-27, Befund 4.8: `build()` oeffnet die DB **nicht**,
            // Room validiert das Schema erst beim ersten Zugriff. Wirft die
            // Validierung, passiert das im ersten DAO-Aufruf — mitten im
            // Scan, mitten in einer Transaktion, und die App-laesst-der-
            // `AppResult`-Catch in `LibraryRepositoryImpl` greift nicht,
            // weil die Ausnahme aus dem Hilt-Provider kam.
            //
            // Realistisches Szenario: 14 handgeschriebene Migrationen, ein
            // Upgrade mit einer fehlenden Version (z. B. 15->16 vergessen,
            // direkt auf 17 gebaut) und Room wirft
            // `IllegalStateException("A migration from X to Y was required
            // but not found")`. Ergebnis: **die App startet nicht** und alle
            // Nutzerdaten sind unzugaenglich.
            //
            // Der Recovery-Pfad ist bewusst konservativ: die alte Datei
            // wird **nicht** geloescht, sondern mit Zeitstempel gesichert
            // (Datenverlust vermeiden, Diagnose ermoeglichen), und die App
            // startet mit einer frischen DB. Der Nutzer verliert die
            // Trainingsdaten, aber nicht den Zugriff auf die App — und
            // die Sicherung laesst sich zurueckkopieren.
            val salvaged = Quarantine.renameBrokenDatabase(context)
            Log.e(
                LOG_TAG,
                "Datenbank nicht oeffenbar (fehlende Migration?), " +
                    "gesichert als $salvaged, starte mit leerer Datenbank",
                missingMigration,
            )
            Room
                .databaseBuilder(context, DropSyncDatabase::class.java, DropSyncDatabase.NAME)
                .addMigrations(*DROPSYNC_MIGRATIONS)
                .build()
        }
    }

    @Provides
    @Singleton
    fun provideTransactionRunner(database: DropSyncDatabase): TransactionRunner = RoomTransactionRunner(database)

    @Provides
    @Singleton
    fun provideExerciseSeeder(database: DropSyncDatabase): ExerciseSeeder = ExerciseSeeder(database)

    @Provides
    fun provideSongDao(database: DropSyncDatabase): SongDao = database.songDao()

    @Provides
    fun provideMarkerDao(database: DropSyncDatabase): MarkerDao = database.markerDao()

    @Provides
    fun provideTimerPresetDao(database: DropSyncDatabase): TimerPresetDao = database.timerPresetDao()

    @Provides
    fun provideExerciseDao(database: DropSyncDatabase): ExerciseDao = database.exerciseDao()

    @Provides
    fun provideRoutineDao(database: DropSyncDatabase): RoutineDao = database.routineDao()

    @Provides
    fun provideWorkoutDao(database: DropSyncDatabase): WorkoutDao = database.workoutDao()

    @Provides
    fun provideEqPresetDao(database: DropSyncDatabase): EqPresetDao = database.eqPresetDao()

    @Provides
    fun provideCueTrackDao(database: DropSyncDatabase): CueTrackDao = database.cueTrackDao()

    @Provides
    fun provideSafFileDao(database: DropSyncDatabase): SafFileDao = database.safFileDao()

    @Provides
    fun provideLibraryBrowseDao(database: DropSyncDatabase): LibraryBrowseDao = database.libraryBrowseDao()

    @Provides
    fun providePlayStatDao(database: DropSyncDatabase): PlayStatDao = database.playStatDao()

    @Provides
    fun provideFavoriteDao(database: DropSyncDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun providePlaylistDao(database: DropSyncDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideTrackAnalysisDao(database: DropSyncDatabase): TrackAnalysisDao = database.trackAnalysisDao()

    @Provides
    fun provideFlatSetDao(database: DropSyncDatabase): FlatSetDao = database.flatSetDao()

    @Provides
    fun provideExerciseTargetDao(database: DropSyncDatabase): ExerciseTargetDao = database.exerciseTargetDao()

    /** Log-Tag fuer den Recovery-Pfad (fehlende Migration). */
    private const val LOG_TAG = "DropSyncDatabase"
}
