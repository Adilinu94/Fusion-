package com.dropsync.domain.playback

/**
 * Aktiviert/deaktiviert das Pausenmusik-Ducking (Design Phase 7).
 * Der Coordinator schaltet es bei Pausenbeginn ein und am Pausenende
 * bzw. bei manueller Uebernahme aus; der konkrete dB-Wert kommt aus
 * der DSP-Konfiguration (`DspConfig.restDuckDb`).
 */
interface RestDuckingGate {
    /** Pausenmusik ducken (Rampe); [active] = false stellt 0 dB wieder her. */
    suspend fun setActive(active: Boolean)
}
