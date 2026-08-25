package com.dropsync.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zielauswertung (Entscheidung 14, E4). Die Regeln liegen im Kern; hier
 * wird die Uebersetzung von Fusions Millikilogramm-Modell geprueft sowie
 * die Auswahl des besten Satzes.
 */
class TargetEvaluatorTest {
    private val target =
        ExerciseTarget(
            exerciseId = 1L,
            targetWeightMilliKg = 100_000L,
            targetReps = 5,
            updatedAtEpochMs = 0L,
        )

    private fun set(
        weightMilliKg: Long,
        reps: Int,
        id: Long = 1L,
        loggedAtEpochMs: Long = 0L,
    ) = FlatSet(
        id = id,
        exerciseId = 1L,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs = loggedAtEpochMs,
    )

    @Test
    fun `ziel braucht gewicht und reps`() {
        // Genau das Beispiel aus E4: 60 kg x 12 hat mehr Volumen als
        // 100 kg x 5, erfuellt das Ziel aber nicht.
        assertFalse(TargetEvaluator.evaluate(target, listOf(set(60_000L, 12))).reached)
        assertTrue(TargetEvaluator.evaluate(target, listOf(set(100_000L, 5))).reached)
    }

    @Test
    fun `gewicht allein genuegt nicht`() {
        assertFalse(TargetEvaluator.evaluate(target, listOf(set(120_000L, 3))).reached)
    }

    @Test
    fun `reps allein genuegen nicht`() {
        assertFalse(TargetEvaluator.evaluate(target, listOf(set(90_000L, 10))).reached)
    }

    @Test
    fun `ueberschreitung erfuellt das ziel`() {
        assertTrue(TargetEvaluator.evaluate(target, listOf(set(105_000L, 8))).reached)
    }

    /**
     * Der Grund fuer die "bester Satz"-Regel (E4): Nach einem leichteren
     * Abschlusssatz darf der Status nicht zurueckfallen.
     */
    @Test
    fun `leichter abschlusssatz nimmt das ziel nicht zurueck`() {
        val sets =
            listOf(
                set(100_000L, 5, id = 1, loggedAtEpochMs = 1_000),
                set(60_000L, 12, id = 2, loggedAtEpochMs = 2_000),
            )
        val status = TargetEvaluator.evaluate(target, sets)
        assertTrue(status.reached)
        assertEquals(100_000L, status.bestWeightMilliKg)
        assertEquals(5, status.bestReps)
    }

    @Test
    fun `bester satz bei gleichem gewicht hat mehr reps`() {
        val sets =
            listOf(
                set(100_000L, 5, id = 1),
                set(100_000L, 8, id = 2),
            )
        val status = TargetEvaluator.evaluate(target, sets)
        assertEquals(8, status.bestReps)
    }

    @Test
    fun `ohne saetze ist nichts erreicht`() {
        val status = TargetEvaluator.evaluate(target, emptyList())
        assertFalse(status.reached)
        assertEquals(0f, status.progress, 0f)
        assertNull(status.bestWeightMilliKg)
        assertNull(status.bestReps)
    }

    @Test
    fun `fortschritt folgt dem gewicht und ist begrenzt`() {
        assertEquals(0.5f, TargetEvaluator.evaluate(target, listOf(set(50_000L, 3))).progress, 0.001f)
        // Ueber dem Ziel bleibt der Fortschritt bei 1 — der Balken laeuft
        // nicht ueber.
        assertEquals(1f, TargetEvaluator.evaluate(target, listOf(set(150_000L, 3))).progress, 0f)
    }

    @Test
    fun `krummes zielgewicht bleibt grammgenau`() {
        val krumm = target.copy(targetWeightMilliKg = 92_501L)
        // Ein Gramm zu wenig ist nicht erreicht.
        assertFalse(TargetEvaluator.evaluate(krumm, listOf(set(92_500L, 5))).reached)
        assertTrue(TargetEvaluator.evaluate(krumm, listOf(set(92_501L, 5))).reached)
    }

    @Test
    fun `ziel ohne reps oder gewicht ist kein ziel`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseTarget(exerciseId = 1L, targetWeightMilliKg = 100_000L, targetReps = 0, updatedAtEpochMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseTarget(exerciseId = 1L, targetWeightMilliKg = 0L, targetReps = 5, updatedAtEpochMs = 0L)
        }
    }
}
