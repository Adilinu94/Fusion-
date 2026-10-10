package com.dropsync.domain.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Drop-Kandidaten aus Fullband- UND Bass-Energie.
 *
 * [OnsetDetection] sieht nur positive Spruenge der Fullband-RMS. Das findet laute
 * Refrain-Einsaetze (oft Fehlalarme) und verfehlt Drops, bei denen der Bass im Build-up
 * weggeschnitten war und die Gesamtlautstaerke durch Riser und Pads schon oben stand.
 *
 * Dieser Detektor sucht zuerst die **Bass-Rueckkehr**: die Bass-Energie war ueber mehrere
 * Sekunden niedrig ("Break") und ist danach deutlich da. Die Position wird auf das
 * Energiefenster genau gesetzt, in dem der Bass einsetzt (Kick-/Bass-Transiente), optional
 * auf das Taktraster ([BeatGrid]) gerastet. Danach fuellen Fullband-Kandidaten aus
 * [OnsetDetection] die uebrigen Plaetze - schwaecher eingestuft, damit eine echte
 * Bass-Rueckkehr in den Top-N nicht von einem lauten Refrain verdraengt wird.
 *
 * Gilt fuer Musik mit hörbarem Bass; ohne Bass-Energie (Sprache, a-cappella) faellt der
 * Detektor auf das reine Fullband-Verhalten zurueck. Die Kandidaten bleiben Vorschlaege,
 * die Person bestaetigt.
 *
 * Nur auf synthetischen Signalen getestet (siehe DropDetectionTest); die Schwellen sind
 * Startwerte, kein Ergebnis einer Messung an echten Tracks.
 */
object DropDetection {
    enum class Source { BASS_RETURN, FULLBAND }

    data class Candidate(
        val positionMs: Long,
        val source: Source,
        /** Bass-Rueckkehr: normierter Anstieg (0..1+); Fullband: absolute Novelty. */
        val score: Double,
    )

    /** Taktraster eines Tracks (BPM und Position eines Downbeats in ms). */
    data class BeatGrid(
        val bpm: Double,
        val downbeatOffsetMs: Long,
    )

    /**
     * Taktraster nur, wenn Tempo UND Downbeat die Konfidenz-Schwellen der Mix-Analyse erreichen
     * ([MixConfidence.MIN_BPM_CONFIDENCE], [DownbeatConfidence.MIN_SNAP_CONFIDENCE]) - dieselben
     * wie beim Marker-Snap in der UI (ADR-0025). Sonst null: die Kandidaten bleiben ungerastet.
     */
    fun beatGrid(
        tempo: TempoEstimate?,
        downbeat: DownbeatEstimate?,
    ): BeatGrid? {
        val bpm = MixConfidence.acceptBpm(tempo?.bpm, tempo?.confidence) ?: return null
        val offset = DownbeatConfidence.acceptOffset(downbeat?.offsetMs, downbeat?.confidence) ?: return null
        return BeatGrid(bpm.toDouble(), offset)
    }

    /** Kandidaten, staerkster zuerst (Bass-Rueckkehr vor Fullband). */
    fun detect(
        fullbandEnergy: List<Double>,
        bassEnergy: List<Double>,
        windowDurationMs: Long,
        maxCandidates: Int = OnsetDetection.DEFAULT_MAX_CANDIDATES,
        minSpacingMs: Long = OnsetDetection.DEFAULT_MIN_SPACING_MS,
        beatGrid: BeatGrid? = null,
    ): List<Candidate> {
        require(windowDurationMs > 0) { "windowDurationMs muss positiv sein" }
        if (maxCandidates <= 0) return emptyList()

        val picked = ArrayList<Candidate>()
        for (c in bassReturns(bassEnergy, windowDurationMs, minSpacingMs)) {
            if (picked.size == maxCandidates) break
            picked += c.copy(positionMs = snap(c.positionMs, beatGrid))
        }
        val fullband =
            OnsetDetection.detectScored(
                energyWindows = fullbandEnergy,
                windowDurationMs = windowDurationMs,
                minSpacingMs = minSpacingMs,
                maxCandidates = maxCandidates,
            )
        for ((positionMs, novelty) in fullband) {
            if (picked.size == maxCandidates) break
            val tooClose = picked.any { abs(it.positionMs - positionMs) < minSpacingMs }
            if (!tooClose) picked += Candidate(snap(positionMs, beatGrid), Source.FULLBAND, novelty)
        }
        return picked
    }

    /** Positionen in ms, zeitlich aufsteigend - Drop-in-Ersatz fuer [OnsetDetection.detectOnsets]. */
    fun candidatePositions(
        fullbandEnergy: List<Double>,
        bassEnergy: List<Double>,
        windowDurationMs: Long,
        maxCandidates: Int = OnsetDetection.DEFAULT_MAX_CANDIDATES,
        minSpacingMs: Long = OnsetDetection.DEFAULT_MIN_SPACING_MS,
        beatGrid: BeatGrid? = null,
    ): List<Long> =
        detect(fullbandEnergy, bassEnergy, windowDurationMs, maxCandidates, minSpacingMs, beatGrid)
            .map { it.positionMs }
            .sorted()

