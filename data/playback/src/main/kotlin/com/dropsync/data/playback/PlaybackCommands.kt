package com.dropsync.data.playback

/**
 * Eigene MediaSession-Kommandos jenseits der Standard-Player-Befehle
 * (Musik-Workout-Plan Phase 4). Der echte Crossfade auf einen beliebig
 * vorgespulten Titel kann nur dienstseitig laufen (nur der Service haelt
 * den Zweitspieler); der Controller stoesst ihn deshalb ueber ein
 * Custom-Kommando an. Die Konstanten teilen sich [PlaybackRepositoryImpl]
 * (Sender) und [PlaybackService] (Empfaenger).
 */
object PlaybackCommands {
    /** Drop-Landung: dienstseitig per Crossfade auf einen Work-Titel wechseln. */
    const val ACTION_CROSSFADE_TO: String = "com.dropsync.playback.CROSSFADE_TO"

    /** MediaStore-ID des Zieltitels (Long). */
    const val ARG_SONG_ID: String = "song_id"

    /** Startposition/Vorspulen des Zieltitels in Millisekunden (Long). */
    const val ARG_START_POSITION_MS: String = "start_position_ms"
}
