package com.dropsync.domain.health

import com.dropsync.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Herzfrequenz-Quelle (Herzfrequenz-Plan 3.2). Daten stammen aus Health
 * Connect (Quelle: Mi Fitness), gelesen wird ausschliesslich lokal und nur
 * im Foreground (Plan 3.4); die App selbst spricht nie mit dem Band.
 *
 * Der Berechtigungsdialog laeuft ueber einen ActivityResultContract, den
 * :data:health unter dem Qualifier [HealthPermissionContract] als
 * generischen `ActivityResultContract<Set<String>, Set<String>>`
 * bereitstellt — dieses Interface bleibt dadurch frei von SDK-Typen.
 */
interface HeartRateSource {
    /** Aktueller Verfuegbarkeits-/Berechtigungszustand. */
    val availability: Flow<HeartRateAvailability>

    /** Letzter bekannter Messwert oder null, solange keiner vorliegt. */
    val latestSample: Flow<HeartRateSample?>

    /** Permission-Strings fuer den Berechtigungs-Launcher. */
    val requiredPermissions: Set<String>

    /** Nach Dialog-Ergebnis oder App-Resume den Zustand neu bestimmen. */
    suspend fun refreshAvailability()

    /**
     * Einmaliges Nachladen: Initial-Read bzw. Changes seit dem
     * persistierten Token; bei abgelaufenem Token faellt die
     * Implementierung auf einen frischen Initial-Read zurueck (Plan 3.3/4).
     */
    suspend fun refresh(): AppResult<Unit>
}

/** Zustaende gemaess Plan 3.2; Aufloesung siehe [HeartRateAvailabilityResolver]. */
enum class HeartRateAvailability {
    /** API < 28 oder Health Connect auf dem Geraet nicht verfuegbar. */
    HEALTH_CONNECT_NOT_AVAILABLE,

    /** Provider installiert, braucht aber ein Update (Play-Store-Link). */
    UPDATE_REQUIRED,

    PERMISSION_REQUIRED,
    NO_RECENT_DATA,
    READY,
}

/** Einzelner Messwert; Zeitstempel als UTC-Epoch-Millis (Bauplan 6). */
data class HeartRateSample(
    val bpm: Int,
    val recordedAtEpochMs: Long,
)
