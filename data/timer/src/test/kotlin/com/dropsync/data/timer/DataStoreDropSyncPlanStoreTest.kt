package com.dropsync.data.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.timer.DropSyncPlanKind
import com.dropsync.domain.timer.DropSyncPlanMarker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C13-Marker-Persistenz (Entscheidungen 5.8/5.9): Roundtrip je Art,
 * "kein Marker" bei fehlenden/unlesbaren Werten und das Loeschen.
 */
class DataStoreDropSyncPlanStoreTest : RobolectricTestCase() {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "test_dropsync_plan.preferences_pb") },
        )

    private fun store(dataStore: DataStore<Preferences>) = DataStoreDropSyncPlanStore(dataStore)

    @Test
    fun `ohne Marker ist load null`() =
        runTest {
            assertNull(store(dataStore()).load())
        }

    @Test
    fun `save und load round-tript Art und Sitzungs-ID`() =
        runTest {
            val store = store(dataStore())
            store.save(DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, "session-1"))
            assertEquals(
                DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, "session-1"),
                store.load(),
            )
        }

    @Test
    fun `clear entfernt den Marker`() =
        runTest {
            val store = store(dataStore())
            store.save(DropSyncPlanMarker(DropSyncPlanKind.MANUAL_DROP_REST, "session-2"))
            store.clear()
            assertNull(store.load())
        }

    @Test
    fun `unlesbare Art gilt als kein Marker`() =
        runTest {
            val dataStore = dataStore()
            dataStore.edit {
                it[stringPreferencesKey("dropsync_plan_kind")] = "KAPUTT"
                it[stringPreferencesKey("dropsync_plan_session_id")] = "session-3"
            }
            assertNull(store(dataStore).load())
        }

    @Test
    fun `leere Sitzungs-ID gilt als kein Marker`() =
        runTest {
            val dataStore = dataStore()
            dataStore.edit {
                it[stringPreferencesKey("dropsync_plan_kind")] = DropSyncPlanKind.AUTO_LANDING.name
                it[stringPreferencesKey("dropsync_plan_session_id")] = "   "
            }
            assertNull(store(dataStore).load())
        }

    @Test
    fun `gespeicherter Marker liegt als Preferences vor`() =
        runTest {
            val dataStore = dataStore()
            store(dataStore).save(DropSyncPlanMarker(DropSyncPlanKind.AUTO_LANDING, "session-4"))
            val prefs = dataStore.data.first()
            assertEquals("AUTO_LANDING", prefs[stringPreferencesKey("dropsync_plan_kind")])
            assertEquals("session-4", prefs[stringPreferencesKey("dropsync_plan_session_id")])
        }
}
