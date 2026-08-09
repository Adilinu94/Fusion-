package com.dropsync.domain.library

// Validierung des Markerimport-Dokuments (Bauplan 6.1, Schritt 6).
// Ein Import ist transaktional: ein einziger Regelverstoss lehnt das
// gesamte Dokument ab; es wird keine teilweise Datenmenge gespeichert.

const val SUPPORTED_IMPORT_SCHEMA_VERSION = 1
const val MAX_IMPORT_FILE_BYTES: Long = 5L * 1024 * 1024

private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

/** Ein einzelner, konkreter Ablehnungsgrund mit Kontext. */
data class ImportViolation(
    val trackDisplayName: String?,
    val reason: String,
)

sealed interface ImportValidation {
    data object Valid : ImportValidation

    data class Invalid(
        val violations: List<ImportViolation>,
    ) : ImportValidation
}

class ImportValidator {
    fun validate(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): ImportValidation {
        val violations = mutableListOf<ImportViolation>()

        if (schemaVersion != SUPPORTED_IMPORT_SCHEMA_VERSION) {
            // Versionspruefung kommt vor jeder Fachpruefung (Schritt 6.1);
            // bei fremder Version wird nichts weiter geprueft.
            return ImportValidation.Invalid(
                listOf(ImportViolation(null, "Unbekannte schemaVersion: $schemaVersion")),
            )
        }

        if (tracks.isEmpty()) {
            violations += ImportViolation(null, "Dokument enthaelt keine Tracks")
        }

        for (track in tracks) {
            val name = track.displayName
            if (name.isBlank()) {
                violations += ImportViolation(name, "Leerer Dateiname")
            }
            if (track.sizeBytes <= 0) {
                violations += ImportViolation(name, "Ungueltige Groesse: ${track.sizeBytes}")
            }
            if (track.durationMs <= 0) {
                violations += ImportViolation(name, "Ungueltige Dauer: ${track.durationMs}")
            }
            val hash = track.sha256
            if (hash != null && !SHA256_REGEX.matches(hash)) {
                violations += ImportViolation(name, "Ungueltiger SHA-256-Hash")
            }
            if (track.markers.isEmpty()) {
                violations += ImportViolation(name, "Track ohne Marker")
            }
            val seenPositions = mutableSetOf<Long>()
            for (marker in track.markers) {
                if (marker.label.isBlank()) {
                    violations += ImportViolation(name, "Leeres Marker-Label")
                }
                if (marker.positionMs < 0) {
                    violations += ImportViolation(name, "Negative Markerposition: ${marker.positionMs}")
                }
                if (marker.positionMs > track.durationMs) {
                    violations +=
                        ImportViolation(
                            name,
                            "Marker ausserhalb der Songdauer: ${marker.positionMs} > ${track.durationMs}",
                        )
                }
                if (!seenPositions.add(marker.positionMs)) {
                    violations +=
                        ImportViolation(
                            name,
                            "Doppelte Markerposition: ${marker.positionMs}",
                        )
                }
            }
        }

        return if (violations.isEmpty()) ImportValidation.Valid else ImportValidation.Invalid(violations)
    }
}
