package com.dropsync.data.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.dropsync.domain.audio.CrossfadeCurves
import com.dropsync.domain.audio.MixPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dual-Player-Crossfade (ADR-0007, Plan Phase 4).
 *
 * Der sessionfuehrende Hauptspieler bleibt Single Source of Truth; ein
 * zweiter, vorgepufferter ExoPlayer startet [crossfadeSeconds] vor dem
 * Titelende und beide laufen ueber die Kurven des aktiven [MixPreset]
 * aus (Mix-Uebergaenge-Plan Phase 2; Default FADE = Equal-Power-Rampen
 * aus [CrossfadeCurves]). Nach der Rampe uebernimmt der Hauptspieler den
 * naechsten Titel an der Position des Zweitspielers.
 *
 * Regeln (Plan Phase 4):
 * - kein Crossfade innerhalb zusammenhaengender Alben (Gapless) oder
 *   bei virtuellen CUE-Tracks ([shouldCrossfade]),
 * - nie bei Bit-Perfect (Phase 5 setzt die Dauer dann auf 0),
 * - Fallback bei Fehlern: harter Uebergang (ExoPlayer-Standard).
 *
 * Alle Player-Zugriffe laufen auf dem Application-Main-Thread; [scope]
 * muss deshalb an Dispatchers.Main gebunden sein.
 */
