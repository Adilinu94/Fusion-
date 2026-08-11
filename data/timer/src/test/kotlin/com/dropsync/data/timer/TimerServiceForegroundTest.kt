package com.dropsync.data.timer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import com.dropsync.core.testing.FakeClock
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Schicht-2-Test (Robolectric): Service-Lifecycle + Foreground-Notification
 * + Xiaomi/MIUI-Fallback (Abschnitt-11-Punkt "TimerService Foreground",
 * Abschnitt-13-Risiko "Xiaomi killt Service").
 *
 * `TimerService` wird als Basisklasse gebaut (die Hilt-Injektion passiert
 * in der generierten `Hilt_`-Subklasse), daher wird die public
 * `lateinit timerEngine` hier direkt gesetzt.
 */
class TimerServiceForegroundTest : RobolectricTestCase() {
    private val clock = FakeClock()

    private fun newEngine(): TimerEngine =
        TimerEngine(
            clock = clock,
            cueOutput = NoOpCueOutput(),
            idGenerator = { "session-test" },
        )

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

    private fun startService(engine: TimerEngine): TimerService {
        val controller = Robolectric.buildService(TimerService::class.java)
        controller.get().timerEngine = engine
        controller.create()
        controller.startCommand(0, 1)
        idleMainLooper()
        return controller.get()
    }

    @Test
    fun `onStartCommand meldet START_STICKY`() {
        val controller = Robolectric.buildService(TimerService::class.java)
        controller.get().timerEngine = newEngine()
        controller.create()
        val result = controller.startCommand(0, 1)
        assertEquals(Service.START_STICKY, result)
    }

    @Test
    fun `mit erteilter permission wird die foreground notification gezeigt`() {
        grantNotificationsPermission()
        val engine = newEngine()
        engine.start(TimerMode.REST, 60_000)
        startService(engine)

        val notification =
            shadowOf(notificationManager()).getNotification(TimerService.NOTIFICATION_ID)
        assertNotNull(notification)
        assertTrue(notification!!.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
    }

    @Test
    fun `xiaomi fallback ohne permission zeigt keine notification und timer laeuft weiter`() {
        // POST_NOTIFICATIONS nicht erteilt (Default in Robolectric, SDK 34):
        // kein Crash, keine gepostete Notification, Engine laeuft weiter.
        val engine = newEngine()
        engine.start(TimerMode.REST, 60_000)
        startService(engine)

        val notification =
            shadowOf(notificationManager()).getNotification(TimerService.NOTIFICATION_ID)
        assertNull(notification)
        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
    }

    @Test
    fun `evaluate treibt den timer ueber den service tick`() {
        grantNotificationsPermission()
        val engine = newEngine()
        engine.start(TimerMode.REST, 60_000)
        startService(engine)
        assertEquals(60_000L, engine.state.value.remainingMs)

        clock.advanceBy(5_000)
        idleMainLooper(durationMs = 250)

        assertEquals(55_000L, engine.state.value.remainingMs)
    }
}
