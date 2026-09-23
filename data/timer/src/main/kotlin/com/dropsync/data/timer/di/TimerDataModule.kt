package com.dropsync.data.timer.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.common.datastore.createResilientPreferencesDataStore
import com.dropsync.data.timer.AndroidCueOutput
import com.dropsync.data.timer.AndroidSetLogHaptics
import com.dropsync.data.timer.CompletionTonePlayer
import com.dropsync.data.timer.CountdownBeepPlayer
import com.dropsync.data.timer.DataStoreDropSyncPlanStore
import com.dropsync.data.timer.DataStoreMonotonicStateStore
import com.dropsync.data.timer.DataStoreTimerSnapshotStore
import com.dropsync.data.timer.DefaultDropRestRequestBus
import com.dropsync.data.timer.DuckingController
import com.dropsync.data.timer.HapticsAdapter
import com.dropsync.data.timer.MonotonicStateStore
import com.dropsync.data.timer.RestTimerPreferencesStore
import com.dropsync.data.timer.SpeechTextFormatter
import com.dropsync.data.timer.TimerService
import com.dropsync.data.timer.TtsSpeaker
import com.dropsync.domain.playback.PlayerVolumeGate
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.DefaultRestTimerRecovery
import com.dropsync.domain.timer.DropRestRequestBus
import com.dropsync.domain.timer.DropSyncPlanStore
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import com.dropsync.domain.timer.RestTimerRecovery
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerSnapshotStore
import com.dropsync.domain.workout.SetLogHaptics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Singleton

/**
 * Verdrahtet Timerkern und Cue-Ausgabe (Bauplan Schritt 7/8):
 * genau eine TimerEngine, ein CueOutput, ein DuckingController.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimerDataModule {
    @Provides
    @Singleton
    fun provideDuckingController(volumeGate: PlayerVolumeGate): DuckingController = DuckingController(volumeGate)

    @Provides
    @Singleton
    fun provideCueOutput(
        @ApplicationContext context: Context,
        ducking: DuckingController,
        dispatchers: DispatcherProvider,
    ): CueOutput {
        val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
        val speaker =
            TtsSpeaker(context) { cueSessionId ->
                // TTS fertig/fehlgeschlagen: Ducking genau dieser Session
                // zuruecknehmen; fremde Sessions bleiben unberuehrt (8.3).
                scope.launch { ducking.endCue(cueSessionId) }
            }
        speaker.initialize(Locale.getDefault())
        return AndroidCueOutput(
            tts = speaker,
            haptics = HapticsAdapter(context),
            tonePlayer = CompletionTonePlayer(),
            beepPlayer = CountdownBeepPlayer(),
            ducking = ducking,
            formatter = SpeechTextFormatter(Locale.getDefault()),
            scope = scope,
        )
    }

    @Provides
    @Singleton
    fun provideTimerEngine(
        clock: Clock,
        cueOutput: CueOutput,
    ): TimerEngine = TimerEngine(clock, cueOutput)

    /** Phase 3: features start the foreground service via the domain port. */
    @Provides
    @Singleton
    fun provideRestTimerServiceStarter(
        @ApplicationContext context: Context,
    ): RestTimerServiceStarter = RestTimerServiceStarter { TimerService.start(context) }

    /** Schmale Workout-zu-Player-Kopplung fuer Drop-Rest (Schritt 11). */
    @Provides
    @Singleton
    fun provideDropRestRequestBus(): DropRestRequestBus = DefaultDropRestRequestBus()

    /** Resttimer-Presets (B8) und Get-Ready-Vorlauf (B9), Musik-Workout-Plan Phase 6. */
    @Provides
    @Singleton
    fun provideRestTimerPreferences(
        @ApplicationContext context: Context,
    ): RestTimerPreferencesRepository = RestTimerPreferencesStore(context)

    @Provides
    @Singleton
    fun provideMonotonicStateStore(
        @ApplicationContext context: Context,
    ): MonotonicStateStore =
        DataStoreMonotonicStateStore(
            createResilientPreferencesDataStore {
                context.preferencesDataStoreFile(DataStoreMonotonicStateStore.DATA_STORE_NAME)
            },
        )

    /** Kill-Fallback (Testinfra-Plan Schritt 2, 5b): Snapshot als JSON. */
    @Provides
    @Singleton
    fun provideTimerSnapshotStore(
        @ApplicationContext context: Context,
    ): TimerSnapshotStore =
        DataStoreTimerSnapshotStore(
            createResilientPreferencesDataStore {
                context.preferencesDataStoreFile(DataStoreTimerSnapshotStore.DATA_STORE_NAME)
            },
        )

    /** C13: Marker der aktiven DropSync-Sitzung fuer den Kill-Fallback. */
    @Provides
    @Singleton
    fun provideDropSyncPlanStore(
        @ApplicationContext context: Context,
    ): DropSyncPlanStore =
        DataStoreDropSyncPlanStore(
            createResilientPreferencesDataStore {
                context.preferencesDataStoreFile(DataStoreDropSyncPlanStore.DATA_STORE_NAME)
            },
        )

    /** Kill-Fallback (5b): reine Rehydrier-Entscheidung. */
    @Provides
    @Singleton
    fun provideRestTimerRecovery(store: TimerSnapshotStore): RestTimerRecovery = DefaultRestTimerRecovery(store)

    /**
     * A1 (5.6/5.15): Haptik beim Satz-Speichern — kurzer Impuls, nur nach
     * erfolgreichem Log (die Entscheidung faellt im SetLogController).
     */
    @Provides
    @Singleton
    fun provideSetLogHaptics(
        @ApplicationContext context: Context,
    ): SetLogHaptics = AndroidSetLogHaptics(HapticsAdapter(context))
}
