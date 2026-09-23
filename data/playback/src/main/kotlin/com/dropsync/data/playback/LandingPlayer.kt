package com.dropsync.data.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.dropsync.core.model.Song

/**
 * Schmaler Player-Port der armierten Landung (Audit 2026-09-22): Der
 * [DropLandingArmer] braucht vom ExoPlayer nur Position, Medienindex,
 * Lautstaerke, Abspielzustand, eine positionsgebundene Nachricht und den
 * Titelwechsel. Der Port macht Armier-/Watchdog-/Fade-Logik ohne
 * Media3-Stack pruefbar (Fake statt Mocking-Framework).
 */
interface LandingPlayer {
    val currentPositionMs: Long

    val currentMediaItemIndex: Int

    val isPlaying: Boolean

    var volume: Float

    /**
     * Terminiert [onFire] an der Wiedergabeposition [positionMs] von
     * [mediaItemIndex] (Media3-`PlayerMessage`). Wirft, wenn die Nachricht
     * nicht gesendet werden kann (der Aufrufer meldet dann PLAYER_ERROR).
     */
    fun sendMessageAt(
        mediaItemIndex: Int,
        positionMs: Long,
        onFire: () -> Unit,
    ): LandingMessage

    /** Wechselt auf [song] an [startPositionMs] und startet die Wiedergabe. */
    fun startSong(
        song: Song,
        startPositionMs: Long,
    )
}

/** Abbruch-Handle einer terminierten Player-Nachricht. */
fun interface LandingMessage {
    fun cancel()
}

/** Produktions-Adapter: Media3-`PlayerMessage` auf dem Main-Looper. */
@OptIn(UnstableApi::class)
class ExoLandingPlayer(
    private val player: ExoPlayer,
) : LandingPlayer {
    override val currentPositionMs: Long get() = player.currentPosition

    override val currentMediaItemIndex: Int get() = player.currentMediaItemIndex

    override val isPlaying: Boolean get() = player.isPlaying

    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    override fun sendMessageAt(
        mediaItemIndex: Int,
        positionMs: Long,
        onFire: () -> Unit,
    ): LandingMessage {
        val message =
            player
                .createMessage { _, _ -> onFire() }
                .setPosition(mediaItemIndex, positionMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(true)
        message.send()
        return LandingMessage { message.cancel() }
    }

    override fun startSong(
        song: Song,
        startPositionMs: Long,
    ) {
        player.setMediaItem(MediaItemFactory.fromSong(song), startPositionMs)
        player.prepare()
        player.play()
    }
}
