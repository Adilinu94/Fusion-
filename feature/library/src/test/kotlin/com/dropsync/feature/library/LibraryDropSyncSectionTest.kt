package com.dropsync.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 1): Music Home — Marker-Review und die Work-/Rest-Karten (C4).
 * Die Tests halten die Regeln fest, die der Nutzer sieht: Kandidaten mit
 * ihren drei Aktionen, Abdeckung ("9/12 drops"), offene Reviews und der
 * Erkennungs-Einstieg, wenn noch keine Marker da sind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryDropSyncSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `marker-review listet kandidaten und ruft die aktionen mit der id`() {
        val confirmed = mutableListOf<Long>()
        val discarded = mutableListOf<Long>()
        val previewed = mutableListOf<Long>()
        compose.setContent {
            FlowRepTheme {
                MarkerReviewSection(
                    markers = listOf(marker(id = 7L, songId = 42L, positionMs = 75_600L)),
                    songs = listOf(song(42L, "Neon Alley")),
                    onConfirm = { confirmed += it },
                    onDiscard = { discarded += it },
                    onPreview = { previewed += it.id },
                )
            }
        }

        compose.onNodeWithText("Review markers").assertIsDisplayed()
        compose.onNodeWithText("1 drop candidates still to review").assertIsDisplayed()
        compose.onNodeWithText("Neon Alley").assertIsDisplayed()
        compose.onNodeWithText("Drop at", substring = true).assertIsDisplayed()

        // C4 (U-3): erst hoeren, dann entscheiden — dieselbe ID in allen Pfaden.
        compose.onNodeWithText("Listen").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Confirm").performClick()

        assertEquals(listOf(7L), previewed)
        assertEquals(listOf(7L), discarded)
        assertEquals(listOf(7L), confirmed)
    }

    @Test
    fun `dropsync-karte nennt abdeckung und startet die playlist`() {
        val used = mutableListOf<Long>()
        compose.setContent {
            FlowRepTheme {
                DropSyncSection(
                    cards =
                        listOf(
                            DropSyncCard(
                                playlistId = 5L,
                                name = "Training",
                                label = PlaylistLabel.WORK,
                                coverage = DropCoverage(totalSongs = 12, songsWithDrop = 9, pendingReviews = 3),
                            ),
                        ),
                    onOpenPlaylist = {},
                    onUseDropSync = { used += it },
                    onDetectDrops = {},
                )
            }
        }

        compose.onNodeWithText("Work & rest").assertIsDisplayed()
        compose.onNodeWithText("Training").assertIsDisplayed()
        compose.onNodeWithText("9/12 drops").assertIsDisplayed()
        compose.onNodeWithText("3 markers still to review").assertIsDisplayed()

        compose.onNodeWithText("Use DropSync").performClick()
        assertEquals(listOf(5L), used)
    }

    @Test
    fun `karte ohne marker fuehrt in die automatische erkennung`() {
        val detected = mutableListOf<Long>()
        compose.setContent {
            FlowRepTheme {
                DropSyncSection(
                    cards =
                        listOf(
                            DropSyncCard(
                                playlistId = 6L,
                                name = "Pause",
                                label = PlaylistLabel.REST,
                                coverage = DropCoverage(totalSongs = 12, songsWithDrop = 0, pendingReviews = 0),
                            ),
                        ),
                    onOpenPlaylist = {},
                    onUseDropSync = {},
                    onDetectDrops = { detected += it },
                )
            }
        }

        compose.onNodeWithText("No drop markers yet").assertIsDisplayed()
        compose.onNodeWithText("Detect drops automatically").performClick()
        assertEquals(listOf(6L), detected)
    }

    private fun marker(
        id: Long,
        songId: Long,
        positionMs: Long,
    ): SongMarker =
        SongMarker(
            id = id,
            label = "Drop 1",
            positionMs = positionMs,
            source = MarkerSource.AUTO_DETECTED,
            isEnabled = false,
            linkedSongId = songId,
        )

    private fun song(
        id: Long,
        title: String,
    ): Song =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/$id",
            displayName = "$title.flac",
            relativePath = "Music",
            durationMs = 200_000,
            sizeBytes = 1_000,
            dateModifiedSeconds = 1,
            title = title,
            artist = "Nova Kane",
            album = "Album",
            isAvailable = true,
        )
}
