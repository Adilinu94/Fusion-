package com.dropsync.data.playback

import com.dropsync.core.common.Clock
import com.dropsync.core.model.Song
import com.dropsync.domain.playback.DropLandingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armierte Drop-Landung (MP-3, Tiefenrecherche D3): Der Wechsel auf den
 * Work-Titel wird als Media3-`PlayerMessage` **an einer Wiedergabeposition**
 * terminiert und damit auf dem Playback-Thread ausgeloest — nicht durch ein
 * `delay()` im App-Prozess, das unter Last, Doze oder CPU-Drossel streut.
 *
 * Stufe 1 der Crossfade-Entscheidung (ADR-0022): der Wechsel wird mit den
 * vorhandenen Equal-Power-Kurven als Aus-/Einblendung um den harten Schnitt
 * gelegt (Mikro-Rampe gegen Knacksen inklusive). Es ueberlappen **keine**
 * zwei Player.
 *
 * Der Arm-Vorgang ist prozesslokal: [PlaybackService] haelt den Player und
 * speist [attach] ueber den schmalen Port [LandingPlayer]; die App-Schicht
 * sieht nur den Port [com.dropsync.domain.playback.PlaybackRepository.armLanding].
 * Ergebnisse (Landed/Missed) laufen als [events] zurueck und werden ueber die
 * Repository-Schnittstelle sichtbar.
 *
 * - Ein Watchdog uebernimmt, wenn die Nachricht nicht feuert (Pause, Seek,
 *   Titelwechsel). Er landet dann **nicht** — der Nutzer hat Vorrang.
 * - Eine neue Armierung oder [cancel] ersetzt die vorherige.
 */
