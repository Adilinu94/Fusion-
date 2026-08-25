package com.dropsync.data.workout.di

import android.content.Context
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.ExerciseDao
import com.dropsync.core.database.dao.ExerciseTargetDao
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.RoutineDao
import com.dropsync.core.database.dao.WorkoutDao
import com.dropsync.data.workout.FlatSetRepositoryImpl
import com.dropsync.data.workout.TargetRepositoryImpl
import com.dropsync.data.workout.WorkoutGoalPreferencesStore
import com.dropsync.data.workout.WorkoutRepositoryImpl
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.TargetRepository
import com.dropsync.domain.workout.WorkoutGoalRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Verdrahtet den Trainingslog (Bauplan Schritt 9/10). */
@Module
@InstallIn(SingletonComponent::class)
object WorkoutDataModule {
    @Provides
    @Singleton
    fun provideWorkoutRepository(
        workoutDao: WorkoutDao,
        routineDao: RoutineDao,
        exerciseDao: ExerciseDao,
        transactionRunner: TransactionRunner,
        playbackRepository: PlaybackRepository,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): WorkoutRepository =
        WorkoutRepositoryImpl(
            workoutDao,
            routineDao,
            exerciseDao,
            transactionRunner,
            playbackRepository,
            clock,
            dispatchers,
        )

    /** Flaches Satz-Log (FlowRep Phase 2). */
    @Provides
    @Singleton
    fun provideFlatSetRepository(
        flatSetDao: FlatSetDao,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): FlatSetRepository = FlatSetRepositoryImpl(flatSetDao, clock, dispatchers)

    /** Wochenziel-DataStore (Flowtimer-Integration Schritt 7). */
    @Provides
    @Singleton
    fun provideWorkoutGoalRepository(
        @ApplicationContext context: Context,
    ): WorkoutGoalRepository = WorkoutGoalPreferencesStore(context)

    /** Uebungsziele (Flowtimer-Integration Entscheidung 14, DB v9). */
    @Provides
    @Singleton
    fun provideTargetRepository(
        targetDao: ExerciseTargetDao,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): TargetRepository = TargetRepositoryImpl(targetDao, clock, dispatchers)
}