    private fun bassReturns(
        bass: List<Double>,
        windowMs: Long,
        minSpacingMs: Long,
    ): List<Candidate> {
        val perBlock = (BLOCK_MS / windowMs).toInt().coerceAtLeast(1)
        val blocks =
            bass
                .chunked(perBlock)
                .filter { it.size == perBlock }
                .map { w -> sqrt(w.sumOf { it * it } / w.size) }
        if (blocks.size < MIN_PRE_BLOCKS + POST_BLOCKS + 1) return emptyList()

        val reference = blocks.sorted()[((blocks.size - 1) * REFERENCE_PERCENTILE).toInt()]
        if (reference < BASS_FLOOR) return emptyList()

        val scored = ArrayList<Pair<Int, Double>>()
        for (b in MIN_PRE_BLOCKS..blocks.size - POST_BLOCKS) {
            val pre = mean(blocks, max(0, b - PRE_BLOCKS), b)
            val post = mean(blocks, b, b + POST_BLOCKS)
            val breakBefore = pre <= PRE_MAX_FRACTION * reference
            val bassBack = post >= POST_MIN_FRACTION * reference
            val clearStep = post >= MIN_RATIO * max(pre, FLOOR_FRACTION * reference)
            if (breakBefore && bassBack && clearStep) scored += b to (post - pre) / reference
        }

        val selected = ArrayList<Pair<Int, Double>>()
        for (candidate in scored.sortedByDescending { it.second }) {
            val ms = candidate.first * BLOCK_MS
            if (selected.none { abs(it.first * BLOCK_MS - ms) < minSpacingMs }) selected += candidate
        }
        return selected.map { (block, score) ->
            Candidate(localize(bass, block * perBlock, perBlock, windowMs), Source.BASS_RETURN, score)
        }
    }

    /** Fenster mit dem staerksten Bass-Einsatz (Mittel danach minus Mittel davor) um [center]. */
    private fun localize(
        bass: List<Double>,
        center: Int,
        perBlock: Int,
        windowMs: Long,
    ): Long {
        val from = max(STEP_SPAN, center - 2 * perBlock)
        val to = min(bass.size - STEP_SPAN, center + 2 * perBlock)
        var bestIndex = center.coerceIn(0, max(0, bass.size - 1))
        var bestStep = Double.NEGATIVE_INFINITY
        for (i in from..to) {
            val step = mean(bass, i, i + STEP_SPAN) - mean(bass, i - STEP_SPAN, i)
            if (step > bestStep) {
                bestStep = step
                bestIndex = i
            }
        }
        return bestIndex * windowMs
    }

    /**
     * Rastet [positionMs] auf den naechsten Beat ab dem Raster-Offset ein, wie der Marker-Snap
     * (ADR-0025): nie VOR dem Offset (dort gibt es keinen Beat), nur im Bereich 30-300 BPM und nur,
     * wenn der Beat nah genug liegt (hoechstens [SNAP_TOLERANCE_MS] und ein Viertel Beat).
     */
    private fun snap(
        positionMs: Long,
        grid: BeatGrid?,
    ): Long {
        if (grid == null || grid.bpm !in MIN_SNAP_BPM..MAX_SNAP_BPM) return positionMs
        val offset = grid.downbeatOffsetMs.coerceAtLeast(0L)
        if (positionMs < offset) return positionMs
        val beatMs = 60_000.0 / grid.bpm
        val k = ((positionMs - offset) / beatMs).roundToLong()
        val snapped = (offset + k * beatMs).roundToLong()
        val tolerance = minOf(SNAP_TOLERANCE_MS.toDouble(), beatMs / 4.0)
        return if (abs(snapped - positionMs) <= tolerance) snapped else positionMs
    }

    private fun mean(
        v: List<Double>,
        from: Int,
        to: Int,
    ): Double {
        var sum = 0.0
        var n = 0
        for (i in max(0, from) until min(v.size, to)) {
            sum += v[i]
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** Blockbreite fuer die Bass-Huellkurve. */
    private const val BLOCK_MS = 250L

    /** 4 s Rueckblick, mindestens 2 s (8 Bloecke) davon muessen vorhanden sein. */
    private const val PRE_BLOCKS = 16
    private const val MIN_PRE_BLOCKS = 8

    /** 2 s Ausblick. */
    private const val POST_BLOCKS = 8
    private const val REFERENCE_PERCENTILE = 0.9
    private const val BASS_FLOOR = 1e-3
    private const val PRE_MAX_FRACTION = 0.4
    private const val POST_MIN_FRACTION = 0.7
    private const val MIN_RATIO = 2.5
    private const val FLOOR_FRACTION = 0.05
    private const val STEP_SPAN = 4

    /** Rasten nur, wenn der Takt-Punkt nah genug liegt - sonst bleibt die gemessene Position. */
    private const val SNAP_TOLERANCE_MS = 60L
    private const val MIN_SNAP_BPM = 30.0
    private const val MAX_SNAP_BPM = 300.0
}
