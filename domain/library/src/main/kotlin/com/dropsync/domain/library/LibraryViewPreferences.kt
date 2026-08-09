package com.dropsync.domain.library

import kotlinx.coroutines.flow.Flow

/**
 * Persistente Konfiguration der Bibliotheksansichten (Plan Phase 6.4:
 * "Bibliotheksansichten konfigurierbar — ein-/ausblendbar, Reihenfolge").
 *
 * Die konkreten Ansichten (Titel/Alben/…) sind ein Praesentationsdetail
 * des Feature-Moduls; :domain:library speichert sie deshalb neutral als
 * Schluessel-Strings (Modulregel 3.2). Das Feature bildet seine Ansichten
 * auf stabile Schluessel ab und gleicht unbekannte Schluessel beim Lesen ab.
 */
data class LibraryViewConfig(
    /** Alle Ansichts-Schluessel in Anzeigereihenfolge. */
    val orderedKeys: List<String>,
    /** Teilmenge von [orderedKeys], die ausgeblendet ist. */
    val hiddenKeys: Set<String>,
)

/** Zugang zur Ansichts-Konfiguration; Implementierung in `:data:library`. */
interface LibraryViewPreferencesRepository {
    /** Aktuelle Konfiguration; null solange der Nutzer nichts angepasst hat. */
    val config: Flow<LibraryViewConfig?>

    /** Persistiert Reihenfolge und ausgeblendete Ansichten. */
    suspend fun setConfig(config: LibraryViewConfig)

    /** Intelligentes Shuffle (Musik-Workout-Plan A5) an/aus; Default aus. */
    val smartShuffleEnabled: Flow<Boolean>

    suspend fun setSmartShuffleEnabled(enabled: Boolean)

    /**
     * Listen-Optionen einer Bibliothekskategorie (Poweramp-Umbau):
     * Sortierung, Richtung und Ansichtsmodus, gespeichert pro
     * Kategorie-Schluessel; null = noch nie angepasst.
     */
    fun listConfig(categoryKey: String): Flow<LibraryListConfig?>

    suspend fun setListConfig(
        categoryKey: String,
        config: LibraryListConfig,
    )
}

/**
 * Persistierte Listen-Optionen einer Kategorie (Poweramp "List Options").
 * Sortier- und Ansichts-Schluessel sind bewusst neutrale Strings
 * (Enum-Namen des Features), analog zu [LibraryViewConfig].
 */
data class LibraryListConfig(
    val sortKey: String,
    val descending: Boolean,
    val viewModeKey: String,
)
