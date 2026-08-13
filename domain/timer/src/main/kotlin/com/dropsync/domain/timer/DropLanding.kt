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
 * trifft der Drop nach Ablauf der Restzeit das Pausenende (Design Phase 6:
 * WorkStart = Go - Marker - Latenz).
 *
 * [kind] unterscheidet zwei Strategien:
 * - [Kind.INTRO]: Drop liegt vor dem Pausenende -> Work-Titel startet
 *   nach der Verzoegerung von vorn (Intro), Crossfade aus [crossfadeMs].
 * - [Kind.DIRECT_TO_DROP]: Drop liegt hinter dem Pausenende -> Rest-Musik
 *   laeuft die volle Restzeit, beim Go springt der Player direkt auf den
 *   Drop (kein Intro, kein Knacksen dank Mikro-Rampe).
 */
data class DropLandingPlan(
    val songId: Long,
    val markerId: Long,
    val startAtPositionMs: Long,
    val startAfterDelayMs: Long,
    val kind: Kind = Kind.INTRO,
    val crossfadeMs: Long = 0L,
) {
    enum class Kind { INTRO, DIRECT_TO_DROP }
}

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
 * dem Pausenende zusammenfaellt (Musik-Workout-Plan Phase 3, Design Phase 6).
 *
 * Gegeben die Restzeit R, Latenz L und ein Kandidat mit Drop-Position D
 * (alle relativ zum Pausenende, also vor dem Go-Zeitpunkt):
 * - R < [MIN_REST_MS]                 -> NotPossible(REST_TOO_SHORT)
 * - kein Kandidat mit brauchbarem Drop -> NotPossible(NO_WORK_SONG_WITH_DROP)
 * - D >= R: DIRECT_TO_DROP - die Rest-Musik laeuft die volle Restzeit,
 *   beim Go springt der Player direkt zum Drop.
 * - D <  R: INTRO - der Work-Titel startet R - D vor dem Go von vorn,
 *   damit sein Intro genau in den Drop muendet.
 *
 * Die Latenz L wird vom Go abgezogen (WorkStart = Go - L), damit der
 * hoerbare Drop das Pausenende trifft. [crossfadeMs] lautet den
 * INTRO-Crossfade, der vor dem Drop endet (Design Phase 6.1).
 */
object DropLandingPlanner {
    /** Mindest-Restzeit fuer eine Landung; Groessenordnung der DropSync-Schwelle. */
    const val MIN_REST_MS: Long = 5_000L

    fun plan(
        remainingRestMs: Long,
        candidates: List<WorkSongDrop>,
        minRestMs: Long = MIN_REST_MS,
        latencyMs: Long = 0L,
        crossfadeMs: Long = 0L,
    ): DropLandingResult {
        if (remainingRestMs < minRestMs) {
            return DropLandingResult.NotPossible(DropLandingReason.REST_TOO_SHORT)
        }
        // Kandidaten mit brauchbarem Drop (0 <= Drop <= Songdauer), nach
        // Entscheidung 37: kleinster Abstand |R - D|, Drop <= R bevorzugt
        // (gleicher Abstand -> der Titel, der vor dem Pausenende landet).
        val valid =
            candidates.filter { it.dropPositionMs in 0..it.durationMs }
        if (valid.isEmpty()) {
            return DropLandingResult.NotPossible(DropLandingReason.NO_WORK_SONG_WITH_DROP)
        }
        val rest = remainingRestMs
        val candidate =
            valid.minWithOrNull(
                compareBy(
                    { kotlin.math.abs(it.dropPositionMs - rest) },
                    { if (it.dropPositionMs <= rest) 0 else 1 },
                ),
            ) ?: valid.first()

        val drop = candidate.dropPositionMs
        val plan =
            if (drop >= rest) {
                // Drop liegt hinter dem Go: Rest-Musik laeuft die volle
                // Restzeit; beim Go (abzueglich Latenz) direkt zum Drop.
                DropLandingPlan(
                    songId = candidate.songId,
                    markerId = candidate.markerId,
                    startAtPositionMs = drop,
                    startAfterDelayMs = (rest - latencyMs).coerceAtLeast(0),
                    kind = DropLandingPlan.Kind.DIRECT_TO_DROP,
                    crossfadeMs = 0L,
                )
            } else {
                // Drop liegt vor dem Go: Work-Titel startet (R - D - L)
                // vor dem Go von vorn; sein Intro (D) endet exakt im Drop.
                // Der Crossfade endet vor dem Restende (Design 7.1a).
                DropLandingPlan(
                    songId = candidate.songId,
                    markerId = candidate.markerId,
                    startAtPositionMs = 0L,
                    startAfterDelayMs = (rest - drop - latencyMs - crossfadeMs).coerceAtLeast(0),
                    kind = DropLandingPlan.Kind.INTRO,
                    crossfadeMs = crossfadeMs,
                )
            }
        return DropLandingResult.Scheduled(plan)
    }
}