@Singleton
class DropLandingArmer
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private val mutableEvents = MutableSharedFlow<DropLandingEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<DropLandingEvent> = mutableEvents.asSharedFlow()

        private var player: LandingPlayer? = null
        private var scope: CoroutineScope? = null

        private var armedMessage: LandingMessage? = null
        private var watchdogJob: Job? = null
        private var fadeJob: Job? = null

        /** Laufende Armierung; null = nichts armiert. */
        private var armed: Armed? = null

        /** Bindet Player und Service-Scope (genau einmal je Service). */
        fun attach(
            player: LandingPlayer,
            scope: CoroutineScope,
        ) {
            this.player = player
            this.scope = scope
        }

        /** Loest die Bindung beim Service-Ende; bricht eine Armierung ab. */
        fun detach() {
            cancel()
            player = null
            scope = null
        }

        /**
         * Armiert die Landung auf [song] bei `aktuellePosition + delayMs`.
         * [fadeMs] ist die Ausblenddauer VOR dem Wechsel (0 = nur
         * Mikro-Rampe); die Einblendung ist immer eine Mikro-Rampe.
         */
        fun arm(
            song: Song,
            startPositionMs: Long,
            delayMs: Long,
            fadeMs: Long,
        ): Boolean {
            val player = player ?: return false
            val scope = scope ?: return false
            cancel()
            val targetElapsed = clock.elapsedRealtimeMs() + delayMs.coerceAtLeast(0)
            val token = ++generation
            val armed =
                Armed(
                    token = token,
                    song = song,
                    startPositionMs = startPositionMs.coerceAtLeast(0),
                    targetElapsedRealtimeMs = targetElapsed,
                    fadeMs = fadeMs.coerceAtLeast(0),
                )
            this.armed = armed
            // Der Wechsel sitzt auf der Audio-Uhr: PlayerMessage feuert,
            // sobald die Wiedergabeposition die Zielposition erreicht.
            val positionMs = player.currentPositionMs + delayMs.coerceAtLeast(0)
            val index = player.currentMediaItemIndex.coerceAtLeast(0)
            val message =
                runCatching {
                    player.sendMessageAt(index, positionMs) { onMessage(token) }
                }.getOrElse {
                    this.armed = null
                    mutableEvents.tryEmit(DropLandingEvent.Missed(DropLandingEvent.Reason.PLAYER_ERROR))
                    return false
                }
            armedMessage = message
            // Ausblenden VOR dem Wechsel (Stufe 1, kein Ueberlappen): die
            // Kurve endet am Wechselzeitpunkt; die Restmusik wird nicht
            // mitten im Takt abgerissen.
            if (fadeMs > 0) {
                fadeJob =
                    scope.launch {
                        val fadeStart = targetElapsed - fadeMs
                        val wait = (fadeStart - clock.elapsedRealtimeMs()).coerceAtLeast(0)
                        delay(wait)
                        rampVolume(player, from = player.volume, to = 0f, durationMs = fadeMs)
                    }
            }
            // Watchdog: feuert die Nachricht nicht (Pause, Seek, Titelwechsel),
            // wird NICHT gelandet — der Nutzer hat Vorrang.
            watchdogJob =
                scope.launch {
                    delay(delayMs.coerceAtLeast(0) + WATCHDOG_SLACK_MS)
                    if (this@DropLandingArmer.armed?.token == token) {
                        // Eine noch haengende Nachricht entwerten, damit sie
                        // nicht nachtraeglich doch noch landet.
                        armedMessage?.cancel()
                        armedMessage = null
                        val paused = !player.isPlaying
                        finish(armed, landed = false)
                        mutableEvents.tryEmit(
                            DropLandingEvent.Missed(
                                if (paused) {
                                    DropLandingEvent.Reason.OVERRIDDEN
                                } else {
                                    DropLandingEvent.Reason.WATCHDOG
                                },
                            ),
                        )
                    }
                }
            return true
        }

        /** Bricht eine armierte Landung ab (Override, Neuplanung, Ende). */
        fun cancel() {
            armedMessage?.cancel()
            armedMessage = null
            watchdogJob?.cancel()
            watchdogJob = null
            fadeJob?.cancel()
            fadeJob = null
            armed = null
        }

        /**
         * Laeuft auf dem Main-Thread (Looper der Nachricht). Fuehrt den
         * Wechsel aus und meldet das Ergebnis.
         */
        private fun onMessage(token: Long) {
            val player = player ?: return
            val armed = armed ?: return
            if (armed.token != token) return
            val deltaMs = clock.elapsedRealtimeMs() - armed.targetElapsedRealtimeMs
            watchdogJob?.cancel()
            watchdogJob = null
            fadeJob?.cancel()
            fadeJob = null
            armedMessage = null
            val scope = scope
            player.volume = 0f
            player.startSong(armed.song, armed.startPositionMs)
            if (scope != null) {
                scope.launch {
                    rampVolume(player, from = 0f, to = 1f, durationMs = MICRO_RAMP_MS)
                }
            } else {
                player.volume = 1f
            }
            finish(armed, landed = true)
            mutableEvents.tryEmit(DropLandingEvent.Landed(armed.song.mediaStoreId, deltaMs))
        }

        private fun finish(
            armed: Armed,
            landed: Boolean,
        ) {
            if (this.armed?.token == armed.token) {
                this.armed = null
            }
            if (!landed) {
                // Ein nicht gelandeter Plan darf keine Lautstaerke-Rampe
                // hinterlassen (Fade-Out war moeglicherweise schon aktiv).
                player?.let { it.volume = 1f }
            }
        }

        private suspend fun rampVolume(
            player: LandingPlayer,
            from: Float,
            to: Float,
            durationMs: Long,
        ) {
            if (durationMs <= 0) {
                player.volume = to
                return
            }
            val steps = (durationMs / RAMP_STEP_MS).coerceAtLeast(1L).toInt()
            for (i in 1..steps) {
                player.volume = from + (to - from) * (i.toFloat() / steps)
                delay(RAMP_STEP_MS)
            }
            player.volume = to
        }

        /** Song-Aufloesung kommt vom Service (Library) — kein Zyklus hier. */
        private var generation = 0L

        private data class Armed(
            val token: Long,
            val song: Song,
            val startPositionMs: Long,
            val targetElapsedRealtimeMs: Long,
            val fadeMs: Long,
        )

        private companion object {
            /** Nachlauf des Watchdogs, bevor eine verpasste Landung gilt. */
            const val WATCHDOG_SLACK_MS = 250L

            /** Klick-Schutz: kurze Rampe um den harten Wechsel. */
            const val MICRO_RAMP_MS = 12L

            const val RAMP_STEP_MS = 4L
        }
    }
