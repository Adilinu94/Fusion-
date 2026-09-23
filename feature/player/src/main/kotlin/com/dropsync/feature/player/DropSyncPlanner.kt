package com.dropsync.feature.player

import com.dropsync.core.common.AppResult
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.library.DropTargetRepository
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RouteProfileRepository
import com.dropsync.domain.timer.ChainCandidate
import com.dropsync.domain.timer.DropChain
import com.dropsync.domain.timer.DropChainPlanner
import com.dropsync.domain.timer.DropChainResult
import com.dropsync.domain.timer.DropLandingPlan
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropLandingReason
import com.dropsync.domain.timer.DropLandingResult
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.TimingConfidence
import com.dropsync.domain.timer.WorkSongDrop
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Planung der Drop-Landung (MP-3/MP-14/D9): laedt Work-Kandidaten,
 * waehlt den Plan und nennt die Konfidenz. Bewusst ohne Ausfuehrung —
 * Armieren, Watchdog und Zustand liegen im [DropSyncCoordinator].
 *
 * - MP-14: **alle** aktiven Marker je Work-Titel sind Kandidaten; der
 *   [DropLandingPlanner] waehlt den mit dem kleinsten Abstand zur Restzeit.
 * - D9 (ADR-0022 Stufe 1): die Crossfade-Dauer aus der DSP-Konfiguration
 *   wird an den Planner uebergeben (Fade-Fenster um den harten Wechsel).
 * - C16: [planChain] plant die Ueberleitungskette aus der aktuellen
 *   Wiedergabe-Liste (5.17) ueber [DropChainPlanner].
 */
