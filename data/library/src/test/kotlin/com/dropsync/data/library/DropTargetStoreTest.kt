package com.dropsync.data.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * P2-21: Das bevorzugte DropSync-Ziel wird je Song als DataStore-Eintrag
 * gehalten. Der Test prueft Setzen, Lesen, Ersetzen und Entfernen sowie
 * die Trennung mehrerer Songs (die Planung liest alle Ziele auf einmal).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DropTargetStoreTest {
    private fun store(dir: File): DropTargetStore =
        DropTargetStore(
            PreferenceDataStoreFactory.create(
                produceFile = { File(dir, "drop_targets.preferences_pb") },
            ),
        )

    private fun tempDir(): File = Files.createTempDirectory("droptarget").toFile()

    @Test
    fun `setzen lesen und entfernen eines ziels`() =
        runTest {
            val store = store(tempDir())

            assertNull(store.observeTargetMarkerId(7L).first())

            store.setTarget(7L, 42L)
            assertEquals(42L, store.observeTargetMarkerId(7L).first())
            assertEquals(mapOf(7L to 42L), store.targets.first())

            store.setTarget(7L, 43L)
            assertEquals(43L, store.observeTargetMarkerId(7L).first())

            store.clearTarget(7L)
            assertNull(store.observeTargetMarkerId(7L).first())
            assertTrue(store.targets.first().isEmpty())
        }

    @Test
    fun `mehrere songs bleiben getrennt`() =
        runTest {
            val store = store(tempDir())

            store.setTarget(7L, 42L)
            store.setTarget(8L, 99L)

            assertEquals(42L, store.observeTargetMarkerId(7L).first())
            assertEquals(99L, store.observeTargetMarkerId(8L).first())
            assertEquals(mapOf(7L to 42L, 8L to 99L), store.targets.first())

            store.clearTarget(7L)
            assertEquals(mapOf(8L to 99L), store.targets.first())
        }
}
