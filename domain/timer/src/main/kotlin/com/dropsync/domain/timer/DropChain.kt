package com.dropsync.domain.timer

// C16 (5.16-5.22): Geplante Ueberleitungskette fuer die Countdown-Zeit.
// Statt genau EINER Landung berechnet die App eine Folge von Uebergaengen
// ("aktueller Song spielt noch X, dann Wechsel zu Y, ...") und landet am
// Ende exakt auf einem Drop. Reine Mathematik ohne Android-/Media3-Bezug;
// die Ausfuehrung liegt im DropSyncCoordinator.

/**
 * Ein Titel der aktuellen Wiedergabe-Liste als Kettenkandidat (5.17).
 * [positionMs] ist die bekannte Wiedergabeposition (nur beim laufenden
 * Titel relevant), [dropPositionMs]/[markerId] der naechste aktive Drop;
 * null = der Titel kann nie Landung sein (Fueller darf er trotzdem).
 */
data class ChainCandidate(
    val songId: Long,
    val durationMs: Long,
    val positionMs: Long = 0L,
    val dropPositionMs: Long? = null,
    val markerId: Long? = null,
) {
    /** Restspielzeit des Titels ab [positionMs]. */
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
}

/** Rolle eines Segments in der Kette. */
enum class ChainSegmentKind {
    /** Zwischenstueck ohne Landungsanspruch. */
    FILLER,

    /** Letztes Segment: landet auf dem Drop (DropLandingPlanner). */
    LANDING,
}

/**
 * Ein Segment der Kette. [startsAfterMs] ist der Versatz ab Planzeit bis
 * zum Segmentbeginn, [playForMs] die Dauer bis zum naechsten Uebergang
 * bzw. bis zum Pausenende. [startAtPositionMs] ist die Startposition im
 * Titel (0 = von vorn, > 0 = Direktsprung).
 */
data class ChainSegment(
    val songId: Long,
    val kind: ChainSegmentKind,
    val startsAfterMs: Long,
    val playForMs: Long,
    val startAtPositionMs: Long = 0L,
    val markerId: Long? = null,
)

/** Ergebnis der Kettenplanung: mindestens die Landung, optional Fueller. */
data class DropChain(
    val segments: List<ChainSegment>,
) {
    /** Das Landungssegment (letztes Segment, C16-Entwurf). */
    val landing: ChainSegment? get() = segments.lastOrNull { it.kind == ChainSegmentKind.LANDING }

    /** Anzahl der Segmente inkl. laufendem Titel (5.19 begrenzt die Queue). */
    val plannedSongs: Int get() = segments.size
}

/** Ergebnis der Kettenplanung. */
sealed interface DropChainResult {
    data class Planned(
        val chain: DropChain,
    ) : DropChainResult

    data class NotPossible(
        val reason: DropLandingReason,
    ) : DropChainResult
}

/**
 * Plant die Ueberleitungskette (C16, Entscheidungen 5.16-5.22):
 *
 * - Kandidaten sind die **aktuelle Wiedergabe-Liste** ([queue] nach dem
 *   laufenden Titel, [currentSong] selbst), nicht die Pausen-Playlist
 *   (5.17).
 * - Hat der laufende Titel einen Drop am/ab dem Pausenende, bleibt er und
 *   ist selbst die Landung (5.18, Direktsprung beim Go).
 * - Aus dem Fenster der ersten [maxPlannedSongs] Queue-Titel ist der
 *   **letzte mit Drop** die Landung; die Titel davor werden Fueller (5.19).
 * - Ein Fueller wechselt auf seinem Drop, wenn dieser das Landungsfenster
 *   nicht verschiebt und mindestens [minSegmentMs] lang ist; sonst auf
 *   seinem Segmentende (5.20). Sehr kurze neue Segmente entfallen ganz.
 * - Die Landung nutzt die bestehende Landungsmathematik
 *   ([DropLandingPlanner], INTRO/DIRECT, Latenz, Crossfade) — Praezision
 *   nur am Ende.
 * - Unter [DropLandingPlanner.MIN_DROP_AUTO_REST_MS] gibt es keine Kette
 *   (C15).
 *
 * Deterministisch: gleiche Eingaben ergeben dieselbe Kette (feste
 * Queue-Reihenfolge, keine Zufallsquelle).
 */
