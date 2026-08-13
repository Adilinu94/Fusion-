package com.dropsync.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.CrossfadeCurves
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.MixPreset
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MarkerDocumentParser
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.ParsedMarkerDocument
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.settings.AccentColorRepository
import com.dropsync.domain.settings.ThemeSettingsRepository
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Sichtbares Ergebnis eines Importversuchs (Bauplan 6.1/6.4). */
sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object InProgress : ImportUiState

    /** Bericht: hinzugefuegt, aktualisiert, nicht zugeordnet, abgelehnt. */
    data class Done(
        val report: ImportReport,
    ) : ImportUiState

    data class Failed(
        val reason: ImportFailReason,
    ) : ImportUiState
}

enum class ImportFailReason { FILE_TOO_LARGE, UNREADABLE, MALFORMED, STORE_FAILED }

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val markerRepository: MarkerRepository,
        libraryRepository: LibraryRepository,
        private val restMusicSettings: RestMusicSettingsRepository,
        private val themeSettings: ThemeSettingsRepository,
        private val accentColorSettings: AccentColorRepository,
        private val restTimerPreferences: RestTimerPreferencesRepository,
        private val libraryViewPreferences: LibraryViewPreferencesRepository,
        private val audioEngine: AudioEngineRepository,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        /** Nicht zugeordnete Marker fuer die manuelle Zuordnung (Schritt 6.6). */
        val unmatchedMarkers: StateFlow<List<SongMarker>> =
            markerRepository.unmatchedMarkers.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        /**
         * Unbestaetigte Onset-Kandidaten (Marker/Waveform-Plan Phase 5,
         * source = AUTO_DETECTED, isEnabled = false) fuer die Review-Liste:
         * Bestaetigen aktiviert den Marker, Verwerfen loescht ihn — nie
         * Automatik.
         */
        val pendingAutoDetectedMarkers: StateFlow<List<SongMarker>> =
            markerRepository.pendingAutoDetectedMarkers.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        /** Songliste fuer den Zuordnungsdialog (6.6). */
        val songs: StateFlow<List<Song>> =
            libraryRepository.availableSongs.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        /** Verhalten der Musik in Trainingspausen (Musik-Workout-Plan Phase 3). */
        val restMusicBehavior: StateFlow<RestMusicBehavior> =
            restMusicSettings.behavior.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RestMusicBehavior.NORMAL,
            )

        /** Gewaehltes App-Design (Systemdesign folgen / Hell / Dunkel). */
        val themeMode: StateFlow<ThemeMode> =
            themeSettings.themeMode.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ThemeMode.SYSTEM,
            )

        /** Gewaehlte Akzentfarbe (Marken-Lime / Blau); gilt Hell wie Dunkel. */
        val accentColor: StateFlow<AccentColor> =
            accentColorSettings.accentColor.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AccentColor.LIME,
            )

        /** Get-Ready-Vorlauf an/aus (B9). */
        val getReadyEnabled: StateFlow<Boolean> =
            restTimerPreferences.getReadyEnabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false,
            )

        /** Dauer des Get-Ready-Vorlaufs in Sekunden (B9). */
        val getReadySeconds: StateFlow<Int> =
            restTimerPreferences.getReadySeconds.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS,
            )

        /** Bearbeitbare Rest-Schnellwahl in Sekunden (B8). */
        val restPresets: StateFlow<List<Int>> =
            restTimerPreferences.restPresetsSeconds.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS,
            )

        /** Intelligentes Shuffle an/aus (A5). */
        val smartShuffleEnabled: StateFlow<Boolean> =
            libraryViewPreferences.smartShuffleEnabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false,
            )

        /**
         * DSP-Konfiguration fuer den Mix-Uebergaenge-Abschnitt
         * (Mix-Uebergaenge-Plan Phase 3): an/aus = crossfadeSeconds > 0,
         * Preset und Dauer werden direkt in der Konfiguration gehalten.
         */
        val dspConfig: StateFlow<DspConfig> =
            audioEngine.dspConfig.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DspConfig(),
            )

        private val mutableImportState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
        val importState: StateFlow<ImportUiState> = mutableImportState.asStateFlow()

        /**
         * Liest die SAF-Datei (Limit 5 MB, 6.1), parst und importiert
         * transaktional; jeder Fehler laesst die Tabellen unveraendert.
         */
        fun importFrom(uri: Uri) {
            viewModelScope.launch {
                mutableImportState.value = ImportUiState.InProgress
                val text =
                    when (val read = withContext(dispatchers.io) { readLimited(uri) }) {
                        is ReadResult.Ok -> {
                            read.text
                        }

                        ReadResult.TooLarge -> {
                            mutableImportState.value =
                                ImportUiState.Failed(ImportFailReason.FILE_TOO_LARGE)
                            return@launch
                        }

                        ReadResult.Unreadable -> {
                            mutableImportState.value =
                                ImportUiState.Failed(ImportFailReason.UNREADABLE)
                            return@launch
                        }
                    }
                when (val parsed = MarkerDocumentParser.parse(text)) {
                    is ParsedMarkerDocument.Malformed -> {
                        mutableImportState.value = ImportUiState.Failed(ImportFailReason.MALFORMED)
                    }

                    is ParsedMarkerDocument.Success -> {
                        when (
                            val result =
                                markerRepository.importDocument(parsed.schemaVersion, parsed.tracks)
                        ) {
                            is AppResult.Success -> {
                                mutableImportState.value = ImportUiState.Done(result.value)
                            }

                            is AppResult.Failure -> {
                                mutableImportState.value =
                                    ImportUiState.Failed(ImportFailReason.STORE_FAILED)
                            }
                        }
                    }
                }
            }
        }

        /** Manuelle, bestaetigte Zuordnung (6.6). */
        fun linkMarker(
            markerId: Long,
            songId: Long,
        ) {
            viewModelScope.launch { markerRepository.linkManually(markerId, songId) }
        }

        /** Bestaetigt einen AUTO_DETECTED-Kandidaten (Phase 5): isEnabled = true. */
        fun confirmMarker(markerId: Long) {
            viewModelScope.launch { markerRepository.confirmMarker(markerId) }
        }

        /** Verwirft einen Kandidaten endgueltig (Phase 5): loeschen statt behalten. */
        fun discardMarker(markerId: Long) {
            viewModelScope.launch { markerRepository.deleteMarker(markerId) }
        }

        /** Setzt das Pausen-Musik-Verhalten (Musik-Workout-Plan Phase 3). */
        fun setRestMusicBehavior(behavior: RestMusicBehavior) {
            viewModelScope.launch { restMusicSettings.setBehavior(behavior) }
        }

        /** Setzt das App-Design (Systemdesign folgen / Hell / Dunkel). */
        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { themeSettings.setThemeMode(mode) }
        }

        /** Setzt die Akzentfarbe (Marken-Lime / Blau). */
        fun setAccentColor(color: AccentColor) {
            viewModelScope.launch { accentColorSettings.setAccentColor(color) }
        }

        /** Get-Ready-Vorlauf an/aus + Dauer (B9). */
        fun setGetReady(
            enabled: Boolean,
            seconds: Int,
        ) {
            viewModelScope.launch { restTimerPreferences.setGetReady(enabled, seconds) }
        }

        /** Speichert die bearbeitete Rest-Schnellwahl (B8). */
        fun setRestPresets(seconds: List<Int>) {
            viewModelScope.launch { restTimerPreferences.setRestPresetsSeconds(seconds) }
        }

        /** Intelligentes Shuffle an/aus (A5). */
        fun setSmartShuffleEnabled(enabled: Boolean) {
            viewModelScope.launch { libraryViewPreferences.setSmartShuffleEnabled(enabled) }
        }

        /**
         * Mix-Uebergaenge an/aus: aus = Dauer 0 (Bestandsverhalten des
         * Crossfade), an = zuletzt sinnvolle bzw. Standarddauer.
         */
        fun setMixEnabled(enabled: Boolean) {
            viewModelScope.launch {
                val current = audioEngine.dspConfig.first()
                val seconds = if (enabled) DEFAULT_MIX_SECONDS else 0
                audioEngine.updateDspConfig(current.copy(crossfadeSeconds = seconds))
            }
        }

        /** Waehlt das Uebergangs-Preset (Mix-Uebergaenge-Plan Phase 2). */
        fun setMixPreset(preset: MixPreset) {
            viewModelScope.launch {
                val current = audioEngine.dspConfig.first()
                audioEngine.updateDspConfig(current.copy(mixPreset = preset))
            }
        }

        /** Setzt die Uebergangsdauer in Sekunden (1..MAX, 0 nur via Schalter). */
        fun setMixSeconds(seconds: Int) {
            viewModelScope.launch {
                val current = audioEngine.dspConfig.first()
                val clamped = seconds.coerceIn(1, CrossfadeCurves.MAX_SECONDS)
                audioEngine.updateDspConfig(current.copy(crossfadeSeconds = clamped))
            }
        }

        /** Ducking der Pausenmusik in dB (Design Phase 7; -12..0). */
        fun setRestDuckDb(db: Double) {
            viewModelScope.launch {
                val current = audioEngine.dspConfig.first()
                val clamped = db.coerceIn(DspConfig.REST_DUCK_MIN_DB, DspConfig.REST_DUCK_MAX_DB)
                audioEngine.updateDspConfig(current.copy(restDuckDb = clamped))
            }
        }

        fun dismissImportResult() {
            mutableImportState.value = ImportUiState.Idle
        }

        /** Liest hoechstens 5 MB + 1 Byte, ohne die Datei ganz zu halten. */
        private fun readLimited(uri: Uri): ReadResult =
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val limit = MarkerDocumentParser.MAX_DOCUMENT_BYTES.toInt() + 1
                    val bytes = stream.readNBytes(limit)
                    if (bytes.size >= limit) {
                        ReadResult.TooLarge
                    } else {
                        ReadResult.Ok(bytes.decodeToString())
                    }
                } ?: ReadResult.Unreadable
            } catch (e: Exception) {
                ReadResult.Unreadable
            }

        private sealed interface ReadResult {
            data class Ok(
                val text: String,
            ) : ReadResult

            data object TooLarge : ReadResult

            data object Unreadable : ReadResult
        }

        companion object {
            /** Standarddauer beim Einschalten der Mix-Uebergaenge. */
            const val DEFAULT_MIX_SECONDS: Int = 6
        }
    }
