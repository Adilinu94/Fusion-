package com.dropsync.domain.library

import kotlin.random.Random

/**
 * Ein Kandidat fuer das intelligente Shuffle (Musik-Workout-Plan A5):
 * die fuer die Gewichtung noetigen Werte aus `play_stats`/`favorites`.
 * [lastPlayedAtEpochMs] ist null, wenn der Titel noch nie gespielt wurde.
 */
data class ShuffleCandidate(
    val songId: Long,
    val playCount: Int,
    val lastPlayedAtEpochMs: Long?,
    val isFavorite: Boolean,
)

/**
 * Intelligentes Shuffle (Musik-Workout-Plan A5) als reine, getestete
 * Funktion: gewichtet ueber `play_stats`/Favoriten und meidet zuletzt
 * Gespielte. Kein Android, keine Persistenz.
 *
 * Gewicht = Favoriten-Boost * Aktualitaets-Faktor * milder Haeufigkeits-
 * Boost. Der Aktualitaets-Faktor ist bei nie gespielten Titeln 1.0 und
 * faellt fuer gerade eben Gespielte gegen [MIN_RECENCY_FACTOR]. Die
 * Reihenfolge entsteht per gewichteter Ziehung ohne Zuruecklegen
 * (Efraimidis-Spirakis), deterministisch bei festem [Random].
 */
object SmartShuffle {
    /** Fenster, ueber das die "Kuerzlich gespielt"-Strafe ausklingt. */
    const val RECENCY_WINDOW_MS: Long = 6 * 60 * 60 * 1000L

    /** Untergrenze des Aktualitaets-Faktors: nie ganz ausschliessen. */
    const val MIN_RECENCY_FACTOR: Double = 0.05

    /** Favoriten wiegen doppelt. */
    const val FAVORITE_BOOST: Double = 2.0

    /** Deckel des milden Haeufigkeits-Boosts (Abwechslung bleibt gewahrt). */
    const val MAX_PLAY_COUNT_FOR_BOOST: Int = 20

    /** Zuwachs pro Wiedergabe bis zum Deckel. */
    const val PLAY_COUNT_BOOST_STEP: Double = 0.02

    /** Reines Gewicht eines Kandidaten; groesser = wahrscheinlicher vorn. */
    fun weightOf(
        candidate: ShuffleCandidate,
        nowEpochMs: Long,
    ): Double {
        val favorite = if (candidate.isFavorite) FAVORITE_BOOST else 1.0
        val recency =
            when (val last = candidate.lastPlayedAtEpochMs) {
                null -> {
                    1.0
                }

                else -> {
                    val elapsed = (nowEpochMs - last).coerceAtLeast(0)
                    (elapsed.toDouble() / RECENCY_WINDOW_MS).coerceIn(MIN_RECENCY_FACTOR, 1.0)
                }
            }
        val plays = 1.0 + minOf(candidate.playCount, MAX_PLAY_COUNT_FOR_BOOST) * PLAY_COUNT_BOOST_STEP
        return favorite * recency * plays
    }

    /**
     * Gewichtete Zufallsreihenfolge der Song-IDs ohne Wiederholung. Bei
     * festem [random] deterministisch (fuer Tests). Leere Eingabe -> leer.
     */
    fun order(
        candidates: List<ShuffleCandidate>,
        nowEpochMs: Long,
        random: Random = Random.Default,
    ): List<Long> {
        if (candidates.isEmpty()) return emptyList()
        return candidates
            .map { candidate ->
                val weight = weightOf(candidate, nowEpochMs).coerceAtLeast(MIN_KEY_WEIGHT)
                val u = random.nextDouble().coerceIn(MIN_UNIFORM, 1.0)
                // Efraimidis-Spirakis: key = u^(1/weight); groesser = frueher.
                candidate.songId to Math.pow(u, 1.0 / weight)
            }.sortedByDescending { it.second }
            .map { it.first }
    }

    private const val MIN_KEY_WEIGHT: Double = 1e-6
    private const val MIN_UNIFORM: Double = 1e-12
}