class CrossfadeController(
    private val mainPlayer: ExoPlayer,
    private val secondaryPlayerFactory: () -> ExoPlayer,
    private val scope: CoroutineScope,
) {
    private var crossfadeSeconds: Int = 0
    private var sessionPreset: MixPreset = MixPreset.FADE
    private var watchJob: Job? = null
    private var fadeJob: Job? = null
    private var secondaryPlayer: ExoPlayer? = null

    /** Dauer aus der DSP-Konfiguration; 0 deaktiviert den Crossfade. */
    fun setCrossfadeSeconds(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, CrossfadeCurves.MAX_SECONDS)
        if (crossfadeSeconds == 0) {
            cancelFade(restoreVolume = true)
        }
    }

    /**
     * Uebergangs-Preset aus der DSP-Konfiguration (Mix-Uebergaenge-Plan
     * Phase 2). Wirkt ab der naechsten Rampe; eine laufende Rampe wird
     * nicht unterbrochen.
     */
    fun setPreset(preset: MixPreset) {
        sessionPreset = preset
    }

    /** Startet die Titelende-Ueberwachung (einmal je Service-Leben). */
    fun start() {
        if (watchJob != null) return
        watchJob =
            scope.launch {
                while (isActive) {
                    maybeBeginFade()
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    /** Gibt Ueberwachung und Zweitspieler frei (Service.onDestroy). */
    fun release() {
        watchJob?.cancel()
        watchJob = null
        cancelFade(restoreVolume = false)
    }

    /**
     * Aktiver Wechsel auf [next], vorgespult auf [startPositionMs], per
     * Equal-Power-Crossfade (Drop-Landung, Musik-Workout-Plan Phase 4).
     * Anders als [maybeBeginFade] wartet dies nicht auf das Titelende und
     * startet den Zweitspieler an einer beliebigen Position. Ist der
     * Crossfade deaktiviert, laeuft gerade eine Rampe oder pausiert die
     * Wiedergabe, erfolgt ein harter Uebergang auf dem Hauptspieler.
     * Muss auf dem Main-Thread laufen (Player-Vertrag).
     */
    fun crossfadeTo(
        next: MediaItem,
        startPositionMs: Long,
    ) {
        cancelFade(restoreVolume = true)
        val startPos = startPositionMs.coerceAtLeast(0)
        val secondary =
            if (crossfadeSeconds <= 0 || !mainPlayer.isPlaying) {
                null
            } else {
                runCatching(secondaryPlayerFactory).getOrNull()
            }
        if (secondary == null) {
            // Fallback (Crossfade aus, pausiert oder kein Zweitspieler):
            // harter, aber vorgespulter Uebergang auf dem einen Player.
            mainPlayer.setMediaItem(next, startPos)
            mainPlayer.prepare()
            mainPlayer.play()
            return
        }
        secondaryPlayer = secondary
        secondary.volume = 0f
        secondary.setMediaItem(next, startPos)
        secondary.prepare()
        secondary.play()
        val fadeMs = crossfadeSeconds * 1000L
        val preset = sessionPreset
        fadeJob =
            scope.launch {
                try {
                    var elapsed = 0L
                    while (isActive && elapsed < fadeMs) {
                        // Nutzer hat pausiert: Rampe abbrechen, Uebergabe folgt.
                        if (!mainPlayer.isPlaying) break
                        val t = elapsed.toDouble() / fadeMs
                        mainPlayer.volume = smoothedGain(preset, t, fadeMs, fadeOut = true)
                        secondary.volume = smoothedGain(preset, t, fadeMs, fadeOut = false)
                        delay(STEP_MS)
                        elapsed += STEP_MS
                    }
                    // Uebergabe: Hauptspieler uebernimmt [next] an der bereits
                    // gespielten Position des Zweitspielers.
                    mainPlayer.setMediaItem(next, secondary.currentPosition)
                    mainPlayer.prepare()
                    mainPlayer.play()
                } finally {
                    mainPlayer.volume = 1f
                    secondary.release()
                    secondaryPlayer = null
                    fadeJob = null
                }
            }
    }

    private fun maybeBeginFade() {
        if (crossfadeSeconds <= 0 || fadeJob != null) return
        if (!mainPlayer.isPlaying) return
        val durationMs = mainPlayer.duration
        if (durationMs == C.TIME_UNSET) return
        val fadeMs = crossfadeSeconds * 1000L
        val remainingMs = durationMs - mainPlayer.currentPosition
        if (remainingMs <= 0 || remainingMs > fadeMs) return
        val nextIndex = mainPlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val current = mainPlayer.currentMediaItem ?: return
        val next = mainPlayer.getMediaItemAt(nextIndex)
        if (!shouldCrossfade(current, next)) return
        beginFade(next, nextIndex, remainingMs)
    }

    private fun beginFade(
        next: MediaItem,
        nextIndex: Int,
        fadeMs: Long,
    ) {
        // Fallback harter Uebergang, wenn kein Zweitspieler moeglich ist.
        val secondary =
            runCatching(secondaryPlayerFactory).getOrNull() ?: return
        secondaryPlayer = secondary
        secondary.volume = 0f
        secondary.setMediaItem(next)
        secondary.prepare()
        secondary.play()
        val preset = sessionPreset
        fadeJob =
            scope.launch {
                try {
                    var elapsed = 0L
                    while (isActive && elapsed < fadeMs) {
                        // Nutzer hat pausiert oder gesprungen: Rampe abbrechen.
                        if (!mainPlayer.isPlaying) return@launch
                        val t = elapsed.toDouble() / fadeMs
                        mainPlayer.volume = smoothedGain(preset, t, fadeMs, fadeOut = true)
                        secondary.volume = smoothedGain(preset, t, fadeMs, fadeOut = false)
                        delay(STEP_MS)
                        elapsed += STEP_MS
                    }
                    if (isActive) {
                        // Uebergabe: Hauptspieler springt auf den naechsten
                        // Titel an die bereits gespielte Position.
                        mainPlayer.seekTo(nextIndex, secondary.currentPosition)
                    }
                } finally {
                    mainPlayer.volume = 1f
                    secondary.release()
                    secondaryPlayer = null
                    fadeJob = null
                }
            }
    }

    private fun cancelFade(restoreVolume: Boolean) {
        fadeJob?.cancel()
        fadeJob = null
        secondaryPlayer?.release()
        secondaryPlayer = null
        if (restoreVolume) {
            mainPlayer.volume = 1f
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val STEP_MS = 50L

        /**
         * Klickschutz (Mix-Uebergaenge-Plan Phase 2): Der Gain wird ueber
         * das jeweils naechste [STEP_MS]-Fenster gemittelt. Fuer stetige
         * Kurven aendert das praktisch nichts; der harte 0<->1-Sprung von
         * [MixPreset.SLAM] wird so zu einer kurzen Mikro-Rampe statt eines
         * Knacksers in einem einzelnen Schritt.
         */
        internal fun smoothedGain(
            preset: MixPreset,
            t: Double,
            fadeMs: Long,
            fadeOut: Boolean,
        ): Float {
            val tNext = (t + STEP_MS.toDouble() / fadeMs).coerceAtMost(1.0)
            val now = if (fadeOut) preset.fadeOutGain(t) else preset.fadeInGain(t)
            val next = if (fadeOut) preset.fadeOutGain(tNext) else preset.fadeInGain(tNext)
            return ((now + next) / 2.0).toFloat()
        }

        /**
         * Gapless-Regel (Plan Phase 4): kein Crossfade bei virtuellen
         * CUE-Tracks und nicht innerhalb desselben Albums (dort ist ein
         * lueckenloser Uebergang gewollt, z. B. Live-Alben).
         */
        fun shouldCrossfade(
            current: MediaItem,
            next: MediaItem,
        ): Boolean {
            if (current.mediaId.startsWith(MediaItemFactory.CUE_MEDIA_ID_PREFIX)) return false
            if (next.mediaId.startsWith(MediaItemFactory.CUE_MEDIA_ID_PREFIX)) return false
            val currentAlbum = current.mediaMetadata.albumTitle
            val nextAlbum = next.mediaMetadata.albumTitle
            if (currentAlbum == null || nextAlbum == null) return true
            return currentAlbum.toString() != nextAlbum.toString()
        }
    }
}
