package com.dropsync.feature.workout.shadow.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.feature.workout.shadow.JsonlShadowSessionRecorder
import com.dropsync.feature.workout.shadow.NoOpShadowSessionRecorder
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * RC-2 (Entscheidung 19.09.2026): Rohdaten werden nur in debuggable Builds
 * geschrieben. Die Release-App sammelt keine Sensordaten — das ist
 * Speicherlast und ein Datenschutzthema (Bewegungsprofile plus
 * Kalibrierdaten), auch wenn nichts das Geraet verlaesst. Fuer die
 * Gate-11b-Kampagne genuegt der Debug-Build.
 *
 * Die Entscheidung steht als ADR-0023; Tests koennen
 * [isRecordingEnabled] ohne Android pruefen.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShadowRecorderModule {
    @Provides
    @Singleton
    fun provideShadowSessionRecorder(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): ShadowSessionRecorder =
        if (isRecordingEnabled(context.applicationInfo.flags)) {
            JsonlShadowSessionRecorder(context, dispatchers)
        } else {
            NoOpShadowSessionRecorder()
        }

    /**
     * Reine Entscheidung: `FLAG_DEBUGGABLE` ist genau bei Debug- und
     * Benchmark-Builds gesetzt; Release-Builds fallen auf den NoOp zurueck.
     */
    fun isRecordingEnabled(applicationFlags: Int): Boolean = (applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
