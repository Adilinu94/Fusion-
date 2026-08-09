package com.dropsync.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Datei aus einem SAF-Baum; [relativePath] ist der Ordnerpfad im Baum. */
data class SafDocument(
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/**
 * Zugriff auf einen per ACTION_OPEN_DOCUMENT_TREE freigegebenen Ordner
 * (Plan Phase 3). Als Interface abstrahiert, damit Repositorytests ohne
 * Android laufen; die Formatauswahl trifft das Repository.
 */
interface SafFolderGateway {
    /** Alle Dateien unterhalb des Baums (rekursiv, ohne Verzeichnisse). */
    fun listFiles(treeUri: String): List<SafDocument>

    /** Textinhalt eines Dokuments (fuer CUE/M3U); null wenn unlesbar. */
    fun readDocument(documentUri: String): String?
}

class SafFolderGatewayImpl(
    private val context: Context,
) : SafFolderGateway {
    override fun listFiles(treeUri: String): List<SafDocument> {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val results = mutableListOf<SafDocument>()
        // Iterative Tiefensuche; SAF hat keine rekursive Kind-Abfrage.
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(rootId to "")
        while (pending.isNotEmpty()) {
            val (documentId, path) = pending.removeFirst()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            context.contentResolver
                .query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0)
                        val name = cursor.getString(1).orEmpty()
                        val mime = cursor.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val childPath = if (path.isEmpty()) name else "$path/$name"
                            pending.addLast(childId to childPath)
                        } else {
                            results +=
                                SafDocument(
                                    documentUri =
                                        DocumentsContract
                                            .buildDocumentUriUsingTree(tree, childId)
                                            .toString(),
                                    displayName = name,
                                    relativePath = path,
                                    sizeBytes = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                                    lastModifiedMs = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                                )
                        }
                    }
                }
        }
        return results
    }

    override fun readDocument(documentUri: String): String? =
        try {
            context.contentResolver.openInputStream(Uri.parse(documentUri))?.use {
                it.readBytes().decodeToString()
            }
        } catch (e: Exception) {
            null
        }
}
