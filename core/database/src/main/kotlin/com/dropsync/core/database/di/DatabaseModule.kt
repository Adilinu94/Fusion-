package com.dropsync.core.database.di

import android.content.Context
import androidx.room.Room
import com.dropsync.core.database.DROPSYNC_MIGRATIONS
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.EqPresetDao
import com.dropsync.core.database.dao.ExerciseDao
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
    ): DropSyncDatabase =
        Room
            .databaseBuilder(context, DropSyncDatabase::class.java, DropSyncDatabase.NAME)
            .addMigrations(*DROPSYNC_MIGRATIONS)
            .build()

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
}
