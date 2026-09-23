package com.dropsync.feature.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Beat-Snap fuer Marker (Recherche 2026: Marker landen gehoerig auf
 * Beats; Haptik mit Crescendo beim Annaehern). Snapt eine Position auf
 * den naechsten Beat des analysierten Track-BPM, wenn sie nah genug
 * liegt — nie gewaltsam, der Nutzer behaelt das letzte Wort.
 *
 * B4/RC-22: Das Raster hat eine gemessene Phase
 * ([com.dropsync.domain.audio.TrackAnalysis.downbeatOffsetMs], im Kern
 * die Beat-Phase, kein Taktanfang). **Ohne bekannten Offset rastet
 * nichts**: ein Raster mit geratener Phase kann die Position um bis zu
 * einen halben Beat verschieben (234 ms bei 128 BPM) und damit mehr
 * Fehler einbringen als die gesamte Audio-Latenzkette. Kein Snap ist
 * besser als ein falsches Snap.
 */
object MarkerSnapping {
    /** Maximaler Abstand in ms, innerhalb dessen auf den Beat gerastet wird. */
    const val SNAP_WINDOW_MS = 250L

    /**
     * Naechste Beat-Position zu [positionMs] bei [bpm] auf dem Raster mit
     * der Phase [downbeatOffsetMs], wenn sie innerhalb des Fensters
     * liegt; sonst null (kein Snap).
     *
     * Das Fenster ist auf **einen halben Beat** geklemmt: ab 120 BPM ist
     * ein halber Beat <= 250 ms, sonst wuerde jede Position zwangsweise
     * rasten. [downbeatOffsetMs] null oder negativ heisst "kein Raster
     * bekannt" — dann gibt es keinen Snap.
     */
    fun snapToBeat(
        positionMs: Long,
        bpm: Float?,
        downbeatOffsetMs: Long?,
    ): Long? {
        if (bpm == null || bpm !in 30f..300f) return null
        val offset = downbeatOffsetMs?.coerceAtLeast(0L) ?: return null
        val beatMs = 60_000f / bpm
        val beats = (positionMs - offset) / beatMs
        val nearest = (beats.roundToInt()) * beatMs + offset
        // Rasterpunkt vor 0 ms gibt es nicht (Trackanfang).
        val nearestMs = nearest.toLong().coerceAtLeast(0L)
        val window = minOf(SNAP_WINDOW_MS, (beatMs / 2f).toLong())
        return if (abs(nearestMs - positionMs) <= window) nearestMs else null
    }
}
