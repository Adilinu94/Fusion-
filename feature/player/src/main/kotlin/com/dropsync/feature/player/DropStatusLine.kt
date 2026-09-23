package com.dropsync.feature.player

import com.dropsync.domain.timer.BestEffortReason
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState

/**
 * P2-21 (MP-7): Statuszeile unter der Now-Playing-Waveform — dieselbe
 * Sprache wie die Train-Konsole, nur als Zeile statt als Karte.
 *
 * Reine Ableitung aus dem einen DropSync-Zustand (keine zweite
 * Wahrheit) und der monotonen Uhr: die Zielzeit eines Plans ist ein
 * `elapsedRealtime`-Deadline; damit tickt die Anzeige unabhaengig davon,
 * ob die Konsole (Rest-Timer) gerade sichtbar ist.
 */
sealed interface DropStatusLine {
    /** Plan steht/ist scharf: Track, Marker und Countdown bis zum Drop. */
    data class Ready(
        val songTitle: String,
        val markerLabel: String,
        val remainingMs: Long,
        /**
         * C11: Betriebsart — bei [DropSyncMode.UNTIL_MARKER] zeigt die
         * DropRestCard den Countdown; die Statuszeile bleibt dann stumm.
         */
        val mode: DropSyncMode,
        /**
         * C16 (5.22): geplante Ueberleitungskette ("A -> B -> Drop");
         * leer bei einer Einzellandung.
         */
        val chain: List<String> = emptyList(),
    ) : DropStatusLine

    /** Nur Best-Effort-Landung moeglich; der Grund wird genannt. */
    data class BestEffort(
        val reason: BestEffortReason,
    ) : DropStatusLine

    /** Nutzer hat den Plan uebernommen; sichtbar zurueckgenommen. */
    data object Overridden : DropStatusLine

    /**
     * C1: Kein Plan moeglich — der Grund wird genannt statt still zu
     * bleiben (Ausfuehrungsregel 4). [PLAN_LOST] ist quittierbar.
     */
    data class Failed(
        val reason: DropSyncFailureReason,
    ) : DropStatusLine
}

/**
 * Statuszeile fuer [state]; null, wenn der Zustand stumm bleibt
 * (Off/Cancelled/Landed — dieselbe Regel wie das Mini-Player-Badge,
 * P1-10). Seit C1 wird auch [DropSyncState.Failed] sichtbar.
 */
fun dropStatusLine(
    state: DropSyncState,
    nowElapsedRealtimeMs: Long,
): DropStatusLine? =
    when (state) {
        is DropSyncState.Planned -> {
            DropStatusLine.Ready(
                songTitle = state.songTitle,
                markerLabel = state.markerLabel,
                remainingMs = remainingTo(state.targetElapsedRealtimeMs, nowElapsedRealtimeMs),
                mode = state.mode,
                chain = state.chain,
            )
        }

        is DropSyncState.Armed -> {
            DropStatusLine.Ready(
                songTitle = state.plan.songTitle,
                markerLabel = state.plan.markerLabel,
                remainingMs = remainingTo(state.plan.targetElapsedRealtimeMs, nowElapsedRealtimeMs),
                mode = state.plan.mode,
                chain = state.plan.chain,
            )
        }

        is DropSyncState.BestEffort -> {
            DropStatusLine.BestEffort(state.reason)
        }

        is DropSyncState.Overridden -> {
            DropStatusLine.Overridden
        }

        is DropSyncState.Failed -> {
            DropStatusLine.Failed(state.reason)
        }

        else -> {
            null
        }
    }

private fun remainingTo(
    targetElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
): Long = (targetElapsedRealtimeMs - nowElapsedRealtimeMs).coerceAtLeast(0L)

/** C16: Trenner der Kettenzeile (Konsolen- und Badge-Sprache). */
internal const val CHAIN_ARROW = " → "

/**
 * Dauer als Uhrzeit ("1:27", "1:02:03"); geteilt von Statuszeile,
 * Mini-Player-Badge und Zeitachse (eine Formatierung, eine Sprache).
 */
internal fun formatClockMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
