package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 (RC-18, Stufe 1): Die ROM-/Tempo-Scores sind einseitig. Ermuedung
 * (kleinere Prominenz, laengere Dauer) wird weit toleriert, Schwung
 * (groesser/schneller als erwartet) eng.
 *
 * Abweichung zur Plan-Skizze: Die Toleranzen sind als Deltas zur alten
 * 1.0 gelesen (1.45/0.80). Mit den woertlichen 0.45/0.20 waere die
 * Bewertung in BEIDEN Richtungen strenger als vorher geworden und haette
 * die Korpus-Gates gebrochen — der Plan verlangt aber "lockert in der
 * Ermuedungsrichtung und strafft nur gegen oben". Folge: Die
 * Ermuedungsseite kann (bei positiven Messgroessen) nicht mehr 0 erreichen
 * (bei ratio 0 bleibt 1 - 1/1.45 = 0.31); die verdaechtige Seite schon.
 */
class QualityScorerAsymmetryTest {
    private fun scorer() =
        QualityScorer(
            expectedProminence = 60.0,
            expectedDurationMs = 1_200.0,
            fatigueTolerance = 1.45,
            suspiciousTolerance = 0.80,
        )

    @Test
    fun `minus 30 prozent prominence gibt hoeheren score als plus 30 prozent`() {
        val s = scorer()
        val fatigue =
            s.score(correlation = 1.0, prominence = 60.0 * 0.7, durationMs = 1_200, durationRatio = 0.5)
        val suspicious =
            s.score(correlation = 1.0, prominence = 60.0 * 1.3, durationMs = 1_200, durationRatio = 0.5)
        assertTrue(
            "Ermuedung (${fatigue.romScore}) muss besser bewertet werden als Schwung (${suspicious.romScore})",
            fatigue.romScore > suspicious.romScore,
        )
        assertEquals(1.0 - 0.3 / 1.45, fatigue.romScore, 1e-9)
        assertEquals(1.0 - 0.3 / 0.80, suspicious.romScore, 1e-9)
    }

    @Test
    fun `plus 30 prozent dauer gibt hoeheren score als minus 30 prozent`() {
        // Fuer die Dauer ist die Ermuedungsrichtung invertiert: laenger ist
        // normal, kuerzer ist verdaechtig.
        val s = scorer()
        val fatigue =
            s.score(correlation = 1.0, prominence = 60.0, durationMs = 1_560, durationRatio = 0.5)
        val suspicious =
            s.score(correlation = 1.0, prominence = 60.0, durationMs = 840, durationRatio = 0.5)
        assertTrue(
            "laengere Rep (${fatigue.tempoScore}) muss besser bewertet werden als kuerzere (${suspicious.tempoScore})",
            fatigue.tempoScore > suspicious.tempoScore,
        )
        assertEquals(1.0 - 0.3 / 1.45, fatigue.tempoScore, 1e-9)
        assertEquals(1.0 - 0.3 / 0.80, suspicious.tempoScore, 1e-9)
    }

    @Test
    fun `die verdaechtige seite gibt ausserhalb der toleranz 0`() {
        val s = scorer()
        // Verdaechtig: deutlich groesser (Prominenz) bzw. deutlich schneller
        // (Dauer) als erwartet.
        val spike = s.score(correlation = 1.0, prominence = 120.0, durationMs = 1_200, durationRatio = 0.5)
        assertEquals("doppelte Prominenz ist Schwung", 0.0, spike.romScore, 1e-9)
        val rushed = s.score(correlation = 1.0, prominence = 60.0, durationMs = 240, durationRatio = 0.5)
        assertEquals("viermal zu schnell ist Schwung", 0.0, rushed.tempoScore, 1e-9)
    }

    @Test
    fun `die ermuedungsseite bleibt auch am extrem belastbar`() {
        // Plan-Skizze sprach von "beide Extreme geben 0"; mit der weiten
        // Ermuedungstoleranz ist das fuer positive Messgroessen nicht
        // erreichbar (und genau das ist gewollt: eine extrem kleine/langsame
        // Rep ist Ermuedung, kein Formfehler).
        val s = scorer()
        val extreme = s.score(correlation = 1.0, prominence = 0.0, durationMs = 2_640, durationRatio = 0.5)
        assertTrue("Ermuedungsseite darf nicht auf 0 fallen", extreme.romScore > 0.0)
        assertTrue("Ermuedungsseite darf nicht auf 0 fallen", extreme.tempoScore > 0.0)
        assertEquals(1.0 - 1.0 / 1.45, extreme.romScore, 1e-9)
        assertEquals(1.0 - 1.2 / 1.45, extreme.tempoScore, 1e-9)
    }

    @Test
    fun `exakte erwartung ergibt volle rom- und tempo-scores`() {
        val s = scorer()
        val exact = s.score(correlation = 1.0, prominence = 60.0, durationMs = 1_200, durationRatio = 0.5)
        assertEquals(1.0, exact.romScore, 1e-9)
        assertEquals(1.0, exact.tempoScore, 1e-9)
        assertEquals(1.0, exact.score, 1e-9)
    }
}
