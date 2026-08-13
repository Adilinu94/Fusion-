package com.dropsync.feature.workout.shadow.di

import com.dropsync.feature.workout.shadow.JsonlShadowSessionRecorder
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [ShadowSessionRecorder] to the JSONL-writing implementation
 * (SHADOW_DIFF_HARNESS_PLAN.md Schritt 2/3). Tests override this module
 * with [com.dropsync.feature.workout.shadow.NoOpShadowSessionRecorder].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShadowRecorderModule {
    @Binds
    @Singleton
    abstract fun bindShadowSessionRecorder(impl: JsonlShadowSessionRecorder): ShadowSessionRecorder
}