@Singleton
class DropSyncPlanner
    @Inject
    constructor(
        private val browseRepository: LibraryBrowseRepository,
        private val libraryRepository: LibraryRepository,
        private val markerRepository: MarkerRepository,
        private val routeProfiles: RouteProfileRepository,
        private val audioEngine: AudioEngineRepository,
        private val dropTargetRepository: DropTargetRepository,
    ) {
        /** Ergebnis einer Planung. */
        sealed interface Outcome {
            data class Planned(
                val plan: DropLandingPlan,
                val song: Song,
                val markerLabel: String,
                val confidence: TimingConfidence,
            ) : Outcome

            data class NotPossible(
                val reason: DropSyncFailureReason,
            ) : Outcome
        }

        /**
         * C16: Ergebnis einer Kettenplanung mit aufgeloesten Songs.
         * [currentSongId] ist der bereits laufende Titel (Segment 0 wird
         * nicht erneut gestartet), [crossfadeMs] die Fade-Dauer der
         * Landung aus der DSP-Konfiguration.
         */
        sealed interface ChainOutcome {
            data class Planned(
                val chain: DropChain,
                val currentSongId: Long?,
                val songsById: Map<Long, Song>,
                val labelsByMarkerId: Map<Long, String>,
                val confidence: TimingConfidence,
                val crossfadeMs: Long,
            ) : ChainOutcome

            data class NotPossible(
                val reason: DropSyncFailureReason,
            ) : ChainOutcome
        }

        /** Plant die Landung fuer [remainingMs] Restzeit. */
        suspend fun plan(remainingMs: Long): Outcome {
            val candidates = workCandidates()
            val latency = routeProfiles.currentLatencyMs()
            val scheduled =
                DropLandingPlanner.plan(
                    remainingRestMs = remainingMs,
                    candidates = candidates.drops,
                    latencyMs = latency ?: 0L,
                    crossfadeMs = crossfadeMs(),
                )
            val plan = (scheduled as? DropLandingResult.Scheduled)?.plan
            if (plan == null) {
                return Outcome.NotPossible(
                    when (scheduled) {
                        is DropLandingResult.NotPossible -> {
                            if (scheduled.reason == DropLandingReason.REST_TOO_SHORT) {
                                DropSyncFailureReason.REST_TOO_SHORT
                            } else {
                                DropSyncFailureReason.NO_WORK_DROP
                            }
                        }

                        else -> {
                            DropSyncFailureReason.NO_WORK_DROP
                        }
                    },
                )
            }
            val song =
                candidates.songsById[plan.songId]
                    ?: return Outcome.NotPossible(DropSyncFailureReason.NO_WORK_DROP)
            return Outcome.Planned(
                plan = plan,
                song = song,
                markerLabel = candidates.labelsByMarkerId[plan.markerId].orEmpty(),
                confidence = if (latency == null) TimingConfidence.DEGRADED else TimingConfidence.EXACT,
            )
        }

        /** Titel der Work-Playlist (Pausenende ohne Landung). */
        suspend fun workSongs(): List<Song> = songsForLabel(PlaylistLabel.WORK)

        /**
         * C16: Plant die Ueberleitungskette aus der aktuellen
         * Wiedergabe-Liste (5.17). [queue] ist die Player-Queue,
         * [currentIndex] der laufende Eintrag; [currentPositionMs] die
         * bekannte Position des laufenden Titels (fuer die Restspielzeit).
         * Es werden nur die Fenster-Titel geladen (kein N+1 ueber die
         * ganze Queue).
         */
        suspend fun planChain(
            remainingMs: Long,
            queue: List<QueueItem>,
            currentIndex: Int,
            currentPositionMs: Long,
        ): ChainOutcome {
            val currentId = queue.getOrNull(currentIndex)?.songId
            val window = queue.drop(currentIndex + 1).take(DropChainPlanner.MAX_PLANNED_SONGS)
            val ids =
                buildList {
                    if (currentId != null) add(currentId)
                    window.mapNotNullTo(this) { it.songId }
                }.distinct()
            if (ids.isEmpty()) return ChainOutcome.NotPossible(DropSyncFailureReason.NO_WORK_DROP)
            val songsById =
                ids
                    .mapNotNull { id ->
                        (libraryRepository.getSong(id) as? AppResult.Success)
                            ?.value
                            ?.let { id to it }
                    }.toMap()
            val targets =
                runCatching { dropTargetRepository.targets.first() }.getOrDefault(emptyMap())
            // D5/A7: EINE Marker-Abfrage fuer den laufenden Titel und das
            // Ketten-Fenster (vorher je Titel eine Query).
            val markersBySong =
                markerRepository
                    .getEnabledMarkersForSongs(songsById.keys.toList())
                    .getOrNull()
                    .orEmpty()
            val drops = mutableMapOf<Long, Pair<Long, Long>>()
            val labels = mutableMapOf<Long, String>()
            songsById.forEach { (id, song) ->
                nextDrop(song, targets[id], markersBySong[song.mediaStoreId].orEmpty()).let { drop ->
                    if (drop != null) {
                        drops[id] = drop.positionMs to drop.markerId
                        labels[drop.markerId] = drop.label
                    }
                }
            }
            val current =
                currentId
                    ?.let { songsById[it] }
                    ?.let { song -> chainCandidate(song, currentPositionMs, drops[song.mediaStoreId]) }
            val queueCandidates =
                window.mapNotNull { item ->
                    item.songId
                        ?.let { songsById[it] }
                        ?.let { song -> chainCandidate(song, 0L, drops[song.mediaStoreId]) }
                }
            val latency = routeProfiles.currentLatencyMs()
            val crossfade = crossfadeMs()
            val result =
                DropChainPlanner.plan(
                    remainingRestMs = remainingMs,
                    currentSong = current,
                    queue = queueCandidates,
                    latencyMs = latency ?: 0L,
                    crossfadeMs = crossfade,
                )
            return when (result) {
                is DropChainResult.NotPossible -> {
                    ChainOutcome.NotPossible(
                        if (result.reason == DropLandingReason.REST_TOO_SHORT) {
                            DropSyncFailureReason.REST_TOO_SHORT
                        } else {
                            DropSyncFailureReason.NO_WORK_DROP
                        },
                    )
                }

                is DropChainResult.Planned -> {
                    ChainOutcome.Planned(
                        chain = result.chain,
                        currentSongId = currentId,
                        songsById = songsById,
                        labelsByMarkerId = labels,
                        confidence = if (latency == null) TimingConfidence.DEGRADED else TimingConfidence.EXACT,
                        crossfadeMs = crossfade,
                    )
                }
            }
        }

        private fun chainCandidate(
            song: Song,
            positionMs: Long,
            drop: Pair<Long, Long>?,
        ): ChainCandidate =
            ChainCandidate(
                songId = song.mediaStoreId,
                durationMs = song.durationMs,
                positionMs = positionMs,
                dropPositionMs = drop?.first,
                markerId = drop?.second,
            )

        /**
         * Naechster brauchbarer Drop eines Titels (Ziel bevorzugt, MP-14)
         * aus den bereits geladenen Markern (D5/A7: kein eigener Zugriff).
         */
        private fun nextDrop(
            song: Song,
            targetMarkerId: Long?,
            markers: List<SongMarker>,
        ): DropCandidate? {
            val usable =
                markers
                    .filter { it.positionMs in 0..song.durationMs }
                    .sortedBy { it.positionMs }
            val preferred = usable.filter { it.id == targetMarkerId }
            return (preferred.ifEmpty { usable }).firstOrNull()?.let {
                DropCandidate(positionMs = it.positionMs, markerId = it.id, label = it.label)
            }
        }

        private data class DropCandidate(
            val positionMs: Long,
            val markerId: Long,
            val label: String,
        )

        /** Titel einer Playlist-Kennzeichnung (Rest-Queue des Koordinators). */
        suspend fun playlistSongs(label: PlaylistLabel): List<Song> = songsForLabel(label)

        /** Crossfade-Dauer aus der DSP-Konfiguration (D9, ADR-0022 Stufe 1). */
        private suspend fun crossfadeMs(): Long =
            runCatching {
                audioEngine.dspConfig.first().crossfadeSeconds * 1_000L
            }.getOrDefault(0L)

        /**
         * Work-Titel mit ihren aktiven Markern als Landungs-Kandidaten
         * (MP-14: ALLE Marker je Titel, der Planner waehlt den naechsten).
         *
         * P2-21: Ein vom Nutzer gewaehltes Ziel ("Als DropSync-Ziel waehlen")
         * ersetzt die Naechster-Marker-Wahl SEINES Titels; die Titelwahl
         * (kleinster Abstand zur Restzeit) bleibt unveraendert. Fehlt der
         * Marker (geloescht oder deaktiviert), greift ohne Aufraeumen wieder
         * die Automatik.
         */
        private suspend fun workCandidates(): WorkCandidates {
            val songs = songsForLabel(PlaylistLabel.WORK)
            val songsById = songs.associateBy { it.mediaStoreId }
            val targets =
                runCatching { dropTargetRepository.targets.first() }.getOrDefault(emptyMap())
            // D5/A7: EINE Marker-Abfrage fuer alle Work-Titel (vorher je
            // Titel eine Query); die Wahl je Titel bleibt unveraendert.
            val markersBySong =
                markerRepository
                    .getEnabledMarkersForSongs(songs.map { it.mediaStoreId })
                    .getOrNull()
                    .orEmpty()
            val drops = mutableListOf<WorkSongDrop>()
            val labels = mutableMapOf<Long, String>()
            songs.forEach { song ->
                val markers =
                    markersBySong[song.mediaStoreId]
                        .orEmpty()
                        // Deterministisch: nur gueltige Positionen, kleinste
                        // zuerst; nie auf DAO-Reihenfolge verlassen.
                        .filter { it.positionMs in 0..song.durationMs }
                        .sortedBy { it.positionMs }
                val preferred = markers.filter { it.id == targets[song.mediaStoreId] }
                (preferred.ifEmpty { markers }).forEach { marker ->
                    drops +=
                        WorkSongDrop(
                            songId = song.mediaStoreId,
                            dropPositionMs = marker.positionMs,
                            durationMs = song.durationMs,
                            markerId = marker.id,
                        )
                    labels[marker.id] = marker.label
                }
            }
            return WorkCandidates(drops, songsById, labels)
        }

        private suspend fun songsForLabel(label: PlaylistLabel): List<Song> =
            // D5/A7: EINE Abfrage fuer alle Playlists des Labels (vorher je
            // Playlist eine Query).
            browseRepository.songsForLabelOnce(label).getOrNull().orEmpty()

        private data class WorkCandidates(
            val drops: List<WorkSongDrop>,
            val songsById: Map<Long, Song>,
            val labelsByMarkerId: Map<Long, String>,
        )
    }
