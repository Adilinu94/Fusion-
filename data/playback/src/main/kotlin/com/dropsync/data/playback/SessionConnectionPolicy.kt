package com.dropsync.data.playback

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/**
 * Verbindungs-Policy der MediaSession (Ausbauplan A2).
 *
 * Der Service ist `exported` (System, Auto, BT brauchen ihn) — die Haertung
 * liegt daher in `onConnect`, nicht im Manifest:
 *
 * - Eigene App: alles + Drop-eigene Custom-Kommandos (wie bisher).
 * - System-UIDs (< 10000: System-UI, Assistant): volle Standard-Kommandos
 *   (Auto/BT/Resume funktionieren), aber keine Custom-Kommandos.
 * - Dritte (Automatisierung, Scrobbler, ...): Transport + Browse. Queue-,
 *   Tempo-, Track- und Effekt-Eingriffe sind entzogen — ein fremder Controller
 *   soll die Wiedergabe steuern, aber nicht die Pipeline umbauen (BPM-Lock,
 *   DSP-Kette und Queue gehoeren der App).
 *
 * Reine Entscheidungslogik ohne Android-Abhaengigkeit (uid/Package als Werte),
 * damit sie auf der JVM testbar ist. Media3 1.11 verschaerft zusaetzlich den
 * Default fuer Apps ohne `onConnect`-Override; wir haben ein Override und
 * bilden die Staffelung deshalb explizit ab.
 *
 * `@SuppressLint` statt `@OptIn`: Objekt-Scope traegt `@OptIn(UnstableApi)`
 * in dieser Lint-Version nicht (UnsafeOptInUsageError auf jeder Zeile,
 * einschliesslich der Annotation selbst); Klassen-Scope wie in
 * `PlaybackService` waere die Alternative. Bewusst eng auf diese Datei.
 */
@SuppressLint("UnsafeOptInUsageError")
object SessionConnectionPolicy {
    /**
     * Wie `android.os.Process.FIRST_APPLICATION_UID` (10_000), als eigene
     * Konstante, damit die Policy ohne Android-Stub auf der JVM testbar bleibt.
     */
    const val FIRST_APPLICATION_UID: Int = 10_000

    fun isOwnPackage(
        controllerPackage: String,
        ownPackage: String,
    ): Boolean = controllerPackage == ownPackage

    fun isSystemUid(uid: Int): Boolean = uid < FIRST_APPLICATION_UID

    /** Session-Kommandos: Custom-Kommandos nur fuer die eigene App. */
    fun sessionCommands(isOwn: Boolean): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (isOwn) {
            builder
                .add(SessionCommand(PlaybackCommands.ACTION_PLAY_SONG_AT, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.ACTION_SET_SCRUBBING_MODE, Bundle.EMPTY))
        }
        return builder.build()
    }

    /**
     * Player-Kommandos: eigene App und System voll; Dritte ohne
     * Queue-/Tempo-/Track-/Effekt-Eingriffe.
     */
    fun playerCommands(
        isOwn: Boolean,
        isSystem: Boolean,
    ): Player.Commands {
        if (isOwn || isSystem) {
            return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        }
        return MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SET_PLAYLIST_METADATA)
            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
            .remove(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            .build()
    }
}
