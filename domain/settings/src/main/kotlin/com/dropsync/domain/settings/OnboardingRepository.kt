package com.dropsync.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * First-Run-Status des Onboardings (Ausbauplan B3). Default `false`: Nach
 * Installation/Update ohne Flag sieht der Nutzer einmalig die drei
 * Einstiegsseiten, danach nie wieder.
 */
interface OnboardingRepository {
    /** `true`, sobald das Onboarding abgeschlossen oder uebersprungen wurde. */
    val onboardingSeen: Flow<Boolean>

    /** Markiert das Onboarding als gesehen (nicht mehr anzeigen). */
    suspend fun markOnboardingSeen()
}
