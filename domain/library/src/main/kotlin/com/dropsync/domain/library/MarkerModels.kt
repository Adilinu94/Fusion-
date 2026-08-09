package com.dropsync.domain.library

/**
 * Fachliche Identitaet eines Songs fuer die Markerzuordnung (Bauplan 5.1).
 * Der Hash stammt ausschliesslich aus dem externen Analyzer; die App
 * berechnet nie selbst einen SHA-256.
 */
data class SongFingerprint(
    val mediaStoreId: Long,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    /** Optionaler, extern erzeugter und bereits gespeicherter Hash. */
    val knownSha256: String?,
)

/** Ein Marker aus dem Importdokument (Bauplan 6.1). */
data class ImportedMarker(
    val label: String,
    val positionMs: Long,
)

/** Ein Track aus dem Importdokument mit seinen Markern (Bauplan 6.1). */
data class ImportedTrack(
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sha256: String?,
    val markers: List<ImportedMarker>,
)

/** Ergebnis der Zuordnung eines Import-Tracks zu lokalen Songs (5.1). */
sealed interface MatchResult {
    /** Eindeutig zugeordnet; [method] dokumentiert die Zuordnungsstufe. */
    data class Matched(
        val mediaStoreId: Long,
        val method: MatchMethod,
    ) : MatchResult

    /** Kein Treffer: als nicht zugeordnet speichern. */
    data object Unmatched : MatchResult

    /** Mehrere Treffer: App darf nicht raten (5.1); manuelle Auswahl noetig. */
    data class Ambiguous(
        val candidateIds: List<Long>,
    ) : MatchResult
}

enum class MatchMethod { HASH, METADATA_STRICT, METADATA_LOOSE }
