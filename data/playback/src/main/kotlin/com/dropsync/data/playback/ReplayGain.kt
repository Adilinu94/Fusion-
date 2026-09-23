package com.dropsync.data.playback

/**
 * ReplayGain-Referenz (Befund 2.10): Titel werden auf -18 LUFS
 * normalisiert (ReplayGain 2.0-Praxis, von foobar2000/Poweramp genutzt).
 * Der Gain in dB = Referenz - gemessene integrierte Loudness.
 */
object ReplayGain {
    const val REFERENCE_LUFS: Double = -18.0
}
