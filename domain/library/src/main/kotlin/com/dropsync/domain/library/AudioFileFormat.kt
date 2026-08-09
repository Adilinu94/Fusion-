package com.dropsync.domain.library

/**
 * Katalog der unterstuetzten Audioformate (Plan Phase 3, reine JVM).
 *
 * Einzige Quelle fuer: welche Endungen die App kennt, welche der
 * Android-MediaStore ueblicherweise **nicht** indexiert (und daher der
 * SAF-Ordnerscan finden muss) und welche die selbstgebaute
 * FFmpeg-Extension (ADR-0006) benoetigen.
 */
enum class AudioFileFormat(
    val extensions: List<String>,
    val displayName: String,
    val indexedByMediaStore: Boolean,
    val requiresFfmpegExtension: Boolean,
    val hiResCapable: Boolean,
) {
    MP3(listOf("mp3"), "MP3", indexedByMediaStore = true, requiresFfmpegExtension = false, hiResCapable = false),
    AAC(listOf("aac"), "AAC", indexedByMediaStore = true, requiresFfmpegExtension = false, hiResCapable = false),
    M4A(
        listOf("m4a", "mp4", "m4b"),
        "MP4/M4A",
        indexedByMediaStore = true,
        requiresFfmpegExtension = false,
        hiResCapable = true,
    ),
    FLAC(listOf("flac"), "FLAC", indexedByMediaStore = true, requiresFfmpegExtension = false, hiResCapable = true),
    WAV(listOf("wav"), "WAV", indexedByMediaStore = true, requiresFfmpegExtension = false, hiResCapable = true),
    OGG(
        listOf("ogg", "oga"),
        "Ogg Vorbis",
        indexedByMediaStore = true,
        requiresFfmpegExtension = false,
        hiResCapable = false,
    ),
    OPUS(listOf("opus"), "Opus", indexedByMediaStore = true, requiresFfmpegExtension = false, hiResCapable = false),
    MKA(
        listOf("mka"),
        "Matroska-Audio",
        indexedByMediaStore = true,
        requiresFfmpegExtension = false,
        hiResCapable = true,
    ),
    AIFF(
        listOf("aif", "aiff", "aifc"),
        "AIFF",
        indexedByMediaStore = false,
        requiresFfmpegExtension = true,
        hiResCapable = true,
    ),
    WMA(
        listOf("wma"),
        "Windows Media Audio",
        indexedByMediaStore = false,
        requiresFfmpegExtension = true,
        hiResCapable = false,
    ),
    APE(
        listOf("ape"),
        "Monkey's Audio",
        indexedByMediaStore = false,
        requiresFfmpegExtension = true,
        hiResCapable = true,
    ),
    TAK(listOf("tak"), "TAK", indexedByMediaStore = false, requiresFfmpegExtension = true, hiResCapable = true),
    TTA(listOf("tta"), "True Audio", indexedByMediaStore = false, requiresFfmpegExtension = true, hiResCapable = true),
    DSF(listOf("dsf"), "DSD (DSF)", indexedByMediaStore = false, requiresFfmpegExtension = true, hiResCapable = true),
    DFF(listOf("dff"), "DSD (DFF)", indexedByMediaStore = false, requiresFfmpegExtension = true, hiResCapable = true),
    WAVPACK(listOf("wv"), "WavPack", indexedByMediaStore = false, requiresFfmpegExtension = true, hiResCapable = true),
    ;

    companion object {
        /** Endung der CUE-Sidecar-Datei (kein Audio, sondern Metadaten). */
        const val CUE_EXTENSION: String = "cue"

        /** Endungen gaengiger M3U/M3U8-Playlisten. */
        val PLAYLIST_EXTENSIONS: Set<String> = setOf("m3u", "m3u8")

        private val BY_EXTENSION: Map<String, AudioFileFormat> =
            buildMap {
                for (format in AudioFileFormat.entries) {
                    for (ext in format.extensions) put(ext.lowercase(), format)
                }
            }

        /** Alle bekannten Audioendungen (ohne CUE/Playlist), kleingeschrieben. */
        val allExtensions: Set<String> = BY_EXTENSION.keys

        /**
         * Endungen, die der MediaStore ueblicherweise nicht indexiert und
         * die daher der SAF-Ordnerscan finden muss (Plan Phase 3, Punkt 5).
         */
        val folderScanExtensions: Set<String> =
            AudioFileFormat.entries
                .filterNot { it.indexedByMediaStore }
                .flatMap { it.extensions }
                .map { it.lowercase() }
                .toSet()

        fun fromExtension(extension: String): AudioFileFormat? = BY_EXTENSION[extension.trim().lowercase()]

        /** Format anhand des Dateinamens (letzte Endung); null wenn unbekannt. */
        fun fromFileName(fileName: String): AudioFileFormat? {
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.length - 1) return null
            return fromExtension(fileName.substring(dot + 1))
        }

        fun isCueFile(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").equals(CUE_EXTENSION, ignoreCase = true)

        fun isPlaylistFile(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS
    }
}
