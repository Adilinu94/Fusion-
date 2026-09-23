package com.dropsync.feature.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.theme.accentSwatchColor
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.audio.MixPreset
import com.dropsync.domain.health.HEALTH_CONNECT_SETTINGS_ACTION
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.sensor.SensorHealth
import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.workout.ExportFormat
import com.dropsync.domain.workout.WorkoutGoalRepository
import java.util.Locale
import kotlin.math.roundToInt

/** UI-Befund 4.2.3: max. angezeigte Import-Verstoesse; Rest als Summenzeile. */
private const val MAX_SHOWN_VIOLATIONS = 5

/**
 * Einstellungen (Schritt 12.2/12.3): Markerimport ueber den
 * SAF-Dateiwaehler (6.1), Bericht mit allen vier Zaehlern (6.4),
 * manuelle Zuordnung nicht zugeordneter Marker (6.6) und
 * Datenschutzueberblick.
 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenAudioSettings: () -> Unit = {},
    onOpenTimer: () -> Unit = {},
    // Befund 7.1.3: Einfuehrung erneut aufrufbar (nicht nur First-Run).
    onOpenOnboarding: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val unmatched by viewModel.unmatchedMarkers.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val restMusicBehavior by viewModel.restMusicBehavior.collectAsStateWithLifecycle()
    // C7 (U-5): Work-/Rest-Zuordnung der DropSync-Sektion.
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val getReadyEnabled by viewModel.getReadyEnabled.collectAsStateWithLifecycle()
    val getReadySeconds by viewModel.getReadySeconds.collectAsStateWithLifecycle()
    val restPresets by viewModel.restPresets.collectAsStateWithLifecycle()
    val smartShuffleEnabled by viewModel.smartShuffleEnabled.collectAsStateWithLifecycle()
    val weeklyTrainingGoal by viewModel.weeklyTrainingGoal.collectAsStateWithLifecycle()
    val dspConfig by viewModel.dspConfig.collectAsStateWithLifecycle()
    val heartRateSyncEnabled by viewModel.heartRateSyncEnabled.collectAsStateWithLifecycle()
    val heartRateAvailability by viewModel.heartRateAvailability.collectAsStateWithLifecycle()
    // P2-17/RC-7: Entwickler-Schalter + Diagnose-Werte (Sensor live, letzter Satz).
    val diagnosticsEnabled by viewModel.diagnosticsEnabled.collectAsStateWithLifecycle()
    val sensorHealth by viewModel.sensorHealth.collectAsStateWithLifecycle()
    val lastSetDiagnostics by viewModel.lastSetDiagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var markerToLink by remember { mutableStateOf<SongMarker?>(null) }

    // SAF-Dateiwaehler (6.1); JSON-Dateien kommen je nach Quelle auch als
    // text/plain oder application/octet-stream an.
    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importFrom)
        }

    // Datenexport (Phase 3): Speicherort vom Nutzer waehlen lassen (SAF),
    // keine Speicher-Permission noetig.
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val createJson =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { viewModel.exportTo(it, ExportFormat.JSON) }
        }
    val createCsv =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { viewModel.exportTo(it, ExportFormat.CSV) }
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsScreenTitle()
        }
        // Befund 7.1.3: Einfuehrung erneut ansehen (First-Run ist vorbei).
        item {
            TextButton(onClick = onOpenOnboarding) {
                Text(stringResource(R.string.settings_replay_onboarding))
            }
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_rest_music_section))
        }
        item {
            Text(
                text = stringResource(R.string.settings_rest_music_desc),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(RestMusicBehavior.entries, key = { it.name }) { option ->
            RestMusicOption(
                option = option,
                selected = option == restMusicBehavior,
                onSelect = { viewModel.setRestMusicBehavior(option) },
            )
        }
        item {
            // Phase 7: Duck-Regler fuer die Pausenmusik (-12..0 dB, Default -8).
            RestDuckSection(
                restDuckDb = dspConfig.restDuckDb,
                onSetRestDuckDb = viewModel::setRestDuckDb,
            )
        }
        item {
            // C7 (U-5): eigene DropSync-Sektion — Work-/Rest-Playlist und
            // der Hinweis auf die automatischen Drop-Vorschlaege.
            DropSyncPlaylistsSection(
                playlists = playlists,
                onSetLabel = viewModel::setPlaylistLabel,
            )
        }
        item {
            // Phase 6: dezenter Timing-Hinweis - die App verspricht keine
            // Millisekunden, das Timing passt sich der Route an.
            Text(
                text = stringResource(R.string.settings_timing_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            WorkoutExtrasSection(
                getReadyEnabled = getReadyEnabled,
                getReadySeconds = getReadySeconds,
                onSetGetReady = viewModel::setGetReady,
                presets = restPresets,
                onSetPresets = viewModel::setRestPresets,
                smartShuffleEnabled = smartShuffleEnabled,
                onSetSmartShuffle = viewModel::setSmartShuffleEnabled,
            )
        }
        item {
            // B2: eigenstaendiger Timer-Einstieg (kein fuenfter Tab) — gleiche
            // Route wie aus der Train-Pause, gleiche geteilte Engine.
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_timer_entry)) },
                supportingContent = { Text(stringResource(R.string.settings_timer_entry_desc)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenTimer),
            )
        }
        item {
            // B5: Health-Connect nach offizieller UX-Vorgabe (Sync-Toggle,
            // Manage-Zugriff, Hinweis bei fehlender Berechtigung).
            HealthSyncSection(
                syncEnabled = heartRateSyncEnabled,
                availability = heartRateAvailability,
                onSetSyncEnabled = viewModel::setHeartRateSyncEnabled,
                onManageAccess = {
                    openHealthSettings(context)
                    viewModel.refreshHeartRateAvailability()
                },
            )
        }
        item {
            // Wochenziel (Flowtimer-Integration Schritt 7): traegt den
            // Wochenring des Progress-Dashboards (UI-Vertrag R2).
            WeeklyGoalSection(
                weeklyGoal = weeklyTrainingGoal,
                onSetGoal = viewModel::setWeeklyTrainingGoal,
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_appearance_section))
        }
        items(ThemeMode.entries, key = { it.name }) { option ->
            ThemeModeOption(
                option = option,
                selected = option == themeMode,
                onSelect = { viewModel.setThemeMode(option) },
            )
        }
        item {
            AccentColorSection(
                selected = accentColor,
                onSelect = viewModel::setAccentColor,
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_audio_section))
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_audio_entry)) },
                supportingContent = { Text(stringResource(R.string.settings_audio_entry_desc)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenAudioSettings),
            )
        }
        item {
            MixTransitionsSection(
                crossfadeSeconds = dspConfig.crossfadeSeconds,
                preset = dspConfig.mixPreset,
                bitPerfectEnabled = dspConfig.bitPerfectEnabled,
                onSetEnabled = viewModel::setMixEnabled,
                onSetPreset = viewModel::setMixPreset,
                onSetSeconds = viewModel::setMixSeconds,
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_data_section))
        }
        item {
            Text(
                text = stringResource(R.string.settings_data_desc),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            FlowRepPrimaryButton(
                text = stringResource(R.string.settings_export_json),
                onClick = { createJson.launch("flowrep-training.json") },
                enabled = exportState != SettingsViewModel.ExportUiState.InProgress,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            FlowRepPrimaryButton(
                text = stringResource(R.string.settings_export_csv),
                onClick = { createCsv.launch("flowrep-training.csv") },
                enabled = exportState != SettingsViewModel.ExportUiState.InProgress,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            ExportResultText(exportState)
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_markers_section))
        }
        item {
            FlowRepPrimaryButton(
                text = stringResource(R.string.settings_import_button),
                onClick = {
                    openDocument.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                },
                enabled = importState != ImportUiState.InProgress,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item { ImportResultText(importState) }
        // UI-Befund 4.2.3: Verstoesse einzeln zeigen, nicht nur als Zahl.
        val doneImport = importState as? ImportUiState.Done
        if (doneImport != null && doneImport.report.wasRejected) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    for (violation in doneImport.report.rejectedViolations.take(MAX_SHOWN_VIOLATIONS)) {
                        Text(
                            text =
                                buildString {
                                    append("• ")
                                    violation.trackDisplayName?.let {
                                        append(it)
                                        append(": ")
                                    }
                                    append(violation.reason)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    val remaining = doneImport.report.rejectedViolations.size - MAX_SHOWN_VIOLATIONS
                    if (remaining > 0) {
                        Text(
                            text = stringResource(R.string.settings_import_more_violations, remaining),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.settings_unmatched_markers,
                        unmatched.size,
                        unmatched.size,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(unmatched, key = { it.id }) { marker ->
            ListItem(
                headlineContent = { Text(marker.label) },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.settings_marker_position,
                            marker.positionMs / 1000,
                        ),
                    )
                },
                trailingContent = {
                    TextButton(
                        onClick = { markerToLink = marker },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.settings_link_marker))
                    }
                },
            )
        }
        item {
            SettingsSectionTitle(stringResource(R.string.settings_privacy_section))
        }
        item {
            Text(
                text = stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            // P2-17/RC-7: Entwickler-Bereich. Der Schalter ist immer
            // sichtbar, die Werte erst nach dem Aktivieren (Design 8.4).
            SettingsSectionTitle(stringResource(R.string.settings_developer_section))
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_developer_diagnostics)) },
                supportingContent = { Text(stringResource(R.string.settings_developer_diagnostics_desc)) },
                trailingContent = {
                    Switch(
                        checked = diagnosticsEnabled,
                        onCheckedChange = viewModel::setDiagnosticsEnabled,
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            )
        }
        if (diagnosticsEnabled) {
            item {
                DiagnosticsSensorSection(sensorHealth)
            }
            item {
                DiagnosticsLastSetSection(lastSetDiagnostics)
            }
        }
    }

    markerToLink?.let { marker ->
        LinkMarkerDialog(
            marker = marker,
            songs = songs,
            onConfirm = { songId ->
                viewModel.linkMarker(marker.id, songId)
                markerToLink = null
            },
            onDismiss = { markerToLink = null },
        )
    }
}

/** Wochenziel-Auswahl 1..7 Trainingstage (Schritt 7): FilterChips ohne Deko. */
@Composable
private fun WeeklyGoalSection(
    weeklyGoal: Int,
    onSetGoal: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.settings_weekly_goal),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (day in WorkoutGoalRepository.MIN_WEEKLY_GOAL..WorkoutGoalRepository.MAX_WEEKLY_GOAL) {
                val dayDescription =
                    pluralStringResource(R.plurals.settings_weekly_goal_days, day, day)
                FilterChip(
                    selected = day == weeklyGoal,
                    onClick = { onSetGoal(day) },
                    label = { Text(day.toString()) },
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = dayDescription },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreenTitle() {
    Text(
        text = stringResource(R.string.settings_screen_title),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
    )
}

@Composable
private fun SettingsSectionTitle(title: String) {
    FlowRepSectionHeader(
        title = title,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
    )
}

/** Klartextbericht des letzten Imports (6.4); Fehler nennen den Grund. */
@Composable
private fun ImportResultText(state: ImportUiState) {
    val text =
        when (state) {
            ImportUiState.Idle -> {
                return
            }

            ImportUiState.InProgress -> {
                stringResource(R.string.settings_import_running)
            }

            is ImportUiState.Done -> {
                if (state.report.wasRejected) {
                    stringResource(
                        R.string.settings_import_rejected,
                        state.report.rejectedViolations.size,
                    )
                } else {
                    stringResource(
                        R.string.settings_import_report,
                        state.report.added,
                        state.report.updated,
                        state.report.unmatched,
                    )
                }
            }

            is ImportUiState.Failed -> {
                when (state.reason) {
                    ImportFailReason.FILE_TOO_LARGE -> {
                        stringResource(R.string.settings_import_too_large)
                    }

                    ImportFailReason.UNREADABLE -> {
                        stringResource(R.string.settings_import_unreadable)
                    }

                    ImportFailReason.MALFORMED -> {
                        stringResource(R.string.settings_import_malformed)
                    }

                    ImportFailReason.STORE_FAILED -> {
                        stringResource(R.string.settings_import_store_failed)
                    }
                }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Sichtbares Ergebnis des Datenexports (Phase 3). */
@Composable
private fun ExportResultText(state: SettingsViewModel.ExportUiState) {
    val text =
        when (state) {
            SettingsViewModel.ExportUiState.Idle -> {
                return
            }

            SettingsViewModel.ExportUiState.InProgress -> {
                stringResource(R.string.settings_export_running)
            }

            is SettingsViewModel.ExportUiState.Done -> {
                stringResource(R.string.settings_export_done, state.setCount)
            }

            is SettingsViewModel.ExportUiState.Failed -> {
                when (state.reason) {
                    SettingsViewModel.ExportFailReason.NOTHING_TO_EXPORT -> {
                        stringResource(R.string.settings_export_nothing)
                    }

                    SettingsViewModel.ExportFailReason.WRITE_FAILED -> {
                        stringResource(R.string.settings_export_failed)
                    }
                }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Manuelle Zuordnung (6.6): Der Nutzer waehlt den Zielsong explizit;
 * die App raetselt nie selbst (5.1).
 */
@Composable
private fun LinkMarkerDialog(
    marker: SongMarker,
    songs: List<Song>,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_link_dialog_title, marker.label)) },
        text = {
            if (songs.isEmpty()) {
                Text(stringResource(R.string.settings_link_no_songs))
            } else {
                // Fontscale-Adaption (7.2): Kappe skaliert mit der Schrift.
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp * LocalDensity.current.fontScale),
                ) {
                    items(songs, key = { it.mediaStoreId }) { song ->
                        ListItem(
                            headlineContent = { Text(song.displayName) },
                            supportingContent = { song.artist?.let { Text(it) } },
                            trailingContent = {
                                TextButton(
                                    onClick = { onConfirm(song.mediaStoreId) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.settings_link_confirm))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_link_cancel))
            }
        },
    )
}

/**
 * Trainings-Extras (Musik-Workout-Plan Phase 6): Get-Ready-Vorlauf (B9),
 * bearbeitbare Rest-Schnellwahl (B8) und intelligentes Shuffle (A5).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutExtrasSection(
    getReadyEnabled: Boolean,
    getReadySeconds: Int,
    onSetGetReady: (Boolean, Int) -> Unit,
    presets: List<Int>,
    onSetPresets: (List<Int>) -> Unit,
    smartShuffleEnabled: Boolean,
    onSetSmartShuffle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_extras_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Get-Ready 3-2-1 (B9): optionaler Vorlauf vor dem Rest-Countdown.
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_get_ready_title)) },
            supportingContent = { Text(stringResource(R.string.settings_get_ready_desc)) },
            trailingContent = {
                Switch(
                    checked = getReadyEnabled,
                    onCheckedChange = { onSetGetReady(it, getReadySeconds) },
                )
            },
        )
        if (getReadyEnabled) {
            Text(
                text = stringResource(R.string.settings_get_ready_seconds, getReadySeconds),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = getReadySeconds.toFloat(),
                onValueChange = { onSetGetReady(true, it.roundToInt()) },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Bearbeitbare Rest-Schnellwahl (B8): waehlbare Sekunden-Chips.
        Text(
            text = stringResource(R.string.settings_presets_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(R.string.settings_presets_desc),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PRESET_CHOICES.forEach { choice ->
                val selected = choice in presets
                FilterChip(
                    selected = selected,
                    onClick = {
                        val next = if (selected) presets - choice else presets + choice
                        onSetPresets(next.distinct().sorted())
                    },
                    label = { Text(stringResource(R.string.settings_seconds_format, choice)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Intelligentes Shuffle (A5): gewichtet ueber play_stats/Favoriten.
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_smart_shuffle_title)) },
            supportingContent = { Text(stringResource(R.string.settings_smart_shuffle_desc)) },
            trailingContent = {
                Switch(checked = smartShuffleEnabled, onCheckedChange = onSetSmartShuffle)
            },
        )
    }
}

/** Waehlbare Sekundenwerte fuer die Rest-Schnellwahl (B8). */
private val PRESET_CHOICES: List<Int> = listOf(30, 45, 60, 75, 90, 120, 150, 180, 240, 300)

/**
 * Health-Connect-Abschnitt (B5, offizielle UX-Vorgabe): Sync-Schalter zum
 * Pausieren/Fortsetzen, Statuszeile je Verfuegbarkeit, Hinweis bei fehlender
 * Berechtigung und „Zugriff verwalten"-Button in die Health-Einstellungen.
 */
@Composable
private fun HealthSyncSection(
    syncEnabled: Boolean,
    availability: HeartRateAvailability,
    onSetSyncEnabled: (Boolean) -> Unit,
    onManageAccess: () -> Unit,
) {
    // C5 (U-8): Der Sync-Schalter nennt sein Ziel (vorher nur "an/aus").
    val syncTitle = stringResource(R.string.settings_health_sync_title)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_health_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(healthStatusRes(availability)),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_health_sync_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_health_sync_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // C5 (U-8): Der Schalter nennt sein Ziel, nicht nur "an/aus".
            Switch(
                checked = syncEnabled,
                onCheckedChange = onSetSyncEnabled,
                modifier =
                    Modifier.semantics {
                        contentDescription = syncTitle
                    },
            )
        }
        if (availability == HeartRateAvailability.PERMISSION_REQUIRED) {
            Text(
                text = stringResource(R.string.settings_health_permission_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        TextButton(onClick = onManageAccess) {
            Text(stringResource(R.string.settings_health_manage))
        }
    }
}

private fun healthStatusRes(availability: HeartRateAvailability): Int =
    when (availability) {
        HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE -> R.string.settings_health_status_unavailable
        HeartRateAvailability.UPDATE_REQUIRED -> R.string.settings_health_status_update
        HeartRateAvailability.PERMISSION_REQUIRED -> R.string.settings_health_status_permission
        HeartRateAvailability.NO_RECENT_DATA -> R.string.settings_health_status_nodata
        HeartRateAvailability.READY -> R.string.settings_health_status_ready
    }

/**
 * Oeffnet die Health-Connect-Einstellungen („Manage access", B5). Nur wenn
 * das System die Aktion aufloest — aeltere Geraete kennen sie nicht, dort
 * passiert still nichts statt abzustuerzen.
 */
private fun openHealthSettings(context: Context) {
    val intent = Intent(HEALTH_CONNECT_SETTINGS_ACTION)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

/**
 * Mix-Uebergaenge (Mix-Uebergaenge-Plan Phase 3): automatischer
 * Uebergang zwischen Titeln mit waehlbarem Preset und Dauer. An/aus
 * entspricht Crossfade-Dauer > 0; bei Bit-Perfect (ADR-0009) ist der
 * Crossfade technisch deaktiviert, der Abschnitt weist darauf hin.
 *
 * B-AUD-5 (Weg b, Nutzerentscheidung): Die Bedienung ist ausgegraut — die
 * Kurven haben keinen Konsumenten, Uebergaenge laufen als harter Wechsel.
 * Stil und Dauer bleiben persistiert und sichtbar, damit sie greifen, sobald
 * der Konsument existiert; der Hinweis steht immer da, nicht nur im
 * Bit-Perfect-Fall.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MixTransitionsSection(
    crossfadeSeconds: Int,
    preset: MixPreset,
    bitPerfectEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetPreset: (MixPreset) -> Unit,
    onSetSeconds: (Int) -> Unit,
) {
    val enabled = crossfadeSeconds > 0
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_mix_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_mix_toggle_title)) },
            supportingContent = { Text(stringResource(R.string.settings_mix_toggle_desc)) },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = onSetEnabled,
                    // B-AUD-5 (Weg b): kein Konsument — Schalter ausgegraut.
                    enabled = false,
                )
            },
        )
        Text(
            text = stringResource(R.string.settings_mix_no_effect),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (bitPerfectEnabled) {
            Text(
                text = stringResource(R.string.settings_mix_bit_perfect_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (enabled && !bitPerfectEnabled) {
            Text(
                text = stringResource(R.string.settings_mix_preset_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MixPreset.entries.forEach { option ->
                    FilterChip(
                        selected = option == preset,
                        onClick = { onSetPreset(option) },
                        label = { Text(stringResource(option.labelRes())) },
                        // B-AUD-5 (Weg b): kein Konsument — Chips ausgegraut.
                        enabled = false,
                        // A5: 48-dp-Mindesthoehe fuers Touch-Ziel.
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                text = stringResource(preset.descRes()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_mix_duration, crossfadeSeconds),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = crossfadeSeconds.toFloat(),
                onValueChange = { onSetSeconds(it.roundToInt()) },
                valueRange = 1f..12f,
                steps = 10,
                modifier = Modifier.padding(horizontal = 16.dp),
                // B-AUD-5 (Weg b): kein Konsument — Regler ausgegraut.
                enabled = false,
            )
        }
    }
}

private fun MixPreset.labelRes(): Int =
    when (this) {
        MixPreset.FADE -> R.string.settings_mix_preset_fade
        MixPreset.RISE -> R.string.settings_mix_preset_rise
        MixPreset.BLEND -> R.string.settings_mix_preset_blend
        MixPreset.WAVE -> R.string.settings_mix_preset_wave
        MixPreset.MELT -> R.string.settings_mix_preset_melt
        MixPreset.SLAM -> R.string.settings_mix_preset_slam
    }

private fun MixPreset.descRes(): Int =
    when (this) {
        MixPreset.FADE -> R.string.settings_mix_preset_fade_desc
        MixPreset.RISE -> R.string.settings_mix_preset_rise_desc
        MixPreset.BLEND -> R.string.settings_mix_preset_blend_desc
        MixPreset.WAVE -> R.string.settings_mix_preset_wave_desc
        MixPreset.MELT -> R.string.settings_mix_preset_melt_desc
        MixPreset.SLAM -> R.string.settings_mix_preset_slam_desc
    }

/**
 * Eine Auswahl des Pausen-Musik-Verhaltens (Musik-Workout-Plan Phase 3):
 * Radio-Knopf, Titel und Erklaertext. NORMAL = Aus (Shuffle laeuft weiter).
 */
@Composable
private fun RestMusicOption(
    option: RestMusicBehavior,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(option.titleRes())) },
        supportingContent = { Text(stringResource(option.descRes())) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onSelect),
    )
}

private fun RestMusicBehavior.titleRes(): Int =
    when (this) {
        RestMusicBehavior.NORMAL -> R.string.settings_rest_music_normal
        RestMusicBehavior.REST_PLAYLIST -> R.string.settings_rest_music_rest_playlist
        RestMusicBehavior.DROP_LANDING -> R.string.settings_rest_music_drop_landing
    }

private fun RestMusicBehavior.descRes(): Int =
    when (this) {
        RestMusicBehavior.NORMAL -> R.string.settings_rest_music_normal_desc
        RestMusicBehavior.REST_PLAYLIST -> R.string.settings_rest_music_rest_playlist_desc
        RestMusicBehavior.DROP_LANDING -> R.string.settings_rest_music_drop_landing_desc
    }

/**
 * C7 (U-5): DropSync-Sektion — Work-/Rest-Playlist zuordnen. Die Zuordnung
 * ist dieselbe wie in der Bibliothek (eine Wahrheit); neue Titel bekommen
 * ihre Drop-Vorschlaege automatisch (A10), deshalb nur ein Hinweis.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DropSyncPlaylistsSection(
    playlists: List<Playlist>,
    onSetLabel: (Long, PlaylistLabel?) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingsSectionTitle(stringResource(R.string.settings_dropsync_section))
        Text(
            text = stringResource(R.string.settings_dropsync_playlists_desc),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (playlists.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_dropsync_no_playlists),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            playlists.forEach { playlist ->
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(text = playlist.name, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        FilterChip(
                            selected = playlist.label == null,
                            onClick = { onSetLabel(playlist.id, null) },
                            label = { Text(stringResource(R.string.settings_dropsync_label_none)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = playlist.label == PlaylistLabel.WORK,
                            onClick = { onSetLabel(playlist.id, PlaylistLabel.WORK) },
                            label = { Text(stringResource(R.string.settings_dropsync_label_work)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = playlist.label == PlaylistLabel.REST,
                            onClick = { onSetLabel(playlist.id, PlaylistLabel.REST) },
                            label = { Text(stringResource(R.string.settings_dropsync_label_rest)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_dropsync_auto_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Duck-Regler fuer die Pausenmusik (Design Phase 7): -12..0 dB in
 * 2-dB-Schritten, Default -8. Der Wert ist Teil der DSP-Konfiguration
 * und wirkt als Preamp-Absenkung waehrend der Pause (nie doppelt mit
 * dem Cue-Ducking, der staerkere Wert gewinnt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestDuckSection(
    restDuckDb: Double,
    onSetRestDuckDb: (Double) -> Unit,
) {
    val steps = listOf(0.0, -2.0, -4.0, -6.0, -8.0, -10.0, -12.0)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_rest_duck_title, restDuckDb.roundToInt()),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.settings_rest_duck_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        // C7 (U-5): FlowRow statt Row — die Chips laufen bei grosser
        // Systemschrift nicht mehr aus dem Bild.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            steps.forEach { value ->
                FilterChip(
                    selected = restDuckDb == value,
                    onClick = { onSetRestDuckDb(value) },
                    label = { Text("${value.roundToInt()} dB") },
                    // A5: 48-dp-Mindesthoehe fuers Touch-Ziel.
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

/**
 * Eine Auswahl des App-Designs (Darstellung): Radio-Knopf, Titel und
 * Erklaertext. SYSTEM folgt dem hellen/dunklen Systemdesign.
 */
@Composable
private fun ThemeModeOption(
    option: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(option.titleRes())) },
        supportingContent = { Text(stringResource(option.descRes())) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onSelect),
    )
}

