package com.dropsync.data.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Einzige Verbindung der App zum PlaybackService (Bauplan 3.3):
 * Alle Screens teilen sich denselben MediaController; kein Feature
 * erzeugt je einen eigenen Player (Schritt 5.3).
 */
interface PlayerConnection {
    /** Liefert den verbundenen Controller; verbindet bei Bedarf neu. */
    suspend fun requirePlayer(): Player
}

class MediaControllerConnection(
    private val context: Context,
) : PlayerConnection {
    private val mutex = Mutex()
    private var controller: MediaController? = null

    override suspend fun requirePlayer(): Player =
        mutex.withLock {
            val existing = controller
            if (existing != null && existing.isConnected) {
                existing
            } else {
                existing?.release()
                val token =
                    SessionToken(
                        context,
                        ComponentName(context, PlaybackService::class.java),
                    )
                MediaController
                    .Builder(context, token)
                    .buildAsync()
                    .await()
                    .also { controller = it }
            }
        }
}
