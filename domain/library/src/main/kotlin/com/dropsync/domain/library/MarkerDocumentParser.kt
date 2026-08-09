package com.dropsync.domain.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Ergebnis des strukturellen Parsens einer Markerdatei (Bauplan 6.1). */
sealed interface ParsedMarkerDocument {
    data class Success(
        val schemaVersion: Int,
        val tracks: List<ImportedTrack>,
    ) : ParsedMarkerDocument

    /** Strukturfehler; inhaltliche Regeln prueft der [ImportValidator]. */
    data class Malformed(
        val reason: String,
    ) : ParsedMarkerDocument
}

/**
 * Wandelt das JSON-Dokument des externen Analyzers in [ImportedTrack]-Werte
 * um (Bauplan 6.1). Nur Strukturpruefung: fehlende oder falsch typisierte
 * Felder fuehren zu [ParsedMarkerDocument.Malformed]; Wertregeln (negative
 * Zeiten, doppelte Positionen, Hashformat) gehoeren zum [ImportValidator].
 */
object MarkerDocumentParser {
    /** Groessenlimit der Importdatei (6.1); der Leser bricht darueber ab. */
    const val MAX_DOCUMENT_BYTES: Long = 5L * 1024 * 1024

    fun parse(jsonText: String): ParsedMarkerDocument {
        val root =
            try {
                Json.parseToJsonElement(jsonText) as? JsonObject
                    ?: return ParsedMarkerDocument.Malformed("Wurzel ist kein Objekt")
            } catch (e: Exception) {
                return ParsedMarkerDocument.Malformed("Kein gueltiges JSON")
            }

        val schemaVersion =
            (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
                ?: return ParsedMarkerDocument.Malformed("schemaVersion fehlt oder ist keine Zahl")
        val tracksJson =
            root["tracks"] as? JsonArray
                ?: return ParsedMarkerDocument.Malformed("tracks fehlt oder ist keine Liste")

        val tracks = mutableListOf<ImportedTrack>()
        tracksJson.forEachIndexed { index, element ->
            val obj =
                element as? JsonObject
                    ?: return ParsedMarkerDocument.Malformed("tracks[$index] ist kein Objekt")
            tracks +=
                parseTrack(obj)
                    ?: return ParsedMarkerDocument.Malformed("tracks[$index] hat fehlende oder ungueltige Felder")
        }
        return ParsedMarkerDocument.Success(schemaVersion, tracks)
    }

    private fun parseTrack(obj: JsonObject): ImportedTrack? {
        val relativePath = obj.stringOrNull("relativePath") ?: return null
        val displayName = obj.stringOrNull("displayName") ?: return null
        val sizeBytes = (obj["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: return null
        val durationMs = (obj["durationMs"] as? JsonPrimitive)?.longOrNull ?: return null
        // sha256 ist optional; nur der externe Analyzer erzeugt ihn (5.1).
        val sha256 =
            when (val raw = obj["sha256"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (raw.isString) raw.content else return null
                else -> return null
            }
        val markersJson = obj["markers"] as? JsonArray ?: return null
        val markers =
            markersJson.map { element ->
                val marker = element as? JsonObject ?: return null
                ImportedMarker(
                    label = marker.stringOrNull("label") ?: return null,
                    positionMs = (marker["positionMs"] as? JsonPrimitive)?.longOrNull ?: return null,
                )
            }
        return ImportedTrack(
            relativePath = relativePath,
            displayName = displayName,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            sha256 = sha256,
            markers = markers,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
