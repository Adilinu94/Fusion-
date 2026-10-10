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
 *   laeuft bis kurz vor dem Go; der Titel steigt dann [leadInMs] VOR dem Drop
 *   ein (Build-up: Snare-Wirbel, Riser), sodass der Drop selbst genau aufs Go faellt.
 *   Mit [leadInMs] = 0 springt der Player beim Go direkt auf den Drop (kein Intro,
 *   kein Knacksen dank Mikro-Rampe).
 */
data class DropLandingPlan(
    val songId: Long,
    val markerId: Long,
    val startAtPositionMs: Long,
    val startAfterDelayMs: Long,
    val kind: Kind = Kind.INTRO,
    val crossfadeMs: Long = 0L,
    /** Nur DIRECT_TO_DROP: so weit liegt die Startposition VOR dem Drop (Build-up). */
    val leadInMs: Long = 0L,
) {
    enum class Kind { INTRO, DIRECT_TO_DROP }

    /**
     * Startposition, wenn die Armierung [lateMs] zu spaet kam: der Titel muss so weit
     * vorspulen, dass der Drop trotzdem aufs Go faellt (MP-3). Bei DIRECT_TO_DROP kann
     * hoechstens um [leadInMs] vorgespult werden - ohne Build-up ([leadInMs] = 0) startet
     * der Titel direkt auf dem Drop, wie bisher.
     */
    fun startPositionAfterLate(lateMs: Long): Long =
        when (kind) {
            Kind.INTRO -> startAtPositionMs + lateMs
            Kind.DIRECT_TO_DROP -> startAtPositionMs + lateMs.coerceIn(0L, leadInMs)
        }
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
 * - D >= R: DIRECT_TO_DROP - die Rest-Musik laeuft bis [leadInMs] vor dem Go,
 *   dann steigt der Titel [leadInMs] vor dem Drop ein (Build-up); mit
 *   [leadInMs] = 0 springt der Player beim Go direkt zum Drop.
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

    /**
     * C15 (PR-4): Unter einer Minute ist Drop-Auto nicht moeglich — die
     * Landung waere kaum mehr als ein Titelwechsel. Gilt fuer den Schalter
     * und die Sofort-Planung; der manuelle DropRest bleibt bei [MIN_REST_MS].
     */
    const val MIN_DROP_AUTO_REST_MS: Long = 60_000L

    /**
     * Build-up vor dem Drop bei DIRECT_TO_DROP: Der Titel steigt so lange vor dem Drop ein,
     * dass der Aufbau (Snare-Wirbel, Riser) zu hoeren ist. 6 s sind ca. 3 Takte bei 120 BPM.
     * Startwert, nicht an echter Musik abgestimmt. Aktiv nur, wenn der Schalter "Build-up vor dem Drop"
     * in den Einstellungen an ist (Standard aus, [RestMusicSettingsRepository.dropLeadInEnabled]).
     */
    const val DEFAULT_LEAD_IN_MS: Long = 6_000L

    /** Der Build-up darf hoechstens 1/4 der Restzeit belegen. */
    private const val MAX_LEAD_IN_REST_DIVISOR: Long = 4L

    fun plan(
        remainingRestMs: Long,
        candidates: List<WorkSongDrop>,
        minRestMs: Long = MIN_REST_MS,
        latencyMs: Long = 0L,
        crossfadeMs: Long = 0L,
        leadInMs: Long = 0L,
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
                // Build-up: nie vor Position 0 und nie laenger als ein Viertel der Restzeit,
                // damit die Rest-Musik den groesseren Teil der Pause behaelt.
                val lead = leadInMs.coerceIn(0L, minOf(drop, rest / MAX_LEAD_IN_REST_DIVISOR))
                DropLandingPlan(
                    songId = candidate.songId,
                    markerId = candidate.markerId,
                    startAtPositionMs = drop - lead,
                    startAfterDelayMs = (rest - lead - latencyMs).coerceAtLeast(0),
                    kind = DropLandingPlan.Kind.DIRECT_TO_DROP,
                    crossfadeMs = 0L,
                    leadInMs = lead,
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
