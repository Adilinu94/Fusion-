package com.dropsync.domain.library

/**
 * Ein Eintrag einer M3U/M3U8-Playlist (Plan Phase 3). [location] ist der
 * rohe Pfad oder die URL aus der Datei; [isRemote] markiert Netzquellen
 * (http/https/...), die laut Offline-Grundsatz nur nach ausdruecklicher
 * Bestaetigung abgespielt werden.
 */
data class M3uEntry(
    val location: String,
    val title: String?,
    val durationSeconds: Int?,
    val isRemote: Boolean,
)

/** Ergebnis des M3U-Parsers. */
data class M3uPlaylist(
    val entries: List<M3uEntry>,
)

/**
 * Parser fuer M3U/M3U8-Playlisten (reine JVM-Logik, Plan Phase 3).
 *
 * - `#EXTINF:dauer,Titel` beschreibt den naechsten Eintrag.
 * - Andere `#`-Zeilen (inkl. `#EXTM3U`) sind Kommentare.
 * - Leerzeilen werden uebersprungen.
 * - Netzquellen werden erkannt, aber nicht aufgeloest; lokale relative
 *   Pfade loest [resolveLocal] gegen das Playlist-Verzeichnis auf.
 */
object M3uPlaylistParser {
    private val REMOTE_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    fun parse(text: String): M3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        var pending = Pending()
        for (rawLine in text.lineSequence()) {
            pending = consumeLine(rawLine, pending, entries)
        }
        return M3uPlaylist(entries)
    }

    /** Ueberstand zwischen `#EXTINF` und dem zugehoerigen Eintrag. */
    private data class Pending(
        val title: String? = null,
        val duration: Int? = null,
    )

    /**
     * Verarbeitet eine Zeile und liefert den neuen Ueberstand: Leerzeilen
     * und Kommentare aendern nichts, `#EXTINF` setzt den Ueberstand, alles
     * andere ist ein Eintrag und leert ihn.
     */
    private fun consumeLine(
        rawLine: String,
        pending: Pending,
        entries: MutableList<M3uEntry>,
    ): Pending {
        val line = rawLine.trim().removePrefix("\uFEFF").trim()
        if (line.isEmpty()) return pending
        if (line.startsWith("#")) {
            if (!line.startsWith("#EXTINF:", ignoreCase = true)) return pending
            val payload = line.substringAfter(':', "")
            val durationPart = payload.substringBefore(',').trim()
            return Pending(
                title = payload.substringAfter(',', "").trim().ifBlank { null },
                // Dauer kann negativ (-1) oder mit Attributen versehen sein.
                duration = durationPart.substringBefore(' ').toDoubleOrNull()?.toInt(),
            )
        }
        entries +=
            M3uEntry(
                location = line,
                title = pending.title,
                durationSeconds = pending.duration,
                isRemote = isRemote(line),
            )
        return Pending()
    }

    /** true fuer http/https und andere Netz-Schemata. */
    fun isRemote(location: String): Boolean = REMOTE_SCHEME.containsMatchIn(location.trim())

    /**
     * Loest einen lokalen Eintrag gegen das Verzeichnis [baseDir] der
     * Playlist auf (POSIX-artige String-Logik, ohne Dateizugriff).
     * Absolute Pfade und Netzquellen bleiben unveraendert. `.`/`..`
     * werden normalisiert; Rueckgabe ohne fuehrenden Slash-Doppel.
     */
    fun resolveLocal(
        baseDir: String,
        location: String,
    ): String {
        val trimmed = location.trim().replace('\\', '/')
        // Netzquellen bleiben unveraendert (Normalisierung wuerde // fressen).
        if (isRemote(trimmed)) return location.trim()
        if (trimmed.startsWith('/')) return normalize(trimmed)
        val base = baseDir.trim().replace('\\', '/').trimEnd('/')
        val combined = if (base.isEmpty()) trimmed else "$base/$trimmed"
        return normalize(combined)
    }

    /** Entfernt `.`/`..`-Segmente aus einem Slash-Pfad. */
    private fun normalize(path: String): String {
        val absolute = path.startsWith('/')
        val stack = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    if (stack.isNotEmpty() &&
                        stack.last() != ".."
                    ) {
                        stack.removeLast()
                    } else if (!absolute) {
                        stack.addLast("..")
                    }
                }

                else -> {
                    stack.addLast(segment)
                }
            }
        }
        val joined = stack.joinToString("/")
        return if (absolute) "/$joined" else joined
    }
}
