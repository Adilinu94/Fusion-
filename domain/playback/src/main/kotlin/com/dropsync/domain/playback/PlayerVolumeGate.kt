package com.dropsync.domain.playback

/**
 * Zugriff auf die Lautstaerke des EIGENEN Players (Bauplan 5.3):
 * Ducking veraendert nie die Systemlautstaerke, sondern nur den
 * App-Player. Implementierung in :data:playback; genutzt vom
 * Ducking-Koordinator in :data:timer (Schritt 8.4).
 */
interface PlayerVolumeGate {
    /** Aktuelle Playerlautstaerke 0.0..1.0. */
    suspend fun currentVolume(): Float

    suspend fun setVolume(volume: Float)
}
