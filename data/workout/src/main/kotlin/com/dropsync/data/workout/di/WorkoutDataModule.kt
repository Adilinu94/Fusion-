package com.dropsync.data.workout.di

import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.ExerciseDao
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.RoutineDao
import com.dropsync.core.database.dao.WorkoutDao
import com.dropsync.data.workout.FlatSetRepositoryImpl
import com.dropsync.data.workout.WorkoutRepositoryImpl
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
}