object DropChainPlanner {
    /** Hoechstzahl geplanter Queue-Titel nach dem laufenden (5.19). */
    const val MAX_PLANNED_SONGS: Int = 2

    /**
     * Ein neuer Fueller wird nur geplant, wenn er mindestens so lange
     * spielt; sonst deckt der vorige Titel den Rest mit ab.
     */
    const val MIN_SEGMENT_MS: Long = 20_000L

    fun plan(
        remainingRestMs: Long,
        currentSong: ChainCandidate?,
        queue: List<ChainCandidate>,
        latencyMs: Long = 0L,
        crossfadeMs: Long = 0L,
        minSegmentMs: Long = MIN_SEGMENT_MS,
        maxPlannedSongs: Int = MAX_PLANNED_SONGS,
    ): DropChainResult {
        if (remainingRestMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS) {
            return DropChainResult.NotPossible(DropLandingReason.REST_TOO_SHORT)
        }

        // 5.18: Der laufende Titel bleibt und ist selbst die Landung, wenn
        // sein Drop am/ab dem Pausenende erreichbar ist (Direktsprung beim
        // Go). Ein Drop davor wuerde einen hoerbaren Ruecksprung bedeuten.
        currentSongLanding(remainingRestMs, currentSong, latencyMs)?.let { return it }

        // 5.19: Landung ist der LETZTE Titel mit Drop innerhalb der
        // Kettenlaenge; davor liegende Titel werden Fueller (5.20).
        val window = queue.take(maxPlannedSongs.coerceAtLeast(1))
        val landingIndex = window.indexOfLast { it.dropPositionMs != null }
        if (landingIndex < 0) {
            return DropChainResult.NotPossible(DropLandingReason.NO_WORK_SONG_WITH_DROP)
        }
        val landingSong = window[landingIndex]
        val drop =
            landingSong.dropPositionMs
                ?: return DropChainResult.NotPossible(DropLandingReason.NO_WORK_SONG_WITH_DROP)

        // Landung ueber den bestehenden Planner (eine Wahrheit fuer
        // Latenz/Crossfade); die Entscheidung faellt gegen die volle
        // Restzeit, nicht gegen das Fueller-Fenster.
        val landingPlan =
            when (
                val result =
                    DropLandingPlanner.plan(
                        remainingRestMs = remainingRestMs,
                        candidates = listOf(landingSong.toWorkSongDrop(drop)),
                        latencyMs = latencyMs,
                        crossfadeMs = crossfadeMs,
                    )
            ) {
                is DropLandingResult.Scheduled -> result.plan
                is DropLandingResult.NotPossible -> return DropChainResult.NotPossible(result.reason)
            }
        val landingStart = landingPlan.startAfterDelayMs

        val fillers = buildFillers(currentSong, window, landingIndex, landingStart, minSegmentMs)
        val landing =
            ChainSegment(
                songId = landingSong.songId,
                kind = ChainSegmentKind.LANDING,
                startsAfterMs = landingStart,
                playForMs = (remainingRestMs - landingStart).coerceAtLeast(0L),
                startAtPositionMs = landingPlan.startAtPositionMs,
                markerId = landingSong.markerId,
            )
        return DropChainResult.Planned(DropChain(fillers + landing))
    }

    /**
     * 5.18: Der laufende Titel ist selbst die Landung, wenn sein Drop am
     * oder nach dem Pausenende liegt (Direktsprung beim Go); sonst null.
     */
    private fun currentSongLanding(
        remainingRestMs: Long,
        currentSong: ChainCandidate?,
        latencyMs: Long,
    ): DropChainResult.Planned? {
        val drop = currentSong?.dropPositionMs ?: return null
        if (drop < remainingRestMs - latencyMs) return null
        return DropChainResult.Planned(
            DropChain(
                listOf(
                    ChainSegment(
                        songId = currentSong.songId,
                        kind = ChainSegmentKind.LANDING,
                        startsAfterMs = 0L,
                        playForMs = remainingRestMs,
                        startAtPositionMs = drop,
                        markerId = currentSong.markerId,
                    ),
                ),
            ),
        )
    }

