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

    /**
     * Baut den **knappen** Report-Text fuer die Snackbar.
     *
     * **Was hier drin ist und was nicht (2026-09-27, Befund 12.3, UI-Hebel
     * 3):** der volle Report stand als
     *
     * > "Satz beendet: 12 erkannt · Rate 51,3 Hz · 2 Aussetzer · 4 ZuPT ·
     * > 3 abgelehnt (Beschleunigung 2, Template 1)"
     *
     * in einer Snackbar, die mit "Satz gespeichert" + Undo, dem
     * Learning-Event, Train-Fehlern und dem DropSync-Skip um die
     * Aufmerksamkeit konkurriert. Fuenf Quellen auf **einem** Host: bei
     * fuenf Saetzen in 90 Sekunden (ein realistischer Satz-Durchschnitt
     * bei 15 Saetzen) wird eine der Quellen verdraengt — und die
     * realistischste ist ausgerechnet der **Undo**-Knopf.
     *
     * Von den Angaben ist fuer einen Trainierenden **eine**
     * handlungsrelevant: **Aussetzer** (Paketverlueufe, also Luecken in
     * der Messung). Der Rest ist Diagnose:
     *
     * - **Rate 51,3 Hz** — was ist das fuer jemanden, der Bankdruecken
     *   macht? Nichts. Kein Normalnutzer weiss, was eine Abtastrate im
     *   Kontext eines Satzes bedeutet.
     * - **ZuPT** — ein Akronym ohne Expansion.
     * - **abgelehnt (Beschleunigung, Template)** — reines ML-Interna. Gehoert
     *   ins Diagnose-Panel, wo es bereits existiert
     *   (`SettingsScreen.kt:1257-1301`, `DiagnosticsLastSetSection`).
     *
     * Der volle Report bleibt erhalten — [format] wird fuer das
     * Diagnose-Panel weiterverwendet, und diese Funktion **ersetzt** ihn
     * nur in der Snackbar.
     *
     * @return `null`, wenn es nichts zu sagen gibt. Dann zeigt die
     *   Snackbar nur "Satz gespeichert" — kein Rauschen.
     */
    fun formatConcise(
        report: SetDiagnostics,
        gapsTemplate: String,
    ): String? {
        if (report.largeGapCount == 0) return null
        return String.format(Locale.ROOT, gapsTemplate, report.largeGapCount)
    }
}
