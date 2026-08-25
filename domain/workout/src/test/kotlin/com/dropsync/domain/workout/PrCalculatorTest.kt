package com.dropsync.domain.workout

import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-Berechnung an der Kern-Grenze (Schritt 5, CONTEXT E1/E6). Der Kern
 * liefert Kilogramm als Double und seine eigenen Enums; hier wird
 * geprueft, dass die Uebersetzung nach Fusions Millikilogramm-Modell
 * grammgenau ist und die Enum-Zuordnung stimmt.
 */
class PrCalculatorTest {
    private fun segment(
        loadMilliKg: Long,
        reps: Int,
        completedAtEpochMs: Long,
        loadMultiplier: Int = 1,
        sessionId: Long = 1L,
        sessionStartedAtEpochMs: Long = 0L,
        clusterId: Long = 1L,
    ) = QualifiedSegment(
        sessionId = sessionId,
        sessionStartedAtEpochMs = sessionStartedAtEpochMs,
        clusterId = clusterId,
        completedAtEpochMs = completedAtEpochMs,
        loadMilliKg = loadMilliKg,
        loadMultiplier = loadMultiplier,
        reps = reps,
    )

    @Test
    fun `leere historie erzeugt keine rekorde`() {
        assertTrue(PrCalculator.computeAll(emptyList()).isEmpty())
    }

    @Test
    fun `hoechste last beruecksichtigt den multiplikator`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(loadMilliKg = 60_000L, reps = 8, completedAtEpochMs = 1, loadMultiplier = 2),
                    segment(loadMilliKg = 100_000L, reps = 5, completedAtEpochMs = 2, clusterId = 2),
                ),
            )
        val highest = records.first { it.type == PrType.HIGHEST_LOAD }
        // 60 kg * 2 = 120 kg schlaegt 100 kg.
        assertEquals(120_000L, highest.valueLong)
        assertEquals(PrValueUnit.MILLI_KG, highest.valueUnit)
        assertEquals(1L, highest.achievedAtEpochMs)
        assertNull(highest.comparableLoadMilliKg)
    }

    @Test
    fun `gleichstand haelt das frueheste segment`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(loadMilliKg = 100_000L, reps = 5, completedAtEpochMs = 10, clusterId = 1),
                    segment(loadMilliKg = 100_000L, reps = 3, completedAtEpochMs = 20, clusterId = 2),
                ),
            )
        assertEquals(1L, records.first { it.type == PrType.HIGHEST_LOAD }.achievedClusterId)
    }

    @Test
    fun `hoechstes sessionvolumen summiert je session`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(loadMilliKg = 100_000L, reps = 5, completedAtEpochMs = 1, sessionId = 1),
                    segment(loadMilliKg = 100_000L, reps = 5, completedAtEpochMs = 2, sessionId = 1, clusterId = 2),
                    segment(
                        loadMilliKg = 140_000L,
                        reps = 6,
                        completedAtEpochMs = 3,
                        sessionId = 2,
                        sessionStartedAtEpochMs = 1_000,
                        clusterId = 3,
                    ),
                ),
            )
        val volume = records.first { it.type == PrType.HIGHEST_SESSION_VOLUME }
        // 2 * 500 kg = 1000 kg schlaegt 840 kg.
        assertEquals(1L, volume.achievedSessionId)
        assertEquals(1_000_000L, volume.valueLong)
        assertNull(volume.achievedClusterId)
    }

    @Test
    fun `meiste reps erzeugt einen rekord je lastwert`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(loadMilliKg = 100_000L, reps = 5, completedAtEpochMs = 1, clusterId = 1),
                    segment(loadMilliKg = 100_000L, reps = 8, completedAtEpochMs = 2, clusterId = 2),
                    segment(loadMilliKg = 90_000L, reps = 12, completedAtEpochMs = 3, clusterId = 3),
                ),
            )
        val atLoad = records.filter { it.type == PrType.MOST_REPS_AT_LOAD }
        assertEquals(2, atLoad.size)
        val at100 = atLoad.first { it.comparableLoadMilliKg == 100_000L }
        assertEquals(8L, at100.valueLong)
        // Reps sind Reps, nicht Millikilogramm — die Einheit muss mitwandern.
        assertEquals(PrValueUnit.REPS, at100.valueUnit)
        assertEquals(12L, atLoad.first { it.comparableLoadMilliKg == 90_000L }.valueLong)
    }

    /**
     * Der Grund fuer diese Testklasse: Krumme Lasten muessen den
     * Double-Vertrag des Kerns grammgenau ueberstehen.
     */
    @Test
    fun `krumme last bleibt grammgenau`() {
        val records =
            PrCalculator.computeAll(
                listOf(segment(loadMilliKg = 92_501L, reps = 3, completedAtEpochMs = 1)),
            )
        assertEquals(92_501L, records.first { it.type == PrType.HIGHEST_LOAD }.valueLong)
        assertEquals(277_503L, records.first { it.type == PrType.HIGHEST_SESSION_VOLUME }.valueLong)
        assertEquals(
            92_501L,
            records.first { it.type == PrType.MOST_REPS_AT_LOAD }.comparableLoadMilliKg,
        )
    }
}