private fun ThemeMode.titleRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private fun ThemeMode.descRes(): Int =
    when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system_desc
        ThemeMode.LIGHT -> R.string.settings_theme_light_desc
        ThemeMode.DARK -> R.string.settings_theme_dark_desc
    }

// Auswaehlbare Akzentfarben als Farbkreise (C1: Tokens aus dem Theme, keine
// eigenen Hex-Werte).
private val AccentLimeSwatch = accentSwatchColor(AccentColor.LIME)
private val AccentBlueSwatch = accentSwatchColor(AccentColor.BLUE)

/**
 * Akzentfarb-Auswahl (Darstellung): Farbkreise fuer Marken-Lime und Blau.
 * Die gewaehlte Farbe traegt einen kraeftigen Kontrastring; die Auswahl
 * gilt gleichermassen in Hell und Dunkel.
 */
@Composable
private fun AccentColorSection(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_accent_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.settings_accent_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentSwatch(
                color = AccentLimeSwatch,
                selected = selected == AccentColor.LIME,
                label = stringResource(R.string.settings_accent_lime),
                onClick = { onSelect(AccentColor.LIME) },
            )
            AccentSwatch(
                color = AccentBlueSwatch,
                selected = selected == AccentColor.BLUE,
                label = stringResource(R.string.settings_accent_blue),
                onClick = { onSelect(AccentColor.BLUE) },
            )
        }
    }
}

