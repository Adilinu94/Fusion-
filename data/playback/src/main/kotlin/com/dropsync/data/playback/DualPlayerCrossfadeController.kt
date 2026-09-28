package com.dropsync.data.playback

import androidx.media3.common.Player
import com.dropsync.domain.audio.CrossfadeCurves
import com.dropsync.domain.audio.MixPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lautstaerke-Port fuer einen physischen Ausgang des Crossfade-PoC. */
internal interface CrossfadeVolumeTarget {
    var volume: Float
    val isPlaying: Boolean

    fun play()

    fun pause()

    fun stop()
}

/** Media3-Adapter. Der Crossfade-PoC besitzt den Player nicht. */
internal class Media3CrossfadeVolumeTarget(
    private val player: Player,
) : CrossfadeVolumeTarget {
    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    override val isPlaying: Boolean
        get() = player.isPlaying

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }
}

/**
 * Isolierter Dual-Player-Rampen-PoC. Er macht **keine** Queue-, Session-,
 * Audio-Focus- oder DSP-Verwaltung. Der Aufrufer muss den ankommenden Player
 * mit eigener Media3-Konfiguration erzeugen und diese Grenzen auf einem
 * Android-Geraet pruefen, bevor der Controller in die Produktionsqueue
 * eingebunden wird.
 *
 * Cancellation stellt die vorherigen Lautstaerken wieder her und pausiert
 * einen ankommenden Player, den der Aufrufer vor dem Crossfade nicht laufen
 * hatte. Ein erfolgreicher Abschluss stoppt den ausgehenden Player.
 */
internal class DualPlayerCrossfadeController(
    private val scope: CoroutineScope,
    private val stepMs: Long = DEFAULT_STEP_MS,
) {
    private val mutex = Mutex()
    private var rampJob: Job? = null

    /**
     * Startet die Lautstaerkerampen. `durationSeconds == 0` ist ein No-op;
     * gueltige Dauer wird auf den konfigurierten Bereich 0..12 Sekunden
     * begrenzt. Das ankommende Medium sollte vorab vorbereitet sein.
     */
    suspend fun start(
        outgoing: CrossfadeVolumeTarget,
        incoming: CrossfadeVolumeTarget,
        durationSeconds: Int,
        preset: MixPreset,
        onComplete: () -> Unit = {},
    ): Job? =
        mutex.withLock {
            cancelActiveLocked()
            val durationMs =
                durationSeconds
                    .coerceIn(0, CrossfadeCurves.MAX_SECONDS)
                    .toLong() * 1_000L
            if (durationMs == 0L) return@withLock null

            val outgoingStartVolume = outgoing.volume.coerceIn(0f, 1f)
            val incomingStartVolume = incoming.volume.coerceIn(0f, 1f)
            val incomingWasPlaying = incoming.isPlaying
            incoming.volume = 0f
            incoming.play()

            scope
                .launch {
                    var completed = false
                    try {
                        var elapsedMs = 0L
                        while (elapsedMs < durationMs) {
                            val waitMs = minOf(stepMs.coerceAtLeast(1L), durationMs - elapsedMs)
                            delay(waitMs)
                            elapsedMs += waitMs
                            val progress = elapsedMs.toDouble() / durationMs.toDouble()
                            outgoing.volume =
                                outgoingStartVolume * preset.fadeOutGain(progress).toFloat()
                            incoming.volume =
                                incomingStartVolume * preset.fadeInGain(progress).toFloat()
                        }
                        outgoing.volume = 0f
                        incoming.volume = incomingStartVolume
                        outgoing.stop()
                        completed = true
                        onComplete()
                    } finally {
                        if (!completed) {
                            outgoing.volume = outgoingStartVolume
                            incoming.volume = incomingStartVolume
                            if (!incomingWasPlaying) incoming.pause()
                        }
                    }
                }.also { rampJob = it }
        }

    /** Beendet eine laufende Rampe und stellt den Ausgangszustand wieder her. */
    suspend fun cancel() {
        mutex.withLock { cancelActiveLocked() }
    }

    private suspend fun cancelActiveLocked() {
        val job = rampJob ?: return
        rampJob = null
        job.cancelAndJoin()
    }

    private companion object {
        const val DEFAULT_STEP_MS = 20L
    }
}
