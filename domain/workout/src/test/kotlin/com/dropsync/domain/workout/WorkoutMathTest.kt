package com.dropsync.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die Kern-Grenze ab (Schritt 5, CONTEXT E1/E6): Seit dem
 * Mathematik-Umzug laeuft jede Rechnung ueber `com.training.core`, das
 * Kilogramm als Double spricht. Diese Tests belegen, dass der Rundweg
 * Long -> Double -> Long verlustfrei ist und Fusions Vertrag (ganze
 * Millikilogramm nach aussen) unveraendert gilt.
 */
class WorkoutMathTest {
    @Test
    fun `nur multiplikator 1 und 2 sind gueltig`() {
        assertTrue(WorkoutMath.isValidLoadMultiplier(1))
        assertTrue(WorkoutMath.isValidLoadMultiplier(2))
        assertTrue(!WorkoutMath.isValidLoadMultiplier(0))
        assertTrue(!WorkoutMath.isValidLoadMultiplier(3))
    }

    @Test
    fun `effektive last multipliziert die implementlast`() {
        assertEquals(120_000L, WorkoutMath.effectiveLoadMilliKg(60_000L, 2))
        assertEquals(60_000L, WorkoutMath.effectiveLoadMilliKg(60_000L, 1))
    }

    @Test
    fun `ungueltiger multiplikator wirft`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutMath.effectiveLoadMilliKg(60_000L, 3)
        }
    }

    @Test
    fun `segmentvolumen ist effektive last mal reps`() {
        assertEquals(500_000L, WorkoutMath.segmentVolumeMilliKg(100_000L, 1, 5))
        assertEquals(1_000_000L, WorkoutMath.segmentVolumeMilliKg(50_000L, 2, 10))
    }

    @Test
    fun `segmentvolumen ohne reps wirft`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutMath.segmentVolumeMilliKg(100_000L, 1, 0)
        }
    }

    /**
     * Der eigentliche Grund fuer diese Testklasse: Ein einzelnes Gramm
     * darf auf dem Weg durch den Double-Vertrag des Kerns nicht
     * verschwinden.
     */
    @Test
    fun `einzelnes gramm uebersteht die kern-grenze`() {
        assertEquals(1L, WorkoutMath.effectiveLoadMilliKg(1L, 1))
        assertEquals(3L, WorkoutMath.segmentVolumeMilliKg(1L, 1, 3))
    }

    @Test
    fun `krumme lasten summieren grammgenau`() {
        // 92,5 kg und 0,001 kg — beides als Binaer-Double nicht exakt.
        val segments =
            listOf(
                SegmentInput(externalLoadMilliKgPerImplement = 92_500L, loadMultiplier = 1, reps = 3),
                SegmentInput(externalLoadMilliKgPerImplement = 1L, loadMultiplier = 1, reps = 1),
            )
        assertEquals(277_501L, WorkoutMath.clusterVolumeMilliKg(segments))
    }

    @Test
    fun `clustervolumen zaehlt nur qualifizierte segmente`() {
        val segments =
            listOf(
                SegmentInput(externalLoadMilliKgPerImplement = 100_000L, loadMultiplier = 1, reps = 5),
                // Nicht qualifiziert: keine Reps (Qualification.segmentQualifies).
                SegmentInput(externalLoadMilliKgPerImplement = 100_000L, loadMultiplier = 1, reps = null),
                // Nicht qualifiziert: keine Last.
                SegmentInput(externalLoadMilliKgPerImplement = null, loadMultiplier = 1, reps = 5),
            )
        assertEquals(500_000L, WorkoutMath.clusterVolumeMilliKg(segments))
    }

    @Test
    fun `leeres cluster hat volumen null`() {
        assertEquals(0L, WorkoutMath.clusterVolumeMilliKg(emptyList()))
    }

    @Test
    fun `kg-eingabe wird kaufmaennisch auf gramm gerundet`() {
        assertEquals(92_500L, WorkoutMath.roundKgInputToMilliKg("92,5"))
        assertEquals(92_500L, WorkoutMath.roundKgInputToMilliKg("92.5"))
        assertEquals(100_000L, WorkoutMath.roundKgInputToMilliKg(" 100 "))
        // HALF_UP auf der vierten Dezimalstelle.
        assertEquals(1_235L, WorkoutMath.roundKgInputToMilliKg("1.2345"))
    }

    @Test
    fun `geschaetztes 1rm nur fuer 1 bis 10 reps`() {
        // 100 kg * (1 + 5/30) = 116,667 kg
        assertEquals(116_667L, WorkoutMath.estimatedOneRmMilliKg(100_000L, 5))
        assertEquals(76_000L, WorkoutMath.estimatedOneRmMilliKg(60_000L, 8))
        assertNull(WorkoutMath.estimatedOneRmMilliKg(100_000L, 0))
        assertNull(WorkoutMath.estimatedOneRmMilliKg(100_000L, 11))
        assertNull(WorkoutMath.estimatedOneRmMilliKg(0L, 5))
    }

    @Test
    fun `formelversion bleibt stabil`() {
        // Wandert die Formel, muss die Version steigen (Datenexporte).
        assertEquals(1, WorkoutMath.ONE_RM_FORMULA_VERSION)
    }
}
