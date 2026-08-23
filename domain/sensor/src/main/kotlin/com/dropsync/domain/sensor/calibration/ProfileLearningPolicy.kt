package com.dropsync.domain.sensor.calibration

/**
 * Umbauplan Phase 7.4/7.5: konservative Regeln fuer Profil-Promotion und
 * automatisches Rollback. Reine Funktion, damit die Entscheidungen JVM-
 * testbar sind, ohne Repository oder ViewModel zu benoetigen.
 */
object ProfileLearningPolicy {
    /** Anzahl validierter Sets, die ein Kandidat braucht, um aktiv zu werden. */
    const val PROMOTION_SETS = 3

    /** Ab dieser Abweichung (in Reps) gilt ein Set als klar daneben. */
    const val ROLLBACK_DIFF = 3

    /** So viele klar daneben liegende Sets in Folge loesen ein Rollback aus. */
    const val ROLLBACK_STREAK = 2

    /**
     * Liefert true, wenn die letzten [ROLLBACK_STREAK] Abweichungen alle
     * >= [ROLLBACK_DIFF] sind: Die aktive Revision erkennt offensichtlich
     * schlechter und sollte zur letzten guten Revision zurueckrollen.
     */
    fun shouldRollback(recentDiffs: List<Int>): Boolean {
        if (recentDiffs.size < ROLLBACK_STREAK) return false
        val tail = recentDiffs.takeLast(ROLLBACK_STREAK)
        return tail.all { it >= ROLLBACK_DIFF }
    }
}
