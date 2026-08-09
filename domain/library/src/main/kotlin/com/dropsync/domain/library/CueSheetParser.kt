package com.dropsync.domain.library

/**
 * Ein virtueller Track aus einem CUE-Sheet (Plan Phase 3). Zeiten sind
 * Millisekunden ab dem Anfang der referenzierten Audiodatei; [endMs] ist
 * der Beginn des Folgetracks derselben Datei oder null fuer den letzten
 * Track (dann bis Dateiende).
 */
data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val file: String,
    val startMs: Long,
    val endMs: Long?,
)

/** Ergebnis eines geparsten CUE-Sheets. */
data class CueSheet(
    val albumTitle: String?,
    val albumPerformer: String?,
    val tracks: List<CueTrack>,
)

/** Struktur- oder Erfolgsergebnis des CUE-Parsers. */
sealed interface ParsedCueSheet {
    data class Success(
        val sheet: CueSheet,
    ) : ParsedCueSheet

    data class Malformed(
        val reason: String,
    ) : ParsedCueSheet
}

/**
 * Parser fuer CUE-Sheets (reine JVM-Logik, Plan Phase 3). Unterstuetzt
 * Alben mit einer grossen Audiodatei ebenso wie Mehrdatei-Sheets (jeder
 * Track behaelt seine FILE-Referenz). INDEX 01 legt den Startpunkt fest;
 * ein optionaler Pregap (INDEX 00) wird ignoriert. Das Zeitformat ist
 * MM:SS:FF mit 75 Frames pro Sekunde (Red-Book-Standard).
 */
object CueSheetParser {
    private const val FRAMES_PER_SECOND = 75

    fun parse(text: String): ParsedCueSheet {
        var albumTitle: String? = null
        var albumPerformer: String? = null
        var currentFile: String? = null
        var sawTrack = false

        val tracks = mutableListOf<MutableCueTrack>()
        var current: MutableCueTrack? = null

        text.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEachIndexed
            val keyword = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (keyword) {
                "REM" -> {
                    Unit
                }

                // Kommentar/Metadaten; ignoriert.
                "FILE" -> {
                    currentFile = extractQuoted(rest)
                }

                "TRACK" -> {
                    sawTrack = true
                    val number = rest.substringBefore(' ').toIntOrNull()
                    val file = currentFile
                    if (number == null || file == null) {
                        return ParsedCueSheet.Malformed("TRACK ohne Nummer oder FILE (Zeile ${lineIndex + 1})")
                    }
                    current?.let { tracks += it }
                    current = MutableCueTrack(number = number, file = file)
                }

                "TITLE" -> {
                    val track = current
                    if (track != null) track.title = extractValue(rest) else albumTitle = extractValue(rest)
                }

                "PERFORMER" -> {
                    val track = current
                    if (track != null) {
                        track.performer = extractValue(rest)
                    } else {
                        albumPerformer = extractValue(rest)
                    }
                }

                "INDEX" -> {
                    val indexNumber = rest.substringBefore(' ').toIntOrNull()
                    val time = parseTime(rest.substringAfter(' ', "").trim())
                    if (indexNumber == 1) {
                        if (time == null) {
                            return ParsedCueSheet.Malformed("Ungueltige INDEX-Zeit (Zeile ${lineIndex + 1})")
                        }
                        current?.startMs = time
                    }
                }

                else -> {
                    Unit
                } // SONGWRITER, FLAGS, ISRC, POSTGAP ... nicht relevant.
            }
        }
        current?.let { tracks += it }

        if (!sawTrack || tracks.isEmpty()) {
            return ParsedCueSheet.Malformed("Kein Track im CUE-Sheet")
        }
        if (tracks.any { it.startMs == null }) {
            return ParsedCueSheet.Malformed("Track ohne INDEX 01")
        }

        val resolved =
            tracks.mapIndexed { index, track ->
                val next = tracks.getOrNull(index + 1)
                // Endzeit nur, wenn der Folgetrack in derselben Datei liegt.
                val endMs = if (next != null && next.file == track.file) next.startMs else null
                CueTrack(
                    number = track.number,
                    title = track.title,
                    performer = track.performer,
                    file = track.file,
                    startMs = track.startMs!!,
                    endMs = endMs,
                )
            }
        return ParsedCueSheet.Success(
            CueSheet(albumTitle = albumTitle, albumPerformer = albumPerformer, tracks = resolved),
        )
    }

    /** MM:SS:FF -> Millisekunden; null bei Formatfehler. */
    private fun parseTime(raw: String): Long? {
        val parts = raw.split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        val frames = parts[2].toLongOrNull() ?: return null
        if (minutes < 0 || seconds !in 0..59 || frames !in 0 until FRAMES_PER_SECOND) return null
        return (minutes * 60 + seconds) * 1000 + frames * 1000 / FRAMES_PER_SECOND
    }

    /** Wert ohne umschliessende Anfuehrungszeichen; sonst der Rohwert. */
    private fun extractValue(raw: String): String? = extractQuoted(raw) ?: raw.takeIf { it.isNotBlank() }

    private fun extractQuoted(raw: String): String? {
        val start = raw.indexOf('"')
        if (start < 0) return null
        val end = raw.indexOf('"', start + 1)
        if (end <= start) return null
        return raw.substring(start + 1, end)
    }

    private class MutableCueTrack(
        val number: Int,
        val file: String,
        var title: String? = null,
        var performer: String? = null,
        var startMs: Long? = null,
    )
}
