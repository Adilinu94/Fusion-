package com.dropsync.feature.workout

import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SetDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-17/RC-17: Format des Satz-Reports. Die Templates kommen live aus den
 * Ressourcen; hier stehen feste Test-Templates, damit die Logik (Rate-
 * Format, Anhang nur bei Ablehnungen, Sortierung der Mechanismen) ohne
 * Compose geprueft werden kann.
 */
class SetReportTextTest {
    private val core = "%1\$d erkannt | Rate %2\$s Hz | %3\$d Aussetzer | %4\$d ZuPT"
    private val rejected = "%1\$d abgelehnt (%2\$s)"

    @Test
    fun `report ohne ablehnungen zeigt nur den kern`() {
        val report =
            SetDiagnostics(
                countedReps = 8,
                measuredSampleRateHz = 49.83,
                largeGapCount = 1,
                zuptBiasUpdates = 2,
            )

        val text = SetReportText.format(report, core, rejected) { it.name }

        assertEquals("8 erkannt | Rate 49.8 Hz | 1 Aussetzer | 2 ZuPT", text)
    }

    @Test
    fun `report mit ablehnungen haengt die mechanismus-zerlegung an`() {
        val report =
            SetDiagnostics(
                countedReps = 5,
                measuredSampleRateHz = 50.0,
                rejectionCounts =
                    mapOf(
                        RepRejectionReason.QUALITY to 2,
                        RepRejectionReason.PHASE_VALIDATION to 1,
                    ),
            )

        val text = SetReportText.format(report, core, rejected) { it.name }

        assertEquals(
            "5 erkannt | Rate 50.0 Hz | 0 Aussetzer | 0 ZuPT · 3 abgelehnt (QUALITY 2, PHASE_VALIDATION 1)",
            text,
        )
    }

    @Test
    fun `mechanismen werden nach haeufigkeit sortiert`() {
        val summary =
            SetReportText.rejectionSummary(
                mapOf(
                    RepRejectionReason.QUALITY to 1,
                    RepRejectionReason.PHASE_VALIDATION to 3,
                    RepRejectionReason.TEMPLATE_MATCH to 0,
                ),
            )

        assertEquals(
            listOf(
                RepRejectionReason.PHASE_VALIDATION to 3,
                RepRejectionReason.QUALITY to 1,
            ),
            summary,
        )
    }
}
