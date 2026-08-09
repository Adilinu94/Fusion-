package com.dropsync.domain.health

/**
 * Reiner Zustandsautomat der Verfuegbarkeit (Herzfrequenz-Plan Phase 1):
 * bildet Providerstatus + Berechtigungs-/Datenlage deterministisch auf
 * [HeartRateAvailability] ab. Die SDK-Statuscodes uebersetzt :data:health
 * in [ProviderState]; dadurch bleibt die Logik ohne SDK JVM-testbar.
 */
object HeartRateAvailabilityResolver {
    /** Vom Data-Layer aus `getSdkStatus` abgeleiteter Providerzustand. */
    enum class ProviderState {
        NOT_AVAILABLE,
        UPDATE_REQUIRED,
        AVAILABLE,
    }

    fun resolve(
        provider: ProviderState,
        permissionGranted: Boolean,
        hasRecentSample: Boolean,
    ): HeartRateAvailability =
        when {
            provider == ProviderState.NOT_AVAILABLE -> HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE
            provider == ProviderState.UPDATE_REQUIRED -> HeartRateAvailability.UPDATE_REQUIRED
            !permissionGranted -> HeartRateAvailability.PERMISSION_REQUIRED
            !hasRecentSample -> HeartRateAvailability.NO_RECENT_DATA
            else -> HeartRateAvailability.READY
        }
}
