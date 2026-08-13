package com.dropsync.data.timer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import com.dropsync.core.common.Clock
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeTimerSnapshotStore
import com.dropsync.data.timer.di.TimerDataModule
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSnapshotStore
import com.dropsync.domain.timer.TimerStatus
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Schicht-2-Test (Robolectric + Hilt): Service-Lifecycle +
 * Foreground-Notification + Xiaomi/MIUI-Fallback + Kill-Fallback-Persistenz
 * (Abschnitt-11-Punkt "TimerService Foreground", Abschnitt-13-Risiko
 * "Xiaomi killt Service").
 *
 * Der Service wird als Basisklasse gebaut; Hilt injiziert die vier
 * Abhaengigkeiten aus den [BindValue]-Feldern, damit kein echtes Modul
 * (TTS/Vibrator/Ducking) gebaut werden muss.
 */
@HiltAndroidTest
@UninstallModules(TimerDataModule::class)
class TimerServiceForegroundTest : HiltRobolectricTestCase() {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val fakeClock = FakeClock()

    private val fakeSnapshotStore = FakeTimerSnapshotStore()

    private val fakeMonotonicStore = FakeMonotonicStateStore()

    @BindValue
    @JvmField
    val clock: Clock = fakeClock

    @BindValue
    @JvmField
    val snapshotStore: TimerSnapshotStore = fakeSnapshotStore

    @BindValue
    @JvmField
    val monotonicStateStore: MonotonicStateStore = fakeMonotonicStore

    @BindValue
    @JvmField
    val timerEngine: TimerEngine = TimerEngine(clock, NoOpCueOutput()) { "session-test" }

    private fun notificationManager(): NotificationManager =
        RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)

    private fun idleMainLooper(durationMs: Long = 0) {
        val looper = shadowOf(Looper.getMainLooper())
        if (durationMs > 0) {
            looper.idleFor(Duration.ofMillis(durationMs))
        } else {
            looper.idle()
        }
    }

    private fun grantNotificationsPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startService(): TimerService {
        val controller = Robolectric.buildService(TimerService::class.java)
        controller.create()
        controller.startCommand(0, 1)
        idleMainLooper()
        return controller.get()
    }

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `onStartCommand meldet START_STICKY`() {
        val controller = Robolectric.buildService(TimerService::class.java)
        controller.create()
        val result = controller.get().onStartCommand(null, 0, 1)
        assertEquals(Service.START_STICKY, result)
    }

    @Test
    fun `mit erteilter permission wird die foreground notification gezeigt`() {
        grantNotificationsPermission()
        timerEngine.start(TimerMode.REST, 60_000)
        startService()

        val notification =
            shadowOf(notificationManager()).getNotification(TimerService.NOTIFICATION_ID)
        assertNotNull(notification)
        assertTrue(notification!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(TimerStatus.RUNNING, timerEngine.state.value.status)
    }

    @Test
    fun `xiaomi fallback ohne permission crasht nicht und timer laeuft weiter`() {
        // POST_NOTIFICATIONS nicht erteilt (Default in Robolectric, SDK 34):
        // kein Crash, Engine laeuft weiter. Robolectric enforct die
        // Permission im startForeground-Pfad nicht, daher prueft der Test
        // die Invariante (Service lebt, Timer RUNNING), nicht die
        // Notification-Anzeige.
        timerEngine.start(TimerMode.REST, 60_000)
        startService()

        assertEquals(TimerStatus.RUNNING, timerEngine.state.value.status)
    }

    @Test
    fun `evaluate treibt den timer ueber den service tick`() {
        grantNotificationsPermission()
        timerEngine.start(TimerMode.REST, 60_000)
        startService()
        assertEquals(60_000L, timerEngine.state.value.remainingMs)

        (clock as FakeClock).advanceBy(5_000)
        idleMainLooper(durationMs = 250)

        assertEquals(55_000L, timerEngine.state.value.remainingMs)
    }

    @Test
    fun `laufender timer wird als snapshot persistiert`() {
        grantNotificationsPermission()
        timerEngine.start(TimerMode.REST, 60_000)
        startService()

        idleMainLooper(durationMs = 250)

        val saved = fakeSnapshotStore.snapshot
        assertNotNull("Snapshot wurde nicht gespeichert", saved)
        assertEquals(TimerStatus.RUNNING, saved!!.status)
        assertNotNull("monotoner Zeitwert fehlt", fakeMonotonicStore.stored)
    }

    @Test
    fun `abgeschlossener timer leert das snapshot`() {
        grantNotificationsPermission()
        timerEngine.start(TimerMode.REST, 5_000)
        startService()

        // Erst wird gespeichert ...
        idleMainLooper(durationMs = 250)
        assertNotNull(fakeSnapshotStore.snapshot)

        // ... dann laeuft der Timer ab: COMPLETED leert den Store.
        (clock as FakeClock).advanceBy(6_000)
        idleMainLooper(durationMs = 250)
        assertEquals(TimerStatus.COMPLETED, timerEngine.state.value.status)
        assertNull("Snapshot wurde nach Abschluss nicht geleert", fakeSnapshotStore.snapshot)
    }

    @Test
    fun `pause wird als snapshot mit eingefrorener restzeit persistiert`() {
        grantNotificationsPermission()
        timerEngine.start(TimerMode.REST, 60_000)
        startService()

        timerEngine.pause()
        idleMainLooper(durationMs = 250)

        val saved = fakeSnapshotStore.snapshot
        assertNotNull(saved)
        assertEquals(TimerStatus.PAUSED, saved!!.status)
        assertTrue(saved.pausedRemainingMs!! > 0)
    }
}
