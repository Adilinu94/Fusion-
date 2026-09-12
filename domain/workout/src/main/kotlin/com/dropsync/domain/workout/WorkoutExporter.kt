package com.dropsync.domain.workout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trainingsdaten-Export: das flache Satz-Log
 * und die Uebungsliste als portables Dokument. Die App ist offline; der
 * Export ist der einzige Weg, das Tagebuch bei einem Geraetewechsel zu
 * retten.
 *
 * Reines JVM (kein Android-Import); das Schreiben der Datei uebernimmt
 * :feature:settings per Storage Access Framework.
 */

@Serializable
data class ExportedSet(
    /** Uebungs-Slug statt interner ID: bleibt ueber Installationen stabil. */
    val exerciseSlug: String,
    val exerciseName: String,
    val weightKg: Double,
    val reps: Int,
    val loggedAtEpochMs: Long,
)

@Serializable
data class WorkoutExport(
    /** Formatversion fuer kuenftige Importe; 1 = erstes Format. */
    val schemaVersion: Int,
    val exportedAtEpochMs: Long,
    val sets: List<ExportedSet>,
)

/** Zielformat des Exports. */
enum class ExportFormat { JSON, CSV }

object WorkoutExporter {
    const val SCHEMA_VERSION = 1

    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    /**
     * Erzeugt das Exportdokument. [exercises] mappt Uebungs-ID auf
     * Slug+Name, damit der Export lesbar und installationsstabil bleibt.
     */
    fun buildDocument(
        sets: List<FlatSet>,
        exercises: Map<Long, Pair<String, String>>,
        exportedAtEpochMs: Long,
    ): WorkoutExport =
        WorkoutExport(
            schemaVersion = SCHEMA_VERSION,
            exportedAtEpochMs = exportedAtEpochMs,
            sets =
                sets.map { set ->
                    val (slug, name) =
                        exercises[set.exerciseId]
                            ?: ("unknown-${set.exerciseId}" to "Unbekannte Übung")
                    ExportedSet(
                        exerciseSlug = slug,
                        exerciseName = name,
                        weightKg = set.weightMilliKg / 1_000_000.0,
                        reps = set.reps,
                        loggedAtEpochMs = set.loggedAtEpochMs,
                    )
                },
        )

    /** JSON-Dokument (vollstaendig, fuer spaeteren Reimport). */
    fun toJson(document: WorkoutExport): String = json.encodeToString(document)

    /**
     * CSV nur der Saetze (Tabellenkalkulation). RFC 4180: Felder mit
     * Komma/Anfuehrungszeichen/Zeilenumbruch werden in Anfuehrungszeichen
     * gesetzt und interne Anfuehrungszeichen verdoppelt.
     */
    fun toCsv(document: WorkoutExport): String {
        val header = "exercise_slug,exercise_name,weight_kg,reps,logged_at_epoch_ms"
        val rows =
            document.sets.joinToString("\r\n") { set ->
                listOf(
                    set.exerciseSlug,
                    set.exerciseName,
                    formatWeight(set.weightKg),
                    set.reps.toString(),
                    set.loggedAtEpochMs.toString(),
                ).joinToString(",") { field -> escapeCsv(field) }
            }
        return if (rows.isEmpty()) "$header\r\n" else "$header\r\n$rows\r\n"
    }

    private fun escapeCsv(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    private fun formatWeight(weightKg: Double): String {
        // Millikilogramm-Basis: maximal drei Nachkommastellen, keine
        // Gleitkomma-Artefakte (0.07500000000000001). Locale.ROOT erzwingt
        // den Dezimalpunkt (deutsche System-Locale nutzt Komma).
        val milli = Math.round(weightKg * 1_000).toInt()
        return when {
            milli % 1_000 == 0 -> (milli / 1_000).toString()
            milli % 100 == 0 -> java.lang.String.format(java.util.Locale.ROOT, "%.1f", milli / 1_000.0)
            milli % 10 == 0 -> java.lang.String.format(java.util.Locale.ROOT, "%.2f", milli / 1_000.0)
            else -> java.lang.String.format(java.util.Locale.ROOT, "%.3f", milli / 1_000.0)
        }
    }
}
