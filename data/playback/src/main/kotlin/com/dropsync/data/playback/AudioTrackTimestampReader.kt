package com.dropsync.data.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import com.dropsync.domain.playback.AudioTimestampReader

/**
 * Echte [AudioTimestampReader]-Implementierung ueber `AudioTrack.getTimestamp()`
 * (Testinfrastruktur-Umbauplan Schritt 2, 5c).
 *
 * Warm-up-Gate: `getTimestamp()` liefert in der Warm-up-Phase (erste
 * Sekunden nach `play()`) false - solange bleibt [isTimestampValid] false
 * und der Extrapolator faellt auf [playbackHeadPosition] zurueck.
 *
 * Bewusst nicht in der DI verdrahtet: Die App besitzt keinen eigenen
 * AudioTrack (Media3 verwaltet seinen Sink intern). Der Reader wird erst
 * gebraucht, wenn die Latenz-Kalibrierung pro Route (Design Phase 6)
 * gebaut wird; bis dahin prueft der JVM-Test die Extrapolation gegen
 * synthetische Paare.
 */
class AudioTrackTimestampReader(
    private val audioTrack: AudioTrack,
) : AudioTimestampReader {
    private val androidBuffer = AudioTimestamp()
    private var lastValid: com.dropsync.domain.playback.AudioTimestamp? = null

    override fun isTimestampValid(): Boolean {
        refresh()
        return lastValid != null
    }

    override fun readTimestamp(): com.dropsync.domain.playback.AudioTimestamp? {
        refresh()
        return lastValid
    }

    override fun playbackHeadPosition(): Long = audioTrack.playbackHeadPosition.toLong()

    private fun refresh() {
        if (audioTrack.getTimestamp(androidBuffer)) {
            lastValid =
                com.dropsync.domain.playback.AudioTimestamp(
                    systemTimeNs = androidBuffer.nanoTime,
                    framePosition = androidBuffer.framePosition,
                )
        }
    }
}
