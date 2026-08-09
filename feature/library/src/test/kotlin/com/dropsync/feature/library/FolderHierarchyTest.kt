package com.dropsync.feature.library

import com.dropsync.domain.library.LibraryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aufbau des Ordnerbaums aus flachen relative_path-Zeilen (Plan-Testplan:
 * Ordnerbaum-Aufbau). Prueft Aggregation von Titelzahl/Dauer je Teilbaum,
 * Sortierung und Blatterkennung.
 */
class FolderHierarchyTest {
    private val folders =
        listOf(
            LibraryFolder(relativePath = "Music/Rock", trackCount = 2, totalDurationMs = 1_000),
            LibraryFolder(relativePath = "Music/Jazz", trackCount = 3, totalDurationMs = 2_000),
            LibraryFolder(relativePath = "Music/Rock/Live", trackCount = 1, totalDurationMs = 500),
            LibraryFolder(relativePath = "Podcasts", trackCount = 4, totalDurationMs = 4_000),
        )

    @Test
    fun rootLevelAggregatesWholeSubtreePerChild() {
        val root = FolderHierarchy.childrenOf(folders, basePath = "")
        assertEquals(listOf("Music", "Podcasts"), root.map { it.name })

        val music = root.first { it.name == "Music" }
        assertEquals("Music", music.path)
        assertEquals("", music.parent)
        assertEquals(2 + 3 + 1, music.trackCount)
        assertEquals(1_000 + 2_000 + 500, music.totalDurationMs)

        val podcasts = root.first { it.name == "Podcasts" }
        assertEquals(4, podcasts.trackCount)
    }

    @Test
    fun childLevelMergesDeeperLeavesIntoDirectChild() {
        val children = FolderHierarchy.childrenOf(folders, basePath = "Music")
        assertEquals(listOf("Jazz", "Rock"), children.map { it.name })

        val rock = children.first { it.name == "Rock" }
        // Rock (2) + Rock/Live (1) fliessen in den direkten Kindordner Rock.
        assertEquals(3, rock.trackCount)
        assertEquals(1_500, rock.totalDurationMs)
        assertEquals("Music/Rock", rock.path)
        assertEquals("Music", rock.parent)
    }

    @Test
    fun deeperLevelReturnsOnlyDirectChild() {
        val children = FolderHierarchy.childrenOf(folders, basePath = "Music/Rock")
        assertEquals(1, children.size)
        assertEquals("Live", children.single().name)
        assertEquals("Music/Rock/Live", children.single().path)
        assertEquals("Music/Rock", children.single().parent)
    }

    @Test
    fun isLeafOnlyForPathsWithOwnTracks() {
        assertTrue(FolderHierarchy.isLeaf(folders, "Music/Rock"))
        assertTrue(FolderHierarchy.isLeaf(folders, "Podcasts"))
        // "Music" ist reiner Zwischenordner -> kein Blatt.
        assertFalse(FolderHierarchy.isLeaf(folders, "Music"))
    }
}
