package com.dropsync.data.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.dropsync.domain.playback.AudioClock
import com.dropsync.domain.playback.AudioClockSnapshot
import com.dropsync.domain.playback.AudioClockSnapshotProvider
import com.dropsync.domain.playback.RouteProfileRepository
import com.dropsync.domain.playback.toConfidence
import kotlinx.coroutines.flow.first
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
 *
 * Zusaetzlich liefert [snapshot] den Clock-Kontext fuer Transition-States
 * (Offtrack Phase 8): Position, Spielzustand, Ladezustand, Route-ID,
 * Latenzschaetzung und Confidence.
 */
@Singleton
class Media3AudioClock
    @Inject
    constructor(
        private val routeProfiles: RouteProfileRepository,
    ) : AudioClock,
        AudioClockSnapshotProvider {
        @Volatile
        private var player: Player? = null

        private val hasPlayer = AtomicBoolean(false)
        private val lastPositionMs = AtomicLong(0L)
        private val lastUpdateElapsedMs = AtomicLong(0L)
        private val playing = AtomicBoolean(false)
        private val recentUnderrunAtElapsedMs = AtomicLong(0L)

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

        override suspend fun snapshot(): AudioClockSnapshot? {
            val bound = player ?: return null
            val profile = routeProfiles.currentProfile.first()
            return AudioClockSnapshot(
                capturedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                playerPositionMs = bound.currentPosition.coerceAtLeast(0L),
                isPlaying = bound.isPlaying,
                isLoading = bound.isLoading,
                outputLatencyMs = profile?.estimatedLatencyMs,
                routeId = profile?.routeKey,
                confidence = mode.toConfidence(),
                hadRecentUnderrun = hadRecentUnderrun(),
            )
        }

        private fun hadRecentUnderrun(): Boolean {
            val at = recentUnderrunAtElapsedMs.get()
            if (at == 0L) return false
            return SystemClock.elapsedRealtime() - at < RECENT_UNDERRUN_WINDOW_MS
        }

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

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Konservativ: ein Player-Fehler gilt als Underrun-Hinweis
                    // fuer den aktuellen Transition-Snapshot.
                    recentUnderrunAtElapsedMs.set(SystemClock.elapsedRealtime())
                }
            }

        private companion object {
            const val RECENT_UNDERRUN_WINDOW_MS = 2_000L
        }
    }
