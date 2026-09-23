package com.dropsync.feature.workout

import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SetDiagnostics
import java.util.Locale

/**
 * RC-7/RC-17: Text des Satz-Reports nach dem Stop. Bewusst als reine
 * Funktionen ohne Compose/Ressourcen — die lokalisierten Templates und
 * Labels kommen als Parameter, damit die Logik testbar bleibt.
 */
internal object SetReportText {
    /**
     * Sortiert die Ablehnungszaehler fuer die Anzeige: haeufigster
     * Mechanismus zuerst, bei Gleichstand feste Enum-Reihenfolge.
     */
    fun rejectionSummary(counts: Map<RepRejectionReason, Int>): List<Pair<RepRejectionReason, Int>> =
        counts.entries
            .filter { it.value > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<RepRejectionReason, Int>> { it.value }
                    .thenBy { it.key.ordinal },
            ).map { it.key to it.value }

    /**
     * Baut den Report-Text.
     *
     * @param coreTemplate z. B. "%1$d erkannt · Rate %2$s Hz · %3$d Aussetzer · %4$d ZuPT"
     * @param rejectedTemplate z. B. "%1$d abgelehnt (%2$s)"
     * @param reasonLabel liefert das lokalisierte Label eines Mechanismus.
     */
    fun format(
        report: SetDiagnostics,
        coreTemplate: String,
        rejectedTemplate: String,
        reasonLabel: (RepRejectionReason) -> String,
    ): String {
        val rate = String.format(Locale.ROOT, "%.1f", report.measuredSampleRateHz)
        val core =
            String.format(
                Locale.ROOT,
                coreTemplate,
                report.countedReps,
                rate,
                report.largeGapCount,
                report.zuptBiasUpdates,
            )
        if (report.totalRejections == 0) return core
        val reasons =
            rejectionSummary(report.rejectionCounts).joinToString(", ") { (reason, count) ->
                "${reasonLabel(reason)} $count"
            }
        return core + " · " + String.format(Locale.ROOT, rejectedTemplate, report.totalRejections, reasons)
    }
}
