package com.dropsync.feature.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Beat-Snap fuer Marker (Recherche 2026: Marker landen gehoerig auf
 * Beats; Haptik mit Crescendo beim Annaehern). Snapt eine Position auf
 * den naechsten Beat des analysierten Track-BPM, wenn sie nah genug
 * liegt — nie gewaltsam, der Nutzer behaelt das letzte Wort.
 */
object MarkerSnapping {
    /** Maximaler Abstand in ms, innerhalb dessen auf den Beat gerastet wird. */
    const val SNAP_WINDOW_MS = 250L

    /**
     * Naechste Beat-Position zu [positionMs] bei [bpm], wenn sie innerhalb
     * von [SNAP_WINDOW_MS] liegt; sonst null (kein Snap). Beat-Grid startet
     * bei 0 — eine volle Offset-Kalibrierung waere genauer, ist aber ohne
     * Downbeat-Analyse Spekulation.
     */
    fun snapToBeat(
        positionMs: Long,
        bpm: Float?,
    ): Long? {
        if (bpm == null || bpm !in 30f..300f) return null
        val beatMs = 60_000f / bpm
        val beats = positionMs / beatMs
        val nearest = (beats.roundToInt()) * beatMs
        val nearestMs = nearest.toLong()
        return if (abs(nearestMs - positionMs) <= SNAP_WINDOW_MS) nearestMs else null
    }
}
