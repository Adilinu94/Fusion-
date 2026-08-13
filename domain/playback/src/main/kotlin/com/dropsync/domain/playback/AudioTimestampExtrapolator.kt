package com.dropsync.domain.playback

/**
 * Lese-Port fuer AudioTrack-Timing (Testinfrastruktur-Umbauplan Schritt 2,
 * 5c). Die Extrapolation wird damit in einem reinen JVM-Unit-Test pruefbar;
 * die echte Implementierung nutzt `AudioTrack.getTimestamp()`.
 *
 * Die absolute Latenz ist geraeteabhaengig (selbst bei identischer
 * Playhead-Position kommt Audio auf zwei Geraeten zeitversetzt raus) -
 * deshalb prüfen Tests nur Deltas, nie Absolutwerte.
 */
interface AudioTimestampReader {
    /**
     * `getTimestamp()` liefert in der Warm-up-Phase (erste Sekunden nach
     * `play()`) 0 bzw. nicht-aktualisierte Werte. Solange das false ist,
     * darf der Extrapolator nicht rechnen und faellt auf
     * [playbackHeadPosition] zurueck.
     */
    fun isTimestampValid(): Boolean

    /**
     * Aktuelles `(systemTimeNs, framePosition)`-Paar oder `null`, wenn der
     * Timestamp gerade nicht lesbar ist.
     */
    fun readTimestamp(): AudioTimestamp?

    /** Fallback: Frame-Zaehler der Playhead-Position. */
    fun playbackHeadPosition(): Long
}

/** Ein `(systemTimeNs, framePosition)`-Paar aus `AudioTrack.getTimestamp()`. */
data class AudioTimestamp(
    val systemTimeNs: Long,
    val framePosition: Long,
)

/**
 * Extrapoliert die hoerbare Frame-Position aus dem letzten validen
 * Audio-Timestamp (Design Phase 6):
 *
 *   audibleFrame ~= framePosition + (nowNs - tsNano) * rate
 *
 * - Nur Delta, nie Absolutwert: Der Test nutzt zwei synthetische Paare und
 *   prueft die Extrapolations-Genauigkeit zwischen ihnen, nicht gegen eine
 *   feste Latenz.
 * - Latenz-Zusammensetzung: Der Timestamp enthaelt Mixer + Treiber +
 *   AudioTrack-Buffer. Fuer die Latenz nur unter AudioTrack wird der
 *   Buffer-Anteil ([bufferSizeFrames]) abgezogen.
 * - Warm-up-Gate: Ist der Timestamp invalide (oder nicht lesbar, oder
 *   `nowNs < tsNano`), greift der Fallback auf [AudioTimestampReader.playbackHeadPosition].
 */
class AudioTimestampExtrapolator(
    private val reader: AudioTimestampReader,
    private val sampleRateHz: Int,
    private val nowNs: () -> Long,
    private val bufferSizeFrames: Long = 0,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein: $sampleRateHz" }
        require(bufferSizeFrames >= 0) { "bufferSizeFrames muss >= 0 sein: $bufferSizeFrames" }
    }

    /**
     * Extrapolierte hoerbare Frame-Position oder Fallback auf die
     * Playhead-Position, wenn kein valider Timestamp vorliegt.
     */
    fun audibleFramePosition(): Long {
        if (!reader.isTimestampValid()) return reader.playbackHeadPosition()
        val ts = reader.readTimestamp() ?: return reader.playbackHeadPosition()
        val elapsedNs = nowNs() - ts.systemTimeNs
        // Uhr rueckwaerts oder unplausibel: nicht extrapolieren.
        if (elapsedNs < 0) return reader.playbackHeadPosition()
        val extrapolated = ts.framePosition + elapsedNs * sampleRateHz / NANOS_PER_SECOND
        return (extrapolated - bufferSizeFrames).coerceAtLeast(0)
    }

    companion object {
        const val NANOS_PER_SECOND: Long = 1_000_000_000
    }
}
