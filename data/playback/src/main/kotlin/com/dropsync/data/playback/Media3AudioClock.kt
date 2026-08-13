package com.dropsync.data.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.dropsync.domain.playback.AudioClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MVP-AudioClock (Design Phase 6): Media3 verwaltet den AudioTrack intern,
 * daher ist ein echter `AudioTrack.getTimestamp()` hier nicht erreichbar.
 * Die Uhr interpoliert stattdessen die Player-Position zwischen den
 * Media3-Updates ueber die monotone Systemzeit:
 *
 *   position = lastPositionMs + (nowElapsed - lastUpdateElapsed)
 *
 * - [AudioClock.Mode.BEST_EFFORT]: Normalfall (Player gebunden, spielt).
 * - [AudioClock.Mode.UNAVAILABLE]: kein Player gebunden.
 *
 * [AudioClock.Mode.EXACT] wird spaeter aktiv, wenn der AudioTrack-Zugang
 * verfuegbar ist (Testinfra 5c: AudioTrackTimestampReader + Extrapolator);
 * der MVP rechnet bewusst ohne Latenzversprechen (ADR-0012, BEST_EFFORT).
 */
@Singleton
class Media3AudioClock
    @Inject
    constructor() : AudioClock {
        @Volatile
        private var player: Player? = null

        private val hasPlayer = AtomicBoolean(false)
        private val lastPositionMs = AtomicLong(0L)
        private val lastUpdateElapsedMs = AtomicLong(0L)
        private val playing = AtomicBoolean(false)

        /** Bindet den Dienst-Player (PlaybackService.onCreate). */
        fun attach(player: Player) {
            this.player = player
            hasPlayer.set(true)
            playing.set(player.isPlaying)
            lastPositionMs.set(player.currentPosition)
            lastUpdateElapsedMs.set(SystemClock.elapsedRealtime())
            player.addListener(positionListener)
        }

        /** Loest die Bindung (PlaybackService.onDestroy). */
        fun detach() {
            player?.removeListener(positionListener)
            player = null
            hasPlayer.set(false)
            playing.set(false)
        }

        override val mode: AudioClock.Mode
            get() = if (hasPlayer.get()) AudioClock.Mode.BEST_EFFORT else AudioClock.Mode.UNAVAILABLE

        override fun audiblePositionMs(): Long {
            if (!hasPlayer.get()) return 0L
            val base = lastPositionMs.get()
            val now = SystemClock.elapsedRealtime()
            val lastUpdate = lastUpdateElapsedMs.get()
            if (!playing.get() || now <= lastUpdate) return base
            return base + (now - lastUpdate)
        }

        override fun playheadPositionMs(): Long = player?.currentPosition ?: 0L

        private val positionListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    lastPositionMs.set(player?.currentPosition ?: 0L)
                    lastUpdateElapsedMs.set(SystemClock.elapsedRealtime())
                    playing.set(playbackState == Player.STATE_READY && (player?.isPlaying == true))
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing.set(isPlaying)
                    lastPositionMs.set(player?.currentPosition ?: 0L)
                    lastUpdateElapsedMs.set(SystemClock.elapsedRealtime())
                }
            }
    }
