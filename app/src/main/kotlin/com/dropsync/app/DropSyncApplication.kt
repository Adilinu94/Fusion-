package com.dropsync.app

import android.app.Application
import android.util.Log
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.seed.ExerciseSeeder
import com.dropsync.data.audio.EqPresetSeeder
import com.dropsync.data.audio.OutputProfileController
import com.dropsync.data.timer.TimerRecoveryStarter
import com.dropsync.feature.player.RestMusicCoordinator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-Einstieg; enthaelt keine Fachlogik (Bauplan 3.2/5).
 * Hilt stellt die wenigen langlebigen Objekte bereit (Schritt 2.4).
 */
@HiltAndroidApp
class DropSyncApplication : Application() {
    @Inject lateinit var seeder: ExerciseSeeder

    @Inject lateinit var eqPresetSeeder: EqPresetSeeder

    @Inject lateinit var outputProfileController: OutputProfileController

    @Inject lateinit var restMusicCoordinator: RestMusicCoordinator

    @Inject lateinit var timerRecoveryStarter: TimerRecoveryStarter

    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onCreate() {
        super.onCreate()
        // Pro-Ausgang-Profile (ADR-0008): Geraetewechsel beobachten und
        // Profile automatisch anwenden.
        outputProfileController.start()
        // Musik in Pausen (Musik-Workout-Plan Phase 4): beobachtet die
        // TimerEngine und steuert Rest-Playlist bzw. Drop-Landung, sofern
        // in den Einstellungen aktiviert (Default aus = kein Eingriff).
        restMusicCoordinator.start()
        // Kill-Fallback (Testinfra-Plan 5b): laufenden Resttimer nach einem
        // Xiaomi-Kill rehydrieren und den Service neu starten.
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                timerRecoveryStarter.start()
            } catch (e: Exception) {
                Log.e("DropSyncApplication", "Timer-Recovery fehlgeschlagen", e)
            }
        }
        // Standarduebungen idempotent einspielen (Schritt 3.6); der Seed
        // ueberschreibt nie Benutzerdaten und darf bei jedem Start laufen.
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                val json =
                    assets.open(ExerciseSeeder.ASSET_PATH).use {
                        it.readBytes().decodeToString()
                    }
                seeder.seed(json)
            } catch (e: Exception) {
                // Fehlerhafter Seed darf den App-Start nie verhindern.
                Log.e("DropSyncApplication", "Seed fehlgeschlagen", e)
            }
            try {
                // Eingebaute EQ-Presets idempotent einspielen (Plan Phase 2).
                eqPresetSeeder.seed()
            } catch (e: Exception) {
                Log.e("DropSyncApplication", "EQ-Preset-Seed fehlgeschlagen", e)
            }
        }
    }
}
