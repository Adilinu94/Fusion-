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

    /**
     * Scrubbing-Modus des ExoPlayer (Media3 1.8+): waehrend eines
     * Waveform-Drags optimiert der Player auf viele schnelle Seeks
     * (kein Audio-Ausgabe-Reset je Sprung). Nur der Service haelt den
     * ExoPlayer, deshalb laeuft der Schalter als Custom-Kommando.
     */
    const val ACTION_SET_SCRUBBING_MODE: String = "com.dropsync.playback.SET_SCRUBBING_MODE"

    /**
     * Armierte Drop-Landung (MP-3): der Service terminiert den Wechsel
     * per `PlayerMessage` an der Wiedergabeposition und blendet mit
     * [ARG_FADE_MS] um (Stufe 1, ADR-0022).
     */
    const val ACTION_ARM_LANDING: String = "com.dropsync.playback.ARM_LANDING"

    /** Bricht eine armierte Landung ab. */
    const val ACTION_CANCEL_LANDING: String = "com.dropsync.playback.CANCEL_LANDING"

    /** MediaStore-ID des Zieltitels (Long). */
    const val ARG_SONG_ID: String = "song_id"

    /** Startposition/Vorspulen des Zieltitels in Millisekunden (Long). */
    const val ARG_START_POSITION_MS: String = "start_position_ms"

    /** Verzoegerung bis zur Landung ab der aktuellen Position (Long, ms). */
    const val ARG_DELAY_MS: String = "delay_ms"

    /** Ueberblenddauer der Landung (Long, ms); 0 = nur Mikro-Rampe. */
    const val ARG_FADE_MS: String = "fade_ms"

    /** Scrubbing an/aus (Boolean). */
    const val ARG_SCRUBBING_ENABLED: String = "scrubbing_enabled"
}