/** Ein Farbkreis der Akzentauswahl; ausgewaehlt = dickerer Kontrastring. */
@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val ring =
        if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outline
        }
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = label
                    this.selected = selected
                },
    )
}

/** P2-17/RC-7: Platzhalter fuer nicht verfuegbare Diagnosewerte. */
private const val DIAGNOSTICS_DASH = "-"

/**
 * P2-17/RC-7: eine Zeile des Diagnose-Panels (Label links, Wert rechts).
 * Technische Werte bleiben bewusst technisch (Enum-Namen) — das Panel ist
 * ein Entwickler-Werkzeug, keine Nutzeroberflaeche.
 */
@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * P2-17/RC-7: Live-Werte der Sensorstrecke (Transport, MTU, Drops, Gaps).
 * Die Daten kommen aus dem Sensor-Provider-Health-Flow; ohne verbundenen
 * Chip steht der Fake-Transport mit Nullwerten.
 */
@Composable
private fun DiagnosticsSensorSection(health: SensorHealth) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_diag_sensor_title),
            style = MaterialTheme.typography.titleSmall,
        )
        DiagnosticRow(stringResource(R.string.settings_diag_connection), health.connectionState.name)
        DiagnosticRow(stringResource(R.string.settings_diag_transport), health.transport.name)
        DiagnosticRow(
            stringResource(R.string.settings_diag_mtu),
            health.negotiatedMtu?.let { "$it B" } ?: DIAGNOSTICS_DASH,
        )
        DiagnosticRow(stringResource(R.string.settings_diag_quality), health.quality.name)
        DiagnosticRow(stringResource(R.string.settings_diag_batches), health.receivedBatches.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_duplicates), health.duplicateBatches.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_missed), health.missedBatches.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_parse_errors), health.parseErrors.toString())
        DiagnosticRow(
            stringResource(R.string.settings_diag_device_event_poll_errors),
            health.deviceEventPollErrors.toString(),
        )
        DiagnosticRow(stringResource(R.string.settings_diag_jitter_drops), health.jitterBufferDrops.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_sample_drops), health.samplesDropped.toString())
        DiagnosticRow(
            stringResource(R.string.settings_diag_largest_gap),
            // A3/S-2: die Qualitaet folgt dem Gap im Fenster (~5 s); der
            // kumulative Wert steht im Satz-Report, nicht hier.
            stringResource(R.string.settings_diag_ms, health.largestRecentGapMs),
        )
    }
}

