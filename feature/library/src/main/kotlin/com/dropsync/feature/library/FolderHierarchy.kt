package com.dropsync.feature.library

import com.dropsync.domain.library.LibraryFolder

/**
 * Ein Knoten der Ordner-Hierarchie (Poweramp "Folders Hierarchy"). Zeigt die
 * direkten Unterordner eines Pfades und die Titelzahl/-dauer, die im gesamten
 * Teilbaum liegen.
 */
data class FolderNode(
    /** Vollstaendiger relativer Pfad dieses Ordners (z. B. "Music/Rock"). */
    val path: String,
    /** Nur der letzte Pfadabschnitt fuer die Anzeige (z. B. "Rock"). */
    val name: String,
    /** Elternpfad zur Anzeige unter dem Namen (z. B. "Music"); leer bei Wurzel. */
    val parent: String,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/**
 * Baut aus den flachen [LibraryFolder]-Zeilen (jeweils voller relative_path)
 * die direkten Kindordner eines [basePath]. Jeder aggregierte Pfad in der
 * MediaStore-Ausgabe ist ein Blattordner mit Titeln; Zwischenordner entstehen
 * aus den Pfad-Praefixen. Titelzahl und Dauer eines Knotens summieren alle
 * Blattordner in seinem Teilbaum.
 *
 * [basePath] leer = Wurzelebene. Rueckgabe ist alphabetisch nach Name sortiert.
 */
object FolderHierarchy {
    fun childrenOf(
        folders: List<LibraryFolder>,
        basePath: String,
    ): List<FolderNode> {
        val prefix = if (basePath.isEmpty()) "" else basePath.trimEnd('/') + "/"

        // Direkter Kindsegmentname -> aggregierte Zahlen ueber alle Blaetter.
        data class Acc(
            var tracks: Int,
            var duration: Long,
        )
        val children = linkedMapOf<String, Acc>()
        for (folder in folders) {
            val normalized = folder.relativePath.trim('/')
            if (basePath.isNotEmpty() && !("$normalized/").startsWith(prefix)) continue
            val remainder = normalized.removePrefix(prefix.trimEnd('/')).trim('/')
            if (remainder.isEmpty()) continue
            val childSegment = remainder.substringBefore('/')
            val acc = children.getOrPut(childSegment) { Acc(0, 0) }
            acc.tracks += folder.trackCount
            acc.duration += folder.totalDurationMs
        }
        return children
            .map { (segment, acc) ->
                FolderNode(
                    path = if (prefix.isEmpty()) segment else prefix + segment,
                    name = segment,
                    parent = basePath.trim('/'),
                    trackCount = acc.tracks,
                    totalDurationMs = acc.duration,
                )
            }.sortedBy { it.name.lowercase() }
    }

    /**
     * Blattordner (mit direkt enthaltenen Titeln) unter [basePath], deren
     * relative_path exakt [basePath] ist — fuer die Titel-Anzeige beim
     * Drill-down in einen Ordner der Hierarchie.
     */
    fun isLeaf(
        folders: List<LibraryFolder>,
        path: String,
    ): Boolean = folders.any { it.relativePath.trim('/') == path.trim('/') }
}