    private fun ChainCandidate.toWorkSongDrop(dropPositionMs: Long): WorkSongDrop =
        WorkSongDrop(
            songId = songId,
            dropPositionMs = dropPositionMs,
            durationMs = durationMs,
            markerId = markerId ?: 0L,
        )

    /**
     * Fueller fuer [0, landingStart): laufender Titel zuerst, dann die
     * Queue-Titel vor der Landung (hoechstens [landingIndex]). Restluecke
     * deckt der ERSTE Titel ab (der laufende spielt bereits; ohne laufenden
     * Titel der erste Fueller) — die spaeteren Wechselpunkte bleiben damit
     * auf ihren Drops. Die Folge-Segmente ruecken um die Luecke nach hinten.
     */
    private fun buildFillers(
        currentSong: ChainCandidate?,
        window: List<ChainCandidate>,
        landingIndex: Int,
        landingStart: Long,
        minSegmentMs: Long,
    ): List<ChainSegment> {
        val fillers = mutableListOf<ChainSegment>()
        var filled = 0L
        for ((candidate, isCurrent) in candidatePairs(currentSong, window, landingIndex)) {
            if (stopFilling(isCurrent, fillers, filled, landingStart, minSegmentMs)) break
            val remaining = landingStart - filled
            val playFor = fillerPlayFor(candidate, isCurrent, filled, landingStart, minSegmentMs, remaining)
            fillers +=
                ChainSegment(
                    songId = candidate.songId,
                    kind = ChainSegmentKind.FILLER,
                    startsAfterMs = filled,
                    playForMs = playFor,
                )
            filled += playFor
        }
        extendFirstFiller(fillers, landingStart - filled)
        return fillers
    }

    private fun candidatePairs(
        currentSong: ChainCandidate?,
        window: List<ChainCandidate>,
        landingIndex: Int,
    ): List<Pair<ChainCandidate, Boolean>> =
        buildList {
            if (currentSong != null) add(currentSong to true)
            window.take(landingIndex).forEach { add(it to false) }
        }

    /** Kein Platz mehr oder ein zu kurzes neues Segment (Fragment-Springen). */
    private fun stopFilling(
        isCurrent: Boolean,
        fillers: List<ChainSegment>,
        filled: Long,
        landingStart: Long,
        minSegmentMs: Long,
    ): Boolean {
        val remaining = landingStart - filled
        if (remaining <= 0L) return true
        return !isCurrent && remaining < minSegmentMs && fillers.isNotEmpty()
    }

    private fun extendFirstFiller(
        fillers: MutableList<ChainSegment>,
        gap: Long,
    ) {
        if (gap <= 0L || fillers.isEmpty()) return
        val first = fillers.removeAt(0)
        fillers.add(0, first.copy(playForMs = first.playForMs + gap))
        for (index in 1 until fillers.size) {
            val segment = fillers[index]
            fillers[index] = segment.copy(startsAfterMs = segment.startsAfterMs + gap)
        }
    }

    /**
     * Dauer eines Fuellers: 5.20 — Wechsel auf dem Drop, wenn er das
     * Landungsfenster nicht verschiebt (Drop-Ende <= [landingStart]) und
     * im erlaubten Fenster liegt; sonst Segmentende (Restspielzeit).
     */
    private fun fillerPlayFor(
        candidate: ChainCandidate,
        isCurrent: Boolean,
        filled: Long,
        landingStart: Long,
        minSegmentMs: Long,
        remaining: Long,
    ): Long {
        val drop = candidate.dropPositionMs
        val dropOffset = drop?.let { (it - candidate.positionMs).coerceAtLeast(0L) }
        val switchOnDrop =
            dropOffset != null &&
                dropOffset >= minSegmentMs &&
                filled + dropOffset <= landingStart
        val naturalEnd = if (isCurrent) candidate.remainingMs else candidate.durationMs
        val switchAt = if (switchOnDrop) dropOffset else naturalEnd
        return switchAt.coerceAtMost(remaining)
    }
}
