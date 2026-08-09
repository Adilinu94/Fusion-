package com.dropsync.data.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.dropsync.core.model.Song

/**
 * Zugriff auf den Android-Medienindex (Bauplan Schritt 4).
 * Als Interface abstrahiert, damit Repositorytests ohne Android laufen.
 */
interface MediaStoreGateway {
    fun hasAudioPermission(): Boolean

    /** Name der benoetigten Laufzeitberechtigung fuer Fehlermeldungen. */
    fun requiredPermission(): String

    /**
     * Aenderungsstand des Medienindex (Version + Generation). Nur bei
     * Aenderung wird ein Vollscan durchgefuehrt (Schritt 4.3).
     */
    fun currentGeneration(): String

    /** Alle Audiodateien mit positiver Dauer und lesbarer Content-URI. */
    fun queryAudio(): List<Song>
}

class MediaStoreGatewayImpl(
    private val context: Context,
) : MediaStoreGateway {
    override fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    override fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED

    override fun currentGeneration(): String {
        val version = MediaStore.getVersion(context)
        val generation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY).toString()
                } catch (_: IllegalArgumentException) {
                    ""
                }
            } else {
                ""
            }
        return "$version:$generation"
    }

    override fun queryAudio(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection =
            buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.DISPLAY_NAME)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.DATE_MODIFIED)
                add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Audio.Media.GENRE)
                }
                if (hasRelativePath) {
                    add(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.Audio.Media.DATA)
                }
            }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.DURATION} > 0"
        val songs = mutableListOf<Song>()
        context.contentResolver.query(collection, projection, selection, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            // GENRE erst ab Android 11 (API 30) im Audio-Media-Index.
            val genreCol =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                } else {
                    -1
                }
            val pathCol =
                if (hasRelativePath) {
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val rawPath = cursor.getString(pathCol).orEmpty()
                val relativePath =
                    if (hasRelativePath) {
                        rawPath.trimEnd('/')
                    } else {
                        // API 26-28: relative Ordnerangabe aus dem Dateipfad ableiten.
                        rawPath
                            .substringBeforeLast('/', missingDelimiterValue = "")
                            .substringAfter("/storage/emulated/0/", missingDelimiterValue = rawPath)
                            .trimEnd('/')
                    }
                songs +=
                    Song(
                        mediaStoreId = id,
                        contentUri = ContentUris.withAppendedId(collection, id).toString(),
                        displayName = cursor.getString(nameCol).orEmpty(),
                        relativePath = relativePath,
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateModifiedSeconds = cursor.getLong(modifiedCol),
                        title = cursor.getString(titleCol),
                        artist = cursor.getString(artistCol),
                        album = cursor.getString(albumCol),
                        genre = if (genreCol >= 0) cursor.getString(genreCol) else null,
                        isAvailable = true,
                    )
            }
        }
        return songs
    }
}
