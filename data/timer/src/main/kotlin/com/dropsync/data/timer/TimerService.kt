package com.dropsync.data.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.dropsync.core.common.Clock
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSnapshotStore
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Foreground service for the rest timer (Fusion design doc Phase 3 step 2):
 * keeps the TimerEngine alive in the pocket, drives `evaluate()` and shows a
 * countdown notification with actions Skip / +15 s / Finish exercise.
 *
 * - Notification updates run at [NOTIFY_TICK_MS] only while the timer is
 *   PREPARING/RUNNING/PAUSED (visible state); IDLE/COMPLETED/CANCELLED stop
 *   the loop instead of polling forever.
 * - Xiaomi/MIUI fallback: if POST_NOTIFICATIONS is denied or notifications
 *   are blocked for the channel, the engine keeps running in the foreground
 *   service and cues still fire via CueOutput (TTS/haptics/tone).
 */
@AndroidEntryPoint
class TimerService : Service() {
    @Inject
    lateinit var timerEngine: TimerEngine

    @Inject
    lateinit var snapshotStore: TimerSnapshotStore

    @Inject
    lateinit var monotonicStateStore: MonotonicStateStore

    @Inject
    lateinit var clock: Clock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_SKIP -> onSkip()
            ACTION_PLUS_15 -> onPlus15()
            ACTION_FINISH -> onFinish()
            else -> Unit // ACTION_START or re-delivery: just ensure we run.
        }
        promoteToForeground(timerEngine.state.value)
        startTicking()
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Actions -----------------------------------------------------------

    /** Skip: cancel the current rest and go back to IDLE. */
    private fun onSkip() {
        timerEngine.cancel(CancelReason.USER)
        timerEngine.reset()
        stopSelfIfIdle()
    }

    /** +15 s: finish the current rest, then start a fresh 15 s rest. */
    private fun onPlus15() {
        timerEngine.cancel(CancelReason.USER)
        timerEngine.reset()
        timerEngine.start(TimerMode.REST, PLUS_15_MS)
    }

    /** Finish exercise: cancel the timer immediately (design rule step 5). */
    private fun onFinish() {
        timerEngine.cancel(CancelReason.USER)
        timerEngine.reset()
        stopSelfIfIdle()
    }

    // --- Tick + notification ----------------------------------------------

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob =
            serviceScope.launch {
                while (isActive) {
                    timerEngine.evaluate()
                    val state = timerEngine.state.value
                    persistSnapshot(state)
                    updateNotification(state)
                    if (state.status !in VISIBLE_STATUSES) {
                        stopSelfIfIdle()
                        break
                    }
                    delay(NOTIFY_TICK_MS)
                }
            }
    }

    /**
     * Kill-Fallback (Testinfra-Plan Schritt 2, 5b): laufende NORMAL/REST-Timer
     * werden bei jeder Tick-Aenderung persistiert, damit ein Xiaomi-Kill den
     * Countdown nicht verliert. Endzustaende leeren das Snapshot; der letzte
     * monotone Zeitwert wird fuer die Reboot-Erkennung mitgeschrieben.
     */
    private fun persistSnapshot(state: TimerState) {
        serviceScope.launch {
            when {
                state.status in VISIBLE_STATUSES -> {
                    timerEngine.snapshot()?.let { snapshotStore.save(it) }
                }

                state.status == TimerStatus.COMPLETED || state.status == TimerStatus.CANCELLED -> {
                    snapshotStore.clear()
                }

                else -> Unit
            }
            monotonicStateStore.setLastElapsedRealtimeMs(clock.elapsedRealtimeMs())
        }
    }

    private fun promoteToForeground(state: TimerState) {
        val notification = buildNotification(state)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: SecurityException) {
            // Xiaomi/MIUI fallback: notifications blocked — engine keeps
            // running in-process; cues (TTS/haptics) still fire.
            stopSelf()
        }
    }

    private fun updateNotification(state: TimerState) {
        // Xiaomi/MIUI fallback: without the runtime permission or with the
        // channel blocked, skip notify silently instead of crashing.
        if (!notificationsAllowed()) return
        val manager = getSystemService<NotificationManager>() ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: SecurityException) {
            // Permission revoked at runtime: ignore, cues still fire.
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun buildNotification(state: TimerState): Notification {
        val contentText =
            when (state.status) {
                TimerStatus.COMPLETED -> getString(R.string.timer_notification_completed)
                else -> formatRemaining(state.remainingMs)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.timer_notification_title))
            .setContentText(contentText)
            .setContentIntent(openAppIntent())
            .setOngoing(state.status in VISIBLE_STATUSES)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(0, getString(R.string.timer_action_skip), actionIntent(ACTION_SKIP, REQUEST_SKIP))
            .addAction(0, getString(R.string.timer_action_plus15), actionIntent(ACTION_PLUS_15, REQUEST_PLUS_15))
            .addAction(0, getString(R.string.timer_action_finish), actionIntent(ACTION_FINISH, REQUEST_FINISH))
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        action: String,
        requestCode: Int,
    ): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.timer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.timer_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun stopSelfIfIdle() {
        val status = timerEngine.state.value.status
        if (status == TimerStatus.IDLE || status == TimerStatus.CANCELLED || status == TimerStatus.COMPLETED) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val CHANNEL_ID = "rest_timer"
        const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.dropsync.data.timer.action.START"
        const val ACTION_SKIP = "com.dropsync.data.timer.action.SKIP"
        const val ACTION_PLUS_15 = "com.dropsync.data.timer.action.PLUS_15"
        const val ACTION_FINISH = "com.dropsync.data.timer.action.FINISH"

        private const val NOTIFY_TICK_MS = 200L
        private const val PLUS_15_MS = 15_000L
        private const val REQUEST_SKIP = 1
        private const val REQUEST_PLUS_15 = 2
        private const val REQUEST_FINISH = 3
        private const val REQUEST_OPEN_APP = 4

        private val VISIBLE_STATUSES =
            setOf(TimerStatus.PREPARING, TimerStatus.RUNNING, TimerStatus.PAUSED)

        private fun formatRemaining(remainingMs: Long): String {
            val totalSeconds = (remainingMs + 999) / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }

        /** Starts the foreground service; safe to call on every rest start. */
        fun start(context: Context) {
            val intent = Intent(context, TimerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
