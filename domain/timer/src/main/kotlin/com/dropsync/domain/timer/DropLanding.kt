package com.dropsync.domain.timer

// "DropSync rueckwaerts" (Musik-Workout-Plan Phase 3): Der Work-Titel wird
// so getimt, dass sein Drop exakt das Pausenende trifft. Reine Domainlogik
// ohne Media3-/Room-/Android-Typen; voll unit-testbar.

/**
 * Ein Work-Titel-Kandidat mit auto-erkanntem oder manuell gesetztem Drop.
 * [dropPositionMs] ist die Drop-Position ab Songanfang, [durationMs] die
 * Songdauer, [markerId] der zugehoerige Marker.
 */
data class WorkSongDrop(
    val songId: Long,
    val dropPositionMs: Long,
    val durationMs: Long,
    val markerId: Long,
)

/**
 * Ausfuehrbarer Plan der Drop-Landung: [songId]/[markerId] identifizieren
 * den Titel; [startAtPositionMs] ist die Startposition (Vorspulen),
 * [startAfterDelayMs] die Verzoegerung bis zum Start. In beiden Faellen
 * trifft der Drop nach Ablauf der Restzeit das Pausenende.
 */
data class DropLandingPlan(
    val songId: Long,
    val markerId: Long,
    val startAtPositionMs: Long,
    val startAfterDelayMs: Long,
)

/** Warum keine Drop-Landung moeglich ist (Fallback-Kette im Coordinator). */
enum class DropLandingReason {
    /** Restzeit kuerzer als die Mindestdauer; keine sinnvolle Landung. */
    REST_TOO_SHORT,

    /** Kein Work-Titel mit brauchbarem Drop verfuegbar. */
    NO_WORK_SONG_WITH_DROP,
}

/** Ergebnis der Drop-Landungs-Planung. */
sealed interface DropLandingResult {
    data class Scheduled(
        val plan: DropLandingPlan,
    ) : DropLandingResult

    data class NotPossible(
        val reason: DropLandingReason,
    ) : DropLandingResult
}

/**
 * Berechnet, wie ein Work-Titel zu starten ist, damit sein Drop exakt mit
 * dem Pausenende zusammenfaellt (Musik-Workout-Plan Phase 3).
 *
 * Gegeben die Restzeit R und ein Kandidat mit Drop-Position D:
 * - R < [MIN_REST_MS]                 -> NotPossible(REST_TOO_SHORT)
 * - kein Kandidat mit brauchbarem Drop -> NotPossible(NO_WORK_SONG_WITH_DROP)
 * - D >= R: sofort starten, auf D - R vorspulen (nach R ms kommt der Drop)
 * - D <  R: erst nach R - D ms starten, Titel von vorn (nach D ms der Drop)
 */
object DropLandingPlanner {
    /** Mindest-Restzeit fuer eine Landung; Groessenordnung der DropSync-Schwelle. */
    const val MIN_REST_MS: Long = 5_000L

    fun plan(
        remainingRestMs: Long,
        candidates: List<WorkSongDrop>,
        minRestMs: Long = MIN_REST_MS,
    ): DropLandingResult {
        if (remainingRestMs < minRestMs) {
            return DropLandingResult.NotPossible(DropLandingReason.REST_TOO_SHORT)
        }
        // Erster Titel mit brauchbarem Drop (0 <= Drop <= Songdauer).
        val candidate =
            candidates.firstOrNull { it.dropPositionMs in 0..it.durationMs }
                ?: return DropLandingResult.NotPossible(DropLandingReason.NO_WORK_SONG_WITH_DROP)

        val drop = candidate.dropPositionMs
        val rest = remainingRestMs
        val plan =
            if (drop >= rest) {
                DropLandingPlan(
                    songId = candidate.songId,
                    markerId = candidate.markerId,
                    startAtPositionMs = drop - rest,
                    startAfterDelayMs = 0L,
                )
            } else {
                DropLandingPlan(
                    songId = candidate.songId,
                    markerId = candidate.markerId,
                    startAtPositionMs = 0L,
                    startAfterDelayMs = rest - drop,
                )
            }
        return DropLandingResult.Scheduled(plan)
    }
}
