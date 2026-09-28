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
    /**
     * Maximaler Abstand in ms, innerhalb dessen auf den Beat gerastet wird.
     *
     * 2026-09-27, Befund 10.3: hier stand 250, geklemmt auf `beatMs / 2`.
     * Bei 128 BPM sind das 234 ms — und damit rastet **jede** Position im
     * Umkreis von einem Viertel Beat. Das ist keine Bemaessigung, das ist
     * erzwungene Rasterung, und sie widerspricht dem eigenen KDoc ("nie
     * gewaltsam, der Nutzer behaelt das letzte Wort").
     *
     * 150 ms und `beatMs / 4` begrenzen das auf ein echtes Fenster: bei
     * 128 BPM (468 ms Beat) sind das 150 ms bzw. 117 ms — der Marker muss
     * ehrlich nahe am Beat liegen, um zu rasten. Bei sehr schnellen
     * Tempi (ab 160 BPM, Beat 375 ms) greift `beatMs / 4` = 94 ms, und
     * selbst ein perfekt gesetzter Marker muss innerhalb von 94 ms
     * liegen, um zu rasten — das ist die physikalische Grenze der
     * Aufloesung, keine willkuerliche.
     */
    const val SNAP_WINDOW_MS = 150L

    /**
     * Naechste Beat-Position zu [positionMs] bei [bpm] auf dem Raster mit
     * der Phase [downbeatOffsetMs], wenn sie innerhalb des Fensters
     * liegt; sonst null (kein Snap).
     *
     * Das Fenster ist auf ein Drittel des halben Beats geklemmt. Ohne
     * Begrenzung rastet bei jedem Tempo >= 120 BPM jede Position
     * zwangslaeufig — der Nutzer koennte keinen Marker frei setzen.
     */
    fun snapToBeat(
        positionMs: Long,
        bpm: Float?,
        downbeatOffsetMs: Long?,
    ): Long? {
        if (bpm == null || bpm !in 30f..300f) return null
        val offset = downbeatOffsetMs?.coerceAtLeast(0L) ?: return null
        val beatMs = 60_000f / bpm
        // Vor dem Raster-Offset gibt es keinen Beat: `roundToInt()` rundet
        // dort "away from zero" und wuerde z. B. eine Position bei 5 ms auf
        // den Beat bei 140 ms ziehen. Das ist ein Snap auf eine Stelle, an
        // der nie etwas war.
        if (positionMs < offset) return null
        val beats = (positionMs - offset) / beatMs
        val nearest = (beats.roundToInt()) * beatMs + offset
        val nearestMs = nearest.toLong().coerceAtLeast(0L)
        val window = minOf(SNAP_WINDOW_MS, (beatMs / 4f).toLong())
        return if (abs(nearestMs - positionMs) <= window) nearestMs else null
    }
}
