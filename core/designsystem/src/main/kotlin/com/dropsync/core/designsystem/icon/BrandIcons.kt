package com.dropsync.core.designsystem.icon

import androidx.annotation.DrawableRes
import com.dropsync.core.designsystem.R

/**
 * Zentraler Zugriff auf die FlowRep-Marken-Icons (Outline, 2px, 24dp-Grid,
 * `res/drawable`). Die Farbe setzt die aufrufende Stelle per `Icon(tint=...)`:
 * Standard ist die Content-Farbe der Umgebung, Lime (primary) nur fuer den
 * aktiven Zustand bzw. die Primaeraktion (Design.txt).
 */
object BrandIcons {
    // Navigation (App-Shell, 4 Tabs: Train, Music, Verlauf, Einstellungen)
    @DrawableRes val NavTrain = R.drawable.ic_nav_training

    @DrawableRes val NavMusic = R.drawable.ic_nav_music

    @DrawableRes val NavHistory = R.drawable.ic_nav_history

    @DrawableRes val NavSettings = R.drawable.ic_nav_settings

    // Transport / Player
    @DrawableRes val Play = R.drawable.ic_play

    @DrawableRes val Pause = R.drawable.ic_pause

    @DrawableRes val SkipNext = R.drawable.ic_skip_next

    @DrawableRes val SkipPrevious = R.drawable.ic_skip_previous

    @DrawableRes val Queue = R.drawable.ic_queue

    @DrawableRes val PlayNext = R.drawable.ic_play_next

    @DrawableRes val Forward15 = R.drawable.ic_forward_15

    @DrawableRes val Replay15 = R.drawable.ic_replay_15

    @DrawableRes val Waveform = R.drawable.ic_waveform

    @DrawableRes val Marker = R.drawable.ic_marker

    @DrawableRes val MarkerImport = R.drawable.ic_marker_import

    @DrawableRes val RestDropSync = R.drawable.ic_rest_dropsync

    // Bibliothek
    @DrawableRes val Search = R.drawable.ic_search

    @DrawableRes val Close = R.drawable.ic_close

    @DrawableRes val Filter = R.drawable.ic_filter

    @DrawableRes val Back = R.drawable.ic_back

    @DrawableRes val More = R.drawable.ic_more

    @DrawableRes val Add = R.drawable.ic_add

    @DrawableRes val PlaylistAdd = R.drawable.ic_playlist_add

    @DrawableRes val Delete = R.drawable.ic_delete

    @DrawableRes val Edit = R.drawable.ic_edit

    @DrawableRes val FavoriteFilled = R.drawable.ic_favorite_filled

    @DrawableRes val FavoriteOutline = R.drawable.ic_favorite_outline

    @DrawableRes val Artists = R.drawable.ic_artists

    @DrawableRes val Albums = R.drawable.ic_albums

    @DrawableRes val Genres = R.drawable.ic_genres

    @DrawableRes val Folder = R.drawable.ic_folder

    @DrawableRes val Playlists = R.drawable.ic_playlists

    @DrawableRes val ExpandCollapse = R.drawable.ic_expand_collapse

    // Audio / DSP
    @DrawableRes val AudioDsp = R.drawable.ic_audio_dsp

    @DrawableRes val Equalizer = R.drawable.ic_equalizer

    @DrawableRes val BassTreble = R.drawable.ic_bass_treble

    @DrawableRes val StereoWidth = R.drawable.ic_stereo_width

    @DrawableRes val Reverb = R.drawable.ic_reverb

    @DrawableRes val Resampler = R.drawable.ic_resampler

    @DrawableRes val Dvc = R.drawable.ic_dvc

    @DrawableRes val BitPerfect = R.drawable.ic_bit_perfect

    @DrawableRes val OutputDevice = R.drawable.ic_output_device

    @DrawableRes val Bluetooth = R.drawable.ic_bluetooth

    @DrawableRes val Swap = R.drawable.ic_swap

    // Training
    @DrawableRes val Session = R.drawable.ic_session

    @DrawableRes val ExerciseLibrary = R.drawable.ic_exercise_library

    @DrawableRes val Routines = R.drawable.ic_routines

    @DrawableRes val Progress = R.drawable.ic_progress

    @DrawableRes val RepeatSession = R.drawable.ic_repeat_session

    @DrawableRes val SetComplete = R.drawable.ic_set_complete

    @DrawableRes val PrBadge = R.drawable.ic_pr_badge

    @DrawableRes val PlateauWarning = R.drawable.ic_plateau_warning

    @DrawableRes val WeightKg = R.drawable.ic_weight_kg

    @DrawableRes val MuscleArms = R.drawable.ic_muscle_arms

    @DrawableRes val MuscleBack = R.drawable.ic_muscle_back

    @DrawableRes val MuscleChest = R.drawable.ic_muscle_chest

    @DrawableRes val MuscleLegs = R.drawable.ic_muscle_legs

    @DrawableRes val MuscleShoulders = R.drawable.ic_muscle_shoulders

    // Allgemein
    @DrawableRes val Info = R.drawable.ic_info

    @DrawableRes val Bell = R.drawable.ic_bell

    @DrawableRes val Undo = R.drawable.ic_undo

    @DrawableRes val TimerReset = R.drawable.ic_timer_reset
}
