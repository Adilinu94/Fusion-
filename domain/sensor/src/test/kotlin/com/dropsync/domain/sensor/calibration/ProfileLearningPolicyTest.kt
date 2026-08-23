package com.dropsync.domain.sensor.calibration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Umbauplan Phase 7.5: Rollback-Regel. Zwei aufeinanderfolgende Sets mit
 * >= 3 Reps Abweichung loesen ein Rollback aus; ein einzelner Ausreisser
 * oder kleinere Abweichungen nicht.
 */
class ProfileLearningPolicyTest {
    @Test
    fun `ein einzelnes schlechtes Set loest kein Rollback aus`() {
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(5)))
    }

    @Test
    fun `zwei schlechte Sets in Folge loesen ein Rollback aus`() {
        assertTrue(ProfileLearningPolicy.shouldRollback(listOf(3, 4)))
    }

    @Test
    fun `kleine Abweichungen loesen nie ein Rollback aus`() {
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(2, 2, 2)))
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(0, 1, 2)))
    }

    @Test
    fun `Grenzwert zaehlt als schlecht`() {
        assertTrue(ProfileLearningPolicy.shouldRollback(listOf(3, 3)))
    }

    @Test
    fun `ein gutes Set zwischen schlechten verhindert das Rollback`() {
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(5, 0, 5)))
    }

    @Test
    fun `nur die letzten beiden Sets zaehlen`() {
        assertTrue(ProfileLearningPolicy.shouldRollback(listOf(0, 0, 5, 4)))
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(5, 5, 5, 0, 3)))
    }

    @Test
    fun `leere und zu kurze Liste loesen nichts aus`() {
        assertFalse(ProfileLearningPolicy.shouldRollback(emptyList()))
        assertFalse(ProfileLearningPolicy.shouldRollback(listOf(9)))
    }
}
