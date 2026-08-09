package com.dropsync.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Ordnerauswahl der Bibliothek (Poweramp "Folders and Library", Umbau
 * Punkt 3): jeder bekannte Musikordner ist eine Zeile mit Haken. Angehakt =
 * in der Bibliothek enthalten; abgehakte Ordner landen in der Ausschlussmenge.
 * "Speichern" persistiert die Auswahl und stoesst einen erneuten Einlesevorgang
 * an. Intern gehalten wird die Ausschlussmenge, damit der Default (nichts
 * abgehakt) alle Ordner einschliesst.
 */
@Composable
internal fun SelectFoldersDialog(
    allFolders: List<String>,
    excluded: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Arbeitskopie der Ausschlussmenge; nur beim Speichern uebernommen.
    val working = remember(excluded, allFolders) { mutableStateListOf<String>().apply { addAll(excluded) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_select_folders_title)) },
        text = {
            if (allFolders.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_select_folders_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.library_select_folders_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(allFolders, key = { it }) { folder ->
                            val included = folder !in working
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (included) working.add(folder) else working.remove(folder)
                                        }.heightIn(min = 48.dp)
                                        .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = included,
                                    onCheckedChange = { checked ->
                                        if (checked) working.remove(folder) else working.add(folder)
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = folder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(working.toSet()) }) {
                Text(stringResource(R.string.library_select_folders_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) }
        },
    )
}

/**
 * Kategorie-Sichtbarkeit der Startseite (Poweramp Listenoptionen, Umbau
 * Punkt 3): jede Kategorie ist eine Zeile mit Haken. Umschalten wirkt sofort
 * (persistiert ueber [onToggle]); "Schliessen" beendet nur den Dialog.
 */
@Composable
internal fun CategoryVisibilityDialog(
    hidden: Set<String>,
    onToggle: (LibraryCategory, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_categories_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(LibraryCategory.entries, key = { it.key }) { category ->
                    val visible = category.key !in hidden
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(category, !visible) }
                                .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = visible, onCheckedChange = { onToggle(category, it) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(category.titleRes()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) }
        },
    )
}
