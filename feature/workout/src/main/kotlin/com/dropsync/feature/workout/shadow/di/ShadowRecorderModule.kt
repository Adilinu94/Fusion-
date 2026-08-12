package com.dropsync.feature.workout.shadow.di

import com.dropsync.feature.workout.shadow.NoOpShadowSessionRecorder
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [ShadowSessionRecorder] to the no-op implementation until the real
 * JSONL-writing recorder (`SHADOW_DIFF_HARNESS_PLAN.md` Schritt 2/3) lands
 * with verified Android file I/O.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShadowRecorderModule {
    @Provides
    @Singleton
    fun provideShadowSessionRecorder(): ShadowSessionRecorder = NoOpShadowSessionRecorder()
}
