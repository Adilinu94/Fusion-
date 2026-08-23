package com.dropsync.data.playback

/**
 * Eigene MediaSession-Kommandos jenseits der Standard-Player-Befehle.
 * Die Drop-Landung wechselt dienstseitig auf den einen sessionfuehrenden
 * Player; der Controller stoesst das ueber ein Custom-Kommando an. Die
 * Konstanten teilen sich [PlaybackRepositoryImpl] (Sender) und
 * [PlaybackService] (Empfaenger).
 */
object PlaybackCommands {
    /** Drop-Landung: auf einen Work-Titel wechseln, vorgespult. */
    const val ACTION_PLAY_SONG_AT: String = "com.dropsync.playback.PLAY_SONG_AT"

    /** MediaStore-ID des Zieltitels (Long). */
    const val ARG_SONG_ID: String = "song_id"

    /** Startposition/Vorspulen des Zieltitels in Millisekunden (Long). */
    const val ARG_START_POSITION_MS: String = "start_position_ms"
}
