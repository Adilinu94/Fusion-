package com.dropsync.data.timer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dropsync.domain.timer.PlannedCue
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerSnapshot
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Round-Trip-Test fuer [DataStoreTimerSnapshotStore] (5b): das JSON-Schema
 * muss ein Snapshot verlustfrei serialisieren und deserialisieren, damit die
 * Recovery nach einem Kill denselben Zustand wiederherstellt.
 */
class TimerSnapshotStoreTest : RobolectricTestCase() {
    private fun store(dir: File): DataStoreTimerSnapshotStore =
        DataStoreTimerSnapshotStore(
            PreferenceDataStoreFactory.create(
                produceFile = { File(dir, "test_snapshot.preferences_pb") },
            ),
        )

    private fun sampleSnapshot(): TimerSnapshot =
        TimerSnapshot(
            session =
                TimerSession(
                    id = "session-42",
                    mode = TimerMode.REST,
                    durationMs = 90_000,
                    startedElapsedRealtimeMs = 1_000,
                    markerPositionMs = null,
                    plannedCues =
                        listOf(
                            PlannedCue(thresholdMs = 60_000, speak = true, haptic = true, tone = false),
                            PlannedCue(thresholdMs = 0, speak = false, haptic = true, tone = true),
                        ),
                ),
            status = TimerStatus.RUNNING,
            endElapsedRealtimeMs = 123_456,
            pausedRemainingMs = null,
        )

    @Test
    fun `save und load liefern identisches snapshot`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("snaptest").toFile()
        val store = store(dir)
        val original = sampleSnapshot()

        store.save(original)
        val loaded = store.load()

        assertEquals(original.session.id, loaded!!.session.id)
        assertEquals(original.session.mode, loaded.session.mode)
        assertEquals(original.session.durationMs, loaded.session.durationMs)
        assertEquals(original.session.startedElapsedRealtimeMs, loaded.session.startedElapsedRealtimeMs)
        assertEquals(original.session.plannedCues, loaded.session.plannedCues)
        assertEquals(original.status, loaded.status)
        assertEquals(original.endElapsedRealtimeMs, loaded.endElapsedRealtimeMs)
        assertEquals(original.pausedRemainingMs, loaded.pausedRemainingMs)
    }

    @Test
    fun `pausiertes snapshot behaelt pausedRemainingMs`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("snaptest").toFile()
        val store = store(dir)
        val paused =
            sampleSnapshot().copy(
                status = TimerStatus.PAUSED,
                endElapsedRealtimeMs = null,
                pausedRemainingMs = 45_000,
            )
        store.save(paused)
        val loaded = store.load()!!
        assertEquals(TimerStatus.PAUSED, loaded.status)
        assertEquals(45_000L, loaded.pausedRemainingMs)
        assertNull(loaded.endElapsedRealtimeMs)
    }

    @Test
    fun `clear entfernt das snapshot`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("snaptest").toFile()
        val store = store(dir)
        store.save(sampleSnapshot())
        store.clear()
        assertNull(store.load())
    }
}