/**
 * P2-17/RC-7/RC-17: Diagnose des letzten gestoppten Satzes (Rate, Gaps,
 * ZUPT, klassifizierte Ablehnungen, Plausibilitaet). Leer, solange in dieser
 * App-Sitzung kein Satz gestoppt wurde.
 */
@Composable
private fun DiagnosticsLastSetSection(report: SetDiagnostics?) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_diag_last_set_title),
            style = MaterialTheme.typography.titleSmall,
        )
        if (report == null) {
            Text(
                text = stringResource(R.string.settings_diag_last_set_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        DiagnosticRow(stringResource(R.string.settings_diag_counted), report.countedReps.toString())
        DiagnosticRow(
            stringResource(R.string.settings_diag_rate),
            String.format(Locale.ROOT, "%.1f Hz", report.measuredSampleRateHz),
        )
        DiagnosticRow(stringResource(R.string.settings_diag_quality), report.signalQuality.name)
        DiagnosticRow(
            stringResource(R.string.settings_diag_frames),
            "${report.framesProcessed} / ${report.framesRejected}",
        )
        DiagnosticRow(stringResource(R.string.settings_diag_gaps), report.largeGapCount.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_zupt_updates), report.zuptBiasUpdates.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_zupt_aborted), report.zuptAbortedPending.toString())
        DiagnosticRow(stringResource(R.string.settings_diag_rejections), report.totalRejections.toString())
        // RC-17: Mechanismus-Zerlegung — haeufigster Grund zuerst.
        for ((reason, count) in report.rejectionCounts.entries.sortedByDescending { it.value }) {
            if (count > 0) {
                DiagnosticRow(label = "  ${reason.name}", value = count.toString())
            }
        }
        DiagnosticRow(
            stringResource(R.string.settings_diag_plausibility),
            report.plausibility?.verdict?.name ?: DIAGNOSTICS_DASH,
        )
    }
}
